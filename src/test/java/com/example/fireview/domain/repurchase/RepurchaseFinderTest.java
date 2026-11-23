package com.example.fireview.domain.repurchase;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Import({RepurchaseFinder.class, RepurchaseFinderTest.FixedClock.class})
class RepurchaseFinderTest {

    /** 2026-11-23 월요일 오전 10시(한국) */
    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-23T01:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired RepurchaseFinder finder;
    @Autowired CartEventRepository events;
    @Autowired CartScheduleRepository schedules;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired jakarta.persistence.EntityManager em;

    private User user;
    private User other;

    @BeforeEach
    void setUp() {
        user = users.save(User.builder().email("f@test.com").nickname("f").provider(OAuthProvider.LOCAL).build());
        other = users.save(User.builder().email("g@test.com").nickname("g").provider(OAuthProvider.LOCAL).build());
    }

    private Product product(long id, String name) {
        return products.save(Product.builder().id(id).name(name).platform("KURLY").price(1000L)
                .imageUrl("https://img/" + id).dataPlatform("kurly").dataProductId(String.valueOf(id)).build());
    }

    private void added(User who, Product product, LocalDate... days) {
        for (LocalDate day : days) {
            events.save(CartEvent.builder().user(who).product(product).quantity(1).source(CartEvent.MANUAL)
                    .createdAt(day.atTime(12, 0)).build());
        }
    }

    private static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    @Test
    void 다시_살_때인_상품만_늦어진_순서로_고른다() {
        Product water = product(1L, "생수");      // 주기 7, 경과 9 → 1.29
        Product tissue = product(2L, "휴지");     // 주기 14, 경과 22 → 1.57
        Product soap = product(3L, "비누");       // 주기 30, 경과 3 → 아직
        added(user, water, d(11, 7), d(11, 14));
        added(user, tissue, d(10, 18), d(11, 1));
        added(user, soap, d(10, 21), d(11, 20));

        assertThat(finder.findDue(user.getId(), 10)).extracting(RepurchaseCandidate::name)
                .containsExactly("휴지", "생수");
        assertThat(finder.findAll(user.getId(), 10)).extracting(RepurchaseCandidate::name)
                .containsExactly("휴지", "생수", "비누");
    }

    @Test
    void 후보에_상품_정보와_분석_결과를_싣는다() {
        Product water = product(1L, "생수");
        added(user, water, d(11, 9), d(11, 16));

        RepurchaseCandidate c = finder.findDue(user.getId(), 10).get(0);

        assertThat(c.productId()).isEqualTo(1L);
        assertThat(c.externalId()).isEqualTo("kurly-1");
        assertThat(c.thumbnailUrl()).isEqualTo("https://img/1");
        assertThat(c.price()).isEqualTo(1000L);
        assertThat(c.stats().intervalDays()).isEqualTo(7);
        assertThat(c.stats().suggestion()).isEqualTo(ScheduleRule.weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0)));
    }

    @Test
    void 진행_중_예약이_있는_상품은_뺀다() {
        Product water = product(1L, "생수");
        added(user, water, d(11, 7), d(11, 14));
        schedules.save(CartSchedule.builder().user(user).product(water).quantity(1)
                .rule(ScheduleRule.weekly(DayOfWeek.SATURDAY, LocalTime.of(9, 0)))
                .nextRunAt(d(11, 28).atTime(9, 0)).status(CartSchedule.ACTIVE)
                .createdAt(d(11, 14).atTime(9, 0)).build());

        assertThat(finder.findDue(user.getId(), 10)).isEmpty();
    }

    @Test
    void 백팔십일보다_오래된_기록과_다른_사용자의_기록은_쓰지_않는다() {
        Product old = product(1L, "오래된");
        Product theirs = product(2L, "남의");
        added(user, old, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 8), d(11, 1));
        added(other, theirs, d(11, 1), d(11, 8));

        // 180일 안에는 11월 1일 하루뿐이라 후보가 아니다
        assertThat(finder.findAll(user.getId(), 10)).isEmpty();
    }

    @Test
    void 최대_개수만큼만_돌려준다() {
        for (long i = 1; i <= 4; i++) {
            added(user, product(i, "상품" + i), d(11, 1), d(11, 8));
        }

        assertThat(finder.findDue(user.getId(), 2)).hasSize(2);
    }
    @Test
    void 후보가_많아도_쿼리_수는_상품_수에_비례하지_않는다() {
        // 담기 기록마다 상품을 따로 읽으면 매 챗봇 턴이 상품 수만큼 쿼리를 날린다(#271 리뷰)
        for (long i = 1; i <= 20; i++) {
            added(user, product(i, "상품" + i), d(11, 1), d(11, 8));
        }
        em.flush();
        em.clear();
        org.hibernate.stat.Statistics stats =
                em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.clear();

        assertThat(finder.findAll(user.getId(), 5)).hasSize(5);

        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }
}
