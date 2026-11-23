package com.example.fireview.domain.dataserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Locale;

/**
 * 리뷰 한 건의 신뢰도 분석 결과 ({@code analysis.results[]}).
 *
 * <p>Data 서버는 분석을 v4.0 하나로 돌리고 공개 응답에 v4 필드만 싣는다(#273).
 * v0.5 의 {@code rti}·{@code level}·{@code reasons}, v3.1 의 {@code *_v3}·{@code *_ko} 는 오지 않는다.
 *
 * <p>모든 점수는 0~100 이고 <b>null 은 "계산 불가"</b>다. 음수가 들어오면 null 로 다룬다 —
 * -1 을 숫자로 두면 화면·챗봇이 "-1점"을 실제 점수처럼 보여준다.
 *
 * @param levelV4           v4.0 등급 {@code SAFE}/{@code WARN}/{@code DANGER}. 아직 채워지지 않았으면 null
 * @param policyIndexV4     v4.0 정책 지수(0~100). 등급 구간 안의 값이다. 없으면 null
 * @param textScoreV4       v4.0 본문 점수(0~100). 높을수록 신뢰. 없으면 null
 * @param textProbabilityV4 v4.0 의심 확률(0~1). 없거나 범위 밖이면 null
 * @param modelVersionV4    v4.0 모델 버전 (예: {@code ptext-koelectra-v4.0})
 * @param policyVersionV4   v4.0 규칙 버전 (예: {@code text-first-v1})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DataServerReviewAnalysis(
        @JsonProperty("review_id") String reviewId,
        @JsonProperty("level_v4") String levelV4,
        @JsonProperty("policy_index_v4") Double policyIndexV4,
        @JsonProperty("text_score_v4") Double textScoreV4,
        @JsonProperty("text_probability_v4") Double textProbabilityV4,
        @JsonProperty("model_version_v4") String modelVersionV4,
        @JsonProperty("policy_version_v4") String policyVersionV4
) {

    public DataServerReviewAnalysis {
        levelV4 = upperLevel(levelV4);
        policyIndexV4 = knownScore(policyIndexV4);
        textScoreV4 = knownScore(textScoreV4);
        textProbabilityV4 = knownProbability(textProbabilityV4);
    }

    /** v4 등급은 Data 서버가 대문자로 보낸다. 공백·대소문자만 맞추고 모르는 값은 버린다 */
    private static String upperLevel(String level) {
        if (level == null || level.isBlank()) return null;
        String upper = level.strip().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "SAFE", "WARN", "DANGER" -> upper;
            default -> null;
        };
    }

    private static Double knownScore(Double score) {
        return score == null || score < 0 ? null : score;
    }

    private static Double knownProbability(Double probability) {
        return probability == null || probability < 0 || probability > 1 ? null : probability;
    }
}
