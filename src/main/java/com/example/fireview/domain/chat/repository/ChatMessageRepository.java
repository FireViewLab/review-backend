package com.example.fireview.domain.chat.repository;

import com.example.fireview.domain.chat.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySession_IdOrderByCreatedAtAsc(Long sessionId);

    /** 프롬프트에 실을 최근 대화만 가져온다. 이력 전체를 보내면 토큰이 선형으로 늘어난다. */
    List<ChatMessage> findBySession_IdAndBlockedFalseOrderByCreatedAtDesc(Long sessionId, Pageable pageable);

    /** 대화 삭제(#275). 세션이 메시지에 FK 로 물려 있어 메시지를 먼저 지운다. 한 문장으로 지워 메시지마다 DELETE 를 보내지 않는다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ChatMessage m WHERE m.session.id IN :sessionIds")
    int deleteBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);
}
