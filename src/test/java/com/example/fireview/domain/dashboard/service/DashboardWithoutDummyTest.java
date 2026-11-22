package com.example.fireview.domain.dashboard.service;

import com.example.fireview.domain.dashboard.dto.DashboardResponse;
import com.example.fireview.domain.dataserver.dto.response.AnalysisStatus;
import com.example.fireview.domain.dashboard.entity.ViewHistory;
import com.example.fireview.domain.dashboard.repository.ViewHistoryRepository;
import com.example.fireview.domain.onboarding.entity.UserPreference;
import com.example.fireview.domain.onboarding.repository.UserPreferenceRepository;
import com.example.fireview.domain.product.entity.Category;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.user.entity.OAuthProvider;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.repository.UserRepository;
import com.example.fireview.domain.user.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
 * 더미 상품을 지운 뒤의 대시보드(#195).
 *
 * <p>남는 상품은 Data 서버 번호표가 붙은 실제 상품뿐이고, 분석 전이라 avgRti 가 null 이다.
 * 위험 상품은 빈 배열이 되고, 나머지도 예외 없이 내려가야 한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({DashboardService.class, SearchKeywordCounter.class})
class DashboardWithoutDummyTest {

    @Autowired DashboardService dashboardService;
    @Autowired ProductRepository productRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserPreferenceRepository userPreferenceRepository;
    @Autowired ViewHistoryRepository viewHistoryRepository;
    @MockitoBean UserService userService;

    private User user;
    private Product real;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder().email("u@test.com").nickname("u")
                .provider(OAuthProvider.LOCAL).build());
        given(userService.findByEmail("u@test.com")).willReturn(user);

        real = productRepository.save(Product.builder().id(1L).name("실상품").platform("KURLY")
                .dataPlatform("kurly").dataProductId("1000146248")
                .category(Category.BEAUTY_SKINCARE).build());
    }

    @Test
    void 공개_대시보드는_분석_전_상품을_올리지_않는다() {
        // 검색으로 막 등록된 상품은 리뷰가 아직 없을 수 있다. 빈자리를 이런 상품으로 채우지 않는다
        DashboardResponse res = dashboardService.getPublicDashboard();

        assertThat(res.recommendedProducts()).isEmpty();
        assertThat(res.riskyProducts()).isEmpty();
    }

    @Test
    void 선호_카테고리가_있어도_분석_전_상품_때문에_깨지지_않는다() {
        // 분석 전(avgRti null) 상품은 최소 신뢰도를 넘는지 알 수 없으므로 추천하지 않는다
        userPreferenceRepository.save(UserPreference.builder().user(user)
                .preferredCategories(new HashSet<>(Set.of(Category.BEAUTY_SKINCARE)))
                .minTrustScore(0).build());
        viewHistoryRepository.save(ViewHistory.builder().user(user).product(real).build());

        DashboardResponse res = dashboardService.getDashboard("u@test.com");

        assertThat(res.recommendedProducts()).isEmpty();
        assertThat(res.recentProducts()).singleElement()
                .satisfies(p -> assertThat(p.externalId()).isEqualTo("kurly-1000146248"));
        assertThat(res.riskyProducts()).isEmpty();
    }

    @Test
    void 상품이_하나도_없어도_대시보드가_내려간다() {
        productRepository.deleteAll();

        DashboardResponse res = dashboardService.getDashboard("u@test.com");

        assertThat(res.recommendedProducts()).isEmpty();
        assertThat(res.recentProducts()).isEmpty();
        assertThat(res.riskyProducts()).isEmpty();
    }

    @Test
    void 대시보드_상품에_분석_상태가_실린다() {
        real.observeAnalysisStatus(AnalysisStatus.DONE, LocalDateTime.now());
        real.setAvgRti(80.0);
        real.setAnalysisScoredReviewCount(DashboardService.MIN_ANALYZED_REVIEWS);
        productRepository.saveAndFlush(real);

        JsonNode json = new ObjectMapper().valueToTree(dashboardService.getPublicDashboard());

        JsonNode product = json.get("recommendedProducts").get(0);
        assertThat(product.get("externalId").asText()).isEqualTo("kurly-1000146248");
        assertThat(product.get("analysisStatus").asText()).isEqualTo("DONE");
    }

    @Test
    void 분석_상태를_못_본_대시보드_상품은_null이다() {
        // Data 상품이 하나도 없을 때만 기존 상품으로 채운다. 그 상품은 분석 상태가 없다
        productRepository.deleteAll();
        productRepository.save(Product.builder().id(900000000001L).name("더미").platform("NAVER")
                .avgRti(80.0).build());

        JsonNode json = new ObjectMapper().valueToTree(dashboardService.getPublicDashboard());

        JsonNode product = json.get("recommendedProducts").get(0);
        assertThat(product.has("analysisStatus")).isTrue();
        assertThat(product.get("analysisStatus").isNull()).isTrue();
    }
}
