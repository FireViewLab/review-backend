package com.example.fireview.domain.product.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fireview.domain.product.entity.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CategoryMapperTest {

    @ParameterizedTest(name = "{0}/{1} -> {2}")
    @CsvSource({
            "디지털/가전, 노트북, DIGITAL_PC",
            "디지털/가전, 청소기, DIGITAL_HOME_APPLIANCE",
            "패션의류, 남성의류, FASHION_MEN",
            "패션잡화, 신발, ACC_SHOES",
            "화장품/미용, 클렌징폼, BEAUTY_CLEANSING",
            "뷰티, 샴푸, BEAUTY_HAIR",
            "식품, 커피, FOOD_BEVERAGE",
            "생활/주방, 냄비, LIVING_KITCHEN",
            "가구/인테리어, 이불, FURNITURE_BEDDING",
            "스포츠/레저, 골프공, SPORTS_GOLF",
            "자동차/공구, 전동공구, AUTO_TOOL",
            "출산/유아동, 블록, BABY_TOY",
            "반려동물, 캣타워, PET_CAT",
            "도서/문구/취미, 노트, BOOKS_STATIONERY",
            "여행/서비스, 호텔, TRAVEL_LODGING",
            "명품/브랜드, 향수, LUXURY_BEAUTY"
    })
    @DisplayName("대분류와 중분류 조합에 맞는 내부 카테고리로 변환한다")
    void mapsCombination(String c1, String c2, Category expected) {
        assertThat(CategoryMapper.fromNaver(c1, c2)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} 미등록 중분류 -> {1}")
    @CsvSource({
            "디지털/가전, DIGITAL_MOBILE",
            "패션의류, FASHION_WOMEN",
            "식품, FOOD_PROCESSED",
            "반려동물용품, PET_DOG",
            "명품/브랜드, LUXURY_ACCESSORIES"
    })
    @DisplayName("알 수 없는 중분류는 대분류의 기본 카테고리로 대체한다")
    void fallsBackToDefaultOfCategory1(String c1, Category expected) {
        assertThat(CategoryMapper.fromNaver(c1, "없는중분류")).isEqualTo(expected);
    }

    @Test
    @DisplayName("알 수 없거나 null/빈 대분류는 ETC 이다")
    void unknownCategory1IsEtc() {
        assertThat(CategoryMapper.fromNaver("모르는분류", "노트북")).isEqualTo(Category.ETC);
        assertThat(CategoryMapper.fromNaver(null, null)).isEqualTo(Category.ETC);
        assertThat(CategoryMapper.fromNaver("  ", "  ")).isEqualTo(Category.ETC);
    }

    @Test
    @DisplayName("앞뒤 공백은 무시하고, null 중분류는 기본 카테고리로 처리한다")
    void trimsAndHandlesNullCategory2() {
        assertThat(CategoryMapper.fromNaver(" 디지털/가전 ", " 노트북 ")).isEqualTo(Category.DIGITAL_PC);
        assertThat(CategoryMapper.fromNaver("식품", null)).isEqualTo(Category.FOOD_PROCESSED);
    }

    @Test
    @DisplayName("대분류만 받는 오버로드는 중분류 없이 기본 카테고리로 변환한다")
    void singleArgOverload() {
        assertThat(CategoryMapper.fromNaver("스포츠/레저")).isEqualTo(Category.SPORTS_FITNESS);
        assertThat(CategoryMapper.fromNaver("없음")).isEqualTo(Category.ETC);
    }
}
