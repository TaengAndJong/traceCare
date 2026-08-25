package com.tracecare.backend.domain.notification.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.tracecare.backend.domain.notification.entity.NotificationHistory;

public interface NotificationHistoryRepository extends JpaRepository<NotificationHistory, Long> {

    Optional<NotificationHistory> findByIdAndUserId(Long id, Long userId);

    /**
     * GET /api/guardian/notifications(목록) — {@code idx_nh_user_status(user_id, status) WHERE
     * status<>'READ'} 재사용.
     */
    Page<NotificationHistory> findByUserIdAndStatusNotOrderBySentAtDesc(
            Long userId, String status, Pageable pageable);

    /**
     * GET /api/guardian/notifications/history(전체 이력) — {@code idx_nh_user_sent(user_id, sent_at
     * DESC)} 재사용.
     */
    Page<NotificationHistory> findByUserIdOrderBySentAtDesc(Long userId, Pageable pageable);
}
