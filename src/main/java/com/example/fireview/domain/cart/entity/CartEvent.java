package com.example.fireview.domain.cart.entity;

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

import java.time.LocalDateTime;

/**
 * 장바구니에 담은 기록 한 건. 추가만 하고 고치거나 지우지 않는다.
 *
 * <p>{@link CartItem} 은 사용자·상품당 한 줄이라 다시 담으면 수량만 바뀌고 "언제 몇 번 담았는지"가
 * 남지 않는다. 정기 구매 추천은 담은 간격과 횟수를 봐야 하므로 이력을 따로 쌓는다.
 *
 * <p>{@code source} 는 enum 칼럼이 아니라 문자열이다. enum 이면 Hibernate 가 체크 제약을 붙이는데
 * {@code ddl-auto=update} 는 값이 늘어도 제약을 고치지 않아 새 값 저장이 거부된다(#205).
 */
@Entity
@Table(name = "cart_events", indexes = @Index(name = "idx_cart_events_user_created", columnList = "user_id, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartEvent {

    /** 사용자가 직접 담았다 */
    public static final String MANUAL = "MANUAL";
    /** 장바구니 예약이 담았다 */
    public static final String SCHEDULE = "SCHEDULE";

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

    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private CartEvent(User user, Product product, int quantity, String source, LocalDateTime createdAt) {
        this.user = user;
        this.product = product;
        this.quantity = quantity;
        this.source = source;
        this.createdAt = createdAt;
    }
}
