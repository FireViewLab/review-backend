package com.example.fireview.domain.chat.dto.response;

import com.example.fireview.domain.cartschedule.dto.ScheduleProposal;
import com.example.fireview.domain.chat.service.ChatService;

import java.util.List;

/**
 * @param sessionId   대화 세션 ID. 다음 질문에 그대로 넣으면 대화가 이어진다
 * @param answer      챗봇 답변 (차단된 경우 안내 문구)
 * @param blocked     세이프가드에 걸렸는지
 * @param blockReason 차단 사유 코드 (INJECTION / OFF_TOPIC / UNGROUNDED_SCORE 등)
 * @param usedTokens  이번 턴에 소모한 토큰
 * @param quota       이 턴을 반영한 오늘 사용량. 남은 횟수 표시에 쓴다
 * @param recommendations 답변 아래에 보여줄 비슷한 상품. 없으면 빈 배열(null 이 아니다).
 *                        차단됐거나 사용자가 추천을 원하지 않은 턴은 항상 비어 있다
 * @param scheduleProposal 장바구니 예약 제안. 화면은 이걸 보고 확인 버튼을 그린다.
 *                         예약 요청이 아니었거나 만들 수 없었으면 null
 */
public record ChatResponse(
        Long sessionId,
        String answer,
        boolean blocked,
        String blockReason,
        int usedTokens,
        ChatQuotaResponse quota,
        List<ChatRecommendationResponse> recommendations,
        ScheduleProposal scheduleProposal
) {
    public ChatResponse {
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }

    public static ChatResponse from(ChatService.ChatResult result) {
        return new ChatResponse(result.sessionId(), result.answer(),
                result.blocked(), result.blockReason(), result.usedTokens(),
                ChatQuotaResponse.from(result.quota()),
                result.recommendations().stream().map(ChatRecommendationResponse::from).toList(),
                result.scheduleProposal());
    }
}
