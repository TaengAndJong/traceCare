package com.tracecare.backend.domain.guardian.entity;

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
 * Guardian-CareTarget 관계 해소 테이블(DATABASE_DESIGN_GUIDE.md §3.2). 행 생성은 초대(Invitation)+CareTarget 승인
 * 절차로만 이뤄지므로({@link #createActive}), 이 클래스에는 의도적으로 "직접 INSERT용" public 생성자를 두지 않는다.
 *
 * <p><b>이상행동 알림 모드(§15.2)</b>: {@code notificationMode}는 Guardian 개인별 설정이다(PRIMARY가 SUB 것을
 * 대신 설정할 수 없다 — Service 계층에서 "호출자 본인 행인지"만 확인). {@code escalateMinutesArrival}/{@code
 * escalateMinutesStay}는 REALTIME/HYBRID 두 모드가 공유하는 단일 트리거 컬럼이다 — "감지 후 N분 경과하면 즉시 알림 승격"이라는
 * 동일한 메커니즘이고, {@code notificationMode} 값 자체는 UI 프리셋 라벨 역할만 한다(검증 v2 이후 확정, v2의 별도
 * realtime_interval_minutes 컬럼은 폐지 — 감지는 항상 전역 설정({@code anomaly.*-detect-minutes})으로 고정하고, 이
 * 두 컬럼은 감지 이후 "언제 알림으로 승격할지"에만 관여해 AnomalyEvent의 단일 detected_at과 모순되지 않는다).
 */
@Entity
@Table(name = "GuardianTarget")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuardianTarget {

    public static final String ROLE_PRIMARY = "PRIMARY";
    public static final String ROLE_SUB = "SUB";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_TERMINATED = "TERMINATED";

    public static final String NOTIFICATION_MODE_REALTIME = "REALTIME";
    public static final String NOTIFICATION_MODE_REPORT_ONLY = "REPORT_ONLY";
    public static final String NOTIFICATION_MODE_HYBRID = "HYBRID";
    public static final String NOTIFICATION_MODE_PAUSED = "PAUSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "guardian_id", nullable = false, updatable = false)
    private Long guardianId;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Column(name = "guardian_role", nullable = false)
    private String guardianRole;

    @Column(name = "relation")
    private String relation;

    @Column(name = "alias")
    private String alias;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "terminated_at")
    private Instant terminatedAt;

    @Column(name = "notification_mode", nullable = false)
    private String notificationMode;

    @Column(name = "escalate_minutes_arrival")
    private Integer escalateMinutesArrival;

    @Column(name = "escalate_minutes_stay")
    private Integer escalateMinutesStay;

    @Column(name = "previous_notification_mode")
    private String previousNotificationMode;

    @Column(name = "paused_until")
    private Instant pausedUntil;

    private GuardianTarget(Long guardianId, Long targetId, String guardianRole) {
        this.guardianId = guardianId;
        this.targetId = targetId;
        this.guardianRole = guardianRole;
        this.status = STATUS_ACTIVE;
        this.createdAt = Instant.now();
        // DB DEFAULT('HYBRID'/30/60)와 동일값 — INSERT 시 애플리케이션이 값을 명시하지 않으면
        // Hibernate가 컬럼 자체를 생략하지 않고 null을 보내 NOT NULL 제약에 걸리므로 여기서도 맞춰준다.
        this.notificationMode = NOTIFICATION_MODE_HYBRID;
        this.escalateMinutesArrival = 30;
        this.escalateMinutesStay = 60;
    }

    /** CareTarget 승인 트랜잭션(GuardianTargetService)에서만 호출된다 — 정원/PRIMARY 검증은 호출부 책임. */
    public static GuardianTarget createActive(Long guardianId, Long targetId, String guardianRole) {
        return new GuardianTarget(guardianId, targetId, guardianRole);
    }

    public boolean isPrimary() {
        return ROLE_PRIMARY.equals(guardianRole);
    }

    public void updateRelation(String relation, String alias) {
        this.relation = relation;
        this.alias = alias;
    }

    public void promoteToPrimary() {
        this.guardianRole = ROLE_PRIMARY;
    }

    public void demoteToSub() {
        this.guardianRole = ROLE_SUB;
    }

    public void terminate() {
        this.status = STATUS_TERMINATED;
        this.terminatedAt = Instant.now();
    }

    /**
     * GET/PUT 알림 설정 API(§4.4) 전용. {@code PAUSED}로의 전환은 이 메서드가 아니라 {@link
     * #pause(Instant)}로만 한다 — {@code previousNotificationMode}를 함께 기록해야 하기 때문이다.
     */
    public void updateNotificationMode(
            String notificationMode, Integer escalateMinutesArrival, Integer escalateMinutesStay) {
        this.notificationMode = notificationMode;
        this.escalateMinutesArrival = escalateMinutesArrival;
        this.escalateMinutesStay = escalateMinutesStay;
    }

    /** 현재 모드를 {@code previousNotificationMode}에 저장한 뒤 PAUSED로 전환한다. */
    public void pause(Instant pausedUntil) {
        this.previousNotificationMode = this.notificationMode;
        this.notificationMode = NOTIFICATION_MODE_PAUSED;
        this.pausedUntil = pausedUntil;
    }

    /** {@code AnomalyScheduler}가 {@code pausedUntil} 경과를 감지했을 때 호출한다(§4.2 역할2). */
    public void resumeFromPause() {
        this.notificationMode = previousNotificationMode;
        this.previousNotificationMode = null;
        this.pausedUntil = null;
    }

    public boolean isPauseDue(Instant now) {
        return NOTIFICATION_MODE_PAUSED.equals(notificationMode)
                && pausedUntil != null
                && !pausedUntil.isAfter(now);
    }
}
