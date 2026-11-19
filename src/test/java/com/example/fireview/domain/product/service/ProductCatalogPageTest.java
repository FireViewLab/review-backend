package com.example.fireview.domain.product.service;

import com.example.fireview.domain.product.dto.ProductPageResponse;
import com.example.fireview.domain.product.entity.Category;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.global.exception.CustomException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 카테고리 화면 목록(#248). 대분류·세부 분류 이름을 받아 페이지 응답을 만든다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(ProductService.class)
class ProductCatalogPageTest {

    @Autowired ProductService productService;
    @Autowired ProductRepository productRepository;

    private void data(long id, Category category) {
        productRepository.save(Product.builder().id(id).name("실상품 " + id).platform("KURLY")
                .category(category).dataPlatform("kurly").dataProductId("p" + id).build());
    }

    @Test
    void 대분류를_고르면_그_아래_세부_분류를_모두_포함한다() {
        data(1L, Category.FOOD_SNACK);
        data(2L, Category.FOOD_FRESH);
        data(3L, Category.BEAUTY_SKINCARE);

        ProductPageResponse res = productService.getCatalogPage("FOOD", 0, 10);

        assertThat(res.items()).hasSize(2);
        assertThat(res.totalElements()).isEqualTo(2);
        assertThat(res.hasNext()).isFalse();
    }

    @Test
    void 세부_분류는_그_분류만_돌려주고_대소문자를_가리지_않는다() {
        data(1L, Category.FOOD_SNACK);
        data(2L, Category.FOOD_FRESH);

        assertThat(productService.getCatalogPage("food_snack", 0, 10).items())
                .singleElement().satisfies(p -> assertThat(p.category()).isEqualTo(Category.FOOD_SNACK));
    }

    @Test
    void 카테고리를_비우면_전체를_페이지로_나눈다() {
        for (long id = 1; id <= 3; id++) data(id, Category.FOOD_SNACK);

        ProductPageResponse res = productService.getCatalogPage(null, 0, 2);

        assertThat(res.items()).hasSize(2);
        assertThat(res.page()).isZero();
        assertThat(res.size()).isEqualTo(2);
        assertThat(res.totalElements()).isEqualTo(3);
        assertThat(res.hasNext()).isTrue();
    }

    @Test
    void 모르는_카테고리는_입력_오류다() {
        assertThatThrownBy(() -> productService.getCatalogPage("NOT_A_CATEGORY", 0, 10))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void 페이지_범위를_벗어나면_입력_오류다() {
        assertThatThrownBy(() -> productService.getCatalogPage(null, -1, 10)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> productService.getCatalogPage(null, 0, 0)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> productService.getCatalogPage(null, 0, ProductService.MAX_PAGE_SIZE + 1))
                .isInstanceOf(CustomException.class);
    }
}
