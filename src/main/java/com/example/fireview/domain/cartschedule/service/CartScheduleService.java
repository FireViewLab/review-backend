package com.example.fireview.domain.cartschedule.service;

import com.example.fireview.domain.cartschedule.NextRunCalculator;
import com.example.fireview.domain.cartschedule.ScheduleRequest;
import com.example.fireview.domain.cartschedule.ScheduleText;
import com.example.fireview.domain.cartschedule.dto.CartScheduleResponse;
import com.example.fireview.domain.cartschedule.dto.ScheduleProposal;
import com.example.fireview.domain.cartschedule.entity.CartSchedule;
import com.example.fireview.domain.cartschedule.repository.CartScheduleRepository;
import com.example.fireview.domain.dataserver.DataServerProductKey;
import com.example.fireview.domain.dataserver.service.ProductTagRegistry;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.service.UserService;
import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 장바구니 예약. 챗봇이 제안을 만들고, 사용자가 확인해야 확정된다.
 * 실제로 담는 일은 {@code CartScheduleRunner} 가 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartScheduleService {

    /** 사용자당 진행 중인 예약 한도. 대화로 예약이 끝없이 쌓이는 것을 막는다 */
    public static final int MAX_ACTIVE = 20;

    /** 제안을 확인할 수 있는 시간. 오래된 대화의 버튼을 눌러 예상 못 한 예약이 생기지 않게 한다 */
    static final long PROPOSAL_TTL_HOURS = 24;

    private final CartScheduleRepository scheduleRepository;
    private final ProductTagRegistry registry;
    private final UserService userService;
    private final Clock clock;

    /**
     * 챗봇 대화에서 예약 제안을 만든다. 만들 수 없으면 비어 있다 — 답변은 그대로 나가야 하므로 던지지 않는다.
     *
     * @param externalProductId 대화 중인 상품 {@code "{platform}-{productId}"}
     * @param productName       번호표를 새로 만들 때 쓸 상품명. 모르면 null
     */
    @Transactional
    public Optional<ScheduleProposal> propose(User user, String externalProductId, String productName,
                                              ScheduleRequest request) {
        Optional<DataServerProductKey> key = DataServerProductKey.parse(externalProductId);
        if (key.isEmpty()) return Optional.empty();
        if (scheduleRepository.countByUser_IdAndStatus(user.getId(), CartSchedule.ACTIVE) >= MAX_ACTIVE) {
            log.info("[CartSchedule] 진행 중 예약 한도라 제안하지 않는다 - userId={}", user.getId());
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime next = NextRunCalculator.next(request.rule(), now);
        if (next == null) return Optional.empty();

        Product product = registry.resolveOrCreate(key.get(), productName);
        CartSchedule saved = scheduleRepository.save(CartSchedule.builder()
                .user(user)
                .product(product)
                .quantity(request.quantity())
                .rule(request.rule())
                .nextRunAt(next)
                .status(CartSchedule.PROPOSED)
                .createdAt(now)
                .build());
        return Optional.of(new ScheduleProposal(saved.getId(),
                ScheduleText.describe(request.rule(), request.quantity()), product.getName()));
    }

    /** 제안을 확정한다. 다음 실행 시각은 확인하는 지금을 기준으로 다시 계산한다 */
    @Transactional
    public CartScheduleResponse confirm(String userEmail, Long scheduleId) {
        User user = userService.findByEmail(userEmail);
        CartSchedule schedule = owned(user, scheduleId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!CartSchedule.PROPOSED.equals(schedule.getStatus())
                || schedule.getCreatedAt().isBefore(now.minusHours(PROPOSAL_TTL_HOURS))
                || scheduleRepository.countByUser_IdAndStatus(user.getId(), CartSchedule.ACTIVE) >= MAX_ACTIVE) {
            throw new CustomException(ErrorCode.CART_SCHEDULE_NOT_CONFIRMABLE);
        }
        LocalDateTime next = NextRunCalculator.next(schedule.rule(), now);
        if (next == null) {
            throw new CustomException(ErrorCode.CART_SCHEDULE_NOT_CONFIRMABLE);
        }
        schedule.activate(next);
        return CartScheduleResponse.from(schedule);
    }

    /** 예약을 취소한다. 이미 끝났거나 취소한 예약은 그대로 둔다 */
    @Transactional
    public void cancel(String userEmail, Long scheduleId) {
        CartSchedule schedule = owned(userService.findByEmail(userEmail), scheduleId);
        if (CartSchedule.ACTIVE.equals(schedule.getStatus()) || CartSchedule.PROPOSED.equals(schedule.getStatus())) {
            schedule.cancel();
        }
    }

    /** 진행 중인 예약. 다음에 담을 시각이 이른 순 */
    public List<CartScheduleResponse> mySchedules(String userEmail) {
        User user = userService.findByEmail(userEmail);
        return scheduleRepository.findByUser_IdAndStatusOrderByNextRunAtAsc(user.getId(), CartSchedule.ACTIVE)
                .stream()
                .map(CartScheduleResponse::from)
                .toList();
    }

    private CartSchedule owned(User user, Long scheduleId) {
        CartSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.CART_SCHEDULE_NOT_FOUND));
        if (!schedule.isOwnedBy(user.getId())) {
            throw new CustomException(ErrorCode.CART_SCHEDULE_FORBIDDEN);
        }
        return schedule;
    }
}
