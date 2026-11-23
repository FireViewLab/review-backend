package com.example.fireview.domain.dataserver.dto.response;

import com.example.fireview.domain.product.dto.ReviewLevelCounts;

import java.util.List;

/**
 * Data 서버 기반 상품 상세 응답.
 *
 * <p>기존 {@code /api/products/{id}} 와 다른 점:
 * <ul>
 *   <li>상품의 주인이 Data 서버다. Spring 은 조합만 한다</li>
 *   <li>{@code collectionStatus} 가 있다. 데이터가 없거나 오래된 상태를 숨기지 않는다</li>
 *   <li>{@code analysisStatus} 가 있다. 분석 진행 상태는 늘 여기서 본다</li>
 *   <li>{@code analysis} 는 <b>분석 결과가 있을 때만</b> 객체다. 점수는 리뷰마다 {@code reviews.items[]} 에 붙는다.
 *       상품 단위 평균 RTI·등급은 아직 없다</li>
 * </ul>
 *
 * @param collectionStatus 수집 신선도. {@code QUEUED} 면 {@code product} 가 null 이다
 * @param springProductId  찜·장바구니에 쓸 Spring 쪽 상품 번호. **아직 없으면 null**
 * @param product          상품 정보. {@code QUEUED}·{@code UNAVAILABLE} 이면 null
 * @param reviews          리뷰 한 페이지
 * @param job              수집 job. 진행 중일 때만 들어온다
 * @param analysisStatus   신뢰도 분석 상태. null 이 아니다 — 모르면 {@code UNAVAILABLE}
 * @param analysis         분석 결과 정보. {@link #hasAnalysisResult} 일 때만 객체, 그 밖에는 null
 */
public record DataProductResponse(
        CollectionStatus collectionStatus,
        Long springProductId,
        DataProductDetail product,
        ReviewPage reviews,
        CollectionJobStatus job,
        AnalysisStatus analysisStatus,
        ProductAnalysis analysis
) {

    /**
     * {@code analysis} 를 객체로 내릴 상태인지.
     *
     * <p><b>{@code analysis != null} 은 "분석 결과 있음"이라는 기존 뜻을 지킨다.</b>
     * 배포된 프론트는 {@code analysis != null} 로 결과 유무를 판단하고, 아니면 "분석 대기"
     * 안내를 그린다. 상태와 상관없이 객체를 내리면 미분석 상품에서도 안내가 사라진다.
     * 진행 상태는 {@code analysisStatus} 로 따로 준다.
     *
     * <p>{@code STALE} 은 넣지 않는다. Data 서버(analysis_repository.status)는
     * 상태가 {@code done} 일 때만 {@code results} 를 채우고, stale 이면 빈 배열을 보낸다.
     * 보여줄 결과가 없으므로 결과 없음과 같다.
     */
    public static boolean hasAnalysisResult(AnalysisStatus status) {
        return status == AnalysisStatus.DONE;
    }

    /**
     * 분석 결과가 있는 상품의 분석 정보. 상태는 늘 {@code DONE} 이다.
     *
     * <p>상품 평균 RTI·등급은 넣지 않는다. Data 서버가 주는 결과는 지금 리뷰 페이지 것뿐이라
     * Spring 이 평균을 내면 20건 표본 평균이 된다. Data 서버가 전체로 계산해 줄 때 붙인다.
     *
     * <p><b>리뷰 수가 셋이다.</b> 서로 다른 것을 센다.
     * <ul>
     *   <li>{@code analysis.reviewCount} — 분석에 실제로 넣은 리뷰 수(표본 수)</li>
     *   <li>{@code analysis.sourceReviewCount} — 표본을 고를 때 Data 서버가 가진 원본 리뷰 수</li>
     *   <li>{@code product.reviewCount} — 쇼핑몰이 표시하는 전체 리뷰 수</li>
     * </ul>
     * {@code sampled} 가 true 면 표본에 들지 않은 리뷰는 DONE 이어도 {@code rti} 가 null 이다.
     *
     * @param status            분석 상태. 지금은 {@code DONE} 뿐이다
     * @param modelVersion      분석한 AI 모델 버전
     * @param policyVersion     등급 정책 버전 (예: {@code rti-v0})
     * @param reviewCount       분석 입력(표본) 리뷰 수. 상품 리뷰 수와 다를 수 있다. 모르면 null
     * @param sampled           표본으로 일부만 분석했으면 true, 전수 분석이면 false.
     *                          구버전 Data 서버라 모르면 null
     * @param sourceReviewCount Data 서버가 가진 원본 리뷰 수. 구버전 Data 서버라 모르면 null
     * @param contractVersion   결과를 받은 AI 계약 {@code v0.5}/{@code v2}/{@code v4}. 리뷰 결과는 계약과
     *                          상관없이 {@code *V4} 필드로만 온다. 구버전 Data 서버라 모르면 null
     * @param avgPolicyIndexV3  v3.1 정책 지수 평균. Data 서버가 v3.1 을 보내지 않아 늘 null 이다(#273)
     * @param avgPolicyIndexV4  상품 전체의 v4.0 정책 지수 평균. 없으면 null
     * @param v4LevelCounts     v4.0 등급별 리뷰 수 {@code {SAFE, WARN, DANGER}}. 없으면 null
     * @param modelVersionV4    v4.0 모델 버전 (예: {@code ptext-koelectra-v4.0}). 이 페이지 리뷰 결과에서 읽는다.
     *                          {@code modelVersion} 은 분석 job 의 버전이라 v4 로 다시 채운 상품은 v3.1 값이 남는다(#276).
     *                          v4 결과가 있는 리뷰가 페이지에 없으면 null
     * @param policyVersionV4   v4.0 규칙 버전 (예: {@code text-first-v1}). 읽는 곳과 null 조건은 위와 같다
     */
    public record ProductAnalysis(
            AnalysisStatus status,
            String modelVersion,
            String policyVersion,
            Integer reviewCount,
            Boolean sampled,
            Integer sourceReviewCount,
            String contractVersion,
            Double avgPolicyIndexV3,
            Double avgPolicyIndexV4,
            ReviewLevelCounts v4LevelCounts,
            String modelVersionV4,
            String policyVersionV4
    ) {}

    /**
     * @param externalId {@code "{platform}-{productId}"}. 챗봇·알림처럼 식별자를 한 칸에
     *                   담아야 하는 자리에서 쓴다
     */
    public record DataProductDetail(
            String platform,
            String productId,
            String externalId,
            String name,
            String url,
            String brand,
            String manufacturer,
            String seller,
            Integer price,
            String thumbnailUrl,
            String category,
            Integer reviewCount,
            Double rating,
            String lastCollectedAt
    ) {}

    /**
     * @param nextCursor 다음 페이지 요청에 그대로 넣는다. null 이면 마지막 페이지
     */
    public record ReviewPage(List<DataReview> items, String nextCursor) {}

    /**
     * @param rating  5점 만점 환산. 소수점이 올 수 있다 (예: 4.5)
     * @param rti     이 리뷰의 RTI (0~100). v2 계약이면 정책 지수. 분석 결과가 없거나 계산 불가면 null.
     *                0 으로 채우지 않는다
     * @param level   이 리뷰의 등급 {@code safe}/{@code warn}/{@code danger}. v2 의 대문자 등급도 소문자로 맞춘다.
     *                결과가 없거나 계산 불가면 null
     * @param reasons 판단 근거. v2 계약이면 한국어 설명 문장. 결과가 없으면 빈 배열
     *
     * <p><b>{@code *V4} 는 v4.0 텍스트 모델 결과다(#255).</b> 화면 등급·지수는 이 값을 쓴다.
     * v4 가 아직 채워지지 않은 리뷰는 v4 필드가 전부 null 이다.
     *
     * <p><b>{@code rti} 부터 {@code reasonSource} 까지는 늘 null(목록은 빈 배열)이다.</b> Data 서버가
     * 분석을 v4.0 하나로 바꾸며 v0.5·v3.1 결과를 보내지 않는다(#273). 배포된 프론트가 키를 읽고 있어
     * 자리만 남겼다. 프론트가 키를 지우면 뺀다.
     *
     * @param textScore         본문 신호 점수(0~100). 계산 불가면 null
     * @param behaviorScore     행동 신호 점수(0~100). 계산 불가면 null
     * @param networkScore      관계 신호 점수(0~100). 계산 불가면 null.
     *                          화면의 리뷰 상세 "텍스트·행동·네트워크 점수"가 이 이름을 읽는다.
     *                          예전에는 내려주지 않아 세 점수가 늘 "계산 불가"였다
     * @param policyIndexV3     v3.1 정책 지수(0~100). v2 계약이면 {@code rti} 와 같은 값이다. 없으면 null
     * @param evidenceLevelV3   v3.1 등급 {@code SAFE}/{@code WARN}/{@code DANGER}. 대문자. 없으면 null.
     *                          v2 계약 화면은 리뷰마다 이 두 이름을 읽는다. 예전에는 {@code rti}·{@code level} 로만
     *                          옮겨 보내 화면이 점수를 찾지 못하고 모든 리뷰를 "계산 불가"로 셌다
     * @param analysisSummaryKo v3.1 한 문단 설명. 없으면 null
     * @param rulesReasonsKo    v3.1 상세 근거 문장. 없으면 빈 배열
     * @param evidenceReasonsV3 v3.1 근거 코드. 없으면 빈 배열
     * @param reasonSource      설명을 만든 곳 {@code python}/{@code groq}/{@code rules}. 없으면 null.
     *                          v2 화면은 {@code reasons} 를 읽지 않고 이 네 이름에서 설명을 읽는다.
     *                          예전에는 설명을 {@code reasons} 로만 보내 "요약할 근거가 없다"고 보였다
     * @param levelV4           v4.0 등급 {@code SAFE}/{@code WARN}/{@code DANGER}. 대문자. 없으면 null
     * @param policyIndexV4     v4.0 정책 지수(0~100). SAFE 70~100, WARN 40~69, DANGER 0~39. 없으면 null
     * @param textScoreV4       v4.0 본문 점수(0~100). 높을수록 신뢰. 없으면 null
     * @param textProbabilityV4 v4.0 의심 확률(0~1). 없으면 null
     * @param modelVersionV4    v4.0 모델 버전. 없으면 null
     * @param policyVersionV4   v4.0 규칙 버전. 없으면 null
     */
    public record DataReview(
            String reviewId,
            String content,
            Double rating,
            String author,
            String writtenAt,
            String option,
            List<String> images,
            Integer helpfulCount,
            Double rti,
            String level,
            List<String> reasons,
            Double textScore,
            Double behaviorScore,
            Double networkScore,
            Double policyIndexV3,
            String evidenceLevelV3,
            String analysisSummaryKo,
            List<String> rulesReasonsKo,
            List<String> evidenceReasonsV3,
            String reasonSource,
            String levelV4,
            Double policyIndexV4,
            Double textScoreV4,
            Double textProbabilityV4,
            String modelVersionV4,
            String policyVersionV4
    ) {}

    /**
     * @param status        pending / running / succeeded / partial / failed
     * @param productStatus 상품 수집 결과
     * @param reviewStatus  리뷰 수집 결과. 상품만 성공한 부분 실패를 구분하려고 따로 둔다
     */
    public record CollectionJobStatus(
            Long id,
            String status,
            String productStatus,
            String reviewStatus,
            String lastError
    ) {}
}
