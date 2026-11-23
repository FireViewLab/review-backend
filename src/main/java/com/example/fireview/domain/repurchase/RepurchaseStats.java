package com.example.fireview.domain.repurchase;

import com.example.fireview.domain.cartschedule.ScheduleRule;

import java.time.LocalDate;

/**
 * 한 상품을 반복해서 담은 기록을 분석한 결과.
 *
 * @param intervalDays  담은 날 간격의 중앙값(일)
 * @param lastAddedOn   마지막으로 담은 날(한국 시간)
 * @param daysSinceLast 마지막으로 담은 날부터 오늘까지 일수
 * @param timesAdded    담은 날 수. 같은 날 여러 번 담은 기록은 하루로 센다
 * @param due           다시 살 때인지. 경과일이 주기의 80% 이상이다
 * @param score         경과일 ÷ 주기. 늦어질수록 크다
 * @param suggestion    장바구니 예약 제안. 예약이 지원하는 주기가 아니면 null
 * @param reason        사용자에게 보여줄 한 문장. 예: "약 14일마다 담으셨고 마지막은 16일 전이에요"
 */
public record RepurchaseStats(int intervalDays, LocalDate lastAddedOn, int daysSinceLast, int timesAdded,
                              boolean due, double score, ScheduleRule suggestion, String reason) {
}
