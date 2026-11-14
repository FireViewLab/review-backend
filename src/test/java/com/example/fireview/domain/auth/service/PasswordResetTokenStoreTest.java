package com.example.fireview.domain.auth.service;

import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 인메모리 재설정 토큰도 한 번만 쓸 수 있다(#235). 조회와 삭제를 나누면 동시 요청이 둘 다 통과한다.
 */
class PasswordResetTokenStoreTest {

    private final PasswordResetTokenStore store = new PasswordResetTokenStore();

    @Test
    void 발급한_토큰을_소비하면_이메일을_돌려준다() {
        String token = store.issue("user@example.com");

        assertThat(store.consume(token)).isEqualTo("user@example.com");
    }

    @Test
    void 같은_토큰은_두_번_쓸_수_없다() {
        String token = store.issue("user@example.com");
        store.consume(token);

        assertThatThrownBy(() -> store.consume(token))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_RESET_TOKEN);
    }

    @Test
    void 발급하지_않은_토큰은_거부한다() {
        assertThatThrownBy(() -> store.consume("unknown"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_RESET_TOKEN);
    }

    @Test
    void 동시에_소비해도_한_요청만_통과한다() throws Exception {
        int threads = 16;
        for (int round = 0; round < 50; round++) {
            String token = store.issue("user@example.com");
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger succeeded = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        store.consume(token);
                        succeeded.incrementAndGet();
                    } catch (CustomException ignored) {
                        // 뒤에 온 요청은 거부된다
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
            pool.shutdown();

            assertThat(succeeded.get()).isEqualTo(1);
        }
    }
}
