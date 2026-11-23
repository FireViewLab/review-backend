package com.example.fireview.domain.user.service;

import com.example.fireview.domain.auth.service.RefreshTokenService;
import com.example.fireview.domain.notification.repository.NotificationRepository;
import com.example.fireview.domain.report.repository.ReportRepository;
import com.example.fireview.domain.review.repository.ReviewFeedbackRepository;
import com.example.fireview.domain.user.dto.ProfileUpdateRequest;
import com.example.fireview.domain.user.dto.UserActivityResponse;
import com.example.fireview.domain.user.dto.UserResponse;
import com.example.fireview.domain.user.dto.UserSecurityResponse;
import com.example.fireview.domain.user.dto.UserStatsResponse;
import com.example.fireview.domain.user.entity.PlanTier;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.repository.UserRepository;
import com.example.fireview.domain.wishlist.repository.WishlistRepository;
import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final WishlistRepository wishlistRepository;
    private final ReviewFeedbackRepository feedbackRepository;
    private final ReportRepository reportRepository;
    private final NotificationRepository notificationRepository;
    private final RefreshTokenService refreshTokenService;
    private final UserDataPurger userDataPurger;

    // ── 요금제 ────────────────────────────────────────────────────────────────

    /**
     * 사용자 본인이 요금제를 바꾼다.
     *
     * <p><b>결제 없이 바로 바뀐다.</b> 결제 연동 전까지 요금제 화면을 쓸 수 있게 하려는
     * 임시 경로다. 결제가 붙으면 이 메서드는 결제 확인 뒤에만 호출되어야 하고,
     * 지금처럼 컨트롤러에서 바로 부르면 누구나 무료로 PRO 를 고를 수 있다.
     *
     * <p>만료 시각은 두지 않는다(무기한). 기간제 판매가 생기면 결제 쪽에서 정한다.
     * 이미 같은 요금제여도 실패시키지 않는다 — 화면에서 같은 버튼을 두 번 눌러도
     * 사용자가 오류를 볼 이유가 없다.
     */
    @Transactional
    public UserResponse changeMyPlan(String email, PlanTier planTier) {
        User user = findByEmail(email);
        user.changePlan(planTier, null);
        return UserResponse.from(user);
    }

    // ── 조회 ─────────────────────────────────────────────────────────────────

    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    public UserResponse getProfile(String email) {
        return UserResponse.from(findByEmail(email));
    }

    /** 이용 통계 조회 */
    public UserStatsResponse getStats(String email) {
        User user = findByEmail(email);
        long wishlistCount   = wishlistRepository.countByUser_Id(user.getId());
        long feedbackCount   = feedbackRepository.countByUser_Id(user.getId());
        long reportCount     = reportRepository.countByReporter_Id(user.getId());
        long unreadCount     = notificationRepository.countByReceiver_IdAndIsReadFalse(user.getId());
        return new UserStatsResponse(wishlistCount, feedbackCount, reportCount, unreadCount);
    }

    // ── 수정 ─────────────────────────────────────────────────────────────────

    /** 프로필 수정 (닉네임, 프로필 이미지) */
    @Transactional
    public UserResponse updateProfile(String email, ProfileUpdateRequest request) {
        User user = findByEmail(email);

        if (request.nickname() != null && !request.nickname().equals(user.getNickname())) {
            if (userRepository.existsByNickname(request.nickname())) {
                throw new CustomException(ErrorCode.NICKNAME_ALREADY_EXISTS);
            }
            user.setNickname(request.nickname());
        }

        if (request.profileImageUrl() != null) {
            user.setProfileImageUrl(request.profileImageUrl());
        }

        if (request.phone() != null) {
            user.setPhone(request.phone());
        }

        if (request.interestCategories() != null) {
            user.getInterestCategories().clear();
            user.getInterestCategories().addAll(request.interestCategories());
        }

        return UserResponse.from(userRepository.save(user));
    }

    /**
     * 회원 탈퇴.
     *
     * <p>사용자를 가리키는 행(찜·장바구니·담기 이력·예약·조회 이력·알림·설정·선호·채팅·신고·피드백)을
     * 먼저 지운다({@link UserDataPurger}). 하나라도 남으면 사용자 삭제가 외래 키에 막힌다.
     * 이 삭제는 영속성 컨텍스트를 비우므로 사용자는 id 로 지운다.
     */
    @Transactional
    public void deleteAccount(String email) {
        User user = findByEmail(email);
        Long userId = user.getId();
        userDataPurger.purge(userId);
        userRepository.deleteById(userId);
        refreshTokenService.revokeAll(userId);
    }

    /** 최근 활동 목록 (찜 추가, 피드백 제출 최신 10건 혼합) */
    public List<UserActivityResponse> getRecentActivities(String email) {
        User user = findByEmail(email);
        List<UserActivityResponse> activities = new ArrayList<>();

        wishlistRepository.findByUser_IdOrderByCreatedAtDesc(user.getId())
                .stream().limit(5).forEach(w -> activities.add(new UserActivityResponse(
                        "WISHLIST_ADD",
                        w.getProduct().getName() + " 저장",
                        w.getProduct().getId().toString(),
                        w.getCreatedAt()
                )));

        // 외부(Data 서버) 리뷰 피드백은 내부 리뷰가 없다. 대상 ID 로 외부 리뷰 ID 를 쓴다
        feedbackRepository.findByUserIdWithReview(user.getId(), PageRequest.of(0, 5))
                .forEach(f -> activities.add(new UserActivityResponse(
                        "FEEDBACK_SUBMIT",
                        "분석 결과 피드백 제출",
                        f.reviewIdOrNull() != null ? f.reviewIdOrNull().toString() : f.getExternalReviewId(),
                        f.getCreatedAt()
                )));

        activities.sort(Comparator.comparing(UserActivityResponse::createdAt).reversed());
        return activities.stream().limit(10).toList();
    }

    /** 보안 상태 조회 */
    public UserSecurityResponse getSecurityStatus(String email) {
        return UserSecurityResponse.from(findByEmail(email));
    }

    // ── 내부 사용 ────────────────────────────────────────────────────────────

    @Transactional
    public void markOnboardingComplete(Long userId) {
        User user = findById(userId);
        user.setOnboardingCompleted(true);
        userRepository.save(user);
    }
}
