package com.example.fireview.domain.product.controller;

import com.example.fireview.domain.dashboard.service.DashboardService;
import com.example.fireview.domain.product.cache.NaverProductCache;
import com.example.fireview.domain.dataserver.service.DataProductCatalogService;
import com.example.fireview.domain.product.dto.ProductPageResponse;
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
            if (dataProductCatalogService.isEnabled()) {
                return ApiResponse.success(dataProductCatalogService.search(keyword));
            }
            // Data 서버가 없는 로컬 개발 환경: 네이버 API + DB 병합 결과
            return ApiResponse.success(naverSearchService.search(keyword, 100).products());
        }
        return ApiResponse.success(productService.getAllProducts());
    }

    /**
     * 카테고리 화면 목록(#248). 기존 {@code GET /api/products} 는 홈용 100건 배열을 그대로 둔다 —
     * 응답을 페이지 객체로 바꾸면 배포된 홈 화면이 깨진다.
     *
     * @param category 대분류 또는 세부 분류 enum 이름. 비우면 전체
     * @param page     0부터 센 페이지 번호
     * @param size     페이지 크기 (1~100)
     */
    @GetMapping("/catalog")
    public ApiResponse<ProductPageResponse> getCatalogPage(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(productService.getCatalogPage(category, page, size));
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