package com.example.fireview.domain.dataserver.dto;

import com.example.fireview.domain.dataserver.DataServerFixtures;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataServerAnalysisJsonTest {

    @Test
    void done이면_버전과_리뷰별_결과를_읽는다() {
        DataServerAnalysis analysis = DataServerFixtures.load("product-analysis-done.json").analysis();

        assertThat(analysis.status()).isEqualTo("done");
        assertThat(analysis.modelVersion()).isEqualTo("rti-model-0.5");
        assertThat(analysis.policyVersion()).isEqualTo("rti-v0");
        assertThat(analysis.inputHash()).isEqualTo("sha256:abc");
        assertThat(analysis.reviewCount()).isEqualTo(128);
        assertThat(analysis.job().id()).isEqualTo(31L);
        assertThat(analysis.job().aiJobId()).isEqualTo("ai-7f3a");
        assertThat(analysis.job().inputReviewCount()).isEqualTo(128);
        assertThat(analysis.results()).hasSize(2);

        DataServerReviewAnalysis r1 = analysis.resultsByReviewId().get("r-1");
        assertThat(r1.policyIndexV4()).isEqualTo(82.5);
        assertThat(r1.levelV4()).isEqualTo("SAFE");
        assertThat(r1.textScoreV4()).isEqualTo(80.0);
        assertThat(r1.textProbabilityV4()).isEqualTo(0.1);
    }

    @Test
    void 계산_불가는_null로_남는다() {
        // Data 서버는 AI 의 -1 을 null 로 바꿔 보낸다. 0 이나 -1 이 되면 안 된다
        DataServerReviewAnalysis r3 = DataServerFixtures.load("product-analysis-done.json")
                .analysis().resultsByReviewId().get("r-3");

        assertThat(r3.policyIndexV4()).isNull();
        assertThat(r3.levelV4()).isNull();
        assertThat(r3.textScoreV4()).isNull();
    }

    @Test
    void job이_없는_상태는_결과가_비어_있다() {
        for (String name : new String[]{"product-analysis-not-analyzed.json", "product-analysis-disabled.json"}) {
            DataServerAnalysis analysis = DataServerFixtures.load(name).analysis();

            assertThat(analysis.job()).isNull();
            assertThat(analysis.modelVersion()).isNull();
            assertThat(analysis.reviewCount()).isZero();
            assertThat(analysis.results()).isEmpty();
            assertThat(analysis.resultsByReviewId()).isEmpty();
        }
        assertThat(DataServerFixtures.load("product-analysis-not-analyzed.json").analysis().status())
                .isEqualTo("not_analyzed");
        assertThat(DataServerFixtures.load("product-analysis-disabled.json").analysis().status())
                .isEqualTo("disabled");
    }

    @Test
    void stale은_job은_있고_결과는_없다() {
        DataServerAnalysis analysis = DataServerFixtures.load("product-analysis-stale.json").analysis();

        assertThat(analysis.status()).isEqualTo("stale");
        assertThat(analysis.job().status()).isEqualTo("done");
        assertThat(analysis.results()).isEmpty();
    }

    @Test
    void 구버전처럼_analysis가_없으면_null이다() {
        DataServerProductResponse body = DataServerFixtures.load("product-analysis-missing.json");

        assertThat(body.analysis()).isNull();
        assertThat(body.product().name()).isEqualTo("샘플 상품");
        assertThat(body.reviewItems()).hasSize(3);
    }

    @Test
    void 결과_목록이_null이어도_빈_목록이다() throws JsonProcessingException {
        DataServerAnalysis analysis = DataServerFixtures.MAPPER.readValue(
                "{\"status\": \"running\"}", DataServerAnalysis.class);

        assertThat(analysis.results()).isEmpty();
        assertThat(analysis.reviewCount()).isNull();
    }

    @Test
    void 표본_정보를_읽는다() {
        // review_count 는 분석 입력(표본) 수, source_review_count 는 Data 가 가진 원본 리뷰 수
        DataServerAnalysis analysis = DataServerFixtures.load("product-analysis-done.json").analysis();

        assertThat(analysis.sampled()).isTrue();
        assertThat(analysis.sourceReviewCount()).isEqualTo(1318);
        assertThat(analysis.reviewCount()).isEqualTo(128);
    }

    @Test
    void 표본_필드가_없는_구버전_done은_null로_둔다() throws JsonProcessingException {
        // Data #80 이전 응답. 모르는 값을 false·0 으로 채우면 "전수 분석"으로 읽힌다
        DataServerAnalysis analysis = DataServerFixtures.MAPPER.readValue("""
                {"status": "done", "model_version": "m", "policy_version": "rti-v0",
                 "review_count": 40, "results": []}
                """, DataServerAnalysis.class);

        assertThat(analysis.sampled()).isNull();
        assertThat(analysis.sourceReviewCount()).isNull();
        assertThat(analysis.reviewCount()).isEqualTo(40);
    }

    @Test
    void v4_계약_응답의_리뷰_결과를_읽는다() {
        // #273: Data 는 리뷰 결과에 v4 필드만 싣는다
        DataServerAnalysis analysis = DataServerFixtures.load("product-analysis-v4-done.json").analysis();
        assertThat(analysis.contractVersion()).isEqualTo("v4");
        var results = analysis.resultsByReviewId();

        DataServerReviewAnalysis r2 = results.get("r-2");
        assertThat(r2.levelV4()).isEqualTo("WARN");
        assertThat(r2.policyIndexV4()).isEqualTo(52.0);
        assertThat(r2.textScoreV4()).isEqualTo(55.0);
        assertThat(r2.textProbabilityV4()).isEqualTo(0.41);
        assertThat(r2.modelVersionV4()).isEqualTo("ptext-koelectra-v4.0");
        assertThat(r2.policyVersionV4()).isEqualTo("text-first-v1");
    }

    @Test
    void v4_결과가_없는_리뷰는_null로_둔다() {
        DataServerReviewAnalysis r3 = DataServerFixtures.load("product-analysis-v4-done.json")
                .analysis().resultsByReviewId().get("r-3");

        assertThat(r3.levelV4()).isNull();
        assertThat(r3.policyIndexV4()).isNull();
        assertThat(r3.textProbabilityV4()).isNull();
    }

    @Test
    void v4_범위_밖_값과_모르는_등급은_null로_바꾼다() throws JsonProcessingException {
        DataServerReviewAnalysis r = DataServerFixtures.MAPPER.readValue("""
                {"review_id": "r-9", "level_v4": " warn ", "policy_index_v4": -1,
                 "text_score_v4": -1, "text_probability_v4": 1.5}
                """, DataServerReviewAnalysis.class);
        DataServerReviewAnalysis unknown = DataServerFixtures.MAPPER.readValue("""
                {"review_id": "r-10", "level_v4": "SUSPICIOUS", "text_probability_v4": -0.1}
                """, DataServerReviewAnalysis.class);

        assertThat(r.levelV4()).isEqualTo("WARN");
        assertThat(r.policyIndexV4()).isNull();
        assertThat(r.textScoreV4()).isNull();
        assertThat(r.textProbabilityV4()).isNull();
        assertThat(unknown.levelV4()).isNull();
        assertThat(unknown.textProbabilityV4()).isNull();
    }

    @Test
    void 카탈로그_v4_평균과_대문자_키_등급_분포를_읽는다() throws JsonProcessingException {
        DataServerCatalogPage page = DataServerFixtures.MAPPER.readValue("""
                {"items": [{"product": {"platform": "kurly", "product_id": "p"},
                  "analysis": {"status": "done", "contract_version": "v4", "scored_review_count": 3,
                    "avg_policy_index_v4": 81.0,
                    "v4_level_counts": {"SAFE": 2, "WARN": 1, "DANGER": 0}}}],
                 "next_cursor": null}
                """, DataServerCatalogPage.class);

        DataServerCatalogPage.Analysis a = page.items().get(0).analysis();
        assertThat(a.avgPolicyIndexV4()).isEqualTo(81.0);
        assertThat(a.v4LevelCounts()).isEqualTo(new DataServerCatalogPage.LevelCounts(2, 1, 0));
        assertThat(a.scoredReviewCount()).isEqualTo(3);
        assertThat(a.contractVersion()).isEqualTo("v4");
    }

    @Test
    void 계약을_모르는_응답도_v4_필드를_읽는다() {
        DataServerAnalysis analysis = DataServerFixtures.load("product-analysis-done.json").analysis();

        assertThat(analysis.contractVersion()).isNull();
        assertThat(analysis.resultsByReviewId().get("r-1").policyIndexV4()).isEqualTo(82.5);
    }

    @Test
    void null_결과가_섞여도_리뷰_id로_묶는다() throws JsonProcessingException {
        DataServerAnalysis analysis = DataServerFixtures.MAPPER.readValue("""
                {"status": "done", "results": [null, {"review_id": "r-1", "policy_index_v4": 70.0}]}
                """, DataServerAnalysis.class);

        assertThat(analysis.resultsByReviewId()).containsOnlyKeys("r-1");
    }
}
