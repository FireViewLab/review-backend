package com.example.fireview.domain.chat.repository;

import com.example.fireview.domain.chat.entity.ChatMessage;
import com.example.fireview.domain.chat.entity.ChatRole;
import com.example.fireview.domain.chat.entity.ChatSession;
import com.example.fireview.domain.user.entity.OAuthProvider;
import com.example.fireview.domain.user.entity.User;
import com.example.fireview.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 대화 삭제 쿼리가 실제 외래 키 아래에서 지우는지 확인한다(#275) */
@DataJpaTest
@ActiveProfiles("test")
class ChatSessionDeleteJpaTest {

    @Autowired ChatSessionRepository sessions;
    @Autowired ChatMessageRepository messages;
    @Autowired UserRepository users;
    @Autowired EntityManager em;

    private User user(String email) {
        return users.save(User.builder().email(email).nickname(email).provider(OAuthProvider.LOCAL).build());
    }

    private ChatSession sessionWithMessages(User owner) {
        ChatSession session = sessions.save(ChatSession.builder().user(owner).title("대화").build());
        for (ChatRole role : List.of(ChatRole.USER, ChatRole.ASSISTANT)) {
            messages.save(ChatMessage.builder().session(session).role(role).content("내용")
                    .createdAt(LocalDateTime.now()).build());
        }
        return session;
    }

    @Test
    void 메시지와_세션을_지우고_남의_대화는_남긴다() {
        User me = user("me@test.com");
        User other = user("other@test.com");
        ChatSession mine1 = sessionWithMessages(me);
        ChatSession mine2 = sessionWithMessages(me);
        ChatSession theirs = sessionWithMessages(other);
        em.flush();
        em.clear();

        List<Long> ids = sessions.findIdsByUserId(me.getId());
        assertThat(ids).containsExactlyInAnyOrder(mine1.getId(), mine2.getId());

        assertThat(messages.deleteBySessionIds(ids)).isEqualTo(4);
        assertThat(sessions.deleteByIds(ids)).isEqualTo(2);
        em.flush();

        assertThat(sessions.findAll()).extracting(ChatSession::getId).containsExactly(theirs.getId());
        assertThat(messages.findBySession_IdOrderByCreatedAtAsc(theirs.getId())).hasSize(2);
    }
}
