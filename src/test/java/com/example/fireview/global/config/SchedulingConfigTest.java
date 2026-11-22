package com.example.fireview.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 정기 작업 중 하나라도 켜면 스케줄링이 켜진다. 홈 자동 채우기를 꺼도 예약·동기화는 돌아야 한다 */
class SchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void 모두_꺼져_있으면_스케줄링을_켜지_않는다() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(SchedulingConfig.class));
    }

    @Test
    void 장바구니_예약만_켜도_스케줄링을_켠다() {
        runner.withPropertyValues("app.cart-schedule.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(SchedulingConfig.class));
    }

    @Test
    void 카탈로그_동기화만_켜도_스케줄링을_켠다() {
        runner.withPropertyValues("app.data-catalog-sync.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(SchedulingConfig.class));
    }

    @Test
    void 홈_자동_채우기를_켜면_스케줄링을_켠다() {
        runner.withPropertyValues("app.home-refresh.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(SchedulingConfig.class));
    }
}
