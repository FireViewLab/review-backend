package com.example.fireview.domain.reviewsummary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewSummaryPromptTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 리뷰를_태그_안에_번호와_별점으로_싣는다() {
        String msg = ReviewSummaryPrompt.userMessage("샘플 상품", List.of(
                new ReviewSummaryPrompt.ReviewInput("촉촉하고\n좋아요", 5.0),
                new ReviewSummaryPrompt.ReviewInput("향이 강해요", null)));

        assertThat(msg).contains("상품: 샘플 상품", "<reviews>", "1. [별점 5] 촉촉하고 좋아요", "2. 향이 강해요", "</reviews>");
    }

    @Test
    void 긴_리뷰는_자른다() {
        String msg = ReviewSummaryPrompt.userMessage("상품",
                List.of(new ReviewSummaryPrompt.ReviewInput("가".repeat(500), 4.0)));

        assertThat(msg).contains("가".repeat(300) + "…").doesNotContain("가".repeat(301));
    }

    @Test
    void 앞뒤에_말이_붙어도_JSON을_읽는다() {
        var summary = ReviewSummaryPrompt.parse("""
                요약입니다.
                ```json
                {"pros": ["보습력 좋음", " 향이 은은함 "], "cons": ["용량 대비 비쌈"]}
                ```
                """, mapper).orElseThrow();

        assertThat(summary.pros()).containsExactly("보습력 좋음", "향이 은은함");
        assertThat(summary.cons()).containsExactly("용량 대비 비쌈");
    }

    @Test
    void 항목은_세_개까지_자르고_빈_값과_문자열이_아닌_값은_뺀다() {
        var summary = ReviewSummaryPrompt.parse("""
                {"pros": ["a", "", 3, "b", "c", "d"], "cons": null}
                """, mapper).orElseThrow();

        assertThat(summary.pros()).containsExactly("a", "b", "c");
        assertThat(summary.cons()).isEmpty();
    }

    @Test
    void 장점이_없거나_JSON이_아니면_비어_있다() {
        assertThat(ReviewSummaryPrompt.parse("{\"pros\": [], \"cons\": [\"아쉬움\"]}", mapper)).isEmpty();
        assertThat(ReviewSummaryPrompt.parse("요약할 수 없습니다", mapper)).isEmpty();
        assertThat(ReviewSummaryPrompt.parse("{깨진 json}", mapper)).isEmpty();
        assertThat(ReviewSummaryPrompt.parse(null, mapper)).isEmpty();
    }

    @Test
    void 항목_안의_줄바꿈은_공백으로_바꾼다() {
        // 항목을 줄바꿈으로 이어 저장하므로 항목 안에 줄바꿈이 남으면 다시 읽을 때 쪼개진다
        var summary = ReviewSummaryPrompt.parse("{\"pros\": [\"보습력\\n좋음\"]}", mapper).orElseThrow();

        assertThat(summary.pros()).containsExactly("보습력 좋음");
    }
}
