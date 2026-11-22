package com.example.fireview.domain.product.entity;

import com.example.fireview.domain.dataserver.dto.response.AnalysisStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Entity
@Table(name = "products",
        // 같은 Data 서버 상품이 Spring 쪽에 두 행으로 생기면 찜·장바구니가 갈라진다.
        // 두 칼럼 모두 NULL 인 기존 행끼리는 충돌하지 않는다(Postgres 는 NULL 을 서로 다르게 본다).
        uniqueConstraints = @UniqueConstraint(
                name = "uq_products_data_server_key",
                columnNames = {"data_platform", "data_product_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// 바뀐 칼럼만 UPDATE 한다. 표시 정보 저장(ProductTagRegistry.upsertForDisplay)과 분석 상태 기록이
// 같은 행에서 겹치면, 전체 칼럼 UPDATE 는 읽을 때의 옛 상태를 그대로 다시 써 방금 기록된 DONE 을
// null 로 되돌렸다(#205). 상태 칼럼에 updatable=false 를 거는 방법도 있지만, 그러면 엔티티에서
// 상태를 바꿔도 조용히 저장되지 않는 함정이 생겨 이쪽을 택했다. 상태 기록 자체는
// ProductRepository.updateAnalysisStatus 가 칼럼을 지정해 따로 쓴다.
@DynamicUpdate
public class Product {

    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    private String imageUrl;

    private Long price;

    /**
     * 카테고리. Data 서버에서 온 상품은 비어 있을 수 있다.
     *
     * <p>Data 서버의 category 는 "뷰티 인디 > 인디 스킨케어 > 인디 마스크/팩" 같은
     * 몰마다 다른 자유 문자열이다. 원문은 {@link #subCategory} 에 두고, 원문과 상품명으로
     * 분류할 수 있을 때만 이 값을 채운다(DataServerCategoryMapper). 근거가 없으면 null 이다.
     */
    @Enumerated(EnumType.STRING)
    private Category category;

    @Column(nullable = false)
    private String platform;

    /**
     * 상품 평균 RTI. <b>분석 전이면 null 이다.</b>
     *
     * <p>Data 서버는 원본 수집만 소유하고 신뢰도 분석을 주지 않는다. 값이 없을 때
     * 0 이나 50 같은 기본값을 넣으면 화면이 그 수치를 실제 분석 결과처럼 보여준다.
     * 모른다는 것은 모른다고 두는 편이 맞다.
     */
    private Double avgRti;

    private Integer reviewCount;

    private Double avgRating;

    /** 네이버 쇼핑 API의 productId (AI 서버 연동 시 식별자로 사용) */
    @Column(name = "naver_product_id")
    private String naverProductId;

    /**
     * Data 서버의 수집기 이름 (naver, kurly, elevenst ...). 소문자.
     *
     * <p>위의 {@link #platform} 과 다르다. platform 은 Spring 이 화면에 쓰는 표기("NAVER")이고
     * 이쪽은 Data 서버 주소의 일부다. 둘을 한 칼럼으로 합치면 표기를 바꿀 때마다
     * 외부 서버 호출이 깨진다.
     *
     * <p>{@link #naverProductId} 를 대체한다. 그쪽은 네이버만 가정한 칼럼이라
     * 쇼핑몰이 9곳으로 늘어난 지금은 상품을 특정하지 못한다.
     */
    @Column(name = "data_platform", length = 30)
    private String dataPlatform;

    /** Data 서버의 상품 ID. 쇼핑몰이 발급한 원본 값 */
    @Column(name = "data_product_id", length = 100)
    private String dataProductId;

    /** 소분류 (Naver category3 값, 예: "스마트폰", "이어폰") */
    @Column(name = "sub_category", length = 100)
    private String subCategory;

    private LocalDateTime createdAt;

    /**
     * v2 상세를 열 때 Data 서버에서 마지막으로 본 신뢰도 분석 상태. <b>아직 못 봤으면 null 이다.</b>
     *
     * <p>목록은 상품마다 Data 서버를 부를 수 없어 이 값을 그대로 보여준다. 원본은 Data 서버이고,
     * 카탈로그 동기화와 상세 조회가 갱신하며 최대 동기화 주기만큼 늦을 수 있다.
     * {@link AnalysisStatus#UNAVAILABLE}(Data 미도달·구버전)은 적지 않는다 — 일시 장애가
     * 마지막으로 본 상태를 지우면 안 된다.
     *
     * <p>운영은 {@code ddl-auto=update} 라 NOT NULL 칼럼을 기존 행에 붙이지 못한다. nullable 로 둔다.
     *
     * <p><b>enum 이 아니라 문자열 칼럼이다.</b> {@code @Enumerated(STRING)} 이면 Hibernate 가
     * PostgreSQL 에 {@code CHECK (analysis_status IN (...))} 를 붙이는데, {@code ddl-auto=update} 는
     * enum 에 값을 더해도 이 제약을 고치지 않아 새 상태 저장이 DB 에서 거부된다(#205).
     * 변환은 {@link #getAnalysisStatus()} 에서 한다. 읽기 전용 접근자만 두고 쓰기는
     * {@link #observeAnalysisStatus} 와 {@code ProductRepository.updateAnalysisStatus} 로만 한다.
     */
    @Column(name = "analysis_status", length = 20)
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private String analysisStatus;

    /** {@link #analysisStatus} 가 지금 값으로 바뀐 것을 처음 본 시각. 같은 상태를 다시 봐도 고치지 않는다 */
    @Column(name = "analysis_status_at")
    private LocalDateTime analysisStatusAt;

    /** 목록 RTI 평균의 표본 여부와 분석 당시 원본·선택 건수. 원본은 Data 서버다. */
    private Boolean analysisSampled;
    private Integer analysisReviewCount;
    private Integer analysisSourceReviewCount;

    /**
     * v4.0 텍스트 모델의 상품 평균 지수와 등급별 리뷰 수(#255). v4 가 아직 없으면 null.
     * v3.1 평균은 {@link #avgRti} 에 그대로 두어 되돌릴 수 있게 한다. 서로 대신 채우지 않는다.
     */
    private Double avgPolicyIndexV4;
    private Integer v4SafeCount;
    private Integer v4WarnCount;
    private Integer v4DangerCount;

    /** 멀티 플랫폼 구매 링크 (NAVER, COUPANG, 11ST 등) */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "product_platform_links",
                     joinColumns = @JoinColumn(name = "product_id"))
    @Builder.Default
    private List<PlatformLink> platformLinks = new ArrayList<>();

    /**
     * Data 서버에서 이 상품을 가리키는 주소가 있는지.
     *
     * <p>없으면 Spring 안에서만 존재하는 상품이다(더미, 또는 Data 서버 연동 전에 만든 행).
     */
    public boolean hasDataServerAddress() {
        return dataPlatform != null && !dataPlatform.isBlank()
                && dataProductId != null && !dataProductId.isBlank();
    }

    /**
     * Data 서버 주소를 {@code "{platform}-{productId}"} 한 문자열로.
     * 챗봇 세션이나 알림 링크처럼 식별자를 한 칸에 담아야 하는 자리에서 쓴다.
     *
     * @return 주소가 없으면 null
     */
    public String dataServerExternalId() {
        return hasDataServerAddress() ? dataPlatform + "-" + dataProductId : null;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (platform == null) platform = "NAVER";
        // avgRti 는 채우지 않는다. 예전에는 NOT NULL 이라 50.0 을 넣었는데, 그러면 분석도
        // 하지 않은 상품에 신뢰도 50점이 붙어 화면이 그 수치를 실제 분석 결과처럼 보여준다.
        // 모르는 값은 null 로 둔다.
        if (reviewCount == null) reviewCount = 0;
        if (avgRating == null) avgRating = 0.0;
    }

    /**
     * 저장된 분석 상태. 못 봤으면 null 이다.
     *
     * <p>이 버전이 모르는 저장값(새 버전이 적은 상태, 손으로 고친 값)도 null 로 읽는다.
     * 모르는 값으로 예외를 내면 그 상품이 들어간 목록 전체가 깨진다.
     */
    public AnalysisStatus getAnalysisStatus() {
        if (analysisStatus == null) return null;
        for (AnalysisStatus status : AnalysisStatus.values()) {
            if (status.name().equals(analysisStatus)) return status;
        }
        return null;
    }

    /**
     * Data 서버에서 본 분석 상태를 이 엔티티에 적는다.
     *
     * <p>v2 상세 경로는 이 메서드가 아니라 {@code ProductTagRegistry.recordAnalysisStatus} 로
     * 칼럼만 지정해 쓴다. 읽어 둔 엔티티를 거치면 그 사이 다른 요청이 쓴 값과 경합한다.
     *
     * @return 값을 바꿨으면 true. 같은 상태거나 적지 않는 값({@code null}, {@code UNAVAILABLE})이면
     *         false 이고 아무것도 바꾸지 않는다 — 바뀐 필드가 없으면 UPDATE 도 나가지 않는다
     */
    public boolean observeAnalysisStatus(AnalysisStatus status, LocalDateTime observedAt) {
        if (status == null || status == AnalysisStatus.UNAVAILABLE || status.name().equals(analysisStatus)) {
            return false;
        }
        this.analysisStatus = status.name();
        this.analysisStatusAt = observedAt;
        return true;
    }

    /** AI 서버 분석 결과로 평균 RTI 업데이트 */
    public void updateAvgRti(double newAvgRti) {
        this.avgRti = newAvgRti;
    }

    /** AI 서버 분석 결과로 리뷰 수 업데이트 */
    public void updateReviewCount(int count) {
        this.reviewCount = count;
    }

    /** 최저가 반환 (platformLinks에서 가장 낮은 가격, 없으면 기본 price) */
    public Long getLowestPrice() {
        return platformLinks.stream()
                .filter(l -> l.getPrice() != null)
                .min(Comparator.comparingLong(PlatformLink::getPrice))
                .map(PlatformLink::getPrice)
                .orElse(price);
    }

    /** 최저가 플랫폼 이름 반환 */
    public String getLowestPlatform() {
        return platformLinks.stream()
                .filter(l -> l.getPrice() != null)
                .min(Comparator.comparingLong(PlatformLink::getPrice))
                .map(PlatformLink::getPlatform)
                .orElse(platform);
    }
}
