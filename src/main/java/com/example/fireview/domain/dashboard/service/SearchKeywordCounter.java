package com.example.fireview.domain.dashboard.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/**
 * 인기 검색어 집계 (인메모리 기본 구현, #247).
 *
 * <p>검색어를 날짜별로 세고, 최근 {@value #WINDOW_DAYS}일 합계로 순위를 낸다. 누적 횟수만 두면
 * 초기 시드나 오래전 유행어가 영원히 상위를 차지한다.
 *
 * <p>운영에서는 {@code app.search.redis-keywords.enabled=true} 로 {@link RedisSearchKeywordCounter}
 * 가 이 빈을 대체한다. 인메모리 구현은 인스턴스마다 따로 세고 재시작하면 비워진다.
 *
 * <p><b>하루 기준</b>은 {@code app.search.keywords.zone}(기본 Asia/Seoul)의 자정이다.
 */
@Component
@ConditionalOnProperty(name = "app.search.redis-keywords.enabled", havingValue = "false", matchIfMissing = true)
public class SearchKeywordCounter {

    /** 순위에 넣는 기간(오늘 포함) */
    public static final int WINDOW_DAYS = 7;

    /** 이보다 긴 검색어는 세지 않는다. 문장·붙여넣기가 공개 대시보드에 오르지 않게 한다 */
    public static final int MAX_LENGTH = 30;

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    protected final ZoneId zone;
    private final Clock clock;

    private final Map<LocalDate, Map<String, LongAdder>> daily = new ConcurrentHashMap<>();

    /** 생성자가 둘이라 스프링이 쓸 것을 지정한다. 시계를 받는 생성자는 테스트용이다 */
    @Autowired
    public SearchKeywordCounter(@Value("${app.search.keywords.zone:Asia/Seoul}") String zoneId) {
        this(Clock.system(ZoneId.of(zoneId)));
    }

    SearchKeywordCounter(Clock clock) {
        this.clock = clock;
        this.zone = clock.getZone();
    }

    /** 검색 한 번을 센다. 셀 수 없는 검색어(빈 값·너무 긴 값)는 무시한다 */
    public void record(String keyword) {
        normalize(keyword).ifPresent(k -> {
            LocalDate today = today();
            daily.computeIfAbsent(today, d -> new ConcurrentHashMap<>())
                    .computeIfAbsent(k, x -> new LongAdder())
                    .increment();
            daily.keySet().removeIf(d -> d.isBefore(today.minusDays(WINDOW_DAYS - 1)));
        });
    }

    /** 최근 {@value #WINDOW_DAYS}일 합계 상위 {@code limit}개. 같으면 가나다순 */
    public List<String> top(int limit) {
        LocalDate from = today().minusDays(WINDOW_DAYS - 1);
        Map<String, Long> sum = new HashMap<>();
        daily.forEach((day, counts) -> {
            if (day.isBefore(from)) return;
            counts.forEach((k, n) -> sum.merge(k, n.sum(), Long::sum));
        });
        return sum.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.naturalOrder())))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    protected LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * 앞뒤 공백을 지우고, 안쪽 공백을 하나로 줄이고, 영문은 소문자로 맞춘다.
     * "Nike"와 "nike "를 다른 검색어로 세지 않기 위해서다.
     * 비었거나 {@value #MAX_LENGTH}자를 넘으면 세지 않는다.
     */
    static Optional<String> normalize(String keyword) {
        if (keyword == null) return Optional.empty();
        String k = SPACES.matcher(CONTROL.matcher(keyword).replaceAll(" ")).replaceAll(" ")
                .strip().toLowerCase(Locale.ROOT);
        if (k.isEmpty() || k.length() > MAX_LENGTH) return Optional.empty();
        return Optional.of(k);
    }
}
