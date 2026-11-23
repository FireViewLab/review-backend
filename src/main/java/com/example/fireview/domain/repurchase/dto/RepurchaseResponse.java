package com.example.fireview.domain.repurchase.dto;

import com.example.fireview.domain.cartschedule.ScheduleText;
import com.example.fireview.domain.repurchase.RepurchaseCandidate;
import com.example.fireview.domain.repurchase.RepurchaseStats;

import java.time.LocalDate;

/**
 * 메인 "다시 살 때 된 상품" 한 칸.
 *
 * @param productId          Spring 상품 번호. 장바구니·찜·예약과 같은 번호다
 * @param externalId         Data 상품 주소. 상세 화면으로 갈 때 쓴다. 없으면 null
 * @param reason             추천 이유 한 문장. 실제 담기 기록으로 만든다
 * @param scheduleSuggestion 정기 예약 제안. 예약이 지원하는 주기(매주·매월)가 아니면 null
 */
public record RepurchaseResponse(Long productId, String externalId, String name, String thumbnailUrl, Long price,
                                 int intervalDays, LocalDate lastAddedOn, int daysSinceLast, int timesAdded,
                                 String reason, ScheduleSuggestion scheduleSuggestion) {

    /**
     * @param repeatType {@code WEEKLY} / {@code MONTHLY}
     * @param summary    "매주 월요일 오전 9시 · 1개" 같은 한 줄. 예약 버튼 옆에 보여준다
     */
    public record ScheduleSuggestion(String repeatType, String summary) {
    }

    public static RepurchaseResponse from(RepurchaseCandidate c) {
        RepurchaseStats s = c.stats();
        ScheduleSuggestion suggestion = s.suggestion() == null ? null
                : new ScheduleSuggestion(s.suggestion().repeatType(), ScheduleText.describe(s.suggestion(), 1));
        return new RepurchaseResponse(c.productId(), c.externalId(), c.name(), c.thumbnailUrl(), c.price(),
                s.intervalDays(), s.lastAddedOn(), s.daysSinceLast(), s.timesAdded(), s.reason(), suggestion);
    }
}
