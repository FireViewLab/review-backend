package com.example.fireview.domain.cartschedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;

/**
 * 예약의 다음 실행 시각을 계산한다. 시각은 모두 한국 시간 기준 LocalDateTime 이다.
 *
 * <p>반복 예약은 {@code after} 보다 늦은 가장 가까운 회차를 돌려준다. 서버가 멈춰 회차를 여러 번
 * 놓쳤어도 몰아서 실행하지 않는다 — 같은 상품이 장바구니에 한꺼번에 여러 개 담기면 안 된다.
 */
public final class NextRunCalculator {

    private NextRunCalculator() {
    }

    /**
     * @return 다음 실행 시각. 한 번 예약이 이미 지났으면 null
     */
    public static LocalDateTime next(ScheduleRule rule, LocalDateTime after) {
        return switch (rule.repeatType()) {
            case ScheduleRule.ONCE -> {
                LocalDateTime at = rule.runDate().atTime(rule.runTime());
                yield at.isAfter(after) ? at : null;
            }
            case ScheduleRule.WEEKLY -> {
                LocalDateTime at = after.toLocalDate()
                        .with(TemporalAdjusters.nextOrSame(rule.dayOfWeek()))
                        .atTime(rule.runTime());
                yield at.isAfter(after) ? at : at.plusWeeks(1);
            }
            case ScheduleRule.MONTHLY -> {
                YearMonth month = YearMonth.from(after);
                LocalDateTime at = dayIn(month, rule.dayOfMonth()).atTime(rule.runTime());
                yield at.isAfter(after) ? at : dayIn(month.plusMonths(1), rule.dayOfMonth()).atTime(rule.runTime());
            }
            default -> throw new IllegalArgumentException("알 수 없는 repeatType: " + rule.repeatType());
        };
    }

    /** 그 달에 없는 날(예: 2월 30일)은 말일로 바꾼다 */
    private static LocalDate dayIn(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }
}
