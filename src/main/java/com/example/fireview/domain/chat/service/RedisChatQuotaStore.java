package com.example.fireview.domain.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 기반 하루 사용량 카운터.
 * {@code app.chat.redis-quota.enabled=true} 일 때 {@link ChatQuotaStore} 빈을 대체한다.
 *
 * <p>INCR 가 원자적이라 인스턴스가 여러 대여도 한도가 하나로 공유되고,
 * 서버를 재시작해도 사용량이 유지된다. 날짜별 키의 만료는 Redis 가 처리한다.
 *
 * <p><b>Redis 장애</b>: 연결 실패·타임아웃은 예외({@link DataAccessException})로 올라온다.
 * 과금 경계보다 서비스 연속성을 택해 한도 검사를 건너뛰고 통과시킨다. 예외를 그대로 두면
 * 챗봇 전체가 500 이 된다(#240). 리프레시 토큰 저장소 장애 때 로그인시키는 것과 같은 판단이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.chat.redis-quota.enabled", havingValue = "true")
public class RedisChatQuotaStore extends ChatQuotaStore {

    private static final String KEY_PREFIX = "chat:quota:";

    private final StringRedisTemplate redisTemplate;

    public RedisChatQuotaStore(StringRedisTemplate redisTemplate,
                               @Value("${app.chat.quota.zone:Asia/Seoul}") String zoneId) {
        super(zoneId);
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryConsume(long userId, int limit) {
        if (limit == 0) return false;

        String key = redisKey(userId);
        try {
            Long after = redisTemplate.opsForValue().increment(key);
            if (after == null) {
                // Redis 가 응답하지 않는 상황. 과금 경계보다 서비스 연속성을 택해 통과시킨다.
                log.warn("[ChatQuota] INCR 응답이 비어 있다. 한도 검사를 건너뛴다 - userId={}", userId);
                return true;
            }

            // 매번 TTL 을 다시 건다. 증가 직후에만 걸면 그 사이에 프로세스가 죽었을 때
            // 만료 없는 키가 남아 다음 날까지 사용량이 이어진다. 같은 자정을 가리키므로
            // 몇 번을 걸어도 결과는 같다.
            redisTemplate.expire(key, untilMidnight());

            if (limit != UNLIMITED && after > limit) {
                redisTemplate.opsForValue().decrement(key);
                return false;
            }
            return true;
        } catch (DataAccessException e) {
            log.warn("[ChatQuota] Redis 접근 실패. 한도 검사를 건너뛴다 - userId={}, cause={}",
                    userId, e.getClass().getSimpleName());
            return true;
        }
    }

    @Override
    public int used(long userId) {
        String value;
        try {
            value = redisTemplate.opsForValue().get(redisKey(userId));
        } catch (DataAccessException e) {
            log.warn("[ChatQuota] Redis 접근 실패. 사용량을 0 으로 본다 - userId={}, cause={}",
                    userId, e.getClass().getSimpleName());
            return 0;
        }
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("[ChatQuota] 사용량 값이 숫자가 아니다 - userId={}, value={}", userId, value);
            return 0;
        }
    }

    @Override
    public void refund(long userId) {
        String key = redisKey(userId);
        try {
            Long after = redisTemplate.opsForValue().decrement(key);
            if (after != null && after < 0) {
                // 환불이 중복으로 들어온 경우. 음수로 남겨두면 한도가 늘어난 것처럼 보인다.
                redisTemplate.opsForValue().set(key, "0", untilMidnight());
            }
        } catch (DataAccessException e) {
            // 환불은 LLM 실패 뒤에 부른다. 여기서 던지면 원래 오류(CHAT_LLM_UNAVAILABLE)를 덮는다.
            log.warn("[ChatQuota] Redis 접근 실패. 환불을 건너뛴다 - userId={}, cause={}",
                    userId, e.getClass().getSimpleName());
        }
    }

    private String redisKey(long userId) {
        return KEY_PREFIX + key(userId);
    }
}
