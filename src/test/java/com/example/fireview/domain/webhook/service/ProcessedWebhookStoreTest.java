package com.example.fireview.domain.webhook.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 같은 jobId 웹훅은 한 번만 처리하고, 실패하면 다시 받을 수 있게 되돌린다.
 */
class ProcessedWebhookStoreTest {

    @Test
    void 인메모리_처음_보는_jobId_만_통과한다() {
        ProcessedWebhookStore store = new ProcessedWebhookStore();

        assertThat(store.markIfAbsent("job-1")).isTrue();
        assertThat(store.markIfAbsent("job-1")).isFalse();
        assertThat(store.markIfAbsent("job-2")).isTrue();
    }

    @Test
    void 인메모리_되돌리면_다시_처리할_수_있다() {
        ProcessedWebhookStore store = new ProcessedWebhookStore();
        store.markIfAbsent("job-1");

        store.release("job-1");

        assertThat(store.markIfAbsent("job-1")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void Redis_는_SET_NX_와_24시간_TTL_로_표시한다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent("webhook:processed:job-1", "1", Duration.ofHours(24))).thenReturn(true, false);
        RedisProcessedWebhookStore store = new RedisProcessedWebhookStore(redisTemplate);

        assertThat(store.markIfAbsent("job-1")).isTrue();
        assertThat(store.markIfAbsent("job-1")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void Redis_응답이_비면_처리하지_않은_것으로_본다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        RedisProcessedWebhookStore store = new RedisProcessedWebhookStore(redisTemplate);

        assertThat(store.markIfAbsent("job-1")).isFalse();
    }

    @Test
    void Redis_되돌리기는_키를_지운다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisProcessedWebhookStore store = new RedisProcessedWebhookStore(redisTemplate);

        store.release("job-1");

        verify(redisTemplate).delete("webhook:processed:job-1");
    }
}
