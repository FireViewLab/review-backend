package com.example.fireview.domain.dashboard.service;

import com.example.fireview.domain.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인기 검색어가 실제 검색을 따라 바뀌는지 본다(#247).
 * 예전에는 TTL 없는 캐시라 처음 계산한 값이 재시작 전까지 그대로였다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({DashboardService.class, SearchKeywordCounter.class})
class DashboardPopularKeywordsTest {

    @Autowired DashboardService dashboardService;
    @MockitoBean UserService userService;

    @Test
    void 검색할수록_인기_검색어가_바로_바뀐다() {
        dashboardService.recordKeywordSearch("에어팟");
        assertThat(dashboardService.getPopularKeywords()).containsExactly("에어팟");

        dashboardService.recordKeywordSearch("나이키");
        dashboardService.recordKeywordSearch("나이키");

        assertThat(dashboardService.getPopularKeywords()).containsExactly("나이키", "에어팟");
        assertThat(dashboardService.getPublicDashboard().popularKeywords()).containsExactly("나이키", "에어팟");
    }

    @Test
    void 검색이_없으면_비어_있다() {
        // 시드 10개(갤럭시 15200회 등)를 더 이상 넣지 않는다
        assertThat(dashboardService.getPopularKeywords()).isEmpty();
    }
}
