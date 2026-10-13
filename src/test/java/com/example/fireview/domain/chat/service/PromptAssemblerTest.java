package com.example.fireview.domain.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PromptAssemblerTest {

    private final PromptAssembler assembler = new PromptAssembler(new ObjectMapper(), new TopicGuard());

    @Test
    void 본문에_마크다운_기호를_쓰지_말라는_규칙이_있다() {
        String prompt = assembler.systemPrompt();

        assertThat(prompt).contains("[본문 서식]");
        assertThat(prompt).contains("마크다운 기호를 절대 쓰지 마라");
    }

    @Test
    void 프롬프트_자체도_마크다운_서식을_쓰지_않는다() {
        // 프롬프트가 제목·목록 서식을 쓰면 모델이 답변도 그 서식으로 쓴다.
        // 금지 기호를 설명하는 문장("별표(*, **)")은 서식이 아니므로, 굵게 표시로 감싼 경우만 본다.
        String[] lines = assembler.systemPrompt().split("\n");

        assertThat(Arrays.stream(lines).map(String::strip))
                .noneMatch(line -> line.startsWith("#"))
                .noneMatch(line -> line.startsWith("- "))
                .noneMatch(line -> line.startsWith("* "))
                .noneMatch(line -> line.matches(".*\\*\\*[^*\\s][^*]*\\*\\*.*"));
    }

    @Test
    void 응답_형식은_그대로_유지한다() {
        // LlmAnswer 가 첫 줄 ONTOPIC 과 구분 줄 --- 로 파싱한다
        String prompt = assembler.systemPrompt();

        assertThat(prompt).contains("ONTOPIC: yes", "ONTOPIC: no", "\n---\n");
    }

    @Test
    void 추천_여부_줄을_안내한다() {
        String prompt = assembler.systemPrompt();

        assertThat(prompt).contains("RECOMMEND: yes", "RECOMMEND: no");
        // ONTOPIC 바로 다음 줄에 둔다. LlmAnswer 는 그 자리만 읽는다
        assertThat(prompt).contains("ONTOPIC: yes\nRECOMMEND: no\n---\n");
    }

    @Test
    void 상품_이름이나_링크를_지어내지_말라고_한다() {
        // 추천 상품은 서버가 DB 에서 고른다. 모델이 만든 상품명·링크가 본문에 나오면 안 된다
        assertThat(assembler.systemPrompt())
                .contains("[비슷한 상품 추천]")
                .contains("상품 이름, 가격, 링크, 상품 번호를 지어내거나 본문에 적지 마라");
    }

    @Test
    void 리뷰_한_건의_점수를_상품_등급처럼_말하지_말라고_한다() {
        // 대표 리뷰에만 RTI 가 붙고 상품 평균은 없다. 모델이 리뷰 점수를 상품 등급으로 옮기면 안 된다
        assertThat(assembler.systemPrompt())
                .contains("대표리뷰의 RTI와 등급은 그 리뷰 한 건의 값이다");
    }

    @Test
    void 분석_리뷰_수와_쇼핑몰_리뷰_수를_따로_싣는다() {
        var ctx = new com.example.fireview.domain.chat.port.ProductAnalysisContext(
                "oliveyoung-A1", "크림", 27600, null, 95.5, "안전", 208,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), 5305);

        String message = assembler.buildUserMessage(ctx, "분석에 쓴 리뷰는 몇 건이야?");

        assertThat(message).contains("\"분석리뷰수\" : 208").contains("\"쇼핑몰전체리뷰수\" : 5305")
                .contains("\"평균RTI\" : 95.5").doesNotContain("판단 보류");
        assertThat(assembler.systemPrompt()).contains("분석에 쓴 리뷰가 몇 건인지는 분석리뷰수로만 답해라");
    }
}
