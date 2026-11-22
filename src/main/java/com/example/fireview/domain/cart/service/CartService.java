package com.example.fireview.domain.cart.service;

import com.example.fireview.domain.cart.dto.CartItemResponse;
import com.example.fireview.domain.cart.dto.CartSummaryResponse;
import com.example.fireview.domain.cart.entity.CartEvent;
import com.example.fireview.domain.cart.entity.CartItem;
import com.example.fireview.domain.cart.repository.CartEventRepository;
import com.example.fireview.domain.cart.repository.CartRepository;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.service.ProductService;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.repository.UserRepository;
import com.example.fireview.domain.user.service.UserService;
import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartService {

    private final CartRepository cartRepository;
    private final UserService userService;
    private final ProductService productService;
    private final CartEventRepository cartEventRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /** 장바구니 조회 */
    public CartSummaryResponse getCart(String userEmail) {
        User user = userService.findByEmail(userEmail);
        List<CartItemResponse> items = cartRepository.findByUser_IdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(CartItemResponse::from)
                .toList();
        return CartSummaryResponse.of(items);
    }

    /** 장바구니 상품 추가 */
    @Transactional
    public CartItemResponse addToCart(String userEmail, Long productId, int quantity) {
        User user = userService.findByEmail(userEmail);
        Product product = productService.findById(productId);
        return add(user, product, quantity, CartEvent.MANUAL);
    }

    /**
     * 장바구니에 담고 담기 이력을 한 줄 남긴다. 직접 담기와 예약 자동 담기가 같이 쓴다.
     *
     * @param source {@link CartEvent#MANUAL} / {@link CartEvent#SCHEDULE}
     */
    @Transactional
    public CartItemResponse add(User user, Product product, int quantity, String source) {
        // 읽고 더해서 저장하는 사이에 같은 사용자의 다른 담기(직접·예약)가 끼면 한쪽 증가가 사라진다.
        // 사용자 행을 잠가 같은 사용자의 담기를 한 줄로 세운다. 첫 담기끼리 겹쳐 같은 행을 두 번
        // 만들려다 유니크 제약에 걸리는 것도 함께 막는다.
        userRepository.lockById(user.getId());
        cartEventRepository.save(CartEvent.builder()
                .user(user)
                .product(product)
                .quantity(quantity)
                .source(source)
                .createdAt(LocalDateTime.now(clock))
                .build());

        // 이미 있으면 수량 증가
        return cartRepository.findByUser_IdAndProduct_Id(user.getId(), product.getId())
                .map(item -> {
                    item.setQuantity(item.getQuantity() + quantity);
                    return CartItemResponse.from(cartRepository.save(item));
                })
                .orElseGet(() -> {
                    CartItem item = CartItem.builder()
                            .user(user)
                            .product(product)
                            .quantity(quantity)
                            .build();
                    return CartItemResponse.from(cartRepository.save(item));
                });
    }

    /** 수량 변경 */
    @Transactional
    public CartItemResponse updateQuantity(String userEmail, Long productId, int quantity) {
        if (quantity < 1) throw new CustomException(ErrorCode.INVALID_INPUT);

        User user = userService.findByEmail(userEmail);
        CartItem item = cartRepository.findByUser_IdAndProduct_Id(user.getId(), productId)
                .orElseThrow(() -> new CustomException(ErrorCode.CART_ITEM_NOT_FOUND));

        item.setQuantity(quantity);
        return CartItemResponse.from(cartRepository.save(item));
    }

    /** 장바구니 상품 삭제 */
    @Transactional
    public void removeFromCart(String userEmail, Long productId) {
        User user = userService.findByEmail(userEmail);
        if (!cartRepository.existsByUser_IdAndProduct_Id(user.getId(), productId)) {
            throw new CustomException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
        cartRepository.deleteByUser_IdAndProduct_Id(user.getId(), productId);
    }

    /** 장바구니 전체 비우기 */
    @Transactional
    public void clearCart(String userEmail) {
        User user = userService.findByEmail(userEmail);
        cartRepository.deleteAllByUser_Id(user.getId());
    }
}
