package com.example.fireview.domain.cartschedule.repository;

import com.example.fireview.domain.cartschedule.entity.CartSchedule;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CartScheduleRepository extends JpaRepository<CartSchedule, Long> {

    long countByUser_IdAndStatus(Long userId, String status);

    List<CartSchedule> findByUser_IdAndStatusOrderByNextRunAtAsc(Long userId, String status);

    /** 실행할 때가 된 확정 예약. 오래 밀린 것부터 */
    @Query("""
            SELECT s FROM CartSchedule s
            WHERE s.status = 'ACTIVE' AND s.nextRunAt <= :now
            ORDER BY s.nextRunAt ASC, s.id ASC
            """)
    List<CartSchedule> findDue(@Param("now") LocalDateTime now, Limit limit);

    /**
     * 한 회차를 실행했다고 적는다. {@code next_run_at} 이 읽을 때 값 그대로일 때만 바꾼다 —
     * 두 실행이 같은 예약을 동시에 집어도 한쪽만 1을 돌려받아 장바구니에 담는다.
     *
     * @param nextRunAt 다음 회차. 한 번 예약이 끝났으면 null
     * @param status    다음 회차가 있으면 ACTIVE, 없으면 DONE
     * @return 바꾼 행 수. 0 이면 다른 실행이 먼저 처리했다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE CartSchedule s
            SET s.nextRunAt = :nextRunAt, s.status = :status, s.lastRunAt = :ranAt
            WHERE s.id = :id AND s.status = 'ACTIVE' AND s.nextRunAt = :expected
            """)
    int advance(@Param("id") Long id, @Param("expected") LocalDateTime expected,
                @Param("nextRunAt") LocalDateTime nextRunAt, @Param("status") String status,
                @Param("ranAt") LocalDateTime ranAt);
}
