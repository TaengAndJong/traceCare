package com.tracecare.backend.domain.anomaly.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.tracecare.backend.common.cache.CacheKeyGenerator;
import com.tracecare.backend.domain.anomaly.entity.AnomalyEvent;
import com.tracecare.backend.domain.anomaly.repository.AnomalyEventRepository;
import com.tracecare.backend.domain.anomaly.repository.PlaceArrivalScheduleRepository;
import com.tracecare.backend.domain.anomaly.repository.PlaceArrivalScheduleRepository.ScheduledPlace;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.notification.repository.NotificationHistoryRepository;
import com.tracecare.backend.domain.notification.service.NotificationDispatchService;
import com.tracecare.backend.domain.notification.service.NotificationDispatchService.AnomalyEscalationTarget;
import com.tracecare.backend.domain.visit.repository.VisitHistoryRepository;

/**
 * DATABASE_DESIGN_GUIDE.md §15.1/§15.2/§15.3이 확정한 3가지 책임을 하나의 tick이 공유한다(§15.1 "새 스케줄러를
 * 추가로 만들지 않고 기존 tick에 얹는다") — ① {@link #detectArrivalDelays} ARRIVAL_DELAY 감지, ② {@link
 * #escalate} REALTIME/HYBRID 승격, ③ {@link #resumePausedGuardians} PAUSED 자동 복귀.
 *
 * <p><b>트랜잭션 경계(§4.2 확정: B)</b>: 이 클래스 어디에도 {@code @Transactional}이 없다. 각 단계는 Spring Data
 * JPA({@code SimpleJpaRepository})가 개별 조회/저장 메서드에 이미 걸어둔 짧은 트랜잭션에 의존하고,
 * {@code NotificationDispatchService.dispatchAnomalyEscalation}의 FCM 호출(외부 I/O)은 그 어떤 트랜잭션에도
 * 감싸이지 않는다. 큰 메서드 하나를 {@code @Transactional}로 감쌌다면 그 안의 FCM 호출까지 트랜잭션에 포함돼
 * {@code database.md}의 "트랜잭션 내부 외부 API 호출 금지" 원칙과 어긋난다({@code
 * NotificationDispatchService#dispatchArrival}이 이미 이 문제를 갖고 있음을 이번에 발견 — 결과 보고서 참고).
 *
 * <p><b>분산 락(§4.2 확정: D)</b>: {@code anomaly:scheduler:lock}(Redis)로 tick 전체를 감싼다. 현재 단일
 * 인스턴스 배포라 실질 효과는 없으나, 수평 확장 시 여러 인스턴스가 같은 tick을 동시에 실행해 ARRIVAL_DELAY/승격 알림이
 * 중복 발송되는 것을 막기 위해 미리 설계해뒀다. Redis 장애로 락 획득 자체가 실패하면(다른 인스턴스가 쥐고 있어서가
 * 아니라 Redis에 닿지 않아서) 락 없이 진행한다 — 단일 인스턴스 환경에서 락 부재는 실제 위험이 아닌데, 그것 때문에
 * 이상행동 감지 전체가 멈추는 쪽이 더 나쁜 결과라고 판단했다(fail-open, Cache_Strategy_Guide.md §3.2 각주).
 */
@Component
public class AnomalyScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnomalyScheduler.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final PlaceArrivalScheduleRepository placeArrivalScheduleRepository;
    private final AnomalyEventRepository anomalyEventRepository;
    private final VisitHistoryRepository visitHistoryRepository;
    private final GuardianTargetRepository guardianTargetRepository;
    private final NotificationHistoryRepository notificationHistoryRepository;
    private final NotificationDispatchService notificationDispatchService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheKeyGenerator cacheKeyGenerator;
    private final int arrivalDelayDetectMinutes;
    private final int scanPageSize;
    private final Duration lockTtl;

    public AnomalyScheduler(
            PlaceArrivalScheduleRepository placeArrivalScheduleRepository,
            AnomalyEventRepository anomalyEventRepository,
            VisitHistoryRepository visitHistoryRepository,
            GuardianTargetRepository guardianTargetRepository,
            NotificationHistoryRepository notificationHistoryRepository,
            NotificationDispatchService notificationDispatchService,
            RedisTemplate<String, Object> redisTemplate,
            CacheKeyGenerator cacheKeyGenerator,
            @Value("${anomaly.arrival-delay-detect-minutes}") int arrivalDelayDetectMinutes,
            @Value("${anomaly.arrival-delay-scan-page-size}") int scanPageSize,
            @Value("${anomaly.scheduler.lock-ttl-minutes}") long lockTtlMinutes) {
        this.placeArrivalScheduleRepository = placeArrivalScheduleRepository;
        this.anomalyEventRepository = anomalyEventRepository;
        this.visitHistoryRepository = visitHistoryRepository;
        this.guardianTargetRepository = guardianTargetRepository;
        this.notificationHistoryRepository = notificationHistoryRepository;
        this.notificationDispatchService = notificationDispatchService;
        this.redisTemplate = redisTemplate;
        this.cacheKeyGenerator = cacheKeyGenerator;
        this.arrivalDelayDetectMinutes = arrivalDelayDetectMinutes;
        this.scanPageSize = scanPageSize;
        this.lockTtl = Duration.ofMinutes(lockTtlMinutes);
    }

    @Scheduled(fixedDelayString = "#{${anomaly.scheduler.fixed-delay-minutes:5} * 60000}")
    public void tick() {
        log.info("event=ANOMALY_SCHEDULER_TICK_STARTED");
        String lockValue = UUID.randomUUID().toString();
        if (!tryLock(lockValue)) {
            log.info("event=ANOMALY_SCHEDULER_TICK_SKIPPED, reason=lock_held_elsewhere");
            return;
        }
        try {
            detectArrivalDelays();
            escalate();
            resumePausedGuardians();
        } finally {
            unlock(lockValue);
        }
        log.info("event=ANOMALY_SCHEDULER_TICK_COMPLETED");
    }

    // ---------------------------------------------------------------------
    // 역할1: ARRIVAL_DELAY 감지
    // ---------------------------------------------------------------------

    private void detectArrivalDelays() {
        int dayOfWeek = LocalDate.now(ZONE).getDayOfWeek().getValue();
        Instant todayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();

        int pageNumber = 0;
        Page<ScheduledPlace> page;
        do {
            page =
                    placeArrivalScheduleRepository.findScheduledByDayOfWeek(
                            dayOfWeek, PageRequest.of(pageNumber, scanPageSize));
            page.getContent().forEach(scheduled -> evaluateSchedule(scheduled, todayStart));
            pageNumber++;
        } while (page.hasNext());
    }

    private void evaluateSchedule(ScheduledPlace scheduled, Instant todayStart) {
        boolean arrivedToday =
                visitHistoryRepository.existsByUserIdAndPlaceIdAndArrivalTimeGreaterThanEqual(
                        scheduled.getTargetId(), scheduled.getPlaceId(), todayStart);

        if (arrivedToday) {
            anomalyEventRepository
                    .findOpenArrivalDelay(scheduled.getTargetId(), scheduled.getPlaceId())
                    .ifPresent(this::resolveArrivalDelay);
            return;
        }

        Instant expectedAt =
                LocalDate.now(ZONE)
                        .atTime(scheduled.getExpectedArrivalTime())
                        .atZone(ZONE)
                        .toInstant();
        Instant deadline = expectedAt.plus(arrivalDelayDetectMinutes, ChronoUnit.MINUTES);
        if (Instant.now().isBefore(deadline)) {
            return;
        }
        if (anomalyEventRepository
                .findOpenArrivalDelay(scheduled.getTargetId(), scheduled.getPlaceId())
                .isPresent()) {
            return;
        }

        AnomalyEvent event =
                AnomalyEvent.createArrivalDelay(
                        scheduled.getTargetId(), scheduled.getPlaceId(), deadline, expectedAt);
        anomalyEventRepository.save(event);
        log.info(
                "event=ANOMALY_ARRIVAL_DELAY_DETECTED, careTargetId={}, placeId={}, anomalyEventId={}",
                scheduled.getTargetId(),
                scheduled.getPlaceId(),
                event.getId());
    }

    private void resolveArrivalDelay(AnomalyEvent event) {
        event.resolve(Instant.now());
        anomalyEventRepository.save(event);
        log.info(
                "event=ANOMALY_ARRIVAL_DELAY_RESOLVED, careTargetId={}, anomalyEventId={}",
                event.getUserId(),
                event.getId());
    }

    // ---------------------------------------------------------------------
    // 역할2: 승격(Escalation) — Guardian별 개별 판단(§4.2 확정: A)
    // ---------------------------------------------------------------------

    private void escalate() {
        Instant now = Instant.now();
        List<AnomalyEvent> openEvents = anomalyEventRepository.findByResolvedAtIsNull();
        for (AnomalyEvent event : openEvents) {
            List<GuardianTarget> guardians =
                    guardianTargetRepository.findByTargetIdAndStatus(
                            event.getUserId(), GuardianTarget.STATUS_ACTIVE);
            for (GuardianTarget guardian : guardians) {
                evaluateEscalation(event, guardian, now);
            }
        }
    }

    private void evaluateEscalation(AnomalyEvent event, GuardianTarget guardian, Instant now) {
        if (!isEscalationEligible(guardian.getNotificationMode())) {
            return;
        }
        Integer escalateMinutes =
                AnomalyEvent.TYPE_ARRIVAL_DELAY.equals(event.getType())
                        ? guardian.getEscalateMinutesArrival()
                        : guardian.getEscalateMinutesStay();
        if (escalateMinutes == null) {
            // §15.2/§15.3 확정: NULL이면 이 유형은 즉시 알림으로 절대 승격되지 않는다(리포트로만 처리).
            return;
        }
        if (now.isBefore(event.getDetectedAt().plus(escalateMinutes, ChronoUnit.MINUTES))) {
            return;
        }
        if (notificationHistoryRepository.existsByAnomalyEventIdAndUserId(
                event.getId(), guardian.getGuardianId())) {
            return;
        }

        event.escalate(now);
        anomalyEventRepository.save(event);

        notificationDispatchService.dispatchAnomalyEscalation(
                new AnomalyEscalationTarget(
                        guardian.getGuardianId(),
                        event.getUserId(),
                        event.getId(),
                        event.getType(),
                        event.getPlaceId()));
    }

    private boolean isEscalationEligible(String notificationMode) {
        return GuardianTarget.NOTIFICATION_MODE_REALTIME.equals(notificationMode)
                || GuardianTarget.NOTIFICATION_MODE_HYBRID.equals(notificationMode);
    }

    // ---------------------------------------------------------------------
    // 역할3: PAUSED 자동 복귀
    // ---------------------------------------------------------------------

    private void resumePausedGuardians() {
        Instant now = Instant.now();
        List<GuardianTarget> paused =
                guardianTargetRepository.findByNotificationMode(
                        GuardianTarget.NOTIFICATION_MODE_PAUSED);
        for (GuardianTarget guardian : paused) {
            if (guardian.isPauseDue(now)) {
                guardian.resumeFromPause();
                guardianTargetRepository.save(guardian);
                log.info(
                        "event=ANOMALY_PAUSE_AUTO_RESUMED, guardianTargetId={}", guardian.getId());
            }
        }
    }

    // ---------------------------------------------------------------------
    // 분산 락
    // ---------------------------------------------------------------------

    private boolean tryLock(String value) {
        try {
            Boolean acquired =
                    redisTemplate
                            .opsForValue()
                            .setIfAbsent(cacheKeyGenerator.anomalySchedulerLock(), value, lockTtl);
            return Boolean.TRUE.equals(acquired);
        } catch (DataAccessException | SerializationException e) {
            log.warn("event=ANOMALY_SCHEDULER_LOCK_ACQUIRE_FAILED, proceeding_without_lock=true", e);
            return true;
        }
    }

    private void unlock(String value) {
        try {
            String key = cacheKeyGenerator.anomalySchedulerLock();
            Object current = redisTemplate.opsForValue().get(key);
            if (value.equals(current)) {
                redisTemplate.delete(key);
            }
        } catch (DataAccessException | SerializationException e) {
            log.warn("event=ANOMALY_SCHEDULER_LOCK_RELEASE_FAILED, ttl_will_expire_naturally=true", e);
        }
    }
}
