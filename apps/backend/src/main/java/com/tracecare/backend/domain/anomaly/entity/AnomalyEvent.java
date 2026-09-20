package com.tracecare.backend.domain.anomaly.entity;

import java.math.BigDecimal;
import java.time.Instant;

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
 * ARRIVAL_DELAY/UNREGISTERED_STAY 감지 상태의 Source of Truth(DATABASE_DESIGN_GUIDE.md §15.2).
 * {@code NotificationHistory}(type=AI_ANOMALY)는 "그에 대해 실제 발송한 알림 로그"이고, 이 엔티티는 "감지된 이상행동 상태"
 * 자체를 표현한다 — 역할이 분리돼 있다.
 */
@Entity
@Table(name = "AnomalyEvent")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnomalyEvent {

    public static final String TYPE_ARRIVAL_DELAY = "ARRIVAL_DELAY";
    public static final String TYPE_UNREGISTERED_STAY = "UNREGISTERED_STAY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "type", nullable = false, updatable = false)
    private String type;

    @Column(name = "place_id", updatable = false)
    private Long placeId;

    @Column(name = "latitude", updatable = false)
    private BigDecimal latitude;

    @Column(name = "longitude", updatable = false)
    private BigDecimal longitude;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * {@code ARRIVAL_DELAY} 전용 스냅샷 — 이벤트 생성 시점의 예정 도착 시각(그날의 {@code
     * PlaceArrivalSchedule.expected_arrival_time}을 절대 시각으로 변환한 값). {@code /explain} 질문이 역산 없이 쓴다
     * (DATABASE_DESIGN_GUIDE.md §15.7 E). 다른 유형이거나 이 컬럼 추가 이전에 생성된 이벤트는 {@code null}.
     */
    @Column(name = "scheduled_at", updatable = false)
    private Instant scheduledAt;

    /**
     * {@code UNREGISTERED_STAY} 전용 스냅샷 — 후보({@code anomaly:candidate})의 {@code startedAt}, 즉 등록 안 된 곳에서
     * 머물기 시작한 시각. 다른 유형이거나 이 컬럼 추가 이전에 생성된 이벤트는 {@code null}.
     */
    @Column(name = "stay_started_at", updatable = false)
    private Instant stayStartedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private AnomalyEvent(
            Long userId,
            String type,
            Long placeId,
            BigDecimal latitude,
            BigDecimal longitude,
            Instant detectedAt,
            Instant scheduledAt,
            Instant stayStartedAt) {
        this.userId = userId;
        this.type = type;
        this.placeId = placeId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.detectedAt = detectedAt;
        this.scheduledAt = scheduledAt;
        this.stayStartedAt = stayStartedAt;
        this.createdAt = Instant.now();
    }

    /**
     * {@code AnomalyScheduler}가 오늘 요일 스케줄 중 미도착 Place를 찾아 감지한 시점에 생성한다. {@code scheduledAt}은 그날의
     * 예정 도착 시각 스냅샷이다.
     */
    public static AnomalyEvent createArrivalDelay(
            Long userId, Long placeId, Instant detectedAt, Instant scheduledAt) {
        return new AnomalyEvent(
                userId, TYPE_ARRIVAL_DELAY, placeId, null, null, detectedAt, scheduledAt, null);
    }

    /**
     * {@code UnregisteredStayDetector}가 등록 안 된 곳에서 감지 기준(분) 이상 머문 것을 확인한 시점에 생성한다. {@code
     * nearestPlaceId}는 가장 가까운 등록 장소(있으면) 참고용이며, 실제 위치는 {@code latitude}/{@code longitude}다. {@code
     * stayStartedAt}은 체류 시작 시각 스냅샷(후보의 {@code startedAt})이다.
     */
    public static AnomalyEvent createUnregisteredStay(
            Long userId,
            Long nearestPlaceId,
            BigDecimal latitude,
            BigDecimal longitude,
            Instant detectedAt,
            Instant stayStartedAt) {
        return new AnomalyEvent(
                userId,
                TYPE_UNREGISTERED_STAY,
                nearestPlaceId,
                latitude,
                longitude,
                detectedAt,
                null,
                stayStartedAt);
    }

    public boolean isOpen() {
        return resolvedAt == null;
    }

    public boolean isEscalated() {
        return escalatedAt != null;
    }

    /**
     * "최초 승격 시각"만 기록한다(§4.2 확정: A) — Guardian마다 개별 승격 판단이라 같은 이벤트가 여러 번 승격될 수
     * 있는데, 이미 값이 있으면 갱신하지 않아 정말 "처음" 값만 남긴다(정보/표시용, 스캔 필터로는 쓰지 않음 — {@link
     * com.tracecare.backend.domain.anomaly.repository.AnomalyEventRepository#findByResolvedAtIsNull}
     * 참고).
     */
    public void escalate(Instant escalatedAt) {
        if (this.escalatedAt == null) {
            this.escalatedAt = escalatedAt;
        }
    }

    public void resolve(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
