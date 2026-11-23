package com.example.fireview.domain.repurchase;

import com.example.fireview.domain.cart.repository.CartEventRepository;
import com.example.fireview.domain.cartschedule.repository.CartScheduleRepository;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final ProductRepository productRepository;
    private final Clock clock;

    /** 다시 살 때가 된 상품. 늦어진 순 */
    public List<RepurchaseCandidate> findDue(Long userId, int limit) {
        return find(userId, true, limit);
    }

    /** 반복해서 담는 상품 전부. 아직 때가 아닌 상품도 포함한다. 챗봇이 "자주 담는 상품"을 답할 때 쓴다 */
    public List<RepurchaseCandidate> findAll(Long userId, int limit) {
        return find(userId, false, limit);
    }

    /**
     * 담은 상품 번호와 시각만 읽어 분석하고, 고른 상품의 정보만 한 번에 읽는다.
     * 매 챗봇 턴에 불리므로 쿼리 수가 상품 수에 비례하지 않게 한다(#271 리뷰).
     */
    private List<RepurchaseCandidate> find(Long userId, boolean dueOnly, int limit) {
        LocalDateTime now = LocalDateTime.now(clock);
        Set<Long> scheduled = new HashSet<>(cartScheduleRepository.findActiveProductIds(userId));

        Map<Long, List<LocalDate>> daysByProduct = new HashMap<>();
        for (CartEventRepository.AddedAt added
                : cartEventRepository.findAddedAtSince(userId, now.minusDays(LOOKBACK_DAYS))) {
            if (scheduled.contains(added.productId())) continue;
            daysByProduct.computeIfAbsent(added.productId(), k -> new ArrayList<>())
                    .add(added.createdAt().toLocalDate());
        }

        LocalDate today = now.toLocalDate();
        List<Map.Entry<Long, RepurchaseStats>> ranked = new ArrayList<>();
        daysByProduct.forEach((productId, days) -> RepurchaseAnalyzer.analyze(days, today)
                .filter(stats -> !dueOnly || stats.due())
                .ifPresent(stats -> ranked.add(Map.entry(productId, stats))));
        ranked.sort(Comparator.comparingDouble((Map.Entry<Long, RepurchaseStats> e) -> e.getValue().score())
                .reversed()
                .thenComparing(e -> e.getValue().timesAdded(), Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey));
        List<Map.Entry<Long, RepurchaseStats>> chosen = ranked.stream().limit(limit).toList();
        if (chosen.isEmpty()) return List.of();

        Map<Long, Product> productById = productRepository.findAllById(
                chosen.stream().map(Map.Entry::getKey).toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return chosen.stream()
                .filter(e -> productById.containsKey(e.getKey()))
                .map(e -> {
                    Product p = productById.get(e.getKey());
                    return new RepurchaseCandidate(p.getId(), p.dataServerExternalId(), p.getName(),
                            p.getImageUrl(), p.getPrice(), e.getValue());
                })
                .toList();
    }
}
