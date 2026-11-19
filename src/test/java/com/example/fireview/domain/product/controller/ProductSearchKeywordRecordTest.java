package com.example.fireview.domain.product.controller;

import com.example.fireview.domain.dashboard.service.DashboardService;
import com.example.fireview.domain.dataserver.service.DataProductCatalogService;
import com.example.fireview.domain.product.cache.NaverProductCache;
import com.example.fireview.domain.product.service.ProductService;
import com.example.fireview.domain.review.service.ReviewService;
import com.example.fireview.domain.search.service.NaverSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품 검색(GET /api/products?keyword=)이 인기 검색어에 기록되는지 본다(#247).
 * 운영은 Data 서버 분기를 타는데, 예전에는 이 분기에서 기록하지 않아 집계가 0건이었다.
 */
class ProductSearchKeywordRecordTest {

    private final DashboardService dashboardService = mock(DashboardService.class);
    private final DataProductCatalogService catalogService = mock(DataProductCatalogService.class);
    private final ProductService productService = mock(ProductService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ProductController controller = new ProductController(productService, mock(ReviewService.class),
                dashboardService, mock(NaverSearchService.class), mock(NaverProductCache.class), catalogService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void Data_서버_검색도_검색어를_기록한다() throws Exception {
        when(catalogService.isEnabled()).thenReturn(true);
        when(catalogService.search("나이키")).thenReturn(List.of());

        mvc.perform(get("/api/products").param("keyword", "나이키")).andExpect(status().isOk());

        verify(dashboardService).recordKeywordSearch("나이키");
    }

    @Test
    void 검색어_없는_홈_목록은_기록하지_않는다() throws Exception {
        when(productService.getAllProducts()).thenReturn(List.of());

        mvc.perform(get("/api/products")).andExpect(status().isOk());

        verify(dashboardService, never()).recordKeywordSearch(anyString());
    }
}
