package com.tracecare.backend.domain.guardian.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.auth.AccessDeniedCustomException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.dto.response.NotificationSettingsResponse;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;

/**
 * API_Specification.md §3.10 / DATABASE_DESIGN_GUIDE.md §15.8 — 이상행동 알림 설정 서비스의 권한(호출자 본인 행, TARGET_001/002), 쓰기 경로의 행 잠금
 * 사용, 정지 누적+상한, 재개 멱등, 스케줄러 복귀({@code resumeIfDue})의 "잠금 후 재확인"을 검증한다. 시각({@code Instant.now()})은 서비스가 직접
 * 읽으므로 호출 전후 시각 범위로 단언한다(계산식 자체는 GuardianTargetNotificationTest가 고정 시각으로 검증).
 */
@ExtendWith(MockitoExtension.class)
class GuardianNotificationSettingsServiceTest {

    private static final Long GUARDIAN_ID = 1L;
    private static final Long TARGET_ID = 2L;
    private static final Long RELATION_ID = 30L;

    @Mock private GuardianTargetRepository guardianTargetRepository;
    @Mock private UserRepository userRepository;

    private final UUID targetPublicId = UUID.randomUUID();
    private User target;
    private GuardianTarget relation;

    @BeforeEach
    void setUp() {
        target = User.createFromOAuth("care@example.com", "GOOGLE", "care-oauth");
        ReflectionTestUtils.setField(target, "id", TARGET_ID);
        ReflectionTestUtils.setField(target, "publicId", targetPublicId);
        relation = GuardianTarget.createActive(GUARDIAN_ID, TARGET_ID, GuardianTarget.ROLE_SUB);
        ReflectionTestUtils.setField(relation, "id", RELATION_ID);
    }

    private GuardianNotificationSettingsService service() {
        return new GuardianNotificationSettingsService(guardianTargetRepository, userRepository);
    }

    private void givenTarget() {
        when(userRepository.findByPublicId(targetPublicId)).thenReturn(Optional.of(target));
    }

    private void givenOwnRelationForRead() {
        givenTarget();
        when(guardianTargetRepository.findByGuardianIdAndTargetIdAndStatus(
                        GUARDIAN_ID, TARGET_ID, GuardianTarget.STATUS_ACTIVE))
                .thenReturn(Optional.of(relation));
    }

    private void givenOwnRelationLockedForWrite() {
        givenTarget();
        when(guardianTargetRepository.findActiveByGuardianIdAndTargetIdForUpdate(
                        GUARDIAN_ID, TARGET_ID))
                .thenReturn(Optional.of(relation));
    }

    private void makePaused(String previousMode, Instant pausedUntil) {
        ReflectionTestUtils.setField(
                relation, "notificationMode", GuardianTarget.NOTIFICATION_MODE_PAUSED);
        ReflectionTestUtils.setField(relation, "previousNotificationMode", previousMode);
        ReflectionTestUtils.setField(relation, "pausedUntil", pausedUntil);
    }

    private void assertBusinessError(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    // ---------------------------------------------------------------------
    // GET
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("조회 성공 — 기본값(HYBRID/30/60, 정지 아님)을 반환하고 읽기 전용이라 행 잠금을 잡지 않는다")
    void getSettings_ownActiveRelation_returnsDefaultsWithoutLock() {
        // given
        givenOwnRelationForRead();

        // when
        NotificationSettingsResponse response = service().getSettings(GUARDIAN_ID, targetPublicId);

        // then
        assertThat(response.getNotificationMode()).isEqualTo("HYBRID");
        assertThat(response.getEscalateMinutesArrival()).isEqualTo(30);
        assertThat(response.getEscalateMinutesStay()).isEqualTo(60);
        assertThat(response.isPaused()).isFalse();
        assertThat(response.getPausedUntil()).isNull();
        verify(guardianTargetRepository, never())
                .findActiveByGuardianIdAndTargetIdForUpdate(any(), any());
    }

    @Test
    @DisplayName("조회 — 정지 중이면 PAUSED를 노출하지 않고 돌아갈 기본 모드와 paused/pausedUntil로 표현한다(오버레이)")
    void getSettings_paused_exposesBaseModeAndPausedOverlay() {
        // given
        Instant until = Instant.parse("2026-09-21T09:00:00Z");
        makePaused(GuardianTarget.NOTIFICATION_MODE_REALTIME, until);
        givenOwnRelationForRead();

        // when
        NotificationSettingsResponse response = service().getSettings(GUARDIAN_ID, targetPublicId);

        // then
        assertThat(response.getNotificationMode()).isEqualTo("REALTIME");
        assertThat(response.isPaused()).isTrue();
        assertThat(response.getPausedUntil()).isEqualTo(until);
    }

    @Test
    @DisplayName("조회 — CareTarget이 없으면 TARGET_001이다")
    void getSettings_targetNotFound_throwsTarget001() {
        // given
        when(userRepository.findByPublicId(targetPublicId)).thenReturn(Optional.empty());

        // when & then
        assertBusinessError(() -> service().getSettings(GUARDIAN_ID, targetPublicId), ErrorCode.TARGET_001);
    }

    @Test
    @DisplayName("조회 — 호출자와 ACTIVE 관계가 아니면(없음/해제/PENDING) TARGET_002(403)이다")
    void getSettings_noActiveRelation_throwsTarget002() {
        // given
        givenTarget();
        when(guardianTargetRepository.findByGuardianIdAndTargetIdAndStatus(
                        GUARDIAN_ID, TARGET_ID, GuardianTarget.STATUS_ACTIVE))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service().getSettings(GUARDIAN_ID, targetPublicId))
                .isInstanceOfSatisfying(
                        AccessDeniedCustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TARGET_002));
    }

    // ---------------------------------------------------------------------
    // PUT
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("수정 성공 — 모드와 승격 분을 저장하고 행 잠금 조회를 사용한다")
    void updateSettings_notPaused_savesUnderRowLock() {
        // given
        givenOwnRelationLockedForWrite();

        // when
        NotificationSettingsResponse response =
                service()
                        .updateSettings(GUARDIAN_ID, targetPublicId, "REALTIME", 10, 20);

        // then
        assertThat(response.getNotificationMode()).isEqualTo("REALTIME");
        assertThat(relation.getNotificationMode()).isEqualTo("REALTIME");
        assertThat(relation.getEscalateMinutesArrival()).isEqualTo(10);
        assertThat(relation.getEscalateMinutesStay()).isEqualTo(20);
        verify(guardianTargetRepository).findActiveByGuardianIdAndTargetIdForUpdate(GUARDIAN_ID, TARGET_ID);
    }

    @Test
    @DisplayName("수정 — 명시적 null 승격 분은 '승격 안 함'으로 저장된다")
    void updateSettings_explicitNullMinutes_storedAsNoEscalation() {
        // given
        givenOwnRelationLockedForWrite();

        // when
        NotificationSettingsResponse response =
                service().updateSettings(GUARDIAN_ID, targetPublicId, "HYBRID", null, null);

        // then
        assertThat(response.getEscalateMinutesArrival()).isNull();
        assertThat(response.getEscalateMinutesStay()).isNull();
        assertThat(relation.getEscalateMinutesArrival()).isNull();
    }

    @Test
    @DisplayName("수정 — 정지 중이면 정지를 풀지 않고 돌아갈 기본 모드만 바꾼다(응답의 paused/pausedUntil 유지)")
    void updateSettings_whilePaused_keepsPauseAndChangesBaseMode() {
        // given
        Instant until = Instant.now().plus(Duration.ofMinutes(30));
        makePaused(GuardianTarget.NOTIFICATION_MODE_HYBRID, until);
        givenOwnRelationLockedForWrite();

        // when
        NotificationSettingsResponse response =
                service().updateSettings(GUARDIAN_ID, targetPublicId, "REPORT_ONLY", 15, 45);

        // then
        assertThat(response.isPaused()).isTrue();
        assertThat(response.getPausedUntil()).isEqualTo(until);
        assertThat(response.getNotificationMode()).isEqualTo("REPORT_ONLY");
        assertThat(relation.getNotificationMode()).isEqualTo(GuardianTarget.NOTIFICATION_MODE_PAUSED);
        assertThat(relation.getPreviousNotificationMode()).isEqualTo("REPORT_ONLY");
    }

    @Test
    @DisplayName("수정 — PAUSED 값이 넘어오면(API 검증을 우회한 호출) 엔티티가 COMMON_002로 거부하고 상태를 바꾸지 않는다")
    void updateSettings_pausedModeValue_isRejectedWithoutChange() {
        // given
        givenOwnRelationLockedForWrite();

        // when & then
        assertBusinessError(
                () -> service().updateSettings(GUARDIAN_ID, targetPublicId, "PAUSED", 10, 20),
                ErrorCode.COMMON_002);
        assertThat(relation.getNotificationMode()).isEqualTo("HYBRID");
        assertThat(relation.getEscalateMinutesArrival()).isEqualTo(30);
    }

    @Test
    @DisplayName("수정 — 호출자와 ACTIVE 관계가 아니면 TARGET_002(403)이고 아무 것도 저장하지 않는다")
    void updateSettings_noActiveRelation_throwsTarget002() {
        // given
        givenTarget();
        when(guardianTargetRepository.findActiveByGuardianIdAndTargetIdForUpdate(
                        GUARDIAN_ID, TARGET_ID))
                .thenReturn(Optional.empty());

        // when & then
        assertBusinessError(
                () -> service().updateSettings(GUARDIAN_ID, targetPublicId, "REALTIME", 10, 20),
                ErrorCode.TARGET_002);
    }

    @Test
    @DisplayName("수정 — 락 경합 실패는 서버 오류가 아니라 재시도 가능한 COMMON_008(409)로 변환한다")
    void updateSettings_lockConflict_throwsCommon008() {
        // given
        givenTarget();
        when(guardianTargetRepository.findActiveByGuardianIdAndTargetIdForUpdate(
                        GUARDIAN_ID, TARGET_ID))
                .thenThrow(new PessimisticLockingFailureException("lock(테스트)"));

        // when & then
        assertBusinessError(
                () -> service().updateSettings(GUARDIAN_ID, targetPublicId, "REALTIME", 10, 20),
                ErrorCode.COMMON_008);
    }

    // ---------------------------------------------------------------------
    // pause — 누적 + 상한
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("정지 — 정지 중이 아니면 현재 모드를 previous에 저장하고 지금부터 N분 뒤로 정지한다")
    void pause_notPaused_pausesFromNow() {
        // given
        givenOwnRelationLockedForWrite();
        Instant before = Instant.now();

        // when
        NotificationSettingsResponse response = service().pause(GUARDIAN_ID, targetPublicId, 30);
        Instant after = Instant.now();

        // then
        assertThat(response.isPaused()).isTrue();
        assertThat(response.getNotificationMode()).isEqualTo("HYBRID");
        assertThat(relation.getPreviousNotificationMode()).isEqualTo("HYBRID");
        assertThat(response.getPausedUntil())
                .isBetween(before.plus(Duration.ofMinutes(30)), after.plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("정지 — 정지 중 재요청은 기존 남은 시간 위에 더한다(20분 남은 상태 + 30분 → 약 50분 뒤), previous는 유지한다")
    void pause_alreadyPaused_accumulatesOnRemaining() {
        // given
        makePaused("REALTIME", Instant.now().plus(Duration.ofMinutes(20)));
        givenOwnRelationLockedForWrite();
        Instant before = Instant.now();

        // when
        NotificationSettingsResponse response = service().pause(GUARDIAN_ID, targetPublicId, 30);
        Instant after = Instant.now();

        // then
        assertThat(response.getPausedUntil())
                .isBetween(
                        before.plus(Duration.ofMinutes(20)).plus(Duration.ofMinutes(30)).minusSeconds(1),
                        after.plus(Duration.ofMinutes(20)).plus(Duration.ofMinutes(30)).plusSeconds(1));
        assertThat(relation.getPreviousNotificationMode()).isEqualTo("REALTIME");
        assertThat(response.getNotificationMode()).isEqualTo("REALTIME");
    }

    @Test
    @DisplayName("정지 — 24시간 근접이면 지금부터 24시간으로 잘린다(23시간 30분 남은 상태 + 60분 → 약 24시간)")
    void pause_nearLimit_isCappedAtTwentyFourHoursFromNow() {
        // given
        makePaused("HYBRID", Instant.now().plus(Duration.ofHours(23).plusMinutes(30)));
        givenOwnRelationLockedForWrite();
        Instant before = Instant.now();

        // when
        NotificationSettingsResponse response = service().pause(GUARDIAN_ID, targetPublicId, 60);
        Instant after = Instant.now();

        // then
        assertThat(response.getPausedUntil())
                .isBetween(before.plus(Duration.ofHours(24)), after.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("정지 — 이미 최대(지금부터 24시간)면 더 늘어나지 않고 예외 없이 응답한다(더 늘릴 수 없음)")
    void pause_alreadyAtLimit_doesNotIncreaseAndDoesNotThrow() {
        // given
        Instant almostLimit = Instant.now().plus(Duration.ofHours(24)).plusSeconds(5);
        makePaused("HYBRID", almostLimit);
        givenOwnRelationLockedForWrite();
        Instant limitAfterCall;

        // when
        NotificationSettingsResponse response = service().pause(GUARDIAN_ID, targetPublicId, 30);
        limitAfterCall = Instant.now().plus(Duration.ofHours(24));

        // then — 요청한 30분이 더해지지 않고 상한(호출 시점 now + 24h) 이하로 잘린다
        assertThat(response.getPausedUntil()).isBefore(almostLimit.plus(Duration.ofMinutes(1)));
        assertThat(response.getPausedUntil()).isBeforeOrEqualTo(limitAfterCall);
    }

    @Test
    @DisplayName("정지 — durationMinutes가 1~1440 밖이면 COMMON_002이고 상태를 바꾸지 않는다")
    void pause_outOfRangeDuration_throwsCommon002WithoutChange() {
        // given
        givenOwnRelationLockedForWrite();

        // when & then
        assertBusinessError(() -> service().pause(GUARDIAN_ID, targetPublicId, 0), ErrorCode.COMMON_002);
        assertBusinessError(() -> service().pause(GUARDIAN_ID, targetPublicId, 1441), ErrorCode.COMMON_002);
        assertThat(relation.isPaused()).isFalse();
    }

    @Test
    @DisplayName("정지 — CareTarget이 없으면 TARGET_001이다")
    void pause_targetNotFound_throwsTarget001() {
        // given
        when(userRepository.findByPublicId(targetPublicId)).thenReturn(Optional.empty());

        // when & then
        assertBusinessError(() -> service().pause(GUARDIAN_ID, targetPublicId, 30), ErrorCode.TARGET_001);
    }

    @Test
    @DisplayName("정지 — 호출자와 ACTIVE 관계가 아니면 TARGET_002(403)이다")
    void pause_noActiveRelation_throwsTarget002() {
        // given
        givenTarget();
        when(guardianTargetRepository.findActiveByGuardianIdAndTargetIdForUpdate(
                        GUARDIAN_ID, TARGET_ID))
                .thenReturn(Optional.empty());

        // when & then
        assertBusinessError(() -> service().pause(GUARDIAN_ID, targetPublicId, 30), ErrorCode.TARGET_002);
    }

    // ---------------------------------------------------------------------
    // resume — 멱등
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("재개 — 정지 중이면 기본 모드로 즉시 복귀하고 pausedUntil을 비운다")
    void resume_paused_returnsToBaseModeImmediately() {
        // given
        makePaused("REALTIME", Instant.now().plus(Duration.ofMinutes(30)));
        givenOwnRelationLockedForWrite();

        // when
        NotificationSettingsResponse response = service().resume(GUARDIAN_ID, targetPublicId);

        // then
        assertThat(response.isPaused()).isFalse();
        assertThat(response.getPausedUntil()).isNull();
        assertThat(response.getNotificationMode()).isEqualTo("REALTIME");
        assertThat(relation.getNotificationMode()).isEqualTo("REALTIME");
        assertThat(relation.getPreviousNotificationMode()).isNull();
    }

    @Test
    @DisplayName("재개 — 정지 중이 아니어도 에러 없이 현재 설정을 그대로 반환한다(멱등, 정상 행의 모드를 바꾸지 않음)")
    void resume_notPaused_isIdempotentNoOp() {
        // given
        relation.updateNotificationMode("REALTIME", 10, 20);
        givenOwnRelationLockedForWrite();

        // when
        NotificationSettingsResponse response = service().resume(GUARDIAN_ID, targetPublicId);

        // then
        assertThat(response.isPaused()).isFalse();
        assertThat(response.getNotificationMode()).isEqualTo("REALTIME");
        assertThat(response.getEscalateMinutesArrival()).isEqualTo(10);
    }

    @Test
    @DisplayName("재개 — 호출자와 ACTIVE 관계가 아니면 TARGET_002(403)이다")
    void resume_noActiveRelation_throwsTarget002() {
        // given
        givenTarget();
        when(guardianTargetRepository.findActiveByGuardianIdAndTargetIdForUpdate(
                        GUARDIAN_ID, TARGET_ID))
                .thenReturn(Optional.empty());

        // when & then
        assertBusinessError(() -> service().resume(GUARDIAN_ID, targetPublicId), ErrorCode.TARGET_002);
    }

    // ---------------------------------------------------------------------
    // resumeIfDue — 스케줄러 자동 복귀(잠금 후 재확인)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("자동 복귀 — 잠금 후 확인해도 paused_until이 지났으면 복귀시키고 true를 반환한다")
    void resumeIfDue_stillDueAfterLock_resumesAndReturnsTrue() {
        // given
        makePaused("REALTIME", Instant.now().minusSeconds(60));
        when(guardianTargetRepository.findByIdForUpdate(RELATION_ID)).thenReturn(Optional.of(relation));

        // when
        boolean resumed = service().resumeIfDue(RELATION_ID, Instant.now());

        // then
        assertThat(resumed).isTrue();
        assertThat(relation.getNotificationMode()).isEqualTo("REALTIME");
        assertThat(relation.getPausedUntil()).isNull();
    }

    @Test
    @DisplayName("자동 복귀 — 스케줄러가 읽은 뒤 사용자가 정지를 연장해 잠금 후 확인 시 아직 안 지났으면 복귀시키지 않는다(연장 유지)")
    void resumeIfDue_extendedByUserBeforeLock_doesNotResume() {
        // given — 잠금 후 최신 상태: 사용자가 방금 연장해 paused_until이 미래
        Instant extended = Instant.now().plus(Duration.ofMinutes(30));
        makePaused("REALTIME", extended);
        when(guardianTargetRepository.findByIdForUpdate(RELATION_ID)).thenReturn(Optional.of(relation));

        // when
        boolean resumed = service().resumeIfDue(RELATION_ID, Instant.now());

        // then
        assertThat(resumed).isFalse();
        assertThat(relation.isPaused()).isTrue();
        assertThat(relation.getPausedUntil()).isEqualTo(extended);
    }

    @Test
    @DisplayName("자동 복귀 — 이미 사용자가 재개해 정지 중이 아니면 false이고 모드를 건드리지 않는다")
    void resumeIfDue_alreadyResumedByUser_returnsFalseWithoutChange() {
        // given
        relation.updateNotificationMode("REPORT_ONLY", 30, 60);
        when(guardianTargetRepository.findByIdForUpdate(RELATION_ID)).thenReturn(Optional.of(relation));

        // when
        boolean resumed = service().resumeIfDue(RELATION_ID, Instant.now());

        // then
        assertThat(resumed).isFalse();
        assertThat(relation.getNotificationMode()).isEqualTo("REPORT_ONLY");
    }

    @Test
    @DisplayName("자동 복귀 — 행이 없으면 false이다")
    void resumeIfDue_rowMissing_returnsFalse() {
        // given
        when(guardianTargetRepository.findByIdForUpdate(RELATION_ID)).thenReturn(Optional.empty());

        // when & then
        assertThat(service().resumeIfDue(RELATION_ID, Instant.now())).isFalse();
    }

    @Test
    @DisplayName("자동 복귀 — 락 경합 실패는 예외를 던지지 않고 false로 건너뛴다(다음 tick에서 재평가, 스케줄러 전체가 멈추지 않음)")
    void resumeIfDue_lockConflict_isSkippedWithoutThrowing() {
        // given
        when(guardianTargetRepository.findByIdForUpdate(RELATION_ID))
                .thenThrow(new PessimisticLockingFailureException("lock(테스트)"));

        // when & then
        assertThatCode(() -> assertThat(service().resumeIfDue(RELATION_ID, Instant.now())).isFalse())
                .doesNotThrowAnyException();
    }
}
