package com.example.fireview.domain.chat.service;

import com.example.fireview.domain.chat.dto.response.ChatSessionResponse;
import com.example.fireview.domain.chat.entity.ChatSession;
import com.example.fireview.domain.dataserver.DataServerProductKey;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 대화 목록에 상품 이름·이미지를 붙인다(#275).
 *
 * <p>세션은 상품을 외부 식별자({@code kurly-1000146248}) 문자열로만 가진다. 예전에는 프론트가
 * 세션마다 상세를 따로 불러 이름을 붙였다. 여기서 한 페이지의 상품을 쿼리 한 번으로 읽는다.
 *
 * <p>이름은 Spring 번호표({@code products})에서 읽는다. Data 서버를 부르지 않는다 — 목록 20건에
 * Data 를 20번 부를 수 없다. 번호표가 없는 상품(목록·검색에 한 번도 오르지 않은 상품)은 이름이 null 이다.
 */
@Service
@RequiredArgsConstructor
public class ChatSessionListService {

    private final ChatService chatService;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public Page<ChatSessionResponse> getMySessions(String userEmail, Pageable pageable) {
        Page<ChatSession> sessions = chatService.getMySessions(userEmail, pageable);
        Map<DataServerProductKey, Product> products = productsOf(sessions);
        return sessions.map(s -> ChatSessionResponse.of(s,
                DataServerProductKey.parse(s.getProductId()).map(products::get).orElse(null)));
    }

    private Map<DataServerProductKey, Product> productsOf(Page<ChatSession> sessions) {
        var keys = sessions.stream()
                .map(ChatSession::getProductId)
                .map(DataServerProductKey::parse)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());
        if (keys.isEmpty()) return Map.of();
        return productRepository.findByDataPlatformInAndDataProductIdIn(
                        keys.stream().map(DataServerProductKey::platform).collect(Collectors.toSet()),
                        keys.stream().map(DataServerProductKey::productId).collect(Collectors.toSet()))
                .stream()
                .filter(p -> keys.contains(new DataServerProductKey(p.getDataPlatform(), p.getDataProductId())))
                .collect(Collectors.toMap(
                        p -> new DataServerProductKey(p.getDataPlatform(), p.getDataProductId()),
                        Function.identity(), (a, b) -> a));
    }
}
