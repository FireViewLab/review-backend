package com.example.fireview.domain.review.service;

import com.example.fireview.domain.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * ATI (Account Trust Index) 서비스
 *
 * ATI는 리뷰 작성자 계정의 신뢰도 점수입니다.
 * 당근마켓 온도처럼 리뷰어 프로필 옆에 표시됩니다.
 *
 * [계산 방식]
 * - 해당 reviewerId로 작성된 모든 리뷰의 RTI 평균 = ATI
 * - RTI가 높은 리뷰를 많이 작성한 계정 = 높은 ATI (신뢰)
 * - 도배/조작성 리뷰를 많이 작성한 계정 = 낮은 ATI (위험)
 *
 * [등급 기준]
 * - 80 이상: 신뢰 계정
 * - 50~79 : 주의 계정
 * - 50 미만: 의심 계정
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AtiService {

    private static final double DEFAULT_ATI = 50.0;

    private final ReviewRepository reviewRepository;

    /**
     * 특정 작성자의 ATI 점수 계산
     *
     * @param reviewerId 리뷰 작성자 ID
     * @return ATI 점수 (0~100), 리뷰가 없으면 기본값 50.0
     */
    public double calculateAti(String reviewerId) {
        if (reviewerId == null || reviewerId.isBlank()) {
            return DEFAULT_ATI;
        }
        Double avgRti = reviewRepository.findAvgRtiByReviewerId(reviewerId);
        if (avgRti == null) {
            return DEFAULT_ATI;
        }
        double ati = Math.round(avgRti * 10.0) / 10.0;
        log.debug("[ATI] reviewerId={}, atiScore={}", reviewerId, ati);
        return ati;
    }

    /**
     * 특정 상품의 모든 리뷰어 ATI를 한 번에 조회 (N+1 방지)
     *
     * 작성자가 없거나 평균 RTI 가 null(그 작성자 리뷰의 rti_score 가 전부 null)인 행은 뺀다(#240).
     * 그대로 두면 toMap 언박싱에서 NPE 가 나 리뷰 목록 조회 전체가 실패한다.
     * 빠진 작성자는 호출부에서 기본값(50.0)으로 채운다.
     *
     * @param productId 상품 ID
     * @return reviewerId → ATI 점수 맵
     */
    public Map<String, Double> calculateAtiMapByProduct(Long productId) {
        return reviewRepository.findReviewerAtiByProductId(productId)
                .stream()
                .filter(proj -> proj.getReviewerId() != null && proj.getAvgRti() != null)
                .collect(Collectors.toMap(
                        ReviewRepository.ReviewerAtiProjection::getReviewerId,
                        proj -> Math.round(proj.getAvgRti() * 10.0) / 10.0
                ));
    }

    /**
     * ATI 등급 레이블 반환
     */
    public String getAtiLabel(double atiScore) {
        if (atiScore >= 80) return "신뢰";
        if (atiScore >= 50) return "주의";
        return "의심";
    }
}
