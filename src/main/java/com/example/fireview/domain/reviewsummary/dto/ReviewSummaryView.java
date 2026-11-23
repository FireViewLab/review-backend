package com.example.fireview.domain.reviewsummary.dto;

import com.example.fireview.domain.reviewsummary.entity.ProductReviewSummary;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 상세 응답에 싣는 상품 리뷰 요약(#277).
 *
 * @param pros              장점 2~3개
 * @param cons              아쉬운 점 0~3개. 리뷰에 아쉬운 점이 없으면 빈 배열
 * @param sourceReviewCount 요약에 넣은 리뷰 수. 의심 등급(DANGER) 리뷰는 넣지 않는다
 * @param generatedAt       만든 시각. 리뷰가 바뀌면 뒤에서 다시 만들고, 그동안은 이전 요약이 보인다
 */
public record ReviewSummaryView(
        List<String> pros,
        List<String> cons,
        Integer sourceReviewCount,
        LocalDateTime generatedAt
) {
    public static ReviewSummaryView from(ProductReviewSummary summary) {
        return new ReviewSummaryView(summary.prosList(), summary.consList(),
                summary.getSourceReviewCount(), summary.getGeneratedAt());
    }
}
