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

    /**
     * {@code AnomalyScheduler} 역할2(승격) — "이 Guardian이 이 AnomalyEvent에 대해 이미 통지받았는지"를 Guardian별로
     * 개별 판단하기 위해 쓴다(§4.2 확정: A). {@code idx_nh_anomaly_event(anomaly_event_id) WHERE
     * anomaly_event_id IS NOT NULL} 파티얼 인덱스로 지원된다.
     */
    boolean existsByAnomalyEventIdAndUserId(Long anomalyEventId, Long userId);
}
