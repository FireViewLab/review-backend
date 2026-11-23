package com.example.fireview.domain.chat.service;

import com.example.fireview.domain.cartschedule.ScheduleRequest;
import com.example.fireview.domain.cartschedule.dto.ScheduleProposal;
import com.example.fireview.domain.cartschedule.service.CartScheduleService;
import com.example.fireview.domain.repurchase.RepurchaseCandidate;
import com.example.fireview.domain.repurchase.RepurchaseFinder;
import com.example.fireview.domain.chat.client.LlmClient;
import com.example.fireview.domain.chat.entity.ChatMessage;
import com.example.fireview.domain.chat.entity.ChatRole;
import com.example.fireview.domain.chat.entity.ChatSession;
import com.example.fireview.domain.chat.entity.ChatTier;
import com.example.fireview.domain.chat.port.ProductAnalysisContext;
import com.example.fireview.domain.chat.port.ProductAnalysisPort;
import com.example.fireview.domain.chat.repository.ChatMessageRepository;
import com.example.fireview.domain.chat.repository.ChatSessionRepository;
import com.example.fireview.domain.user.entity.PlanTier;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.service.UserService;
import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 챗봇 대화 오케스트레이션.
 *
 * 흐름: 요금제 검사 → 세이프가드 1계층 → 쿼터 차감 → 컨텍스트 조회
 *      → 프롬프트 조립 → LLM 호출 → 구조화 응답 파싱(3계층)
 *      → 세이프가드 4계층 → 저장 → (커밋 후) 비슷한 상품 추천(DB 조회만)
 *
 * <p><b>쿼터를 차감하는 시점</b>이 중요하다. 세이프가드 1계층에서 막힌 질문은
 * LLM 을 부르지 않아 비용이 0 이므로 사용량으로 세지 않는다. 반대로 LLM 을
 * 부른 뒤 주제 이탈로 막힌 턴은 토큰을 이미 썼으므로 사용량에 포함한다.
 *
 * <p><b>트랜잭션 경계</b>: {@link #ask} 자체는 트랜잭션이 없다. 대화 저장까지는
 * {@link TransactionOperations} 로 묶어 커밋하고, 커넥션을 돌려준 뒤에 추천을 조회한다.
 * 대화 트랜잭션 안에서 추천을 따로 조회하면 바깥 커넥션을 쥔 채 두 번째 커넥션을
 * 기다리게 되어 공유 커넥션 풀이 고갈될 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ProductAnalysisPort productAnalysisPort;
    private final PromptAssembler promptAssembler;
    private final TopicGuard topicGuard;
    private final LlmClient llmClient;
    private final UserService userService;
    private final ChatPlanPolicy planPolicy;
    private final ChatQuotaStore quotaStore;
    private final ChatRecommendationService recommendationService;
    private final TransactionOperations transactionOperations;
    private final CartScheduleService cartScheduleService;
    private final RepurchaseFinder repurchaseFinder;
    private final Clock clock;

    /** 프롬프트에 싣는 반복 담기 후보 수. 후보가 있는 사용자만 턴당 약 150토큰이 는다 */
    static final int REPURCHASE_PROMPT_LIMIT = 5;

    /**
     * @param tier      호출된 엔드포인트의 등급. 요금제가 이 등급을 쓸 수 없으면 403
     * @param sessionId 이어갈 세션. null 이면 새로 만든다
     * @param productId 대화 대상 상품 (새 세션일 때만 사용)
     */
    public ChatResult ask(String userEmail, ChatTier tier,
                          Long sessionId, String productId, String question) {
        // 반복 담기 이력은 대화 트랜잭션 밖에서 미리 찾는다. 안에서 찾다 쿼리가 실패하면 예외를 잡아도
        // 대화 트랜잭션이 rollback-only 가 되어 답변을 저장하지 못한다(#271 리뷰)
        List<RepurchaseCandidate> repurchases = findRepurchases(userEmail);
        Turn turn = transactionOperations.execute(
                status -> converse(userEmail, tier, sessionId, productId, question, repurchases));

        // ── 추천·예약 제안: 대화 트랜잭션이 커밋되고 커넥션을 돌려준 뒤에 처리한다 ──
        ChatResult result = turn.result();
        if (turn.wantsRecommendations()) {
            result = result.withRecommendations(findRecommendations(turn.productId()));
        }
        if (turn.scheduleValue() != null) {
            result = result.withScheduleProposal(proposeSchedule(turn).orElse(null));
        }
        return result;
    }

    /** 대화 한 턴을 처리하고 저장한다. 호출자가 연 트랜잭션 안에서 돈다 */
    private Turn converse(String userEmail, ChatTier tier, Long sessionId, String productId,
                          String question, List<RepurchaseCandidate> repurchases) {
        User user = userService.findByEmail(userEmail);
        PlanTier plan = user.getEffectivePlan();

        // ── 요금제 검사: 세션을 만들기 전에 끊어 빈 대화가 남지 않게 한다 ──
        planPolicy.verifyAccess(plan, tier);

        ChatSession session = resolveSession(user, sessionId, productId, question);

        // ── 세이프가드 1계층: 입력 검증 (여기서 막히면 토큰 소모 0 → 쿼터도 차감 안 함) ──
        TopicGuard.Verdict input = topicGuard.inspectQuestion(question);
        if (!input.allowed()) {
            return Turn.of(blockAndSave(session, user, plan, question, input.reason(), input.userMessage()));
        }

        // ── 쿼터 차감: LLM 을 부르기 직전에 한다 ──
        int dailyLimit = planPolicy.dailyLimit(plan);
        if (!quotaStore.tryConsume(user.getId(), dailyLimit)) {
            log.info("[Chat] 하루 한도 초과 - userId={}, plan={}, limit={}",
                    user.getId(), plan, dailyLimit);
            // 던져서 트랜잭션을 되돌린다. 위에서 만든 새 세션도 같이 사라진다.
            throw new CustomException(ErrorCode.CHAT_QUOTA_EXCEEDED);
        }

        ProductAnalysisContext context = session.getProductId() == null ? null
                : productAnalysisPort.findContext(session.getProductId()).orElse(null);

        String systemPrompt = promptAssembler.systemPrompt();
        String userMessage = promptAssembler.buildUserMessage(context, repurchases, question);
        List<LlmClient.Turn> history = loadHistory(session);

        LlmClient.LlmResponse response;
        try {
            response = llmClient.complete(systemPrompt, history, userMessage,
                    planPolicy.llmOptions(tier));
        } catch (RuntimeException e) {
            // 답변을 못 준 턴은 사용량에서 뺀다. 서버 잘못으로 한도를 깎지 않는다.
            quotaStore.refund(user.getId());
            log.error("[Chat] LLM 호출 실패 - sessionId={}: {}", session.getId(), e.getMessage());
            throw new CustomException(ErrorCode.CHAT_LLM_UNAVAILABLE);
        }

        // ── 세이프가드 3계층: 모델의 주제 판정 ──
        LlmAnswer parsed = LlmAnswer.parse(response.text());
        if (!parsed.onTopic()) {
            log.info("[Chat] 주제 이탈 응답 - sessionId={}", session.getId());
            return Turn.of(blockAndSave(session, user, plan, question, "OFF_TOPIC", parsed.answer(),
                    response.inputTokens(), response.outputTokens()));
        }

        // ── 세이프가드 4계층: 근거 없는 수치 차단 ──
        // 같은 질문도 모델 표현에 따라 데이터에 없는 수치가 섞여 간헐적으로 막혔다(#219).
        // 바로 막지 않고 문제 수치를 알려 한 번만 다시 쓰게 한다. 다시 써도 섞이면 막는다.
        Optional<Double> ungrounded = topicGuard.findUngroundedScore(parsed.answer(), context);
        if (ungrounded.isPresent()) {
            log.info("[Chat] 근거 없는 수치로 다시 작성 - sessionId={}, mentioned={}",
                    session.getId(), ungrounded.get());
            LlmClient.LlmResponse retry;
            try {
                retry = llmClient.complete(systemPrompt, history,
                        promptAssembler.rewriteRequest(userMessage, ungrounded.get()),
                        planPolicy.llmOptions(tier));
            } catch (RuntimeException e) {
                // 첫 답변은 받았으므로 사용량은 그대로 두고 차단으로 끝낸다
                log.warn("[Chat] 다시 작성 호출 실패 - sessionId={}: {}", session.getId(), e.getMessage());
                retry = null;
            }
            int inputTokens = response.inputTokens() + (retry == null ? 0 : retry.inputTokens());
            int outputTokens = response.outputTokens() + (retry == null ? 0 : retry.outputTokens());
            LlmAnswer rewritten = retry == null ? null : LlmAnswer.parse(retry.text());
            TopicGuard.Verdict output = rewritten == null || !rewritten.onTopic()
                    ? TopicGuard.Verdict.block(TopicGuard.UNGROUNDED_SCORE,
                            "답변에 확인되지 않은 수치가 포함되어 표시하지 않았습니다. 다시 질문해주세요.")
                    : topicGuard.inspectAnswer(rewritten.answer(), context);
            if (!output.allowed()) {
                return Turn.of(blockAndSave(session, user, plan, question, output.reason(), output.userMessage(),
                        inputTokens, outputTokens));
            }
            parsed = rewritten;
            response = new LlmClient.LlmResponse(retry.text(), inputTokens, outputTokens);
        }

        saveTurn(session, question, parsed.answer(), false, null,
                response.inputTokens(), response.outputTokens());
        session.touch();

        ChatResult result = new ChatResult(session.getId(), parsed.answer(), false, null,
                response.inputTokens() + response.outputTokens(), quotaOf(user, plan), List.of());
        // 추천: 모델이 원한다고 판단한 턴에만, 상품은 DB 에서 고른다 (LLM 추가 호출 없음)
        // 예약: 모델이 옮겨 적은 SCHEDULE 값만 받아 두고, 검사와 저장은 커밋 뒤에 한다 (LLM 추가 호출 없음)
        return new Turn(result, parsed.wantsRecommendations(), session.getProductId(), user,
                parsed.wantsSchedule() ? parsed.scheduleValue() : null,
                context == null ? null : context.productName(), repurchases);
    }

    /** 오늘 남은 사용량. 프론트가 전송 버튼을 막거나 남은 횟수를 보여줄 때 쓴다 */
    @Transactional(readOnly = true)
    public QuotaStatus getQuotaStatus(String userEmail) {
        User user = userService.findByEmail(userEmail);
        return quotaOf(user, user.getEffectivePlan());
    }

    /**
     * 대화 하나를 메시지까지 지운다(#275). 남의 대화면 403, 없으면 404 — 조회와 같은 기준이다.
     * 사용량은 되돌리지 않는다. 지우고 다시 물으면 한도가 늘어나는 셈이 되기 때문이다.
     */
    @Transactional
    public void deleteSession(String userEmail, Long sessionId) {
        User user = userService.findByEmail(userEmail);
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        if (!session.isOwnedBy(user)) {
            throw new CustomException(ErrorCode.CHAT_SESSION_FORBIDDEN);
        }
        deleteSessions(List.of(sessionId));
    }

    /** 내 대화를 모두 지운다(#275). 지운 대화 수를 돌려준다. 없으면 0 */
    @Transactional
    public int deleteAllSessions(String userEmail) {
        User user = userService.findByEmail(userEmail);
        return deleteSessions(sessionRepository.findIdsByUserId(user.getId()));
    }

    private int deleteSessions(List<Long> sessionIds) {
        if (sessionIds.isEmpty()) return 0;
        messageRepository.deleteBySessionIds(sessionIds);
        return sessionRepository.deleteByIds(sessionIds);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> getMessages(String userEmail, Long sessionId) {
        User user = userService.findByEmail(userEmail);
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        if (!session.isOwnedBy(user)) {
            throw new CustomException(ErrorCode.CHAT_SESSION_FORBIDDEN);
        }
        return messageRepository.findBySession_IdOrderByCreatedAtAsc(sessionId);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ChatSession> getMySessions(
            String userEmail, org.springframework.data.domain.Pageable pageable) {
        User user = userService.findByEmail(userEmail);
        return sessionRepository.findByUser_IdOrderByLastMessageAtDesc(user.getId(), pageable);
    }

    // ────────────────────────────── 내부 ──────────────────────────────

    /**
     * 추천은 부가 기능이라 조회가 실패해도 답변은 그대로 돌려준다.
     *
     * <p>대화 트랜잭션이 이미 커밋된 뒤에 부르므로 조회 실패가 저장에 영향을 주지 않는다.
     * 잡는 자리는 반드시 프록시 바깥(호출자)이어야 한다. 안쪽에서 삼키면 rollback-only 인
     * 조회 트랜잭션을 커밋하려다 UnexpectedRollbackException 이 난다.
     */
    private List<ChatRecommendation> findRecommendations(String productId) {
        try {
            return recommendationService.findSimilar(productId);
        } catch (RuntimeException e) {
            log.warn("[Chat] 추천 조회 실패, 빈 목록으로 대체 - productId={}", productId, e);
            return List.of();
        }
    }

    /**
     * 예약 제안은 부가 기능이라 형식이 어긋나거나 저장이 실패해도 답변은 그대로 돌려준다.
     * 추천과 같은 이유로 대화 트랜잭션 밖(호출자)에서 잡는다.
     */
    private Optional<ScheduleProposal> proposeSchedule(Turn turn) {
        try {
            return ScheduleRequest.parse(turn.scheduleValue(), LocalDateTime.now(clock))
                    .flatMap(request -> scheduleTarget(turn, request.productRef())
                            .flatMap(target -> cartScheduleService.propose(
                                    turn.user(), target.externalId(), target.name(), request)));
        } catch (RuntimeException e) {
            log.warn("[Chat] 예약 제안 실패, 제안 없이 답변만 보낸다 - productId={}", turn.productId(), e);
            return Optional.empty();
        }
    }

    /**
     * 예약할 상품. product= 가 없으면 대화 중인 상품이고, 있으면 대화 중인 상품이거나 이번 턴 프롬프트에
     * 실은 반복 담기 후보여야 한다. 모델이 아무 상품 주소나 적어 예약하는 것을 막는다.
     */
    private Optional<ScheduleTarget> scheduleTarget(Turn turn, String productRef) {
        if (productRef == null || productRef.equals(turn.productId())) {
            return turn.productId() == null ? Optional.empty()
                    : Optional.of(new ScheduleTarget(turn.productId(), turn.productName()));
        }
        return turn.repurchases().stream()
                .filter(c -> productRef.equals(c.externalId()))
                .findFirst()
                .map(c -> new ScheduleTarget(c.externalId(), c.name()));
    }

    private record ScheduleTarget(String externalId, String name) {
    }

    /** 반복 담기 후보(RAG 검색). 부가 정보라 실패해도 이력 없이 답한다. 대화 트랜잭션 밖에서 부른다 */
    private List<RepurchaseCandidate> findRepurchases(String userEmail) {
        try {
            return repurchaseFinder.findAll(userService.findByEmail(userEmail).getId(), REPURCHASE_PROMPT_LIMIT);
        } catch (CustomException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[Chat] 반복 담기 이력 조회 실패, 이력 없이 답한다", e);
            return List.of();
        }
    }

    private ChatSession resolveSession(User user, Long sessionId, String productId, String question) {
        if (sessionId == null) {
            return sessionRepository.save(ChatSession.builder()
                    .user(user)
                    .productId(productId)
                    .title(deriveTitle(question))
                    .build());
        }
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        if (!session.isOwnedBy(user)) {
            throw new CustomException(ErrorCode.CHAT_SESSION_FORBIDDEN);
        }
        return session;
    }

    /** 프롬프트에 실을 최근 대화만 오래된 순으로 만든다. 이력 전체를 보내면 토큰이 선형으로 늘어난다. */
    private List<LlmClient.Turn> loadHistory(ChatSession session) {
        if (session.getId() == null) return List.of();

        List<ChatMessage> recent = new ArrayList<>(messageRepository
                .findBySession_IdAndBlockedFalseOrderByCreatedAtDesc(
                        session.getId(), PageRequest.of(0, PromptAssembler.MAX_HISTORY_MESSAGES)));
        recent.sort(Comparator.comparing(ChatMessage::getCreatedAt));

        return recent.stream()
                .map(m -> new LlmClient.Turn(m.getRole() == ChatRole.USER, m.getContent()))
                .toList();
    }

    private ChatResult blockAndSave(ChatSession session, User user, PlanTier plan, String question,
                                    String reason, String userMessage) {
        return blockAndSave(session, user, plan, question, reason, userMessage, null, null);
    }

    private ChatResult blockAndSave(ChatSession session, User user, PlanTier plan, String question,
                                    String reason, String userMessage,
                                    Integer inputTokens, Integer outputTokens) {
        saveTurn(session, question, userMessage, true, reason, inputTokens, outputTokens);
        session.touch();
        int used = (inputTokens == null ? 0 : inputTokens) + (outputTokens == null ? 0 : outputTokens);
        return new ChatResult(session.getId(), userMessage, true, reason, used,
                quotaOf(user, plan), List.of());
    }

    private QuotaStatus quotaOf(User user, PlanTier plan) {
        int limit = planPolicy.dailyLimit(plan);
        int used = quotaStore.used(user.getId());
        int remaining = limit == ChatQuotaStore.UNLIMITED
                ? ChatQuotaStore.UNLIMITED
                : Math.max(0, limit - used);
        return new QuotaStatus(plan, limit, used, remaining,
                planPolicy.canUse(plan, ChatTier.PRO), quotaStore.resetAt());
    }

    private void saveTurn(ChatSession session, String question, String answer,
                          boolean blocked, String blockReason,
                          Integer inputTokens, Integer outputTokens) {
        messageRepository.save(ChatMessage.builder()
                .session(session)
                .role(ChatRole.USER)
                .content(truncate(question))
                .blocked(blocked)
                .blockReason(blockReason)
                .build());
        messageRepository.save(ChatMessage.builder()
                .session(session)
                .role(ChatRole.ASSISTANT)
                .content(truncate(answer))
                .blocked(blocked)
                .blockReason(blockReason)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .build());
    }

    private static String deriveTitle(String question) {
        if (question == null || question.isBlank()) return "새 대화";
        String trimmed = question.strip();
        return trimmed.length() <= 30 ? trimmed : trimmed.substring(0, 30) + "...";
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() <= 4000 ? text : text.substring(0, 4000);
    }

    /**
     * 대화 트랜잭션의 결과. 추천은 커밋 뒤에 붙이므로 여기 담긴 결과의 추천은 항상 비어 있다.
     *
     * @param productId 추천 기준 상품. 요청값이 아니라 세션에 저장된 값
     */
    /**
     * @param scheduleValue 주제 안 답변에서 모델이 적은 SCHEDULE 값. 예약 요청이 아니면 null
     * @param productName   대화 상품 이름. 예약할 상품의 번호표가 없을 때 이름으로 쓴다. 모르면 null
     * @param repurchases   이번 턴 프롬프트에 실은 반복 담기 후보. product= 로 고른 상품이 이 안에 있어야 예약한다
     */
    private record Turn(ChatResult result, boolean wantsRecommendations, String productId,
                        User user, String scheduleValue, String productName,
                        List<RepurchaseCandidate> repurchases) {
        static Turn of(ChatResult blocked) {
            return new Turn(blocked, false, null, null, null, null, List.of());
        }
    }

    /**
     * @param blocked     세이프가드에 걸렸는지
     * @param blockReason 차단 사유 (blocked=true 일 때)
     * @param usedTokens  이번 턴에 소모한 토큰
     * @param quota       이 턴을 반영한 오늘 사용량
     * @param recommendations 비슷한 상품. 차단됐거나 추천을 원하지 않은 턴은 빈 목록
     */
    public record ChatResult(Long sessionId, String answer, boolean blocked,
                             String blockReason, int usedTokens, QuotaStatus quota,
                             List<ChatRecommendation> recommendations, ScheduleProposal scheduleProposal) {
        public ChatResult {
            recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        }

        public ChatResult(Long sessionId, String answer, boolean blocked, String blockReason, int usedTokens,
                          QuotaStatus quota, List<ChatRecommendation> recommendations) {
            this(sessionId, answer, blocked, blockReason, usedTokens, quota, recommendations, null);
        }

        ChatResult withRecommendations(List<ChatRecommendation> recommendations) {
            return new ChatResult(sessionId, answer, blocked, blockReason, usedTokens, quota,
                    recommendations, scheduleProposal);
        }

        ChatResult withScheduleProposal(ScheduleProposal proposal) {
            return new ChatResult(sessionId, answer, blocked, blockReason, usedTokens, quota,
                    recommendations, proposal);
        }
    }

    /**
     * @param plan         적용 중인 요금제 (만료된 유료 요금제는 FREE 로 내려온 값)
     * @param dailyLimit   하루 한도. -1 이면 무제한
     * @param usedToday    오늘 사용한 메시지 수
     * @param remaining    남은 메시지 수. -1 이면 무제한
     * @param proAvailable PRO 엔드포인트를 쓸 수 있는지
     * @param resetAt      한도가 초기화되는 시각
     */
    public record QuotaStatus(PlanTier plan, int dailyLimit, int usedToday, int remaining,
                              boolean proAvailable, Instant resetAt) {}
}
