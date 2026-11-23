package com.example.fireview.domain.dataserver.service;

import com.example.fireview.domain.dataserver.dto.DataServerCatalogPage;
import com.example.fireview.domain.dataserver.dto.DataServerProduct;
import com.example.fireview.domain.dataserver.DataServerProductKey;
import com.example.fireview.domain.dataserver.dto.response.AnalysisStatus;
import com.example.fireview.domain.product.dto.ProductResponse;
import com.example.fireview.domain.product.dto.ReviewLevelCounts;
import com.example.fireview.domain.product.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.domain.PageRequest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(ProductTagRegistry.class)
class DataCatalogSummaryJpaTest {
    @Autowired ProductTagRegistry registry;
    @Autowired ProductRepository products;
    @Autowired EntityManager em;

    /** Data v4.0 단독 응답(#273). scored_review_count 는 등급 분포의 합이다 */
    private DataServerCatalogPage.Entry entry(String status, Double avgV4, int scored, boolean sampled) {
        return entry(status, avgV4, scored, sampled,
                scored == 0 ? null : new DataServerCatalogPage.LevelCounts(scored, 0, 0));
    }

    private DataServerCatalogPage.Entry entry(String status, Double avgV4, int scored, boolean sampled,
                                              DataServerCatalogPage.LevelCounts counts) {
        var p = new DataServerProduct("kurly", "p", "상품", "https://x", null, null, null,
                1000, "https://img", null, 2000, null, null);
        var a = new DataServerCatalogPage.Analysis(status, 1L, scored,
                sampled ? 500 : Math.max(scored, 1), sampled ? 1318 : Math.max(scored, 1), sampled,
                "ptext-koelectra-v4.0", "text-first-v1", "v4", avgV4, counts);
        return new DataServerCatalogPage.Entry(p, a);
    }

    private ProductResponse read() {
        em.flush();
        em.clear();
        return ProductResponse.from(products.findByDataPlatformAndDataProductId("kurly", "p").orElseThrow());
    }

    @Test
    void 실제_0점과_표본_범위를_목록에_보존한다() {
        assertThat(registry.upsertCatalogPage(List.of(entry("done", 0.0, 500, true)))).isEqualTo(1);
        var response = read();
        assertThat(response.avgRti()).isZero();
        assertThat(response.analysisStatus()).isEqualTo(AnalysisStatus.DONE);
        assertThat(response.analysisSampled()).isTrue();
        assertThat(response.analysisReviewCount()).isEqualTo(500);
        assertThat(response.analysisSourceReviewCount()).isEqualTo(1318);
    }

    @Test
    void 재분석이나_실패_상태에서_이전_점수를_보이지_않는다() {
        registry.upsertCatalogPage(List.of(entry("done", 80.0, 1, false)));
        assertThat(read().avgRti()).isEqualTo(80);
        for (String status : List.of("queued", "running", "failed", "stale")) {
            registry.upsertCatalogPage(List.of(entry(status, null, 0, false)));
            assertThat(read().avgRti()).isNull();
            assertThat(read().analysisStatus()).isEqualTo(AnalysisStatus.from(status));
        }
    }

    @Test
    void 완료지만_점수가_없으면_가상_점수를_채우지_않는다() {
        registry.upsertCatalogPage(List.of(entry("done", null, 0, false)));
        var response = read();
        assertThat(response.analysisStatus()).isEqualTo(AnalysisStatus.DONE);
        assertThat(response.avgRti()).isNull();
    }

    @Test
    void 잘못된_점수는_등록하지_않는다() {
        assertThat(registry.upsertCatalogPage(List.of(entry("done", -1.0, 1, false)))).isZero();
        assertThat(products.count()).isZero();
    }

    @Test
    void 신규_미분석_상품보다_완료_상품을_홈에_우선한다() {
        registry.upsertCatalogPage(List.of(entry("done", 80.0, 1, false)));
        em.flush();
        em.clear();
        registry.resolveOrCreate(new DataServerProductKey("kurly", "new"), "신규 상품");
        assertThat(products.findHomeCatalogCandidates(PageRequest.of(0, 1)))
                .extracting(p -> p.getDataProductId()).containsExactly("p");
    }

    @Test
    void v4_평균을_상품_대표_점수와_v4_평균에_함께_저장한다() {
        // #273: Data 가 v3.1 평균을 보내지 않는다. 목록 등급·대시보드·챗봇이 읽는 avgRti 도 v4 평균이다
        registry.upsertCatalogPage(List.of(entry("done", 81.0, 3, false,
                new DataServerCatalogPage.LevelCounts(2, 1, 0))));

        var response = read();
        assertThat(response.avgRti()).isEqualTo(81.0);
        assertThat(response.avgPolicyIndexV4()).isEqualTo(81.0);
        assertThat(response.v4LevelCounts()).isEqualTo(new ReviewLevelCounts(2, 1, 0));
    }

    @Test
    void 점수_매긴_리뷰가_있는데_v4_평균이_없으면_건너뛴다() {
        assertThat(registry.upsertCatalogPage(List.of(entry("done", null, 3, false)))).isZero();
        assertThat(products.count()).isZero();
    }

    @Test
    void 등급_분포가_이상해도_상품은_등록하고_분포만_비운다() {
        assertThat(registry.upsertCatalogPage(List.of(entry("done", 81.0, 1, false,
                new DataServerCatalogPage.LevelCounts(1, null, 0))))).isEqualTo(1);

        var response = read();
        assertThat(response.avgPolicyIndexV4()).isEqualTo(81.0);
        assertThat(response.v4LevelCounts()).isNull();
    }

    @Test
    void 재분석에_들어가면_v4_값도_지운다() {
        registry.upsertCatalogPage(List.of(entry("done", 81.0, 3, false,
                new DataServerCatalogPage.LevelCounts(2, 1, 0))));
        registry.upsertCatalogPage(List.of(entry("running", null, 0, false)));

        var response = read();
        assertThat(response.avgRti()).isNull();
        assertThat(response.avgPolicyIndexV4()).isNull();
        assertThat(response.v4LevelCounts()).isNull();
    }

    @Test
    void 상세_조회가_재분석_상태를_적으면_v4_값도_지운다() {
        registry.upsertCatalogPage(List.of(entry("done", 81.0, 3, false,
                new DataServerCatalogPage.LevelCounts(2, 1, 0))));
        em.flush();
        products.updateAnalysisStatus("kurly", "p", "RUNNING", java.time.LocalDateTime.now());

        var response = read();
        assertThat(response.avgRti()).isNull();
        assertThat(response.avgPolicyIndexV4()).isNull();
        assertThat(response.v4LevelCounts()).isNull();
    }

    @Test
    void 점수가_범위를_벗어나면_등록하지_않는다() {
        assertThat(registry.upsertCatalogPage(List.of(entry("done", 120.0, 1, false)))).isZero();
        assertThat(products.count()).isZero();
    }
}
