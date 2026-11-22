package com.example.fireview.domain.cartschedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleRequestTest {

    /** 2026-11-22 일요일 오후 3시 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 22, 15, 0);

    @Test
    void 한_번_예약을_읽는다() {
        assertThat(ScheduleRequest.parse("once 2026-12-01 09:00 qty=2", NOW)).contains(
                new ScheduleRequest(ScheduleRule.once(LocalDate.of(2026, 12, 1), LocalTime.of(9, 0)), 2));
    }

    @Test
    void 매주_예약을_읽는다() {
        assertThat(ScheduleRequest.parse("weekly MON 18:30 qty=1", NOW)).contains(
                new ScheduleRequest(ScheduleRule.weekly(DayOfWeek.MONDAY, LocalTime.of(18, 30)), 1));
    }

    @Test
    void 매월_예약을_읽는다() {
        assertThat(ScheduleRequest.parse("monthly 31 09:00 qty=1", NOW)).contains(
                new ScheduleRequest(ScheduleRule.monthly(31, LocalTime.of(9, 0)), 1));
    }

    @Test
    void 대소문자와_공백은_가리지_않는다() {
        assertThat(ScheduleRequest.parse("  Weekly   fri  07:05   QTY=3 ", NOW)).contains(
                new ScheduleRequest(ScheduleRule.weekly(DayOfWeek.FRIDAY, LocalTime.of(7, 5)), 3));
    }

    @Test
    void 시각과_수량을_빼면_오전_9시_1개다() {
        assertThat(ScheduleRequest.parse("weekly SUN", NOW)).contains(
                new ScheduleRequest(ScheduleRule.weekly(DayOfWeek.SUNDAY, LocalTime.of(9, 0)), 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "none", "", "  ",
            "once 2026-11-22 14:59 qty=1",   // 이미 지난 시각
            "once 2027-11-23 09:00 qty=1",   // 1년 넘게 먼 날짜
            "once 2026-02-30 09:00 qty=1",   // 없는 날짜
            "weekly XYZ 09:00 qty=1",        // 모르는 요일
            "monthly 0 09:00 qty=1",
            "monthly 32 09:00 qty=1",
            "weekly MON 25:00 qty=1",        // 없는 시각
            "weekly MON 09:00 qty=0",
            "weekly MON 09:00 qty=11",
            "daily 09:00 qty=1",             // 지원하지 않는 반복
            "weekly MON 09:00 qty=1 extra",  // 뒤에 다른 말
            "매주 월요일",
    })
    void 형식이_어긋나면_예약하지_않는다(String value) {
        assertThat(ScheduleRequest.parse(value, NOW)).isEmpty();
    }

    @Test
    void null도_예약하지_않는다() {
        assertThat(ScheduleRequest.parse(null, NOW)).isEmpty();
    }
}
