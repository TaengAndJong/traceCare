package com.tracecare.backend.domain.guardian.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

/**
 * §4.4 2단계 — GuardianTarget 알림 설정 불변식(오버레이, 정지 누적+상한, 복귀 폴백)의 회귀 테스트(API_Specification.md §3.10,
 * DATABASE_DESIGN_GUIDE.md §15.8). 버그 A/B와 오버레이 규칙 테스트는 수정 전 코드에서 실패하는 것을 먼저 확인한 뒤 수정했다.
 * 시각은 {@link #NOW}로 고정한다(엔티티가 시계를 직접 읽지 않으므로 가능).
 */
class GuardianTargetNotificationTest {

    /** 기준 시각 = 문서 예시의 "15:00". */
    private static final Instant NOW = Instant.parse("2026-09-21T06:00:00Z");

    private GuardianTarget guardian() {
        return GuardianTarget.createActive(1L, 2L, GuardianTarget.ROLE_SUB);
    }

    /** 이미 정지 중인 상태를 정확한 값으로 만든다(계산식 검증에서 "기존 pausedUntil"을 직접 지정하기 위함). */
    private GuardianTarget pausedGuardian(String previousMode, Instant pausedUntil) {
        GuardianTarget guardian = guardian();
        ReflectionTestUtils.setField(
                guardian, "notificationMode", GuardianTarget.NOTIFICATION_MODE_PAUSED);
        ReflectionTestUtils.setField(guardian, "previousNotificationMode", previousMode);
        ReflectionTestUtils.setField(guardian, "pausedUntil", pausedUntil);
        return guardian;
    }

    private static Instant after(Duration duration) {
        return NOW.plus(duration);
    }

    private void assertCommon002(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_002));
    }

    // ---------------------------------------------------------------------
    // 버그 A — 이미 PAUSED일 때 pause() 재호출
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("pause 재호출 — 이미 PAUSED면 previous_notification_mode를 덮어쓰지 않는다(PAUSED를 저장하면 DB CHECK 위반)")
    void pause_alreadyPaused_keepsPreviousModeUntouched() {
        // given
        GuardianTarget guardian = guardian();
        guardian.updateNotificationMode(GuardianTarget.NOTIFICATION_MODE_REALTIME, 10, 20);
        guardian.pause(NOW, 10);

        // when
        guardian.pause(NOW.plusSeconds(30), 20);

        // then
        assertThat(guardian.getPreviousNotificationMode())
                .isEqualTo(GuardianTarget.NOTIFICATION_MODE_REALTIME);
        assertThat(guardian.getNotificationMode()).isEqualTo(GuardianTarget.NOTIFICATION_MODE_PAUSED);
    }

    @Test
    @DisplayName("pause 최초 호출 — 정지가 아니면 현재 모드를 previous에 저장하고 PAUSED로 전환한다")
    void pause_notPaused_savesCurrentModeAsPrevious() {
        // given
        GuardianTarget guardian = guardian();
        guardian.updateNotificationMode(GuardianTarget.NOTIFICATION_MODE_REPORT_ONLY, 30, 60);

        // when
        guardian.pause(NOW, 30);

        // then
        assertThat(guardian.getNotificationMode()).isEqualTo(GuardianTarget.NOTIFICATION_MODE_PAUSED);
        assertThat(guardian.getPreviousNotificationMode())
                .isEqualTo(GuardianTarget.NOTIFICATION_MODE_REPORT_ONLY);
        assertThat(guardian.isPaused()).isTrue();
    }

    // ---------------------------------------------------------------------
    // 정지 시간 계산식: pausedUntil = min( max(기존, now) + d, now + 1440분 )
    // (DATABASE_DESIGN_GUIDE.md §15.8 시나리오 표 그대로)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("pause 계산식 — 정지 중 연장은 기존 남은 시간 위에 더한다(15:20 정지 중 15:00에 30분 → 15:50, 상한 미적용)")
    void pause_extendWhilePaused_addsOnTopOfRemaining() {
        // given
        GuardianTarget guardian =
                pausedGuardian(GuardianTarget.NOTIFICATION_MODE_HYBRID, after(Duration.ofMinutes(20)));

        // when
        guardian.pause(NOW, 30);

        // then
        assertThat(guardian.getPausedUntil()).isEqualTo(after(Duration.ofMinutes(50)));
    }

    @Test
    @DisplayName("pause 계산식 — 정지 중이 아니면 now 기준으로 새로 정지한다(15:00에 60분 → 16:00)")
    void pause_notPaused_startsFromNow() {
        // given
        GuardianTarget guardian = guardian();

        // when
        guardian.pause(NOW, 60);

        // then
        assertThat(guardian.getPausedUntil()).isEqualTo(after(Duration.ofMinutes(60)));
    }

    @Test
    @DisplayName("pause 계산식 — 24시간 근접이면 상한에서 잘린다(다음 날 14:30 + 60분 → 다음 날 15:00, 30분만 늘어남)")
    void pause_nearLimit_isCappedAtNowPlus24Hours() {
        // given
        GuardianTarget guardian =
                pausedGuardian(
                        GuardianTarget.NOTIFICATION_MODE_HYBRID,
                        after(Duration.ofHours(23).plusMinutes(30)));

        // when
        guardian.pause(NOW, 60);

        // then
        assertThat(guardian.getPausedUntil()).isEqualTo(after(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("pause 계산식 — 이미 최대(now+24h)면 더 늘어나지 않고 예외도 없다")
    void pause_alreadyAtLimit_staysUnchanged() {
        // given
        GuardianTarget guardian =
                pausedGuardian(GuardianTarget.NOTIFICATION_MODE_HYBRID, after(Duration.ofHours(24)));

        // when
        guardian.pause(NOW, 30);

        // then
        assertThat(guardian.getPausedUntil()).isEqualTo(after(Duration.ofHours(24)));
        assertThat(guardian.getPreviousNotificationMode())
                .isEqualTo(GuardianTarget.NOTIFICATION_MODE_HYBRID);
    }

    @Test
    @DisplayName("pause 계산식 — 최대에 도달한 10분 뒤 다시 누르면 상한이 함께 이동해 10분 늘어난다(남은 시간 상한이지 연속 총 길이 상한이 아님)")
    void pause_retryTenMinutesAfterReachingLimit_limitMovesWithNow() {
        // given — 15:00에 최대(1440분)로 정지
        GuardianTarget guardian = guardian();
        guardian.pause(NOW, GuardianTarget.MAX_PAUSE_MINUTES);
        assertThat(guardian.getPausedUntil()).isEqualTo(after(Duration.ofHours(24)));

        // when — 10분 뒤 30분 더
        Instant tenMinutesLater = NOW.plus(Duration.ofMinutes(10));
        guardian.pause(tenMinutesLater, 30);

        // then — min(다음 날 15:30, 다음 날 15:10) = 다음 날 15:10
        assertThat(guardian.getPausedUntil()).isEqualTo(tenMinutesLater.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("pause 계산식 — 기존 pausedUntil이 이미 지났는데 아직 복귀 전이면 now 기준으로 계산한다")
    void pause_existingPausedUntilAlreadyPassed_usesNowAsBase() {
        // given — 5분 전에 끝났어야 할 정지(스케줄러 복귀 전)
        GuardianTarget guardian =
                pausedGuardian(
                        GuardianTarget.NOTIFICATION_MODE_HYBRID, NOW.minus(Duration.ofMinutes(5)));

        // when
        guardian.pause(NOW, 30);

        // then
        assertThat(guardian.getPausedUntil()).isEqualTo(after(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("pause 계산식 — 기존 값이 상한보다 길면(비정상 데이터) 결과는 항상 now+24h로 잘린다")
    void pause_existingBeyondLimit_resultNeverExceedsNowPlus24Hours() {
        // given
        GuardianTarget guardian =
                pausedGuardian(GuardianTarget.NOTIFICATION_MODE_HYBRID, after(Duration.ofHours(30)));

        // when
        guardian.pause(NOW, 10);

        // then
        assertThat(guardian.getPausedUntil()).isEqualTo(after(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("pause 경계값 — 1분과 1440분은 허용된다")
    void pause_boundaryDurations_1And1440_areAllowed() {
        // given
        GuardianTarget oneMinute = guardian();
        GuardianTarget fullDay = guardian();

        // when
        oneMinute.pause(NOW, 1);
        fullDay.pause(NOW, 1440);

        // then
        assertThat(oneMinute.getPausedUntil()).isEqualTo(after(Duration.ofMinutes(1)));
        assertThat(fullDay.getPausedUntil()).isEqualTo(after(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("pause 경계값 — 0분·음수·1441분은 COMMON_002로 거부하고 상태를 바꾸지 않는다")
    void pause_outOfRangeDuration_isRejectedWithoutStateChange() {
        // given
        GuardianTarget guardian = guardian();

        // when & then
        assertCommon002(() -> guardian.pause(NOW, 0));
        assertCommon002(() -> guardian.pause(NOW, -1));
        assertCommon002(() -> guardian.pause(NOW, 1441));
        assertThat(guardian.isPaused()).isFalse();
        assertThat(guardian.getPausedUntil()).isNull();
        assertThat(guardian.getPreviousNotificationMode()).isNull();
    }

    // ---------------------------------------------------------------------
    // 버그 B — previous가 null인 상태에서 resumeFromPause()
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("복귀 — previous_notification_mode가 null이면 HYBRID로 폴백하고 previous/paused_until은 비운다(null이면 NOT NULL 위반)")
    void resumeFromPause_previousModeNull_fallsBackToHybrid() {
        // given — PAUSED인데 복귀할 모드가 기록되지 않은 비정상 데이터
        GuardianTarget guardian = pausedGuardian(null, NOW.minusSeconds(60));

        // when
        guardian.resumeFromPause();

        // then
        assertThat(guardian.getNotificationMode()).isEqualTo(GuardianTarget.NOTIFICATION_MODE_HYBRID);
        assertThat(guardian.getPreviousNotificationMode()).isNull();
        assertThat(guardian.getPausedUntil()).isNull();
    }

    @Test
    @DisplayName("복귀 — previous가 있으면 그 모드로 돌아가고 previous/paused_until은 비운다")
    void resumeFromPause_previousModePresent_restoresIt() {
        // given
        GuardianTarget guardian =
                pausedGuardian(GuardianTarget.NOTIFICATION_MODE_REALTIME, after(Duration.ofMinutes(5)));

        // when
        guardian.resumeFromPause();

        // then
        assertThat(guardian.getNotificationMode())
                .isEqualTo(GuardianTarget.NOTIFICATION_MODE_REALTIME);
        assertThat(guardian.getPreviousNotificationMode()).isNull();
        assertThat(guardian.getPausedUntil()).isNull();
        assertThat(guardian.isPaused()).isFalse();
    }

    @Test
    @DisplayName("복귀 — 정지 중이 아니면 아무것도 바꾸지 않는다(폴백이 정상 행의 모드를 HYBRID로 덮어쓰지 않음, resume API 멱등)")
    void resumeFromPause_notPaused_isNoOp() {
        // given
        GuardianTarget guardian = guardian();
        guardian.updateNotificationMode(GuardianTarget.NOTIFICATION_MODE_REALTIME, 10, 20);

        // when
        guardian.resumeFromPause();

        // then
        assertThat(guardian.getNotificationMode())
                .isEqualTo(GuardianTarget.NOTIFICATION_MODE_REALTIME);
        assertThat(guardian.getPreviousNotificationMode()).isNull();
        assertThat(guardian.getPausedUntil()).isNull();
    }

    @Test
    @DisplayName("정지→복귀→재정지 — 복귀 후 다시 정지하면 그 시점의 모드가 새로 previous에 저장된다")
    void pause_afterResume_savesNewPreviousMode() {
        // given
        GuardianTarget guardian = guardian();
        guardian.pause(NOW, 30);
        guardian.resumeFromPause();
        guardian.updateNotificationMode(GuardianTarget.NOTIFICATION_MODE_REPORT_ONLY, 30, 60);

        // when
        guardian.pause(NOW.plusSeconds(60), 30);

        // then
        assertThat(guardian.getPreviousNotificationMode())
                .isEqualTo(GuardianTarget.NOTIFICATION_MODE_REPORT_ONLY);
    }

    // ---------------------------------------------------------------------
    // updateNotificationMode — 오버레이 규칙
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("설정 변경 — PAUSED 값은 이 메서드로 직접 설정할 수 없다(COMMON_002, 상태 불변)")
    void updateNotificationMode_pausedValue_isRejected() {
        // given
        GuardianTarget guardian = guardian();

        // when & then
        assertCommon002(
                () ->
                        guardian.updateNotificationMode(
                                GuardianTarget.NOTIFICATION_MODE_PAUSED, 15, 45));
        assertThat(guardian.getNotificationMode()).isEqualTo(GuardianTarget.NOTIFICATION_MODE_HYBRID);
        assertThat(guardian.getEscalateMinutesArrival()).isEqualTo(30);
        assertThat(guardian.getEscalateMinutesStay()).isEqualTo(60);
        assertThat(guardian.getPreviousNotificationMode()).isNull();
    }

    @Test
    @DisplayName("설정 변경 — 알 수 없는 값이나 null도 거부한다(조용히 무시하지 않음)")
    void updateNotificationMode_unknownOrNullValue_isRejected() {
        // given
        GuardianTarget guardian = guardian();

        // when & then
        assertCommon002(() -> guardian.updateNotificationMode("UNKNOWN", 30, 60));
        assertCommon002(() -> guardian.updateNotificationMode(null, 30, 60));
        assertThat(guardian.getNotificationMode()).isEqualTo(GuardianTarget.NOTIFICATION_MODE_HYBRID);
    }

    @Test
    @DisplayName("설정 변경 — 정지 중이 아니면 notification_mode를 직접 바꾸고 승격 분을 갱신한다(명시적 null = 승격 안 함)")
    void updateNotificationMode_notPaused_updatesModeAndMinutes() {
        // given
        GuardianTarget guardian = guardian();

        // when
        guardian.updateNotificationMode(GuardianTarget.NOTIFICATION_MODE_REALTIME, 10, null);

        // then
        assertThat(guardian.getNotificationMode())
                .isEqualTo(GuardianTarget.NOTIFICATION_MODE_REALTIME);
        assertThat(guardian.getEscalateMinutesArrival()).isEqualTo(10);
        assertThat(guardian.getEscalateMinutesStay()).isNull();
        assertThat(guardian.getPreviousNotificationMode()).isNull();
    }

    @Test
    @DisplayName("설정 변경 — 정지 중이면 정지는 유지하고 previous_notification_mode만 새 값으로 바꾸며 승격 분은 정상 갱신한다")
    void updateNotificationMode_whilePaused_keepsPausedAndUpdatesPreviousOnly() {
        // given
        GuardianTarget guardian = guardian();
        guardian.pause(NOW, 10); // 기본 HYBRID에서 정지
        Instant pausedUntilBefore = guardian.getPausedUntil();

        // when
        guardian.updateNotificationMode(GuardianTarget.NOTIFICATION_MODE_REALTIME, 15, 45);

        // then
        assertThat(guardian.getNotificationMode()).isEqualTo(GuardianTarget.NOTIFICATION_MODE_PAUSED);
        assertThat(guardian.getPreviousNotificationMode())
                .isEqualTo(GuardianTarget.NOTIFICATION_MODE_REALTIME);
        assertThat(guardian.getPausedUntil()).isEqualTo(pausedUntilBefore);
        assertThat(guardian.getEscalateMinutesArrival()).isEqualTo(15);
        assertThat(guardian.getEscalateMinutesStay()).isEqualTo(45);
    }

    @Test
    @DisplayName("설정 변경 후 복귀 — 정지 중 바꾼 기본 모드로 복귀한다")
    void updateNotificationMode_whilePausedThenResume_returnsToUpdatedMode() {
        // given
        GuardianTarget guardian = guardian();
        guardian.pause(NOW, 10);
        guardian.updateNotificationMode(GuardianTarget.NOTIFICATION_MODE_REPORT_ONLY, 30, 60);

        // when
        guardian.resumeFromPause();

        // then
        assertThat(guardian.getNotificationMode())
                .isEqualTo(GuardianTarget.NOTIFICATION_MODE_REPORT_ONLY);
    }

    // ---------------------------------------------------------------------
    // baseNotificationMode — API가 노출하는 "기본 모드"(PAUSED는 노출하지 않음)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("기본 모드 — 정지 중이 아니면 notification_mode, 정지 중이면 previous(없으면 HYBRID)이며 PAUSED는 절대 나오지 않는다")
    void baseNotificationMode_overlay_neverExposesPaused() {
        // given
        GuardianTarget notPaused = guardian();
        notPaused.updateNotificationMode(GuardianTarget.NOTIFICATION_MODE_REPORT_ONLY, 30, 60);
        GuardianTarget pausedWithPrevious =
                pausedGuardian(GuardianTarget.NOTIFICATION_MODE_REALTIME, after(Duration.ofMinutes(5)));
        GuardianTarget pausedWithoutPrevious = pausedGuardian(null, after(Duration.ofMinutes(5)));

        // then
        assertThat(notPaused.baseNotificationMode())
                .isEqualTo(GuardianTarget.NOTIFICATION_MODE_REPORT_ONLY);
        assertThat(pausedWithPrevious.baseNotificationMode())
                .isEqualTo(GuardianTarget.NOTIFICATION_MODE_REALTIME);
        assertThat(pausedWithoutPrevious.baseNotificationMode())
                .isEqualTo(GuardianTarget.NOTIFICATION_MODE_HYBRID);
    }

    // ---------------------------------------------------------------------
    // isPauseDue
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("복귀 시점 판정 — 정지 중이고 paused_until이 지났을 때만 true이다(NULL이면 false)")
    void isPauseDue_onlyWhenPausedAndDeadlinePassed() {
        // given
        GuardianTarget due = pausedGuardian(GuardianTarget.NOTIFICATION_MODE_HYBRID, NOW);
        GuardianTarget notYet =
                pausedGuardian(GuardianTarget.NOTIFICATION_MODE_HYBRID, after(Duration.ofSeconds(1)));
        GuardianTarget nullDeadline = pausedGuardian(GuardianTarget.NOTIFICATION_MODE_HYBRID, null);

        // then
        assertThat(due.isPauseDue(NOW)).isTrue();
        assertThat(notYet.isPauseDue(NOW)).isFalse();
        assertThat(nullDeadline.isPauseDue(NOW)).isFalse();
        assertThat(guardian().isPauseDue(NOW)).isFalse();
    }
}
