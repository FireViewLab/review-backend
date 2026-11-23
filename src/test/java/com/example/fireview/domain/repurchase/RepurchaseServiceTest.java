package com.example.fireview.domain.repurchase;

import com.example.fireview.domain.cartschedule.ScheduleRule;
import com.example.fireview.domain.cartschedule.dto.CartScheduleResponse;
import com.example.fireview.domain.cartschedule.service.CartScheduleService;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.repurchase.dto.RepurchaseResponse;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.service.UserService;
import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepurchaseServiceTest {

    @Mock RepurchaseFinder finder;
    @Mock CartScheduleService cartScheduleService;
    @Mock ProductRepository productRepository;
    @Mock UserService userService;
    @InjectMocks RepurchaseService service;

    private final User user = User.builder().id(1L).email("u@test.com").nickname("u").build();
    private final ScheduleRule weeklyMon = ScheduleRule.weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0));

    private RepurchaseCandidate candidate(long productId, ScheduleRule suggestion) {
        return new RepurchaseCandidate(productId, "kurly-" + productId, "생수", "https://img", 1000L,
                new RepurchaseStats(7, LocalDate.of(2026, 11, 16), 7, 3, true, 1.0, suggestion,
                        "약 7일마다 담으셨고 마지막은 7일 전이에요"));
    }

    @BeforeEach
    void setUp() {
        when(userService.findByEmail("u@test.com")).thenReturn(user);
    }

    @Test
    void 다시_살_때인_상품을_이유와_예약_제안과_함께_돌려준다() {
        when(finder.findDue(1L, RepurchaseService.MAX_ITEMS)).thenReturn(List.of(candidate(5L, weeklyMon)));

        List<RepurchaseResponse> list = service.myRepurchases("u@test.com");

        RepurchaseResponse r = list.get(0);
        assertThat(r.productId()).isEqualTo(5L);
        assertThat(r.reason()).isEqualTo("약 7일마다 담으셨고 마지막은 7일 전이에요");
        assertThat(r.scheduleSuggestion().repeatType()).isEqualTo(ScheduleRule.WEEKLY);
        assertThat(r.scheduleSuggestion().summary()).isEqualTo("매주 월요일 오전 9시 · 1개");
    }

    @Test
    void 예약_제안이_없으면_null이다() {
        when(finder.findDue(1L, RepurchaseService.MAX_ITEMS)).thenReturn(List.of(candidate(5L, null)));

        assertThat(service.myRepurchases("u@test.com").get(0).scheduleSuggestion()).isNull();
    }

    @Test
    void 제안을_서버에서_다시_계산해_바로_예약을_만든다() {
        Product product = Product.builder().id(5L).name("생수").platform("KURLY").build();
        CartScheduleResponse created = new CartScheduleResponse(9L, 5L, "생수", "kurly-5",
                "매주 월요일 오전 9시 · 1개", ScheduleRule.WEEKLY, LocalDateTime.of(2026, 11, 30, 9, 0), "ACTIVE");
        when(finder.findDue(1L, Integer.MAX_VALUE)).thenReturn(List.of(candidate(5L, weeklyMon)));
        when(productRepository.findById(5L)).thenReturn(Optional.of(product));
        when(cartScheduleService.createActive(user, product, weeklyMon, 1)).thenReturn(Optional.of(created));

        assertThat(service.scheduleFromSuggestion("u@test.com", 5L)).isEqualTo(created);
    }

    @Test
    void 제안이_없거나_다시_살_때가_아니면_409다() {
        when(finder.findDue(1L, Integer.MAX_VALUE)).thenReturn(List.of(candidate(5L, null)));

        assertThatThrownBy(() -> service.scheduleFromSuggestion("u@test.com", 5L))
                .extracting("errorCode").isEqualTo(ErrorCode.REPURCHASE_NOT_SCHEDULABLE);
        assertThatThrownBy(() -> service.scheduleFromSuggestion("u@test.com", 6L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REPURCHASE_NOT_SCHEDULABLE);
        verify(cartScheduleService, never()).createActive(any(), any(), any(), anyInt());
    }

    @Test
    void 예약을_만들_수_없으면_409다() {
        Product product = Product.builder().id(5L).name("생수").platform("KURLY").build();
        when(finder.findDue(1L, Integer.MAX_VALUE)).thenReturn(List.of(candidate(5L, weeklyMon)));
        when(productRepository.findById(5L)).thenReturn(Optional.of(product));
        when(cartScheduleService.createActive(user, product, weeklyMon, 1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.scheduleFromSuggestion("u@test.com", 5L))
                .extracting("errorCode").isEqualTo(ErrorCode.REPURCHASE_NOT_SCHEDULABLE);
    }
}
