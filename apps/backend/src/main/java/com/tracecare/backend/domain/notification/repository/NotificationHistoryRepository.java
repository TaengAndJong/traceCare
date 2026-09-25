package com.tracecare.backend.domain.notification.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * {@code /summary}·{@code /report/weekly}의 {@code notified} 판별(§3.6) — 주어진 이상행동 id 중 이 Guardian에게
     * {@code excludedStatus}(FAILED)가 아닌 발송 기록이 있는 id만 한 번의 IN 쿼리로 돌려준다. FAILED뿐인 이상행동은 "푸시를
     * 실제로 받지 못했다"이므로 포함되지 않는다. {@code idx_nh_anomaly_event} 파티얼 인덱스를 사용한다.
     */
    @Query(
            "SELECT DISTINCT nh.anomalyEventId FROM NotificationHistory nh "
                    + "WHERE nh.userId = :userId AND nh.anomalyEventId IN :anomalyEventIds "
                    + "AND nh.status <> :excludedStatus")
    List<Long> findNotifiedAnomalyEventIds(
            @Param("userId") Long userId,
            @Param("anomalyEventIds") List<Long> anomalyEventIds,
            @Param("excludedStatus") String excludedStatus);
}
