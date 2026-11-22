package com.example.fireview.domain.cartschedule.service;

import com.example.fireview.domain.cart.entity.CartItem;
import com.example.fireview.domain.cart.repository.CartEventRepository;
import com.example.fireview.domain.cart.repository.CartRepository;
import com.example.fireview.domain.cart.service.CartService;
import com.example.fireview.domain.cartschedule.ScheduleRule;
import com.example.fireview.domain.cartschedule.entity.CartSchedule;
import com.example.fireview.domain.cartschedule.repository.CartScheduleRepository;
import com.example.fireview.domain.notification.entity.Notification;
import com.example.fireview.domain.notification.repository.NotificationRepository;
import com.example.fireview.domain.notification.service.NotificationService;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.product.service.ProductService;
import com.example.fireview.domain.user.entity.OAuthProvider;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.repository.UserRepository;
import com.example.fireview.domain.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * 알림 저장이 실패해도 장바구니 담기와 회차 갱신은 커밋된다(#269 리뷰).
 *
 * <p>진짜 {@link NotificationService} 를 쓴다. 목으로 바꾸면 그 트랜잭션 인터셉터가 공유 트랜잭션을
 * rollback-only 로 만드는 문제를 볼 수 없다. 테스트 트랜잭션 없이 실제 커밋 결과를 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "app.cart-schedule.enabled=true")
@Import({CartScheduleRunner.class, CartScheduleExecutor.class, CartScheduleNotifier.class, CartService.class,
        NotificationService.class, com.example.fireview.global.config.ClockConfig.class})
class CartScheduleNotificationIsolationTest {

    @Autowired CartScheduleRunner runner;
    @Autowired CartScheduleRepository schedules;
    @Autowired CartRepository cartItems;
    @Autowired CartEventRepository events;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @MockitoSpyBean NotificationRepository notifications;
    @MockitoBean UserService userService;
    @MockitoBean ProductService productService;

    private User user;
    private Product product;

    @BeforeEach
    void setUp() {
        user = users.save(User.builder().email("n@test.com").nickname("n").provider(OAuthProvider.LOCAL).build());
        product = products.save(Product.builder().id(3L).name("휴지").platform("KURLY").price(1000L).build());
    }

    @AfterEach
    void cleanUp() {
        events.deleteAll();
        cartItems.deleteAll();
        schedules.deleteAll();
        notifications.deleteAll();
        products.deleteAll();
        users.deleteAll();
    }

    private CartSchedule dueWeekly() {
        return schedules.save(CartSchedule.builder().user(user).product(product).quantity(1)
                .rule(ScheduleRule.weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0)))
                .nextRunAt(LocalDateTime.now().minusMinutes(1)).status(CartSchedule.ACTIVE)
                .createdAt(LocalDateTime.now().minusDays(7)).build());
    }

    @Test
    void 알림_저장이_실패해도_담기와_회차_갱신은_커밋된다() {
        doThrow(new IllegalStateException("notification db down")).when(notifications).save(any(Notification.class));
        CartSchedule s = dueWeekly();

        assertThat(runner.runDue()).isEqualTo(1);

        assertThat(cartItems.findByUser_IdAndProduct_Id(user.getId(), 3L)).map(CartItem::getQuantity).contains(1);
        assertThat(schedules.findById(s.getId()).orElseThrow().getNextRunAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void 성공하면_커밋_뒤에_알림이_한_건_저장된다() {
        dueWeekly();

        runner.runDue();

        assertThat(notifications.findAll()).singleElement()
                .satisfies(n -> assertThat(n.getTitle()).isEqualTo("예약한 상품을 장바구니에 담았어요"));
    }
}
