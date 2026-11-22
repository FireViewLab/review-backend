package com.example.fireview.domain.dataserver.service;

import com.example.fireview.domain.dataserver.DataServerCategoryMapper;
import com.example.fireview.domain.dataserver.DataServerProductKey;
import com.example.fireview.domain.dataserver.dto.DataServerProduct;
import com.example.fireview.domain.dataserver.dto.DataServerCatalogPage;
import com.example.fireview.domain.dataserver.dto.response.AnalysisStatus;
import com.example.fireview.domain.product.entity.Category;
import com.example.fireview.domain.product.entity.PlatformLink;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.fireview.domain.product.dto.ProductResponse;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Limit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/**
 * Data 서버 상품에 Spring 쪽 번호표를 붙인다.
 *
 * <p>찜·장바구니·조회이력은 {@code products.id}(Long)를 FK 로 물고 있고 Data 서버는
 * {@code (platform, product_id)} 로 상품을 가리킨다. 이 사이를 잇는 행을 만든다.
 *
 * <p><b>상품을 열어보는 것만으로는 만들지 않는다.</b> 그렇게 하면 Data 서버에 있는
 * 상품 수만큼 빈 행이 쌓인다. 찜처럼 Spring 쪽에 기록이 실제로 필요해질 때만 부른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductTagRegistry {

    /**
     * 기존 더미 상품이 쓰는 ID 구간. 새 번호표가 여기에 떨어지면 비켜간다.
     * {@code DataInitializer} 의 fallback 번호가 {@code 900_000_000_000} 부터 시작한다.
     */
    private static final long DUMMY_ID_FLOOR = 900_000_000_000L;
    private static final long DUMMY_ID_CEIL = 900_001_000_000L;

    /** ID 비트 수. JS 안전 정수(2^53 - 1) 안에 들어가야 웹 프론트에서 값이 안 틀어진다 */
    private static final int ID_BITS = 52;

    private final ProductRepository productRepository;

    /** 이미 붙어 있는 번호표만 찾는다. 만들지 않는다 */
    @Transactional(readOnly = true)
    public java.util.Optional<Product> find(DataServerProductKey key) {
        return productRepository.findByDataPlatformAndDataProductId(key.platform(), key.productId());
    }

    /**
     * 번호표를 찾고, 없으면 만든다.
     *
     * @param name Data 서버에서 받은 상품명. 알림·목록에 쓸 최소 표시용으로만 저장한다.
     *             가격·평점·리뷰는 저장하지 않는다 — 그쪽 주인은 Data 서버다
     */
    @Transactional
    public Product resolveOrCreate(DataServerProductKey key, String name) {
        return productRepository
                .findByDataPlatformAndDataProductId(key.platform(), key.productId())
                .orElseGet(() -> create(key, name));
    }

    /**
     * Data 서버 상품으로 번호표를 찾거나 만들고, 목록 표시용 정보를 최신값으로 덮는다.
     *
     * <p><b>표시용 캐시다.</b> 홈·검색 목록은 상품 20개를 보여줄 때 Data 서버를 20번 부를 수
     * 없어서 이름·가격·이미지·카테고리·리뷰 수·구매 링크만 이 행에 적어 둔다. 원본은
     * 여전히 Data 서버이고, 검색·상세 조회 때마다 다시 덮이므로 잠깐 낡을 수는 있어도
     * 오래 어긋나지 않는다. 리뷰 원본은 저장하지 않는다. 최신 분석 상태·실제 평균·표본 범위는
     * 별도 카탈로그 동기화가 기록한다.
     *
     * <p>몰 카테고리 원문은 {@code subCategory} 에 그대로 두고, 그 원문과 상품명으로
     * {@code Category} 를 분류해 넣는다({@link DataServerCategoryMapper}). 분류 근거가 없으면
     * 비워 둔다. 억지로 끼우면 엉뚱한 분류가 뜬다.
     */
    @Transactional
    @CacheEvict(value = "productList", allEntries = true)
    public Product upsertForDisplay(DataServerProduct source) {
        DataServerProductKey key = new DataServerProductKey(source.platform(), source.productId());
        Product product = resolveOrCreate(key, source.name());

        // 빈 값(null)은 "모른다"는 뜻이지 "없다"는 뜻이 아니다. 기존 값을 지우지 않는다.
        // Data 서버는 같은 상품이라도 검색 응답에는 review_count 를 주고 상세 응답에서는
        // 비우는 쇼핑몰이 있다(11번가·올리브영). 그대로 덮으면 상세를 한 번 열 때마다
        // 목록의 리뷰 수가 0 이 됐다.
        if (hasText(source.name())) {
            product.setName(source.name());
        }
        if (hasText(source.thumbnailUrl())) {
            product.setImageUrl(source.thumbnailUrl());
        }
        if (source.price() != null) {
            product.setPrice(source.price().longValue());
        }
        if (source.reviewCount() != null) {
            product.setReviewCount(source.reviewCount());
        }
        if (source.rating() != null) {
            product.setAvgRating(source.rating());
        }
        if (hasText(source.category())) {
            product.setSubCategory(truncate(source.category(), 100));
        }
        // 카테고리 화면이 이 값으로 거른다. 저장해 둔 몰 카테고리와 상품명으로 분류해서,
        // 검색 응답처럼 카테고리가 빈 때도 상세에서 받아 둔 경로를 쓴다.
        // 분류 못 하면 기존 값을 그대로 둔다.
        Category category = DataServerCategoryMapper.classify(product.getSubCategory(), product.getName());
        if (category != null) {
            product.setCategory(category);
        }

        // 구매 링크. 프론트의 "구매하러 가기"와 최저가 표시가 이 값을 쓴다.
        // 주소가 비어 오면 기존 링크를 그대로 둔다.
        if (hasText(source.url())) {
            product.getPlatformLinks().clear();
            product.getPlatformLinks().add(PlatformLink.builder()
                    .platform(truncate(key.platform().toUpperCase(), 30))
                    .price(product.getPrice())
                    .url(truncate(source.url(), 1000))
                    .build());
        }
        return product;
    }

    /**
     * v2 상세에서 본 분석 상태를 번호표에 적는다. 목록이 이 값으로 "분석 완료/진행 중"을 보여준다.
     *
     * <p>번호표가 없으면 만들지 않는다 — {@link #upsertForDisplay} 와 같은 이유다.
     * {@code UNAVAILABLE} 은 적지 않고, 이미 같은 상태면 쓰지 않는다.
     * 실제로 바꿨을 때만 홈 목록 캐시를 비운다.
     *
     * <p>엔티티를 읽어 고치지 않고 두 칼럼만 지정해 UPDATE 한다
     * ({@link ProductRepository#updateAnalysisStatus}). 엔티티 저장은 읽은 시점의 다른 칼럼까지
     * 다시 써서, 동시에 들어온 검색의 표시 정보 저장과 서로 덮었다(#205).
     *
     * @return 상태를 바꿨으면 true
     */
    @Transactional
    @CacheEvict(value = "productList", allEntries = true, condition = "#result")
    public boolean recordAnalysisStatus(DataServerProductKey key, AnalysisStatus status) {
        if (status == null || status == AnalysisStatus.UNAVAILABLE) {
            return false;
        }
        return productRepository.updateAnalysisStatus(
                key.platform(), key.productId(), status.name(), LocalDateTime.now()) > 0;
    }

    /**
     * 검색 결과를 한 트랜잭션으로 저장하고 응답으로 바꾼다.
     *
     * <p>응답 변환까지 트랜잭션 안에서 한다. {@code ProductResponse.from} 이 구매 링크
     * 컬렉션을 읽는데, 트랜잭션 밖에서 읽으면 지연 로딩이 실패한다.
     *
     * <p>홈 목록 캐시를 비운다. 새 상품이 들어왔거나 가격이 바뀌었는데 캐시가 남아 있으면
     * 홈에 옛 값이 계속 보인다.
     */
    @Transactional
    @CacheEvict(value = "productList", allEntries = true)
    public java.util.List<ProductResponse> upsertAllForDisplay(java.util.List<DataServerProduct> sources) {
        return sources.stream()
                .map(this::upsertForDisplay)
                .map(ProductResponse::from)
                .toList();
    }

    /**
     * 이미 저장된 Data 상품 중 리뷰가 있는 것을 이름으로 찾아 응답으로 바꾼다.
     * 응답 변환이 구매 링크 컬렉션을 읽으므로 트랜잭션 안에서 바꾼다.
     */
    @Transactional(readOnly = true)
    public java.util.List<ProductResponse> findStoredForDisplay(String keyword, int limit) {
        return productRepository.findStoredByNameWithReviews(escapeLike(keyword.strip()), Limit.of(limit))
                .stream()
                .map(ProductResponse::from)
                .toList();
    }

    /** LIKE 의 특수 문자를 글자 그대로 찾게 바꾼다. 이스케이프 문자는 쿼리의 ESCAPE 와 맞춘다 */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 네트워크 조회가 끝난 페이지의 표시 정보와 분석 요약만 짧게 저장한다. */
    @Transactional
    @CacheEvict(value = "productList", allEntries = true)
    public int upsertCatalogPage(java.util.List<DataServerCatalogPage.Entry> entries) {
        int saved = 0;
        for (var entry : entries) {
            if (entry == null || entry.product() == null || entry.analysis() == null) continue;
            var p = entry.product();
            var a = entry.analysis();
            AnalysisStatus status = AnalysisStatus.from(a.status());
            if (!hasText(p.platform()) || !hasText(p.productId()) || status == AnalysisStatus.UNAVAILABLE) continue;
            if (a.reviewCount() == null || a.sourceReviewCount() == null || a.scoredReviewCount() == null
                    || a.reviewCount() < 0 || a.reviewCount() > 500 || a.scoredReviewCount() < 0
                    || a.scoredReviewCount() > a.reviewCount() || a.sourceReviewCount() < a.reviewCount()
                    || a.sampled() == null || a.sampled() != (a.sourceReviewCount() > a.reviewCount())) {
                log.warn("[DataCatalog] 분석 범위가 잘못된 상품을 건너뛴다 - {}/{}", p.platform(), p.productId());
                continue;
            }
            Double avg = status == AnalysisStatus.DONE ? a.avgScore() : null;
            if (status == AnalysisStatus.DONE && ((avg == null && a.scoredReviewCount() != 0)
                    || (avg != null && (!Double.isFinite(avg) || avg < 0 || avg > 100 || a.scoredReviewCount() == 0)))) {
                log.warn("[DataCatalog] 분석 평균이 잘못된 상품을 건너뛴다 - {}/{}", p.platform(), p.productId());
                continue;
            }
            upsertForDisplay(p);
            // v4 는 추가 필드라 값이 이상해도 상품을 건너뛰지 않고 v4 만 비운다(#255)
            boolean done = status == AnalysisStatus.DONE;
            Double avgV4 = done ? validIndex(a.avgPolicyIndexV4()) : null;
            DataServerCatalogPage.LevelCounts countsV4 = done ? validCounts(a.v4LevelCounts()) : null;
            productRepository.updateAnalysisSummary(p.platform(), p.productId(), status.name(),
                    LocalDateTime.now(), avg, a.sampled(), a.reviewCount(), a.sourceReviewCount(),
                    a.scoredReviewCount(),
                    avgV4,
                    countsV4 == null ? null : countsV4.safe(),
                    countsV4 == null ? null : countsV4.warn(),
                    countsV4 == null ? null : countsV4.danger());
            saved++;
        }
        return saved;
    }

    private static Double validIndex(Double index) {
        return index == null || !Double.isFinite(index) || index < 0 || index > 100 ? null : index;
    }

    /** 셋 중 하나라도 비었거나 음수면 통째로 버린다. 일부만 있는 분포는 화면이 잘못 그린다 */
    private static DataServerCatalogPage.LevelCounts validCounts(DataServerCatalogPage.LevelCounts counts) {
        if (counts == null || counts.safe() == null || counts.warn() == null || counts.danger() == null
                || counts.safe() < 0 || counts.warn() < 0 || counts.danger() < 0) {
            return null;
        }
        return counts;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Product create(DataServerProductKey key, String name) {
        Product tag = Product.builder()
                .id(allocateId(key))
                .name(name == null || name.isBlank() ? key.asExternalId() : name)
                // 화면 표기용. Data 서버 수집기 이름을 대문자로 옮긴다
                .platform(key.platform().toUpperCase())
                .dataPlatform(key.platform())
                .dataProductId(key.productId())
                // category·avgRti 는 비워둔다. Data 서버는 분석을 주지 않고,
                // 카테고리 문자열은 이 enum 으로 안전하게 옮길 수 없다
                .build();
        try {
            Product saved = productRepository.saveAndFlush(tag);
            log.info("[ProductTag] 번호표 생성 - key={}, id={}", key.asExternalId(), saved.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 상품을 찜한 요청이 둘 있었다. 유니크 제약이 하나만 통과시킨다.
            // 먼저 들어간 쪽을 다시 읽어 돌려준다.
            return productRepository
                    .findByDataPlatformAndDataProductId(key.platform(), key.productId())
                    .orElseThrow(() -> e);
        }
    }

    /**
     * 외부 식별자에서 ID 를 결정론적으로 뽑는다.
     *
     * <p>시퀀스를 쓰지 않는 이유: 같은 상품에 대한 동시 요청이 서로 다른 ID 로 두 행을
     * 만들려 하면 유니크 제약에 걸려 한쪽이 실패한다. 같은 키에서 늘 같은 ID 가 나오면
     * 재시도해도 같은 행을 가리키므로 그 경합이 사라진다. DB 시퀀스 생성 DDL 도 필요 없다.
     *
     * <p><b>상한은 2^52 이다.</b> 프론트가 Flutter 웹이라 JSON 숫자를 JS double 로 읽고,
     * 2^53 을 넘는 정수는 반올림된다. 처음에는 SHA-256 상위 63비트를 써서 10^18 대의 ID 가
     * 나왔는데, 그러면 브라우저가 받는 순간 값이 틀어져 그 ID 로 상세·찜을 부르면 엉뚱한
     * 번호가 간다. 상위 52비트만 쓴다. 상품 10만 개 기준 충돌 확률은 10^-6 수준이고,
     * 충돌하면 기본키 제약에 걸려 바로 드러난다.
     */
    static long allocateId(DataServerProductKey key) {
        byte[] digest = sha256(key.asExternalId());
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (digest[i] & 0xFFL);
        }
        value >>>= (64 - ID_BITS);   // 상위 52비트, 항상 양수
        if (value == 0) {
            value = 1;
        }
        if (value >= DUMMY_ID_FLOOR && value < DUMMY_ID_CEIL) {
            value += DUMMY_ID_CEIL - DUMMY_ID_FLOOR;
        }
        return value;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없는 환경이다", e);
        }
    }
}
