package com.example.fireview.domain.reviewsummary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 상품 하나의 AI 리뷰 요약(#277). Data 서버 상품을 {@code (platform, product_id)} 로 가리킨다.
 *
 * <p>번호표({@code products})에 붙이지 않는다. 번호표는 찜처럼 Spring 쪽 기록이 필요할 때만 생기는데,
 * 요약은 상세를 열기만 해도 만든다.
 *
 * <p>요약 본문과 마지막 시도를 따로 적는다. 다시 만들다 실패해도 이전 요약은 그대로 보여 준다.
 * 같은 분석 입력({@code input_hash})으로 실패했거나 리뷰가 모자랐으면 입력이 바뀔 때까지(실패는 잠시 뒤까지)
 * 다시 시도하지 않는다 — 상세를 열 때마다 LLM 을 부르지 않게 한다.
 */
@Entity
@Table(name = "product_review_summaries",
        uniqueConstraints = @UniqueConstraint(name = "uk_review_summary_product",
                columnNames = {"data_platform", "data_product_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductReviewSummary {

    /** 요약을 만들었다 */
    public static final String DONE = "DONE";
    /** 요약할 리뷰가 모자랐다. 입력이 바뀔 때까지 다시 시도하지 않는다 */
    public static final String INSUFFICIENT = "INSUFFICIENT";
    /** LLM 호출이나 응답 해석에 실패했다. 잠시 뒤 다시 시도한다 */
    public static final String FAILED = "FAILED";

    private static final String SEPARATOR = "\n";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "data_platform", nullable = false, length = 30)
    private String dataPlatform;

    @Column(name = "data_product_id", nullable = false, length = 100)
    private String dataProductId;

    /** 장점. 항목마다 한 줄. 요약이 아직 없으면 null */
    @Column(length = 1000)
    private String pros;

    /** 아쉬운 점. 항목마다 한 줄. 리뷰에 없으면 빈 문자열 */
    @Column(length = 1000)
    private String cons;

    /** 요약에 넣은 리뷰 수 */
    private Integer sourceReviewCount;

    /** 요약을 만든 분석 입력 해시 */
    @Column(length = 100)
    private String inputHash;

    @Column(length = 100)
    private String model;

    private LocalDateTime generatedAt;

    @Column(length = 100)
    private String lastAttemptHash;

    @Column(length = 20)
    private String lastAttemptStatus;

    private LocalDateTime lastAttemptAt;

    public ProductReviewSummary(String dataPlatform, String dataProductId) {
        this.dataPlatform = dataPlatform;
        this.dataProductId = dataProductId;
    }

    public boolean hasSummary() {
        return pros != null;
    }

    public List<String> prosList() {
        return lines(pros);
    }

    public List<String> consList() {
        return lines(cons);
    }

    public void recordSummary(List<String> pros, List<String> cons, int sourceReviewCount,
                              String inputHash, String model, LocalDateTime now) {
        this.pros = String.join(SEPARATOR, pros);
        this.cons = String.join(SEPARATOR, cons);
        this.sourceReviewCount = sourceReviewCount;
        this.inputHash = inputHash;
        this.model = model;
        this.generatedAt = now;
        recordAttempt(inputHash, DONE, now);
    }

    public void recordAttempt(String inputHash, String status, LocalDateTime now) {
        this.lastAttemptHash = inputHash;
        this.lastAttemptStatus = status;
        this.lastAttemptAt = now;
    }

    private static List<String> lines(String joined) {
        if (joined == null || joined.isEmpty()) return List.of();
        return Arrays.stream(joined.split(SEPARATOR)).filter(s -> !s.isBlank()).toList();
    }
}
