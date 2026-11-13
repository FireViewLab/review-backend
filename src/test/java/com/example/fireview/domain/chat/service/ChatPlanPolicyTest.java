package com.example.fireview.domain.chat.service;

import com.example.fireview.domain.chat.client.LlmClient;
import com.example.fireview.domain.chat.entity.ChatTier;
import com.example.fireview.domain.user.entity.PlanTier;
import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatPlanPolicyTest {

    private ChatPlanPolicy policy(String proModel) {
        return new ChatPlanPolicy(5, 100, 300, proModel, 1200L);
    }

    @Test
    void 요금제별_하루_한도를_설정값대로_돌려준다() {
        ChatPlanPolicy policy = policy("");

        assertThat(policy.dailyLimit(PlanTier.FREE)).isEqualTo(5);
        assertThat(policy.dailyLimit(PlanTier.PLUS)).isEqualTo(100);
        assertThat(policy.dailyLimit(PlanTier.PRO)).isEqualTo(300);
    }

    @Test
    void 요금제가_null_이면_FREE_한도를_쓴다() {
        assertThat(policy("").dailyLimit(null)).isEqualTo(5);
    }

    @Test
    void STANDARD_엔드포인트는_모든_요금제가_쓸_수_있다() {
        ChatPlanPolicy policy = policy("");

        for (PlanTier plan : PlanTier.values()) {
            assertThat(policy.canUse(plan, ChatTier.STANDARD)).isTrue();
        }
    }

    @Test
    void PRO_엔드포인트는_PRO_요금제만_쓸_수_있다() {
        ChatPlanPolicy policy = policy("");

        assertThat(policy.canUse(PlanTier.PRO, ChatTier.PRO)).isTrue();
        assertThat(policy.canUse(PlanTier.PLUS, ChatTier.PRO)).isFalse();
        assertThat(policy.canUse(PlanTier.FREE, ChatTier.PRO)).isFalse();
        assertThat(policy.canUse(null, ChatTier.PRO)).isFalse();
    }

    @Test
    void 권한이_없으면_CHAT_PLAN_REQUIRED_로_거절한다() {
        ChatPlanPolicy policy = policy("");

        assertThatThrownBy(() -> policy.verifyAccess(PlanTier.PLUS, ChatTier.PRO))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_PLAN_REQUIRED);
        assertThatCode(() -> policy.verifyAccess(PlanTier.PRO, ChatTier.PRO)).doesNotThrowAnyException();
    }

    @Test
    void STANDARD_는_기본_LLM_옵션을_쓴다() {
        assertThat(policy("big-model").llmOptions(ChatTier.STANDARD))
                .isEqualTo(LlmClient.LlmOptions.defaults());
    }

    @Test
    void PRO_는_모델과_출력_길이를_올린다() {
        LlmClient.LlmOptions options = policy("big-model").llmOptions(ChatTier.PRO);

        assertThat(options.model()).isEqualTo("big-model");
        assertThat(options.maxTokens()).isEqualTo(1200L);
    }

    @Test
    void PRO_모델이_비어_있으면_모델은_기본값이고_출력_길이만_늘어난다() {
        LlmClient.LlmOptions options = policy("  ").llmOptions(ChatTier.PRO);

        assertThat(options.model()).isNull();
        assertThat(options.maxTokens()).isEqualTo(1200L);
    }
}
