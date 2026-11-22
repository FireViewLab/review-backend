package com.example.fireview.domain.cart.service;

import com.example.fireview.domain.cart.entity.CartEvent;
import com.example.fireview.domain.cart.repository.CartEventRepository;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.product.service.ProductService;
import com.example.fireview.domain.user.entity.OAuthProvider;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.repository.UserRepository;
import com.example.fireview.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** 직접 장바구니에 담을 때마다 담기 이력이 한 줄씩 쌓인다(#269) */
@DataJpaTest
@ActiveProfiles("test")
@Import({CartService.class, CartEventRecordTest.FixedClock.class})
class CartEventRecordTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired CartService cartService;
    @Autowired CartEventRepository events;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @MockitoBean UserService userService;
    @MockitoBean ProductService productService;

    private User user;
    private Product product;

    @BeforeEach
    void setUp() {
        user = users.save(User.builder().email("c@test.com").nickname("c").provider(OAuthProvider.LOCAL).build());
        product = products.save(Product.builder().id(10L).name("텀블러").platform("KURLY").price(1000L).build());
        given(userService.findByEmail("c@test.com")).willReturn(user);
        given(productService.findById(10L)).willReturn(product);
    }

    @Test
    void 담을_때마다_직접_담기_이력이_쌓인다() {
        cartService.addToCart("c@test.com", 10L, 1);
        cartService.addToCart("c@test.com", 10L, 2);

        assertThat(events.findByUser_IdAndCreatedAtAfterOrderByCreatedAtAsc(user.getId(), LocalDateTime.of(2026, 1, 1, 0, 0)))
                .extracting(CartEvent::getQuantity, CartEvent::getSource, CartEvent::getCreatedAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, CartEvent.MANUAL, LocalDateTime.of(2026, 11, 23, 9, 0)),
                        org.assertj.core.groups.Tuple.tuple(2, CartEvent.MANUAL, LocalDateTime.of(2026, 11, 23, 9, 0)));
    }
}
