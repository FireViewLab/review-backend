package com.example.fireview.domain.product.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 상품의 등급별 리뷰 수. Data 서버의 {@code v4_level_counts} 와 같은 대문자 키로 내린다(#255).
 */
public record ReviewLevelCounts(
        @JsonProperty("SAFE") Integer safe,
        @JsonProperty("WARN") Integer warn,
        @JsonProperty("DANGER") Integer danger
) {
    /** 셋 중 하나라도 없으면 null. 일부만 있는 분포는 내리지 않는다 */
    public static ReviewLevelCounts of(Integer safe, Integer warn, Integer danger) {
        if (safe == null || warn == null || danger == null) return null;
        return new ReviewLevelCounts(safe, warn, danger);
    }
}
