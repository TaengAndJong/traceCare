package com.tracecare.backend.domain.guardian.entity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.validation.InvalidRequestException;

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
 *
 * <p><b>정지(PAUSED) 오버레이 불변식</b>(API_Specification.md §3.10, DATABASE_DESIGN_GUIDE.md §15.8): 정지는 모드 값이
 * 아니라 기본 모드 위에 덮이는 상태다. 정지 중에는 {@code notificationMode=PAUSED}이고 정지가 풀린 뒤 돌아갈 기본 모드는
 * {@code previousNotificationMode}에 있다. 이 불변식(정지 중 previous를 덮어쓰지 않음, 복귀할 모드가 없으면 HYBRID 폴백, PAUSED는
 * {@link #pause}로만 진입)은 DB CHECK/NOT NULL에 의존하지 않고 이 클래스의 메서드가 직접 보장한다 — DB 제약 위반은 4xx가 아니라 500으로
 * 나가기 때문이다.
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

    /** 정지 1회 요청의 최대 분(24시간). 정지 남은 시간의 총합 상한으로도 쓴다(DATABASE_DESIGN_GUIDE.md §15.8). */
    public static final int MAX_PAUSE_MINUTES = 1440;

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

    /** 정지 중 여부({@code notificationMode=PAUSED}). */
    public boolean isPaused() {
        return NOTIFICATION_MODE_PAUSED.equals(notificationMode);
    }

    /**
     * API에 노출하는 "기본 모드" — 정지 중이 아니면 {@code notificationMode} 그대로, 정지 중이면 정지가 풀린 뒤 돌아갈 모드다
     * ({@code previousNotificationMode}, 기록이 없으면 {@code HYBRID}). API 응답({@code notificationMode})과 {@link #resumeFromPause}의
     * 복귀 모드가 같은 규칙을 쓰도록 여기서 한 번만 계산한다(API_Specification.md §3.10 오버레이).
     */
    public String baseNotificationMode() {
        if (!isPaused()) {
            return notificationMode;
        }
        return previousNotificationMode != null
                ? previousNotificationMode
                : NOTIFICATION_MODE_HYBRID;
    }

    /**
     * 기본 알림 모드와 승격 분을 저장한다(API_Specification.md §3.10 PUT, DATABASE_DESIGN_GUIDE.md §15.8).
     *
     * <ul>
     *   <li><b>{@code PAUSED}는 이 메서드로 설정할 수 없다.</b> 정지 진입은 기간이 필요하고 {@code previousNotificationMode}를 함께
     *       기록해야 하므로 반드시 {@link #pause(Instant, int)}로만 한다. {@code REALTIME}/{@code REPORT_ONLY}/{@code HYBRID}
     *       외의 값(null 포함)은 {@code COMMON_002}({@link InvalidRequestException})로 거부한다 — 조용히 무시하면 호출자는 저장된 줄
     *       알지만 실제로는 반영되지 않기 때문이다. API 계층이 먼저 검증하므로 여기서 걸리면 호출 코드의 결함이다(방어선).
     *   <li><b>오버레이</b>: 정지 중이면 정지를 풀지 않는다. {@code notificationMode}는 {@code PAUSED}로 두고 정지가 풀린 뒤 돌아갈
     *       {@code previousNotificationMode}만 새 값으로 바꾼다. 정지 중이 아니면 {@code notificationMode}를 직접 바꾼다.
     *   <li>{@code escalateMinutes*}는 정지 여부와 무관하게 그대로 갱신한다({@code null}은 "승격 안 함").
     * </ul>
     *
     * @param mode 기본 모드(REALTIME/REPORT_ONLY/HYBRID)
     */
    public void updateNotificationMode(
            String mode, Integer escalateMinutesArrival, Integer escalateMinutesStay) {
        if (!isBaseMode(mode)) {
            throw new InvalidRequestException(ErrorCode.COMMON_002);
        }
        if (isPaused()) {
            this.previousNotificationMode = mode;
        } else {
            this.notificationMode = mode;
        }
        this.escalateMinutesArrival = escalateMinutesArrival;
        this.escalateMinutesStay = escalateMinutesStay;
    }

    /**
     * 이상행동 알림을 일시정지하거나 정지 시간을 늘린다(API_Specification.md §3.10 pause).
     *
     * <p><b>계산식(누적 + 총합 상한)</b>: {@code pausedUntil = min( max(기존 pausedUntil, now) + durationMinutes, now +
     * 1440분 )}. 정지 중이 아니거나 기존 값이 이미 지났으면 {@code now}가 기준이고, 결과는 어떤 경우에도 지금부터 24시간을 넘지 않는다.
     * 상한에 걸려도 예외가 아니다(더 늘어나지 않을 뿐).
     *
     * <p><b>불변식</b>: 정지가 아닌 상태에서 처음 호출될 때만 {@code previousNotificationMode ← 현재 모드}를 저장한다. 이미 정지
     * 중이면 {@code previousNotificationMode}는 <b>절대 건드리지 않고</b> {@code pausedUntil}만 갱신한다 — 정지 중인 상태({@code
     * PAUSED})를 "돌아갈 모드"로 저장하면 {@code ck_gt_prev_notification_mode} 위반이기 때문이다.
     *
     * @param now 계산 기준 시각. 엔티티가 시계를 직접 읽지 않도록 호출하는 Service가 넘긴다(테스트에서 시각 고정 가능).
     * @param durationMinutes 이번 요청으로 늘릴 분. 1~{@value #MAX_PAUSE_MINUTES}, 범위 밖이면 {@code COMMON_002}
     */
    public void pause(Instant now, int durationMinutes) {
        if (durationMinutes < 1 || durationMinutes > MAX_PAUSE_MINUTES) {
            throw new InvalidRequestException(ErrorCode.COMMON_002);
        }
        boolean alreadyPaused = isPaused();
        Instant base = alreadyPaused && pausedUntil != null && pausedUntil.isAfter(now) ? pausedUntil : now;
        Instant requested = base.plus(durationMinutes, ChronoUnit.MINUTES);
        Instant limit = now.plus(MAX_PAUSE_MINUTES, ChronoUnit.MINUTES);

        if (!alreadyPaused) {
            this.previousNotificationMode = this.notificationMode;
            this.notificationMode = NOTIFICATION_MODE_PAUSED;
        }
        this.pausedUntil = requested.isAfter(limit) ? limit : requested;
    }

    /**
     * 정지를 풀고 기본 모드로 복귀한다. {@code AnomalyScheduler}가 {@code pausedUntil} 경과를 감지했을 때(§4.2 역할2)와 사용자의
     * 즉시 재개 API(API_Specification.md §3.10 resume) <b>양쪽에서</b> 호출한다.
     *
     * <p>{@code previousNotificationMode}가 없으면({@code notification_mode} NOT NULL 위반 방지) {@code HYBRID}로 폴백한다.
     * 정지 중이 아니면 아무것도 바꾸지 않는다(no-op) — 폴백이 정상 행의 모드를 {@code HYBRID}로 덮어쓰지 않게 하고, resume API의 멱등성을
     * 엔티티 수준에서도 보장한다.
     */
    public void resumeFromPause() {
        if (!isPaused()) {
            return;
        }
        this.notificationMode = baseNotificationMode();
        this.previousNotificationMode = null;
        this.pausedUntil = null;
    }

    public boolean isPauseDue(Instant now) {
        return isPaused() && pausedUntil != null && !pausedUntil.isAfter(now);
    }

    private static boolean isBaseMode(String mode) {
        return NOTIFICATION_MODE_REALTIME.equals(mode)
                || NOTIFICATION_MODE_REPORT_ONLY.equals(mode)
                || NOTIFICATION_MODE_HYBRID.equals(mode);
    }
}
