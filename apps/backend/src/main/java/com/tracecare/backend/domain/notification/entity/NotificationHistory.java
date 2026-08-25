package com.tracecare.backend.domain.notification.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 발송/읽음/응답 이력(DATABASE_DESIGN_GUIDE.md §3.7/§4.7). {@code type}은 DB CHECK로 7종이 고정돼 있어({@link
 * #TYPE_ARRIVAL} 등) 임의로 새 값을 추가하지 않는다 — 이번 세션은 GeoFence 도착(ARRIVAL)만 발행한다. 이탈(GeoFence Exit)은 이 7종에
 * 포함되지 않는다고 이미 결정돼 있어(§13 근거) 알림을 만들지 않는다({@code VisitNotificationListener} 참고).
 *
 * <p>{@code title} 컬럼은 DB에 존재하지 않는다 — {@code type}으로부터 조회 시점에 생성한다({@code NotificationResponse}
 * 참고). {@code body}(사람이 읽는 문구)는 {@code place_name} 스냅샷 컬럼을 함께 저장해 실제 발송 문구를 이력에서도 그대로 복원할 수 있게
 * 한다(2026-08 후속 보완 — VisitHistory.place_name과 동일하게 실시간 FK 조회가 아닌 생성 시점 스냅샷).
 */
@Entity
@Table(name = "NotificationHistory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationHistory {

    public static final String TYPE_ARRIVAL = "ARRIVAL";
    public static final String TYPE_ARRIVAL_CONFIRM_REQUEST = "ARRIVAL_CONFIRM_REQUEST";
    public static final String TYPE_ARRIVAL_CONFIRMED = "ARRIVAL_CONFIRMED";
    public static final String TYPE_EMERGENCY = "EMERGENCY";
    public static final String TYPE_AI_ANOMALY = "AI_ANOMALY";
    public static final String TYPE_AI_PREDICTION = "AI_PREDICTION";
    public static final String TYPE_AI_WEEKLY_REPORT = "AI_WEEKLY_REPORT";

    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_READ = "READ";
    public static final String STATUS_RESPONDED = "RESPONDED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Column(name = "type", nullable = false, updatable = false)
    private String type;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "is_retry", nullable = false, updatable = false)
    private boolean retry;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "response_at")
    private Instant responseAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "place_name", updatable = false)
    private String placeName;

    private NotificationHistory(
            Long userId, Long targetId, String type, UUID eventId, String placeName) {
        this.userId = userId;
        this.targetId = targetId;
        this.type = type;
        this.eventId = eventId;
        this.retry = false;
        this.sentAt = Instant.now();
        this.status = STATUS_SENT;
        this.placeName = placeName;
    }

    /**
     * FCM 발송 시도 직전에 생성한다 — 발송 결과는 {@link #markFailed()}로 저장 전에 반영한다(1건의 INSERT로 최종 상태 확정). {@code
     * placeName}은 GeoFence와 무관한 알림 타입에는 {@code null}을 넘긴다.
     */
    public static NotificationHistory create(
            Long userId, Long targetId, String type, UUID eventId, String placeName) {
        return new NotificationHistory(userId, targetId, type, eventId, placeName);
    }

    public void markFailed() {
        this.status = STATUS_FAILED;
    }

    /** 이미 READ/RESPONDED/FAILED면 아무 것도 하지 않는다(멱등) — SENT 상태에서만 최초 1회 읽음 처리한다. */
    public void markRead() {
        if (!STATUS_SENT.equals(status)) {
            return;
        }
        this.status = STATUS_READ;
        this.readAt = Instant.now();
    }

    public boolean isRead() {
        return STATUS_READ.equals(status) || STATUS_RESPONDED.equals(status);
    }
}
