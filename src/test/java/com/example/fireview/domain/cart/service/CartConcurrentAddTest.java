package com.example.fireview.domain.cart.service;

import com.example.fireview.domain.cart.entity.CartEvent;
import com.example.fireview.domain.cart.entity.CartItem;
import com.example.fireview.domain.cart.repository.CartEventRepository;
import com.example.fireview.domain.cart.repository.CartRepository;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.product.service.ProductService;
import com.example.fireview.domain.user.entity.OAuthProvider;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.repository.UserRepository;
import com.example.fireview.domain.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 직접 담기와 예약 담기가 같은 상품에 동시에 들어와도 수량이 하나도 사라지지 않는다(#269 리뷰).
 * 테스트 트랜잭션 없이 실제 트랜잭션으로 돈다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CartService.class, com.example.fireview.global.config.ClockConfig.class})
class CartConcurrentAddTest {

    private static final int ROUNDS = 20;

    @Autowired CartService cartService;
    @Autowired CartRepository cartItems;
    @Autowired CartEventRepository events;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @MockitoBean UserService userService;
    @MockitoBean ProductService productService;

    @AfterEach
    void cleanUp() {
        events.deleteAll();
        cartItems.deleteAll();
        products.deleteAll();
        users.deleteAll();
    }

    @Test
    void 직접_담기와_예약_담기가_겹쳐도_수량이_사라지지_않는다() throws Exception {
        User user = users.save(User.builder().email("cc@test.com").nickname("cc").provider(OAuthProvider.LOCAL).build());
        Product product = products.save(Product.builder().id(77L).name("생수").platform("KURLY").price(1000L).build());
        cartService.add(user, product, 1, CartEvent.MANUAL);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> jobs = new ArrayList<>();
        for (String source : List.of(CartEvent.MANUAL, CartEvent.SCHEDULE)) {
            jobs.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < ROUNDS; i++) {
                    cartService.add(user, product, 1, source);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> job : jobs) job.get();
        pool.shutdown();

        assertThat(cartItems.findByUser_IdAndProduct_Id(user.getId(), 77L)).map(CartItem::getQuantity)
                .contains(1 + 2 * ROUNDS);
        assertThat(events.count()).isEqualTo(1 + 2 * ROUNDS);
    }
}
