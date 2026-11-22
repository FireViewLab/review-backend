package com.example.fireview.domain.chat.service;

import com.example.fireview.domain.chat.client.LlmClient;
import com.example.fireview.domain.chat.entity.ChatTier;
import com.example.fireview.domain.chat.port.ProductAnalysisPort;
import com.example.fireview.domain.chat.repository.ChatMessageRepository;
import com.example.fireview.domain.chat.repository.ChatSessionRepository;
import com.example.fireview.domain.product.entity.Category;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import com.example.fireview.domain.user.entity.OAuthProvider;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.repository.UserRepository;
import com.example.fireview.domain.user.service.UserService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 커넥션이 하나뿐인 풀에서도 추천이 붙는지 확인한다.
 *
 * <p>대화 트랜잭션이 커넥션을 쥔 채 추천이 두 번째 커넥션을 요구하면, 풀이 1개일 때 추천은
 * 커넥션 대기 시간 초과로 실패하고 빈 목록으로 대체된다. 추천 카드가 실제로 나오면 대화
 * 트랜잭션이 커넥션을 돌려준 뒤에 추천을 조회했다는 뜻이다.
 *
 * <p>풀 설정이 적용되도록 내장 DB 교체를 끄고 이 테스트 전용 H2 를 쓴다. 테스트 자체
 * 트랜잭션도 끈다. 테스트가 커넥션을 쥐고 있으면 그 자체로 풀이 바닥난다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:chat_pool_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.connection-timeout=250"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ChatService.class, ChatRecommendationService.class, PromptAssembler.class, TopicGuard.class,
        ChatPlanPolicy.class, ChatQuotaStore.class, JacksonAutoConfiguration.class, com.example.fireview.global.config.ClockConfig.class})
class ChatRecommendationConnectionPoolTest {

    private static final String EMAIL = "pool@test.com";
    private static final String PRODUCT_ID = "kurly-1000";

    @Autowired ChatService chatService;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ChatSessionRepository sessionRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired DataSource dataSource;

    @MockitoBean LlmClient llmClient;
    @MockitoBean ProductAnalysisPort productAnalysisPort;
    @MockitoBean UserService userService;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .email(EMAIL).nickname("tester").provider(OAuthProvider.LOCAL).build());
        when(userService.findByEmail(EMAIL)).thenReturn(user);
        when(productAnalysisPort.findContext(anyString())).thenReturn(Optional.empty());
        when(llmClient.complete(anyString(), any(), anyString(), any()))
                .thenReturn(new LlmClient.LlmResponse(
                        "ONTOPIC: yes\nRECOMMEND: yes\n---\n비슷한 상품이 있으면 아래에 보여 드릴게요.", 1800, 60));

        saveProduct(1L, "1000", 50);
        saveProduct(2L, "2001", 10);
    }

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
        sessionRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void 커넥션이_하나뿐인_풀에서도_대화를_커밋한_뒤_추천을_붙인다() throws Exception {
        assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(1);

        ChatService.ChatResult result =
                chatService.ask(EMAIL, ChatTier.STANDARD, null, PRODUCT_ID, "비슷한 거 없어?");

        assertThat(result.blocked()).isFalse();
        assertThat(result.recommendations())
                .extracting(ChatRecommendation::externalId)
                .containsExactly("kurly-2001");
        assertThat(sessionRepository.count()).isEqualTo(1);
        assertThat(messageRepository.count()).isEqualTo(2);
    }

    private void saveProduct(Long id, String dataProductId, int reviewCount) {
        productRepository.save(Product.builder()
                .id(id).name("상품 " + dataProductId).category(Category.BEAUTY_SKINCARE)
                .platform("KURLY").price(20000L).reviewCount(reviewCount)
                .dataPlatform("kurly").dataProductId(dataProductId)
                .build());
    }
}
