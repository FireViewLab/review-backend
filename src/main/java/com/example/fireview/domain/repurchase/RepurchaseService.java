package com.example.fireview.domain.repurchase;

import com.example.fireview.domain.cartschedule.dto.CartScheduleResponse;
import com.example.fireview.domain.cartschedule.service.CartScheduleService;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.repurchase.dto.RepurchaseResponse;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.service.UserService;
import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 메인 "다시 살 때 된 상품"과 그 자리에서 정기 예약을 거는 기능. LLM 을 쓰지 않는다 */
@Service
@RequiredArgsConstructor
public class RepurchaseService {

    /** 메인에 보여줄 최대 개수 */
    static final int MAX_ITEMS = 10;

    /** 정기 예약 제안은 1개씩 담는 것으로 만든다. 수량을 바꾸려면 챗봇이나 예약 목록에서 다시 건다 */
    private static final int SUGGESTED_QUANTITY = 1;

    private final RepurchaseFinder finder;
    private final CartScheduleService cartScheduleService;
    private final ProductRepository productRepository;
    private final UserService userService;

    public List<RepurchaseResponse> myRepurchases(String userEmail) {
        User user = userService.findByEmail(userEmail);
        return finder.findDue(user.getId(), MAX_ITEMS).stream().map(RepurchaseResponse::from).toList();
    }

    /**
     * 추천의 정기 예약 제안대로 바로 예약을 만든다. 제안은 화면이 보낸 값이 아니라 서버가 지금 다시 계산한 값이다.
     *
     * @throws CustomException 다시 살 때가 아니거나, 제안할 주기가 아니거나, 예약을 만들 수 없으면
     *                         {@code REPURCHASE_NOT_SCHEDULABLE}
     */
    public CartScheduleResponse scheduleFromSuggestion(String userEmail, Long productId) {
        User user = userService.findByEmail(userEmail);
        RepurchaseCandidate candidate = finder.findDue(user.getId(), Integer.MAX_VALUE).stream()
                .filter(c -> c.productId().equals(productId) && c.stats().suggestion() != null)
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.REPURCHASE_NOT_SCHEDULABLE));
        return productRepository.findById(productId)
                .flatMap(product -> cartScheduleService.createActive(
                        user, product, candidate.stats().suggestion(), SUGGESTED_QUANTITY))
                .orElseThrow(() -> new CustomException(ErrorCode.REPURCHASE_NOT_SCHEDULABLE));
    }
}
