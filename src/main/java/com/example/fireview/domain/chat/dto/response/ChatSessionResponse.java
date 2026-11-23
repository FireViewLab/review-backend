package com.example.fireview.domain.chat.dto.response;

import com.example.fireview.domain.chat.entity.ChatSession;
import com.example.fireview.domain.product.entity.Product;

import java.time.LocalDateTime;

/**
 * @param productName     대화 상품 이름. 상품 없는 대화이거나 Spring 에 번호표가 없는 상품이면 null(#275)
 * @param productImageUrl 대화 상품 대표 이미지. null 조건은 위와 같다
 */
public record ChatSessionResponse(
        Long id,
        String productId,
        String title,
        LocalDateTime createdAt,
        LocalDateTime lastMessageAt,
        String productName,
        String productImageUrl
) {
    /** @param product 대화 상품의 번호표. 없으면 null */
    public static ChatSessionResponse of(ChatSession session, Product product) {
        return new ChatSessionResponse(
                session.getId(), session.getProductId(), session.getTitle(),
                session.getCreatedAt(), session.getLastMessageAt(),
                product == null ? null : product.getName(),
                product == null ? null : product.getImageUrl());
    }
}
