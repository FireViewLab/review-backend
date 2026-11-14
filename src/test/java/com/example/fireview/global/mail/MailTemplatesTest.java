package com.example.fireview.global.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메일 본문에 끼워 넣는 값은 HTML 이스케이프한다(#235).
 */
class MailTemplatesTest {

    @Test
    void 재설정_링크를_본문에_넣는다() {
        String html = MailTemplates.passwordReset("https://re-view.kr/reset?token=abc");

        assertThat(html).contains("href=\"https://re-view.kr/reset?token=abc\"");
    }

    @Test
    void 재설정_링크의_따옴표는_속성을_벗어나지_못한다() {
        String html = MailTemplates.passwordReset("https://re-view.kr/reset?token=a\"><script>");

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&quot;&gt;&lt;script&gt;");
    }

    @Test
    void 관리자_코멘트의_태그는_글자로_보인다() {
        String html = MailTemplates.analysisFeedbackResult(
                "반영 완료", "<a href=\"https://evil.example\">확인</a>", "https://re-view.kr/mypage");

        assertThat(html).doesNotContain("evil.example\">");
        assertThat(html).contains("&lt;a href=&quot;https://evil.example&quot;&gt;");
    }

    @Test
    void 코멘트가_비어_있으면_코멘트_문단을_넣지_않는다() {
        String html = MailTemplates.analysisFeedbackResult("반려", " ", "https://re-view.kr/mypage");

        assertThat(html).doesNotContain("관리자 코멘트");
        assertThat(html).contains("<strong>반려</strong>");
    }
}
