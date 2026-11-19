package com.example.fireview.domain.dashboard.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Redis 인기 검색어 집계가 날짜별 키에 세고, 최근 7일을 합쳐 순위를 내는지 본다(#247).
 */
class RedisSearchKeywordCounterTest {

    private StringRedisTemplate redisTemplate;
    private ZSetOperations<String, String> zset;
    private RedisSearchKeywordCounter counter;
    private LocalDate today;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zset);
        counter = new RedisSearchKeywordCounter(redisTemplate, "Asia/Seoul");
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void 오늘_키에_정규화한_검색어를_하나_올리고_만료를_건다() {
        counter.record("  Nike ");

        String key = "search:keywords:" + today;
        verify(zset).incrementScore(key, "nike", 1);
        verify(redisTemplate).expire(eq(key), any(Duration.class));
    }

    @Test
    void 셀_수_없는_검색어는_Redis_를_부르지_않는다() {
        counter.record(" ");

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void 최근_7일_키를_합쳐_상위만_읽는다() {
        String dest = "search:keywords:top:" + today;
        when(redisTemplate.hasKey(dest)).thenReturn(false);
        when(zset.reverseRange(dest, 0, 9)).thenReturn(new LinkedHashSet<>(List.of("나이키", "에어팟")));

        assertThat(counter.top(10)).containsExactly("나이키", "에어팟");

        verify(zset).unionAndStore("search:keywords:" + today, List.of(
                "search:keywords:" + today.minusDays(1), "search:keywords:" + today.minusDays(2),
                "search:keywords:" + today.minusDays(3), "search:keywords:" + today.minusDays(4),
                "search:keywords:" + today.minusDays(5), "search:keywords:" + today.minusDays(6)), dest);
        verify(redisTemplate).expire(dest, Duration.ofSeconds(RedisSearchKeywordCounter.TOP_TTL_SECONDS));
    }

    @Test
    void 합친_결과가_남아_있으면_다시_합치지_않는다() {
        String dest = "search:keywords:top:" + today;
        when(redisTemplate.hasKey(dest)).thenReturn(true);
        when(zset.reverseRange(dest, 0, 9)).thenReturn(new LinkedHashSet<>(List.of("홍삼")));

        assertThat(counter.top(10)).containsExactly("홍삼");

        verify(zset, never()).unionAndStore(anyString(), anyCollection(), anyString());
    }

    @Test
    void Redis_연결이_끊기면_기록을_건너뛴다() {
        when(zset.incrementScore(anyString(), anyString(), eq(1.0)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> counter.record("나이키")).doesNotThrowAnyException();
    }

    @Test
    void Redis_연결이_끊기면_인기_검색어는_비어_있다() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(counter.top(10)).isEmpty();
    }
}
