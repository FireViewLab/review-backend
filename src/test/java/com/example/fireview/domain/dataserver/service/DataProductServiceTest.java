package com.example.fireview.domain.dataserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.fireview.domain.dataserver.DataServerFixtures;
import com.example.fireview.domain.dataserver.dto.DataServerJob;
import com.example.fireview.domain.dataserver.dto.DataServerProduct;
import com.example.fireview.domain.dataserver.dto.DataServerProductResponse;
import com.example.fireview.domain.dataserver.dto.DataServerReview;
import com.example.fireview.domain.dataserver.client.DataServerClient;
import com.example.fireview.domain.dataserver.dto.response.AnalysisStatus;
import com.example.fireview.domain.dataserver.dto.response.CollectionStatus;
import com.example.fireview.domain.dataserver.dto.response.DataProductResponse;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataProductServiceTest {

    private static final String PLATFORM = "kurly";
    private static final String PRODUCT_ID = "1000146248";

    @Mock DataServerClient dataServerClient;
    @Mock ProductRepository productRepository;
    @Mock ProductTagRegistry registry;
    @Mock com.example.fireview.domain.reviewsummary.service.ProductReviewSummaryService summaryService;
    @InjectMocks DataProductService service;

    private static DataServerProduct product() {
        return new DataServerProduct(PLATFORM, PRODUCT_ID, "샘플 상품", "https://kurly.com/p",
                "브랜드", "제조사", "판매자", 29900, "https://img", "식품 > 간편식", 128, 4.5,
                "2026-10-05T00:00:00+09:00");
    }

    private static DataServerProductResponse body(String status, DataServerProduct p,
                                                  List<DataServerReview> reviews, DataServerJob job) {
        return new DataServerProductResponse(status, p,
                reviews == null ? null : new DataServerProductResponse.Reviews(reviews, "next-1"), job, null);
    }

    private DataProductResponse call() {
        return service.getProduct(PLATFORM, PRODUCT_ID, null);
    }

    @Test
    void fresh면_상품과_리뷰를_내려준다() {
        when(dataServerClient.findProduct(any(), any())).thenReturn(Optional.of(
                body("fresh", product(),
                        List.of(new DataServerReview("r-1", "맛있어요", 5.0, "user**",
                                "2026-10-01T10:00:00+09:00", "옵션", List.of("img"), 3)), null)));

        DataProductResponse res = call();

        assertThat(res.collectionStatus()).isEqualTo(CollectionStatus.FRESH);
        assertThat(res.product().name()).isEqualTo("샘플 상품");
        assertThat(res.product().externalId()).isEqualTo("kurly-1000146248");
        assertThat(res.reviews().items()).hasSize(1);
        assertThat(res.reviews().nextCursor()).isEqualTo("next-1");
        // 구버전 Data 서버처럼 analysis 가 없으면 상태는 UNAVAILABLE, 결과는 null
        assertThat(res.analysisStatus()).isEqualTo(AnalysisStatus.UNAVAILABLE);
        assertThat(res.analysis()).isNull();
        assertThat(res.reviews().items().get(0).rti()).isNull();
        assertThat(res.reviews().items().get(0).level()).isNull();
        assertThat(res.reviews().items().get(0).reasons()).isEmpty();
    }

    @Test
    void 상세_설명과_설명_이미지를_내려준다() throws Exception {
        // #249: Data 상품 응답의 description_text·description_images 를 product 에 싣는다
        DataServerProduct p = DataServerFixtures.MAPPER.readValue("""
                {"platform": "kurly", "product_id": "1000146248", "name": "샘플 상품",
                 "description_text": "  국내산 원물로 만든 간편식입니다.\\n",
                 "description_images": ["https://img/d1.jpg", "", null, "https://img/d2.jpg"]}
                """, DataServerProduct.class);
        when(dataServerClient.findProduct(any(), any())).thenReturn(Optional.of(body("fresh", p, List.of(), null)));

        DataProductResponse res = call();

        assertThat(res.product().description()).isEqualTo("국내산 원물로 만든 간편식입니다.");
        assertThat(res.product().detailImages()).containsExactly("https://img/d1.jpg", "https://img/d2.jpg");
    }

    @Test
    void 상세_설명이_없으면_null과_빈_배열이다() throws Exception {
        // 올리브영·11번가 수집기는 아직 설명을 채우지 않는다
        when(dataServerClient.findProduct(any(), any())).thenReturn(Optional.of(body("fresh", product(), List.of(), null)));

        JsonNode product = new ObjectMapper().valueToTree(call()).get("product");

        assertThat(product.get("description").isNull()).isTrue();
        assertThat(product.get("detailImages").isArray()).isTrue();
        assertThat(product.get("detailImages")).isEmpty();
    }

    @Test
    void stale도_상품을_내려준다() {
        // 실패가 아니다. 최신을 기다리면 화면이 크롤링 속도에 묶인다
        when(dataServerClient.findProduct(any(), any())).thenReturn(Optional.of(
                body("stale", product(), List.of(), new DataServerJob(7L, PLATFORM, PRODUCT_ID,
                        "running", "pending", "pending", null))));

        DataProductResponse res = call();

        assertThat(res.collectionStatus()).isEqualTo(CollectionStatus.STALE);
        assertThat(res.product()).isNotNull();
        assertThat(res.job().id()).isEqualTo(7L);
    }

    @Test
    void queued면_상품이_없고_job만_있다() {
        when(dataServerClient.findProduct(any(), any())).thenReturn(Optional.of(
                body("queued", null, null, new DataServerJob(9L, PLATFORM, PRODUCT_ID,
                        "pending", "pending", "pending", null))));

        DataProductResponse res = call();

        assertThat(res.collectionStatus()).isEqualTo(CollectionStatus.QUEUED);
        assertThat(res.product()).isNull();
        assertThat(res.reviews().items()).isEmpty();
        assertThat(res.job().id()).isEqualTo(9L);
    }

    @Test
    void Data_서버에_못_닿으면_UNAVAILABLE이다() {
        // 404 로 바꾸면 "상품이 없다"는 뜻이 되어버린다. 둘은 프론트가 다르게 다뤄야 한다
        when(dataServerClient.findProduct(any(), any())).thenReturn(Optional.empty());

        DataProductResponse res = call();

        assertThat(res.collectionStatus()).isEqualTo(CollectionStatus.UNAVAILABLE);
        assertThat(res.product()).isNull();
        assertThat(res.reviews().items()).isEmpty();
        assertThat(res.analysisStatus()).isEqualTo(AnalysisStatus.UNAVAILABLE);
        assertThat(res.analysis()).isNull();
    }

    @Test
    void 번호표가_있으면_함께_내려준다() {
        when(dataServerClient.findProduct(any(), any()))
                .thenReturn(Optional.of(body("fresh", product(), List.of(), null)));
        when(productRepository.findByDataPlatformAndDataProductId(PLATFORM, PRODUCT_ID))
                .thenReturn(Optional.of(Product.builder().id(42L).name("샘플 상품").build()));

        assertThat(call().springProductId()).isEqualTo(42L);
    }

    @Test
    void 번호표가_없으면_null이고_새로_만들지_않는다() {
        // 열어보기만 해도 행이 생기면 Data 서버 상품 수만큼 빈 행이 쌓인다
        when(dataServerClient.findProduct(any(), any()))
                .thenReturn(Optional.of(body("fresh", product(), List.of(), null)));
        when(productRepository.findByDataPlatformAndDataProductId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThat(call().springProductId()).isNull();
        org.mockito.Mockito.verify(productRepository, org.mockito.Mockito.never())
                .save(any(Product.class));
    }

    @Test
    void job_조회는_없으면_빈값이다() {
        when(dataServerClient.findJob(1L)).thenReturn(Optional.empty());
        when(dataServerClient.findJob(2L)).thenReturn(Optional.of(
                new DataServerJob(2L, PLATFORM, PRODUCT_ID, "partial", "succeeded", "failed", "타임아웃")));

        assertThat(service.getJob(1L)).isEmpty();
        assertThat(service.getJob(2L)).get()
                .extracting(DataProductResponse.CollectionJobStatus::reviewStatus).isEqualTo("failed");
    }

    @Test
    void 번호표가_있는_상품은_상세를_열_때_표시_정보를_갱신한다() {
        // 홈·검색 목록 캐시가 오래 낡지 않게 하는 지점이다
        when(dataServerClient.findProduct(any(), any()))
                .thenReturn(Optional.of(body("fresh", product(), List.of(), null)));
        when(registry.find(any())).thenReturn(Optional.of(Product.builder().id(42L).name("x").build()));

        call();

        org.mockito.Mockito.verify(registry).upsertForDisplay(any());
    }

    private static DataServerProduct productWithoutCounts() {
        // 11번가·올리브영 상세 응답처럼 리뷰 수·평점이 비어 온다
        return new DataServerProduct(PLATFORM, PRODUCT_ID, "샘플 상품", "https://kurly.com/p",
                null, null, null, 29900, "https://img", null, null, null, null);
    }

    @Test
    void 상세가_비워_보낸_리뷰수와_평점은_목록_값으로_채운다() {
        when(dataServerClient.findProduct(any(), any()))
                .thenReturn(Optional.of(body("fresh", productWithoutCounts(), List.of(), null)));
        Product cached = Product.builder().id(42L).name("샘플 상품")
                .reviewCount(10816).avgRating(4.8).build();
        when(registry.find(any())).thenReturn(Optional.of(cached));
        when(registry.upsertForDisplay(any())).thenReturn(cached);

        DataProductResponse res = call();

        assertThat(res.product().reviewCount()).isEqualTo(10816);
        assertThat(res.product().rating()).isEqualTo(4.8);
    }

    @Test
    void 목록_값도_모르는_0이면_채우지_않는다() {
        // 번호표 생성 시 기본값 0 은 "모름"이다. 리뷰 0 개로 보이면 안 된다
        when(dataServerClient.findProduct(any(), any()))
                .thenReturn(Optional.of(body("fresh", productWithoutCounts(), List.of(), null)));
        Product cached = Product.builder().id(42L).name("샘플 상품")
                .reviewCount(0).avgRating(0.0).build();
        when(registry.find(any())).thenReturn(Optional.of(cached));
        when(registry.upsertForDisplay(any())).thenReturn(cached);

        DataProductResponse res = call();

        assertThat(res.product().reviewCount()).isNull();
        assertThat(res.product().rating()).isNull();
    }

    @Test
    void 상세에_값이_있으면_그대로_쓴다() {
        when(dataServerClient.findProduct(any(), any()))
                .thenReturn(Optional.of(body("fresh", product(), List.of(), null)));
        Product cached = Product.builder().id(42L).name("x").reviewCount(1).avgRating(1.0).build();
        when(registry.find(any())).thenReturn(Optional.of(cached));
        when(registry.upsertForDisplay(any())).thenReturn(cached);

        DataProductResponse res = call();

        assertThat(res.product().reviewCount()).isEqualTo(128);
        assertThat(res.product().rating()).isEqualTo(4.5);
    }

    @Test
    void 번호표가_없으면_상세를_열어도_만들지_않는다() {
        when(dataServerClient.findProduct(any(), any()))
                .thenReturn(Optional.of(body("fresh", product(), List.of(), null)));
        when(registry.find(any())).thenReturn(Optional.empty());

        call();

        org.mockito.Mockito.verify(registry, org.mockito.Mockito.never()).upsertForDisplay(any());
    }

    @Test
    void 수집_전이면_갱신하지_않는다() {
        when(dataServerClient.findProduct(any(), any())).thenReturn(Optional.of(
                body("queued", null, null, new DataServerJob(9L, PLATFORM, PRODUCT_ID,
                        "pending", "pending", "pending", null))));

        call();

        org.mockito.Mockito.verify(registry, org.mockito.Mockito.never()).upsertForDisplay(any());
    }
}
