package com.example.fireview.domain.user.service;

import com.example.fireview.domain.cart.repository.CartEventRepository;
import com.example.fireview.domain.cart.repository.CartRepository;
import com.example.fireview.domain.cartschedule.repository.CartScheduleRepository;
import com.example.fireview.domain.chat.repository.ChatMessageRepository;
import com.example.fireview.domain.chat.repository.ChatSessionRepository;
import com.example.fireview.domain.dashboard.repository.ViewHistoryRepository;
import com.example.fireview.domain.feedback.repository.AnalysisFeedbackRepository;
import com.example.fireview.domain.notification.repository.NotificationRepository;
import com.example.fireview.domain.onboarding.repository.UserPreferenceRepository;
import com.example.fireview.domain.report.repository.ReportRepository;
import com.example.fireview.domain.review.repository.ReviewFeedbackRepository;
import com.example.fireview.domain.user.repository.UserSettingRepository;
import com.example.fireview.domain.wishlist.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 회원 탈퇴 전에 사용자를 가리키는 행을 모두 지운다.
 *
 * <p>{@code users} 를 외래 키로 무는 테이블이 여럿이고 DB 에 ON DELETE CASCADE 가 없다. 하나라도 남으면
 * 사용자 삭제가 외래 키에 막혀 탈퇴가 500 으로 실패한다. 예전에는 담기 이력·예약만 지워서 찜·장바구니·
 * 조회 이력·알림·설정·채팅 중 하나라도 있으면 탈퇴할 수 없었다.
 *
 * <p>신고·분석 피드백도 지운다. 탈퇴한 사용자의 기록을 남기려면 작성자 칸을 비워야 하는데
 * 칼럼이 NOT NULL 이고, 운영 스키마는 {@code ddl-auto=update} 라 제약을 풀어 주지 않는다.
 *
 * <p><b>새로 {@code users} 를 무는 테이블을 만들면 여기에 추가한다.</b>
 * {@code UserDeleteWithAllRecordsTest} 가 빠진 테이블을 잡는다.
 */
@Component
@RequiredArgsConstructor
class UserDataPurger {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final WishlistRepository wishlistRepository;
    private final CartRepository cartRepository;
    private final CartEventRepository cartEventRepository;
    private final CartScheduleRepository cartScheduleRepository;
    private final ViewHistoryRepository viewHistoryRepository;
    private final NotificationRepository notificationRepository;
    private final UserSettingRepository userSettingRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final ReviewFeedbackRepository reviewFeedbackRepository;
    private final ReportRepository reportRepository;
    private final AnalysisFeedbackRepository analysisFeedbackRepository;

    /** 탈퇴 트랜잭션 안에서만 부른다. 사용자 행 자체는 지우지 않는다 */
    @Transactional(propagation = Propagation.MANDATORY)
    void purge(Long userId) {
        // 컬렉션 테이블(선호 카테고리·피드백 신호)을 가진 엔티티는 일괄 DELETE 가 그 테이블을 지우지 않아
        // 엔티티로 지운다. 아래 일괄 DELETE 가 실행 전에 이 삭제를 먼저 flush 한다
        userPreferenceRepository.findByUser_Id(userId).ifPresent(userPreferenceRepository::delete);
        analysisFeedbackRepository.deleteAll(analysisFeedbackRepository.findAllBySubmitter_Id(userId));

        List<Long> sessionIds = chatSessionRepository.findIdsByUserId(userId);
        if (!sessionIds.isEmpty()) {
            chatMessageRepository.deleteBySessionIds(sessionIds);
            chatSessionRepository.deleteByIds(sessionIds);
        }
        cartEventRepository.deleteByUserId(userId);
        cartScheduleRepository.deleteByUserId(userId);
        cartRepository.deleteAllOfUser(userId);
        wishlistRepository.deleteAllOfUser(userId);
        viewHistoryRepository.deleteAllOfUser(userId);
        notificationRepository.deleteAllOfUser(userId);
        userSettingRepository.deleteAllOfUser(userId);
        reviewFeedbackRepository.deleteAllOfUser(userId);
        reportRepository.deleteAllOfUser(userId);
    }
}
