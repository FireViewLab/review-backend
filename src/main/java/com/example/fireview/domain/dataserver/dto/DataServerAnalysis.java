package com.example.fireview.domain.dataserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 상품 응답에 붙어 오는 신뢰도 분석 상태 ({@code analysis}).
 *
 * <p>Data 서버가 AI 서버를 불러 리뷰별 v4.0 결과를 저장해 두고, 상품 조회 때 함께 내려준다.
 *
 * <table>
 *   <tr><th>status</th><th>의미</th></tr>
 *   <tr><td>{@code disabled}</td><td>Data 서버에서 분석 기능이 꺼져 있다</td></tr>
 *   <tr><td>{@code not_analyzed}</td><td>분석 job 이 아직 없다</td></tr>
 *   <tr><td>{@code queued} / {@code running}</td><td>분석 중</td></tr>
 *   <tr><td>{@code done}</td><td>완료. {@code results} 가 채워진다</td></tr>
 *   <tr><td>{@code failed}</td><td>분석 실패</td></tr>
 *   <tr><td>{@code stale}</td><td>마지막 분석 뒤 리뷰나 모델이 바뀌었다. 결과는 오지 않는다</td></tr>
 * </table>
 *
 * <p><b>{@code results} 는 이번 응답의 리뷰 페이지에 있는 리뷰만 담긴다.</b>
 * 상품 전체 결과가 아니므로 이걸로 상품 평균을 내면 한 페이지 표본 평균이 된다.
 *
 * @param reviewCount       분석 입력(표본) 리뷰 수 = {@code job.input_review_count}. job 이 없으면 0.
 *                          Data #80 부터 리뷰가 많으면 일부만 골라 분석하므로 원본 리뷰 수보다 작을 수 있다
 * @param sampled           표본으로 일부만 분석했는지. 전수 분석이면 false.
 *                          job 이 없거나 이 필드를 모르는 구버전 Data 서버면 null
 * @param sourceReviewCount 표본을 고를 때 Data 서버가 가진 원본 리뷰 수. 쇼핑몰이 표시하는
 *                          전체 리뷰 수와는 다를 수 있다. job 이 없거나 구버전이면 null
 * @param contractVersion   결과를 받은 AI 계약 {@code v0.5}/{@code v2}/{@code v4}. 결과가 없거나 구버전이면 null.
 *                          계약과 상관없이 리뷰 결과에는 v4 필드만 온다(#273)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DataServerAnalysis(
        String status,
        Job job,
        @JsonProperty("input_hash") String inputHash,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("policy_version") String policyVersion,
        @JsonProperty("review_count") Integer reviewCount,
        Boolean sampled,
        @JsonProperty("source_review_count") Integer sourceReviewCount,
        @JsonProperty("contract_version") String contractVersion,
        List<DataServerReviewAnalysis> results
) {

    public DataServerAnalysis {
        results = results == null ? List.of() : results;
    }

    /** 분석 job. 상품 조회 응답의 수집 job 과는 다른 것이다 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Job(
            Long id,
            String status,
            @JsonProperty("attempt_count") Integer attemptCount,
            @JsonProperty("ai_job_id") String aiJobId,
            @JsonProperty("input_review_count") Integer inputReviewCount,
            @JsonProperty("last_error") String lastError,
            @JsonProperty("completed_at") String completedAt
    ) {}

    /** 리뷰 id 로 결과를 찾을 수 있게 묶는다. 같은 id 가 두 번 오면 앞의 것을 쓴다 */
    public Map<String, DataServerReviewAnalysis> resultsByReviewId() {
        return results.stream()
                .filter(r -> r != null && r.reviewId() != null)
                .collect(Collectors.toMap(DataServerReviewAnalysis::reviewId, Function.identity(), (a, b) -> a));
    }
}
