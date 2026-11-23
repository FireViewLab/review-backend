package com.example.fireview.domain.repurchase;

import com.example.fireview.domain.cart.entity.CartEvent;
import com.example.fireview.domain.cart.repository.CartEventRepository;
import com.example.fireview.domain.cartschedule.repository.CartScheduleRepository;
import com.example.fireview.domain.product.entity.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사용자가 반복해서 담는 상품을 찾는다(RAG 의 검색 단계). 근거는 사용자 본인의 장바구니 담기 기록이다.
 * 진행 중인 예약이 있는 상품은 이미 자동으로 담기므로 뺀다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RepurchaseFinder {

    /** 이보다 오래된 담기 기록은 쓰지 않는다. 생활 패턴이 바뀐 옛 기록이 주기를 흐리지 않게 한다 */
    static final int LOOKBACK_DAYS = 180;

    private final CartEventRepository cartEventRepository;
    private final CartScheduleRepository cartScheduleRepository;
    private final Clock clock;

    /** 다시 살 때가 된 상품. 늦어진 순 */
    public List<RepurchaseCandidate> findDue(Long userId, int limit) {
        return candidates(userId).stream().filter(c -> c.stats().due()).limit(limit).toList();
    }

    /** 반복해서 담는 상품 전부. 아직 때가 아닌 상품도 포함한다. 챗봇이 "자주 담는 상품"을 답할 때 쓴다 */
    public List<RepurchaseCandidate> findAll(Long userId, int limit) {
        return candidates(userId).stream().limit(limit).toList();
    }

    private List<RepurchaseCandidate> candidates(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        Set<Long> scheduled = new HashSet<>(cartScheduleRepository.findActiveProductIds(userId));

        Map<Long, List<LocalDate>> daysByProduct = new LinkedHashMap<>();
        Map<Long, Product> productById = new LinkedHashMap<>();
        for (CartEvent event : cartEventRepository.findByUser_IdAndCreatedAtAfterOrderByCreatedAtAsc(
                userId, now.minusDays(LOOKBACK_DAYS))) {
            Long productId = event.getProduct().getId();
            if (scheduled.contains(productId)) continue;
            productById.putIfAbsent(productId, event.getProduct());
            daysByProduct.computeIfAbsent(productId, k -> new ArrayList<>()).add(event.getCreatedAt().toLocalDate());
        }

        LocalDate today = now.toLocalDate();
        List<RepurchaseCandidate> found = new ArrayList<>();
        daysByProduct.forEach((productId, days) -> RepurchaseAnalyzer.analyze(days, today).ifPresent(stats -> {
            Product p = productById.get(productId);
            found.add(new RepurchaseCandidate(p.getId(), p.dataServerExternalId(), p.getName(), p.getImageUrl(),
                    p.getPrice(), stats));
        }));
        found.sort(Comparator.comparingDouble((RepurchaseCandidate c) -> c.stats().score()).reversed()
                .thenComparing(c -> c.stats().timesAdded(), Comparator.reverseOrder())
                .thenComparing(RepurchaseCandidate::productId));
        return found;
    }
}
