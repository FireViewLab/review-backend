package com.example.fireview.domain.product.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 카테고리 화면 목록 한 페이지(#248).
 *
 * @param items         이 페이지 상품. 홈 목록과 같은 {@link ProductResponse} 모양이다
 * @param page          0부터 센 페이지 번호
 * @param size          요청한 페이지 크기
 * @param totalElements 조건에 맞는 전체 상품 수
 * @param hasNext       다음 페이지가 있는지
 */
public record ProductPageResponse(
        List<ProductResponse> items,
        int page,
        int size,
        long totalElements,
        boolean hasNext
) {

    public static ProductPageResponse from(Page<ProductResponse> page) {
        return new ProductPageResponse(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.hasNext());
    }
}
