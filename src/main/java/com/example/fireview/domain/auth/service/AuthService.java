package com.example.fireview.domain.auth.service;

import com.example.fireview.domain.auth.dto.AuthResult;
import com.example.fireview.domain.auth.dto.LoginRequest;
import com.example.fireview.domain.auth.dto.LoginResponse;
import com.example.fireview.domain.auth.dto.PasswordResetRequest;
import com.example.fireview.domain.auth.dto.SignupRequest;
import com.example.fireview.domain.user.entity.OAuthProvider;
import com.example.fireview.domain.user.entity.Role;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.repository.UserRepository;
import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import com.example.fireview.global.mail.EmailService;
import com.example.fireview.global.mail.MailTemplates;
import com.example.fireview.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordResetTokenStore resetTokenStore;
    private final EmailService emailService;
    private final RefreshTokenService refreshTokenService;

    @Value("${app.mail.from:noreply@re-view.kr}")
    private String mailFrom;

    @Value("${app.mail.password-reset-base-url:https://re-view.kr/reset-password}")
    private String passwordResetBaseUrl;

    @Transactional
    public AuthResult signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .nickname(request.nickname())
                .role(Role.USER)
                .provider(OAuthProvider.LOCAL)
                .onboardingCompleted(false)
                .build();
        User saved = userRepository.save(user);

        return issueSession(saved);
    }

    public AuthResult login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_CREDENTIALS);
        }

        return issueSession(user);
    }

    /**
     * 리프레시 토큰을 회전하고 새 액세스 토큰을 발급한다. 본문 모양은 로그인 응답과 같다.
     *
     * @throws InvalidRefreshTokenException 토큰이 무효이거나 사용자가 없을 때
     */
    public AuthResult refresh(String refreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(refreshToken);
        return new AuthResult(toLoginResponse(rotation.user()), rotation.refreshToken());
    }

    /** 로그아웃. 토큰이 없거나 이미 무효여도 성공으로 본다 */
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    /** 리프레시 저장소 장애면 액세스 토큰만으로 로그인시킨다({@link RefreshTokenService#tryIssue}) */
    private AuthResult issueSession(User user) {
        LoginResponse response = toLoginResponse(user);
        return refreshTokenService.tryIssue(user)
                .map(token -> new AuthResult(response, token))
                .orElseGet(() -> AuthResult.withoutRefreshToken(response));
    }

    private LoginResponse toLoginResponse(User user) {
        String token = jwtTokenProvider.generateToken(user);
        return new LoginResponse(token, user.getEmail(), user.getNickname(), user.getRole(), user.isOnboardingCompleted());
    }

    public void requestPasswordReset(String email) {
        userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        String token = resetTokenStore.issue(email);
        String resetUrl = passwordResetBaseUrl + "?token=" + token;
        emailService.sendHtml(
                email,
                "[Re:view] 비밀번호 재설정 안내",
                MailTemplates.passwordReset(resetUrl)
        );
    }

    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        String email = resetTokenStore.consume(request.token());
        if (email == null) {
            throw new CustomException(ErrorCode.INVALID_RESET_TOKEN);
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        // 비밀번호가 바뀌었으니 다른 기기에 남은 로그인 세션을 모두 끊는다
        refreshTokenService.revokeAll(user.getId());
    }
}
