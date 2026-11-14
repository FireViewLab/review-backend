package com.example.fireview.domain.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Redis 사용량 카운터가 INCR 결과로 한도를 판단하고 날짜별 키에 자정 만료를 거는지 본다.
 * Redis 에 닿지 못하면 챗봇을 막지 않고 한도 검사를 건너뛴다(#240).
 */
class RedisChatQuotaStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> ops;
    private RedisChatQuotaStore store;
    private String key;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        store = new RedisChatQuotaStore(redisTemplate, "Asia/Seoul");
        key = "chat:quota:7:" + LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void 한도_안이면_통과하고_자정_만료를_건다() {
        when(ops.increment(key)).thenReturn(3L);

        assertThat(store.tryConsume(7L, 5)).isTrue();

        verify(redisTemplate).expire(eq(key), any(Duration.class));
        verify(ops, never()).decrement(anyString());
    }

    @Test
    void 한도를_넘으면_되돌리고_거절한다() {
        when(ops.increment(key)).thenReturn(6L);

        assertThat(store.tryConsume(7L, 5)).isFalse();

        verify(ops).decrement(key);
    }

    @Test
    void 무제한이면_거절하지_않는다() {
        when(ops.increment(key)).thenReturn(1_000L);

        assertThat(store.tryConsume(7L, ChatQuotaStore.UNLIMITED)).isTrue();
    }

    @Test
    void 한도가_0이면_Redis_를_부르지_않고_거절한다() {
        assertThat(store.tryConsume(7L, 0)).isFalse();

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void INCR_응답이_비면_통과시킨다() {
        when(ops.increment(key)).thenReturn(null);

        assertThat(store.tryConsume(7L, 5)).isTrue();
    }

    @Test
    void 사용량은_키_값을_읽고_없거나_숫자가_아니면_0() {
        when(ops.get(key)).thenReturn("4", null, "abc");

        assertThat(store.used(7L)).isEqualTo(4);
        assertThat(store.used(7L)).isZero();
        assertThat(store.used(7L)).isZero();
    }

    @Test
    void 중복_환불로_음수가_되면_0으로_되돌린다() {
        when(ops.decrement(key)).thenReturn(-1L);

        store.refund(7L);

        verify(ops).set(eq(key), eq("0"), any(Duration.class));
    }

    @Test
    void Redis_연결이_끊기면_한도_검사를_건너뛰고_통과시킨다() {
        when(ops.increment(key)).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(store.tryConsume(7L, 5)).isTrue();
    }

    @Test
    void Redis_연결이_끊기면_사용량은_0() {
        when(ops.get(key)).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(store.used(7L)).isZero();
    }

    @Test
    void Redis_연결이_끊겨도_환불은_예외를_던지지_않는다() {
        when(ops.decrement(key)).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> store.refund(7L)).doesNotThrowAnyException();
    }

    @Test
    void 정상_환불은_값을_덮어쓰지_않는다() {
        when(ops.decrement(key)).thenReturn(2L);

        store.refund(7L);

        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
