package com.example.fireview.domain.dataserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Data 서버 상품 응답 (GET /api/v1/{platform}/products/{product_id} 의 product).
 *
 * <p>상품 정보만 담는다. 신뢰도 분석 결과는 같은 응답의 최상위 {@code analysis} 로 따로 온다
 * ({@link DataServerAnalysis}).
 *
 * @param descriptionText   상품 상세 설명 본문(plain text). 상세 응답에만 있고 검색·카탈로그에는 없다.
 *                          컬리·무신사 수집기만 채운다. 없으면 null
 * @param descriptionImages 상세 설명 이미지 URL. 없으면 빈 목록
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DataServerProduct(
        String platform,
        @JsonProperty("product_id") String productId,
        String name,
        String url,
        String brand,
        String manufacturer,
        String seller,
        Integer price,
        @JsonProperty("thumbnail_url") String thumbnailUrl,
        String category,
        @JsonProperty("review_count") Integer reviewCount,
        Double rating,
        @JsonProperty("last_collected_at") String lastCollectedAt,
        @JsonProperty("description_text") String descriptionText,
        @JsonProperty("description_images") List<String> descriptionImages
) {

    public DataServerProduct {
        descriptionImages = descriptionImages == null ? List.of() : descriptionImages.stream()
                .filter(image -> image != null && !image.isBlank())
                .toList();
    }

    /** 설명이 없는 응답(검색·카탈로그)용 */
    public DataServerProduct(String platform, String productId, String name, String url, String brand,
                             String manufacturer, String seller, Integer price, String thumbnailUrl,
                             String category, Integer reviewCount, Double rating, String lastCollectedAt) {
        this(platform, productId, name, url, brand, manufacturer, seller, price, thumbnailUrl, category,
                reviewCount, rating, lastCollectedAt, null, null);
    }
}
