package com.example.fireview.domain.product.controller;

import com.example.fireview.domain.dashboard.service.DashboardService;
import com.example.fireview.domain.product.cache.NaverProductCache;
import com.example.fireview.domain.dataserver.service.DataProductCatalogService;
import com.example.fireview.domain.product.dto.ProductResponse;
import com.example.fireview.domain.product.service.ProductService;
import com.example.fireview.domain.review.dto.ReviewResponse;
import com.example.fireview.domain.review.service.ReviewService;
import com.example.fireview.domain.search.service.NaverSearchService;
import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import com.example.fireview.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "상품", description = "상품 목록·상세·리뷰 (Data 서버 이관 예정)")
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ReviewService reviewService;
    private final DashboardService dashboardService;
    private final NaverSearchService naverSearchService;
    private final NaverProductCache naverProductCache;
    private final DataProductCatalogService dataProductCatalogService;

    @GetMapping
    public ApiResponse<List<ProductResponse>> getProducts(
            @RequestParam(required = false) String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            // Data 서버가 설정돼 있으면 실제 쇼핑몰 상품을 검색한다. 응답 모양은 같다.
            // 결과가 비어도 더미로 채우지 않는다 — 진짜 검색 결과에 가짜를 섞지 않는다.
            // Data 서버가 없는 로컬 개발 환경은 네이버 API + DB 병합 결과다.
            List<ProductResponse> found = dataProductCatalogService.isEnabled()
                    ? dataProductCatalogService.search(keyword)
                    : naverSearchService.search(keyword, 100).products();
            // 인기 검색어는 여기서 센다. 검색 서비스 안에서 세면 홈 자동 검색(HomeCatalogRefresher)의
            // 고정 키워드가 순위 1위를 차지한다(#247)
            dashboardService.recordKeywordSearch(keyword);
            return ApiResponse.success(found);
        }
        return ApiResponse.success(productService.getAllProducts());
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductResponse> getProduct(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        // DB에서 조회 시도
        try {
            if (jwt != null) {
                dashboardService.recordView(jwt.getSubject(), id);
            }
            return ApiResponse.success(productService.getProduct(id));
        } catch (CustomException e) {
            if (e.getErrorCode() == ErrorCode.PRODUCT_NOT_FOUND) {
                // DB에 없으면 네이버 캐시에서 조회 후 DB에 자동 저장
                return naverProductCache.get(id)
                        .map(cached -> {
                            ProductResponse saved = productService.saveFromCache(cached);
                            if (jwt != null) {
                                dashboardService.recordView(jwt.getSubject(), saved.id());
                            }
                            return ApiResponse.success(saved);
                        })
                        .orElseThrow(() -> e);
            }
            throw e;
        }
    }

    @GetMapping("/{productId}/reviews")
    public ApiResponse<List<ReviewResponse>> getReviews(
            @PathVariable Long productId,
            @RequestParam(required = false) Double minScore) {
        return ApiResponse.success(reviewService.getReviewsByProduct(productId, minScore));
    }
}