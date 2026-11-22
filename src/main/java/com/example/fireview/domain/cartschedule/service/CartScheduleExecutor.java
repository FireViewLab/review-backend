package com.example.fireview.domain.cartschedule.service;

import com.example.fireview.domain.cart.entity.CartEvent;
import com.example.fireview.domain.cart.service.CartService;
import com.example.fireview.domain.cartschedule.NextRunCalculator;
import com.example.fireview.domain.cartschedule.ScheduleText;
import com.example.fireview.domain.cartschedule.entity.CartSchedule;
import com.example.fireview.domain.cartschedule.repository.CartScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 예약 한 회차를 한 트랜잭션에서 실행한다. 담기가 실패하면 회차 갱신도 되돌려 다음 실행에 다시 시도한다 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartScheduleExecutor {

    private final CartScheduleRepository scheduleRepository;
    private final CartService cartService;
    private final ApplicationEventPublisher events;

    /**
     * @param expectedNextRunAt 대상을 고를 때 본 {@code next_run_at}. 지금 값과 다르면 다른 실행이 먼저 처리한 것이다
     * @return 이번에 장바구니에 담았으면 true
     */
    @Transactional
    public boolean runOne(Long scheduleId, LocalDateTime expectedNextRunAt, LocalDateTime now) {
        CartSchedule schedule = scheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null) return false;

        // 놓친 회차를 몰아서 담지 않는다. 다음 회차는 지금 이후로 잡는다
        LocalDateTime next = NextRunCalculator.next(schedule.rule(), now);
        String status = next == null ? CartSchedule.DONE : CartSchedule.ACTIVE;
        if (scheduleRepository.advance(scheduleId, expectedNextRunAt, next, status, now) == 0) {
            return false;
        }

        // advance 가 영속성 컨텍스트를 비웠으므로 다시 읽어 연관을 쓴다
        CartSchedule ran = scheduleRepository.findById(scheduleId).orElseThrow();
        cartService.add(ran.getUser(), ran.getProduct(), ran.getQuantity(), CartEvent.SCHEDULE);
        // 알림은 이 트랜잭션이 커밋된 뒤 따로 저장한다(CartScheduleNotifier). 실패해도 담기는 남는다
        events.publishEvent(new CartScheduleRanEvent(ran.getId(), ran.getUser().getId(),
                ran.getProduct().getName(), ScheduleText.describe(ran.rule(), ran.getQuantity())));
        return true;
    }
}
