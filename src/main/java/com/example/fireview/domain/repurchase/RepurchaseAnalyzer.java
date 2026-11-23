package com.example.fireview.domain.repurchase;

import com.example.fireview.domain.cartschedule.ScheduleRule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 한 상품을 담은 날짜들로 반복 주기와 "다시 살 때"를 계산한다. DB 를 쓰지 않는다.
 *
 * <p>구매 데이터가 없어 장바구니에 담은 기록을 반복 구매의 대리 신호로 쓴다. 그래서 문장에서도
 * "구매"가 아니라 "담은"이라고 쓴다.
 */
public final class RepurchaseAnalyzer {

    /** 경과일이 주기의 이 비율을 넘으면 다시 살 때로 본다. 다 떨어지기 조금 전에 알려 주려는 값이다 */
    static final double DUE_RATIO = 0.8;

    private static final LocalTime SUGGESTED_TIME = LocalTime.of(9, 0);

    private RepurchaseAnalyzer() {
    }

    /**
     * @param addedOn 담은 날짜들. 순서와 중복은 상관없다
     * @return 담은 날이 이틀 이상이면 분석 결과. 하루뿐이면 반복이라 볼 수 없어 비어 있다
     */
    public static Optional<RepurchaseStats> analyze(Collection<LocalDate> addedOn, LocalDate today) {
        TreeSet<LocalDate> days = new TreeSet<>(addedOn);
        if (days.size() < 2) return Optional.empty();

        List<Long> gaps = new ArrayList<>();
        LocalDate previous = null;
        for (LocalDate day : days) {
            if (previous != null) gaps.add(ChronoUnit.DAYS.between(previous, day));
            previous = day;
        }
        int interval = Math.max(1, median(gaps));
        LocalDate last = days.last();
        int since = (int) Math.max(0, ChronoUnit.DAYS.between(last, today));
        boolean due = since >= Math.ceil(interval * DUE_RATIO);

        return Optional.of(new RepurchaseStats(interval, last, since, days.size(), due,
                (double) since / interval, suggest(interval, last), reason(interval, since)));
    }

    private static int median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) return sorted.get(mid).intValue();
        return (int) Math.round((sorted.get(mid - 1) + sorted.get(mid)) / 2.0);
    }

    /** 장바구니 예약이 지원하는 주기(매주·매월)에 가까울 때만 제안한다. 격주 같은 주기는 지원하지 않는다 */
    private static ScheduleRule suggest(int interval, LocalDate last) {
        if (interval >= 5 && interval <= 9) return ScheduleRule.weekly(last.getDayOfWeek(), SUGGESTED_TIME);
        if (interval >= 25 && interval <= 35) return ScheduleRule.monthly(last.getDayOfMonth(), SUGGESTED_TIME);
        return null;
    }

    private static String reason(int interval, int since) {
        String when = since == 0 ? "오늘" : since + "일 전";
        return "약 %d일마다 담으셨고 마지막은 %s이에요".formatted(interval, when);
    }
}
