package com.example.fireview.domain.cartschedule.service;

import com.example.fireview.domain.notification.entity.NotificationType;
import com.example.fireview.domain.notification.service.NotificationService;
import com.example.fireview.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 예약 담기 알림을 담기 트랜잭션이 커밋된 뒤 새 트랜잭션에서 저장한다.
 *
 * <p>담기와 같은 트랜잭션에서 알림을 저장하면, 알림 저장이 실패할 때 {@code NotificationService} 의
 * 트랜잭션 인터셉터가 공유 트랜잭션을 rollback-only 로 만든다. 그러면 예외를 잡아도 담기와 회차 갱신이
 * 함께 되돌려지고, 같은 예약이 매 실행마다 다시 시도된다(#269 리뷰). 알림은 부가 기능이라 떼어 낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartScheduleNotifier {

    /** 알림을 누르면 갈 화면 */
    static final String CART_URL = "/cart";

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRan(CartScheduleRanEvent event) {
        try {
            userRepository.findById(event.userId()).ifPresent(user ->
                    notificationService.createNotification(user, NotificationType.SYSTEM,
                            "예약한 상품을 장바구니에 담았어요",
                            "%s · %s".formatted(event.productName(), event.summary()),
                            CART_URL));
        } catch (RuntimeException e) {
            log.warn("[CartSchedule] 담기 알림 실패 - scheduleId={}", event.scheduleId(), e);
        }
    }
}
