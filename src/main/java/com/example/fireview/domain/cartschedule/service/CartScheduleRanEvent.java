package com.example.fireview.domain.cartschedule.service;

/**
 * 예약 한 회차를 장바구니에 담았다. 담기 트랜잭션이 커밋된 뒤 {@link CartScheduleNotifier} 가 알림을 보낸다.
 *
 * @param summary "매주 월요일 오전 9시 · 1개" 같은 한 줄 요약
 */
public record CartScheduleRanEvent(Long scheduleId, Long userId, String productName, String summary) {
}
