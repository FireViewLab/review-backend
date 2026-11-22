package com.example.fireview.domain.cartschedule.service;

import com.example.fireview.domain.cartschedule.ScheduleRule;
import com.example.fireview.domain.cartschedule.entity.CartSchedule;
import com.example.fireview.domain.cartschedule.repository.CartScheduleRepository;
import com.example.fireview.domain.dataserver.service.ProductTagRegistry;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.user.entity.OAuthProvider;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.repository.UserRepository;
import com.example.fireview.domain.user.service.UserService;
import com.example.fireview.global.exception.CustomException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 예약 상태 전이는 지금 상태가 맞을 때만 일어난다(#269 리뷰). 실제 트랜잭션으로 돈다.
 * 오래 읽어 둔 확정 요청이 취소나 실행 결과를 덮어쓰면 안 되고, 동시에 확정해도 한도를 넘지 않는다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CartScheduleService.class, com.example.fireview.global.config.ClockConfig.class})
class CartScheduleTransitionTest {

    private static final ScheduleRule WEEKLY = ScheduleRule.weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0));

    @Autowired CartScheduleService service;
    @Autowired CartScheduleRepository schedules;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @MockitoBean ProductTagRegistry registry;
    @MockitoBean UserService userService;

    private User user;
    private Product product;

    @BeforeEach
    void setUp() {
        user = users.save(User.builder().email("t@test.com").nickname("t").provider(OAuthProvider.LOCAL).build());
        product = products.save(Product.builder().id(9L).name("세제").platform("KURLY").build());
        given(userService.findByEmail("t@test.com")).willReturn(user);
    }

    @AfterEach
    void cleanUp() {
        schedules.deleteAll();
        products.deleteAll();
        users.deleteAll();
    }

    private CartSchedule save(String status, LocalDateTime nextRunAt) {
        return schedules.save(CartSchedule.builder().user(user).product(product).quantity(1).rule(WEEKLY)
                .nextRunAt(nextRunAt).status(status).createdAt(LocalDateTime.now()).build());
    }

    @Test
    void 확정은_확인_전_상태에서만_바뀐다() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        LocalDateTime advanced = LocalDateTime.now().plusDays(7);
        CartSchedule cancelled = save(CartSchedule.CANCELLED, null);
        CartSchedule ran = save(CartSchedule.ACTIVE, advanced);

        // 오래 읽어 둔 확정 요청이 뒤늦게 와도 취소를 되살리거나 실행기가 넘긴 회차를 되돌리지 않는다
        assertThat(schedules.activateProposal(cancelled.getId(), LocalDateTime.now().plusDays(1), cutoff)).isZero();
        assertThat(schedules.activateProposal(ran.getId(), LocalDateTime.now().plusDays(1), cutoff)).isZero();

        assertThat(schedules.findById(cancelled.getId()).orElseThrow().getStatus()).isEqualTo(CartSchedule.CANCELLED);
        assertThat(schedules.findById(ran.getId()).orElseThrow().getNextRunAt()).isEqualTo(advanced);
    }

    @Test
    void 취소는_진행_중이거나_확인_전일_때만_바뀐다() {
        CartSchedule done = save(CartSchedule.DONE, null);

        assertThat(schedules.cancelIfOpen(done.getId())).isZero();
        assertThat(schedules.findById(done.getId()).orElseThrow().getStatus()).isEqualTo(CartSchedule.DONE);
    }

    @Test
    void 동시에_확정해도_진행_중_예약_한도를_넘지_않는다() throws Exception {
        for (int i = 0; i < CartScheduleService.MAX_ACTIVE - 1; i++) {
            save(CartSchedule.ACTIVE, LocalDateTime.now().plusDays(1));
        }
        List<Long> proposals = List.of(
                save(CartSchedule.PROPOSED, LocalDateTime.now().plusDays(1)).getId(),
                save(CartSchedule.PROPOSED, LocalDateTime.now().plusDays(1)).getId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (Long id : proposals) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    service.confirm("t@test.com", id);
                    return true;
                } catch (CustomException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int confirmed = 0;
        for (Future<Boolean> r : results) if (r.get()) confirmed++;
        pool.shutdown();

        assertThat(confirmed).isEqualTo(1);
        assertThat(schedules.countByUser_IdAndStatus(user.getId(), CartSchedule.ACTIVE))
                .isEqualTo(CartScheduleService.MAX_ACTIVE);
    }
}
