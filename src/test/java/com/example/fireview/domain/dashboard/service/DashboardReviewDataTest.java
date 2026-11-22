package com.example.fireview.domain.dashboard.service;

import com.example.fireview.domain.dashboard.dto.DashboardResponse;
import com.example.fireview.domain.dataserver.dto.response.AnalysisStatus;
import com.example.fireview.domain.onboarding.entity.UserPreference;
import com.example.fireview.domain.onboarding.repository.UserPreferenceRepository;
import com.example.fireview.domain.product.dto.ProductResponse;
import com.example.fireview.domain.product.entity.Category;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.user.entity.OAuthProvider;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.repository.UserRepository;
import com.example.fireview.domain.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 메인 대시보드에 리뷰 데이터가 충분한 상품만 올린다.
 *
 * <p>운영에서 분석 전 새 상품과 리뷰 1건으로 RTI 96 을 받은 상품이 추천에, 더미가 위험 상품에 섞였다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({DashboardService.class, SearchKeywordCounter.class})
class DashboardReviewDataTest {

    @Autowired DashboardService dashboardService;
    @Autowired ProductRepository productRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserPreferenceRepository userPreferenceRepository;
    @MockitoBean UserService userService;

    private static final int ENOUGH = DashboardService.MIN_ANALYZED_REVIEWS;

    private Product analyzed(long id, Category category, double rti, int reviews) {
        Product p = Product.builder().id(id).name("상품" + id).platform("KURLY")
                .dataPlatform("kurly").dataProductId(String.valueOf(id)).category(category)
                .avgRti(rti).analysisScoredReviewCount(reviews).build();
        p.observeAnalysisStatus(AnalysisStatus.DONE, LocalDateTime.now());
        return productRepository.save(p);
    }

    private Product fresh(long id, Category category) {
        return productRepository.save(Product.builder().id(id).name("새상품" + id).platform("KURLY")
                .dataPlatform("kurly").dataProductId(String.valueOf(id)).category(category).build());
    }

    private Product dummy(long id, double rti) {
        return productRepository.save(Product.builder().id(id).name("더미" + id).platform("NAVER")
                .avgRti(rti).build());
    }

    @Test
    void 추천은_분석이_끝나고_리뷰가_충분한_Data_상품만() {
        analyzed(1L, Category.BEAUTY_SKINCARE, 96.0, 1);
        analyzed(2L, Category.BEAUTY_SKINCARE, 90.0, 20);
        analyzed(3L, Category.BEAUTY_SKINCARE, 85.0, ENOUGH);
        fresh(4L, Category.BEAUTY_SKINCARE);
        dummy(900000000001L, 99.0);

        DashboardResponse res = dashboardService.getPublicDashboard();

        assertThat(res.recommendedProducts()).extracting(ProductResponse::id).containsExactly(2L, 3L);
    }

    @Test
    void 위험_상품에서도_더미와_리뷰가_적은_상품을_뺀다() {
        dummy(900000000002L, 20.0);
        analyzed(5L, Category.DIGITAL_MOBILE, 30.0, 2);
        analyzed(6L, Category.DIGITAL_MOBILE, 35.0, 12);
        analyzed(7L, Category.DIGITAL_MOBILE, 70.0, 12);

        DashboardResponse res = dashboardService.getPublicDashboard();

        assertThat(res.riskyProducts()).extracting(ProductResponse::id).containsExactly(6L);
    }

    @Test
    void 선호_카테고리_추천도_같은_기준이다() {
        User user = userRepository.save(User.builder().email("p@test.com").nickname("p")
                .provider(OAuthProvider.LOCAL).build());
        given(userService.findByEmail("p@test.com")).willReturn(user);
        userPreferenceRepository.save(UserPreference.builder().user(user)
                .preferredCategories(new HashSet<>(Set.of(Category.BEAUTY_SKINCARE)))
                .minTrustScore(50).build());
        analyzed(8L, Category.BEAUTY_SKINCARE, 80.0, 10);
        analyzed(9L, Category.BEAUTY_SKINCARE, 95.0, 1);
        analyzed(10L, Category.BEAUTY_SKINCARE, 40.0, 10);
        analyzed(11L, Category.DIGITAL_MOBILE, 90.0, 10);
        fresh(12L, Category.BEAUTY_SKINCARE);

        DashboardResponse res = dashboardService.getDashboard("p@test.com");

        assertThat(res.recommendedProducts()).extracting(ProductResponse::id).containsExactly(8L);
    }
}
