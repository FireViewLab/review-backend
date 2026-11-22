package com.example.fireview.domain.cartschedule.service;

import com.example.fireview.domain.cartschedule.entity.CartSchedule;
import com.example.fireview.domain.cartschedule.repository.CartScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 실행할 때가 된 확정 예약을 장바구니에 담는다. 한 번에 {@link #BATCH} 건씩 처리하고, 남은 것은 다음 실행이 잇는다.
 * 한 예약의 실패는 기록만 하고 나머지를 계속 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.cart-schedule.enabled", havingValue = "true")
public class CartScheduleRunner {

    static final int BATCH = 100;

    private final CartScheduleRepository scheduleRepository;
    private final CartScheduleExecutor executor;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.cart-schedule.interval-ms:300000}",
            initialDelayString = "${app.cart-schedule.interval-ms:300000}")
    public void scheduled() {
        runDue();
    }

    /** @return 이번에 장바구니에 담은 예약 수 */
    public int runDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<CartSchedule> due = scheduleRepository.findDue(now, Limit.of(BATCH));
        int ran = 0;
        for (CartSchedule schedule : due) {
            try {
                if (executor.runOne(schedule.getId(), schedule.getNextRunAt(), now)) ran++;
            } catch (RuntimeException e) {
                log.warn("[CartSchedule] 예약 실행 실패, 다음 실행에 다시 시도 - scheduleId={}", schedule.getId(), e);
            }
        }
        if (!due.isEmpty()) {
            log.info("[CartSchedule] 예약 {}건 중 {}건을 장바구니에 담았다", due.size(), ran);
        }
        return ran;
    }
}
