package com.example.fireview.domain.product.dto;

import com.example.fireview.domain.dataserver.dto.response.AnalysisStatus;
import com.example.fireview.domain.product.entity.Category;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.review.entity.TrustGrade;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 분석 전 상품(번호표만 있는 행)이 응답으로 나갈 때 터지지 않는지 본다.
 *
 * <p>Data 서버에서 온 상품은 avgRti·category 가 비어 있다. 예전에는 두 칼럼이 NOT NULL
 * 이라 이 경우가 없었고, 그래서 {@code TrustGrade.fromScore(getAvgRti())} 가 Double 을
 * 그냥 언박싱했다. 번호표 행이 목록에 섞이면 거기서 NPE 가 난다.
 */
class ProductResponseNullSafetyTest {

    private static Product tagOnly() {
        return Product.builder()
                .id(123L).name("토리든 마스크팩").platform("KURLY")
                .dataPlatform("kurly").dataProductId("1000146248")
                .build();   // category·avgRti 없음
    }

    @Test
    void 분석_전_상품도_변환된다() {
        assertThatCode(() -> ProductResponse.from(tagOnly())).doesNotThrowAnyException();
    }

    @Test
    void 분석_관련_필드는_null로_내려간다() {
        // 기본값을 끼워 넣으면 사용자가 그 숫자를 실제 판정으로 읽는다
        ProductResponse res = ProductResponse.from(tagOnly());

        assertThat(res.avgRti()).isNull();
        assertThat(res.rtiGrade()).isNull();
        assertThat(res.rtiLevel()).isNull();
        assertThat(res.rtiColor()).isNull();
    }

    @Test
    void 카테고리가_없어도_변환된다() {
        ProductResponse res = ProductResponse.from(tagOnly());

        assertThat(res.category()).isNull();
        assertThat(res.majorCategory()).isNull();
        assertThat(res.categoryDisplayName()).isNull();
        assertThat(res.majorCategoryDisplayName()).isNull();
    }

    @Test
    void 분석된_상품은_그대로_내려간다() {
        Product analyzed = Product.builder()
                .id(1L).name("분석된 상품").platform("NAVER")
                .category(Category.DIGITAL_MOBILE).avgRti(82.0)
                .build();

        ProductResponse res = ProductResponse.from(analyzed);

        assertThat(res.avgRti()).isEqualTo(82.0);
        assertThat(res.rtiGrade()).isEqualTo(TrustGrade.fromScore(82.0));
        assertThat(res.rtiLevel()).isNotNull();
        assertThat(res.category()).isEqualTo(Category.DIGITAL_MOBILE);
    }

    @Test
    void Data_서버_상품은_식별자를_싣는다() {
        // 프론트가 이 값으로 v2 상세(/product/:platform/:productId)를 열고 챗봇 productId 를 채운다
        ProductResponse res = ProductResponse.from(tagOnly());

        assertThat(res.dataPlatform()).isEqualTo("kurly");
        assertThat(res.dataProductId()).isEqualTo("1000146248");
        assertThat(res.externalId()).isEqualTo("kurly-1000146248");
    }

    @Test
    void 더미_상품은_식별자가_없다() {
        Product dummy = Product.builder().id(900000000000L).name("삼성 갤럭시")
                .platform("NAVER").category(Category.DIGITAL_MOBILE).avgRti(80.0).build();

        ProductResponse res = ProductResponse.from(dummy);

        assertThat(res.dataPlatform()).isNull();
        assertThat(res.externalId()).isNull();
    }

    @Test
    void 분석_상태를_못_봤으면_null이다() {
        assertThat(ProductResponse.from(tagOnly()).analysisStatus()).isNull();
    }

    @Test
    void 마지막으로_본_분석_상태를_싣는다() {
        Product tag = tagOnly();
        tag.observeAnalysisStatus(AnalysisStatus.DONE, java.time.LocalDateTime.now());

        JsonNode json = new ObjectMapper().valueToTree(ProductResponse.from(tag));

        assertThat(json.get("analysisStatus").asText()).isEqualTo("DONE");
        // 상품 평균 RTI 는 여전히 없다. 상태가 DONE 이어도 점수를 지어내지 않는다
        assertThat(json.get("avgRti").isNull()).isTrue();
    }

    @Test
    void JSON에_analysisStatus_키가_null로_있다() {
        JsonNode json = new ObjectMapper().valueToTree(ProductResponse.from(tagOnly()));

        assertThat(json.has("analysisStatus")).isTrue();
        assertThat(json.get("analysisStatus").isNull()).isTrue();
    }

    @Test
    void Data_상품의_모르는_평점은_0이_아니라_null이다() {
        // 번호표 생성 때 평점이 비면 0.0 이 채워진다(Product.onCreate). 그 0 은 "모름"이다
        Product tag = tagOnly();
        tag.setAvgRating(0.0);

        assertThat(ProductResponse.from(tag).avgRating()).isNull();
        assertThat(ProductResponse.from(tagOnly()).avgRating()).isNull();
    }

    @Test
    void Data_상품의_실제_평점은_그대로다() {
        Product tag = tagOnly();
        tag.setAvgRating(4.5);

        assertThat(ProductResponse.from(tag).avgRating()).isEqualTo(4.5);
    }

    @Test
    void Data_상품의_리뷰_수_0은_그대로다() {
        // 실제 0 개와 모름을 가를 근거가 없어 바꾸지 않는다
        Product tag = tagOnly();
        tag.setReviewCount(0);

        assertThat(ProductResponse.from(tag).reviewCount()).isZero();
    }

    @Test
    void Data에_리뷰가_저장돼_있으면_리뷰_수_0은_null이다() {
        // 11번가는 검색 응답에 리뷰 수를 주지 않아 0 이 남는다. 리뷰를 분석까지 했으면 0 개일 수 없다
        Product tag = tagOnly();
        tag.setReviewCount(0);
        tag.setAnalysisSourceReviewCount(15);

        assertThat(ProductResponse.from(tag).reviewCount()).isNull();
    }

    @Test
    void 레거시_상품의_평점과_상태는_바꾸지_않는다() {
        Product legacy = Product.builder().id(900000000000L).name("삼성 갤럭시")
                .platform("NAVER").category(Category.DIGITAL_MOBILE).avgRti(80.0)
                .reviewCount(0).avgRating(0.0).build();

        ProductResponse res = ProductResponse.from(legacy);

        assertThat(res.avgRating()).isEqualTo(0.0);
        assertThat(res.reviewCount()).isZero();
        assertThat(res.analysisStatus()).isNull();
        assertThat(res.avgRti()).isEqualTo(80.0);
    }
}
