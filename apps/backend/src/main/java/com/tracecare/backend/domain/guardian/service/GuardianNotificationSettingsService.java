package com.tracecare.backend.domain.guardian.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.auth.AccessDeniedCustomException;
import com.tracecare.backend.common.exception.business.CareTargetNotFoundException;
import com.tracecare.backend.common.exception.infra.DataAccessCustomException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.dto.response.NotificationSettingsResponse;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;

/**
 * 이상행동 알림 설정(GuardianTarget의 {@code notification_mode}/{@code escalate_minutes_*}/{@code paused_until}) 조회·변경·
 * 일시정지·재개(API_Specification.md §3.10, DATABASE_DESIGN_GUIDE.md §15.8).
 *
 * <p><b>권한(호출자 본인 행만)</b>: 요청에 {@code guardianId}를 받지 않고, 조회 조건은 항상 호출자 userId(SecurityContext)와 {@code
 * {id}}(CareTarget public_id → target_id)로 만든다. PRIMARY가 SUB의 행을 지정할 수단이 없어 §15.3 원칙이 구조적으로 지켜지며 PRIMARY/SUB 사이의
 * 권한 차이도 없다. 순서: CareTarget 없음 {@code TARGET_001}(404) → 호출자와 ACTIVE 관계 아님 {@code TARGET_002}(403).
 *
 * <p><b>동시성 — 행 단위 비관적 락</b>: 쓰기 경로(update/pause/resume)는 {@code findActiveByGuardianIdAndTargetIdForUpdate}로 행을
 * 잠근 뒤 상태를 읽고 바꾼다. {@code AnomalyScheduler}의 자동 복귀({@link #resumeIfDue})도 같은 행을 잠그고 <b>다시 확인</b>한다.
 * 그래서 사용자의 정지 연장과 스케줄러 복귀가 겹쳐도 어느 한쪽 변경이 유실되지 않고 순서대로 처리된다(기본 격리 수준 READ COMMITTED에서는 잠금을 기다린
 * 쪽이 커밋된 최신 값을 다시 읽는다). 락 경합 실패는 서버 오류가 아니라 재시도 가능한 {@code COMMON_008}(409)로 변환한다({@link
 * GuardianTargetService#delegatePrimary}와 동일). 외부 호출은 없어 트랜잭션은 짧다.
 */
@Service
public class GuardianNotificationSettingsService {

    private static final Logger log = LoggerFactory.getLogger(GuardianNotificationSettingsService.class);

    private final GuardianTargetRepository guardianTargetRepository;
    private final UserRepository userRepository;

    public GuardianNotificationSettingsService(
            GuardianTargetRepository guardianTargetRepository, UserRepository userRepository) {
        this.guardianTargetRepository = guardianTargetRepository;
        this.userRepository = userRepository;
    }

    /** GET — 잠금 없이 읽는다(읽기 전용, 스케줄러/다른 요청을 막지 않는다). */
    @Transactional(readOnly = true)
    public NotificationSettingsResponse getSettings(Long guardianId, UUID targetPublicId) {
        User target = findTarget(targetPublicId);
        GuardianTarget relation =
                guardianTargetRepository
                        .findByGuardianIdAndTargetIdAndStatus(
                                guardianId, target.getId(), GuardianTarget.STATUS_ACTIVE)
                        .orElseThrow(() -> new AccessDeniedCustomException(ErrorCode.TARGET_002));
        return NotificationSettingsResponse.of(relation);
    }

    /**
     * PUT — 설정 전체 교체. 정지 중이면 정지는 풀지 않고 "돌아갈 기본 모드"만 바뀐다(엔티티의 오버레이 규칙). {@code null} 승격 분은
     * "승격 안 함"이다.
     */
    @Transactional
    public NotificationSettingsResponse updateSettings(
            Long guardianId,
            UUID targetPublicId,
            String notificationMode,
            Integer escalateMinutesArrival,
            Integer escalateMinutesStay) {
        GuardianTarget relation = lockOwnActiveRelation(guardianId, targetPublicId);
        relation.updateNotificationMode(
                notificationMode, escalateMinutesArrival, escalateMinutesStay);
        log.info(
                "event=ANOMALY_NOTIFICATION_SETTINGS_UPDATED, guardianTargetId={}, mode={}, paused={}",
                relation.getId(),
                relation.baseNotificationMode(),
                relation.isPaused());
        return NotificationSettingsResponse.of(relation);
    }

    /**
     * pause — {@code pausedUntil = min( max(기존, now) + durationMinutes, now + 1440분 )}. 계산은 엔티티가 하고, 이 메서드는 서버 시각을
     * 넘긴다(클라이언트 시각은 신뢰하지 않는다). 24시간 상한에 걸려도 예외가 아니라 그대로 200이다.
     */
    @Transactional
    public NotificationSettingsResponse pause(
            Long guardianId, UUID targetPublicId, int durationMinutes) {
        GuardianTarget relation = lockOwnActiveRelation(guardianId, targetPublicId);
        boolean extended = relation.isPaused();
        relation.pause(Instant.now(), durationMinutes);
        log.info(
                "event=ANOMALY_PAUSE_STARTED, guardianTargetId={}, durationMinutes={}, extended={}",
                relation.getId(),
                durationMinutes,
                extended);
        return NotificationSettingsResponse.of(relation);
    }

    /** resume — 정지 중이면 기본 모드로 즉시 복귀, 정지 중이 아니어도 에러가 아니라 현재 설정을 그대로 돌려준다(멱등). */
    @Transactional
    public NotificationSettingsResponse resume(Long guardianId, UUID targetPublicId) {
        GuardianTarget relation = lockOwnActiveRelation(guardianId, targetPublicId);
        if (relation.isPaused()) {
            relation.resumeFromPause();
            log.info("event=ANOMALY_PAUSE_RESUMED_BY_USER, guardianTargetId={}", relation.getId());
        }
        return NotificationSettingsResponse.of(relation);
    }

    /**
     * {@code AnomalyScheduler}의 PAUSED 자동 복귀 전용. 행을 잠근 뒤 <b>잠금 이후의 최신 상태로</b> {@code paused_until} 경과를 다시
     * 확인하고 복귀시킨다. 스케줄러가 잠금 없이 읽은 목록은 이미 오래됐을 수 있다 — 예를 들어 그 사이 사용자가 정지를 연장했다면 여기서 "아직 안 지남"으로
     * 판정되어 연장이 유지된다.
     *
     * @return 실제로 복귀시켰으면 {@code true}. 이미 복귀/연장됐거나 락 경합으로 이번 tick에서 건너뛰면 {@code false}(다음 tick에서 재평가하며
     *     예외를 던지지 않는다 — 스케줄러 전체가 한 행 때문에 멈추지 않게)
     */
    @Transactional
    public boolean resumeIfDue(Long guardianTargetId, Instant now) {
        Optional<GuardianTarget> locked;
        try {
            locked = guardianTargetRepository.findByIdForUpdate(guardianTargetId);
        } catch (PessimisticLockingFailureException e) {
            log.warn(
                    "event=ANOMALY_SETTINGS_LOCK_CONFLICT, path=auto_resume, guardianTargetId={}",
                    guardianTargetId);
            return false;
        }
        if (locked.isEmpty() || !locked.get().isPauseDue(now)) {
            return false;
        }
        locked.get().resumeFromPause();
        return true;
    }

    private GuardianTarget lockOwnActiveRelation(Long guardianId, UUID targetPublicId) {
        User target = findTarget(targetPublicId);
        try {
            return guardianTargetRepository
                    .findActiveByGuardianIdAndTargetIdForUpdate(guardianId, target.getId())
                    .orElseThrow(() -> new AccessDeniedCustomException(ErrorCode.TARGET_002));
        } catch (PessimisticLockingFailureException e) {
            log.warn(
                    "event=ANOMALY_SETTINGS_LOCK_CONFLICT, path=user_write, guardianId={}, targetId={}",
                    guardianId,
                    target.getId());
            throw new DataAccessCustomException(ErrorCode.COMMON_008);
        }
    }

    private User findTarget(UUID targetPublicId) {
        return userRepository
                .findByPublicId(targetPublicId)
                .orElseThrow(CareTargetNotFoundException::new);
    }
}
