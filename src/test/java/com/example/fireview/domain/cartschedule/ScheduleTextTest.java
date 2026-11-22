package com.example.fireview.domain.cartschedule;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleTextTest {

    @Test
    void 예약을_한국어_한_줄로_쓴다() {
        assertThat(ScheduleText.describe(ScheduleRule.once(LocalDate.of(2026, 12, 1), LocalTime.of(9, 0)), 1))
                .isEqualTo("12월 1일(화) 오전 9시 · 1개");
        assertThat(ScheduleText.describe(ScheduleRule.weekly(DayOfWeek.MONDAY, LocalTime.of(18, 30)), 2))
                .isEqualTo("매주 월요일 오후 6시 30분 · 2개");
        assertThat(ScheduleText.describe(ScheduleRule.monthly(31, LocalTime.of(0, 0)), 1))
                .isEqualTo("매월 31일(없는 달은 말일) 오전 12시 · 1개");
        assertThat(ScheduleText.describe(ScheduleRule.monthly(15, LocalTime.of(12, 5)), 3))
                .isEqualTo("매월 15일 오후 12시 5분 · 3개");
    }
}
