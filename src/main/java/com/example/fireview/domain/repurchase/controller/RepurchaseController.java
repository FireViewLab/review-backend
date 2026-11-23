package com.example.fireview.domain.repurchase.controller;

import com.example.fireview.domain.cartschedule.dto.CartScheduleResponse;
import com.example.fireview.domain.repurchase.RepurchaseService;
import com.example.fireview.domain.repurchase.dto.RepurchaseResponse;
import com.example.fireview.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "반복 담기 추천", description = "반복해서 담는 상품 중 다시 살 때가 된 상품")
@RestController
@RequestMapping("/api/repurchase")
@RequiredArgsConstructor
public class RepurchaseController {

    private final RepurchaseService repurchaseService;

    /**
     * 다시 살 때가 된 상품. 늦어진 순, 최대 10개.
     * GET /api/repurchase
     */
    @Operation(summary = "다시 살 때 된 상품", description = """
            최근 180일 장바구니에 2번 이상 담은 상품 중, 담은 주기의 80% 이상 지난 상품이다.
            진행 중인 장바구니 예약이 있는 상품은 빠진다. 이력이 없으면 빈 배열이다. LLM 을 쓰지 않는다.""")
    @GetMapping
    public ApiResponse<List<RepurchaseResponse>> myRepurchases(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(repurchaseService.myRepurchases(jwt.getSubject()));
    }

    /**
     * 추천의 정기 예약 제안대로 바로 예약을 건다.
     * POST /api/repurchase/{productId}/schedule
     */
    @Operation(summary = "제안대로 정기 예약 걸기", description = """
            서버가 제안을 다시 계산해 바로 확정된 장바구니 예약을 만든다. 다시 살 때가 아니거나,
            제안할 주기가 아니거나, 이미 예약이 있거나, 진행 중 예약이 20개면 409 `REPURCHASE_NOT_SCHEDULABLE`.""")
    @PostMapping("/{productId}/schedule")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CartScheduleResponse> schedule(@PathVariable Long productId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success("정기 예약을 걸었습니다.",
                repurchaseService.scheduleFromSuggestion(jwt.getSubject(), productId));
    }
}
