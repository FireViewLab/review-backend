package com.example.fireview.global.config;

import com.example.fireview.domain.product.client.NaverShoppingClient;
import com.example.fireview.domain.product.entity.Category;
import com.example.fireview.domain.product.entity.PlatformLink;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.review.entity.Review;
import com.example.fireview.domain.review.entity.TrustGrade;
import com.example.fireview.domain.review.repository.ReviewRepository;
import com.example.fireview.domain.review.service.RtiEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 시연용 더미 데이터 초기화
 *
 * - 상품 30개 이상 (카테고리별 다양)
 * - 상품당 리뷰 20~50개
 * - 날짜 분포: 최근 30일 (추이 그래프 렌더링 가능)
 * - RTI 분포: safe 60% / warn 30% / danger 10%
 *
 * prod·test 프로파일에서는 실행되지 않는다.
 *
 * <p><b>@Profile 표기에 주의한다.</b> 배열 {@code {"!prod", "!test"}} 은 OR 로 묶인다.
 * prod 로 띄우면 {@code !prod}=false, {@code !test}=true 라 OR 결과가 true 가 되어
 * 운영에서도 시드가 돌았다. 실제로 운영 DB 에 더미 상품 33건과 비밀번호가 공개 저장소에
 * 적힌 관리자 계정이 생겼다. AND 로 묶으려면 문자열 하나에 {@code &} 를 써야 한다.
 */
@Component
@Profile("!prod & !test")
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private static final java.util.concurrent.atomic.AtomicLong FALLBACK_ID =
            new java.util.concurrent.atomic.AtomicLong(900_000_000_000L);

    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final RtiEngineService rtiEngine;
    private final NaverShoppingClient naverShoppingClient;

    private static final Random RANDOM = new Random(42); // 재현 가능한 랜덤

    @Override
    @Transactional
    public void run(String... args) {
        if (productRepository.count() > 0) {
            updatePlaceholderImages();
            updateFakePlatformLinks();
            return;
        }
        log.info("샘플 데이터 초기화 시작...");

        List<Product> products = createProducts();
        createReviews(products);

        log.info("샘플 데이터 초기화 완료. 상품={}, 리뷰={}", productRepository.count(), reviewRepository.count());
    }

    private List<Product> createProducts() {
        List<Product> products = new ArrayList<>();

        // 디지털/가전 - 모바일/태블릿 (3개)
        products.add(product("삼성 갤럭시 S25 Ultra", 1499000L, Category.DIGITAL_MOBILE, 82.5, 4.5,
                List.of(link("NAVER", 1499000L, "https://smartstore.naver.com/samsung/galaxy-s25-ultra"),
                        link("COUPANG", 1469000L, "https://www.coupang.com/vp/products/galaxy-s25-ultra"),
                        link("11ST", 1479000L, "https://www.11st.co.kr/products/galaxy-s25-ultra"))));
        products.add(product("애플 아이패드 프로 11인치", 1299000L, Category.DIGITAL_MOBILE, 85.0, 4.5,
                List.of(link("NAVER", 1299000L, "https://smartstore.naver.com/apple/ipad-pro-11"),
                        link("COUPANG", 1269000L, "https://www.coupang.com/vp/products/ipad-pro-11"))));
        products.add(product("삼성 갤럭시 워치7", 299000L, Category.DIGITAL_MOBILE, 43.0, 3.6));

        // 디지털/가전 - PC/주변기기 (2개)
        products.add(product("LG 그램 17인치 노트북", 1890000L, Category.DIGITAL_PC, 88.0, 4.7,
                List.of(link("NAVER", 1890000L, "https://smartstore.naver.com/lg/gram-17"),
                        link("COUPANG", 1850000L, "https://www.coupang.com/vp/products/lg-gram-17"),
                        link("GMARKET", 1870000L, "https://www.gmarket.co.kr/lg-gram-17"))));
        products.add(product("레노버 씽크패드 X1 카본", 2190000L, Category.DIGITAL_PC, 78.0, 4.3));

        // 디지털/가전 - 영상/음향 (2개)
        products.add(product("애플 에어팟 프로 2세대", 359000L, Category.DIGITAL_AV, 91.0, 4.8,
                List.of(link("NAVER", 359000L, "https://smartstore.naver.com/apple/airpods-pro-2"),
                        link("COUPANG", 339000L, "https://www.coupang.com/vp/products/airpods-pro-2"),
                        link("SSG", 349000L, "https://www.ssg.com/airpods-pro-2"))));
        products.add(product("소니 WH-1000XM5 헤드폰", 429000L, Category.DIGITAL_AV, 87.5, 4.6,
                List.of(link("NAVER", 429000L, "https://smartstore.naver.com/sony/wh-1000xm5"),
                        link("COUPANG", 399000L, "https://www.coupang.com/vp/products/sony-wh1000xm5"))));

        // 디지털/가전 - 생활가전 (2개)
        products.add(product("LG 코드제로 A9S 무선청소기", 899000L, Category.DIGITAL_HOME_APPLIANCE, 84.0, 4.5));
        products.add(product("삼성 비스포크 큐브 공기청정기", 549000L, Category.DIGITAL_HOME_APPLIANCE, 72.0, 4.1));

        // 디지털/가전 - 주방가전 (2개)
        products.add(product("필립스 에어프라이어 5.6L", 159000L, Category.DIGITAL_KITCHEN, 88.5, 4.7));
        products.add(product("다이슨 에어랩 멀티 스타일러", 689000L, Category.DIGITAL_KITCHEN, 67.0, 4.2));

        // 패션잡화 - 신발 (3개)
        products.add(product("나이키 에어맥스 270", 179000L, Category.ACC_SHOES, 78.5, 4.3,
                List.of(link("NAVER", 179000L, "https://smartstore.naver.com/nike/airmax-270"),
                        link("COUPANG", 159000L, "https://www.coupang.com/vp/products/nike-airmax-270"),
                        link("11ST", 169000L, "https://www.11st.co.kr/products/nike-airmax-270"))));
        products.add(product("뉴발란스 574 클래식", 129000L, Category.ACC_SHOES, 85.0, 4.6,
                List.of(link("NAVER", 129000L, "https://smartstore.naver.com/newbalance/574"),
                        link("COUPANG", 119000L, "https://www.coupang.com/vp/products/newbalance-574"))));
        products.add(product("아디다스 울트라부스트 22", 219000L, Category.ACC_SHOES, 55.0, 4.0));

        // 패션의류 - 여성의류 (2개)
        products.add(product("자라 오버핏 트렌치코트", 159000L, Category.FASHION_WOMEN, 38.0, 3.4));
        products.add(product("유니클로 울트라라이트 다운", 89000L, Category.FASHION_WOMEN, 81.0, 4.4));

        // 뷰티 - 스킨케어 (3개)
        products.add(product("설화수 자음생 크림 60ml", 185000L, Category.BEAUTY_SKINCARE, 43.0, 3.8));
        products.add(product("라네즈 워터슬리핑마스크 70ml", 38000L, Category.BEAUTY_SKINCARE, 83.0, 4.5));
        products.add(product("아이오페 레티놀 엑스퍼트 0.1%", 62000L, Category.BEAUTY_SKINCARE, 55.0, 4.1));

        // 뷰티 - 메이크업 (2개)
        products.add(product("에스티로더 갈색병 50ml", 145000L, Category.BEAUTY_MAKEUP, 29.0, 3.1));
        products.add(product("닥터지 브라이트닝 필링젤", 25000L, Category.BEAUTY_CLEANSING, 77.0, 4.2));

        // 식품 - 가공식품 (2개)
        products.add(product("동원 참치 85g 20개입", 28000L, Category.FOOD_PROCESSED, 90.0, 4.8));
        products.add(product("농심 신라면 40봉", 18500L, Category.FOOD_PROCESSED, 86.0, 4.6));

        // 식품 - 건강식품 (1개)
        products.add(product("정관장 홍삼정 에브리타임", 98000L, Category.FOOD_HEALTH, 52.0, 4.0));

        // 스포츠/레저 - 헬스/요가 (2개)
        products.add(product("나이키 에어줌 페가수스 40", 149000L, Category.SPORTS_FITNESS, 80.0, 4.4));
        products.add(product("언더아머 UA 차지 어설트 4", 119000L, Category.SPORTS_FITNESS, 62.0, 4.0));

        // 스포츠/레저 - 등산/캠핑 (1개)
        products.add(product("요넥스 나노플렉스 800 배드민턴 라켓", 189000L, Category.SPORTS_OUTDOOR, 84.0, 4.5));

        // 도서/문구/취미 - 도서 (2개)
        products.add(product("클린 코드 (로버트 C. 마틴)", 33000L, Category.BOOKS_BOOK, 94.0, 4.9));
        products.add(product("자바 ORM 표준 JPA 프로그래밍", 45000L, Category.BOOKS_BOOK, 92.0, 4.8));

        // 출산/유아동 - 출산/육아 (2개)
        products.add(product("하기스 신생아 기저귀 84매", 32000L, Category.BABY_NEWBORN, 88.0, 4.7));
        products.add(product("피셔프라이스 신생아 바운서", 89000L, Category.BABY_GOODS, 75.0, 4.2));

        // 반려동물 (2개)
        products.add(product("로얄캐닌 어덜트 고양이 사료 4kg", 58000L, Category.PET_CAT, 86.0, 4.6));
        products.add(product("강아지 자동 급수기 2.5L", 35000L, Category.PET_DOG, 47.0, 3.7));

        return productRepository.saveAll(products);
    }

    /**
     * DB에 이미 데이터가 있을 때 placeholder 이미지를 네이버 실제 이미지로 교체.
     * 초당 10건 제한 방지를 위해 호출 간 150ms 딜레이 적용.
     */
    private void updatePlaceholderImages() {
        if (!naverShoppingClient.isConfigured()) {
            log.debug("[DataInitializer] 네이버 API 미설정 - placeholder 이미지 업데이트 생략");
            return;
        }

        List<Product> placeholderProducts = productRepository.findAll().stream()
                .filter(p -> p.getImageUrl() == null
                        || p.getImageUrl().isBlank()
                        || p.getImageUrl().contains("placeholder"))
                .toList();

        if (placeholderProducts.isEmpty()) {
            log.debug("[DataInitializer] placeholder 이미지 없음 - 업데이트 불필요");
            return;
        }

        log.info("[DataInitializer] placeholder 이미지 {}개 업데이트 시작", placeholderProducts.size());

        for (Product product : placeholderProducts) {
            String imageUrl = naverShoppingClient.fetchThumbnail(product.getName());
            naverApiDelay(); // 초당 10건 제한 방지
            if (imageUrl != null && !imageUrl.isBlank()) {
                product.setImageUrl(imageUrl);
                productRepository.save(product);
                log.debug("[DataInitializer] 이미지 업데이트: {}", product.getName());
            }
        }

        log.info("[DataInitializer] placeholder 이미지 업데이트 완료");
    }

    /**
     * 기존 RDS 데이터의 가짜 platform URL을 실제 검색 URL로 교체.
     * fake URL 패턴: smartstore.naver.com/samsung, coupang.com/vp/products 등 fix/lazy-init-exception
     * @Transactional 필수 - platformLinks가 LAZY 로딩이라 세션 필요
     */
    @Transactional
    public void updateFakePlatformLinks() {
        List<Product> products = productRepository.findAll();
        boolean updated = false;

        for (Product product : products) {
            boolean changed = false;
            for (var link : product.getPlatformLinks()) {
                String url = link.getUrl();
                if (url == null || isFakeUrl(url)) {
                    String realUrl = getNaverLinkOrSearch(product.getName(), link.getPlatform());
                    link.setUrl(realUrl);
                    changed = true;
                }
            }
            if (changed) {
                productRepository.save(product);
                updated = true;
            }
        }

        if (updated) {
            log.info("[DataInitializer] 가짜 platform URL 업데이트 완료");
        }
    }

    private boolean isFakeUrl(String url) {
        // DataInitializer에서 하드코딩됐던 가짜 URL 패턴 감지
        return url.contains("smartstore.naver.com/samsung")
                || url.contains("smartstore.naver.com/lg")
                || url.contains("smartstore.naver.com/apple")
                || url.contains("smartstore.naver.com/sony")
                || url.contains("smartstore.naver.com/nike")
                || url.contains("smartstore.naver.com/newbalance")
                || url.contains("coupang.com/vp/products/")
                || url.contains("11st.co.kr/products/")
                || url.contains("gmarket.co.kr/")
                || url.contains("ssg.com/");
    }

    private String getNaverLinkOrSearch(String productName, String platform) {
        if ("NAVER".equals(platform) && naverShoppingClient.isConfigured()) {
            var item = naverShoppingClient.fetchItem(productName);
            naverApiDelay();
            if (item != null && item.link() != null && !item.link().isBlank()) {
                return item.link();
            }
        }
        return searchUrl(platform, productName);
    }

    /**
     * 네이버 쇼핑 API로 썸네일 + 실제 링크를 가져와 상품 생성.
     * platformLinks 미지정 시 네이버/쿠팡/11ST 검색 URL 자동 생성.
     */
    private Product product(String name, Long price, Category category, double avgRti, double avgRating) {
        return product(name, price, category, avgRti, avgRating, null);
    }

    private Product product(String name, Long price, Category category, double avgRti, double avgRating,
                            List<PlatformLink> platformLinks) {
        // 네이버 API로 실제 이미지 + 링크 조회
        String imageUrl = "";
        String naverLink = searchUrl("NAVER", name);

        String naverProductId = null;
        if (naverShoppingClient.isConfigured()) {
            var item = naverShoppingClient.fetchItem(name);
            naverApiDelay();
            if (item != null) {
                imageUrl = item.image() != null ? item.image() : "";
                naverLink = item.link() != null && !item.link().isBlank()
                        ? item.link() : naverLink;
                naverProductId = item.productId();
            }
        }

        // platformLinks 미지정 시 검색 URL 자동 생성
        List<PlatformLink> links = (platformLinks != null) ? new ArrayList<>(platformLinks)
                : new ArrayList<>(List.of(
                    link("NAVER",   price, naverLink),
                    link("COUPANG", price, searchUrl("COUPANG", name)),
                    link("11ST",    price, searchUrl("11ST",    name))
                ));

        Long productId = null;
        if (naverProductId != null) {
            try { productId = Long.parseLong(naverProductId); } catch (NumberFormatException ignored) {}
        }
        // 네이버 API에서 productId를 못 가져온 경우 fallback ID 사용
        if (productId == null) {
            productId = FALLBACK_ID.getAndIncrement();
            log.debug("[DataInitializer] naverProductId 없음, fallback ID 사용: name={}, id={}", name, productId);
        }

        return Product.builder()
                .id(productId)
                .name(name)
                .imageUrl(imageUrl)
                .price(price)
                .category(category)
                .avgRti(avgRti)
                .reviewCount(0)
                .avgRating(avgRating)
                .naverProductId(naverProductId)
                .platformLinks(links)
                .build();
    }

    /**
     * 플랫폼별 상품명 검색 URL 생성
     */
    private String searchUrl(String platform, String productName) {
        try {
            String encoded = java.net.URLEncoder.encode(productName, java.nio.charset.StandardCharsets.UTF_8);
            return switch (platform) {
                case "NAVER"   -> "https://search.shopping.naver.com/search/all?query=" + encoded;
                case "COUPANG" -> "https://www.coupang.com/np/search?q=" + encoded;
                case "11ST"    -> "https://search.11st.co.kr/Search.tmall?kwd=" + encoded;
                case "GMARKET" -> "https://browse.gmarket.co.kr/search?keyword=" + encoded;
                case "SSG"     -> "https://www.ssg.com/search/searchBar.ssg?query=" + encoded;
                default        -> "https://search.shopping.naver.com/search/all?query=" + encoded;
            };
        } catch (Exception e) {
            return "https://search.shopping.naver.com/search/all?query=" + productName;
        }
    }

    /** 네이버 API 초당 10건 제한 방지용 딜레이 (150ms) */
    private void naverApiDelay() {
        if (!naverShoppingClient.isConfigured()) return;
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private PlatformLink link(String platform, long price, String url) {
        return new PlatformLink(platform, price, url);
    }

    /** 상품별 RTI 수준에 따른 리뷰 생성 */
    private void createReviews(List<Product> products) {
        // 30일치 추이 데이터 생성 가능하도록 날짜 분산
        for (Product product : products) {
            int reviewCount = 20 + RANDOM.nextInt(31); // 20~50개
            createReviewsForProduct(product, reviewCount);
        }
    }

    private void createReviewsForProduct(Product product, int count) {
        double avgRti = product.getAvgRti();

        // 상품 RTI에 따라 등급 분포 결정
        // safe 60% / warn 30% / danger 10% 목표
        int safeCount  = (int) Math.round(count * 0.60);
        int warnCount  = (int) Math.round(count * 0.30);
        int dangerCount = count - safeCount - warnCount;

        // 상품 avg RTI가 낮으면 danger 비중 높임
        if (avgRti < 50) {
            dangerCount = (int) Math.round(count * 0.35);
            warnCount   = (int) Math.round(count * 0.40);
            safeCount   = count - warnCount - dangerCount;
        } else if (avgRti < 70) {
            warnCount  = (int) Math.round(count * 0.45);
            dangerCount = (int) Math.round(count * 0.20);
            safeCount  = count - warnCount - dangerCount;
        }

        List<Review> batch = new ArrayList<>();

        // SAFE 리뷰 생성
        for (int i = 0; i < safeCount; i++) {
            batch.add(buildReview(product, pickSafeContent(), "trusted_" + i + "_p" + product.getId(),
                    4 + RANDOM.nextInt(2), true, daysBefore(25 + RANDOM.nextInt(6))));
        }
        // WARN 리뷰 생성
        for (int i = 0; i < warnCount; i++) {
            batch.add(buildReview(product, pickWarnContent(), "newbie_" + i + "_p" + product.getId(),
                    3 + RANDOM.nextInt(3), false, daysBefore(10 + RANDOM.nextInt(11))));
        }
        // DANGER 리뷰 생성
        for (int i = 0; i < dangerCount; i++) {
            batch.add(buildReview(product, pickDangerContent(), "fake_" + i + "_p" + product.getId(),
                    5, false, daysBefore(1 + RANDOM.nextInt(5))));
        }

        reviewRepository.saveAll(batch);

        // 리뷰 수 업데이트
        product.updateReviewCount(count);
        productRepository.save(product);
    }

    private Review buildReview(Product product, String content, String reviewerId,
                                int rating, boolean verified, LocalDateTime writtenAt) {
        RtiEngineService.RtiResult result = rtiEngine.calculate(content, reviewerId, writtenAt, verified);
        return Review.builder()
                .product(product)
                .reviewerNickname(reviewerId.split("_")[0])
                .reviewerId(reviewerId)
                .content(content)
                .rating(rating)
                .rtiScore(result.score())
                .trustGrade(result.grade())
                .reasons(result.reasons())
                .writtenAt(writtenAt)
                .isVerifiedPurchase(verified)
                .build();
    }

    private LocalDateTime daysBefore(int days) {
        int hourVariance = RANDOM.nextInt(18) + 6; // 6~23시
        return LocalDateTime.now().minusDays(days).withHour(hourVariance).withMinute(RANDOM.nextInt(60));
    }

    // === 리뷰 내용 풀 ===

    private static final String[] SAFE_CONTENTS = {
            "실제로 써보니 정말 만족스럽습니다. 배송도 빠르고 제품 품질이 기대 이상이에요. 화면이 선명하고 배터리도 하루 종일 쓸 수 있어서 좋습니다.",
            "두 달 정도 사용해봤는데 품질이 정말 좋네요. 처음 살 때 걱정했는데 완전히 만족합니다. 다음에도 이 브랜드로 구매할 것 같아요.",
            "가격 대비 성능이 훌륭합니다. 디자인도 마음에 들고 실용적으로 잘 쓰고 있어요. 주변 지인들에게도 추천했습니다.",
            "제품 받고 바로 써봤는데 생각보다 훨씬 좋아요. 특히 성능 면에서 기대를 뛰어넘었습니다. 포장도 꼼꼼하게 되어있었어요.",
            "오래 사용해보고 리뷰 남깁니다. 내구성이 좋고 사용감이 편합니다. 처음엔 가격이 부담됐는데 충분히 값어치를 합니다.",
            "색상이 사진과 동일하고 소재도 좋습니다. 세탁 후에도 형태가 유지되어서 만족합니다. 사이즈 표를 잘 참고해서 구매하세요.",
            "정품 인증도 되고 배송도 예상보다 빨랐어요. 제품 자체 품질은 흠잡을 곳이 없습니다. 이 가격에 이 품질은 정말 좋은 것 같아요.",
            "여러 브랜드 써봤는데 이 제품이 제일 마음에 들어요. 기능성도 뛰어나고 디자인도 세련됐습니다. 강력 추천합니다!",
            "친구 추천으로 구매했는데 역시 좋네요. 사용하면 할수록 만족도가 높아집니다. 다음에도 재구매할 예정입니다.",
            "처음 사용해봤는데 품질이 정말 좋습니다. 가격도 합리적이고 배송도 빨랐어요. 만족스러운 쇼핑이었습니다."
    };

    private static final String[] WARN_CONTENTS = {
            "나쁘지는 않은데 가격 대비 좀 아쉬운 면이 있어요. 기대했던 것보다는 조금 실망했습니다.",
            "제품 자체는 괜찮은데 포장이 좀 불량이었어요. 재구매 의사는 있지만 가격이 좀 내려갔으면 좋겠어요.",
            "배송이 늦었고 제품도 생각보다 작았어요. 그래도 기능은 잘 됩니다.",
            "색상이 사진과 조금 다르네요. 실제 사용감은 그럭저럭 괜찮습니다.",
            "다른 분들 후기보고 구매했는데 개인차가 있는 것 같아요. 저한테는 보통입니다.",
            "가성비를 생각하면 나쁘지 않아요. 다만 더 좋은 제품들도 있어서 추천하기는 애매합니다."
    };

    private static final String[] DANGER_CONTENTS = {
            "완전대박 인생템 강추강추!!! 무조건 사야됨 역대급이에요!! 진짜 최고최고!!",
            "강추 완전최고 강추 완전최고 재구매확정 강추강추강추!!",
            "짱 좋아요 최고에요 강추합니다 짱짱짱!!!",
            "돈값해요 완벽 실망없음 무조건구매 혜자템!! 강추강추!!",
            "이 제품 안 사면 후회해요!! 무조건 사세요!! 최고최고최고!!",
            "완전 강추!! 역대급 제품!! 이거 사면 인생이 바뀜!! 강추!!",
            "오늘산게 횡재 최고템 강추 무조건추천 짱짱!!!"
    };

    private String pickSafeContent() {
        return SAFE_CONTENTS[RANDOM.nextInt(SAFE_CONTENTS.length)];
    }

    private String pickWarnContent() {
        return WARN_CONTENTS[RANDOM.nextInt(WARN_CONTENTS.length)];
    }

    private String pickDangerContent() {
        return DANGER_CONTENTS[RANDOM.nextInt(DANGER_CONTENTS.length)];
    }
}
