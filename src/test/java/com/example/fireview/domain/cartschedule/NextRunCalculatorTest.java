package com.example.fireview.domain.cartschedule;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class NextRunCalculatorTest {

    private static final LocalTime NINE = LocalTime.of(9, 0);

    @Test
    void 한_번_예약은_정한_날짜와_시각이다() {
        ScheduleRule rule = ScheduleRule.once(LocalDate.of(2026, 12, 1), NINE);

        assertThat(NextRunCalculator.next(rule, LocalDateTime.of(2026, 11, 22, 10, 0)))
                .isEqualTo(LocalDateTime.of(2026, 12, 1, 9, 0));
    }

    @Test
    void 한_번_예약이_지났으면_다음은_없다() {
        ScheduleRule rule = ScheduleRule.once(LocalDate.of(2026, 12, 1), NINE);

        assertThat(NextRunCalculator.next(rule, LocalDateTime.of(2026, 12, 1, 9, 0))).isNull();
    }

    @Test
    void 매주는_다가오는_그_요일이다() {
        ScheduleRule rule = ScheduleRule.weekly(DayOfWeek.MONDAY, NINE);

        // 2026-11-22 는 일요일
        assertThat(NextRunCalculator.next(rule, LocalDateTime.of(2026, 11, 22, 10, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 23, 9, 0));
    }

    @Test
    void 매주_같은_요일_시각이_지났으면_다음_주다() {
        ScheduleRule rule = ScheduleRule.weekly(DayOfWeek.MONDAY, NINE);

        assertThat(NextRunCalculator.next(rule, LocalDateTime.of(2026, 11, 23, 9, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 30, 9, 0));
        assertThat(NextRunCalculator.next(rule, LocalDateTime.of(2026, 11, 23, 8, 59)))
                .isEqualTo(LocalDateTime.of(2026, 11, 23, 9, 0));
    }

    @Test
    void 매월은_이번_달_그날이고_지났으면_다음_달이다() {
        ScheduleRule rule = ScheduleRule.monthly(1, NINE);

        assertThat(NextRunCalculator.next(rule, LocalDateTime.of(2026, 11, 22, 10, 0)))
                .isEqualTo(LocalDateTime.of(2026, 12, 1, 9, 0));
        assertThat(NextRunCalculator.next(rule, LocalDateTime.of(2026, 11, 30, 23, 0)))
                .isEqualTo(LocalDateTime.of(2026, 12, 1, 9, 0));
    }

    @Test
    void 그날이_없는_달은_말일이다() {
        ScheduleRule rule = ScheduleRule.monthly(31, NINE);

        assertThat(NextRunCalculator.next(rule, LocalDateTime.of(2026, 11, 22, 10, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 30, 9, 0));
        assertThat(NextRunCalculator.next(rule, LocalDateTime.of(2027, 1, 31, 10, 0)))
                .isEqualTo(LocalDateTime.of(2027, 2, 28, 9, 0));
    }

    @Test
    void 놓친_회차는_몰아서_돌리지_않고_지금_이후로_넘긴다() {
        ScheduleRule rule = ScheduleRule.weekly(DayOfWeek.MONDAY, NINE);

        // 3주 동안 서버가 꺼져 있었어도 다음은 지금 이후 가장 가까운 월요일 한 번이다
        assertThat(NextRunCalculator.next(rule, LocalDateTime.of(2026, 12, 16, 12, 0)))
                .isEqualTo(LocalDateTime.of(2026, 12, 21, 9, 0));
    }
}
