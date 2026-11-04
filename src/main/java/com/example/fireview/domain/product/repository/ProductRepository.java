package com.example.fireview.domain.product.repository;

import com.example.fireview.domain.product.entity.Category;
import com.example.fireview.domain.product.entity.Product;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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

    Optional<Product> findByNaverProductId(String naverProductId);

    /**
     * Data 서버 주소로 Spring 쪽 상품을 찾는다.
     *
     * <p>Data 서버는 {@code (platform, product_id)} 복합키를 쓰는데 찜·장바구니·조회이력은
     * Spring 의 Long id 를 FK 로 물고 있다. 이 조회가 그 사이를 잇는 번호표 역할을 한다.
     */
    Optional<Product> findByDataPlatformAndDataProductId(String dataPlatform, String dataProductId);

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
                p.avgRti = CASE WHEN :status = 'DONE' THEN p.avgRti ELSE NULL END
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
                p.analysisReviewCount = :reviewCount, p.analysisSourceReviewCount = :sourceReviewCount
            WHERE p.dataPlatform = :dataPlatform AND p.dataProductId = :dataProductId
            """)
    int updateAnalysisSummary(@Param("dataPlatform") String platform,
            @Param("dataProductId") String productId, @Param("status") String status,
            @Param("observedAt") LocalDateTime observedAt, @Param("avgRti") Double avgRti,
            @Param("sampled") Boolean sampled, @Param("reviewCount") Integer reviewCount,
            @Param("sourceReviewCount") Integer sourceReviewCount);

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
