package com.example.fireview.domain.review.service;

import com.example.fireview.domain.review.repository.ReviewRepository;
import com.example.fireview.domain.review.repository.ReviewRepository.ReviewerAtiProjection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ATI 는 작성자 리뷰의 평균 RTI 를 소수 첫째 자리로 반올림한 값이다.
 */
class AtiServiceTest {

    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final AtiService atiService = new AtiService(reviewRepository);

    @Test
    void 작성자가_없으면_기본값_50() {
        assertThat(atiService.calculateAti(null)).isEqualTo(50.0);
        assertThat(atiService.calculateAti(" ")).isEqualTo(50.0);
        verifyNoInteractions(reviewRepository);
    }

    @Test
    void 리뷰가_없으면_기본값_50() {
        when(reviewRepository.findAvgRtiByReviewerId("r1")).thenReturn(null);

        assertThat(atiService.calculateAti("r1")).isEqualTo(50.0);
    }

    @Test
    void 평균을_소수_첫째_자리로_반올림한다() {
        when(reviewRepository.findAvgRtiByReviewerId("r1")).thenReturn(72.46);

        assertThat(atiService.calculateAti("r1")).isEqualTo(72.5);
    }

    @Test
    void 상품의_작성자별_ATI_맵을_만든다() {
        when(reviewRepository.findReviewerAtiByProductId(1L)).thenReturn(List.of(
                projection("r1", 81.04),
                projection("r2", 33.36)));

        assertThat(atiService.calculateAtiMapByProduct(1L))
                .containsExactlyInAnyOrderEntriesOf(Map.of("r1", 81.0, "r2", 33.4));
    }

    @Test
    void 평균이_null_이거나_작성자가_없는_행은_건너뛴다() {
        when(reviewRepository.findReviewerAtiByProductId(1L)).thenReturn(List.of(
                projection("r1", 90.0),
                projection("r2", null),
                projection(null, 40.0)));

        assertThat(atiService.calculateAtiMapByProduct(1L))
                .containsExactlyEntriesOf(Map.of("r1", 90.0));
    }

    @Test
    void 등급은_80과_50을_경계로_나눈다() {
        assertThat(atiService.getAtiLabel(80.0)).isEqualTo("신뢰");
        assertThat(atiService.getAtiLabel(79.9)).isEqualTo("주의");
        assertThat(atiService.getAtiLabel(50.0)).isEqualTo("주의");
        assertThat(atiService.getAtiLabel(49.9)).isEqualTo("의심");
    }

    private static ReviewerAtiProjection projection(String reviewerId, Double avgRti) {
        return new ReviewerAtiProjection() {
            @Override
            public String getReviewerId() {
                return reviewerId;
            }

            @Override
            public Double getAvgRti() {
                return avgRti;
            }
        };
    }
}
