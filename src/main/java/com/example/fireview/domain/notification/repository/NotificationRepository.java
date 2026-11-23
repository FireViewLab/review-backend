package com.example.fireview.domain.notification.repository;

import com.example.fireview.domain.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 내 알림 목록 (최신순, 페이징) */
    Page<Notification> findByReceiver_IdOrderByCreatedAtDesc(Long receiverId, Pageable pageable);

    /** 읽지 않은 알림 수 */
    long countByReceiver_IdAndIsReadFalse(Long receiverId);

    /** 내 알림 전체 읽음 처리 */
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.receiver.id = :receiverId AND n.isRead = false")
    int markAllAsRead(@Param("receiverId") Long receiverId);

    /** 단건 조회 (본인 확인) */
    java.util.Optional<Notification> findByIdAndReceiver_Id(Long notificationId, Long receiverId);

    /** 회원 탈퇴(UserDataPurger). 행마다 DELETE 를 보내지 않고 한 문장으로 지운다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Notification x WHERE x.receiver.id = :userId")
    int deleteAllOfUser(@Param("userId") Long userId);
}
