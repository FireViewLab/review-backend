package com.example.fireview.domain.cartschedule.service;

import com.example.fireview.domain.cart.entity.CartEvent;
import com.example.fireview.domain.cart.entity.CartItem;
import com.example.fireview.domain.cart.repository.CartEventRepository;
import com.example.fireview.domain.cart.repository.CartRepository;
import com.example.fireview.domain.cart.service.CartService;
import com.example.fireview.domain.cartschedule.ScheduleRule;
import com.example.fireview.domain.cartschedule.entity.CartSchedule;
import com.example.fireview.domain.cartschedule.repository.CartScheduleRepository;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.product.service.ProductService;
import com.example.fireview.domain.user.entity.OAuthProvider;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.repository.UserRepository;
import com.example.fireview.domain.user.service.UserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.cart-schedule.enabled=true")
@Import({CartScheduleRunner.class, CartScheduleExecutor.class, CartService.class,
        CartScheduleRunnerTest.FixedClock.class})
class CartScheduleRunnerTest {

    /** 2026-11-23 월요일 오전 9시 5분(한국) */
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 23, 9, 5);

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-23T00:05:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired CartScheduleRunner runner;
    @Autowired CartScheduleExecutor executor;
    @Autowired CartScheduleRepository schedules;
    @Autowired CartRepository cartItems;
    @Autowired CartEventRepository events;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired EntityManager em;
    @MockitoBean UserService userService;
    @MockitoBean ProductService productService;

    private User user;
    private Product product;

    @BeforeEach
    void setUp() {
        user = users.save(User.builder().email("r@test.com").nickname("r").provider(OAuthProvider.LOCAL).build());
        product = products.save(Product.builder().id(5L).name("생수").platform("KURLY").price(1000L).build());
    }

    private CartSchedule schedule(ScheduleRule rule, LocalDateTime nextRunAt, String status, int quantity) {
        return schedules.save(CartSchedule.builder().user(user).product(product).quantity(quantity)
                .rule(rule).nextRunAt(nextRunAt).status(status).createdAt(NOW.minusDays(7)).build());
    }

    private CartSchedule reload(CartSchedule s) {
        em.flush();
        em.clear();
        return schedules.findById(s.getId()).orElseThrow();
    }

    @Test
    void 때가_된_매주_예약을_담고_다음_주로_넘긴다() {
        CartSchedule s = schedule(ScheduleRule.weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0)),
                LocalDateTime.of(2026, 11, 23, 9, 0), CartSchedule.ACTIVE, 2);

        assertThat(runner.runDue()).isEqualTo(1);

        CartSchedule after = reload(s);
        assertThat(after.getStatus()).isEqualTo(CartSchedule.ACTIVE);
        assertThat(after.getNextRunAt()).isEqualTo(LocalDateTime.of(2026, 11, 30, 9, 0));
        assertThat(after.getLastRunAt()).isEqualTo(NOW);
        assertThat(cartItems.findByUser_IdAndProduct_Id(user.getId(), 5L)).map(CartItem::getQuantity).contains(2);
        assertThat(events.findAll()).singleElement()
                .satisfies(e -> assertThat(e.getSource()).isEqualTo(CartEvent.SCHEDULE));
        // 알림은 커밋 뒤에 보낸다. 커밋되지 않는 이 테스트 대신 CartScheduleNotificationIsolationTest 가 본다
    }

    @Test
    void 한_번_예약은_담고_끝낸다() {
        CartSchedule s = schedule(ScheduleRule.once(LocalDate.of(2026, 11, 23), LocalTime.of(9, 0)),
                LocalDateTime.of(2026, 11, 23, 9, 0), CartSchedule.ACTIVE, 1);

        runner.runDue();

        CartSchedule after = reload(s);
        assertThat(after.getStatus()).isEqualTo(CartSchedule.DONE);
        assertThat(after.getNextRunAt()).isNull();
    }

    @Test
    void 때가_안_됐거나_확정_전이거나_취소한_예약은_건드리지_않는다() {
        schedule(ScheduleRule.weekly(DayOfWeek.MONDAY, LocalTime.of(10, 0)),
                LocalDateTime.of(2026, 11, 23, 10, 0), CartSchedule.ACTIVE, 1);
        schedule(ScheduleRule.weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0)),
                LocalDateTime.of(2026, 11, 23, 9, 0), CartSchedule.PROPOSED, 1);
        schedule(ScheduleRule.weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0)),
                LocalDateTime.of(2026, 11, 23, 9, 0), CartSchedule.CANCELLED, 1);

        assertThat(runner.runDue()).isZero();
        assertThat(cartItems.findAll()).isEmpty();
    }

    @Test
    void 놓친_회차는_한_번만_담고_지금_이후로_넘긴다() {
        CartSchedule s = schedule(ScheduleRule.weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0)),
                LocalDateTime.of(2026, 11, 2, 9, 0), CartSchedule.ACTIVE, 1);

        runner.runDue();

        assertThat(reload(s).getNextRunAt()).isEqualTo(LocalDateTime.of(2026, 11, 30, 9, 0));
        assertThat(cartItems.findByUser_IdAndProduct_Id(user.getId(), 5L)).map(CartItem::getQuantity).contains(1);
    }

    @Test
    void 다른_실행이_먼저_처리했으면_담지_않는다() {
        CartSchedule s = schedule(ScheduleRule.weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0)),
                LocalDateTime.of(2026, 11, 23, 9, 0), CartSchedule.ACTIVE, 1);

        // 읽을 때 본 next_run_at 과 지금 값이 다르면 다른 실행이 이미 넘긴 것이다
        assertThat(executor.runOne(s.getId(), LocalDateTime.of(2026, 11, 16, 9, 0), NOW)).isFalse();
        assertThat(cartItems.findAll()).isEmpty();
    }

}
