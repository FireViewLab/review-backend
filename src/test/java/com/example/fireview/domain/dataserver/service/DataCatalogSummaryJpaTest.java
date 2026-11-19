package com.example.fireview.domain.dataserver.service;

import com.example.fireview.domain.dataserver.dto.DataServerCatalogPage;
import com.example.fireview.domain.dataserver.dto.DataServerProduct;
import com.example.fireview.domain.dataserver.DataServerProductKey;
import com.example.fireview.domain.dataserver.dto.response.AnalysisStatus;
import com.example.fireview.domain.product.dto.ProductResponse;
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

    private DataServerCatalogPage.Entry entry(String status, Double score, int scored, boolean sampled) {
        var p = new DataServerProduct("kurly", "p", "상품", "https://x", null, null, null,
                1000, "https://img", null, 2000, null, null);
        var a = new DataServerCatalogPage.Analysis(status, 1L, score, scored,
                sampled ? 500 : 1, sampled ? 1318 : 1, sampled, "m1", "p1", null, "v0.5");
        return new DataServerCatalogPage.Entry(p, a);
    }

    private DataServerCatalogPage.Entry v2Entry(Double avgPolicyIndex, int scored) {
        var p = new DataServerProduct("kurly", "p", "상품", "https://x", null, null, null,
                1000, "https://img", null, 2000, null, null);
        var a = new DataServerCatalogPage.Analysis("done", 2L, null, scored,
                1, 1, false, "v3.1", "policy-v3", avgPolicyIndex, "v2");
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
    void v2_상품은_정책_지수_평균을_저장한다() {
        // #250: v2 상품은 avg_rti 가 null 이다. 예전에는 "점수 매긴 리뷰가 있는데 평균이 없다"로 보고 건너뛰었다
        assertThat(registry.upsertCatalogPage(List.of(v2Entry(72.5, 1)))).isEqualTo(1);

        assertThat(read().avgRti()).isEqualTo(72.5);
    }

    @Test
    void v2_상품도_점수가_범위를_벗어나면_등록하지_않는다() {
        assertThat(registry.upsertCatalogPage(List.of(v2Entry(120.0, 1)))).isZero();
        assertThat(products.count()).isZero();
    }
}
