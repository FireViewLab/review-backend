package com.example.fireview.domain.chat.service;

import com.example.fireview.domain.chat.client.LlmClient;
import com.example.fireview.domain.chat.entity.ChatTier;
import com.example.fireview.domain.chat.port.ProductAnalysisPort;
import com.example.fireview.domain.chat.repository.ChatMessageRepository;
import com.example.fireview.domain.chat.repository.ChatSessionRepository;
import com.example.fireview.domain.user.entity.OAuthProvider;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.repository.UserRepository;
import com.example.fireview.domain.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
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
 * 반복 담기 이력 쿼리가 DB 에서 실제로 실패해도 대화 저장 트랜잭션이 커밋되는지 확인한다(#271 리뷰).
 *
 * <p>목으로 예외를 던지면 트랜잭션 인터셉터를 거치지 않아 rollback-only 표시가 재현되지 않는다.
 * 그래서 진짜 RepurchaseFinder 를 쓰고 cart_events 테이블 이름을 잠깐 바꿔 쿼리가 진짜로 실패하게 한다.
 * 테스트 자체 트랜잭션은 끈다. ChatService.ask 의 트랜잭션이 실제로 커밋돼야 하기 때문이다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ChatService.class, ChatRecommendationService.class,
        com.example.fireview.domain.repurchase.RepurchaseFinder.class, PromptAssembler.class, TopicGuard.class,
        ChatPlanPolicy.class, ChatQuotaStore.class, JacksonAutoConfiguration.class, com.example.fireview.global.config.ClockConfig.class})
class ChatRepurchaseFailureIsolationTest {

    private static final String EMAIL = "repurchase-isolation@test.com";
    private static final String PRODUCT_ID = "kurly-1000";

    @Autowired ChatService chatService;
    @Autowired ChatQuotaStore quotaStore;
    @Autowired UserRepository userRepository;
    @Autowired ChatSessionRepository sessionRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired DataSource dataSource;

    @MockitoBean LlmClient llmClient;
    @MockitoBean com.example.fireview.domain.cartschedule.service.CartScheduleService cartScheduleService;
    @MockitoBean ProductAnalysisPort productAnalysisPort;
    @MockitoBean UserService userService;

    private JdbcTemplate jdbc;
    private User user;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        user = userRepository.save(User.builder()
                .email(EMAIL).nickname("tester").provider(OAuthProvider.LOCAL).build());
        when(userService.findByEmail(EMAIL)).thenReturn(user);
        when(productAnalysisPort.findContext(anyString())).thenReturn(Optional.empty());
        when(llmClient.complete(anyString(), any(), anyString(), any()))
                .thenReturn(new LlmClient.LlmResponse(
                        "ONTOPIC: yes\nRECOMMEND: no\nSCHEDULE: none\n---\n아직 반복해서 담은 상품이 없어요.", 1800, 60));
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("ALTER TABLE IF EXISTS cart_events_broken RENAME TO cart_events");
        messageRepository.deleteAll();
        sessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void 이력_쿼리가_DB에서_실패해도_대화는_커밋되고_답변이_나간다() {
        jdbc.execute("ALTER TABLE cart_events RENAME TO cart_events_broken");

        ChatService.ChatResult result =
                chatService.ask(EMAIL, ChatTier.STANDARD, null, null, "다시 살 거 있어?");

        assertThat(result.blocked()).isFalse();
        assertThat(result.answer()).isEqualTo("아직 반복해서 담은 상품이 없어요.");
        assertThat(quotaStore.used(user.getId())).isEqualTo(1);
        assertThat(sessionRepository.count()).isEqualTo(1);
        assertThat(messageRepository.count()).isEqualTo(2);
    }
}
