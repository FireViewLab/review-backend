package com.example.fireview.domain.cartschedule;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 챗봇 응답 머리 줄 {@code SCHEDULE:} 의 값. 모델이 사용자의 예약 요청을 정해진 형식으로 옮긴 것이다.
 *
 * <pre>
 * once 2026-12-01 09:00 qty=1
 * weekly MON 09:00 qty=1
 * monthly 1 09:00 qty=1
 * </pre>
 *
 * <p>형식이 조금이라도 어긋나면 예약하지 않는다(fail-closed). 잘못 읽어 원하지 않는 상품이
 * 장바구니에 담기는 것보다 제안을 만들지 않는 편이 낫다. 시각과 수량은 빠지면 09:00, 1개로 본다.
 *
 * @param quantity 담을 수량 1~10
 */
public record ScheduleRequest(ScheduleRule rule, int quantity) {

    public static final int MAX_QUANTITY = 10;
    public static final LocalTime DEFAULT_TIME = LocalTime.of(9, 0);

    private static final String TAIL = "(?:\\s+(\\d{1,2}):(\\d{2}))?(?:\\s+qty=(\\d{1,2}))?\\s*";
    private static final Pattern ONCE = Pattern.compile("once\\s+(\\d{4})-(\\d{2})-(\\d{2})" + TAIL);
    private static final Pattern WEEKLY = Pattern.compile("weekly\\s+([a-z]{3})" + TAIL);
    private static final Pattern MONTHLY = Pattern.compile("monthly\\s+(\\d{1,2})" + TAIL);

    private static final Map<String, DayOfWeek> DAYS = Map.of(
            "mon", DayOfWeek.MONDAY, "tue", DayOfWeek.TUESDAY, "wed", DayOfWeek.WEDNESDAY,
            "thu", DayOfWeek.THURSDAY, "fri", DayOfWeek.FRIDAY, "sat", DayOfWeek.SATURDAY,
            "sun", DayOfWeek.SUNDAY);

    /**
     * @param now 한국 시간 현재 시각. 한 번 예약이 지났거나 1년 넘게 먼지 본다
     * @return 예약 요청이 아니거나 형식이 어긋나면 비어 있다
     */
    public static Optional<ScheduleRequest> parse(String value, LocalDateTime now) {
        if (value == null) return Optional.empty();
        String text = value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        try {
            Matcher m;
            if ((m = ONCE.matcher(text)).matches()) {
                LocalDate date = LocalDate.of(
                        Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                LocalDateTime at = date.atTime(time(m, 4));
                if (!at.isAfter(now) || at.isAfter(now.plusYears(1))) return Optional.empty();
                return withQuantity(ScheduleRule.once(date, time(m, 4)), m.group(6));
            }
            if ((m = WEEKLY.matcher(text)).matches()) {
                DayOfWeek day = DAYS.get(m.group(1));
                if (day == null) return Optional.empty();
                return withQuantity(ScheduleRule.weekly(day, time(m, 2)), m.group(4));
            }
            if ((m = MONTHLY.matcher(text)).matches()) {
                int day = Integer.parseInt(m.group(1));
                if (day < 1 || day > 31) return Optional.empty();
                return withQuantity(ScheduleRule.monthly(day, time(m, 2)), m.group(4));
            }
        } catch (DateTimeException | IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** {@code group} 이 시, {@code group + 1} 이 분이다. 시각이 없으면 09:00 */
    private static LocalTime time(Matcher m, int group) {
        if (m.group(group) == null) return DEFAULT_TIME;
        return LocalTime.of(Integer.parseInt(m.group(group)), Integer.parseInt(m.group(group + 1)));
    }

    private static Optional<ScheduleRequest> withQuantity(ScheduleRule rule, String raw) {
        int quantity = raw == null ? 1 : Integer.parseInt(raw);
        if (quantity < 1 || quantity > MAX_QUANTITY) return Optional.empty();
        return Optional.of(new ScheduleRequest(rule, quantity));
    }
}
