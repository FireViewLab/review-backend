package com.example.fireview.global.security;

import com.example.fireview.domain.cart.service.CartService;
import com.example.fireview.domain.chat.service.ChatService;
import com.example.fireview.domain.dataserver.service.DataProductService;
import com.example.fireview.domain.dataserver.service.DataProductTagService;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.report.service.ReportService;
import com.example.fireview.domain.user.entity.OAuthProvider;
import com.example.fireview.domain.user.entity.PlanTier;
import com.example.fireview.domain.user.entity.Role;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.wishlist.service.WishlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증이 필요한 경로(번호표 발급 · 찜 · 장바구니 · 장바구니 예약 · 반복 담기 추천 · 신고 · 챗봇)를 실제 필터 체인으로 검증한다.
 *
 * <p>Swagger 로 손으로 확인하던 구간을 자동화한 것이다. 토큰은 운영과 같은
 * {@link JwtTokenProvider} 로 발급해 {@code SecurityConfig} 의 JwtDecoder 를 그대로 통과시킨다.
 * 서비스 계층은 모두 목으로 막아 Data 서버·LLM·Redis 없이 돈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRequiredEndpointsTest {

    private static final String EMAIL = "auth-test@fireview.com";

    private static final String REPORT_BODY = """
            { "reason": "OTHER", "detail": "상세 내용은 스무 자 이상이어야 검증을 통과합니다." }
            """;
    private static final String CHAT_BODY = """
            { "question": "이 상품 리뷰 믿을 만해?" }
            """;

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired JwtEncoder jwtEncoder;

    @MockitoBean DataProductService dataProductService;
    @MockitoBean DataProductTagService dataProductTagService;
    @MockitoBean WishlistService wishlistService;
    @MockitoBean CartService cartService;
    @MockitoBean com.example.fireview.domain.cartschedule.service.CartScheduleService cartScheduleService;
    @MockitoBean com.example.fireview.domain.repurchase.RepurchaseService repurchaseService;
    @MockitoBean ReportService reportService;
    @MockitoBean ChatService chatService;

    private String bearerToken;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .id(1L)
                .email(EMAIL)
                .nickname("인증테스트")
                .role(Role.USER)
                .provider(OAuthProvider.LOCAL)
                .createdAt(LocalDateTime.now())
                .build();
        bearerToken = "Bearer " + jwtTokenProvider.generateToken(user);

        // 컨트롤러가 응답을 조립하다 null 로 터지지 않도록 필요한 것만 채운다
        when(dataProductTagService.resolveOrCreate(any())).thenReturn(Product.builder()
                .id(42L).name("샘플 상품").dataPlatform("kurly").dataProductId("1000146248").build());
        when(chatService.getQuotaStatus(anyString())).thenReturn(new ChatService.QuotaStatus(
                PlanTier.FREE, 5, 0, 5, false, Instant.now()));
        when(chatService.getMySessions(anyString(), any())).thenReturn(Page.empty());
        when(chatService.getMessages(anyString(), anyLong())).thenReturn(List.of());
    }

    /** 인증 필요 경로 목록. 빌더는 상태를 가지므로 호출할 때마다 새로 만든다 */
    static Stream<Arguments> protectedEndpoints() {
        return Stream.of(
                endpoint("번호표 발급", () -> post("/api/v2/products/kurly/1000146248/tag")),

                endpoint("찜 목록", () -> get("/api/wishlist")),
                endpoint("찜 추가", () -> post("/api/wishlist/1")),
                endpoint("찜 삭제", () -> delete("/api/wishlist/1")),
                endpoint("찜 여부", () -> get("/api/wishlist/1/check")),

                endpoint("장바구니 조회", () -> get("/api/cart")),
                endpoint("장바구니 추가", () -> post("/api/cart/1")),
                endpoint("장바구니 수량 변경", () -> put("/api/cart/1").param("quantity", "2")),
                endpoint("장바구니 삭제", () -> delete("/api/cart/1")),
                endpoint("장바구니 비우기", () -> delete("/api/cart")),
                endpoint("장바구니 예약 확정", () -> post("/api/cart-schedules/1/confirm")),
                endpoint("장바구니 예약 목록", () -> get("/api/cart-schedules")),
                endpoint("장바구니 예약 취소", () -> delete("/api/cart-schedules/1")),
                endpoint("다시 살 때 된 상품", () -> get("/api/repurchase")),
                endpoint("반복 담기 정기 예약 걸기", () -> post("/api/repurchase/1/schedule")),

                endpoint("리뷰 신고", () -> post("/api/reports/reviews/1")
                        .contentType(MediaType.APPLICATION_JSON).content(REPORT_BODY)),
                endpoint("외부 리뷰 신고", () -> post("/api/reports/external/kurly/1000146248/reviews/r-1")
                        .contentType(MediaType.APPLICATION_JSON).content(REPORT_BODY)),
                endpoint("내 신고 목록", () -> get("/api/reports/me")),
                endpoint("내 신고 상세", () -> get("/api/reports/me/1")),

                endpoint("챗봇 질문", () -> post("/api/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY)),
                endpoint("챗봇 질문(프로)", () -> post("/api/chat/pro/messages")
                        .contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY)),
                endpoint("챗봇 사용량", () -> get("/api/chat/quota")),
                endpoint("챗봇 대화 목록", () -> get("/api/chat/sessions")),
                endpoint("챗봇 대화 내용", () -> get("/api/chat/sessions/1/messages"))
        );
    }

    private static Arguments endpoint(String name, Supplier<MockHttpServletRequestBuilder> request) {
        return Arguments.of(name, request);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void 토큰_없이_호출하면_401_과_공통_에러_포맷(String name, Supplier<MockHttpServletRequestBuilder> request)
            throws Exception {
        expectUnauthorized(mockMvc.perform(request.get()));

        assertNoServiceCalled();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void 서명이_틀린_토큰이면_401_과_공통_에러_포맷(String name, Supplier<MockHttpServletRequestBuilder> request)
            throws Exception {
        // 서명의 첫 글자를 바꿔 서명 검증에 실패시킨다.
        // 마지막 글자는 base64url 패딩 비트를 담고 있어 바꿔도 서명이 그대로 통과할 수 있다
        int signatureStart = bearerToken.lastIndexOf('.') + 1;
        char first = bearerToken.charAt(signatureStart);
        String tampered = bearerToken.substring(0, signatureStart)
                + (first == 'A' ? 'Q' : 'A')
                + bearerToken.substring(signatureStart + 1);

        expectInvalidTokenChallenge(
                mockMvc.perform(request.get().header(HttpHeaders.AUTHORIZATION, tampered)));

        assertNoServiceCalled();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void 만료된_토큰이면_401_과_공통_에러_포맷(String name, Supplier<MockHttpServletRequestBuilder> request)
            throws Exception {
        expectInvalidTokenChallenge(
                mockMvc.perform(request.get().header(HttpHeaders.AUTHORIZATION, expiredBearerToken())));

        assertNoServiceCalled();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void 유효한_토큰이면_인증을_통과한다(String name, Supplier<MockHttpServletRequestBuilder> request)
            throws Exception {
        MvcResult result = mockMvc.perform(request.get().header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andReturn();

        // 찜·장바구니 추가는 201, 나머지는 200 이다.
        // 챗봇 질문은 DeferredResult 라 비동기로 시작되고, 그 시점의 상태가 200 이다
        assertThat(result.getResponse().getStatus())
                .as("%s: 인증·인가 단계에서 막히면 안 된다", name)
                .isBetween(200, 299);
    }

    @Test
    void 토큰의_subject_가_컨트롤러에_사용자_식별자로_전달된다() throws Exception {
        mockMvc.perform(get("/api/wishlist").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk());
        verify(wishlistService).getWishlist(EMAIL);

        mockMvc.perform(post("/api/reports/external/kurly/1000146248/reviews/r-1")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(REPORT_BODY))
                .andExpect(status().isOk());
        verify(reportService).createExternalReport(
                eq("kurly"), eq("1000146248"), eq("r-1"), eq(EMAIL), any());

        mockMvc.perform(post("/api/chat/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY))
                .andExpect(status().isOk());
        // 챗봇은 별도 executor 에서 서비스를 부른다
        verify(chatService, timeout(2_000)).ask(eq(EMAIL), any(), isNull(), isNull(), anyString());
    }

    @Test
    void 번호표_발급은_인증되면_번호를_돌려준다() throws Exception {
        mockMvc.perform(post("/api/v2/products/kurly/1000146248/tag")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.springProductId").value(42))
                .andExpect(jsonPath("$.data.externalId").value("kurly-1000146248"));
    }

    // ───────────── /api/v2/products 규칙 순서: GET 은 공개, POST 는 인증 ─────────────

    @Test
    void v2_상품_상세_GET_은_비로그인도_허용된다() throws Exception {
        mockMvc.perform(get("/api/v2/products/kurly/1000146248"))
                .andExpect(status().isOk());

        verify(dataProductService).getProduct("kurly", "1000146248", null);
    }

    @Test
    void v2_수집_job_GET_은_비로그인도_인증에서_막히지_않는다() throws Exception {
        // 목이 빈 Optional 을 돌려주므로 404 가 정상. 401 이 아니라는 게 요점이다
        mockMvc.perform(get("/api/v2/products/collection-jobs/1"))
                .andExpect(status().isNotFound());

        verify(dataProductService).getJob(1L);
    }

    @Test
    void v2_POST_는_번호표_외_경로도_비로그인이면_401() throws Exception {
        // POST 규칙이 permitAll 보다 앞에 있으므로, 매핑이 없는 경로라도 405 가 아니라 401 이다
        mockMvc.perform(post("/api/v2/products/kurly/1000146248"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void v2_번호표_발급은_비로그인이면_Data_서버까지_가지_않는다() throws Exception {
        mockMvc.perform(post("/api/v2/products/kurly/1000146248/tag"))
                .andExpect(status().isUnauthorized());

        verify(dataProductTagService, never()).resolveOrCreate(any());
    }

    /** 토큰 없음 · 위조 · 만료 모두 같은 본문이어야 프론트가 401 을 한 가지로 파싱한다 */
    private ResultActions expectUnauthorized(ResultActions result) throws Exception {
        return result.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
    }

    /**
     * 위조·만료 토큰은 공통 본문에 더해 RFC 6750 §3 의 Bearer challenge 를 내려준다.
     * JwtDecoder 의 예외 메시지는 error_description 에 실리지 않고 고정 문구여야 한다
     */
    private void expectInvalidTokenChallenge(ResultActions result) throws Exception {
        String challenge = expectUnauthorized(result)
                .andReturn().getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE);

        assertThat(challenge)
                .startsWith("Bearer ")
                .contains("error=\"invalid_token\"")
                .contains("error_description=\"The access token is invalid or expired\"")
                .contains("error_uri=\"https://tools.ietf.org/html/rfc6750#section-3.1\"");
    }

    /**
     * 운영과 같은 키로 서명하되 exp 만 과거로 둔다. 서명은 맞으므로 만료 검증에서만 떨어진다.
     * JwtTimestampValidator 의 기본 허용 오차(60초)를 넘기도록 충분히 과거로 잡는다
     */
    private String expiredBearerToken() {
        Instant issuedAt = Instant.now().minus(Duration.ofHours(2));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("fireview")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofHours(1)))
                .subject(EMAIL)
                .claim("role", Role.USER.name())
                .claim("userId", 1L)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private void assertNoServiceCalled() {
        verifyNoInteractions(dataProductTagService, wishlistService, cartService, reportService);
        // getQuotaStatus 등은 setUp 에서 스텁만 했고 호출은 없어야 한다
        verify(chatService, never()).ask(anyString(), any(), any(), any(), anyString());
        verify(chatService, never()).getQuotaStatus(anyString());
        verify(chatService, never()).getMySessions(anyString(), any());
        verify(chatService, never()).getMessages(anyString(), anyLong());
    }
}
