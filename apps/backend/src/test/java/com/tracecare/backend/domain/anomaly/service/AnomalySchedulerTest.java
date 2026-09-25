package com.tracecare.backend.domain.anomaly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.tracecare.backend.common.cache.CacheKeyGenerator;
import com.tracecare.backend.domain.anomaly.entity.AnomalyEvent;
import com.tracecare.backend.domain.anomaly.repository.AnomalyEventRepository;
import com.tracecare.backend.domain.anomaly.repository.PlaceArrivalScheduleRepository;
import com.tracecare.backend.domain.anomaly.repository.PlaceArrivalScheduleRepository.ScheduledPlace;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.guardian.service.GuardianNotificationSettingsService;
import com.tracecare.backend.domain.notification.repository.NotificationHistoryRepository;
import com.tracecare.backend.domain.notification.service.NotificationDispatchService;
import com.tracecare.backend.domain.notification.service.NotificationDispatchService.AnomalyEscalationTarget;
import com.tracecare.backend.domain.visit.repository.VisitHistoryRepository;

/**
 * DATABASE_DESIGN_GUIDE.md §15.6(§4.2 확정 A~D)의 3가지 책임(감지/승격/PAUSED 복귀)과 분산 락을 검증한다.
 * {@code expectedArrivalTime}/임계값 판단은 스케줄러에 주입한 {@link Clock}을 기준으로 한다. 이 테스트는 실행 시각(벽시계)을
 * 읽지 않고 {@code Clock.fixed}로 시각을 고정한다 — 예상 도착 시각은 {@code LocalTime.now()}에서 만들지 않고 고정 시각 기준의
 * 절대값({@link TimeCase})으로 적는다. 감지 판단이 시각에 민감한 테스트는 자정 경계(00:15/23:30/23:59)와 정오(12:00) 네 시각을
 * 모두 돌린다(과거 KST 23시대/00시대에 {@code LocalTime.now().plusHours(1)}이 날짜를 넘어 실패하던 문제의 회귀 방지).
 */
@ExtendWith(MockitoExtension.class)
class AnomalySchedulerTest {

    private static final int DETECT_MINUTES = 10;
    private static final int PAGE_SIZE = 2;
    private static final long LOCK_TTL_MINUTES = 4;
    private static final Long CARE_TARGET_ID = 1L;
    private static final Long PLACE_ID = 10L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDate TEST_DATE = LocalDate.of(2026, 9, 23);

    @Mock private PlaceArrivalScheduleRepository placeArrivalScheduleRepository;
    @Mock private AnomalyEventRepository anomalyEventRepository;
    @Mock private VisitHistoryRepository visitHistoryRepository;
    @Mock private GuardianTargetRepository guardianTargetRepository;
    @Mock private GuardianNotificationSettingsService guardianNotificationSettingsService;
    @Mock private NotificationHistoryRepository notificationHistoryRepository;
    @Mock private NotificationDispatchService notificationDispatchService;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;

    private final CacheKeyGenerator cacheKeyGenerator = new CacheKeyGenerator();

    /** 기본 고정 시각은 정오(자정 경계와 무관한 테스트용). 시각에 민감한 테스트는 {@link #clockAt}으로 교체한다. */
    private Clock clock = clockAt(LocalTime.NOON);

    private static Clock clockAt(LocalTime time) {
        return Clock.fixed(TEST_DATE.atTime(time).atZone(ZONE).toInstant(), ZONE);
    }

    /**
     * 고정 시각과, 그 시각 기준 "같은 날 안에서" 마감이 이미 지난 예상 도착 시각({@code past}) / 아직 지나지 않은 예상 도착
     * 시각({@code future}). 마감 = 예상 시각 + {@value #DETECT_MINUTES}분이며, 날짜를 넘기지 않도록 시각마다 값을 직접 정한다.
     */
    private record TimeCase(LocalTime now, LocalTime past, LocalTime future) {
        @Override
        public String toString() {
            return "now=" + now;
        }
    }

    static Stream<Arguments> clockTimes() {
        return Stream.of(
                Arguments.of(new TimeCase(LocalTime.of(12, 0), LocalTime.of(11, 30), LocalTime.of(13, 0))),
                Arguments.of(new TimeCase(LocalTime.of(0, 15), LocalTime.of(0, 0), LocalTime.of(1, 15))),
                Arguments.of(new TimeCase(LocalTime.of(23, 30), LocalTime.of(23, 0), LocalTime.of(23, 45))),
                Arguments.of(new TimeCase(LocalTime.of(23, 59), LocalTime.of(23, 30), LocalTime.of(23, 55))));
    }

    private AnomalyScheduler scheduler() {
        return new AnomalyScheduler(
                placeArrivalScheduleRepository,
                anomalyEventRepository,
                visitHistoryRepository,
                guardianTargetRepository,
                guardianNotificationSettingsService,
                notificationHistoryRepository,
                notificationDispatchService,
                redisTemplate,
                cacheKeyGenerator,
                clock,
                DETECT_MINUTES,
                PAGE_SIZE,
                LOCK_TTL_MINUTES);
    }

    /**
     * 락이 정상적으로 걸리도록(setIfAbsent=true) 기본값을 잡아둔다. {@code unlock()}의 소유자 비교(get() 값이
     * 실제 락 값과 같을 때만 삭제)는 여기서 정확히 재현하지 않고 항상 {@code null}(불일치)을 반환하게 해 delete가
     * 안전하게 스킵되도록만 한다 — 이 테스트 스위트의 관심사는 unlock 자체의 소유권 비교 정확성이 아니라 tick의
     * 3가지 책임(감지/승격/PAUSED 복귀)과 락 획득 성공/실패 분기이기 때문이다. {@code thenAnswer}/캡처 기반 동적
     * 응답은 의도적으로 쓰지 않는다 — 같은 메서드를 테스트별로 재스텁할 때 Mockito가 스텁 등록 도중 기존 응답을
     * 미리 한 번 평가해보는 특성상, 동적 응답 안에서 부작용(예외)이 있으면 스텁 등록 자체가 깨지기 때문이다.
     * 세 역할(감지/승격/PAUSED 복귀) 모두 기본은 "대상 없음"으로 중립화해, 각 테스트가 필요한 역할만 재정의한다.
     */
    @BeforeEach
    void setUpNeutralDefaults() {
        String lockKey = cacheKeyGenerator.anomalySchedulerLock();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(eq(lockKey), any(), any())).thenReturn(true);
        lenient().when(valueOperations.get(lockKey)).thenReturn(null);

        lenient()
                .when(placeArrivalScheduleRepository.findScheduledByDayOfWeek(anyInt(), any()))
                .thenReturn(Page.empty());
        lenient().when(anomalyEventRepository.findByResolvedAtIsNull()).thenReturn(List.of());
        lenient()
                .when(guardianTargetRepository.findByNotificationMode(
                        GuardianTarget.NOTIFICATION_MODE_PAUSED))
                .thenReturn(List.of());
    }

    private ScheduledPlace scheduledPlace(LocalTime expectedArrivalTime) {
        ScheduledPlace scheduled = org.mockito.Mockito.mock(ScheduledPlace.class);
        lenient().when(scheduled.getScheduleId()).thenReturn(100L);
        lenient().when(scheduled.getPlaceId()).thenReturn(PLACE_ID);
        lenient().when(scheduled.getTargetId()).thenReturn(CARE_TARGET_ID);
        lenient().when(scheduled.getExpectedArrivalTime()).thenReturn(expectedArrivalTime);
        return scheduled;
    }

    // ---------------------------------------------------------------------
    // 분산 락
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("다른 인스턴스가 락을 쥐고 있으면(setIfAbsent=false) 이번 tick 전체를 건너뛴다")
    void tick_lockHeldByAnotherInstance_skipsEntireTick() {
        // given
        when(valueOperations.setIfAbsent(eq(cacheKeyGenerator.anomalySchedulerLock()), any(), any()))
                .thenReturn(false);

        // when
        scheduler().tick();

        // then — 세 역할 모두 아예 실행되지 않는다
        verifyNoInteractions(
                placeArrivalScheduleRepository,
                anomalyEventRepository,
                guardianTargetRepository,
                notificationDispatchService);
    }

    @Test
    @DisplayName("Redis 장애로 락 획득 자체가 실패하면(예외) 락 없이도 tick을 진행한다(fail-open)")
    void tick_lockAcquireThrows_stillProceedsWithAllRoles() {
        // given
        when(valueOperations.setIfAbsent(
                        eq(cacheKeyGenerator.anomalySchedulerLock()), any(), any()))
                .thenThrow(new RedisConnectionFailureException("연결 실패(테스트)"));

        // when
        scheduler().tick();

        // then — 락 없이도 역할1(감지) 조회가 실제로 수행됐다
        verify(placeArrivalScheduleRepository).findScheduledByDayOfWeek(anyInt(), any());
    }

    // ---------------------------------------------------------------------
    // 역할1: ARRIVAL_DELAY 감지
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("clockTimes")
    @DisplayName("예상 도착 시각+감지기준(10분)이 아직 지나지 않았으면 AnomalyEvent를 생성하지 않는다")
    void detectArrivalDelay_deadlineNotYetReached_doesNotCreateEvent(TimeCase t) {
        // given — 마감이 아직 지나지 않은 예상 도착 시각(같은 날 안)
        clock = clockAt(t.now());
        ScheduledPlace scheduled = scheduledPlace(t.future());
        when(placeArrivalScheduleRepository.findScheduledByDayOfWeek(anyInt(), any()))
                .thenReturn(new PageImpl<>(List.of(scheduled)));
        when(visitHistoryRepository.existsByUserIdAndPlaceIdAndArrivalTimeGreaterThanEqual(
                        eq(CARE_TARGET_ID), eq(PLACE_ID), any()))
                .thenReturn(false);

        // when
        scheduler().tick();

        // then
        verify(anomalyEventRepository, never()).save(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clockTimes")
    @DisplayName("예상 도착 시각+감지기준을 넘겼고 도착 기록이 없으면 AnomalyEvent(ARRIVAL_DELAY)를 생성한다")
    void detectArrivalDelay_deadlinePassedAndNotArrived_createsAnomalyEvent(TimeCase t) {
        // given — 마감(예상+10분)이 이미 지난 예상 도착 시각(같은 날 안)
        clock = clockAt(t.now());
        ScheduledPlace scheduled = scheduledPlace(t.past());
        when(placeArrivalScheduleRepository.findScheduledByDayOfWeek(anyInt(), any()))
                .thenReturn(new PageImpl<>(List.of(scheduled)));
        when(visitHistoryRepository.existsByUserIdAndPlaceIdAndArrivalTimeGreaterThanEqual(
                        eq(CARE_TARGET_ID), eq(PLACE_ID), any()))
                .thenReturn(false);
        when(anomalyEventRepository.findOpenArrivalDelay(CARE_TARGET_ID, PLACE_ID))
                .thenReturn(Optional.empty());

        // when
        scheduler().tick();

        // then
        ArgumentCaptor<AnomalyEvent> captor = ArgumentCaptor.forClass(AnomalyEvent.class);
        verify(anomalyEventRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(AnomalyEvent.TYPE_ARRIVAL_DELAY);
        assertThat(captor.getValue().getUserId()).isEqualTo(CARE_TARGET_ID);
        assertThat(captor.getValue().getPlaceId()).isEqualTo(PLACE_ID);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clockTimes")
    @DisplayName("ARRIVAL_DELAY 생성 시 scheduled_at(예정 도착 시각)만 채우고 stay_started_at은 비워 둔다")
    void detectArrivalDelay_created_fillsScheduledAtSnapshotOnly(TimeCase t) {
        // given
        clock = clockAt(t.now());
        LocalTime expectedTime = t.past();
        ScheduledPlace scheduled = scheduledPlace(expectedTime);
        when(placeArrivalScheduleRepository.findScheduledByDayOfWeek(anyInt(), any()))
                .thenReturn(new PageImpl<>(List.of(scheduled)));
        when(visitHistoryRepository.existsByUserIdAndPlaceIdAndArrivalTimeGreaterThanEqual(
                        eq(CARE_TARGET_ID), eq(PLACE_ID), any()))
                .thenReturn(false);
        when(anomalyEventRepository.findOpenArrivalDelay(CARE_TARGET_ID, PLACE_ID))
                .thenReturn(Optional.empty());

        // when
        scheduler().tick();

        // then — scheduled_at은 "오늘 예정 도착 시각"이고 detected_at은 그로부터 감지 기준(분) 뒤다
        ArgumentCaptor<AnomalyEvent> captor = ArgumentCaptor.forClass(AnomalyEvent.class);
        verify(anomalyEventRepository).save(captor.capture());
        Instant expectedAt = TEST_DATE.atTime(expectedTime).atZone(ZONE).toInstant();
        assertThat(captor.getValue().getScheduledAt()).isEqualTo(expectedAt);
        assertThat(captor.getValue().getDetectedAt())
                .isEqualTo(expectedAt.plus(DETECT_MINUTES, ChronoUnit.MINUTES));
        assertThat(captor.getValue().getStayStartedAt()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clockTimes")
    @DisplayName("이미 열린 ARRIVAL_DELAY 이벤트가 있으면 같은 스케줄에 대해 다시 생성하지 않는다")
    void detectArrivalDelay_alreadyOpenEvent_doesNotCreateDuplicate(TimeCase t) {
        // given
        clock = clockAt(t.now());
        ScheduledPlace scheduled = scheduledPlace(t.past());
        when(placeArrivalScheduleRepository.findScheduledByDayOfWeek(anyInt(), any()))
                .thenReturn(new PageImpl<>(List.of(scheduled)));
        when(visitHistoryRepository.existsByUserIdAndPlaceIdAndArrivalTimeGreaterThanEqual(
                        eq(CARE_TARGET_ID), eq(PLACE_ID), any()))
                .thenReturn(false);
        AnomalyEvent existing =
                AnomalyEvent.createArrivalDelay(
                        CARE_TARGET_ID,
                        PLACE_ID,
                        clock.instant().minus(20, ChronoUnit.MINUTES),
                        clock.instant().minus(30, ChronoUnit.MINUTES));
        when(anomalyEventRepository.findOpenArrivalDelay(CARE_TARGET_ID, PLACE_ID))
                .thenReturn(Optional.of(existing));

        // when
        scheduler().tick();

        // then
        verify(anomalyEventRepository, never()).save(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clockTimes")
    @DisplayName("오늘 이미 도착한 기록이 있으면 열려 있던 ARRIVAL_DELAY 이벤트를 해제한다")
    void detectArrivalDelay_arrivedToday_resolvesOpenEvent(TimeCase t) {
        // given
        clock = clockAt(t.now());
        ScheduledPlace scheduled = scheduledPlace(t.past());
        when(placeArrivalScheduleRepository.findScheduledByDayOfWeek(anyInt(), any()))
                .thenReturn(new PageImpl<>(List.of(scheduled)));
        when(visitHistoryRepository.existsByUserIdAndPlaceIdAndArrivalTimeGreaterThanEqual(
                        eq(CARE_TARGET_ID), eq(PLACE_ID), any()))
                .thenReturn(true);
        AnomalyEvent existing =
                AnomalyEvent.createArrivalDelay(
                        CARE_TARGET_ID,
                        PLACE_ID,
                        clock.instant().minus(20, ChronoUnit.MINUTES),
                        clock.instant().minus(30, ChronoUnit.MINUTES));
        when(anomalyEventRepository.findOpenArrivalDelay(CARE_TARGET_ID, PLACE_ID))
                .thenReturn(Optional.of(existing));

        // when
        scheduler().tick();

        // then
        assertThat(existing.getResolvedAt()).isNotNull();
        verify(anomalyEventRepository).save(existing);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clockTimes")
    @DisplayName("청크 경계 — 여러 페이지에 걸친 스케줄을 전부 처리한다(hasNext 반복)")
    void detectArrivalDelay_pagingAcrossChunks_processesAllPages(TimeCase t) {
        // given — 페이지 크기 2, 총 3건(page0=2건, page1=1건)
        clock = clockAt(t.now());
        ScheduledPlace a = scheduledPlace(t.past());
        ScheduledPlace b = scheduledPlace(t.past());
        ScheduledPlace c = scheduledPlace(t.past());
        // argThat 람다는 Mockito가 기존 스텁(@BeforeEach의 any() 기본값)과의 매칭을 판단하는 과정에서
        // null을 인자로 한 번 미리 호출해볼 수 있어 null-safe하게 작성한다(그렇지 않으면 스텁 등록 자체에서 NPE).
        when(placeArrivalScheduleRepository.findScheduledByDayOfWeek(
                        anyInt(), argThat((Pageable p) -> p != null && p.getPageNumber() == 0)))
                .thenReturn(new PageImpl<>(List.of(a, b), org.springframework.data.domain.PageRequest.of(0, 2), 3));
        when(placeArrivalScheduleRepository.findScheduledByDayOfWeek(
                        anyInt(), argThat((Pageable p) -> p != null && p.getPageNumber() == 1)))
                .thenReturn(new PageImpl<>(List.of(c), org.springframework.data.domain.PageRequest.of(1, 2), 3));
        when(visitHistoryRepository.existsByUserIdAndPlaceIdAndArrivalTimeGreaterThanEqual(
                        eq(CARE_TARGET_ID), eq(PLACE_ID), any()))
                .thenReturn(false);
        when(anomalyEventRepository.findOpenArrivalDelay(CARE_TARGET_ID, PLACE_ID))
                .thenReturn(Optional.empty());

        // when
        scheduler().tick();

        // then — 3건 전부 평가되어 존재 여부 조회가 3번 발생해야 한다(page0 2건 + page1 1건)
        verify(visitHistoryRepository, org.mockito.Mockito.times(3))
                .existsByUserIdAndPlaceIdAndArrivalTimeGreaterThanEqual(
                        eq(CARE_TARGET_ID), eq(PLACE_ID), any());
        verify(placeArrivalScheduleRepository)
                .findScheduledByDayOfWeek(
                        anyInt(), argThat((Pageable p) -> p != null && p.getPageNumber() == 1));
    }

    // ---------------------------------------------------------------------
    // 역할2: 승격
    // ---------------------------------------------------------------------

    private AnomalyEvent openArrivalDelayEvent(Instant detectedAt) {
        AnomalyEvent event = AnomalyEvent.createArrivalDelay(
                        CARE_TARGET_ID, PLACE_ID, detectedAt, detectedAt.minus(10, ChronoUnit.MINUTES));
        ReflectionTestUtils.setField(event, "id", 500L);
        return event;
    }

    private GuardianTarget activeGuardian(
            Long guardianTargetId, Long guardianId, String mode, Integer escalateArrival) {
        GuardianTarget guardian =
                GuardianTarget.createActive(guardianId, CARE_TARGET_ID, GuardianTarget.ROLE_SUB);
        ReflectionTestUtils.setField(guardian, "id", guardianTargetId);
        guardian.updateNotificationMode(mode, escalateArrival, 60);
        return guardian;
    }

    @Test
    @DisplayName("감지 후 escalate_minutes_arrival이 아직 안 지났으면 승격 알림을 보내지 않는다")
    void escalate_belowThreshold_doesNotDispatch() {
        // given — 5분 전 감지, escalate 기준 30분(아직 25분 남음)
        AnomalyEvent event = openArrivalDelayEvent(clock.instant().minus(5, ChronoUnit.MINUTES));
        when(anomalyEventRepository.findByResolvedAtIsNull()).thenReturn(List.of(event));
        GuardianTarget guardian =
                activeGuardian(1L, 11L, GuardianTarget.NOTIFICATION_MODE_HYBRID, 30);
        when(guardianTargetRepository.findByTargetIdAndStatus(
                        CARE_TARGET_ID, GuardianTarget.STATUS_ACTIVE))
                .thenReturn(List.of(guardian));

        // when
        scheduler().tick();

        // then
        verify(notificationDispatchService, never()).dispatchAnomalyEscalation(any());
    }

    @Test
    @DisplayName("escalate_minutes_arrival이 NULL이면 아무리 시간이 지나도 절대 승격되지 않는다")
    void escalate_nullEscalateMinutes_neverEscalates() {
        // given
        AnomalyEvent event = openArrivalDelayEvent(clock.instant().minus(6, ChronoUnit.HOURS));
        when(anomalyEventRepository.findByResolvedAtIsNull()).thenReturn(List.of(event));
        GuardianTarget guardian =
                activeGuardian(1L, 11L, GuardianTarget.NOTIFICATION_MODE_HYBRID, null);
        when(guardianTargetRepository.findByTargetIdAndStatus(
                        CARE_TARGET_ID, GuardianTarget.STATUS_ACTIVE))
                .thenReturn(List.of(guardian));

        // when
        scheduler().tick();

        // then
        verify(notificationDispatchService, never()).dispatchAnomalyEscalation(any());
        assertThat(event.getEscalatedAt()).isNull();
    }

    @Test
    @DisplayName("이미 이 Guardian에게 통지된 이벤트는 다시 승격 발송하지 않는다")
    void escalate_alreadyNotifiedGuardian_doesNotDispatchAgain() {
        // given
        AnomalyEvent event = openArrivalDelayEvent(clock.instant().minus(20, ChronoUnit.MINUTES));
        when(anomalyEventRepository.findByResolvedAtIsNull()).thenReturn(List.of(event));
        GuardianTarget guardian =
                activeGuardian(1L, 11L, GuardianTarget.NOTIFICATION_MODE_REALTIME, 10);
        when(guardianTargetRepository.findByTargetIdAndStatus(
                        CARE_TARGET_ID, GuardianTarget.STATUS_ACTIVE))
                .thenReturn(List.of(guardian));
        when(notificationHistoryRepository.existsByAnomalyEventIdAndUserId(event.getId(), 11L))
                .thenReturn(true);

        // when
        scheduler().tick();

        // then
        verify(notificationDispatchService, never()).dispatchAnomalyEscalation(any());
    }

    @Test
    @DisplayName(
            "같은 이벤트에 Guardian별 escalate_minutes가 다르면, 임계값을 넘긴 Guardian만 개별적으로 승격된다"
                    + "(§4.2 확정: A — Guardian 개인별 자율성)")
    void escalate_multipleGuardiansDifferentThresholds_individualJudgement() {
        // given — 감지 후 15분 경과. Guardian A는 10분 기준(승격 대상), Guardian B는 60분 기준(아직 대상 아님)
        AnomalyEvent event = openArrivalDelayEvent(clock.instant().minus(15, ChronoUnit.MINUTES));
        when(anomalyEventRepository.findByResolvedAtIsNull()).thenReturn(List.of(event));
        GuardianTarget guardianA =
                activeGuardian(1L, 11L, GuardianTarget.NOTIFICATION_MODE_REALTIME, 10);
        GuardianTarget guardianB =
                activeGuardian(2L, 22L, GuardianTarget.NOTIFICATION_MODE_HYBRID, 60);
        when(guardianTargetRepository.findByTargetIdAndStatus(
                        CARE_TARGET_ID, GuardianTarget.STATUS_ACTIVE))
                .thenReturn(List.of(guardianA, guardianB));
        when(notificationHistoryRepository.existsByAnomalyEventIdAndUserId(event.getId(), 11L))
                .thenReturn(false);

        // when
        scheduler().tick();

        // then — Guardian A(11L)에게만 승격 알림이 나가고 Guardian B(22L)는 대상에서 빠진다
        ArgumentCaptor<AnomalyEscalationTarget> captor =
                ArgumentCaptor.forClass(AnomalyEscalationTarget.class);
        verify(notificationDispatchService).dispatchAnomalyEscalation(captor.capture());
        assertThat(captor.getValue().guardianId()).isEqualTo(11L);
        assertThat(event.getEscalatedAt()).isNotNull();
    }

    @Test
    @DisplayName("REPORT_ONLY 모드의 Guardian은 아무리 시간이 지나도 즉시 알림으로 승격되지 않는다")
    void escalate_modeReportOnly_neverEscalates() {
        // given
        AnomalyEvent event = openArrivalDelayEvent(clock.instant().minus(6, ChronoUnit.HOURS));
        when(anomalyEventRepository.findByResolvedAtIsNull()).thenReturn(List.of(event));
        GuardianTarget guardian =
                activeGuardian(1L, 11L, GuardianTarget.NOTIFICATION_MODE_REPORT_ONLY, 10);
        when(guardianTargetRepository.findByTargetIdAndStatus(
                        CARE_TARGET_ID, GuardianTarget.STATUS_ACTIVE))
                .thenReturn(List.of(guardian));

        // when
        scheduler().tick();

        // then
        verify(notificationDispatchService, never()).dispatchAnomalyEscalation(any());
    }

    // ---------------------------------------------------------------------
    // 역할3: PAUSED 자동 복귀
    // ---------------------------------------------------------------------

    private GuardianTarget pausedGuardian(Instant pauseNow, int durationMinutes) {
        GuardianTarget guardian =
                GuardianTarget.createActive(11L, CARE_TARGET_ID, GuardianTarget.ROLE_SUB);
        ReflectionTestUtils.setField(guardian, "id", 1L);
        guardian.pause(pauseNow, durationMinutes);
        when(guardianTargetRepository.findByNotificationMode(GuardianTarget.NOTIFICATION_MODE_PAUSED))
                .thenReturn(List.of(guardian));
        return guardian;
    }

    @Test
    @DisplayName("paused_until이 지난 Guardian은 잠금을 잡고 다시 확인하는 서비스(resumeIfDue)에 복귀를 맡긴다 — 스케줄러가 직접 저장하지 않는다")
    void resumePausedGuardians_pauseDue_delegatesToLockedResumeIfDue() {
        // given — paused_until = 1시간 30분 전(이미 지남)
        pausedGuardian(clock.instant().minus(2, ChronoUnit.HOURS), 30);
        when(guardianNotificationSettingsService.resumeIfDue(eq(1L), any(Instant.class)))
                .thenReturn(true);

        // when
        scheduler().tick();

        // then — 잠금 없는 읽기 값으로 엔티티를 직접 고치고 save하던 옛 경로(갱신 유실 위험)를 쓰지 않는다
        verify(guardianNotificationSettingsService).resumeIfDue(eq(1L), any(Instant.class));
        verify(guardianTargetRepository, never()).save(any());
    }

    @Test
    @DisplayName("paused_until이 아직 남은 Guardian은 복귀 요청 자체를 하지 않는다")
    void resumePausedGuardians_pauseNotYetDue_doesNotCallResume() {
        // given — paused_until = 지금부터 60분(아직 한참 남음)
        GuardianTarget guardian = pausedGuardian(clock.instant(), 60);

        // when
        scheduler().tick();

        // then
        assertThat(guardian.getNotificationMode()).isEqualTo(GuardianTarget.NOTIFICATION_MODE_PAUSED);
        verify(guardianNotificationSettingsService, never())
                .resumeIfDue(any(Long.class), any(Instant.class));
        verify(guardianTargetRepository, never()).save(any());
    }

    @Test
    @DisplayName("오래된 목록상 복귀 대상이어도 그 사이 사용자가 연장/해제했다면(resumeIfDue=false) 아무 것도 하지 않고 tick이 정상 완료된다")
    void resumePausedGuardians_staleCandidateAlreadyHandled_isSkippedWithoutError() {
        // given — 목록은 "지남"이지만 잠금 후 재확인에서 이미 처리됨
        pausedGuardian(clock.instant().minus(2, ChronoUnit.HOURS), 30);
        when(guardianNotificationSettingsService.resumeIfDue(eq(1L), any(Instant.class)))
                .thenReturn(false);

        // when & then
        assertThatCode(() -> scheduler().tick()).doesNotThrowAnyException();
        verify(guardianNotificationSettingsService).resumeIfDue(eq(1L), any(Instant.class));
        verify(guardianTargetRepository, never()).save(any());
    }
}
