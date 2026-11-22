package com.example.fireview.domain.cartschedule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.Locale;

/** 예약을 사용자에게 보여줄 한 줄로 쓴다. 예: "매주 월요일 오전 9시 · 1개" */
public final class ScheduleText {

    private ScheduleText() {
    }

    public static String describe(ScheduleRule rule, int quantity) {
        String when = switch (rule.repeatType()) {
            case ScheduleRule.ONCE -> "%d월 %d일(%s)".formatted(rule.runDate().getMonthValue(),
                    rule.runDate().getDayOfMonth(), shortDay(rule.runDate().getDayOfWeek()));
            case ScheduleRule.WEEKLY -> "매주 " + rule.dayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN);
            case ScheduleRule.MONTHLY -> "매월 %d일%s".formatted(rule.dayOfMonth(),
                    rule.dayOfMonth() > 28 ? "(없는 달은 말일)" : "");
            default -> rule.repeatType();
        };
        return "%s %s · %d개".formatted(when, time(rule.runTime()), quantity);
    }

    private static String shortDay(DayOfWeek day) {
        return day.getDisplayName(TextStyle.SHORT, Locale.KOREAN);
    }

    private static String time(LocalTime t) {
        String half = t.getHour() < 12 ? "오전" : "오후";
        int hour = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return t.getMinute() == 0 ? "%s %d시".formatted(half, hour) : "%s %d시 %d분".formatted(half, hour, t.getMinute());
    }
}
