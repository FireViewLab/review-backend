package com.example.fireview.domain.cartschedule.dto;

/**
 * 챗봇이 만든 장바구니 예약 제안. 화면은 이걸 보고 확인 버튼을 그린다.
 * 버튼을 누르면 {@code POST /api/cart-schedules/{scheduleId}/confirm} 을 부른다.
 *
 * @param scheduleId 확인할 예약 번호
 * @param summary    "매주 월요일 오전 9시 · 1개" 같은 한 줄 요약
 * @param productName 담을 상품 이름
 */
public record ScheduleProposal(Long scheduleId, String summary, String productName) {
}
