package com.example.fireview.domain.auth.service;

import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 인메모리 기반 비밀번호 재설정 토큰 저장소 (기본 구현).
 * 운영 환경에서는 app.auth.redis-token-store.enabled=true 설정 시
 * RedisPasswordResetTokenStore 가 이 빈을 대체한다.
 *
 * 단일 인스턴스 환경에서만 사용 가능하며 서버 재시작 시 토큰이 초기화된다.
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "app.auth.redis-token-store.enabled", havingValue = "false", matchIfMissing = true)
public class PasswordResetTokenStore {

    private static final Duration TTL = Duration.ofMinutes(15);

    private record Entry(String email, Instant expiresAt) {}

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public String issue(String email) {
        String token = UUID.randomUUID().toString();
        store.put(token, new Entry(email, Instant.now().plus(TTL)));
        return token;
    }

    /**
     * 토큰을 소비하고 이메일을 반환한다.
     *
     * 기존 문제: store.remove() 로 먼저 삭제 후 만료 여부를 체크해서
     *           만료 토큰과 존재하지 않는 토큰을 구분할 수 없었음.
     *
     * 수정: 꺼내면서 지운 항목으로 존재 여부 → 만료 여부 순으로 체크한다.
     *       만료 시 로그를 남겨 디버깅/모니터링 가능하도록 개선.
     *
     * 조회와 삭제를 remove 한 번으로 한다(#235). get 뒤에 remove 를 따로 부르면 그 사이에 들어온
     * 같은 토큰 요청도 항목을 읽어 1회용 토큰이 두 번 쓰인다. Redis 구현의 GETDEL(#233)과 같은 이유다.
     *
     * @throws CustomException INVALID_RESET_TOKEN - 토큰 없음 또는 만료
     */
    public String consume(String token) {
        Entry entry = store.remove(token);

        if (entry == null) {
            log.warn("[PasswordReset] 존재하지 않는 토큰 사용 시도");
            throw new CustomException(ErrorCode.INVALID_RESET_TOKEN);
        }

        if (entry.expiresAt().isBefore(Instant.now())) {
            log.warn("[PasswordReset] 만료된 토큰 사용 시도: email={}", entry.email());
            throw new CustomException(ErrorCode.EXPIRED_RESET_TOKEN);
        }

        return entry.email();
    }
}
