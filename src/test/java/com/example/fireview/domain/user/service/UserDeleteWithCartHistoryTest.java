package com.example.fireview.domain.user.service;

import com.example.fireview.domain.cart.entity.CartEvent;
import com.example.fireview.domain.cart.repository.CartEventRepository;
import com.example.fireview.domain.cartschedule.ScheduleRule;
import com.example.fireview.domain.cartschedule.entity.CartSchedule;
import com.example.fireview.domain.cartschedule.repository.CartScheduleRepository;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.user.entity.OAuthProvider;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.repository.UserRepository;
import com.example.fireview.domain.auth.service.RefreshTokenService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장바구니 담기 이력과 예약이 남아 있어도 탈퇴할 수 있다(#269 리뷰).
 * 담기 이력은 장바구니를 비워도 남으므로, 지우지 않으면 예약 기능을 안 쓴 사용자도 탈퇴가 외래 키에 막힌다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(UserService.class)
class UserDeleteWithCartHistoryTest {

    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired CartEventRepository events;
    @Autowired CartScheduleRepository schedules;
    @Autowired EntityManager em;
    @MockitoBean RefreshTokenService refreshTokenService;

    @Test
    void 담기_이력과_취소한_예약이_있어도_탈퇴된다() {
        User user = users.save(User.builder().email("d@test.com").nickname("d").provider(OAuthProvider.LOCAL).build());
        Product product = products.save(Product.builder().id(4L).name("물티슈").platform("KURLY").build());
        events.save(CartEvent.builder().user(user).product(product).quantity(1).source(CartEvent.MANUAL)
                .createdAt(LocalDateTime.now()).build());
        schedules.save(CartSchedule.builder().user(user).product(product).quantity(1)
                .rule(ScheduleRule.weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0)))
                .nextRunAt(null).status(CartSchedule.CANCELLED).createdAt(LocalDateTime.now()).build());
        em.flush();
        em.clear();

        userService.deleteAccount("d@test.com");
        em.flush();

        assertThat(users.findByEmail("d@test.com")).isEmpty();
        assertThat(events.count()).isZero();
        assertThat(schedules.count()).isZero();
        assertThat(products.findById(4L)).isPresent();
    }
}
