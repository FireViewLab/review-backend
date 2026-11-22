package com.example.fireview.domain.cartschedule.entity;

import com.example.fireview.domain.cartschedule.ScheduleRule;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 장바구니 예약. 챗봇이 제안({@link #PROPOSED})으로 만들고 사용자가 확인하면 {@link #ACTIVE} 가 된다.
 *
 * <p>상태는 엔티티를 고쳐 저장하지 않고 저장소의 조건부 UPDATE({@code activateProposal},
 * {@code cancelIfOpen}, {@code advance})로만 바꾼다. 읽어 둔 엔티티를 저장하면 그 사이 바뀐 상태를 덮어쓴다.
 *
 * <p>{@code status}·{@code repeat_type} 은 enum 칼럼이 아니라 문자열이다. enum 이면 Hibernate 가 체크 제약을
 * 붙이는데 {@code ddl-auto=update} 는 값이 늘어도 제약을 고치지 않아 새 값 저장이 거부된다(#205).
 * 시각은 모두 한국 시간 LocalDateTime 이다.
 */
@Entity
@Table(name = "cart_schedules", indexes = {
        @Index(name = "idx_cart_schedules_due", columnList = "status, next_run_at"),
        @Index(name = "idx_cart_schedules_user", columnList = "user_id, status")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartSchedule {

    /** 챗봇이 제안했고 아직 확인 전 */
    public static final String PROPOSED = "PROPOSED";
    /** 확인됐고 실행을 기다린다 */
    public static final String ACTIVE = "ACTIVE";
    /** 한 번 예약이 실행을 마쳤다 */
    public static final String DONE = "DONE";
    /** 사용자가 취소했다 */
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "repeat_type", nullable = false, length = 20)
    private String repeatType;

    @Column(name = "run_date")
    private LocalDate runDate;

    /** 1=월요일 ~ 7=일요일 */
    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    @Column(name = "run_time", nullable = false)
    private LocalTime runTime;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private CartSchedule(User user, Product product, int quantity, ScheduleRule rule,
                         LocalDateTime nextRunAt, String status, LocalDateTime createdAt) {
        this.user = user;
        this.product = product;
        this.quantity = quantity;
        this.repeatType = rule.repeatType();
        this.runDate = rule.runDate();
        this.dayOfWeek = rule.dayOfWeek() == null ? null : rule.dayOfWeek().getValue();
        this.dayOfMonth = rule.dayOfMonth();
        this.runTime = rule.runTime();
        this.nextRunAt = nextRunAt;
        this.status = status;
        this.createdAt = createdAt;
    }

    public ScheduleRule rule() {
        return new ScheduleRule(repeatType, runDate,
                dayOfWeek == null ? null : DayOfWeek.of(dayOfWeek), dayOfMonth, runTime);
    }

    public boolean isOwnedBy(Long userId) {
        return user.getId().equals(userId);
    }
}
