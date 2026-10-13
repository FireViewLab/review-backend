package com.example.fireview.domain.chat.port;

import com.example.fireview.domain.dataserver.DataServerProductKey;
import com.example.fireview.domain.dataserver.client.DataServerClient;
import com.example.fireview.domain.dataserver.dto.DataServerAnalysis;
import com.example.fireview.domain.dataserver.dto.DataServerProductResponse;
import com.example.fireview.domain.dataserver.dto.DataServerReview;
import com.example.fireview.domain.dataserver.dto.DataServerReviewAnalysis;
import com.example.fireview.domain.dataserver.dto.response.AnalysisStatus;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.review.entity.TrustGrade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Data 서버에서 챗봇 컨텍스트를 가져오는 어댑터.
 *
 * <p>{@code app.chat.stub-analysis.enabled=false} 일 때 {@link StubProductAnalysisAdapter}
 * 대신 이 빈이 쓰인다.
 *
 * <p><b>대표 리뷰의 RTI·등급만 채운다.</b> Data 서버가 주는 분석 결과는 리뷰 단위이고
 * 그것도 이번 리뷰 페이지 것뿐이다.
 *
 * <p><b>상품 평균 RTI·신뢰등급은 카탈로그 동기화가 저장한 값만 쓴다.</b> 상세 응답의 결과는
 * 이번 리뷰 페이지 것뿐이라 그걸로 평균을 내면 한 페이지 표본 평균이 된다. 상품 화면이
 * 보여 주는 평균({@code products.avg_rti})과 같은 값을 싣고, 분석이 끝나지 않았으면 비워 둔다.
 * 비워두면 {@code PromptAssembler} 가 신뢰등급 항목을 아예 싣지 않고, 모델은 시스템 프롬프트
 * 규칙에 따라 "데이터에 없다"고 답한다. 0 이나 임의값을 넣으면 모델이 그 수치를 사실처럼 말한다.
 * 장단점은 Data 서버가 주지 않아 비워 둔다.
 *
 * <p><b>분석리뷰수는 분석에 넣은 리뷰 수다.</b> 상세 화면의 "분석에 입력한 리뷰"와 같은 값이다.
 * 이번에 받아 온 리뷰 한 페이지 크기를 쓰면 리뷰가 많은 상품도 '판단 보류'가 걸린다(#215).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.chat.stub-analysis.enabled", havingValue = "false")
public class DataServerProductAnalysisAdapter implements ProductAnalysisPort {

    /** 프롬프트에 실을 대표 리뷰 수. 토큰 예산 때문에 전부 넣지 않는다 */
    private static final int MAX_SAMPLES = 5;

    /**
     * Data 서버 level → 챗봇 등급 어휘. 시스템 프롬프트가 "안전/주의/위험" 으로 말하므로 맞춘다.
     * {@code TrustGrade} 의 라벨("의심")과 다르다는 점에 주의.
     */
    private static final Map<String, String> GRADE_BY_LEVEL = Map.of(
            "safe", "안전",
            "warn", "주의",
            "danger", "위험");

    private final DataServerClient dataServerClient;
    private final ProductRepository productRepository;

    @Override
    public Optional<ProductAnalysisContext> findContext(String productId) {
        Optional<DataServerProductKey> key = DataServerProductKey.parse(productId);
        if (key.isEmpty()) {
            log.debug("[Chat] 상품 식별자 형식이 아니다 - productId={}", productId);
            return Optional.empty();
        }

        Optional<DataServerProductResponse> found = dataServerClient.findProduct(key.get());
        if (found.isEmpty()) {
            return Optional.empty();
        }

        DataServerProductResponse response = found.get();
        if (!response.hasUsableData()) {
            // queued. 아직 수집 전이라 할 말이 없다. 빈 컨텍스트로 가면
            // 챗봇이 "해당 상품 정보를 아직 모른다"고 답한다.
            log.debug("[Chat] 수집 대기 중인 상품 - key={}, job={}",
                    key.get().asExternalId(),
                    response.job() != null ? response.job().id() : null);
            return Optional.empty();
        }

        return Optional.of(toContext(key.get(), productId, response));
    }

    private ProductAnalysisContext toContext(DataServerProductKey key, String externalId,
                                             DataServerProductResponse response) {
        var product = response.product();
        List<DataServerReview> reviews = response.reviewItems();
        // 카탈로그 동기화가 적어 둔 분석 요약. 번호표가 없는 상품이면 없다
        Product saved = productRepository
                .findByDataPlatformAndDataProductId(key.platform(), key.productId())
                .orElse(null);

        Integer mallReviewCount = mallReviewCount(product.reviewCount(), saved);
        Double averageRti = savedAverageRti(saved);

        return new ProductAnalysisContext(
                externalId,
                product.name(),
                product.price(),
                product.category(),
                averageRti,
                averageRti == null ? null : GRADE_BY_LEVEL.get(TrustGrade.fromScore(averageRti).toLevel()),
                analyzedReviewCount(response.analysis(), saved, mallReviewCount, reviews.size()),
                List.of(),  // pros
                List.of(),  // cons
                List.of(),  // trustSignals
                toSamples(reviews, analysisResults(response)),
                mallReviewCount);
    }

    /**
     * 분석에 넣은 리뷰 수. 상세 응답의 분석이 끝났으면 그 값, 아니면 동기화로 저장된 값을 쓴다.
     * 둘 다 없으면(분석 전) 알고 있는 리뷰 수로 대신한다 — 쇼핑몰 전체 리뷰 수, 그것도 모르면
     * 이번에 받아 온 건수.
     */
    private static int analyzedReviewCount(DataServerAnalysis analysis, Product saved,
                                           Integer mallReviewCount, int fetched) {
        if (analysis != null && AnalysisStatus.from(analysis.status()) == AnalysisStatus.DONE
                && analysis.reviewCount() != null && analysis.reviewCount() > 0) {
            return analysis.reviewCount();
        }
        if (isAnalysisDone(saved) && saved.getAnalysisReviewCount() != null && saved.getAnalysisReviewCount() > 0) {
            return saved.getAnalysisReviewCount();
        }
        return mallReviewCount != null ? mallReviewCount : fetched;
    }

    /**
     * 쇼핑몰 전체 리뷰 수. 11번가·올리브영은 상세 응답에서 비워 보내므로 목록에서 적어 둔 값으로
     * 보충한다. 번호표를 만들 때 채워진 0 은 "모름"이라 쓰지 않는다(DataProductService 와 같은 기준).
     */
    private static Integer mallReviewCount(Integer fromDetail, Product saved) {
        if (fromDetail != null) return fromDetail;
        if (saved == null || saved.getReviewCount() == null || saved.getReviewCount() <= 0) return null;
        return saved.getReviewCount();
    }

    /** 분석이 끝난 상품의 평균만. 범위를 벗어난 값은 버린다 */
    private static Double savedAverageRti(Product saved) {
        if (!isAnalysisDone(saved)) return null;
        Double avg = saved.getAvgRti();
        return avg != null && Double.isFinite(avg) && avg >= 0 && avg <= 100 ? avg : null;
    }

    private static boolean isAnalysisDone(Product saved) {
        return saved != null && saved.getAnalysisStatus() == AnalysisStatus.DONE;
    }

    private static Map<String, DataServerReviewAnalysis> analysisResults(DataServerProductResponse response) {
        return response.analysis() == null ? Map.of() : response.analysis().resultsByReviewId();
    }

    private List<ProductAnalysisContext.SampleReview> toSamples(List<DataServerReview> reviews,
                                                                Map<String, DataServerReviewAnalysis> results) {
        return reviews.stream()
                .filter(r -> r.content() != null && !r.content().isBlank())
                .limit(MAX_SAMPLES)
                .map(r -> {
                    DataServerReviewAnalysis analysis = r.reviewId() == null ? null : results.get(r.reviewId());
                    return new ProductAnalysisContext.SampleReview(
                            r.content(),
                            // Data 서버 평점은 NUMERIC(3,2) 라 4.5 가 올 수 있다
                            r.rating() == null ? null : (int) Math.round(r.rating()),
                            analysis == null ? null : analysis.rti(),
                            analysis == null ? null : toGrade(analysis.level()));
                })
                .toList();
    }

    /** 모르는 값이나 null(계산 불가)은 null. 아무 등급으로나 채우지 않는다 */
    private static String toGrade(String level) {
        return level == null ? null : GRADE_BY_LEVEL.get(level);
    }
}
