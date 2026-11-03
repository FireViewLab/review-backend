package com.example.fireview.domain.dashboard.service;

import com.example.fireview.domain.dashboard.dto.DashboardResponse;
import com.example.fireview.domain.dashboard.entity.SearchKeyword;
import com.example.fireview.domain.dashboard.entity.ViewHistory;
import com.example.fireview.domain.dashboard.repository.SearchKeywordRepository;
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
import org.springframework.cache.annotation.Cacheable;
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

    private final ProductRepository productRepository;
    private final ViewHistoryRepository viewHistoryRepository;
    private final SearchKeywordRepository searchKeywordRepository;
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

    @Transactional
    public void recordKeywordSearch(String keyword) {
        Optional<SearchKeyword> existing = searchKeywordRepository.findByKeyword(keyword);
        if (existing.isPresent()) {
            SearchKeyword kw = existing.get();
            kw.setSearchCount(kw.getSearchCount() + 1);
            searchKeywordRepository.save(kw);
        } else {
            searchKeywordRepository.save(
                    SearchKeyword.builder()
                            .keyword(keyword)
                            .searchCount(1L)
                            .build()
            );
        }
    }

    @Cacheable(value = "popularKeywords")
    public List<String> getPopularKeywords() {
        return searchKeywordRepository.findTop10ByOrderBySearchCountDesc()
                .stream().map(SearchKeyword::getKeyword).toList();
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