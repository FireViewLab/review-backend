package com.example.fireview.domain.dashboard.service;

import com.example.fireview.domain.dashboard.dto.DashboardResponse;
import com.example.fireview.domain.dataserver.dto.DataServerCatalogPage;
import com.example.fireview.domain.dataserver.dto.DataServerProduct;
import com.example.fireview.domain.dataserver.service.ProductTagRegistry;
import com.example.fireview.domain.product.dto.ProductResponse;
import com.example.fireview.domain.user.service.UserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카탈로그 동기화로 들어온 상품이 대시보드에 오르는 기준.
 *
 * <p>Data 는 리뷰가 많으면 일부를 골라 분석하고, 그중 점수가 나온 수를 따로 준다. 표본 500건 중
 * 1건만 점수가 나온 상품은 평균이 그 1건이라 메인에 올리지 않는다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({ProductTagRegistry.class, DashboardService.class, SearchKeywordCounter.class})
class DashboardCatalogIngestionTest {

    @Autowired ProductTagRegistry registry;
    @Autowired DashboardService dashboardService;
    @Autowired EntityManager em;
    @MockitoBean UserService userService;

    private DataServerCatalogPage.Entry sampled(String productId, double avgRti, int scored) {
        var p = new DataServerProduct("kurly", productId, "상품" + productId, "https://x", null, null, null,
                1000, "https://img", null, 2000, null, null);
        var a = new DataServerCatalogPage.Analysis("done", 1L, avgRti, scored, 500, 1318, true,
                "m1", "p1", null, "v0.5", null, null);
        return new DataServerCatalogPage.Entry(p, a);
    }

    private List<String> recommended() {
        em.flush();
        em.clear();
        DashboardResponse res = dashboardService.getPublicDashboard();
        return res.recommendedProducts().stream().map(ProductResponse::dataProductId).toList();
    }

    @Test
    void 표본은_많아도_점수가_한_건이면_추천하지_않는다() {
        registry.upsertCatalogPage(List.of(sampled("one", 96.0, 1)));

        assertThat(recommended()).isEmpty();
    }

    @Test
    void 점수_매긴_리뷰가_기준_이상이면_추천한다() {
        registry.upsertCatalogPage(List.of(
                sampled("few", 96.0, DashboardService.MIN_ANALYZED_REVIEWS - 1),
                sampled("enough", 80.0, DashboardService.MIN_ANALYZED_REVIEWS)));

        assertThat(recommended()).containsExactly("enough");
    }
}
