package com.example.fireview.domain.chat.service;

import com.example.fireview.domain.chat.dto.response.ChatSessionResponse;
import com.example.fireview.domain.chat.entity.ChatSession;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 대화 목록에 상품 이름·이미지를 붙인다(#275) */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Import(ChatSessionListService.class)
class ChatSessionListServiceTest {

    private static final String EMAIL = "me@test.com";

    @Autowired ChatSessionListService service;
    @Autowired ProductRepository products;
    @Autowired EntityManagerFactory emf;
    @MockitoBean ChatService chatService;

    private void tag(long id, String platform, String productId, String name) {
        products.saveAndFlush(Product.builder().id(id).name(name).platform(platform.toUpperCase())
                .imageUrl("https://img/" + productId).dataPlatform(platform).dataProductId(productId).build());
    }

    private static ChatSession session(long id, String productId) {
        return ChatSession.builder().id(id).productId(productId).title("대화 " + id).build();
    }

    private List<ChatSessionResponse> list(ChatSession... sessions) {
        when(chatService.getMySessions(eq(EMAIL), any()))
                .thenReturn(new PageImpl<>(List.of(sessions), PageRequest.of(0, 20), sessions.length));
        return service.getMySessions(EMAIL, PageRequest.of(0, 20)).getContent();
    }

    @Test
    void 상품_대화에_이름과_이미지를_붙인다() {
        tag(1L, "kurly", "1000146248", "샘플 상품");

        ChatSessionResponse r = list(session(5L, "kurly-1000146248")).get(0);

        assertThat(r.productName()).isEqualTo("샘플 상품");
        assertThat(r.productImageUrl()).isEqualTo("https://img/1000146248");
        assertThat(r.productId()).isEqualTo("kurly-1000146248");
    }

    @Test
    void 상품_없는_대화와_번호표_없는_상품은_null이다() {
        List<ChatSessionResponse> rs = list(session(5L, null), session(6L, "kurly-999"), session(7L, "900000000000"));

        assertThat(rs).allSatisfy(r -> {
            assertThat(r.productName()).isNull();
            assertThat(r.productImageUrl()).isNull();
        });
    }

    @Test
    void 플랫폼과_상품_ID의_짝이_맞는_상품만_붙인다() {
        // 플랫폼·ID 를 따로 IN 으로 거르므로 kurly-2 를 찾을 때 musinsa-2 가 같이 읽힌다
        tag(1L, "kurly", "1", "컬리 1");
        tag(2L, "musinsa", "2", "무신사 2");

        List<ChatSessionResponse> rs = list(session(5L, "kurly-2"), session(6L, "musinsa-1"));

        assertThat(rs).extracting(ChatSessionResponse::productName).containsOnlyNulls();
    }

    @Test
    void 대화가_많아도_상품은_쿼리_한_번으로_읽는다() {
        for (int i = 1; i <= 10; i++) tag(i, "kurly", String.valueOf(i), "상품 " + i);
        ChatSession[] sessions = new ChatSession[10];
        for (int i = 1; i <= 10; i++) sessions[i - 1] = session(i, "kurly-" + i);
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        List<ChatSessionResponse> rs = list(sessions);

        assertThat(rs).extracting(ChatSessionResponse::productName).doesNotContainNull();
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }
}
