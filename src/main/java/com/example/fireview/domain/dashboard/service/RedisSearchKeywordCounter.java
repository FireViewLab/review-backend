package com.example.fireview.domain.dashboard.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Redis 기반 인기 검색어 집계(#247). {@code app.search.redis-keywords.enabled=true} 일 때
 * {@link SearchKeywordCounter} 빈을 대체한다.
 *
 * <p>날짜별 정렬 집합({@code search:keywords:yyyy-MM-dd})에 ZINCRBY 로 센다. 원자적이라 동시 검색에도
 * 증가분이 사라지지 않고, 인스턴스가 여러 대여도 하나로 모인다. 순위는 최근 7일 키를 ZUNIONSTORE 로
 * 합쳐 낸다. 합친 결과는 {@value #TOP_TTL_SECONDS}초 동안 재사용해 대시보드 요청마다 다시 합치지 않는다.
 *
 * <p><b>Redis 장애</b>: 검색어 집계는 부가 기능이라 검색·대시보드를 막지 않는다.
 * 기록은 건너뛰고 순위는 빈 목록을 돌려준다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.search.redis-keywords.enabled", havingValue = "true")
public class RedisSearchKeywordCounter extends SearchKeywordCounter {

    static final String KEY_PREFIX = "search:keywords:";
    static final String TOP_PREFIX = "search:keywords:top:";
    static final long TOP_TTL_SECONDS = 60;

    /** 날짜별 키는 기간보다 하루 더 남긴다. 자정 직후 합칠 때 어제 키가 먼저 사라지지 않게 한다 */
    private static final Duration DAY_KEY_TTL = Duration.ofDays(WINDOW_DAYS + 1L);

    private final StringRedisTemplate redisTemplate;

    public RedisSearchKeywordCounter(StringRedisTemplate redisTemplate,
                                     @Value("${app.search.keywords.zone:Asia/Seoul}") String zoneId) {
        super(zoneId);
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void record(String keyword) {
        normalize(keyword).ifPresent(k -> {
            String key = dayKey(today());
            try {
                redisTemplate.opsForZSet().incrementScore(key, k, 1);
                redisTemplate.expire(key, DAY_KEY_TTL);
            } catch (DataAccessException e) {
                log.warn("[SearchKeyword] Redis 접근 실패. 검색어 기록을 건너뛴다 - cause={}", e.getMessage());
            }
        });
    }

    @Override
    public List<String> top(int limit) {
        LocalDate today = today();
        String dest = TOP_PREFIX + today;
        try {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(dest))) {
                List<String> others = IntStream.range(1, WINDOW_DAYS)
                        .mapToObj(i -> dayKey(today.minusDays(i)))
                        .toList();
                redisTemplate.opsForZSet().unionAndStore(dayKey(today), others, dest);
                redisTemplate.expire(dest, Duration.ofSeconds(TOP_TTL_SECONDS));
            }
            Set<String> top = redisTemplate.opsForZSet().reverseRange(dest, 0, limit - 1L);
            return top == null ? List.of() : List.copyOf(top);
        } catch (DataAccessException e) {
            log.warn("[SearchKeyword] Redis 접근 실패. 인기 검색어를 비워 보낸다 - cause={}", e.getMessage());
            return List.of();
        }
    }

    static String dayKey(LocalDate day) {
        return KEY_PREFIX + day;
    }
}
