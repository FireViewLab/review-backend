package com.example.fireview.domain.review.repository;

import com.example.fireview.domain.review.entity.ReviewFeedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReviewFeedbackRepository extends JpaRepository<ReviewFeedback, Long> {

    Optional<ReviewFeedback> findByReview_IdAndUser_Id(Long reviewId, Long userId);

    boolean existsByReview_IdAndUser_Id(Long reviewId, Long userId);

    /** Data 서버 리뷰 중복 피드백 검사. 상품이 달라도 리뷰 ID 가 겹칠 수 있어 상품까지 본다 */
    boolean existsByUser_IdAndProduct_IdAndExternalReviewId(
            Long userId, Long productId, String externalReviewId);

    /** 내가 제출한 피드백 목록 (리뷰+상품 JOIN FETCH, 최신순) */
    @Query(value = "SELECT f FROM ReviewFeedback f "
         + "LEFT JOIN FETCH f.review r "
         + "LEFT JOIN FETCH r.product LEFT JOIN FETCH f.product "
         + "WHERE f.user.id = :userId "
         + "ORDER BY f.createdAt DESC, f.id DESC",
         countQuery = "SELECT COUNT(f) FROM ReviewFeedback f "
         + "LEFT JOIN f.review r LEFT JOIN r.product LEFT JOIN f.product "
         + "WHERE f.user.id = :userId")
    Page<ReviewFeedback> findByUserIdWithReview(@Param("userId") Long userId, Pageable pageable);

    /** 내가 제출한 피드백 단건 조회 (본인 확인) */
    Optional<ReviewFeedback> findByIdAndUser_Id(Long feedbackId, Long userId);

    /** 내가 제출한 피드백 수 */
    long countByUser_Id(Long userId);

    // ── 모델 성능 모니터링용 ───────────────────────────────────────────────────

    /**
     * AI 판정과 사용자 피드백이 일치하는 건수 (동의율 계산용)
     * - AI SAFE 리뷰 → 사용자 REAL 피드백 : 동의
     * - AI SUSPICIOUS/DANGER 리뷰 → 사용자 FAKE 피드백 : 동의
     */
    @Query("SELECT COUNT(f) FROM ReviewFeedback f " +
           "WHERE (f.review.trustGrade = com.example.fireview.domain.review.entity.TrustGrade.SAFE " +
           "       AND f.feedbackType = com.example.fireview.domain.review.entity.FeedbackType.REAL) " +
           "   OR (f.review.trustGrade <> com.example.fireview.domain.review.entity.TrustGrade.SAFE " +
           "       AND f.feedbackType = com.example.fireview.domain.review.entity.FeedbackType.FAKE)")
    long countAgreementFeedbacks();

    /** 회원 탈퇴(UserDataPurger). 행마다 DELETE 를 보내지 않고 한 문장으로 지운다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ReviewFeedback x WHERE x.user.id = :userId")
    int deleteAllOfUser(@Param("userId") Long userId);
}
