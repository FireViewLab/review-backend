package com.example.fireview.domain.reviewsummary.service;

import com.example.fireview.domain.chat.client.LlmClient;
import com.example.fireview.domain.chat.service.TopicGuard;
import com.example.fireview.domain.dataserver.DataServerProductKey;
import com.example.fireview.domain.dataserver.client.DataServerClient;
import com.example.fireview.domain.dataserver.dto.DataServerProductResponse;
import com.example.fireview.domain.dataserver.dto.DataServerReview;
import com.example.fireview.domain.dataserver.dto.DataServerReviewAnalysis;
import com.example.fireview.domain.reviewsummary.dto.ReviewSummaryView;
import com.example.fireview.domain.reviewsummary.entity.ProductReviewSummary;
import com.example.fireview.domain.reviewsummary.repository.ProductReviewSummaryRepository;
import com.example.fireview.global.config.ExecutorConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 상품 단위 AI 리뷰 요약(#277).
 *
 * <p><b>상세를 처음 열 때 뒤에서 만든다.</b> 그 응답에는 요약이 없고 다음 조회부터 보인다.
 * 아무도 열지 않은 상품까지 미리 만들면 비용이 상품 수만큼 든다. 사용자 채팅 한도와는 따로 센다 —
 * 예전에는 사용자가 버튼을 눌러 채팅으로 요약을 받아 하루 한도를 썼다.
 *
 * <p><b>Data 분석 입력 해시({@code input_hash})가 바뀌면 다시 만든다.</b> 리뷰가 새로 모이거나 재분석되면
 * 해시가 바뀐다. 다시 만드는 동안은 이전 요약을 그대로 보여 준다.
 *
 * <p>비용 안전장치:
 * <ul>
 *   <li>같은 상품은 한 번에 하나만 만든다(서버 한 대 기준)</li>
 *   <li>같은 입력으로 실패하면 {@link #RETRY_AFTER} 동안, 리뷰가 모자라면 입력이 바뀔 때까지 다시 시도하지 않는다</li>
 *   <li>하루 LLM 호출 수에 상한을 둔다({@code app.review-summary.daily-limit}). 리뷰가 모자라 부르지 않은 건 세지 않는다</li>
 * </ul>
 */
@Slf4j
@Service
public class ProductReviewSummaryService {

    static final Duration RETRY_AFTER = Duration.ofHours(1);
    /** 이보다 적으면 요약하지 않는다. 리뷰 두세 개로 만든 "장점"은 한 사람 의견이다 */
    static final int MIN_REVIEWS = 5;
    static final int MAX_REVIEWS = 60;
    /** 첫 페이지 뒤로 더 받을 리뷰 페이지 수. 한 페이지가 20건이라 세 페이지면 {@link #MAX_REVIEWS} 에 닿는다 */
    static final int MAX_EXTRA_PAGES = 2;
    /** 이보다 짧은 리뷰("좋아요")는 요약에 보탤 내용이 없다 */
    static final int MIN_REVIEW_LENGTH = 10;

    private final ProductReviewSummaryRepository repository;
    private final DataServerClient dataServerClient;
    private final LlmClient llmClient;
    private final TopicGuard topicGuard;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final Clock clock;
    private final boolean enabled;
    private final LlmClient.LlmOptions llmOptions;
    private final int dailyLimit;

    private final Set<DataServerProductKey> inFlight = ConcurrentHashMap.newKeySet();
    private LocalDate countedDay;
    private int countedToday;

    @Autowired
    public ProductReviewSummaryService(ProductReviewSummaryRepository repository,
                                       DataServerClient dataServerClient,
                                       LlmClient llmClient,
                                       TopicGuard topicGuard,
                                       ObjectMapper objectMapper,
                                       @Qualifier(ExecutorConfig.REVIEW_SUMMARY_EXECUTOR) Executor executor,
                                       @Value("${app.review-summary.enabled:true}") boolean enabled,
                                       @Value("${app.review-summary.model:claude-haiku-4-5}") String model,
                                       @Value("${app.review-summary.max-tokens:500}") long maxTokens,
                                       @Value("${app.review-summary.daily-limit:300}") int dailyLimit) {
        this(repository, dataServerClient, llmClient, topicGuard, objectMapper, executor,
                Clock.system(ZoneId.of("Asia/Seoul")), enabled, model, maxTokens, dailyLimit);
    }

    ProductReviewSummaryService(ProductReviewSummaryRepository repository, DataServerClient dataServerClient,
                                LlmClient llmClient, TopicGuard topicGuard, ObjectMapper objectMapper,
                                Executor executor, Clock clock, boolean enabled, String model,
                                long maxTokens, int dailyLimit) {
        this.repository = repository;
        this.dataServerClient = dataServerClient;
        this.llmClient = llmClient;
        this.topicGuard = topicGuard;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.clock = clock;
        this.enabled = enabled;
        this.llmOptions = new LlmClient.LlmOptions(model == null || model.isBlank() ? null : model, maxTokens);
        this.dailyLimit = dailyLimit;
    }

    /** 저장된 요약. 아직 없으면 비어 있다 */
    public Optional<ReviewSummaryView> find(DataServerProductKey key) {
        return repository.findByDataPlatformAndDataProductId(key.platform(), key.productId())
                .filter(ProductReviewSummary::hasSummary)
                .map(ReviewSummaryView::from);
    }

    /**
     * 요약이 없거나 분석 입력이 바뀌었으면 뒤에서 만든다. 기다리지 않고 바로 돌아온다.
     *
     * @param inputHash Data 분석 입력 해시. 없으면 아무것도 하지 않는다
     * @param firstPage 방금 받은 상세 응답(리뷰 첫 페이지). Data 를 한 번 덜 부르려고 넘겨받는다
     */
    public void requestIfNeeded(DataServerProductKey key, String inputHash, DataServerProductResponse firstPage) {
        if (!enabled || inputHash == null || inputHash.isBlank() || firstPage == null) return;
        Optional<ProductReviewSummary> existing =
                repository.findByDataPlatformAndDataProductId(key.platform(), key.productId());
        if (!needsGeneration(existing.orElse(null), inputHash, now())) return;
        if (!inFlight.add(key)) return;
        try {
            executor.execute(() -> {
                try {
                    generate(key, inputHash, firstPage);
                } finally {
                    inFlight.remove(key);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(key);
            log.debug("[ReviewSummary] 대기열이 차서 다음 조회로 미룬다 - {}", key.asExternalId());
        }
    }

    static boolean needsGeneration(ProductReviewSummary existing, String inputHash, LocalDateTime now) {
        if (existing == null) return true;
        if (existing.hasSummary() && inputHash.equals(existing.getInputHash())) return false;
        if (!inputHash.equals(existing.getLastAttemptHash())) return true;
        return switch (String.valueOf(existing.getLastAttemptStatus())) {
            case ProductReviewSummary.INSUFFICIENT -> false;
            case ProductReviewSummary.FAILED -> existing.getLastAttemptAt() == null
                    || existing.getLastAttemptAt().isBefore(now.minus(RETRY_AFTER));
            default -> true;
        };
    }

    /** 뒤에서 돈다. 실패를 밖으로 던지지 않고 시도 결과로 적는다 */
    void generate(DataServerProductKey key, String inputHash, DataServerProductResponse firstPage) {
        try {
            List<ReviewSummaryPrompt.ReviewInput> reviews = collectReviews(key, firstPage);
            if (reviews.size() < MIN_REVIEWS) {
                save(key, s -> s.recordAttempt(inputHash, ProductReviewSummary.INSUFFICIENT, now()));
                return;
            }
            if (!tryCountToday()) {
                // 시도로 적지 않는다. 다음 날 조회가 다시 만든다
                log.info("[ReviewSummary] 오늘 상한({}건)에 닿아 만들지 않는다 - {}", dailyLimit, key.asExternalId());
                return;
            }
            String name = firstPage.product() == null ? null : firstPage.product().name();
            LlmClient.LlmResponse response = llmClient.complete(ReviewSummaryPrompt.SYSTEM, List.of(),
                    ReviewSummaryPrompt.userMessage(name, reviews), llmOptions);
            Optional<ReviewSummaryPrompt.Summary> summary = ReviewSummaryPrompt.parse(response.text(), objectMapper);
            if (summary.isEmpty()) {
                log.warn("[ReviewSummary] 응답을 해석하지 못했다 - {}", key.asExternalId());
                save(key, s -> s.recordAttempt(inputHash, ProductReviewSummary.FAILED, now()));
                return;
            }
            save(key, s -> s.recordSummary(summary.get().pros(), summary.get().cons(), reviews.size(),
                    inputHash, llmOptions.model(), now()));
            log.info("[ReviewSummary] 요약 저장 - {}, reviews={}", key.asExternalId(), reviews.size());
        } catch (RuntimeException e) {
            log.warn("[ReviewSummary] 요약 실패 - {}: {}", key.asExternalId(), e.toString());
            try {
                save(key, s -> s.recordAttempt(inputHash, ProductReviewSummary.FAILED, now()));
            } catch (RuntimeException ignored) {
                // 기록까지 실패하면 다음 조회가 다시 시도한다
            }
        }
    }

    /**
     * 요약에 넣을 리뷰. 첫 페이지에 이어 {@link #MAX_EXTRA_PAGES} 페이지까지 더 받는다.
     * 의심 등급(DANGER) 리뷰, 너무 짧은 리뷰, 지시문이 섞인 리뷰는 뺀다.
     */
    private List<ReviewSummaryPrompt.ReviewInput> collectReviews(DataServerProductKey key,
                                                                 DataServerProductResponse firstPage) {
        List<ReviewSummaryPrompt.ReviewInput> out = new ArrayList<>();
        DataServerProductResponse page = firstPage;
        for (int i = 0; page != null && out.size() < MAX_REVIEWS; i++) {
            addUsable(out, page);
            String cursor = page.reviews() == null ? null : page.reviews().nextCursor();
            if (cursor == null || i >= MAX_EXTRA_PAGES) break;
            page = dataServerClient.findProduct(key, cursor).orElse(null);
        }
        return out.size() > MAX_REVIEWS ? out.subList(0, MAX_REVIEWS) : out;
    }

    private void addUsable(List<ReviewSummaryPrompt.ReviewInput> out, DataServerProductResponse page) {
        Map<String, DataServerReviewAnalysis> results =
                page.analysis() == null ? Map.of() : page.analysis().resultsByReviewId();
        for (DataServerReview r : page.reviewItems()) {
            String content = r.content() == null ? "" : r.content().strip();
            if (content.length() < MIN_REVIEW_LENGTH || topicGuard.containsInjection(content)) continue;
            DataServerReviewAnalysis analysis = r.reviewId() == null ? null : results.get(r.reviewId());
            if (analysis != null && "DANGER".equals(analysis.levelV4())) continue;
            out.add(new ReviewSummaryPrompt.ReviewInput(content, r.rating()));
        }
    }

    /** 행이 없으면 만든다. 같은 상품을 두 서버가 동시에 만들면 유니크 제약에 걸리므로 다시 읽어 고친다 */
    private void save(DataServerProductKey key, java.util.function.Consumer<ProductReviewSummary> change) {
        ProductReviewSummary row = repository.findByDataPlatformAndDataProductId(key.platform(), key.productId())
                .orElseGet(() -> new ProductReviewSummary(key.platform(), key.productId()));
        change.accept(row);
        try {
            repository.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            ProductReviewSummary existing = repository
                    .findByDataPlatformAndDataProductId(key.platform(), key.productId())
                    .orElseThrow(() -> e);
            change.accept(existing);
            repository.saveAndFlush(existing);
        }
    }

    private synchronized boolean tryCountToday() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(countedDay)) {
            countedDay = today;
            countedToday = 0;
        }
        if (countedToday >= dailyLimit) return false;
        countedToday++;
        return true;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
