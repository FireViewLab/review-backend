package com.example.fireview.domain.search.controller;

import com.example.fireview.domain.dashboard.service.DashboardService;
import com.example.fireview.domain.search.dto.NaverSearchResponse;
import com.example.fireview.domain.search.service.NaverSearchService;
import com.example.fireview.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "검색", description = "네이버 쇼핑 통합 검색 (Data 서버 이관 예정)")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class NaverSearchController {

    private final NaverSearchService naverSearchService;
    private final DashboardService dashboardService;

    /**
     * 네이버 쇼핑 API 실시간 상품 검색
     *
     * GET /api/search?keyword={검색어}&display={결과수}
     *
     * @param keyword 검색어 (필수)
     * @param display 결과 수 (선택, 기본 30, 최대 100)
     */
    @GetMapping
    public ApiResponse<NaverSearchResponse> search(
            @RequestParam String keyword,
            @RequestParam(required = false, defaultValue = "100") int display) {
        NaverSearchResponse result = naverSearchService.search(keyword, display);
        // 인기 검색어는 사용자 검색만 센다. 서비스 안에서 세면 다른 경로의 내부 호출까지 섞인다(#247)
        dashboardService.recordKeywordSearch(keyword);
        return ApiResponse.success(result);
    }
}
