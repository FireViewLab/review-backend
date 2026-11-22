package com.example.fireview.domain.cart.repository;

import com.example.fireview.domain.cart.entity.CartEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface CartEventRepository extends JpaRepository<CartEvent, Long> {

    /** 기간 안에 담은 기록. 오래된 순 */
    List<CartEvent> findByUser_IdAndCreatedAtAfterOrderByCreatedAtAsc(Long userId, LocalDateTime after);
}
