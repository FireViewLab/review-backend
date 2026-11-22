package com.example.fireview.domain.chat.dto;

import com.example.fireview.domain.chat.dto.response.ChatResponse;
import com.example.fireview.domain.chat.service.ChatRecommendation;
import com.example.fireview.domain.chat.service.ChatService;
import com.example.fireview.domain.user.entity.PlanTier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatResponseJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final ChatService.QuotaStatus quota = new ChatService.QuotaStatus(
            PlanTier.FREE, 5, 1, 4, false, Instant.parse("2026-10-07T15:00:00Z"));

    private JsonNode toJson(ChatService.ChatResult result) throws Exception {
        return objectMapper.readTree(objectMapper.writeValueAsString(ChatResponse.from(result)));
    }

    @Test
    void 추천이_없어도_recommendations_는_빈_배열이다() throws Exception {
        JsonNode json = toJson(new ChatService.ChatResult(10L, "차단 안내", true, "OFF_TOPIC", 0, quota, null));

        assertThat(json.get("recommendations").isArray()).isTrue();
        assertThat(json.get("recommendations")).isEmpty();
    }

    @Test
    void 기존_필드는_그대로_두고_recommendations_를_마지막에_붙인다() throws Exception {
        ChatRecommendation recommendation = new ChatRecommendation(
                "kurly-1001872496", "kurly", "1001872496", "샘플 상품", 29900L,
                "https://img.example/a.jpg", 56, null);

        JsonNode json = toJson(new ChatService.ChatResult(
                10L, "아래에 보여 드릴게요.", false, null, 1850, quota, List.of(recommendation)));

        assertThat(json.fieldNames()).toIterable().containsExactly(
                "sessionId", "answer", "blocked", "blockReason", "usedTokens", "quota", "recommendations",
                "scheduleProposal");
        JsonNode card = json.get("recommendations").get(0);
        assertThat(card.fieldNames()).toIterable().containsExactly(
                "externalId", "platform", "productId", "name", "price", "thumbnailUrl", "reviewCount", "rating");
        assertThat(card.get("externalId").asText()).isEqualTo("kurly-1001872496");
        assertThat(card.get("price").asLong()).isEqualTo(29900L);
        assertThat(card.get("reviewCount").asInt()).isEqualTo(56);
        // 모르는 평점은 0.0 이 아니라 null 로 나간다
        assertThat(card.get("rating").isNull()).isTrue();
    }
    @Test
    void 예약_제안이_없으면_scheduleProposal_은_null이다() throws Exception {
        JsonNode json = toJson(new ChatService.ChatResult(10L, "답변", false, null, 100, quota, List.of()));

        assertThat(json.has("scheduleProposal")).isTrue();
        assertThat(json.get("scheduleProposal").isNull()).isTrue();
    }

    @Test
    void 예약_제안이_있으면_확인_버튼에_쓸_값을_싣는다() throws Exception {
        JsonNode json = toJson(new ChatService.ChatResult(10L, "아래 버튼을 눌러 주세요.", false, null, 100, quota,
                List.of(), new com.example.fireview.domain.cartschedule.dto.ScheduleProposal(
                        55L, "매주 월요일 오전 9시 · 1개", "텀블러")));

        JsonNode proposal = json.get("scheduleProposal");
        assertThat(proposal.get("scheduleId").asLong()).isEqualTo(55L);
        assertThat(proposal.get("summary").asText()).isEqualTo("매주 월요일 오전 9시 · 1개");
        assertThat(proposal.get("productName").asText()).isEqualTo("텀블러");
    }
}
