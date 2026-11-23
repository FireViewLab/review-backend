package com.example.fireview.domain.chat.controller;

import com.example.fireview.domain.chat.dto.request.ChatRequest;
import com.example.fireview.domain.chat.dto.response.ChatMessageResponse;
import com.example.fireview.domain.chat.dto.response.ChatQuotaResponse;
import com.example.fireview.domain.chat.dto.response.ChatResponse;
import com.example.fireview.domain.chat.dto.response.ChatSessionResponse;
import com.example.fireview.domain.chat.entity.ChatTier;
import com.example.fireview.domain.chat.service.ChatService;
import com.example.fireview.global.config.ExecutorConfig;
import com.example.fireview.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 챗봇 API.
 *
 * <p>질문 전송은 DeferredResult 로 처리해 LLM 응답을 기다리는 동안
 * Tomcat 워커 스레드를 붙잡지 않는다. 실제 대기는 챗봇 전용 풀에서 일어나므로
 * 챗봇이 느려져도 로그인·찜 같은 일반 API 가 밀리지 않는다.
 *
 * <p><b>요금제에 따라 전송 엔드포인트가 둘로 나뉜다.</b>
 * 과금 경계를 요청 본문 필드가 아니라 URL 로 드러내 접근 통제와 호출량 집계를
 * 경로 단위로 할 수 있게 했다. 등급 검사 자체는 요금제가 JWT 가 아닌 DB 값이라
 * SecurityConfig 가 아니라 서비스 계층에서 한다.
 */
@Tag(name = "챗봇", description = "리뷰 분석 기반 LLM 대화 (요금제별 한도 적용)")
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    /** DeferredResult 타임아웃. 클라이언트 타임아웃은 이보다 길게 잡아야 한다 */
    private static final long TIMEOUT_MS = 70_000L;

    private final ChatService chatService;
    private final Executor chatExecutor;

    // Executor 빈이 둘(aiCallExecutor, chatExecutor)이라 @Qualifier 가 필요하다.
    // Lombok 의 @RequiredArgsConstructor 는 필드의 @Qualifier 를 생성자 파라미터로
    // 옮겨주지 않으므로 생성자를 직접 쓴다.
    public ChatController(ChatService chatService,
                          @Qualifier(ExecutorConfig.CHAT_EXECUTOR) Executor chatExecutor) {
        this.chatService = chatService;
        this.chatExecutor = chatExecutor;
    }

    /**
     * 질문 전송 (기본)
     * POST /api/chat/messages
     */
    @Operation(summary = "질문 전송 (기본)", description = """
            모든 요금제가 쓸 수 있는 기본 엔드포인트다. 요금제에 따라 **하루 한도만** 다르고
            응답 품질은 같다. 한도를 다 쓰면 `429 CHAT_QUOTA_EXCEEDED` 로 끊긴다.

            | 요금제 | 하루 메시지 | 이 엔드포인트 | `/pro/messages` |
            |---|---|---|---|
            | `FREE` | 5 | O | X |
            | `PLUS` | 100 | O | X |
            | `PRO` | 300 | O | O |

            한도 수치는 서버 설정값이라 바뀔 수 있다. 화면에는 응답의 `quota` 를 쓸 것.
            한도는 **한국 시간 자정**에 초기화된다 (`quota.resetAt`).

            **쿼터를 깎는 기준**
            - 세이프가드 1계층(인젝션·길이·빈 질문)에 걸린 턴은 LLM 을 부르지 않으므로 깎지 않는다
            - LLM 호출이 실패(503)한 턴도 깎지 않는다
            - LLM 을 부른 뒤 주제 이탈로 막힌 턴은 토큰을 이미 썼으므로 깎는다

            **세이프가드에 걸려도 200 으로 응답한다.** 에러가 아니므로 정상 흐름으로 처리할 것.
            `blocked=true` 이면 `answer` 에 안내 문구가, `blockReason` 에 사유 코드가 담긴다.

            | blockReason | 상황 |
            |---|---|
            | `INJECTION` | 프롬프트 조작 시도 감지 |
            | `TOO_LONG` | 질문 500자 초과 |
            | `EMPTY` | 빈 질문 |
            | `OFF_TOPIC` | 상품·리뷰·가격·카테고리·신뢰도 밖의 주제 |
            | `UNGROUNDED_SCORE` | 실제 데이터에 없는 수치가 답변에 포함됨 |

            **응답이 느리다.** LLM 호출은 수 초~수십 초가 걸리며 서버 타임아웃은 70초다.
            클라이언트 타임아웃을 그보다 길게 잡고 로딩 UI 를 둘 것.

            `sessionId` 를 비우면 새 대화가 시작되고, 응답의 `sessionId` 를 다음 요청에
            그대로 넣으면 대화가 이어진다. `productId` 는 새 대화일 때만 반영된다.

            `usedTokens` 는 이번 턴의 LLM 토큰 소모량이다.

            **`recommendations` 는 항상 배열이다(null 이 아니다).** 사용자가 다른·비슷한 상품을
            원할 때만 대화 상품과 같은 카테고리·비슷한 가격대의 실제 상품이 최대 3개 담긴다.
            차단(`blocked=true`)된 턴이나 추천을 원하지 않은 턴, 기준 상품을 못 찾은 턴은 빈 배열이다.
            상품은 서버가 DB 에서 고르며 `answer` 본문에는 상품 이름·링크가 들어가지 않는다.
            카드는 `/product/:platform/:productId` 로 연결하고, `reviewCount`·`rating`·`price` 가
            null 이면 그 항목을 표시하지 않는다.
            """)
    @PostMapping("/messages")
    public DeferredResult<ApiResponse<ChatResponse>> ask(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChatRequest request) {
        return submit(ChatTier.STANDARD, jwt, request);
    }

    /**
     * 질문 전송 (프로)
     * POST /api/chat/pro/messages
     */
    @Operation(summary = "질문 전송 (프로 전용)", description = """
            **`PRO` 요금제만** 호출할 수 있다. 그 외 요금제가 부르면 `403 CHAT_PLAN_REQUIRED` 다.
            요청·응답 형식은 `/api/chat/messages` 와 완전히 같고, 상위 모델로 더 긴 답변을 받는다.
            하루 한도는 요금제 한도를 함께 쓴다 (기본 엔드포인트와 같은 카운터).

            호출 전에 `GET /api/chat/quota` 의 `proAvailable` 로 노출 여부를 판단할 것.
            403 을 받고 나서 숨기면 사용자가 실패를 한 번 겪는다.

            쿼터 차감 기준과 세이프가드 동작은 `/api/chat/messages` 와 같다.
            """)
    @PostMapping("/pro/messages")
    public DeferredResult<ApiResponse<ChatResponse>> askPro(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChatRequest request) {
        return submit(ChatTier.PRO, jwt, request);
    }

    /**
     * 내 사용량
     * GET /api/chat/quota
     */
    @Operation(summary = "오늘 남은 사용량", description = """
            LLM 을 부르지 않으므로 빠르고, 쿼터를 깎지 않는다.
            채팅 화면 진입 시 한 번 불러 남은 횟수와 프로 기능 노출 여부를 정하는 용도다.

            `dailyLimit` 과 `remaining` 이 `-1` 이면 무제한을 뜻한다.
            """)
    @GetMapping("/quota")
    public ApiResponse<ChatQuotaResponse> getQuota(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(
                ChatQuotaResponse.from(chatService.getQuotaStatus(jwt.getSubject())));
    }

    /**
     * 내 대화 목록
     * GET /api/chat/sessions
     */
    @GetMapping("/sessions")
    public ApiResponse<Page<ChatSessionResponse>> getMySessions(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 20, sort = "lastMessageAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        Page<ChatSessionResponse> sessions =
                chatService.getMySessions(jwt.getSubject(), pageable).map(ChatSessionResponse::from);
        return ApiResponse.success(sessions);
    }

    /**
     * 대화 내용 조회
     * GET /api/chat/sessions/{sessionId}/messages
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<ChatMessageResponse>> getMessages(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        List<ChatMessageResponse> messages = chatService.getMessages(jwt.getSubject(), sessionId)
                .stream().map(ChatMessageResponse::from).toList();
        return ApiResponse.success(messages);
    }

    @Operation(summary = "대화 삭제", description = """
            대화 하나를 메시지까지 지운다. 본인 대화가 아니면 403, 없으면 404.
            오늘 쓴 사용량은 되돌리지 않는다.
            """)
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        chatService.deleteSession(jwt.getSubject(), sessionId);
        return ApiResponse.ok("대화를 삭제했습니다.");
    }

    @Operation(summary = "대화 전체 삭제", description = """
            내 대화를 모두 지운다. `data` 는 지운 대화 수다(없으면 0).
            오늘 쓴 사용량은 되돌리지 않는다.
            """)
    @DeleteMapping("/sessions")
    public ApiResponse<Integer> deleteAllSessions(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success("대화를 모두 삭제했습니다.", chatService.deleteAllSessions(jwt.getSubject()));
    }

    // ────────────────────────────── 내부 ──────────────────────────────

    /**
     * 두 전송 엔드포인트의 공통 처리.
     * 등급만 다르고 나머지는 같으므로 분기는 등급 하나로 끝낸다.
     */
    private DeferredResult<ApiResponse<ChatResponse>> submit(
            ChatTier tier, Jwt jwt, ChatRequest request) {

        DeferredResult<ApiResponse<ChatResponse>> deferred = new DeferredResult<>(TIMEOUT_MS);

        chatExecutor.execute(() -> {
            try {
                ChatService.ChatResult result = chatService.ask(
                        jwt.getSubject(), tier,
                        request.sessionId(), request.productId(), request.question());
                deferred.setResult(ApiResponse.success(ChatResponse.from(result)));
            } catch (Exception e) {
                deferred.setErrorResult(e);
            }
        });
        return deferred;
    }
}
