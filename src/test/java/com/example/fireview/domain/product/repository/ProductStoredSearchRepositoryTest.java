package com.example.fireview.domain.product.repository;

import com.example.fireview.domain.dataserver.dto.response.AnalysisStatus;
import com.example.fireview.domain.product.entity.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class ProductStoredSearchRepositoryTest {

    @Autowired ProductRepository products;

    private Product save(long id, String name, Integer storedReviews, boolean done, String dataPlatform) {
        Product p = Product.builder().id(id).name(name).platform("KURLY")
                .dataPlatform(dataPlatform).dataProductId(dataPlatform == null ? null : String.valueOf(id))
                .analysisSourceReviewCount(storedReviews).build();
        if (done) p.observeAnalysisStatus(AnalysisStatus.DONE, LocalDateTime.now());
        return products.save(p);
    }

    @Test
    void 리뷰가_저장된_Data_상품만_이름으로_찾는다() {
        save(1L, "토리든 마스크팩", 120, true, "kurly");
        save(2L, "메디힐 마스크팩", null, false, "kurly");
        save(3L, "마스크팩 더미", 50, false, null);
        save(4L, "선크림", 80, true, "kurly");

        assertThat(products.findStoredByNameWithReviews("마스크팩", Limit.of(10)))
                .extracting(Product::getId).containsExactly(1L);
    }

    @Test
    void 분석_완료를_먼저_그다음_리뷰_많은_순이다() {
        save(1L, "텀블러 A", 10, false, "kurly");
        save(2L, "텀블러 B", 5, true, "kurly");
        save(3L, "텀블러 C", 300, true, "kurly");

        assertThat(products.findStoredByNameWithReviews("텀블러", Limit.of(10)))
                .extracting(Product::getId).containsExactly(3L, 2L, 1L);
    }

    @Test
    void 대소문자를_가리지_않고_퍼센트는_글자로_찾는다() {
        save(1L, "Stanley Tumbler", 10, true, "kurly");
        save(2L, "100% 순면 수건", 10, true, "kurly");
        save(3L, "1000 수건", 10, true, "kurly");

        assertThat(products.findStoredByNameWithReviews("tumbler", Limit.of(10)))
                .extracting(Product::getId).containsExactly(1L);
        assertThat(products.findStoredByNameWithReviews("100\\%", Limit.of(10)))
                .extracting(Product::getId).containsExactly(2L);
    }
}
