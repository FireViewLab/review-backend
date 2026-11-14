package com.example.fireview.domain.auth.oauth2;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 제공자마다 다른 사용자 정보 응답 구조를 같은 필드로 읽는다.
 */
class OAuth2UserInfoTest {

    @Test
    void 구글은_최상위_속성에서_읽는다() {
        OAuth2UserInfo info = new GoogleOAuth2UserInfo(Map.of(
                "sub", "google-123",
                "email", "user@gmail.com",
                "name", "홍길동",
                "picture", "https://lh3.googleusercontent.com/a.png"));

        assertThat(info.getProviderId()).isEqualTo("google-123");
        assertThat(info.getEmail()).isEqualTo("user@gmail.com");
        assertThat(info.getName()).isEqualTo("홍길동");
        assertThat(info.getProfileImageUrl()).isEqualTo("https://lh3.googleusercontent.com/a.png");
    }

    @Test
    void 구글_프로필_사진이_없으면_null() {
        OAuth2UserInfo info = new GoogleOAuth2UserInfo(Map.of("sub", "google-123", "email", "user@gmail.com"));

        assertThat(info.getProfileImageUrl()).isNull();
        assertThat(info.getName()).isNull();
    }

    @Test
    void 네이버는_response_안에서_읽는다() {
        OAuth2UserInfo info = new NaverOAuth2UserInfo(Map.of(
                "resultcode", "00",
                "response", Map.of(
                        "id", "naver-456",
                        "email", "user@naver.com",
                        "name", "김철수",
                        "profile_image", "https://phinf.pstatic.net/a.png")));

        assertThat(info.getProviderId()).isEqualTo("naver-456");
        assertThat(info.getEmail()).isEqualTo("user@naver.com");
        assertThat(info.getName()).isEqualTo("김철수");
        assertThat(info.getProfileImageUrl()).isEqualTo("https://phinf.pstatic.net/a.png");
    }

    @Test
    void 네이버_response_가_없으면_거부한다() {
        assertThatThrownBy(() -> new NaverOAuth2UserInfo(Map.of("resultcode", "024")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 네이버_response_가_객체가_아니면_거부한다() {
        assertThatThrownBy(() -> new NaverOAuth2UserInfo(Map.of("response", "unexpected")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
