package com.example.fireview.domain.feedback.repository;

import com.example.fireview.domain.feedback.entity.AnalysisFeedback;
import com.example.fireview.domain.feedback.entity.AnalysisFeedbackStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisFeedbackRepository extends JpaRepository<AnalysisFeedback, Long> {

    // 대상이 Spring 리뷰(review)이거나 Data 서버 리뷰(product + externalReviewId)다.
    // 한쪽이 null 이어도 행이 빠지지 않도록 대상 쪽은 모두 LEFT JOIN 한다.
    // fetch join 이 있으면 Hibernate 가 count 쿼리를 제대로 못 만들 수 있어 countQuery 를 따로 둔다.

    /** 내가 제출한 분석 피드백 (최신순). Spring 리뷰·Data 서버 리뷰 대상 모두 */
    @Query(value = "SELECT f FROM AnalysisFeedback f "
            + "LEFT JOIN FETCH f.review r LEFT JOIN FETCH r.product LEFT JOIN FETCH f.product "
            + "WHERE f.submitter.id = :userId ORDER BY f.createdAt DESC",
           countQuery = "SELECT COUNT(f) FROM AnalysisFeedback f WHERE f.submitter.id = :userId")
    Page<AnalysisFeedback> findBySubmitterIdWithReview(@Param("userId") Long userId, Pageable pageable);

    /** 상태별 분석 피드백 (관리자용) */
    @Query(value = "SELECT f FROM AnalysisFeedback f JOIN FETCH f.submitter "
            + "LEFT JOIN FETCH f.review r LEFT JOIN FETCH r.product LEFT JOIN FETCH f.product "
            + "WHERE f.status = :status",
           countQuery = "SELECT COUNT(f) FROM AnalysisFeedback f WHERE f.status = :status")
    Page<AnalysisFeedback> findByStatus(@Param("status") AnalysisFeedbackStatus status, Pageable pageable);

    /** 전체 분석 피드백 (관리자용, 최신순) */
    @Query(value = "SELECT f FROM AnalysisFeedback f JOIN FETCH f.submitter "
            + "LEFT JOIN FETCH f.review r LEFT JOIN FETCH r.product LEFT JOIN FETCH f.product "
            + "ORDER BY f.createdAt DESC",
           countQuery = "SELECT COUNT(f) FROM AnalysisFeedback f")
    Page<AnalysisFeedback> findAllWithDetails(Pageable pageable);

    // ── 모델 성능 모니터링용 ───────────────────────────────────────────────────

    /** 상태별 분석 피드백 수 집계 */
    long countByStatus(AnalysisFeedbackStatus status);

    /** 회원 탈퇴(UserDataPurger). 근거 신호가 컬렉션 테이블에 있어 일괄 DELETE 대신 엔티티로 지운다 */
    List<AnalysisFeedback> findAllBySubmitter_Id(Long userId);
}
