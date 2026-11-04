package com.example.fireview.domain.notification.util;

import com.example.fireview.domain.notification.entity.NotificationType;
import com.example.fireview.domain.user.entity.UserSetting;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationSettingCheckerTest {

    private UserSetting allOff() {
        return UserSetting.builder()
                .notifyFeedbackResult(false)
                .notifyAnalysisComplete(false)
                .notifyRiskyProduct(false)
                .notifyMarketing(false)
                .build();
    }

    @Test
    void 키별로_해당_필드값만_반영한다() {
        UserSetting setting = allOff();
        setting.setNotifyRiskyProduct(true);

        assertThat(NotificationSettingChecker.isEnabled(setting, "notifyRiskyProduct")).isTrue();
        assertThat(NotificationSettingChecker.isEnabled(setting, "notifyFeedbackResult")).isFalse();
        assertThat(NotificationSettingChecker.isEnabled(setting, "notifyAnalysisComplete")).isFalse();
        assertThat(NotificationSettingChecker.isEnabled(setting, "notifyMarketing")).isFalse();
    }

    @Test
    void 알_수_없는_키는_발송을_허용한다() {
        assertThat(NotificationSettingChecker.isEnabled(allOff(), "unknownKey")).isTrue();
    }

    @Test
    void 모든_알림_유형의_settingKey_는_UserSetting_필드와_대응된다() {
        // 모든 플래그를 끈 상태에서 true 가 나오면 매핑 누락(알 수 없는 키로 처리됨)
        UserSetting setting = allOff();
        for (NotificationType type : NotificationType.values()) {
            if (type.getSettingKey() == null) continue; // SYSTEM: 서비스에서 설정 확인 없이 항상 발송
            assertThat(NotificationSettingChecker.isEnabled(setting, type.getSettingKey()))
                    .as("%s (%s)", type, type.getSettingKey())
                    .isFalse();
        }
    }
}
