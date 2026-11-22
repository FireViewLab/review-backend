package com.example.fireview.domain.cartschedule.dto;

import com.example.fireview.domain.cartschedule.ScheduleText;
import com.example.fireview.domain.cartschedule.entity.CartSchedule;

import java.time.LocalDateTime;

/**
 * @param productId   Spring 상품 번호. 장바구니·찜과 같은 번호다
 * @param externalId  Data 상품 주소 {@code "{platform}-{productId}"}. 상세 화면으로 갈 때 쓴다. 없으면 null
 * @param summary     "매주 월요일 오전 9시 · 1개" 같은 한 줄 요약
 * @param repeatType  {@code ONCE} / {@code WEEKLY} / {@code MONTHLY}
 * @param nextRunAt   다음에 담을 시각(한국 시간). 끝났거나 취소했으면 null
 * @param status      {@code PROPOSED} / {@code ACTIVE} / {@code DONE} / {@code CANCELLED}
 */
public record CartScheduleResponse(Long id, Long productId, String productName, String externalId,
                                   String summary, String repeatType, LocalDateTime nextRunAt, String status) {

    public static CartScheduleResponse from(CartSchedule s) {
        return new CartScheduleResponse(s.getId(), s.getProduct().getId(), s.getProduct().getName(),
                s.getProduct().dataServerExternalId(), ScheduleText.describe(s.rule(), s.getQuantity()),
                s.getRepeatType(), s.getNextRunAt(), s.getStatus());
    }
}
