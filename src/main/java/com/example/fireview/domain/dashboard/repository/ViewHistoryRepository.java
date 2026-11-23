package com.example.fireview.domain.dashboard.repository;

import com.example.fireview.domain.dashboard.entity.ViewHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ViewHistoryRepository extends JpaRepository<ViewHistory, Long> {
    List<ViewHistory> findTop10ByUser_IdOrderByViewedAtDesc(Long userId);
    boolean existsByUser_IdAndProduct_Id(Long userId, Long productId);

    /** 회원 탈퇴(UserDataPurger). 행마다 DELETE 를 보내지 않고 한 문장으로 지운다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ViewHistory x WHERE x.user.id = :userId")
    int deleteAllOfUser(@Param("userId") Long userId);
}
