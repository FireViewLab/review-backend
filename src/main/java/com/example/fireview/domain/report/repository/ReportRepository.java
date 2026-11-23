package com.example.fireview.domain.report.repository;

import com.example.fireview.domain.report.entity.Report;
import com.example.fireview.domain.report.entity.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {

    /** 특정 사용자가 특정 리뷰를 이미 신고했는지 확인 */
    boolean existsByReporter_IdAndReview_Id(Long reporterId, Long reviewId);

    /** Data 서버 리뷰 중복 신고 검사. 상품이 달라도 리뷰 ID 가 겹칠 수 있어 상품까지 본다 */
    boolean existsByReporter_IdAndProduct_IdAndExternalReviewId(
            Long reporterId, Long productId, String externalReviewId);

    /** 내가 신고한 목록 (최신순) */
    @Query(value = "SELECT r FROM Report r LEFT JOIN FETCH r.review rv LEFT JOIN FETCH rv.product "
         + "LEFT JOIN FETCH r.product "
         + "WHERE r.reporter.id = :userId ORDER BY r.createdAt DESC, r.id DESC",
         countQuery = "SELECT COUNT(r) FROM Report r LEFT JOIN r.review rv LEFT JOIN rv.product "
         + "LEFT JOIN r.product WHERE r.reporter.id = :userId")
    Page<Report> findByReporterIdWithReview(@Param("userId") Long userId, Pageable pageable);

    /** 특정 신고 단건 조회 (신고자 본인 확인용) */
    Optional<Report> findByIdAndReporter_Id(Long reportId, Long reporterId);

    /** 전체 신고 목록 (관리자용, 상태 필터) */
    @Query(value = "SELECT r FROM Report r JOIN FETCH r.reporter "
         + "LEFT JOIN FETCH r.review rv LEFT JOIN FETCH rv.product LEFT JOIN FETCH r.product "
         + "WHERE r.status = :status ORDER BY r.createdAt DESC, r.id DESC",
         countQuery = "SELECT COUNT(r) FROM Report r LEFT JOIN r.review rv LEFT JOIN rv.product "
         + "LEFT JOIN r.product WHERE r.status = :status")
    Page<Report> findByStatus(@Param("status") ReportStatus status, Pageable pageable);

    /** 전체 신고 목록 (관리자용, 전체) */
    @Query(value = "SELECT r FROM Report r JOIN FETCH r.reporter "
         + "LEFT JOIN FETCH r.review rv LEFT JOIN FETCH rv.product LEFT JOIN FETCH r.product "
         + "ORDER BY r.createdAt DESC, r.id DESC",
         countQuery = "SELECT COUNT(r) FROM Report r LEFT JOIN r.review rv LEFT JOIN rv.product "
         + "LEFT JOIN r.product")
    Page<Report> findAllWithDetails(Pageable pageable);

    /** 내가 제출한 신고 수 */
    long countByReporter_Id(Long reporterId);

    /** 회원 탈퇴(UserDataPurger). 행마다 DELETE 를 보내지 않고 한 문장으로 지운다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Report x WHERE x.reporter.id = :userId")
    int deleteAllOfUser(@Param("userId") Long userId);
}
