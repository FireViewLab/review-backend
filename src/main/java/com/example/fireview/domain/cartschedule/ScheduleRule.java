package com.example.fireview.domain.cartschedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * 장바구니 예약이 언제 도는지. 셋 중 하나만 쓴다.
 *
 * @param repeatType {@link #ONCE} / {@link #WEEKLY} / {@link #MONTHLY}
 * @param runDate    한 번 예약의 날짜. 그 밖에는 null
 * @param dayOfWeek  매주 예약의 요일. 그 밖에는 null
 * @param dayOfMonth 매월 예약의 날(1~31). 그 달에 없는 날이면 말일에 돈다. 그 밖에는 null
 * @param runTime    도는 시각(한국 시간)
 */
public record ScheduleRule(String repeatType, LocalDate runDate, DayOfWeek dayOfWeek,
                           Integer dayOfMonth, LocalTime runTime) {

    public static final String ONCE = "ONCE";
    public static final String WEEKLY = "WEEKLY";
    public static final String MONTHLY = "MONTHLY";

    public ScheduleRule {
        Objects.requireNonNull(repeatType, "repeatType");
        Objects.requireNonNull(runTime, "runTime");
        switch (repeatType) {
            case ONCE -> Objects.requireNonNull(runDate, "runDate");
            case WEEKLY -> Objects.requireNonNull(dayOfWeek, "dayOfWeek");
            case MONTHLY -> {
                if (dayOfMonth == null || dayOfMonth < 1 || dayOfMonth > 31) {
                    throw new IllegalArgumentException("dayOfMonth 는 1~31 이다: " + dayOfMonth);
                }
            }
            default -> throw new IllegalArgumentException("알 수 없는 repeatType: " + repeatType);
        }
    }

    public static ScheduleRule once(LocalDate date, LocalTime time) {
        return new ScheduleRule(ONCE, date, null, null, time);
    }

    public static ScheduleRule weekly(DayOfWeek day, LocalTime time) {
        return new ScheduleRule(WEEKLY, null, day, null, time);
    }

    public static ScheduleRule monthly(int day, LocalTime time) {
        return new ScheduleRule(MONTHLY, null, null, day, time);
    }

    public boolean repeats() {
        return !ONCE.equals(repeatType);
    }
}
