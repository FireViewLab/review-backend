package com.example.fireview.domain.repurchase;

/**
 * 반복해서 담은 상품 하나와 분석 결과.
 *
 * @param productId  Spring 상품 번호. 장바구니·찜·예약과 같은 번호다
 * @param externalId Data 상품 주소 {@code "{platform}-{productId}"}. 없으면 null
 */
public record RepurchaseCandidate(Long productId, String externalId, String name, String thumbnailUrl,
                                  Long price, RepurchaseStats stats) {
}
