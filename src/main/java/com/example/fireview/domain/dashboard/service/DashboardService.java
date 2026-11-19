package com.example.fireview.domain.dashboard.service;

import com.example.fireview.domain.dashboard.dto.DashboardResponse;
import com.example.fireview.domain.dashboard.entity.ViewHistory;
import com.example.fireview.domain.dashboard.repository.ViewHistoryRepository;
import com.example.fireview.domain.onboarding.entity.UserPreference;
import com.example.fireview.domain.onboarding.repository.UserPreferenceRepository;
import com.example.fireview.domain.product.dto.ProductResponse;
import com.example.fireview.domain.product.entity.Category;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private static final Limit RECOMMEND_LIMIT = Limit.of(10);
    private static final int POPULAR_KEYWORD_LIMIT = 10;

    private final ProductRepository productRepository;
    private final ViewHistoryRepository viewHistoryRepository;
    private final SearchKeywordCounter searchKeywordCounter;
    private final UserPreferenceRepository userPreferenceRepository;
    private final UserService userService;

    /**
     * 비회원용 공개 대시보드.
     * 개인화 없이 RTI 상위 상품과 인기 키워드만 반환한다.
     */
    public DashboardResponse getPublicDashboard() {
        return new DashboardResponse(
                productRepository.findRecommendedProducts(RECOMMEND_LIMIT)
                        .stream().map(ProductResponse::from).toList(),
                List.of(),
                getRiskyProducts(),
                getPopularKeywords()
        );
    }

    public DashboardResponse getDashboard(String userEmail) {
        User user = userService.findByEmail(userEmail);

        return new DashboardResponse(
                getRecommendedProducts(user),
                getRecentProducts(user),
                getRiskyProducts(),
                getPopularKeywords()
        );
    }

    @Transactional
    public void recordView(String userEmail, Long productId) {
        User user = userService.findByEmail(userEmail);
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) return;

        ViewHistory history = ViewHistory.builder()
                .user(user)
                .product(product)
                .build();
        viewHistoryRepository.save(history);
    }

    /** 사용자 검색 한 번을 인기 검색어 집계에 더한다. 홈 자동 검색 같은 내부 호출은 부르지 않는다 */
    public void recordKeywordSearch(String keyword) {
        searchKeywordCounter.record(keyword);
    }

    /**
     * 최근 7일 검색 횟수 상위 10개(#247).
     *
     * <p>예전에는 누적 횟수 테이블을 읽고 {@code @Cacheable} 을 걸었다. 시드 검색 수(갤럭시 15200회 등)를
     * 실제 검색이 넘을 수 없었고, TTL 없는 캐시라 재시작 전까지 값이 바뀌지 않았다.
     * 집계기가 기간을 자르고, Redis 구현은 합친 결과를 60초만 재사용한다.
     */
    public List<String> getPopularKeywords() {
        return searchKeywordCounter.top(POPULAR_KEYWORD_LIMIT);
    }

    private List<ProductResponse> getRecommendedProducts(User user) {
        Optional<UserPreference> pref = userPreferenceRepository.findByUser_Id(user.getId());

        if (pref.isPresent()) {
            Set<Category> preferred = pref.get().getPreferredCategories();
            int minScore = pref.get().getMinTrustScore();
            if (!preferred.isEmpty()) {
                return productRepository.findByCategoryIn(preferred.stream().toList())
                        .stream()
                        // 분석 전(avgRti null) 상품은 최소 신뢰도를 넘는지 알 수 없다. 예전에는 더미가
                        // 모두 avgRti 를 가져 드러나지 않았지만, 실제 상품만 남으면 언박싱에서 NPE 가 난다
                        .filter(p -> p.getAvgRti() != null && p.getAvgRti() >= minScore)
                        .sorted((a, b) -> Double.compare(b.getAvgRti(), a.getAvgRti()))
                        .limit(10)
                        .map(ProductResponse::from)
                        .toList();
            }
        }
        return productRepository.findRecommendedProducts(RECOMMEND_LIMIT)
                .stream().map(ProductResponse::from).toList();
    }

    private List<ProductResponse> getRecentProducts(User user) {
        return viewHistoryRepository.findTop10ByUser_IdOrderByViewedAtDesc(user.getId())
                .stream()
                .map(vh -> ProductResponse.from(vh.getProduct()))
                .toList();
    }

    private List<ProductResponse> getRiskyProducts() {
        return productRepository.findRiskyProducts(60.0)
                .stream().limit(10)
                .map(ProductResponse::from)
                .toList();
    }
}