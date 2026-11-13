package com.example.fireview.domain.auth.service;

import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 재설정 토큰은 GETDEL 한 번으로 소비한다(#233). GET 과 DELETE 로 나누면 동시 요청이 둘 다 통과한다.
 */
class RedisPasswordResetTokenStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> ops;
    private RedisPasswordResetTokenStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        store = new RedisPasswordResetTokenStore(redisTemplate);
    }

    @Test
    void 토큰을_GETDEL_한_번으로_소비한다() {
        when(ops.getAndDelete("pwd-reset:abc")).thenReturn("user@example.com");

        assertThat(store.consume("abc")).isEqualTo("user@example.com");

        verify(ops, never()).get(anyString());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void 이미_소비했거나_만료된_토큰은_거부한다() {
        when(ops.getAndDelete("pwd-reset:abc")).thenReturn(null);

        assertThatThrownBy(() -> store.consume("abc"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_RESET_TOKEN);
    }
}
