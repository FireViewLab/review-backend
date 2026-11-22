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
    private static final double RISKY_THRESHOLD = 60.0;

    /**
     * 대시보드에 올릴 상품의 최소 분석 리뷰 수. 리뷰 한두 건으로 낸 평균은 상품 신뢰도로 보이기 어렵다.
     * 운영에서 리뷰 1건짜리 상품이 RTI 96 으로 추천 맨 위를 차지했다.
     */
    static final int MIN_ANALYZED_REVIEWS = 5;
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
                getDefaultRecommendedProducts(),
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
            if (!preferred.isEmpty() && hasDataProducts()) {
                return toResponses(productRepository.findDashboardRecommendedIn(
                        preferred, minScore, MIN_ANALYZED_REVIEWS, RECOMMEND_LIMIT));
            }
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
        return getDefaultRecommendedProducts();
    }

    /**
     * 선호 카테고리가 없을 때의 추천.
     *
     * <p>Data 서버 상품이 있으면 분석이 끝나고 리뷰가 충분한 것만 올린다. 열 개가 안 되면 모자란 채로
     * 둔다. 예전에는 분석 전 상품으로 빈자리를 채웠는데(#224), 검색으로 막 등록돼 리뷰가 아직 없는
     * 새 상품이 메인 추천에 떴다. Data 상품이 하나도 없을 때만 기존 더미 순서를 쓴다(홈 목록과 같은 기준).
     */
    private List<ProductResponse> getDefaultRecommendedProducts() {
        return toResponses(hasDataProducts()
                ? productRepository.findDashboardRecommended(MIN_ANALYZED_REVIEWS, RECOMMEND_LIMIT)
                : productRepository.findRecommendedProducts(RECOMMEND_LIMIT));
    }

    private boolean hasDataProducts() {
        return productRepository.existsByDataPlatformIsNotNull();
    }

    private static List<ProductResponse> toResponses(List<Product> products) {
        return products.stream().map(ProductResponse::from).toList();
    }

    private List<ProductResponse> getRecentProducts(User user) {
        return viewHistoryRepository.findTop10ByUser_IdOrderByViewedAtDesc(user.getId())
                .stream()
                .map(vh -> ProductResponse.from(vh.getProduct()))
                .toList();
    }

    /** 위험 상품. 추천과 같은 기준이라 더미와 리뷰가 적은 상품은 Data 상품이 있으면 빠진다 */
    private List<ProductResponse> getRiskyProducts() {
        if (hasDataProducts()) {
            return toResponses(productRepository.findDashboardRisky(
                    RISKY_THRESHOLD, MIN_ANALYZED_REVIEWS, RECOMMEND_LIMIT));
        }
        return productRepository.findRiskyProducts(RISKY_THRESHOLD)
                .stream().limit(10)
                .map(ProductResponse::from)
                .toList();
    }
}