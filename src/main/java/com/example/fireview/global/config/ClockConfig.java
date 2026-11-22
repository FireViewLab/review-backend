package com.example.fireview.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 서비스 시각. 예약처럼 "지금"에 따라 결과가 달라지는 코드는 {@code LocalDateTime.now()} 대신 이 빈을 받는다.
 * 테스트가 시각을 고정할 수 있고, 서버 시간대와 상관없이 한국 시간으로 계산한다.
 */
@Configuration
public class ClockConfig {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(SEOUL);
    }
}
