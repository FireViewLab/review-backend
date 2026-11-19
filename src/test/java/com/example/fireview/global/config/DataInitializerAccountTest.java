package com.example.fireview.global.config;

import com.example.fireview.domain.product.client.NaverShoppingClient;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.review.repository.ReviewRepository;
import com.example.fireview.domain.review.service.RtiEngineService;
import com.example.fireview.domain.user.repository.UserRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataInitializerAccountTest {

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void 상품_유무와_무관하게_계정을_자동_생성하지_않는다(long productCount) {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        ProductRepository products = mock(ProductRepository.class);
        ReviewRepository reviews = mock(ReviewRepository.class);
        when(products.count()).thenReturn(productCount);
        when(products.saveAll(anyList())).thenReturn(List.of());
        when(products.findAll()).thenReturn(List.of());

        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=local")
                .withBean(UserRepository.class, () -> users)
                .withBean(PasswordEncoder.class, () -> passwords)
                .withBean(ProductRepository.class, () -> products)
                .withBean(ReviewRepository.class, () -> reviews)
                .withBean(RtiEngineService.class, () -> mock(RtiEngineService.class))
                .withBean(NaverShoppingClient.class, () -> mock(NaverShoppingClient.class))
                .withBean(DataInitializer.class)
                .run(context -> {
                    context.getBean(DataInitializer.class).run();
                    verifyNoInteractions(users, passwords);
                    if (productCount == 0) {
                        verify(products).saveAll(argThat(batch -> StreamSupport.stream(batch.spliterator(), false).count() == 33));
                    }
                });
    }
}
