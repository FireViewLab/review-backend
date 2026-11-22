package com.example.fireview.domain.cartschedule.controller;

import com.example.fireview.domain.cartschedule.dto.CartScheduleResponse;
import com.example.fireview.domain.cartschedule.service.CartScheduleService;
import com.example.fireview.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "장바구니 예약", description = "챗봇으로 만든 장바구니 자동 담기 예약")
@RestController
@RequestMapping("/api/cart-schedules")
@RequiredArgsConstructor
public class CartScheduleController {

    private final CartScheduleService cartScheduleService;

    /**
     * 챗봇이 만든 예약 제안을 확정한다.
     * POST /api/cart-schedules/{id}/confirm
     */
    @Operation(summary = "예약 제안 확정", description = """
            챗봇 응답의 `scheduleProposal.scheduleId` 로 부른다. 제안을 만든 지 24시간이 지났거나,
            이미 확정·취소했거나, 진행 중 예약이 20개면 409 `CART_SCHEDULE_NOT_CONFIRMABLE` 이다.""")
    @PostMapping("/{id}/confirm")
    public ApiResponse<CartScheduleResponse> confirm(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success("장바구니 예약을 확정했습니다.", cartScheduleService.confirm(jwt.getSubject(), id));
    }

    /**
     * 진행 중인 예약. 다음에 담을 시각이 이른 순이다.
     * GET /api/cart-schedules
     */
    @Operation(summary = "내 장바구니 예약 목록")
    @GetMapping
    public ApiResponse<List<CartScheduleResponse>> mySchedules(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(cartScheduleService.mySchedules(jwt.getSubject()));
    }

    /**
     * 예약을 취소한다. 이미 끝났거나 취소한 예약이면 아무것도 하지 않는다.
     * DELETE /api/cart-schedules/{id}
     */
    @Operation(summary = "장바구니 예약 취소")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> cancel(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        cartScheduleService.cancel(jwt.getSubject(), id);
        return ApiResponse.success("장바구니 예약을 취소했습니다.", null);
    }
}
