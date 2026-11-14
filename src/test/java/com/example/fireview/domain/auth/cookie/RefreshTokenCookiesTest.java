package com.example.fireview.domain.auth.cookie;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리프레시 토큰 쿠키의 속성과 읽기 규칙.
 */
class RefreshTokenCookiesTest {

    private final RefreshTokenCookies cookies =
            new RefreshTokenCookies("review_rt", "/api/auth", true, "Lax", Duration.ofMinutes(30));

    @Test
    void 발급_쿠키는_HttpOnly_Secure_SameSite_와_만료를_단다() {
        String header = cookies.issue("token-value");

        assertThat(header)
                .startsWith("review_rt=token-value")
                .contains("Path=/api/auth")
                .contains("Max-Age=1800")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax")
                .doesNotContain("Domain=");
    }

    @Test
    void 삭제_쿠키는_값이_비고_Max_Age_가_0이다() {
        String header = cookies.clear();

        assertThat(header)
                .startsWith("review_rt=;")
                .contains("Max-Age=0")
                .contains("Path=/api/auth");
    }

    @Test
    void 이름이_맞는_쿠키_값을_읽는다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("other", "x"), new Cookie("review_rt", "token-value"));

        assertThat(cookies.read(request)).contains("token-value");
    }

    @Test
    void 쿠키가_없으면_비어_있다() {
        assertThat(cookies.read(new MockHttpServletRequest())).isEmpty();
    }

    @Test
    void 값이_빈_쿠키는_없는_것으로_본다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("review_rt", ""));

        assertThat(cookies.read(request)).isEmpty();
    }
}
