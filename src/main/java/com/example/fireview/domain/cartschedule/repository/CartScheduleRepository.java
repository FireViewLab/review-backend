package com.example.fireview.domain.cartschedule.repository;

import com.example.fireview.domain.cartschedule.entity.CartSchedule;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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
     * 확인 전 제안을 확정한다. 지금 상태가 {@code PROPOSED} 이고 기한 안일 때만 바꾼다 —
     * 오래 읽어 둔 확정 요청이 뒤늦게 와도 취소를 되살리거나 실행기가 넘긴 회차를 되돌리지 않는다.
     *
     * @return 바꾼 행 수. 0 이면 이미 확정·취소됐거나 기한이 지났다
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE CartSchedule s SET s.status = 'ACTIVE', s.nextRunAt = :nextRunAt
            WHERE s.id = :id AND s.status = 'PROPOSED' AND s.createdAt >= :createdAfter
            """)
    int activateProposal(@Param("id") Long id, @Param("nextRunAt") LocalDateTime nextRunAt,
                         @Param("createdAfter") LocalDateTime createdAfter);

    /**
     * 진행 중이거나 확인 전인 예약을 취소한다. 이미 끝났거나 취소한 예약은 그대로 둔다.
     *
     * @return 바꾼 행 수
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE CartSchedule s SET s.status = 'CANCELLED', s.nextRunAt = NULL
            WHERE s.id = :id AND s.status IN ('ACTIVE', 'PROPOSED')
            """)
    int cancelIfOpen(@Param("id") Long id);

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
