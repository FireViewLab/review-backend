package com.example.fireview.domain.product.repository;

import com.example.fireview.domain.product.entity.Category;
import com.example.fireview.domain.product.entity.Product;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByCategory(Category category);
    List<Product> findByCategoryIn(List<Category> categories);
    /**
     * 대시보드 추천. 분석된 상품을 RTI 높은 순으로 먼저, 모자라면 분석 전 상품으로 채운다.
     * PostgreSQL 은 DESC 에서 NULL 을 앞에 두므로 NULL 을 뒤로 보내는 정렬을 명시한다(#224).
     * NULLS LAST 는 Hibernate 가 방언 기본 순서와 같다고 보면 SQL 에서 빼 버려 CASE 로 쓴다.
     */
    @Query("""
            SELECT p FROM Product p
            ORDER BY CASE WHEN p.avgRti IS NULL THEN 1 ELSE 0 END, p.avgRti DESC, p.id DESC
            """)
    List<Product> findRecommendedProducts(Limit limit);
    List<Product> findByAvgRtiLessThanOrderByAvgRtiAsc(double threshold);
    // platformLinks를 JOIN FETCH로 한 번에 로딩 → LazyInitializationException 방지
    @Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.platformLinks WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Product> findByNameContainingIgnoreCaseWithLinks(@Param("keyword") String keyword);

    @Query("SELECT p FROM Product p WHERE p.avgRti < :threshold ORDER BY p.avgRti ASC")
    List<Product> findRiskyProducts(double threshold);

    /**
     * 이미 저장된 Data 서버 상품을 이름으로 찾는다. 리뷰가 저장된 상품만, 분석 완료·리뷰 많은 순.
     *
     * <p>실시간 몰 검색({@code GET /{platform}/search})은 수집이 멈추면 거절되지만, Data 에는 이미 모은
     * 상품·리뷰가 남아 있다. 그 상품은 카탈로그 동기화로 이 테이블에도 있다.
     * {@code keyword} 의 {@code %}·{@code _}·{@code \} 는 호출자가 이스케이프한다.
     */
    @Query("""
            SELECT p FROM Product p
            WHERE p.dataPlatform IS NOT NULL AND p.analysisSourceReviewCount > 0
              AND LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\'
            ORDER BY CASE WHEN p.analysisStatus = 'DONE' THEN 0 ELSE 1 END,
                p.analysisSourceReviewCount DESC, p.id DESC
            """)
    List<Product> findStoredByNameWithReviews(@Param("keyword") String keyword, Limit limit);

    /** Data 서버 상품이 하나라도 있는지. 없으면 대시보드가 기존 더미로 채운다 */
    boolean existsByDataPlatformIsNotNull();

    /**
     * 대시보드 추천. Data 서버 상품 중 분석이 끝나고 점수가 나온 리뷰가 {@code minReviews} 건 이상인 것만,
     * RTI 높은 순. 분석 전 상품으로 빈자리를 채우지 않는다 — 검색으로 막 등록된 상품은 리뷰가 아직
     * 없을 수 있고, 리뷰 한두 건으로 낸 평균은 상품 신뢰도로 보이기 어렵다.
     */
    @Query("""
            SELECT p FROM Product p
            WHERE p.dataPlatform IS NOT NULL AND p.analysisStatus = 'DONE'
              AND p.avgRti IS NOT NULL AND p.analysisScoredReviewCount >= :minReviews
            ORDER BY p.avgRti DESC, p.analysisScoredReviewCount DESC, p.id DESC
            """)
    List<Product> findDashboardRecommended(@Param("minReviews") int minReviews, Limit limit);

    /** {@link #findDashboardRecommended} 를 선호 카테고리와 최소 신뢰도로 좁힌 것 */
    @Query("""
            SELECT p FROM Product p
            WHERE p.dataPlatform IS NOT NULL AND p.analysisStatus = 'DONE'
              AND p.avgRti IS NOT NULL AND p.analysisScoredReviewCount >= :minReviews
              AND p.category IN :categories AND p.avgRti >= :minScore
            ORDER BY p.avgRti DESC, p.analysisScoredReviewCount DESC, p.id DESC
            """)
    List<Product> findDashboardRecommendedIn(@Param("categories") Collection<Category> categories,
                                             @Param("minScore") double minScore,
                                             @Param("minReviews") int minReviews, Limit limit);

    /** 대시보드 위험 상품. 조건은 {@link #findDashboardRecommended} 와 같고 RTI 낮은 순 */
    @Query("""
            SELECT p FROM Product p
            WHERE p.dataPlatform IS NOT NULL AND p.analysisStatus = 'DONE'
              AND p.avgRti IS NOT NULL AND p.analysisScoredReviewCount >= :minReviews
              AND p.avgRti < :threshold
            ORDER BY p.avgRti ASC, p.analysisScoredReviewCount DESC, p.id DESC
            """)
    List<Product> findDashboardRisky(@Param("threshold") double threshold,
                                     @Param("minReviews") int minReviews, Limit limit);

    Optional<Product> findByNaverProductId(String naverProductId);

    /**
     * Data 서버 주소로 Spring 쪽 상품을 찾는다.
     *
     * <p>Data 서버는 {@code (platform, product_id)} 복합키를 쓰는데 찜·장바구니·조회이력은
     * Spring 의 Long id 를 FK 로 물고 있다. 이 조회가 그 사이를 잇는 번호표 역할을 한다.
     */
    Optional<Product> findByDataPlatformAndDataProductId(String dataPlatform, String dataProductId);

    /**
     * 여러 Data 주소의 번호표를 한 번에 읽는다. 대화 목록처럼 상품 여러 개에 이름을 붙일 때 쓴다(#275).
     * 플랫폼·상품 ID 를 따로 IN 으로 거르므로 짝이 맞지 않는 행이 섞일 수 있다. 호출하는 쪽이 짝을 맞춰 고른다.
     */
    @Query("SELECT p FROM Product p WHERE p.dataPlatform IN :platforms AND p.dataProductId IN :productIds")
    List<Product> findByDataPlatformInAndDataProductIdIn(@Param("platforms") Collection<String> platforms,
                                                         @Param("productIds") Collection<String> productIds);

    /**
     * 번호표의 분석 상태 두 칼럼만 바꾼다. 상태가 이미 같으면 쓰지 않는다.
     *
     * <p>엔티티를 읽어 고친 뒤 저장하면, 그 사이 표시 정보 저장이 같은 행을 쓴 경우 서로의 값을
     * 덮는다(#205). 칼럼을 지정한 UPDATE 는 다른 칼럼을 건드리지 않고, 조건이 DB 에서 평가되므로
     * 읽은 시점의 값에 기대지 않는다. 같은 상태 칼럼을 두 요청이 동시에 바꾸면 나중 커밋이 남는다.
     *
     * <p>이 쿼리는 영속성 컨텍스트를 거치지 않는다. 같은 트랜잭션에서 이미 읽은 엔티티는 옛 상태를
     * 들고 있지만, {@link Product} 가 바뀐 칼럼만 UPDATE 하므로 그 엔티티를 저장해도 이 값을 덮지 않는다.
     *
     * @param status {@code AnalysisStatus#name()}
     * @return 바꾼 행 수. 번호표가 없거나 같은 상태면 0
     */
    @Modifying
    @Query("""
            UPDATE Product p
            SET p.analysisStatus = :status, p.analysisStatusAt = :observedAt,
                p.avgRti = CASE WHEN :status = 'DONE' THEN p.avgRti ELSE NULL END,
                p.avgPolicyIndexV4 = CASE WHEN :status = 'DONE' THEN p.avgPolicyIndexV4 ELSE NULL END,
                p.v4SafeCount = CASE WHEN :status = 'DONE' THEN p.v4SafeCount ELSE NULL END,
                p.v4WarnCount = CASE WHEN :status = 'DONE' THEN p.v4WarnCount ELSE NULL END,
                p.v4DangerCount = CASE WHEN :status = 'DONE' THEN p.v4DangerCount ELSE NULL END
            WHERE p.dataPlatform = :dataPlatform AND p.dataProductId = :dataProductId
              AND (p.analysisStatus IS NULL OR p.analysisStatus <> :status)
            """)
    int updateAnalysisStatus(@Param("dataPlatform") String dataPlatform,
                             @Param("dataProductId") String dataProductId,
                             @Param("status") String status,
                             @Param("observedAt") LocalDateTime observedAt);

    /** 목록용 분석 요약만 갱신해 동시 검색의 표시 정보와 충돌하지 않는다. */
    @Modifying
    @Query("""
            UPDATE Product p SET p.analysisStatus = :status, p.analysisStatusAt = :observedAt,
                p.avgRti = :avgRti, p.analysisSampled = :sampled,
                p.analysisReviewCount = :reviewCount, p.analysisSourceReviewCount = :sourceReviewCount,
                p.analysisScoredReviewCount = :scoredReviewCount,
                p.avgPolicyIndexV4 = :avgPolicyIndexV4, p.v4SafeCount = :v4SafeCount,
                p.v4WarnCount = :v4WarnCount, p.v4DangerCount = :v4DangerCount
            WHERE p.dataPlatform = :dataPlatform AND p.dataProductId = :dataProductId
            """)
    int updateAnalysisSummary(@Param("dataPlatform") String platform,
            @Param("dataProductId") String productId, @Param("status") String status,
            @Param("observedAt") LocalDateTime observedAt, @Param("avgRti") Double avgRti,
            @Param("sampled") Boolean sampled, @Param("reviewCount") Integer reviewCount,
            @Param("sourceReviewCount") Integer sourceReviewCount,
            @Param("scoredReviewCount") Integer scoredReviewCount,
            @Param("avgPolicyIndexV4") Double avgPolicyIndexV4, @Param("v4SafeCount") Integer v4SafeCount,
            @Param("v4WarnCount") Integer v4WarnCount, @Param("v4DangerCount") Integer v4DangerCount);

    /** 홈 목록 후보. Data 서버 상품만, 최근에 들어온 순. 이 중에서 분야를 섞어 고른다 */
    List<Product> findTop300ByDataPlatformIsNotNullOrderByCreatedAtDesc();

    /** 분석 완료 상품을 먼저 가져와 새 검색 상품이 홈 전체를 대기로 바꾸지 않게 한다. */
    @Query("""
            SELECT p FROM Product p WHERE p.dataPlatform IS NOT NULL
            ORDER BY CASE WHEN p.analysisStatus = 'DONE' THEN 0 ELSE 1 END,
                p.createdAt DESC, p.id DESC
            """)
    List<Product> findHomeCatalogCandidates(Pageable pageable);

    /**
     * 카테고리 화면 목록(#248). Data 서버 상품만, 홈과 같은 순서(분석 완료 먼저, 최근 순)로 한 페이지씩.
     * 분류하지 못한 상품(category null)은 어느 카테고리에도 들지 않는다.
     */
    @Query(value = """
            SELECT p FROM Product p WHERE p.dataPlatform IS NOT NULL AND p.category IN :categories
            ORDER BY CASE WHEN p.analysisStatus = 'DONE' THEN 0 ELSE 1 END,
                p.createdAt DESC, p.id DESC
            """,
            countQuery = """
            SELECT COUNT(p) FROM Product p WHERE p.dataPlatform IS NOT NULL AND p.category IN :categories
            """)
    Page<Product> findCatalogByCategoryIn(@Param("categories") Collection<Category> categories, Pageable pageable);

    /** 카테고리를 고르지 않은 전체 목록. 순서는 {@link #findCatalogByCategoryIn} 과 같다 */
    @Query(value = """
            SELECT p FROM Product p WHERE p.dataPlatform IS NOT NULL
            ORDER BY CASE WHEN p.analysisStatus = 'DONE' THEN 0 ELSE 1 END,
                p.createdAt DESC, p.id DESC
            """,
            countQuery = "SELECT COUNT(p) FROM Product p WHERE p.dataPlatform IS NOT NULL")
    Page<Product> findCatalog(Pageable pageable);

    /**
     * 챗봇 추천 후보. 같은 카테고리의 Data 서버 상품 중 가격이 범위 안인 것을 리뷰 많은 순으로.
     *
     * <p>번호표(data_platform, data_product_id)가 없거나 공백뿐인 행은 상세 화면을 열 수 없으므로
     * 뺀다({@link Product#hasDataServerAddress()} 와 같은 뜻). LIMIT 전에 빠져야 그 자리를 다른
     * 유효 후보가 채우므로 쿼리에서 거른다. SQL TRIM 은 스페이스만 지우므로 탭·개행만 있는 값은
     * 호출자가 한 번 더 거른다.
     * 가격이 없는 상품은 BETWEEN 에 걸리지 않아 자연히 빠진다.
     */
    @Query("""
            SELECT p FROM Product p
            WHERE p.category = :category
              AND p.id <> :excludeId
              AND p.dataPlatform IS NOT NULL AND TRIM(p.dataPlatform) <> ''
              AND p.dataProductId IS NOT NULL AND TRIM(p.dataProductId) <> ''
              AND p.price BETWEEN :minPrice AND :maxPrice
            ORDER BY p.reviewCount DESC NULLS LAST, p.id ASC
            """)
    List<Product> findRecommendationCandidatesInPriceRange(@Param("category") Category category,
                                                           @Param("excludeId") Long excludeId,
                                                           @Param("minPrice") long minPrice,
                                                           @Param("maxPrice") long maxPrice,
                                                           Limit limit);

    /** 기준 상품 가격을 모를 때의 추천 후보. 가격 조건만 빠지고 나머지는 위와 같다 */
    @Query("""
            SELECT p FROM Product p
            WHERE p.category = :category
              AND p.id <> :excludeId
              AND p.dataPlatform IS NOT NULL AND TRIM(p.dataPlatform) <> ''
              AND p.dataProductId IS NOT NULL AND TRIM(p.dataProductId) <> ''
            ORDER BY p.reviewCount DESC NULLS LAST, p.id ASC
            """)
    List<Product> findRecommendationCandidates(@Param("category") Category category,
                                               @Param("excludeId") Long excludeId,
                                               Limit limit);
}
