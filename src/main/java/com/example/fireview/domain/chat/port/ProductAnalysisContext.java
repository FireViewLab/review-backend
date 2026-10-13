package com.example.fireview.domain.chat.port;

import java.util.List;

/**
 * 챗봇 프롬프트에 실을 상품 분석 컨텍스트 (C안 - 요약 중심).
 *
 * 리뷰 원문을 대량으로 넣지 않는다. Data 서버가 이미 계산한 요약 지표를 싣고,
 * 근거로 보여줄 대표 리뷰만 소수 포함한다. 리뷰 50건을 통째로 넣는 방식 대비
 * 턴당 토큰이 1/3 수준으로 줄어든다.
 *
 * @param productId     상품 외부 식별자
 * @param productName   상품명
 * @param price         가격 (원). 모르면 null
 * @param category      카테고리 경로 (예: "패션 > 상의 > 니트")
 * @param averageRti    상품 평균 RTI (0~100). 모르면 null
 * @param trustGrade    신뢰 등급 (안전/주의/위험). 모르면 null
 * @param totalReviews  분석에 넣은 리뷰 건수. 분석 전이면 알고 있는 리뷰 수로 대신한다
 * @param pros          자주 언급된 장점
 * @param cons          자주 언급된 단점
 * @param trustSignals  주요 판단 신호 (예: "짧은 호평이 같은 날짜에 몰림")
 * @param sampleReviews 근거용 대표 리뷰 (3~5건 권장)
 * @param mallReviewCount 쇼핑몰이 표시하는 전체 리뷰 수. 모르면 null.
 *                        분석 리뷰 수와 다를 수 있어 따로 싣는다
 * @param analysisSampled 수집된 리뷰 중 일부만 골라 분석했으면 true, 전부 분석했으면 false. 모르면 null
 * @param collectedReviewCount 분석할 때 Re:View 가 수집해 둔 리뷰 수. 모르면 null
 */
public record ProductAnalysisContext(
        String productId,
        String productName,
        Integer price,
        String category,
        Double averageRti,
        String trustGrade,
        int totalReviews,
        List<String> pros,
        List<String> cons,
        List<String> trustSignals,
        List<SampleReview> sampleReviews,
        Integer mallReviewCount,
        Boolean analysisSampled,
        Integer collectedReviewCount
) {

    /** 쇼핑몰 전체 리뷰 수를 모를 때 */
    public ProductAnalysisContext(String productId, String productName, Integer price, String category,
                                  Double averageRti, String trustGrade, int totalReviews,
                                  List<String> pros, List<String> cons, List<String> trustSignals,
                                  List<SampleReview> sampleReviews) {
        this(productId, productName, price, category, averageRti, trustGrade, totalReviews,
                pros, cons, trustSignals, sampleReviews, null, null, null);
    }

    /**
     * 대표 리뷰 한 건.
     *
     * @param content 리뷰 본문 (길면 잘라서 전달)
     * @param rating  평점
     * @param rti     이 리뷰의 RTI
     * @param grade   이 리뷰의 등급
     * @param reasons 이 리뷰의 판단 근거 (화면과 같은 한국어 라벨)
     */
    public record SampleReview(String content, Integer rating, Double rti, String grade, List<String> reasons) {

        public SampleReview {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public SampleReview(String content, Integer rating, Double rti, String grade) {
            this(content, rating, rti, grade, List.of());
        }
    }

    /** 표본이 적어 등급을 단정하기 어려운 구간. 계획서의 '판단 보류' 정책과 맞춘다. */
    public static final int MIN_RELIABLE_REVIEWS = 30;

    public boolean isSampleTooSmall() {
        return totalReviews < MIN_RELIABLE_REVIEWS;
    }

    /** 응답 검증에 쓸 수 있는, 이 컨텍스트가 실제로 담고 있는 RTI 값들 */
    public List<Double> knownRtiValues() {
        return java.util.stream.Stream.concat(
                        averageRti != null ? java.util.stream.Stream.of(averageRti) : java.util.stream.Stream.empty(),
                        sampleReviews == null ? java.util.stream.Stream.empty()
                                : sampleReviews.stream().map(SampleReview::rti).filter(java.util.Objects::nonNull))
                .toList();
    }
}
