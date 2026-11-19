package com.example.fireview.domain.search.service;

import com.example.fireview.domain.product.cache.NaverProductCache;
import com.example.fireview.domain.product.client.NaverShoppingClient;
import com.example.fireview.domain.product.dto.ProductResponse;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.search.dto.NaverSearchResponse;
import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NaverSearchService {

    private final NaverShoppingClient naverShoppingClient;
    private final ProductRepository productRepository;
    private final NaverProductCache naverProductCache;

    /**
     * 네이버 쇼핑 API로 상품 검색.
     *
     * @param keyword 검색어 (필수, 공백 불가)
     * @param display 결과 수 (기본 30, 최대 100)
     * @return 네이버 쇼핑 검색 결과
     */
    public NaverSearchResponse search(String keyword, int display) {
        if (keyword == null || keyword.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }

        if (!naverShoppingClient.isConfigured()) {
            log.warn("[NaverSearch] API 키 미설정 - 검색 불가: {}", keyword);
            throw new CustomException(ErrorCode.NAVER_API_NOT_CONFIGURED);
        }

        log.info("[NaverSearch] 검색 시작: keyword={}, display={}", keyword, display);

        // 1. DB에서 RTI 데이터가 있는 상품 우선 검색 (platformLinks JOIN FETCH로 LazyInit 방지)
        List<ProductResponse> dbProducts = productRepository
                .findByNameContainingIgnoreCaseWithLinks(keyword)
                .stream()
                .map(ProductResponse::from)
                .toList();

        // 2. 네이버 API 검색
        List<ProductResponse> naverProducts = naverShoppingClient
                .searchProducts(keyword, display)
                .stream()
                .map(ProductResponse::fromNaverItem)
                .toList();

        // 3. DB 상품명 set (중복 제거용)
        Set<String> dbNames = dbProducts.stream()
                .map(p -> p.name().toLowerCase())
                .collect(Collectors.toSet());

        // 4. 네이버 결과 중 DB에 없는 상품만 추가 (DB 상품 우선 노출)
        List<ProductResponse> merged = new ArrayList<>(dbProducts);
        naverProducts.stream()
                .filter(p -> !dbNames.contains(p.name().toLowerCase()))
                .forEach(merged::add);

        // 5. 네이버 결과를 캐시에 저장 (상품 상세 조회 시 DB 없는 경우 fallback)
        naverProductCache.putAll(naverProducts);

        log.info("[NaverSearch] 검색 완료: keyword={}, DB={}건, 네이버={}건, 합계={}건",
                keyword, dbProducts.size(), naverProducts.size(), merged.size());

        return NaverSearchResponse.of(keyword, merged);
    }
}
