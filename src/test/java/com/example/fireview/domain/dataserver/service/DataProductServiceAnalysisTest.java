package com.example.fireview.domain.dataserver.service;

import com.example.fireview.domain.dataserver.DataServerFixtures;
import com.example.fireview.domain.dataserver.DataServerProductKey;
import com.example.fireview.domain.dataserver.client.DataServerClient;
import com.example.fireview.domain.dataserver.dto.DataServerAnalysis;
import com.example.fireview.domain.dataserver.dto.DataServerProductResponse;
import com.example.fireview.domain.dataserver.dto.response.AnalysisStatus;
import com.example.fireview.domain.dataserver.dto.response.CollectionStatus;
import com.example.fireview.domain.dataserver.dto.response.DataProductResponse;
import com.example.fireview.domain.dataserver.dto.response.DataProductResponse.DataReview;
import com.example.fireview.domain.product.dto.ReviewLevelCounts;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Data 서버 analysis 를 v2 상세 응답으로 옮기는 부분 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataProductServiceAnalysisTest {

    @Mock DataServerClient dataServerClient;
    @Mock ProductRepository productRepository;
    @Mock ProductTagRegistry registry;
    @InjectMocks DataProductService service;

    private DataProductResponse callWith(String fixture) {
        return callWith(DataServerFixtures.load(fixture));
    }

    private DataProductResponse callWith(DataServerProductResponse body) {
        when(dataServerClient.findProduct(any(), any())).thenReturn(Optional.of(body));
        when(registry.find(any())).thenReturn(Optional.empty());
        return service.getProduct("kurly", "1000146248", null);
    }

    /** 카탈로그 동기화로 번호표와 상품 평균이 저장된 상품을 연다 */
    private DataProductResponse callWithStored(String fixture, Product stored) {
        when(dataServerClient.findProduct(any(), any())).thenReturn(Optional.of(DataServerFixtures.load(fixture)));
        when(registry.find(any())).thenReturn(Optional.of(stored));
        when(registry.upsertForDisplay(any())).thenReturn(stored);
        return service.getProduct("kurly", "1000146248", null);
    }

    private static Product stored(Double avgRti, Double avgV4, Integer safe, Integer warn, Integer danger) {
        return Product.builder().id(1L).name("상품").platform("KURLY")
                .dataPlatform("kurly").dataProductId("1000146248")
                .avgRti(avgRti).avgPolicyIndexV4(avgV4)
                .v4SafeCount(safe).v4WarnCount(warn).v4DangerCount(danger).build();
    }

    private static DataReview review(DataProductResponse res, String reviewId) {
        return res.reviews().items().stream()
                .filter(r -> r.reviewId().equals(reviewId))
                .findFirst().orElseThrow();
    }

    @Test
    void done이면_상태와_버전을_내려준다() {
        DataProductResponse res = callWith("product-analysis-done.json");

        assertThat(res.analysisStatus()).isEqualTo(AnalysisStatus.DONE);
        assertThat(res.analysis().status()).isEqualTo(AnalysisStatus.DONE);
        assertThat(res.analysis().modelVersion()).isEqualTo("rti-model-0.5");
        assertThat(res.analysis().policyVersion()).isEqualTo("rti-v0");
        assertThat(res.analysis().reviewCount()).isEqualTo(128);
    }

    @Test
    void 리뷰마다_review_id로_결과를_붙인다() {
        DataProductResponse res = callWith("product-analysis-done.json");

        DataReview r1 = review(res, "r-1");
        assertThat(r1.rti()).isEqualTo(82.5);
        assertThat(r1.level()).isEqualTo("safe");   // Data 원문 그대로. 한국어로 바꾸지 않는다
        assertThat(r1.reasons()).containsExactly("TEXT_SPECIFIC_DETAIL");
    }

    @Test
    void 결과가_없는_리뷰는_0이_아니라_null이다() {
        DataProductResponse res = callWith("product-analysis-done.json");

        DataReview r2 = review(res, "r-2");   // results 에 아예 없음
        assertThat(r2.rti()).isNull();
        assertThat(r2.level()).isNull();
        assertThat(r2.reasons()).isEmpty();

        DataReview r3 = review(res, "r-3");   // 결과는 있지만 계산 불가
        assertThat(r3.rti()).isNull();
        assertThat(r3.level()).isNull();
    }

    @Test
    void 분석_전_상태는_리뷰에_점수가_없다() {
        for (String fixture : List.of("product-analysis-not-analyzed.json",
                "product-analysis-disabled.json", "product-analysis-stale.json")) {
            DataProductResponse res = callWith(fixture);

            assertThat(res.reviews().items()).hasSize(3)
                    .allSatisfy(r -> {
                        assertThat(r.rti()).isNull();
                        assertThat(r.level()).isNull();
                        assertThat(r.reasons()).isEmpty();
                    });
        }
    }

    @Test
    void Data_상태값을_그대로_올린다() {
        assertThat(callWith("product-analysis-not-analyzed.json").analysisStatus())
                .isEqualTo(AnalysisStatus.NOT_ANALYZED);

        DataProductResponse disabled = callWith("product-analysis-disabled.json");
        assertThat(disabled.analysisStatus()).isEqualTo(AnalysisStatus.DISABLED);
        // 수집 상태(stale)와 분석 상태는 따로 간다
        assertThat(disabled.collectionStatus()).isEqualTo(CollectionStatus.STALE);
        assertThat(disabled.job().id()).isEqualTo(7L);

        assertThat(callWith("product-analysis-stale.json").analysisStatus())
                .isEqualTo(AnalysisStatus.STALE);
    }

    /**
     * 배포된 프론트는 {@code analysis != null} 을 "분석 결과 있음"으로 보고, 아니면 "분석 대기"
     * 안내를 그린다. 결과가 없는 상태에서 객체를 내리면 그 안내가 사라진다.
     */
    @ParameterizedTest(name = "{0} → analysis 객체 {1}")
    @CsvSource({
            "done,         true,  DONE",
            "stale,        false, STALE",
            "not_analyzed, false, NOT_ANALYZED",
            "disabled,     false, DISABLED",
            "queued,       false, QUEUED",
            "running,      false, RUNNING",
            "failed,       false, FAILED",
            "archived,     false, UNAVAILABLE",
    })
    void 결과가_있을_때만_analysis가_객체다(String raw, boolean hasAnalysis, AnalysisStatus expected) {
        DataServerProductResponse done = DataServerFixtures.load("product-analysis-done.json");
        DataServerAnalysis a = done.analysis();
        DataProductResponse res = callWith(new DataServerProductResponse(
                done.status(), done.product(), done.reviews(), done.job(),
                new DataServerAnalysis(raw, a.job(), a.inputHash(), a.modelVersion(),
                        a.policyVersion(), a.reviewCount(), a.sampled(), a.sourceReviewCount(), a.contractVersion(), hasAnalysis ? a.results() : List.of())));

        assertThat(res.analysisStatus()).isEqualTo(expected);
        assertThat(res.analysis() != null).isEqualTo(hasAnalysis);
        assertThat(DataProductResponse.hasAnalysisResult(expected)).isEqualTo(hasAnalysis);
    }

    @Test
    void analysis가_없는_구버전_응답은_UNAVAILABLE이다() {
        DataProductResponse res = callWith("product-analysis-missing.json");

        assertThat(res.analysisStatus()).isEqualTo(AnalysisStatus.UNAVAILABLE);
        assertThat(res.analysis()).isNull();
        assertThat(res.reviews().items()).hasSize(3).allSatisfy(r -> assertThat(r.rti()).isNull());
    }

    @Test
    void 수집_대기면_상품이_없고_분석_상태만_있다() {
        DataProductResponse res = callWith("product-queued.json");

        assertThat(res.collectionStatus()).isEqualTo(CollectionStatus.QUEUED);
        assertThat(res.product()).isNull();
        assertThat(res.reviews().items()).isEmpty();
        assertThat(res.job().id()).isEqualTo(9L);
        assertThat(res.analysisStatus()).isEqualTo(AnalysisStatus.NOT_ANALYZED);
        assertThat(res.analysis()).isNull();
    }

    @Test
    void 모르는_상태값은_UNAVAILABLE이다() {
        assertThat(AnalysisStatus.from("archived")).isEqualTo(AnalysisStatus.UNAVAILABLE);
        assertThat(AnalysisStatus.from(null)).isEqualTo(AnalysisStatus.UNAVAILABLE);
    }

    @Test
    void JSON_응답_모양() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.valueToTree(callWith("product-analysis-done.json"));

        assertThat(json.get("analysisStatus").asText()).isEqualTo("DONE");
        JsonNode analysis = json.get("analysis");
        assertThat(analysis.get("status").asText()).isEqualTo("DONE");
        assertThat(analysis.get("modelVersion").asText()).isEqualTo("rti-model-0.5");
        assertThat(analysis.get("policyVersion").asText()).isEqualTo("rti-v0");
        assertThat(analysis.get("reviewCount").asInt()).isEqualTo(128);
        // 상품 평균은 내려주지 않는다 — 한 페이지 표본 평균이 된다
        assertThat(analysis.has("averageRti")).isFalse();
        assertThat(analysis.has("results")).isFalse();

        JsonNode first = json.get("reviews").get("items").get(0);
        assertThat(first.get("rti").asDouble()).isEqualTo(82.5);
        assertThat(first.get("level").asText()).isEqualTo("safe");
        assertThat(first.get("reasons").isArray()).isTrue();

        JsonNode second = json.get("reviews").get("items").get(1);
        assertThat(second.get("rti").isNull()).isTrue();
        assertThat(second.get("level").isNull()).isTrue();
        assertThat(second.get("reasons")).isEmpty();
    }

    @Test
    void JSON_응답_모양_분석_없음() {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.valueToTree(callWith("product-analysis-missing.json"));

        // 결과가 없으면 analysis 는 null(기존 뜻 유지), 상태는 analysisStatus 로 온다
        assertThat(json.get("analysis").isNull()).isTrue();
        assertThat(json.get("analysisStatus").asText()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void JSON_응답_모양_분석_전() {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.valueToTree(callWith("product-analysis-not-analyzed.json"));

        assertThat(json.get("analysis").isNull()).isTrue();
        assertThat(json.get("analysisStatus").asText()).isEqualTo("NOT_ANALYZED");
        assertThat(json.has("hasAnalysisResult")).isFalse();
    }

    @Test
    void v2_analysis에_표본_정보를_싣는다() {
        DataProductResponse res = callWith("product-analysis-done.json");

        assertThat(res.analysis().reviewCount()).isEqualTo(128);         // 분석 입력(표본) 수
        assertThat(res.analysis().sampled()).isTrue();
        assertThat(res.analysis().sourceReviewCount()).isEqualTo(1318);  // Data 가 가진 원본 수
    }

    @Test
    void 표본_필드가_없는_구버전_done은_null로_내린다() throws Exception {
        DataServerProductResponse done = DataServerFixtures.load("product-analysis-done.json");
        DataServerAnalysis old = DataServerFixtures.MAPPER.readValue("""
                {"status": "done", "model_version": "m", "policy_version": "rti-v0",
                 "review_count": 40, "results": []}
                """, DataServerAnalysis.class);

        DataProductResponse res = callWith(new DataServerProductResponse(
                done.status(), done.product(), done.reviews(), done.job(), old));

        assertThat(res.analysis()).isNotNull();
        assertThat(res.analysis().reviewCount()).isEqualTo(40);
        assertThat(res.analysis().sampled()).isNull();
        assertThat(res.analysis().sourceReviewCount()).isNull();

        JsonNode analysis = new ObjectMapper().valueToTree(res).get("analysis");
        assertThat(analysis.get("sampled").isNull()).isTrue();
        assertThat(analysis.get("sourceReviewCount").isNull()).isTrue();
    }

    @Test
    void JSON_응답에_표본_필드가_있다() {
        JsonNode analysis = new ObjectMapper().valueToTree(callWith("product-analysis-done.json")).get("analysis");

        assertThat(analysis.get("sampled").asBoolean()).isTrue();
        assertThat(analysis.get("sourceReviewCount").asInt()).isEqualTo(1318);
        // 표본 상세(sampling)는 이번에 받지 않는다
        assertThat(analysis.has("sampling")).isFalse();
    }

    @Test
    void 받은_분석_상태를_번호표에_적으라고_넘긴다() {
        callWith("product-analysis-done.json");

        verify(registry).recordAnalysisStatus(new DataServerProductKey("kurly", "1000146248"), AnalysisStatus.DONE);
    }

    @Test
    void 수집_대기여도_분석_상태는_넘긴다() {
        callWith("product-queued.json");

        verify(registry).recordAnalysisStatus(new DataServerProductKey("kurly", "1000146248"),
                AnalysisStatus.NOT_ANALYZED);
    }

    @Test
    void Data_서버에_닿지_못하면_상태를_적지_않는다() {
        when(dataServerClient.findProduct(any(), any())).thenReturn(Optional.empty());

        DataProductResponse res = service.getProduct("kurly", "1000146248", null);

        assertThat(res.analysisStatus()).isEqualTo(AnalysisStatus.UNAVAILABLE);
        verify(registry, never()).recordAnalysisStatus(any(), any());
    }

    @Test
    void v2_결과는_계약_버전과_정책_지수를_내려준다() {
        // #250: 프론트가 지금처럼 rti·level·reasons 만 읽어도 v2 상품이 비어 보이지 않는다
        DataProductResponse res = callWith("product-analysis-v2-done.json");

        assertThat(res.analysis().contractVersion()).isEqualTo("v2");
        DataReview r1 = review(res, "r-1");
        assertThat(r1.rti()).isEqualTo(80.4);
        assertThat(r1.level()).isEqualTo("safe");
        assertThat(r1.reasons()).containsExactly("구체적인 사용 경험이 담겨 있어 신뢰할 만한 리뷰입니다.");
    }

    @Test
    void v2_리뷰는_화면이_읽는_v3_이름으로도_점수와_등급을_내려준다() {
        // 배포된 화면은 v2 계약 리뷰에서 policyIndexV3·evidenceLevelV3 를 읽는다. rti·level 로만 보내면
        // 점수를 못 찾아 모든 리뷰를 "계산 불가"로 셌다
        DataProductResponse res = callWith("product-analysis-v2-done.json");

        DataReview r1 = review(res, "r-1");
        assertThat(r1.policyIndexV3()).isEqualTo(80.4);
        assertThat(r1.evidenceLevelV3()).isEqualTo("SAFE");
        assertThat(r1.rti()).isEqualTo(80.4);

        DataReview r3 = review(res, "r-3");
        assertThat(r3.policyIndexV3()).isNull();
        assertThat(r3.evidenceLevelV3()).isNull();
    }

    @Test
    void v2_상품은_저장된_상품_평균을_analysis에_싣는다() {
        // 화면의 "리뷰 신뢰도 리포트" 상품 점수는 analysis.avgPolicyIndexV3 를 읽는다. 없으면 "계산 불가"였다
        DataProductResponse res = callWithStored("product-analysis-v2-done.json", stored(72.5, 81.0, 2, 1, 0));

        assertThat(res.analysis().avgPolicyIndexV3()).isEqualTo(72.5);
        assertThat(res.analysis().avgPolicyIndexV4()).isEqualTo(81.0);
        assertThat(res.analysis().v4LevelCounts()).isEqualTo(new ReviewLevelCounts(2, 1, 0));
    }

    @Test
    void v0_5_상품의_평균은_v3_이름으로_싣지_않는다() {
        // v0.5 상품의 avgRti 는 RTI 평균이라 정책 지수가 아니다
        DataProductResponse res = callWithStored("product-analysis-done.json", stored(82.5, null, null, null, null));

        assertThat(res.analysis().avgPolicyIndexV3()).isNull();
        assertThat(res.analysis().v4LevelCounts()).isNull();
    }

    @Test
    void 번호표가_없으면_상품_평균은_비어_있다() {
        DataProductResponse res = callWith("product-analysis-v2-done.json");

        assertThat(res.analysis().avgPolicyIndexV3()).isNull();
        assertThat(res.analysis().avgPolicyIndexV4()).isNull();
    }

    @Test
    void v2_리뷰는_설명을_화면이_읽는_이름으로도_싣는다() {
        // v2 화면은 reasons 를 읽지 않는다. 설명을 reasons 에만 두면 "요약할 근거가 없다"로 보였다
        DataProductResponse res = callWith("product-analysis-v2-done.json");

        DataReview r1 = review(res, "r-1");
        assertThat(r1.analysisSummaryKo()).isEqualTo("구체적인 사용 경험이 담겨 있어 신뢰할 만한 리뷰입니다.");
        assertThat(r1.rulesReasonsKo()).containsExactly("사용 경험이 구체적입니다.");
        assertThat(r1.evidenceReasonsV3()).containsExactly("RATING_VERIFIED");
        assertThat(r1.reasonSource()).isEqualTo("groq");

        DataReview r2 = review(res, "r-2");
        assertThat(r2.analysisSummaryKo()).isNull();
        assertThat(r2.rulesReasonsKo()).containsExactly("내용이 매우 짧습니다.", "구체적인 근거가 없습니다.");
    }

    @Test
    void 리뷰별_신호_점수를_내려주고_계산_불가는_null이다() {
        // 화면의 리뷰 상세 "텍스트·행동·네트워크 점수"가 이 이름을 읽는다. 내려주지 않아 늘 "계산 불가"였다
        DataProductResponse res = callWith("product-analysis-v2-done.json");

        DataReview r1 = review(res, "r-1");
        assertThat(r1.textScore()).isEqualTo(80.0);
        assertThat(r1.behaviorScore()).isNull();
        assertThat(r1.networkScore()).isEqualTo(90.0);
    }

    @Test
    void v4_결과를_v3_필드와_따로_내려준다() {
        // #255: 화면 등급·지수는 v4 를 쓰고, rti·level·reasons 는 되돌리기용으로 그대로 둔다
        DataProductResponse res = callWith("product-analysis-v2-done.json");

        DataReview r1 = review(res, "r-1");
        assertThat(r1.levelV4()).isEqualTo("SAFE");
        assertThat(r1.policyIndexV4()).isEqualTo(88.0);
        assertThat(r1.textScoreV4()).isEqualTo(91.5);
        assertThat(r1.textProbabilityV4()).isEqualTo(0.08);
        assertThat(r1.modelVersionV4()).isEqualTo("ptext-koelectra-v4.0");
        assertThat(r1.policyVersionV4()).isEqualTo("text-first-v1");
        assertThat(r1.rti()).isEqualTo(80.4);

        DataReview r3 = review(res, "r-3");
        assertThat(r3.levelV4()).isNull();
        assertThat(r3.policyIndexV4()).isNull();
    }
}
