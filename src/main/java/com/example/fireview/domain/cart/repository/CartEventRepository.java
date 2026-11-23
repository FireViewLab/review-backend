package com.example.fireview.domain.cart.repository;

import com.example.fireview.domain.cart.entity.CartEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CartEventRepository extends JpaRepository<CartEvent, Long> {

    /** 기간 안에 담은 기록. 오래된 순 */
    List<CartEvent> findByUser_IdAndCreatedAtAfterOrderByCreatedAtAsc(Long userId, LocalDateTime after);

    /** 담은 상품 번호와 시각만. 반복 담기 분석은 상품 정보가 필요 없어 엔티티와 상품을 읽지 않는다 */
    record AddedAt(Long productId, LocalDateTime createdAt) {
    }

    @Query("SELECT new com.example.fireview.domain.cart.repository.CartEventRepository$AddedAt(e.product.id, e.createdAt) "
            + "FROM CartEvent e WHERE e.user.id = :userId AND e.createdAt > :after")
    List<AddedAt> findAddedAtSince(@Param("userId") Long userId, @Param("after") LocalDateTime after);

    /** 회원 탈퇴 때 지운다. 이력은 장바구니를 비워도 남아서 지우지 않으면 탈퇴가 외래 키에 막힌다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM CartEvent e WHERE e.user.id = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
