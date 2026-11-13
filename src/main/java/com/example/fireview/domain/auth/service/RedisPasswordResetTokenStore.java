package com.example.fireview.domain.auth.service;

import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis 기반 비밀번호 재설정 토큰 저장소.
 * app.auth.redis-token-store.enabled=true 일 때 PasswordResetTokenStore 빈을 대체한다.
 *
 * 인메모리 구현과 달리 스케일 아웃 환경에서도 토큰이 공유된다.
 * TTL 만료는 Redis가 자동으로 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.auth.redis-token-store.enabled", havingValue = "true")
public class RedisPasswordResetTokenStore extends PasswordResetTokenStore {

    private static final String KEY_PREFIX = "pwd-reset:";
    private static final Duration TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    @Override
    public String issue(String email) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(KEY_PREFIX + token, email, TTL);
        log.info("[PasswordReset:Redis] 토큰 발급: email={}", email);
        return token;
    }

    /**
     * 읽기와 삭제를 GETDEL 한 번으로 한다(#233). GET 뒤에 DELETE 를 따로 부르면 그 사이에 들어온
     * 같은 토큰 요청도 이메일을 받아 1회용 토큰이 두 번 쓰인다. GETDEL 은 Redis 6.2 이상이 필요하다.
     */
    @Override
    public String consume(String token) {
        String email = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + token);

        if (email == null) {
            log.warn("[PasswordReset:Redis] 존재하지 않거나 만료된 토큰 사용 시도");
            throw new CustomException(ErrorCode.INVALID_RESET_TOKEN);
        }

        log.info("[PasswordReset:Redis] 토큰 소비 완료: email={}", email);
        return email;
    }
}
