package com.example.fireview.domain.chat.port;

import com.example.fireview.domain.dataserver.DataServerFixtures;
import com.example.fireview.domain.dataserver.DataServerProductKey;
import com.example.fireview.domain.dataserver.client.DataServerClient;
import com.example.fireview.domain.dataserver.dto.DataServerAnalysis;
import com.example.fireview.domain.dataserver.dto.DataServerJob;
import com.example.fireview.domain.dataserver.dto.DataServerProduct;
import com.example.fireview.domain.dataserver.dto.DataServerProductResponse;
import com.example.fireview.domain.dataserver.dto.DataServerReview;
import com.example.fireview.domain.dataserver.dto.response.AnalysisStatus;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataServerProductAnalysisAdapterTest {

    private static final String EXTERNAL_ID = "kurly-1000146248";

    @Mock DataServerClient dataServerClient;
    @Mock ProductRepository productRepository;
    @InjectMocks DataServerProductAnalysisAdapter adapter;

    private static DataServerProduct product(Integer reviewCount) {
        return new DataServerProduct("kurly", "1000146248", "샘플 상품",
                "https://kurly.com/1000146248", "브랜드", null, null,
                29900, "https://img", "식품 > 간편식", reviewCount, 4.5, "2026-10-05T00:00:00+09:00");
    }

    private static DataServerReview review(String content, Double rating) {
        return new DataServerReview("r-1", content, rating, "user**",
                "2026-10-01T10:00:00+09:00", null, List.of(), 3);
    }

    private static DataServerProductResponse ok(String status, DataServerProduct p,
                                                List<DataServerReview> reviews) {
        return new DataServerProductResponse(status, p,
                new DataServerProductResponse.Reviews(reviews, null), null, null);
    }

    @Test
    void 상품과_리뷰를_컨텍스트로_옮긴다() {
        when(dataServerClient.findProduct(any()))
                .thenReturn(Optional.of(ok("fresh", product(128),
                        List.of(review("맛있어요", 5.0), review("그저 그래요", 3.0)))));

        ProductAnalysisContext ctx = adapter.findContext(EXTERNAL_ID).orElseThrow();

        assertThat(ctx.productId()).isEqualTo(EXTERNAL_ID);
        assertThat(ctx.productName()).isEqualTo("샘플 상품");
        assertThat(ctx.price()).isEqualTo(29900);
        assertThat(ctx.category()).isEqualTo("식품 > 간편식");
        assertThat(ctx.totalReviews()).isEqualTo(128);
        assertThat(ctx.sampleReviews()).hasSize(2);
        assertThat(ctx.sampleReviews().get(0).content()).isEqualTo("맛있어요");
    }

    @Test
    void analysis가_없으면_분석_결과를_채우지_않는다() {
        // 구버전 Data 서버는 analysis 를 안 준다. 0 이나 임의값을 넣으면 모델이 사실처럼 말한다
        when(dataServerClient.findProduct(any()))
                .thenReturn(Optional.of(ok("fresh", product(128), List.of(review("맛있어요", 5.0)))));

        ProductAnalysisContext ctx = adapter.findContext(EXTERNAL_ID).orElseThrow();

        assertThat(ctx.averageRti()).isNull();
        assertThat(ctx.trustGrade()).isNull();
        assertThat(ctx.pros()).isEmpty();
        assertThat(ctx.cons()).isEmpty();
        assertThat(ctx.trustSignals()).isEmpty();
        assertThat(ctx.sampleReviews()).allSatisfy(s -> {
            assertThat(s.rti()).isNull();
            assertThat(s.grade()).isNull();
        });
        // 세이프가드 4계층이 쓰는 목록이 비어야 모든 수치가 '근거 없음'으로 걸린다
        assertThat(ctx.knownRtiValues()).isEmpty();
    }

    @Test
    void stale도_정상으로_쓴다() {
        // TTL 이 지났을 뿐 쓸 수 있는 데이터다. 실패로 다루면 화면이 크롤링 속도에 묶인다
        when(dataServerClient.findProduct(any()))
                .thenReturn(Optional.of(ok("stale", product(50), List.of(review("좋아요", 4.0)))));

        assertThat(adapter.findContext(EXTERNAL_ID)).isPresent();
    }

    @Test
    void queued면_컨텍스트가_없다() {
        // 아직 수집 전. 빈 컨텍스트로 가야 챗봇이 "아직 모른다"고 답한다
        DataServerProductResponse queued = new DataServerProductResponse(
                "queued", null, null, new DataServerJob(7L, "kurly", "1000146248",
                "pending", "pending", "pending", null), null);
        when(dataServerClient.findProduct(any())).thenReturn(Optional.of(queued));

        assertThat(adapter.findContext(EXTERNAL_ID)).isEmpty();
    }

    @Test
    void 조회가_실패하면_컨텍스트가_없다() {
        // 401·404·타임아웃. 챗봇이 죽지 않고 모른다고 답한다
        when(dataServerClient.findProduct(any())).thenReturn(Optional.empty());

        assertThat(adapter.findContext(EXTERNAL_ID)).isEmpty();
    }

    @Test
    void 식별자_형식이_아니면_호출하지_않는다() {
        assertThat(adapter.findContext("7195971829")).isEmpty();
        assertThat(adapter.findContext(null)).isEmpty();

        verify(dataServerClient, never()).findProduct(any(DataServerProductKey.class));
    }

    @Test
    void 리뷰수를_모르면_받아온_건수로_대신한다() {
        when(dataServerClient.findProduct(any()))
                .thenReturn(Optional.of(ok("fresh", product(null),
                        List.of(review("A", 5.0), review("B", 4.0), review("C", 3.0)))));

        assertThat(adapter.findContext(EXTERNAL_ID).orElseThrow().totalReviews()).isEqualTo(3);
    }

    @Test
    void 소수점_평점은_반올림한다() {
        when(dataServerClient.findProduct(any()))
                .thenReturn(Optional.of(ok("fresh", product(10), List.of(review("좋아요", 4.5)))));

        assertThat(adapter.findContext(EXTERNAL_ID).orElseThrow()
                .sampleReviews().get(0).rating()).isEqualTo(5);
    }

    @Test
    void 본문이_빈_리뷰는_빼고_다섯_건까지만_싣는다() {
        when(dataServerClient.findProduct(any()))
                .thenReturn(Optional.of(ok("fresh", product(99), List.of(
                        review("1", 5.0), review("  ", 5.0), review("2", 5.0), review(null, 5.0),
                        review("3", 5.0), review("4", 5.0), review("5", 5.0), review("6", 5.0)))));

        assertThat(adapter.findContext(EXTERNAL_ID).orElseThrow().sampleReviews())
                .hasSize(5)
                .extracting(ProductAnalysisContext.SampleReview::content)
                .containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void 대표_리뷰에_리뷰별_RTI와_등급을_붙인다() {
        when(dataServerClient.findProduct(any()))
                .thenReturn(Optional.of(DataServerFixtures.load("product-analysis-done.json")));

        ProductAnalysisContext ctx = adapter.findContext(EXTERNAL_ID).orElseThrow();

        assertThat(ctx.sampleReviews()).hasSize(3);
        ProductAnalysisContext.SampleReview r1 = ctx.sampleReviews().get(0);
        assertThat(r1.rti()).isEqualTo(82.5);
        assertThat(r1.grade()).isEqualTo("안전");   // 프롬프트 어휘(안전/주의/위험)
        // 결과가 없는 리뷰(r-2)와 계산 불가(r-3)는 비워 둔다
        assertThat(ctx.sampleReviews().get(1).rti()).isNull();
        assertThat(ctx.sampleReviews().get(1).grade()).isNull();
        assertThat(ctx.sampleReviews().get(2).rti()).isNull();
        assertThat(ctx.sampleReviews().get(2).grade()).isNull();
        // 세이프가드 4계층은 실제로 받은 리뷰 RTI 만 근거로 인정한다
        assertThat(ctx.knownRtiValues()).containsExactly(82.5);
    }

    @Test
    void 리뷰_점수로_상품_등급을_만들지_않는다() {
        // 결과는 한 페이지 것뿐이다. 평균을 내면 상품 전체 값처럼 오해된다
        when(dataServerClient.findProduct(any()))
                .thenReturn(Optional.of(DataServerFixtures.load("product-analysis-done.json")));

        ProductAnalysisContext ctx = adapter.findContext(EXTERNAL_ID).orElseThrow();

        assertThat(ctx.averageRti()).isNull();
        assertThat(ctx.trustGrade()).isNull();
        assertThat(ctx.pros()).isEmpty();
        assertThat(ctx.cons()).isEmpty();
        assertThat(ctx.trustSignals()).isEmpty();
    }

    @Test
    void level_어휘를_한국어로_옮긴다() throws Exception {
        DataServerProductResponse body = DataServerFixtures.MAPPER.readValue("""
                {"status": "fresh",
                 "product": {"platform": "kurly", "product_id": "1", "name": "상품", "review_count": 50},
                 "reviews": {"items": [
                   {"review_id": "a", "content": "좋아요"},
                   {"review_id": "b", "content": "보통"},
                   {"review_id": "c", "content": "별로"},
                   {"review_id": "d", "content": "이상한 값"}
                 ]},
                 "analysis": {"status": "done", "review_count": 4, "results": [
                   {"review_id": "a", "rti": 75.0, "level": "safe", "reasons": []},
                   {"review_id": "b", "rti": 55.0, "level": "warn", "reasons": []},
                   {"review_id": "c", "rti": 20.0, "level": "danger", "reasons": []},
                   {"review_id": "d", "rti": 60.0, "level": "unknown", "reasons": []}
                 ]}}
                """, DataServerProductResponse.class);
        when(dataServerClient.findProduct(any())).thenReturn(Optional.of(body));

        ProductAnalysisContext ctx = adapter.findContext("kurly-1").orElseThrow();

        assertThat(ctx.sampleReviews()).extracting(ProductAnalysisContext.SampleReview::grade)
                .containsExactly("안전", "주의", "위험", null);
    }

    private static List<DataServerReview> page(int size) {
        return IntStream.range(0, size).mapToObj(i -> review("리뷰 " + i, 5.0)).toList();
    }

    private static DataServerAnalysis analysis(String status, Integer reviewCount) {
        return new DataServerAnalysis(status, null, "hash", "model", "rti-v0.1",
                reviewCount, false, reviewCount, List.of());
    }

    private static DataServerProductResponse withAnalysis(DataServerProduct p, List<DataServerReview> reviews,
                                                          DataServerAnalysis analysis) {
        return new DataServerProductResponse("fresh", p,
                new DataServerProductResponse.Reviews(reviews, "next"), null, analysis);
    }

    /** 카탈로그 동기화가 적어 둔 상품 */
    private static Product saved(AnalysisStatus status, Double avgRti, Integer analysisReviewCount,
                                 Integer mallReviewCount) {
        Product p = Product.builder().id(1L).name("샘플 상품")
                .dataPlatform("kurly").dataProductId("1000146248")
                .avgRti(avgRti).analysisReviewCount(analysisReviewCount).reviewCount(mallReviewCount)
                .build();
        p.observeAnalysisStatus(status, LocalDateTime.now());
        return p;
    }

    private void savedAs(Product p) {
        when(productRepository.findByDataPlatformAndDataProductId("kurly", "1000146248"))
                .thenReturn(Optional.of(p));
    }

    @Test
    void 분석이_끝났으면_분석에_넣은_리뷰_수를_쓴다() {
        // #215: 올리브영처럼 상세 응답이 리뷰 수를 비워 보내면 한 페이지(20)로 떨어져 '판단 보류'가 걸렸다
        when(dataServerClient.findProduct(any()))
                .thenReturn(Optional.of(withAnalysis(product(null), page(20), analysis("done", 208))));

        ProductAnalysisContext ctx = adapter.findContext(EXTERNAL_ID).orElseThrow();

        assertThat(ctx.totalReviews()).isEqualTo(208);
        assertThat(ctx.isSampleTooSmall()).isFalse();
    }

    @Test
    void 분석이_끝나지_않았으면_상세_응답의_입력_수를_쓰지_않는다() {
        // running 의 review_count 는 이번 job 의 입력 수일 뿐 완료된 결과가 아니다
        when(dataServerClient.findProduct(any()))
                .thenReturn(Optional.of(withAnalysis(product(null), page(20), analysis("running", 208))));

        assertThat(adapter.findContext(EXTERNAL_ID).orElseThrow().totalReviews()).isEqualTo(20);
    }

    @Test
    void 저장된_분석_평균과_등급을_싣는다() {
        // 상품 화면의 평균 RTI 와 같은 값. 쇼핑몰 리뷰 수는 목록에서 적어 둔 값으로 보충한다
        when(dataServerClient.findProduct(any()))
                .thenReturn(Optional.of(withAnalysis(product(null), page(20), analysis("done", 208))));
        savedAs(saved(AnalysisStatus.DONE, 95.5, 208, 5305));

        ProductAnalysisContext ctx = adapter.findContext(EXTERNAL_ID).orElseThrow();

        assertThat(ctx.averageRti()).isEqualTo(95.5);
        assertThat(ctx.trustGrade()).isEqualTo("안전");
        assertThat(ctx.totalReviews()).isEqualTo(208);
        assertThat(ctx.mallReviewCount()).isEqualTo(5305);
        assertThat(ctx.knownRtiValues()).contains(95.5);
    }

    @Test
    void 등급은_70_40_경계로_매긴다() {
        when(dataServerClient.findProduct(any()))
                .thenReturn(Optional.of(withAnalysis(product(null), page(3), analysis("done", 120))));
        savedAs(saved(AnalysisStatus.DONE, 55.0, 120, null));

        assertThat(adapter.findContext(EXTERNAL_ID).orElseThrow().trustGrade()).isEqualTo("주의");
    }

    @Test
    void 저장된_분석이_끝나지_않았으면_평균을_싣지_않는다() {
        when(dataServerClient.findProduct(any()))
                .thenReturn(Optional.of(withAnalysis(product(128), page(20), null)));
        savedAs(saved(AnalysisStatus.RUNNING, 80.0, 100, null));

        ProductAnalysisContext ctx = adapter.findContext(EXTERNAL_ID).orElseThrow();

        assertThat(ctx.averageRti()).isNull();
        assertThat(ctx.trustGrade()).isNull();
        assertThat(ctx.totalReviews()).isEqualTo(128);
    }

    @Test
    void 상세_분석이_없으면_저장된_분석_리뷰_수를_쓴다() {
        // 재분석 중(stale·running)에도 이전 완료 결과가 유지된다
        when(dataServerClient.findProduct(any()))
                .thenReturn(Optional.of(withAnalysis(product(null), page(20), analysis("stale", null))));
        savedAs(saved(AnalysisStatus.DONE, 72.0, 150, 0));

        ProductAnalysisContext ctx = adapter.findContext(EXTERNAL_ID).orElseThrow();

        assertThat(ctx.totalReviews()).isEqualTo(150);
        assertThat(ctx.averageRti()).isEqualTo(72.0);
        // 번호표 생성 때 채워진 0 은 '모름'이다
        assertThat(ctx.mallReviewCount()).isNull();
    }
}
