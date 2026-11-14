package com.example.fireview.global.security;

import com.example.fireview.domain.user.entity.Role;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.global.config.JwtConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 발급한 액세스 토큰을 같은 키의 디코더로 읽을 수 있고, 클레임이 기대한 값인지 본다.
 */
class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long!!";

    private JwtTokenProvider provider;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() {
        JwtConfig config = jwtConfig(SECRET);
        provider = new JwtTokenProvider(config.jwtEncoder(), Duration.ofMinutes(15).toMillis());
        decoder = config.jwtDecoder();
    }

    @Test
    void 이메일_역할_사용자_ID_를_클레임에_싣는다() {
        Jwt jwt = decoder.decode(provider.generateToken(user()));

        assertThat(jwt.getSubject()).isEqualTo("user@example.com");
        assertThat(jwt.getClaimAsString("role")).isEqualTo(Role.USER.name());
        assertThat(((Number) jwt.getClaim("userId")).longValue()).isEqualTo(42L);
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("fireview");
    }

    @Test
    void 만료는_발급_시각에서_설정한_기간_뒤다() {
        Jwt jwt = decoder.decode(provider.generateToken(user()));

        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void 다른_키로_서명한_토큰은_거부한다() {
        String token = provider.generateToken(user());
        JwtDecoder otherDecoder = jwtConfig("another-secret-key-at-least-32-bytes!!").jwtDecoder();

        assertThatThrownBy(() -> otherDecoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private static JwtConfig jwtConfig(String secret) {
        JwtConfig config = new JwtConfig();
        ReflectionTestUtils.setField(config, "jwtSecret", secret);
        return config;
    }

    private static User user() {
        return User.builder()
                .id(42L)
                .email("user@example.com")
                .nickname("tester")
                .role(Role.USER)
                .build();
    }
}
