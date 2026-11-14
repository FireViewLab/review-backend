package com.example.fireview.domain.product.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 네이버 쇼핑 검색 API 클라이언트
 *
 * 두 가지 기능을 제공한다:
 *  1. searchProducts(keyword) - 키워드로 상품 목록 검색
 *  2. fetchThumbnail(productName) - 상품명으로 썸네일 URL 한 건 조회
 *
 * API 키 설정:
 *   application.properties → naver.shopping.client-id / naver.shopping.client-secret
 *   환경변수 → NAVER_SHOPPING_CLIENT_ID / NAVER_SHOPPING_CLIENT_SECRET
 *
 * 미설정 또는 API 오류 시 빈 결과를 반환해 앱 기동을 막지 않는다.
 */
@Slf4j
@Component
public class NaverShoppingClient {

    private static final String SEARCH_URL = "https://openapi.naver.com/v1/search/shop.json";

    private final RestTemplate restTemplate;
    private final String clientId;
    private final String clientSecret;

    public NaverShoppingClient(
            RestTemplate restTemplate,
            @Value("${naver.shopping.client-id:}") String clientId,
            @Value("${naver.shopping.client-secret:}") String clientSecret) {
        this.restTemplate = restTemplate;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    /**
     * 키워드로 네이버 쇼핑 상품 목록 검색.
     * display > 100 이면 자동으로 페이징하여 최대 1000건까지 수집한다.
     *
     * @param keyword 검색어
     * @param display 가져올 결과 수 (네이버 API 한도: 회당 100, 누적 최대 1000)
     * @return 네이버 쇼핑 아이템 목록. API 키 미설정 또는 오류 시 빈 리스트.
     */
    public List<NaverShoppingItem> searchProducts(String keyword, int display) {
        if (!isConfigured()) {
            log.debug("[NaverShopping] API 키 미설정 - 검색 생략: {}", keyword);
            return Collections.emptyList();
        }

        int target = Math.min(display, 1000); // 네이버 API 최대 누적 1000건
        List<NaverShoppingItem> result = new ArrayList<>();
        int start = 1;

        while (result.size() < target && start <= 1000) {
            int pageSize = Math.min(100, target - result.size()); // 회당 최대 100
            List<NaverShoppingItem> page = fetchPage(keyword, pageSize, start);
            if (page.isEmpty()) break;
            result.addAll(page);
            start += page.size();
        }

        log.debug("[NaverShopping] 검색 완료: keyword={}, 수집={}건", keyword, result.size());
        return result;
    }

    /**
     * 단일 페이지 요청.
     */
    private List<NaverShoppingItem> fetchPage(String keyword, int display, int start) {
        try {
            String url = UriComponentsBuilder.fromUriString(SEARCH_URL)
                    .queryParam("query", keyword)
                    .queryParam("display", display)
                    .queryParam("start", start)
                    .queryParam("sort", "sim")   // 정확도순
                    .build().toUriString();

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, buildEntity(), Map.class);

            if (response.getBody() == null) return Collections.emptyList();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawItems =
                    (List<Map<String, Object>>) response.getBody().get("items");

            if (rawItems == null) return Collections.emptyList();

            return rawItems.stream()
                    .map(this::toNaverShoppingItem)
                    .toList();

        } catch (Exception e) {
            log.warn("[NaverShopping] 페이지 요청 실패 (keyword={}, start={}): {}", keyword, start, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 상품명으로 첫 번째 결과의 썸네일 URL 반환.
     * DataInitializer 초기 데이터 세팅 시 사용.
     */
    public String fetchThumbnail(String productName) {
        List<NaverShoppingItem> items = searchProducts(productName, 1);
        if (items.isEmpty()) return "";
        return items.get(0).image() != null ? items.get(0).image() : "";
    }

    /**
     * 상품명으로 첫 번째 결과의 NaverShoppingItem 반환 (이미지 + 링크 포함).
     * DataInitializer에서 썸네일과 실제 Naver 링크를 동시에 가져올 때 사용.
     */
    public NaverShoppingItem fetchItem(String productName) {
        List<NaverShoppingItem> items = searchProducts(productName, 1);
        return items.isEmpty() ? null : items.get(0);
    }

    // ──────────────────────────────────────────────────────────────────────

    private HttpEntity<Void> buildEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Naver-Client-Id", clientId);
        headers.set("X-Naver-Client-Secret", clientSecret);
        return new HttpEntity<>(headers);
    }

    private NaverShoppingItem toNaverShoppingItem(Map<String, Object> raw) {
        return new NaverShoppingItem(
                str(raw, "title"),
                str(raw, "link"),
                str(raw, "image"),
                str(raw, "lprice"),
                str(raw, "mallName"),
                str(raw, "productId"),
                str(raw, "brand"),
                str(raw, "category1"),
                str(raw, "category2"),
                str(raw, "category3")
        );
    }

    private String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : "";
    }
}
