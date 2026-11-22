package com.example.fireview.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 정기 작업을 켠다. 홈 자동 채우기, 카탈로그 동기화, 장바구니 예약 중 하나라도 켜져 있으면 켠다.
 * 예전에는 홈 자동 채우기 설정만 봐서, 그것을 끄면 카탈로그 동기화와 예약 실행까지 조용히 멈췄다.
 *
 * <p>항상 켜 두지 않는 이유: 테스트·로컬에서 외부 서버 호출이 저절로 나가면 안 된다.
 */
@Configuration
@EnableScheduling
@ConditionalOnExpression("${app.home-refresh.enabled:false} or ${app.data-catalog-sync.enabled:false}"
        + " or ${app.cart-schedule.enabled:false}")
public class SchedulingConfig {
}
