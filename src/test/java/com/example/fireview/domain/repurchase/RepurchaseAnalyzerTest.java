package com.example.fireview.domain.repurchase;

import com.example.fireview.domain.cartschedule.ScheduleRule;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepurchaseAnalyzerTest {

    private static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    @Test
    void 담은_날이_하루뿐이면_후보가_아니다() {
        assertThat(RepurchaseAnalyzer.analyze(List.of(d(11, 1), d(11, 1)), d(11, 23))).isEmpty();
        assertThat(RepurchaseAnalyzer.analyze(List.of(), d(11, 23))).isEmpty();
    }

    @Test
    void 주기는_간격의_중앙값이고_같은_날은_하루로_센다() {
        // 간격 14, 14, 16 → 중앙값 14. 11월 1일의 두 번은 하루로 센다
        RepurchaseStats s = RepurchaseAnalyzer.analyze(
                List.of(d(10, 4), d(10, 18), d(11, 1), d(11, 1), d(11, 17)), d(11, 23)).orElseThrow();

        assertThat(s.intervalDays()).isEqualTo(14);
        assertThat(s.timesAdded()).isEqualTo(4);
        assertThat(s.lastAddedOn()).isEqualTo(d(11, 17));
        assertThat(s.daysSinceLast()).isEqualTo(6);
    }

    @Test
    void 간격이_짝수개면_가운데_두_값의_평균을_반올림한다() {
        // 간격 7, 10 → 8.5 → 9
        RepurchaseStats s = RepurchaseAnalyzer.analyze(List.of(d(11, 1), d(11, 8), d(11, 18)), d(11, 23))
                .orElseThrow();

        assertThat(s.intervalDays()).isEqualTo(9);
    }

    @Test
    void 경과일이_주기의_80퍼센트_이상이면_다시_살_때다() {
        // 주기 10 → 기준 8일
        assertThat(RepurchaseAnalyzer.analyze(List.of(d(11, 1), d(11, 11)), d(11, 19)).orElseThrow().due()).isTrue();
        assertThat(RepurchaseAnalyzer.analyze(List.of(d(11, 1), d(11, 11)), d(11, 18)).orElseThrow().due()).isFalse();
    }

    @Test
    void 점수는_경과일을_주기로_나눈_값이다() {
        RepurchaseStats s = RepurchaseAnalyzer.analyze(List.of(d(11, 1), d(11, 11)), d(11, 26)).orElseThrow();

        assertThat(s.score()).isEqualTo(1.5);
    }

    @Test
    void 주기가_5에서_9일이면_매주_마지막으로_담은_요일로_제안한다() {
        // 2026-11-15 는 일요일
        RepurchaseStats s = RepurchaseAnalyzer.analyze(List.of(d(11, 8), d(11, 15)), d(11, 23)).orElseThrow();

        assertThat(s.suggestion()).isEqualTo(ScheduleRule.weekly(DayOfWeek.SUNDAY, LocalTime.of(9, 0)));
    }

    @Test
    void 주기가_25에서_35일이면_매월_마지막으로_담은_날로_제안한다() {
        RepurchaseStats s = RepurchaseAnalyzer.analyze(List.of(d(9, 20), d(10, 20)), d(11, 23)).orElseThrow();

        assertThat(s.suggestion()).isEqualTo(ScheduleRule.monthly(20, LocalTime.of(9, 0)));
    }

    @Test
    void 지원하지_않는_주기는_예약을_제안하지_않는다() {
        assertThat(RepurchaseAnalyzer.analyze(List.of(d(10, 1), d(10, 15)), d(11, 23)).orElseThrow().suggestion())
                .isNull();
        assertThat(RepurchaseAnalyzer.analyze(List.of(d(11, 1), d(11, 4)), d(11, 23)).orElseThrow().suggestion())
                .isNull();
    }

    @Test
    void 이유는_실제_주기와_경과일로_쓴다() {
        assertThat(RepurchaseAnalyzer.analyze(List.of(d(10, 24), d(11, 7)), d(11, 23)).orElseThrow().reason())
                .isEqualTo("약 14일마다 담으셨고 마지막은 16일 전이에요");
        assertThat(RepurchaseAnalyzer.analyze(List.of(d(11, 9), d(11, 23)), d(11, 23)).orElseThrow().reason())
                .isEqualTo("약 14일마다 담으셨고 마지막은 오늘이에요");
    }
}
