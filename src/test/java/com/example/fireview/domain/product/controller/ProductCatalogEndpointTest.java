package com.example.fireview.domain.product.controller;

import com.example.fireview.domain.dashboard.service.DashboardService;
import com.example.fireview.domain.dataserver.service.DataProductCatalogService;
import com.example.fireview.domain.product.cache.NaverProductCache;
import com.example.fireview.domain.product.dto.ProductPageResponse;
import com.example.fireview.domain.product.service.ProductService;
import com.example.fireview.domain.review.service.ReviewService;
import com.example.fireview.domain.search.service.NaverSearchService;
import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import com.example.fireview.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/products/catalog (#248). 기존 GET /api/products 의 배열 응답과 따로 둔다.
 */
class ProductCatalogEndpointTest {

    private final ProductService productService = mock(ProductService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ProductController controller = new ProductController(productService, mock(ReviewService.class),
                mock(DashboardService.class), mock(NaverSearchService.class), mock(NaverProductCache.class),
                mock(DataProductCatalogService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void 기본값은_첫_페이지_20건이다() throws Exception {
        when(productService.getCatalogPage(any(), anyInt(), anyInt()))
                .thenReturn(new ProductPageResponse(List.of(), 0, 20, 0, false));

        mvc.perform(get("/api/products/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.hasNext").value(false));

        verify(productService).getCatalogPage(null, 0, 20);
    }

    @Test
    void 카테고리와_페이지를_넘긴다() throws Exception {
        when(productService.getCatalogPage(any(), anyInt(), anyInt()))
                .thenReturn(new ProductPageResponse(List.of(), 2, 30, 95, true));

        mvc.perform(get("/api/products/catalog").param("category", "FOOD").param("page", "2").param("size", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(95))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        verify(productService).getCatalogPage("FOOD", 2, 30);
    }

    @Test
    void 모르는_카테고리는_400이다() throws Exception {
        when(productService.getCatalogPage(any(), anyInt(), anyInt()))
                .thenThrow(new CustomException(ErrorCode.INVALID_INPUT));

        mvc.perform(get("/api/products/catalog").param("category", "NOPE"))
                .andExpect(status().isBadRequest());
    }
}
