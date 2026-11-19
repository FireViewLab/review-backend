package com.example.fireview.domain.dataserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Locale;

/**
 * 리뷰 한 건의 신뢰도 분석 결과 ({@code analysis.results[]}).
 *
 * <p>모든 점수는 0~100 이고 <b>null 은 "계산 불가"</b>다. AI 는 계산 불가를 -1 로 보내지만
 * Data 서버가 저장하면서 null 로 바꾼다. 그래도 음수가 들어오면 null 로 다룬다 —
 * -1 을 숫자로 두면 화면·챗봇이 "RTI -1" 을 실제 점수처럼 보여준다.
 *
 * <p>AI v3.1 결과(계약 {@code v2})는 {@code rti}·{@code level}·{@code reasons} 를 비워 두고
 * {@code *_v3}·{@code *_ko} 필드로 온다(#250). {@link #forContract(String)} 가 이를 기존 세 필드로 옮긴다.
 *
 * @param level  {@code safe}(≥70) / {@code warn}(≥40) / {@code danger}(&lt;40). RTI 가 계산 불가면 null
 * @param reasons 판단 근거. 코드({@code TEXT_*} 등)와 한국어 문장이 섞여 올 수 있다
 * @param policyIndexV3     v2 정책 지수(0~100). RTI 나 허위 확률이 아니라 정책상 상대 지수다. 계산 불가면 null
 * @param evidenceLevelV3   v2 등급 {@code SAFE}/{@code WARN}/{@code DANGER}. 대문자로 온다
 * @param analysisSummaryKo v2 한 문단 설명. 없을 수 있다
 * @param rulesReasonsKo    v2 상세 근거 문장. 한 문단 설명이 없을 때 대신 보여 준다
 * @param evidenceReasonsV3 v2 근거 코드
 * @param reasonSource      설명을 만든 곳 {@code python}/{@code groq}/{@code rules}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DataServerReviewAnalysis(
        @JsonProperty("review_id") String reviewId,
        Double rti,
        String level,
        @JsonProperty("text_score") Double textScore,
        @JsonProperty("behavior_score") Double behaviorScore,
        @JsonProperty("network_score") Double networkScore,
        List<String> reasons,
        @JsonProperty("policy_index_v3") Double policyIndexV3,
        @JsonProperty("evidence_level_v3") String evidenceLevelV3,
        @JsonProperty("analysis_summary_ko") String analysisSummaryKo,
        @JsonProperty("rules_reasons_ko") List<String> rulesReasonsKo,
        @JsonProperty("evidence_reasons_v3") List<String> evidenceReasonsV3,
        @JsonProperty("reason_source") String reasonSource
) {

    public static final String CONTRACT_V2 = "v2";

    public DataServerReviewAnalysis {
        rti = knownScore(rti);
        textScore = knownScore(textScore);
        behaviorScore = knownScore(behaviorScore);
        networkScore = knownScore(networkScore);
        reasons = reasons == null ? List.of() : reasons;
        policyIndexV3 = knownScore(policyIndexV3);
        rulesReasonsKo = rulesReasonsKo == null ? List.of() : rulesReasonsKo;
        evidenceReasonsV3 = evidenceReasonsV3 == null ? List.of() : evidenceReasonsV3;
    }

    /** v0.5 결과용. v2 필드는 비워 둔다 */
    public static DataServerReviewAnalysis v05(String reviewId, Double rti, String level, Double textScore,
                                               Double behaviorScore, Double networkScore, List<String> reasons) {
        return new DataServerReviewAnalysis(reviewId, rti, level, textScore, behaviorScore, networkScore, reasons,
                null, null, null, null, null, null);
    }

    /**
     * 계약이 {@code v2} 면 정책 지수·등급·설명을 {@code rti}·{@code level}·{@code reasons} 자리로 옮긴다.
     * 화면·챗봇·동기화가 지금처럼 세 필드만 읽어도 v2 상품이 비어 보이지 않게 한다.
     * 등급은 소문자로 바꾼다. 구간(70·40)은 두 계약이 같다.
     * 설명은 한 문단 설명을 먼저 쓰고, 없으면 상세 근거 문장을 쓴다.
     * v0.5 이거나 계약을 모르면 그대로 둔다.
     */
    public DataServerReviewAnalysis forContract(String contractVersion) {
        if (!CONTRACT_V2.equalsIgnoreCase(contractVersion)) return this;
        List<String> displayReasons = analysisSummaryKo != null && !analysisSummaryKo.isBlank()
                ? List.of(analysisSummaryKo.strip())
                : rulesReasonsKo;
        return new DataServerReviewAnalysis(reviewId, policyIndexV3, lowerLevel(evidenceLevelV3),
                textScore, behaviorScore, networkScore, displayReasons,
                policyIndexV3, evidenceLevelV3, analysisSummaryKo, rulesReasonsKo, evidenceReasonsV3, reasonSource);
    }

    private static String lowerLevel(String level) {
        return level == null || level.isBlank() ? null : level.strip().toLowerCase(Locale.ROOT);
    }

    private static Double knownScore(Double score) {
        return score == null || score < 0 ? null : score;
    }
}
