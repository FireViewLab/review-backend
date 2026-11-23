package com.example.fireview.domain.chat.repository;

import com.example.fireview.domain.chat.entity.ChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    Page<ChatSession> findByUser_IdOrderByLastMessageAtDesc(Long userId, Pageable pageable);

    /** 전체 삭제(#275)에서 지울 세션 번호만 읽는다 */
    @Query("SELECT s.id FROM ChatSession s WHERE s.user.id = :userId")
    List<Long> findIdsByUserId(@Param("userId") Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ChatSession s WHERE s.id IN :ids")
    int deleteByIds(@Param("ids") Collection<Long> ids);
}
