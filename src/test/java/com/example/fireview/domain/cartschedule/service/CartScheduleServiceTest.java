package com.example.fireview.domain.cartschedule.service;

import com.example.fireview.domain.cartschedule.ScheduleRequest;
import com.example.fireview.domain.cartschedule.ScheduleRule;
import com.example.fireview.domain.cartschedule.dto.CartScheduleResponse;
import com.example.fireview.domain.cartschedule.dto.ScheduleProposal;
import com.example.fireview.domain.cartschedule.entity.CartSchedule;
import com.example.fireview.domain.cartschedule.repository.CartScheduleRepository;
import com.example.fireview.domain.dataserver.DataServerProductKey;
import com.example.fireview.domain.dataserver.service.ProductTagRegistry;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.user.entity.OAuthProvider;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.repository.UserRepository;
import com.example.fireview.domain.user.service.UserService;
import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@DataJpaTest
@ActiveProfiles("test")
@Import({CartScheduleService.class, CartScheduleServiceTest.FixedClock.class})
class CartScheduleServiceTest {

    /** 2026-11-22 일요일 오후 3시(한국) */
    static final Instant NOW = Instant.parse("2026-11-22T06:00:00Z");
    static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 11, 22, 15, 0);

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired CartScheduleService service;
    @Autowired CartScheduleRepository schedules;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @MockitoBean ProductTagRegistry registry;
    @MockitoBean UserService userService;

    private User owner;
    private User other;
    private Product product;

    private static final ScheduleRequest WEEKLY_MON =
            new ScheduleRequest(ScheduleRule.weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0)), 1);

    @BeforeEach
    void setUp() {
        owner = users.save(User.builder().email("o@test.com").nickname("o").provider(OAuthProvider.LOCAL).build());
        other = users.save(User.builder().email("x@test.com").nickname("x").provider(OAuthProvider.LOCAL).build());
        product = products.save(Product.builder().id(7L).name("텀블러").platform("KURLY")
                .dataPlatform("kurly").dataProductId("7").build());
        given(userService.findByEmail("o@test.com")).willReturn(owner);
        given(userService.findByEmail("x@test.com")).willReturn(other);
        given(registry.resolveOrCreate(any(DataServerProductKey.class), any())).willReturn(product);
    }

    private ScheduleProposal propose() {
        return service.propose(owner, "kurly-7", "텀블러", WEEKLY_MON).orElseThrow();
    }

    @Test
    void 제안은_확인_전_상태로_저장하고_요약을_돌려준다() {
        ScheduleProposal proposal = propose();

        CartSchedule saved = schedules.findById(proposal.scheduleId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(CartSchedule.PROPOSED);
        assertThat(saved.getNextRunAt()).isEqualTo(LocalDateTime.of(2026, 11, 23, 9, 0));
        assertThat(proposal.summary()).isEqualTo("매주 월요일 오전 9시 · 1개");
        assertThat(proposal.productName()).isEqualTo("텀블러");
    }

    @Test
    void 상품_주소가_이상하면_제안하지_않는다() {
        assertThat(service.propose(owner, "nokey", "텀블러", WEEKLY_MON)).isEmpty();
        assertThat(service.propose(owner, null, "텀블러", WEEKLY_MON)).isEmpty();
    }

    @Test
    void 확인하면_예약이_확정된다() {
        ScheduleProposal proposal = propose();

        CartScheduleResponse confirmed = service.confirm("o@test.com", proposal.scheduleId());

        assertThat(confirmed.status()).isEqualTo(CartSchedule.ACTIVE);
        assertThat(confirmed.nextRunAt()).isEqualTo(LocalDateTime.of(2026, 11, 23, 9, 0));
        assertThat(service.mySchedules("o@test.com")).extracting(CartScheduleResponse::id)
                .containsExactly(proposal.scheduleId());
    }

    @Test
    void 남의_예약은_확인하거나_취소할_수_없다() {
        ScheduleProposal proposal = propose();

        assertThatThrownBy(() -> service.confirm("x@test.com", proposal.scheduleId()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CART_SCHEDULE_FORBIDDEN);
        assertThatThrownBy(() -> service.cancel("x@test.com", proposal.scheduleId()))
                .extracting("errorCode").isEqualTo(ErrorCode.CART_SCHEDULE_FORBIDDEN);
    }

    @Test
    void 하루가_지난_제안은_확인할_수_없다() {
        CartSchedule old = schedules.save(CartSchedule.builder().user(owner).product(product).quantity(1)
                .rule(WEEKLY_MON.rule()).nextRunAt(NOW_KST.plusDays(1)).status(CartSchedule.PROPOSED)
                .createdAt(NOW_KST.minusHours(25)).build());

        assertThatThrownBy(() -> service.confirm("o@test.com", old.getId()))
                .extracting("errorCode").isEqualTo(ErrorCode.CART_SCHEDULE_NOT_CONFIRMABLE);
    }

    @Test
    void 이미_확정한_예약을_다시_확인할_수_없다() {
        ScheduleProposal proposal = propose();
        service.confirm("o@test.com", proposal.scheduleId());

        assertThatThrownBy(() -> service.confirm("o@test.com", proposal.scheduleId()))
                .extracting("errorCode").isEqualTo(ErrorCode.CART_SCHEDULE_NOT_CONFIRMABLE);
    }

    @Test
    void 진행_중_예약이_한도면_더_제안하지_않는다() {
        for (int i = 0; i < CartScheduleService.MAX_ACTIVE; i++) {
            schedules.save(CartSchedule.builder().user(owner).product(product).quantity(1)
                    .rule(WEEKLY_MON.rule()).nextRunAt(NOW_KST.plusDays(1)).status(CartSchedule.ACTIVE)
                    .createdAt(NOW_KST).build());
        }

        assertThat(service.propose(owner, "kurly-7", "텀블러", WEEKLY_MON)).isEmpty();
    }

    @Test
    void 이미_지난_한_번_예약은_제안하지_않는다() {
        ScheduleRequest past = new ScheduleRequest(
                ScheduleRule.once(LocalDate.of(2026, 11, 22), LocalTime.of(9, 0)), 1);

        assertThat(service.propose(owner, "kurly-7", "텀블러", past)).isEmpty();
    }

    @Test
    void 취소하면_목록에서_빠진다() {
        ScheduleProposal proposal = propose();
        service.confirm("o@test.com", proposal.scheduleId());

        service.cancel("o@test.com", proposal.scheduleId());

        assertThat(schedules.findById(proposal.scheduleId())).map(CartSchedule::getStatus)
                .isEqualTo(Optional.of(CartSchedule.CANCELLED));
        assertThat(service.mySchedules("o@test.com")).isEmpty();
    }

    @Test
    void 없는_예약은_찾을_수_없다() {
        assertThatThrownBy(() -> service.confirm("o@test.com", 999L))
                .extracting("errorCode").isEqualTo(ErrorCode.CART_SCHEDULE_NOT_FOUND);
    }
}
