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
        assertThat(r1.policyIndexV4()).isEqualTo(82.5);
        assertThat(r1.levelV4()).isEqualTo("SAFE");   // Data 원문 그대로. 한국어로 바꾸지 않는다
    }

    @Test
    void 결과가_없는_리뷰는_0이_아니라_null이다() {
        DataProductResponse res = callWith("product-analysis-done.json");

        DataReview r2 = review(res, "r-2");   // results 에 아예 없음
        assertThat(r2.policyIndexV4()).isNull();
        assertThat(r2.levelV4()).isNull();

        DataReview r3 = review(res, "r-3");   // 결과는 있지만 계산 불가
        assertThat(r3.policyIndexV4()).isNull();
        assertThat(r3.levelV4()).isNull();
    }

    @Test
    void 분석_전_상태는_리뷰에_점수가_없다() {
        for (String fixture : List.of("product-analysis-not-analyzed.json",
                "product-analysis-disabled.json", "product-analysis-stale.json")) {
            DataProductResponse res = callWith(fixture);

            assertThat(res.reviews().items()).hasSize(3)
                    .allSatisfy(r -> {
                        assertThat(r.policyIndexV4()).isNull();
                        assertThat(r.levelV4()).isNull();
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
        assertThat(res.reviews().items()).hasSize(3).allSatisfy(r -> assertThat(r.policyIndexV4()).isNull());
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
        assertThat(first.get("policyIndexV4").asDouble()).isEqualTo(82.5);
        assertThat(first.get("levelV4").asText()).isEqualTo("SAFE");

        JsonNode second = json.get("reviews").get("items").get(1);
        assertThat(second.get("policyIndexV4").isNull()).isTrue();
        assertThat(second.get("levelV4").isNull()).isTrue();
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
    void v4_계약_결과를_내려준다() {
        DataProductResponse res = callWith("product-analysis-v4-done.json");

        assertThat(res.analysis().contractVersion()).isEqualTo("v4");
        DataReview r1 = review(res, "r-1");
        assertThat(r1.levelV4()).isEqualTo("SAFE");
        assertThat(r1.policyIndexV4()).isEqualTo(88.0);
        assertThat(r1.textScoreV4()).isEqualTo(91.5);
        assertThat(r1.textProbabilityV4()).isEqualTo(0.08);
        assertThat(r1.modelVersionV4()).isEqualTo("ptext-koelectra-v4.0");
        assertThat(r1.policyVersionV4()).isEqualTo("text-first-v1");

        DataReview r3 = review(res, "r-3");
        assertThat(r3.levelV4()).isNull();
        assertThat(r3.policyIndexV4()).isNull();
    }

    @Test
    void v0_5와_v3_1_칸은_키만_남기고_비워_둔다() {
        // #273: Data 가 v4.0 만 보낸다. 배포된 프론트가 키를 읽고 있어 자리는 남긴다
        JsonNode json = new ObjectMapper().valueToTree(callWith("product-analysis-v4-done.json"));

        JsonNode r1 = json.get("reviews").get("items").get(0);
        for (String key : List.of("rti", "level", "textScore", "behaviorScore", "networkScore",
                "policyIndexV3", "evidenceLevelV3", "analysisSummaryKo", "reasonSource")) {
            assertThat(r1.get(key).isNull()).as(key).isTrue();
        }
        for (String key : List.of("reasons", "rulesReasonsKo", "evidenceReasonsV3")) {
            assertThat(r1.get(key)).as(key).isEmpty();
        }
        assertThat(json.get("analysis").get("avgPolicyIndexV3").isNull()).isTrue();
    }

    @Test
    void 저장된_v4_평균과_등급_분포를_analysis에_싣는다() {
        DataProductResponse res = callWithStored("product-analysis-v4-done.json", stored(81.0, 81.0, 2, 1, 0));

        assertThat(res.analysis().avgPolicyIndexV4()).isEqualTo(81.0);
        assertThat(res.analysis().v4LevelCounts()).isEqualTo(new ReviewLevelCounts(2, 1, 0));
        assertThat(res.analysis().avgPolicyIndexV3()).isNull();
    }

    @Test
    void analysis에_리뷰_결과의_v4_버전을_싣는다() {
        // #276: modelVersion·policyVersion 은 job 버전이라 v4 로 다시 채운 상품은 v3.1 값이 남는다
        JsonNode analysis = new ObjectMapper().valueToTree(callWith("product-analysis-v4-done.json")).get("analysis");

        assertThat(analysis.get("modelVersionV4").asText()).isEqualTo("ptext-koelectra-v4.0");
        assertThat(analysis.get("policyVersionV4").asText()).isEqualTo("text-first-v1");
    }

    @Test
    void v4_결과가_있는_리뷰가_없으면_v4_버전은_null이다() {
        DataServerProductResponse done = DataServerFixtures.load("product-analysis-done.json");
        DataServerAnalysis a = done.analysis();
        DataProductResponse res = callWith(new DataServerProductResponse(done.status(), done.product(),
                done.reviews(), done.job(), new DataServerAnalysis(a.status(), a.job(), a.inputHash(),
                a.modelVersion(), a.policyVersion(), a.reviewCount(), a.sampled(), a.sourceReviewCount(),
                a.contractVersion(), List.of(a.resultsByReviewId().get("r-3")))));

        assertThat(res.analysis().modelVersionV4()).isNull();
        assertThat(res.analysis().policyVersionV4()).isNull();
    }

    @ParameterizedTest(name = "평균 {0} → {1}")
    @CsvSource({"70.0, SAFE", "69.9, WARN", "40.0, WARN", "39.9, DANGER", "0.0, DANGER"})
    void 상품_v4_평균에_리뷰와_같은_구간의_등급을_붙인다(double avg, String level) {
        // #249: 새로고침·공유 링크로 들어와도 같은 리포트가 보이도록 서버가 등급을 내린다
        DataProductResponse res = callWithStored("product-analysis-v4-done.json", stored(avg, avg, 1, 0, 0));

        assertThat(res.analysis().avgLevelV4()).isEqualTo(level);
    }

    @Test
    void 등급_분포로_WARN과_DANGER_비율을_따로_내린다() {
        // 의심 리뷰 비율에 WARN 을 넣을지는 화면이 정한다. 둘을 따로 주면 어느 쪽이든 그릴 수 있다
        DataProductResponse res = callWithStored("product-analysis-v4-done.json", stored(60.0, 60.0, 5, 2, 1));

        assertThat(res.analysis().warnRatioV4()).isEqualTo(0.25);
        assertThat(res.analysis().dangerRatioV4()).isEqualTo(0.125);
    }

    @Test
    void 비율은_소수_넷째_자리까지다() {
        DataProductResponse res = callWithStored("product-analysis-v4-done.json", stored(60.0, 60.0, 1, 1, 1));

        assertThat(res.analysis().warnRatioV4()).isEqualTo(0.3333);
    }

    @Test
    void 분포가_없거나_합이_0이면_비율과_등급을_비운다() {
        // 0% 로 내리면 "의심 리뷰 없음"으로 읽힌다
        DataProductResponse none = callWithStored("product-analysis-v4-done.json", stored(null, null, null, null, null));
        assertThat(none.analysis().avgLevelV4()).isNull();
        assertThat(none.analysis().warnRatioV4()).isNull();
        assertThat(none.analysis().dangerRatioV4()).isNull();

        DataProductResponse zero = callWithStored("product-analysis-v4-done.json", stored(null, null, 0, 0, 0));
        assertThat(zero.analysis().warnRatioV4()).isNull();
        assertThat(zero.analysis().dangerRatioV4()).isNull();
    }

    @Test
    void 번호표가_없으면_상품_평균은_비어_있다() {
        DataProductResponse res = callWith("product-analysis-v4-done.json");

        assertThat(res.analysis().avgPolicyIndexV3()).isNull();
        assertThat(res.analysis().avgPolicyIndexV4()).isNull();
    }
}
