package com.example.fireview.domain.dataserver.dto;

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
     * @param avgRti           v0.5 리뷰 RTI 평균. v2 상품은 null 이다
     * @param avgPolicyIndexV3 v2 정책 지수 평균. v0.5 상품은 null 이다(#250)
     * @param contractVersion  결과를 받은 AI 계약 {@code v0.5}/{@code v2}. 분석 job 이 없으면 null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Analysis(
            String status,
            @JsonProperty("job_id") Long jobId,
            @JsonProperty("avg_rti") Double avgRti,
            @JsonProperty("scored_review_count") Integer scoredReviewCount,
            @JsonProperty("review_count") Integer reviewCount,
            @JsonProperty("source_review_count") Integer sourceReviewCount,
            Boolean sampled,
            @JsonProperty("model_version") String modelVersion,
            @JsonProperty("policy_version") String policyVersion,
            @JsonProperty("avg_policy_index_v3") Double avgPolicyIndexV3,
            @JsonProperty("contract_version") String contractVersion) {

        /**
         * 상품 평균 점수. v2 정책 지수 평균이 있으면 그것을, 없으면 RTI 평균을 쓴다.
         * v2 상품의 avg_rti 는 null 이라 그대로 읽으면 분석된 상품이 평균 없는 상품으로 보인다.
         */
        public Double avgScore() {
            return avgPolicyIndexV3 != null ? avgPolicyIndexV3 : avgRti;
        }
    }
}
