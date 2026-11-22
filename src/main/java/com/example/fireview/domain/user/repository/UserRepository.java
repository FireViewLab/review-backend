package com.example.fireview.domain.user.repository;

import com.example.fireview.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByNickname(String nickname);

    /**
     * 사용자 행을 잠근다({@code SELECT ... FOR UPDATE}). 같은 사용자의 장바구니 담기와 예약 확정을 한 줄로 세운다.
     * 트랜잭션이 끝나면 풀린다. 행 하나만 잠그므로 다른 사용자의 요청은 막지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> lockById(@Param("id") Long id);
}