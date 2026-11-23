package com.example.fireview.domain.user.service;

import com.example.fireview.domain.auth.service.RefreshTokenService;
import com.example.fireview.domain.cart.entity.CartItem;
import com.example.fireview.domain.chat.entity.ChatMessage;
import com.example.fireview.domain.chat.entity.ChatRole;
import com.example.fireview.domain.chat.entity.ChatSession;
import com.example.fireview.domain.dashboard.entity.ViewHistory;
import com.example.fireview.domain.feedback.entity.AnalysisFeedback;
import com.example.fireview.domain.feedback.entity.AnalysisFeedbackType;
import com.example.fireview.domain.notification.entity.Notification;
import com.example.fireview.domain.notification.entity.NotificationType;
import com.example.fireview.domain.onboarding.entity.UserPreference;
import com.example.fireview.domain.product.entity.Category;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.report.entity.Report;
import com.example.fireview.domain.report.entity.ReportReason;
import com.example.fireview.domain.review.entity.FeedbackType;
import com.example.fireview.domain.review.entity.ReviewFeedback;
import com.example.fireview.domain.user.entity.OAuthProvider;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.entity.UserSetting;
import com.example.fireview.domain.wishlist.entity.Wishlist;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자를 가리키는 기록이 어떤 것이든 탈퇴할 수 있다.
 *
 * <p>예전에는 담기 이력·예약만 지워서 찜·장바구니·조회 이력·알림·설정·채팅 중 하나라도 있으면
 * 사용자 삭제가 외래 키에 막혀 탈퇴가 실패했다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({UserService.class, UserDataPurger.class})
class UserDeleteWithAllRecordsTest {

    @Autowired UserService userService;
    @Autowired EntityManager em;
    @MockitoBean RefreshTokenService refreshTokenService;

    private User saveUser(String email) {
        User user = User.builder().email(email).nickname(email).provider(OAuthProvider.LOCAL).build();
        em.persist(user);
        return user;
    }

    /** 이 사용자를 가리키는 행을 모든 테이블에 하나 이상 만든다 */
    private void fillEveryTable(User user, Product product, String suffix) {
        em.persist(Wishlist.builder().user(user).product(product).createdAt(LocalDateTime.now()).build());
        em.persist(CartItem.builder().user(user).product(product).createdAt(LocalDateTime.now()).build());
        em.persist(com.example.fireview.domain.cart.entity.CartEvent.builder().user(user).product(product)
                .quantity(1).source(com.example.fireview.domain.cart.entity.CartEvent.MANUAL)
                .createdAt(LocalDateTime.now()).build());
        em.persist(com.example.fireview.domain.cartschedule.entity.CartSchedule.builder().user(user).product(product)
                .quantity(1).rule(com.example.fireview.domain.cartschedule.ScheduleRule.weekly(
                        java.time.DayOfWeek.MONDAY, java.time.LocalTime.of(9, 0)))
                .status(com.example.fireview.domain.cartschedule.entity.CartSchedule.CANCELLED)
                .createdAt(LocalDateTime.now()).build());
        em.persist(ViewHistory.builder().user(user).product(product).viewedAt(LocalDateTime.now()).build());
        em.persist(Notification.builder().receiver(user).type(NotificationType.REPORT_RECEIVED)
                .title("알림").message("내용").build());
        em.persist(UserSetting.builder().user(user).build());
        em.persist(UserPreference.builder().user(user).preferredCategories(
                new java.util.HashSet<>(Set.of(Category.values()[0]))).build());
        em.persist(ReviewFeedback.builder().user(user).product(product).externalReviewId("r-" + suffix)
                .feedbackType(FeedbackType.REAL).build());
        em.persist(Report.builder().reporter(user).product(product).externalReviewId("r-" + suffix)
                .reason(ReportReason.FAKE_REVIEW).build());
        em.persist(AnalysisFeedback.builder().submitter(user).product(product).externalReviewId("r-" + suffix)
                .feedbackType(AnalysisFeedbackType.SCORE_MISMATCH)
                .relatedSignals(new ArrayList<>(List.of("TEXT_SHORT"))).build());
        ChatSession session = ChatSession.builder().user(user).title("대화").build();
        em.persist(session);
        em.persist(ChatMessage.builder().session(session).role(ChatRole.USER).content("질문")
                .createdAt(LocalDateTime.now()).build());
    }

    /** users 를 가리키는 속성이 있는 엔티티와 그 속성 이름 */
    private List<String[]> userReferences() {
        List<String[]> refs = new ArrayList<>();
        for (EntityType<?> entity : em.getMetamodel().getEntities()) {
            for (Attribute<?, ?> attr : entity.getAttributes()) {
                if (attr.getJavaType() == User.class) refs.add(new String[]{entity.getName(), attr.getName()});
            }
        }
        return refs;
    }

    private long countOf(String[] ref, Long userId) {
        return em.createQuery("SELECT COUNT(x) FROM " + ref[0] + " x WHERE x." + ref[1] + ".id = :id", Long.class)
                .setParameter("id", userId).getSingleResult();
    }

    @Test
    void 모든_기록이_있어도_탈퇴되고_남의_기록과_상품은_남는다() {
        Product product = Product.builder().id(4L).name("물티슈").platform("KURLY").build();
        em.persist(product);
        User me = saveUser("me@test.com");
        User other = saveUser("other@test.com");
        fillEveryTable(me, product, "me");
        fillEveryTable(other, product, "other");
        em.flush();
        em.clear();

        // 새 테이블이 users 를 물게 되면 이 테스트가 그 행을 만들지 않아 여기서 걸린다 — UserDataPurger 와 여기에 추가할 것
        for (String[] ref : userReferences()) {
            assertThat(countOf(ref, me.getId())).as("%s.%s 에 테스트 행이 없다", ref[0], ref[1]).isPositive();
        }

        userService.deleteAccount("me@test.com");
        em.flush();
        em.clear();

        assertThat(em.find(User.class, me.getId())).isNull();
        for (String[] ref : userReferences()) {
            assertThat(countOf(ref, me.getId())).as("%s.%s", ref[0], ref[1]).isZero();
            assertThat(countOf(ref, other.getId())).as("남의 %s.%s", ref[0], ref[1]).isPositive();
        }
        assertThat(em.find(Product.class, 4L)).isNotNull();
    }
}
