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
    @Test
    void 끝의_product_값으로_예약할_상품을_지정한다() {
        assertThat(ScheduleRequest.parse("weekly SUN 09:00 qty=1 product=kurly-1000146248", NOW)).contains(
                new ScheduleRequest(ScheduleRule.weekly(DayOfWeek.SUNDAY, LocalTime.of(9, 0)), 1, "kurly-1000146248"));
    }

    @Test
    void product_값의_대소문자는_그대로_둔다() {
        // 올리브영 상품 번호처럼 대문자가 들어간 주소가 있다
        assertThat(ScheduleRequest.parse("monthly 1 product=oliveyoung-A000000123456", NOW))
                .map(ScheduleRequest::productRef).contains("oliveyoung-A000000123456");
    }

    @Test
    void product_값이_없으면_null이다() {
        assertThat(ScheduleRequest.parse("weekly SUN 09:00 qty=1", NOW).orElseThrow().productRef()).isNull();
    }

    @Test
    void product_값이_비었거나_뒤에_다른_말이_있으면_예약하지_않는다() {
        assertThat(ScheduleRequest.parse("weekly SUN 09:00 qty=1 product=", NOW)).isEmpty();
        assertThat(ScheduleRequest.parse("weekly SUN 09:00 qty=1 product=kurly-1 extra", NOW)).isEmpty();
    }
    @Test
    void 규칙을_SCHEDULE_줄_형식으로_적고_다시_읽을_수_있다() {
        ScheduleRule weekly = ScheduleRule.weekly(DayOfWeek.SATURDAY, LocalTime.of(9, 0));
        ScheduleRule monthly = ScheduleRule.monthly(20, LocalTime.of(18, 30));

        assertThat(ScheduleRequest.format(weekly, 1)).isEqualTo("weekly SAT 09:00 qty=1");
        assertThat(ScheduleRequest.format(monthly, 2)).isEqualTo("monthly 20 18:30 qty=2");
        assertThat(ScheduleRequest.parse(ScheduleRequest.format(weekly, 1), NOW))
                .contains(new ScheduleRequest(weekly, 1));
    }
}
