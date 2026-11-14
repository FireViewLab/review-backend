package com.example.fireview.domain.user.service;

import com.example.fireview.domain.user.dto.UserSettingResponse;
import com.example.fireview.domain.user.dto.UserSettingUpdateRequest;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.entity.UserSetting;
import com.example.fireview.domain.user.repository.UserSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 설정 수정은 요청에 들어온 필드만 바꾸고, 저장된 설정이 없으면 기본값에서 시작한다.
 */
class UserSettingServiceTest {

    private static final String EMAIL = "user@example.com";

    private final UserSettingRepository repository = mock(UserSettingRepository.class);
    private final UserService userService = mock(UserService.class);
    private final UserSettingService service = new UserSettingService(repository, userService);

    private final User user = User.builder().id(7L).email(EMAIL).nickname("tester").build();

    @BeforeEach
    void setUp() {
        when(userService.findByEmail(EMAIL)).thenReturn(user);
        when(repository.save(any(UserSetting.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void 저장된_설정이_없으면_기본값을_돌려준다() {
        when(repository.findByUser_Id(7L)).thenReturn(Optional.empty());

        assertThat(service.getSettings(EMAIL)).isEqualTo(UserSettingResponse.defaults());
    }

    @Test
    void 요청에_있는_필드만_바꾼다() {
        UserSetting saved = UserSetting.builder().user(user).theme("DARK").rtiThreshold(70).build();
        when(repository.findByUser_Id(7L)).thenReturn(Optional.of(saved));

        UserSettingResponse response = service.updateSettings(EMAIL, request(false, null, null));

        assertThat(response.notifyRiskyProduct()).isFalse();
        assertThat(response.theme()).isEqualTo("DARK");
        assertThat(response.rtiThreshold()).isEqualTo(70);
    }

    @Test
    void 저장된_설정이_없으면_기본값에_요청을_덮어_새로_만든다() {
        when(repository.findByUser_Id(7L)).thenReturn(Optional.empty());

        UserSettingResponse response = service.updateSettings(EMAIL, request(null, 30, "LIGHT"));

        UserSettingResponse defaults = UserSettingResponse.defaults();
        assertThat(response.rtiThreshold()).isEqualTo(30);
        assertThat(response.theme()).isEqualTo("LIGHT");
        assertThat(response.notifyRiskyProduct()).isEqualTo(defaults.notifyRiskyProduct());
        assertThat(response.cardDensity()).isEqualTo(defaults.cardDensity());
    }

    private static UserSettingUpdateRequest request(Boolean notifyRiskyProduct, Integer rtiThreshold, String theme) {
        return new UserSettingUpdateRequest(
                notifyRiskyProduct, null, null, null,
                rtiThreshold, null, null, null, null,
                null, null, null, theme,
                null);
    }
}
