package com.example.fireview.domain.product.dto;

import com.example.fireview.domain.dataserver.dto.response.AnalysisStatus;
import com.example.fireview.domain.product.client.NaverShoppingItem;
import com.example.fireview.domain.product.entity.Category;
import com.example.fireview.domain.product.entity.MajorCategory;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.review.entity.TrustGrade;

import java.util.List;

/**
 * 상품 응답 DTO
 *
 * [표시 정책]
 * - 상품 단위에는 RTI 수치(avgRti)와 등급(rtiGrade, rtiLevel)을 모두 표시합니다.
 * - rtiLevel: AI 서버와 동일한 "safe" | "warn" | "danger" 형식
 * - platforms: 멀티 플랫폼 구매 링크 + 가격 (교수님 반응 좋았던 기능)
 * - lowestPrice / lowestPlatform: 최저가 정보
 * - majorCategory / majorCategoryDisplayName: 대분류
 * - categoryDisplayName: 중분류 표시명
 * - subCategory: 소분류 (Naver category3, 예: "스마트폰")
 */
public record ProductResponse(
        Long id,
        String naverProductId,             // 네이버 상품 ID (AI 분석 요청 시 사용)
        String name,
        String imageUrl,
        Long price,
        MajorCategory majorCategory,           // 대분류
        String majorCategoryDisplayName,       // 대분류 표시명
        Category category,                     // 중분류
        String categoryDisplayName,            // 중분류 표시명
        String subCategory,                    // 소분류 (Naver category3)
        String platform,
        Double avgRti,               // RTI 수치 (상품 단위에서만 노출)
        TrustGrade rtiGrade,         // SAFE | SUSPICIOUS | DANGER
        String rtiLevel,             // "safe" | "warn" | "danger" (AI 서버 형식 통일)
        String rtiColor,
        Integer reviewCount,
        Double avgRating,
        List<PlatformLinkDto> platforms,   // 멀티 플랫폼 구매 링크
        Long lowestPrice,                  // 최저가 (원)
        String lowestPlatform,             // 최저가 플랫폼 이름
        String productUrl,                 // 네이버 상품 페이지 URL (AI 분석 요청 시 사용)

        // ── Data 서버 상품일 때만 채워진다. 더미·네이버 검색 결과는 null ──
        String dataPlatform,               // 수집기 이름 (kurly, oliveyoung ...). 소문자
        String dataProductId,              // 쇼핑몰 원본 상품 ID
        String externalId,                 // "{dataPlatform}-{dataProductId}". 챗봇 productId 에 그대로 넣는다
        AnalysisStatus analysisStatus,     // Data에서 마지막으로 확인한 분석 상태
        Boolean analysisSampled,
        Integer analysisReviewCount,
        Integer analysisSourceReviewCount,

        // ── v4.0 텍스트 모델 결과(#255). 화면 지수·등급 분포는 이 값을 쓴다 ──
        // v4 가 아직 없으면 null. avgRti(v3.1)로 대신 채우지 않는다 — 프론트가 "분석 중"으로 그린다
        Double avgPolicyIndexV4,           // 상품 평균 지수(0~100)
        ReviewLevelCounts v4LevelCounts    // {SAFE, WARN, DANGER} 리뷰 수
) {
    /**
     * 네이버 쇼핑 검색 결과 아이템 → ProductResponse 변환.
     * RTI 데이터가 없으므로 기본값(50.0 / SUSPICIOUS)으로 채운다.
     */
    public static ProductResponse fromNaverItem(NaverShoppingItem item) {
        // 네이버 title에는 <b>태그가 포함되므로 제거
        String name = item.title().replaceAll("<[^>]*>", "").trim();

        long price = 0L;
        try { price = Long.parseLong(item.lprice()); } catch (NumberFormatException ignored) {}

        long productId = 0L;
        try { productId = Long.parseLong(item.productId()); } catch (NumberFormatException ignored) {}

        Category category = CategoryMapper.fromNaver(item.category1(), item.category2());
        TrustGrade grade = TrustGrade.SUSPICIOUS; // 아직 RTI 분석 전

        return new ProductResponse(
                productId,
                item.productId(),               // 네이버 상품 ID (String 원본 보존)
                name,
                item.image(),
                price,
                category == null ? null : category.getMajor(),
                category == null ? null : category.getMajor().getDisplayName(),
                category,
                category == null ? null : category.getDisplayName(),
                item.category3(),               // 소분류
                item.mallName().isBlank() ? "NAVER" : item.mallName(),
                50.0,                           // RTI 미분석 기본값
                grade,
                grade.toLevel(),
                grade.getColor(),
                0,                              // 리뷰 수 미집계
                0.0,                            // 평점 미집계
                List.of(),
                price,
                item.mallName().isBlank() ? "NAVER" : item.mallName(),
                item.link(),                    // AI 분석 요청 시 productUrl로 사용
                null, null, null,
                null, null, null, null,
                null, null
        );
    }

    /**
     * DB 상품 → 응답 변환.
     *
     * <p><b>분석 전 상품을 허용한다.</b> Data 서버에서 온 상품은 신뢰도 분석이 없어
     * {@code avgRti} 가 null 이고, 카테고리 문자열을 enum 으로 옮길 수 없어
     * {@code category} 도 null 일 수 있다. 예전처럼 기본값을 끼워 넣지 않는다 —
     * 없는 분석 결과를 숫자로 보여주면 사용자가 그걸 실제 판정으로 읽는다.
     * 관련 필드는 그대로 null 로 내려가고, 프론트가 "분석 전"으로 그린다.
     */
    public static ProductResponse from(Product product) {
        // avgRti 가 null 이면 등급도 없다. Double 언박싱 NPE 를 막는 것이기도 하다
        TrustGrade grade = product.getAvgRti() == null
                ? null
                : TrustGrade.fromScore(product.getAvgRti());
        List<PlatformLinkDto> platformDtos = product.getPlatformLinks().stream()
                .map(PlatformLinkDto::from)
                .toList();
        Category category = product.getCategory();
        return new ProductResponse(
                product.getId(),
                product.getNaverProductId(),    // DB 상품의 네이버 상품 ID
                product.getName(),
                product.getImageUrl(),
                product.getPrice(),
                category == null ? null : category.getMajor(),
                category == null ? null : category.getMajor().getDisplayName(),
                category,
                category == null ? null : category.getDisplayName(),
                product.getSubCategory(),
                product.getPlatform(),
                product.getAvgRti(),
                grade,
                grade == null ? null : grade.toLevel(),
                grade == null ? null : grade.getColor(),
                product.getReviewCount(),
                avgRating(product),
                platformDtos,
                product.getLowestPrice(),
                product.getLowestPlatform(),
                null,   // DB 상품은 platformLinks에 URL이 있으므로 별도 productUrl 불필요
                product.getDataPlatform(),
                product.getDataProductId(),
                product.dataServerExternalId(),
                product.getAnalysisStatus(),
                product.getAnalysisSampled(),
                product.getAnalysisReviewCount(),
                product.getAnalysisSourceReviewCount(),
                product.getAvgPolicyIndexV4(),
                ReviewLevelCounts.of(product.getV4SafeCount(), product.getV4WarnCount(),
                        product.getV4DangerCount())
        );
    }

    /**
     * Data 서버 상품의 평점이 0 이하면 null 로 내린다.
     *
     * <p>번호표를 만들 때 평점이 비어 있으면 0.0 이 채워진다(Product.onCreate). Data 서버가 평점을
     * 주지 않은 상품(컬리 등)은 그 0.0 이 그대로 남아 목록에 "0점"으로 보였다. 그 0 은 "모름"이다.
     * 평점은 1~5 점이라 실제 0 점은 없다. 상세(DataProductService.cachedRating)와 같은 기준이다.
     *
     * <p>Data 서버 주소가 없는 기존 상품은 손대지 않는다.
     */
    private static Double avgRating(Product product) {
        Double rating = product.getAvgRating();
        if (product.hasDataServerAddress() && (rating == null || rating <= 0)) {
            return null;
        }
        return rating;
    }
}
