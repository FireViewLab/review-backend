package com.example.fireview.domain.dataserver.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 저장 상품과 최신 분석의 목록용 요약. 리뷰 원본은 포함하지 않는다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DataServerCatalogPage(
        List<Entry> items,
        @JsonProperty("next_cursor") String nextCursor) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(DataServerProduct product, Analysis analysis) {}

    /**
     * Data 서버는 분석을 v4.0 하나로 돌리고 v0.5·v3.1 평균({@code avg_rti}·{@code avg_policy_index_v3})을
     * 더는 보내지 않는다(#273).
     *
     * @param scoredReviewCount v4 결과가 있는 리뷰 수. {@code v4_level_counts} 의 합과 같다
     * @param contractVersion   결과를 받은 AI 계약 {@code v0.5}/{@code v2}/{@code v4}. 분석 job 이 없으면 null
     * @param avgPolicyIndexV4  v4.0 정책 지수 평균. 점수 있는 리뷰가 없으면 null
     * @param v4LevelCounts     v4.0 등급별 리뷰 수. 셋 다 0 이면 null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Analysis(
            String status,
            @JsonProperty("job_id") Long jobId,
            @JsonProperty("scored_review_count") Integer scoredReviewCount,
            @JsonProperty("review_count") Integer reviewCount,
            @JsonProperty("source_review_count") Integer sourceReviewCount,
            Boolean sampled,
            @JsonProperty("model_version") String modelVersion,
            @JsonProperty("policy_version") String policyVersion,
            @JsonProperty("contract_version") String contractVersion,
            @JsonProperty("avg_policy_index_v4") Double avgPolicyIndexV4,
            @JsonProperty("v4_level_counts") LevelCounts v4LevelCounts) {}

    /** 등급별 리뷰 수. Data 서버는 키를 대문자({@code SAFE}/{@code WARN}/{@code DANGER})로 보낸다 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LevelCounts(
            @JsonProperty("SAFE") @JsonAlias("safe") Integer safe,
            @JsonProperty("WARN") @JsonAlias("warn") Integer warn,
            @JsonProperty("DANGER") @JsonAlias("danger") Integer danger) {}
}
