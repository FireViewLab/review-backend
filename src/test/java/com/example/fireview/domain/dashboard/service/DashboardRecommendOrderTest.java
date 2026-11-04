package com.example.fireview.domain.dashboard.service;

import com.example.fireview.domain.dashboard.dto.DashboardResponse;
import com.example.fireview.domain.product.dto.ProductResponse;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추천 상품 정렬에서 분석 전(avgRti null) 상품이 분석된 상품을 밀어내지 않는다(#224).
 *
 * <p>운영 PostgreSQL 은 DESC 정렬에서 NULL 을 맨 앞에 둔다. H2 기본값은 반대라 문제가 숨으므로
 * DEFAULT_NULL_ORDERING=HIGH 로 PostgreSQL 과 같은 순서를 만든다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:dashboard_recommend_order;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH")
@Import(DashboardService.class)
class DashboardRecommendOrderTest {

    @Autowired DashboardService dashboardService;
    @Autowired ProductRepository productRepository;
    @MockitoBean UserService userService;

    @Test
    void 분석된_상품이_분석_전_상품보다_먼저_추천된다() {
        // 분석 전 상품이 추천 한도(10)보다 많아도 분석된 상품이 밀려나면 안 된다
        IntStream.rangeClosed(1, 12).forEach(i -> productRepository.save(
                Product.builder().id((long) i).name("분석전" + i).platform("KURLY").build()));
        productRepository.save(Product.builder().id(101L).name("낮음").platform("KURLY").avgRti(40.0).build());
        productRepository.save(Product.builder().id(102L).name("높음").platform("KURLY").avgRti(90.0).build());

        DashboardResponse res = dashboardService.getPublicDashboard();

        assertThat(res.recommendedProducts()).hasSize(10);
        assertThat(res.recommendedProducts()).extracting(ProductResponse::name)
                .startsWith("높음", "낮음");
        assertThat(res.recommendedProducts().subList(2, 10))
                .allSatisfy(p -> assertThat(p.avgRti()).isNull());
    }
}
