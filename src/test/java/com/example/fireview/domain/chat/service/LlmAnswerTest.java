package com.example.fireview.domain.chat.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmAnswerTest {

    @Test
    void 정상_형식을_파싱한다() {
        LlmAnswer parsed = LlmAnswer.parse("""
                ONTOPIC: yes
                ---
                사이즈가 작다는 의견이 많습니다.""");

        assertThat(parsed.onTopic()).isTrue();
        assertThat(parsed.answer()).isEqualTo("사이즈가 작다는 의견이 많습니다.");
    }

    @Test
    void 주제이탈_판정을_파싱한다() {
        LlmAnswer parsed = LlmAnswer.parse("""
                ONTOPIC: no
                ---
                상품과 리뷰에 대해서만 도와드릴 수 있어요.""");

        assertThat(parsed.onTopic()).isFalse();
        assertThat(parsed.answer()).contains("상품과 리뷰");
    }

    /**
     * JSON 형식을 버린 이유가 바로 이것이다.
     * 본문에 큰따옴표가 들어가도 파싱이 깨지지 않아야 한다.
     */
    @Test
    void 본문에_따옴표가_있어도_파싱한다() {
        LlmAnswer parsed = LlmAnswer.parse("""
                ONTOPIC: yes
                ---
                리뷰에 "사이즈가 작다"는 표현이 반복됩니다. 'S' 사이즈는 피하세요.""");

        assertThat(parsed.onTopic()).isTrue();
        assertThat(parsed.answer()).contains("\"사이즈가 작다\"");
    }

    @Test
    void 본문의_줄바꿈과_마크다운을_보존한다() {
        LlmAnswer parsed = LlmAnswer.parse("""
                ONTOPIC: yes
                ---
                **장점**
                - 가격 대비 품질

                **단점**
                - 사이즈가 작음""");

        assertThat(parsed.answer()).contains("**장점**").contains("\n").endsWith("사이즈가 작음");
    }

    @Test
    void 본문에_구분자가_또_나와도_첫_구분자만_기준으로_한다() {
        LlmAnswer parsed = LlmAnswer.parse("""
                ONTOPIC: yes
                ---
                장점은 이렇습니다.
                ---
                단점은 이렇습니다.""");

        assertThat(parsed.answer()).startsWith("장점은").contains("---").contains("단점은");
    }

    @Test
    void 코드펜스로_감싼_응답도_파싱한다() {
        LlmAnswer parsed = LlmAnswer.parse("""
                ```
                ONTOPIC: yes
                ---
                가격 대비 평이 좋습니다.
                ```""");

        assertThat(parsed.onTopic()).isTrue();
        assertThat(parsed.answer()).isEqualTo("가격 대비 평이 좋습니다.");
    }

    @Test
    void 구분자를_빠뜨려도_본문을_건진다() {
        LlmAnswer parsed = LlmAnswer.parse("""
                ONTOPIC: yes
                가격 대비 평이 좋습니다.""");

        assertThat(parsed.onTopic()).isTrue();
        assertThat(parsed.answer()).isEqualTo("가격 대비 평이 좋습니다.");
    }

    @Test
    void true_false_표기도_허용한다() {
        assertThat(LlmAnswer.parse("ONTOPIC: true\n---\n답변").onTopic()).isTrue();
        assertThat(LlmAnswer.parse("ONTOPIC: false\n---\n답변").onTopic()).isFalse();
    }

    @Test
    void 헤더가_없으면_주제이탈로_처리한다() {
        // 형식을 어긴 응답을 그대로 노출하면 세이프가드 3계층을 우회하는 통로가 된다
        LlmAnswer parsed = LlmAnswer.parse("그냥 평문 답변입니다.");

        assertThat(parsed.onTopic()).isFalse();
        assertThat(parsed.answer()).contains("다시 질문");
    }

    @Test
    void 본문이_비면_주제이탈로_처리한다() {
        assertThat(LlmAnswer.parse("ONTOPIC: yes\n---\n   ").onTopic()).isFalse();
    }

    @Test
    void null과_빈_입력을_안전하게_처리한다() {
        assertThat(LlmAnswer.parse(null).onTopic()).isFalse();
        assertThat(LlmAnswer.parse("").onTopic()).isFalse();
    }

    // ── RECOMMEND 줄 ────────────────────────────────────────────────────────────

    @Test
    void RECOMMEND_줄이_없으면_추천하지_않는다() {
        LlmAnswer parsed = LlmAnswer.parse("ONTOPIC: yes\n---\n가격 대비 평이 좋습니다.");

        assertThat(parsed.recommend()).isFalse();
        assertThat(parsed.wantsRecommendations()).isFalse();
        assertThat(parsed.answer()).isEqualTo("가격 대비 평이 좋습니다.");
    }

    @Test
    void RECOMMEND_yes_를_읽고_본문에서는_뺀다() {
        LlmAnswer parsed = LlmAnswer.parse("""
                ONTOPIC: yes
                RECOMMEND: yes
                ---
                같은 분류의 비슷한 상품이 있으면 아래에 보여 드릴게요.""");

        assertThat(parsed.onTopic()).isTrue();
        assertThat(parsed.recommend()).isTrue();
        assertThat(parsed.wantsRecommendations()).isTrue();
        assertThat(parsed.answer()).isEqualTo("같은 분류의 비슷한 상품이 있으면 아래에 보여 드릴게요.");
    }

    @Test
    void RECOMMEND_no_를_읽는다() {
        LlmAnswer parsed = LlmAnswer.parse("ONTOPIC: yes\nRECOMMEND: no\n---\n사이즈가 작다는 의견이 많습니다.");

        assertThat(parsed.onTopic()).isTrue();
        assertThat(parsed.recommend()).isFalse();
        assertThat(parsed.answer()).isEqualTo("사이즈가 작다는 의견이 많습니다.");
    }

    @Test
    void RECOMMEND_값이_이상하면_추천하지_않는다() {
        for (String value : new String[]{"maybe", "", "yes please", "1"}) {
            LlmAnswer parsed = LlmAnswer.parse("ONTOPIC: yes\nRECOMMEND: " + value + "\n---\n답변입니다.");

            assertThat(parsed.recommend()).as(value).isFalse();
            assertThat(parsed.onTopic()).as(value).isTrue();
            assertThat(parsed.answer()).as(value).isEqualTo("답변입니다.");
        }
    }

    @Test
    void RECOMMEND_대소문자와_true_표기도_허용한다() {
        assertThat(LlmAnswer.parse("ONTOPIC: yes\nrecommend: TRUE\n---\n답변").recommend()).isTrue();
        assertThat(LlmAnswer.parse("ONTOPIC: yes\nRECOMMEND：yes\n---\n답변").recommend()).isTrue();
    }

    @Test
    void 구분자를_빠뜨려도_RECOMMEND_줄은_본문에_남지_않는다() {
        LlmAnswer parsed = LlmAnswer.parse("ONTOPIC: yes\nRECOMMEND: yes\n아래에 비슷한 상품을 보여 드릴게요.");

        assertThat(parsed.recommend()).isTrue();
        assertThat(parsed.answer()).isEqualTo("아래에 비슷한 상품을 보여 드릴게요.");
    }

    @Test
    void 엉뚱한_자리의_RECOMMEND_줄은_본문에서_지우고_추천으로_보지_않는다() {
        // 정해진 자리(ONTOPIC 다음 줄)에 둔 줄만 판단에 쓴다. 다른 자리의 줄은 노출만 막는다
        LlmAnswer parsed = LlmAnswer.parse("""
                ONTOPIC: yes
                ---
                장점은 가격입니다.
                RECOMMEND: yes""");

        assertThat(parsed.recommend()).isFalse();
        assertThat(parsed.answer()).isEqualTo("장점은 가격입니다.")
                .doesNotContain("RECOMMEND");
    }

    @Test
    void 코드펜스_안의_RECOMMEND_도_읽는다() {
        LlmAnswer parsed = LlmAnswer.parse("""
                ```
                ONTOPIC: yes
                RECOMMEND: yes
                ---
                아래에 보여 드릴게요.
                ```""");

        assertThat(parsed.recommend()).isTrue();
        assertThat(parsed.answer()).isEqualTo("아래에 보여 드릴게요.");
    }

    @Test
    void 주제이탈이면_RECOMMEND_yes_여도_추천하지_않는다() {
        LlmAnswer parsed = LlmAnswer.parse("ONTOPIC: no\nRECOMMEND: yes\n---\n상품과 리뷰에 대해서만 도와드릴 수 있어요.");

        assertThat(parsed.onTopic()).isFalse();
        assertThat(parsed.wantsRecommendations()).isFalse();
        assertThat(parsed.answer()).doesNotContain("RECOMMEND");
    }

    @Test
    void RECOMMEND_줄만_있고_본문이_없으면_형식_위반이다() {
        LlmAnswer parsed = LlmAnswer.parse("ONTOPIC: yes\nRECOMMEND: yes\n---\n");

        assertThat(parsed.onTopic()).isFalse();
        assertThat(parsed.recommend()).isFalse();
        assertThat(parsed.answer()).contains("다시 질문");
    }

    @Test
    void 형식_위반이면_추천하지_않는다() {
        LlmAnswer parsed = LlmAnswer.parse("RECOMMEND: yes\n그냥 답변");

        assertThat(parsed.onTopic()).isFalse();
        assertThat(parsed.wantsRecommendations()).isFalse();
    }
    @Test
    void RECOMMEND_다음_SCHEDULE_줄을_읽는다() {
        LlmAnswer parsed = LlmAnswer.parse(
                "ONTOPIC: yes\nRECOMMEND: no\nSCHEDULE: weekly MON 09:00 qty=1\n---\n아래 버튼을 누르면 예약돼요.");

        assertThat(parsed.scheduleValue()).isEqualTo("weekly MON 09:00 qty=1");
        assertThat(parsed.answer()).isEqualTo("아래 버튼을 누르면 예약돼요.");
        assertThat(parsed.wantsRecommendations()).isFalse();
    }

    @Test
    void SCHEDULE_줄이_RECOMMEND_보다_먼저_와도_둘_다_읽는다() {
        LlmAnswer parsed = LlmAnswer.parse(
                "ONTOPIC: yes\nSCHEDULE: monthly 1 09:00 qty=2\nRECOMMEND: yes\n---\n본문");

        assertThat(parsed.scheduleValue()).isEqualTo("monthly 1 09:00 qty=2");
        assertThat(parsed.recommend()).isTrue();
    }

    @Test
    void SCHEDULE_줄이_없으면_예약_값은_null이다() {
        LlmAnswer parsed = LlmAnswer.parse("ONTOPIC: yes\nRECOMMEND: no\n---\n본문");

        assertThat(parsed.scheduleValue()).isNull();
        assertThat(parsed.answer()).isEqualTo("본문");
    }

    @Test
    void 본문에_섞인_SCHEDULE_줄은_지우고_예약에_쓰지_않는다() {
        LlmAnswer parsed = LlmAnswer.parse(
                "ONTOPIC: yes\nRECOMMEND: no\n---\n본문\nSCHEDULE: weekly MON 09:00 qty=1");

        assertThat(parsed.scheduleValue()).isNull();
        assertThat(parsed.answer()).isEqualTo("본문");
    }

    @Test
    void 주제이탈이면_SCHEDULE_이_있어도_예약하지_않는다() {
        LlmAnswer parsed = LlmAnswer.parse(
                "ONTOPIC: no\nRECOMMEND: no\nSCHEDULE: weekly MON 09:00 qty=1\n---\n안내");

        assertThat(parsed.wantsSchedule()).isFalse();
    }
}
