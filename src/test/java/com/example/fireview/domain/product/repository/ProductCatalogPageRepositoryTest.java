package com.example.fireview.domain.product.repository;

import com.example.fireview.domain.dataserver.dto.response.AnalysisStatus;
import com.example.fireview.domain.product.entity.Category;
import com.example.fireview.domain.product.entity.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카테고리 화면 목록 쿼리(#248). Data 서버 상품만 카테고리로 거르고 페이지로 나눈다.
 */
@DataJpaTest
@ActiveProfiles("test")
class ProductCatalogPageRepositoryTest {

    @Autowired ProductRepository productRepository;

    private Product data(long id, Category category) {
        return productRepository.save(Product.builder().id(id).name("실상품 " + id).platform("KURLY")
                .category(category).dataPlatform("kurly").dataProductId("p" + id).build());
    }

    @Test
    void 고른_카테고리의_Data_상품만_돌려준다() {
        data(1L, Category.FOOD_SNACK);
        data(2L, Category.BEAUTY_SKINCARE);
        data(3L, null);
        productRepository.save(Product.builder().id(900000000000L).name("더미").platform("NAVER")
                .category(Category.FOOD_SNACK).avgRti(80.0).build());

        Page<Product> page = productRepository.findCatalogByCategoryIn(
                List.of(Category.FOOD_SNACK), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Product::getId).containsExactly(1L);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void 페이지로_나누고_전체_건수를_센다() {
        for (long id = 1; id <= 5; id++) data(id, Category.FOOD_SNACK);

        Page<Product> second = productRepository.findCatalogByCategoryIn(
                List.of(Category.FOOD_SNACK, Category.FOOD_FRESH), PageRequest.of(1, 2));

        assertThat(second.getContent()).hasSize(2);
        assertThat(second.getTotalElements()).isEqualTo(5);
        assertThat(second.hasNext()).isTrue();
    }

    @Test
    void 분석_완료_상품을_먼저_보여준다() {
        data(1L, Category.FOOD_SNACK);
        Product done = data(2L, Category.FOOD_SNACK);
        done.observeAnalysisStatus(AnalysisStatus.DONE, LocalDateTime.now());
        productRepository.save(done);

        assertThat(productRepository.findCatalog(PageRequest.of(0, 10)).getContent())
                .extracting(Product::getId).first().isEqualTo(2L);
    }
}
