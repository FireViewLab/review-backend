package com.example.fireview.domain.product.service;

import com.example.fireview.domain.product.dto.ProductPageResponse;
import com.example.fireview.domain.product.dto.ProductResponse;
import com.example.fireview.domain.product.entity.Category;
import com.example.fireview.domain.product.entity.MajorCategory;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.global.exception.CustomException;
import com.example.fireview.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    public Product findById(Long id) {
        // DB PK로 먼저 조회, 없으면 naverProductId로 fallback 조회
        return productRepository.findById(id)
                .orElseGet(() -> productRepository.findByNaverProductId(String.valueOf(id))
                        .orElseThrow(() -> new CustomException(ErrorCode.PRODUCT_NOT_FOUND)));
    }

    public ProductResponse getProduct(Long id) {
        return ProductResponse.from(findById(id));
    }

    /**
     * 홈 목록.
     *
     * <p>Data 서버 상품이 하나라도 있으면 그것만 보여준다. 더미와 섞으면 사용자가 진짜와
     * 가짜를 구분할 수 없다. 아직 하나도 없을 때만 기존 더미를 보여준다 — 빈 홈보다는 낫다.
     *
     * <p>Data 상품·분석 카탈로그 동기화와 홈 자동 검색·사용자 검색으로 표시 상품을 채운다.
     *
     * <p>분석 완료 상품을 우선한 후보300건에서 대분류별로 번갈아100건을 고른다. 최근 순으로만 자르면 마지막 검색
     * 키워드 두세 개가 홈을 다 차지한다. 분류가 없는 상품도 한 묶음으로 끼워 넣는다.
     *
     * <p>캐시는 검색·상세 조회로 표시 정보가 바뀔 때 {@code ProductTagRegistry} 가 비운다.
     */
    @Cacheable(value = "productList", key = "'all'")
    public List<ProductResponse> getAllProducts() {
        List<Product> fromDataServer = productRepository.findHomeCatalogCandidates(PageRequest.of(0, 300));
        List<Product> source = fromDataServer.isEmpty()
                ? productRepository.findAll()
                : mixByMajorCategory(fromDataServer, HOME_SIZE);
        return source.stream()
                .map(ProductResponse::from)
                .toList();
    }

    private static final int HOME_SIZE = 100;

    /** 카테고리 화면 한 페이지의 최대 크기. 홈 목록 크기와 맞춘다 */
    static final int MAX_PAGE_SIZE = HOME_SIZE;

    /**
     * 카테고리 화면 목록(#248). Data 서버 상품만 페이지로 나눠 돌려준다.
     *
     * <p>{@code category} 는 대분류({@link MajorCategory}) 또는 세부 분류({@link Category}) enum 이름이다.
     * 대분류면 그 아래 세부 분류를 모두 포함한다. 비우면 전체다. 대소문자는 가리지 않는다.
     * 홈 목록과 달리 더미로 채우지 않는다 — 카테고리 화면은 실제 상품만 센다.
     *
     * @throws CustomException 모르는 카테고리이거나 page·size 가 범위를 벗어나면 {@code INVALID_INPUT}
     */
    public ProductPageResponse getCatalogPage(String category, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        PageRequest pageable = PageRequest.of(page, size);
        Page<Product> found = category == null || category.isBlank()
                ? productRepository.findCatalog(pageable)
                : productRepository.findCatalogByCategoryIn(categoriesOf(category.strip()), pageable);
        return ProductPageResponse.from(found.map(ProductResponse::from));
    }

    static List<Category> categoriesOf(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        for (MajorCategory major : MajorCategory.values()) {
            if (major.name().equals(upper)) {
                return Arrays.stream(Category.values()).filter(c -> c.getMajor() == major).toList();
            }
        }
        for (Category c : Category.values()) {
            if (c.name().equals(upper)) return List.of(c);
        }
        throw new CustomException(ErrorCode.INVALID_INPUT);
    }

    /**
     * 대분류별로 묶어 한 개씩 번갈아 꺼낸다. 묶음 순서와 묶음 안 순서는 입력(최근 순)을 따른다.
     */
    static List<Product> mixByMajorCategory(List<Product> newestFirst, int limit) {
        Map<Object, Deque<Product>> groups = new LinkedHashMap<>();
        for (Product p : newestFirst) {
            Object key = p.getCategory() == null ? "NONE" : p.getCategory().getMajor();
            groups.computeIfAbsent(key, k -> new ArrayDeque<>()).add(p);
        }
        List<Product> out = new ArrayList<>(Math.min(limit, newestFirst.size()));
        while (out.size() < limit && !groups.isEmpty()) {
            Iterator<Deque<Product>> it = groups.values().iterator();
            while (it.hasNext() && out.size() < limit) {
                Deque<Product> group = it.next();
                out.add(group.poll());
                if (group.isEmpty()) it.remove();
            }
        }
        return out;
    }

    /** 로컬 DB 상품명 검색 (platformLinks JOIN FETCH로 LazyInit 방지) */
    public List<ProductResponse> searchProducts(String keyword) {
        return productRepository.findByNameContainingIgnoreCaseWithLinks(keyword).stream()
                .map(ProductResponse::from)
                .toList();
    }

    @Transactional
    public void updateAvgRti(Long productId, double newAvgRti) {
        Product product = findById(productId);
        product.setAvgRti(newAvgRti);
        productRepository.save(product);
    }

    /**
     * 네이버 캐시 상품을 DB에 저장하고 저장된 ProductResponse 반환.
     * 이미 naverProductId로 저장된 상품이 있으면 기존 상품 반환 (중복 저장 방지).
     */
    @Transactional
    public ProductResponse saveFromCache(ProductResponse cached) {
        // 중복 저장 방지: naverProductId로 먼저 조회
        if (cached.naverProductId() != null) {
            return productRepository.findByNaverProductId(cached.naverProductId())
                    .map(existing -> {
                        log.info("[ProductService] 이미 DB에 존재하는 네이버 상품: naverProductId={}", cached.naverProductId());
                        return ProductResponse.from(existing);
                    })
                    .orElseGet(() -> doSaveFromCache(cached));
        }
        return doSaveFromCache(cached);
    }

    private ProductResponse doSaveFromCache(ProductResponse cached) {
        Category category = cached.category() != null ? cached.category() : Category.ETC;
        // naverProductId를 DB PK로 직접 사용
        Long productId = cached.id() != null ? cached.id() : Long.parseLong(cached.naverProductId());
        Product product = Product.builder()
                .id(productId)
                .name(cached.name())
                .imageUrl(cached.imageUrl())
                .price(cached.price())
                .category(category)
                .platform(cached.platform() != null ? cached.platform() : "NAVER")
                .naverProductId(cached.naverProductId())
                .subCategory(cached.subCategory())
                .avgRti(50.0)
                .reviewCount(0)
                .avgRating(0.0)
                .build();
        Product saved = productRepository.save(product);
        log.info("[ProductService] 네이버 캐시 상품 DB 저장 완료: id={}, naverProductId={}", saved.getId(), saved.getNaverProductId());
        return ProductResponse.from(saved);
    }
}
