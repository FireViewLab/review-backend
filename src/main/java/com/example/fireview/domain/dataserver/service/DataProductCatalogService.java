package com.example.fireview.domain.dataserver.service;

import com.example.fireview.domain.dataserver.client.DataServerClient;
import com.example.fireview.domain.dataserver.dto.DataServerProduct;
import com.example.fireview.domain.product.dto.ProductResponse;
import com.example.fireview.global.config.ExecutorConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Data 서버를 원천으로 하는 상품 검색.
 *
 * <p>기존 {@code GET /api/products?keyword=} 의 응답 모양({@code ProductResponse})을 그대로
 * 유지하고 출처만 Data 서버로 바꾼다. 프론트는 부르는 주소도 받는 모양도 바뀌지 않는다.
 *
 * <p>검색 결과마다 번호표를 붙여 저장한다. 프론트가 목록→상세로 갈 때 Long {@code id} 를
 * 쓰기 때문이다. 부수효과로 누군가 검색한 상품이 쌓여 홈 목록의 원천이 된다 — Data 서버에는
 * "수집된 상품 전체 목록" API 가 없어서 홈을 채울 다른 방법이 없다.
 */
@Slf4j
@Service
public class DataProductCatalogService {

    private final DataServerClient dataServerClient;
    private final ProductTagRegistry registry;
    private final Executor executor;
    private final List<String> platforms;
    private final int limitPerPlatform;
    private final long timeoutMs;

    public DataProductCatalogService(
            DataServerClient dataServerClient,
            ProductTagRegistry registry,
            @Qualifier(ExecutorConfig.DATA_SERVER_EXECUTOR) Executor executor,
            @Value("${app.data-server.search-platforms:kurly,oliveyoung,musinsa,elevenst}") String platforms,
            @Value("${app.data-server.search-limit-per-platform:10}") int limitPerPlatform,
            @Value("${app.data-server.search-timeout-ms:5000}") long timeoutMs) {
        this.dataServerClient = dataServerClient;
        this.registry = registry;
        this.executor = executor;
        this.platforms = Arrays.stream(platforms.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.limitPerPlatform = limitPerPlatform;
        this.timeoutMs = timeoutMs;
        log.info("[DataCatalog] 검색 대상 {} / 몰당 {}건 / 제한시간 {}ms",
                this.platforms, limitPerPlatform, timeoutMs);
    }

    /** Data 서버 검색을 쓸 수 있는 상태인지. 아니면 호출부가 기존 검색으로 떨어진다 */
    public boolean isEnabled() {
        return dataServerClient.isConfigured() && !platforms.isEmpty();
    }

    /**
     * 키워드로 여러 쇼핑몰을 동시에 검색한다.
     *
     * <p>외부 호출은 트랜잭션 밖에서 한다. 트랜잭션 안에서 기다리면 그동안 DB 커넥션을
     * 붙잡고 있게 된다. 저장만 짧은 트랜잭션으로 묶는다.
     */
    public List<ProductResponse> search(String keyword) {
        List<ProductResponse> live = search(keyword, limitPerPlatform);
        if (!live.isEmpty()) {
            return live;
        }
        // 실시간 몰 검색이 비면 이미 모아 둔 상품에서 찾는다. 운영자가 수집을 멈추면 Data 는 몰 검색을
        // 503 으로 거절하는데, 그동안 모은 상품·리뷰는 그대로 남아 있다. 홈 자동 채우기는 새로 채운
        // 수를 세므로 이 대체 결과를 쓰지 않는다(search(keyword, limit) 를 직접 부른다).
        List<ProductResponse> stored = registry.findStoredForDisplay(keyword, limitPerPlatform * platforms.size());
        log.info("[DataCatalog] 실시간 검색 0건, 저장된 상품 {}건으로 대신한다 - keyword={}", stored.size(), keyword);
        return stored;
    }

    /**
     * 몰당 건수를 정해서 검색한다. 홈 자동 채우기처럼 여러 키워드를 고루 담고 싶을 때
     * 키워드 하나가 많이 차지하지 않게 줄여 쓴다.
     */
    public List<ProductResponse> search(String keyword, int limitPerPlatform) {
        List<List<DataServerProduct>> perPlatform = fetchAll(keyword, limitPerPlatform);
        List<DataServerProduct> merged = interleave(perPlatform);
        if (merged.isEmpty()) {
            // Data 는 리뷰가 저장된 상품만 돌려준다. 수집이 밀리면 몰 검색은 성공해도 결과가 비고,
            // 실패가 아니라 경고 로그도 남지 않아 "결과 없음"과 장애를 구분할 수 없었다.
            // 몰이 실패했다면 그 몰의 경고 로그가 이 줄 앞에 따로 남는다.
            log.info("[DataCatalog] 검색 결과 0건 - keyword={}, 몰 {}곳 모두 빈 결과", keyword, perPlatform.size());
            return List.of();
        }
        try {
            return registry.upsertAllForDisplay(merged);
        } catch (DataIntegrityViolationException e) {
            // 같은 새 상품을 두 요청이 동시에 만들려다 유니크 제약에 걸린 경우다.
            // 다시 하면 이미 행이 있어 갱신만 일어난다.
            log.info("[DataCatalog] 동시 저장 충돌, 한 번 더 시도 - keyword={}", keyword);
            return registry.upsertAllForDisplay(merged);
        }
    }

    /**
     * 몰마다 따로 부르고, 제한 시간 안에 오지 않은 몰은 빈 결과로 친다.
     * 느린 몰 하나(옥션은 30초가 넘게 걸린 적이 있다)가 검색 전체를 붙잡지 않게 한다.
     */
    private List<List<DataServerProduct>> fetchAll(String keyword, int limitPerPlatform) {
        List<CompletableFuture<List<DataServerProduct>>> futures = platforms.stream()
                .map(platform -> CompletableFuture
                        .supplyAsync(() -> dataServerClient.searchProducts(platform, keyword, limitPerPlatform), executor)
                        .completeOnTimeout(List.of(), timeoutMs, TimeUnit.MILLISECONDS)
                        .exceptionally(e -> {
                            log.warn("[DataCatalog] 검색 예외 - platform={}: {}", platform, e.getMessage());
                            return List.of();
                        }))
                .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    /**
     * 몰별 결과를 번갈아 섞는다. 그냥 이어 붙이면 첫 번째 몰 상품이 목록 위쪽을 다 차지한다.
     * 같은 상품(같은 몰·같은 ID)이 두 번 오면 한 번만 남긴다.
     */
    static List<DataServerProduct> interleave(List<List<DataServerProduct>> perPlatform) {
        Map<String, DataServerProduct> unique = new LinkedHashMap<>();
        int longest = perPlatform.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < longest; i++) {
            for (List<DataServerProduct> list : perPlatform) {
                if (i < list.size()) {
                    DataServerProduct p = list.get(i);
                    if (p.platform() != null && p.productId() != null) {
                        unique.putIfAbsent(p.platform() + "-" + p.productId(), p);
                    }
                }
            }
        }
        return new ArrayList<>(unique.values());
    }
}
