package com.tracecare.backend.domain.anomaly.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.common.cache.CacheKeyGenerator;
import com.tracecare.backend.common.util.GeoDistanceCalculator;
import com.tracecare.backend.domain.anomaly.entity.AnomalyEvent;
import com.tracecare.backend.domain.anomaly.repository.AnomalyEventRepository;
import com.tracecare.backend.domain.place.dto.response.PlaceResponse;
import com.tracecare.backend.domain.place.entity.Place;
import com.tracecare.backend.domain.place.repository.PlaceRepository;
import com.tracecare.backend.domain.place.service.PlaceService;

/**
 * DATABASE_DESIGN_GUIDE.md §15.2 UNREGISTERED_STAY 감지. {@code GeoFenceService}와는 별도 서비스로 분리해
 * "도착/이탈 전이 판정"과 "등록 안 된 곳에서의 누적 체류 판정"이라는 서로 다른 종류의 상태 판단을 섞지 않는다 — {@code
 * LocationService}가 {@code geoFenceService.evaluate()}의 반환값(Place 매칭 여부)을 그대로 넘겨줘 Place 매칭 로직 자체는
 * 이 서비스에서 중복 계산하지 않는다(검증 v2 §2.3 이후 확정).
 *
 * <p><b>감지 이전 상태(candidate) — Redis가 Source of Truth</b>: 임계값({@code
 * anomaly.unregistered-stay-detect-minutes}, 기본 10분)을 넘기기 전까지 "머무는 중"이라는 상태는 PostgreSQL 어디에도
 * 대응 데이터가 없다 — {@code anomaly:candidate:{careTargetId}} 캐시가 유일한 원본이다(비용/성능 우선 결정,
 * Cache_Strategy_Guide.md §3.2/§6 각주). 매 위치 수신마다 {@code LocationHistory} range 쿼리로 재계산하는 대안도
 * 검토했으나, 위치 수신이 잦은 CareTarget마다 반복적인 DB 조회가 발생해 배제했다. 분류상 `location:latest`/FCM Token과 같은
 * "Redis가 Source of Truth인 데이터"(cache.md §Source of Truth 구분)이지만, 이 데이터에 한해서는 유실을 fail-open으로
 * 허용한다 — 유실돼도 다음 위치 수신 시 새 후보로 자동 재시작되므로(카운트만 리셋) 이미 감지된 {@code AnomalyEvent}나 {@code
 * EMERGENCY_*}류의 fail-safe 필수 대상과는 안전 요구 수준이 다르기 때문이다.
 *
 * <p><b>{@code startedAt} 고정 규칙</b>: 후보가 반경 안에 있는 동안은 {@code startedAt}을 갱신하지 않는다 — 반경을 벗어나는
 * 순간에만 새 후보로 교체하며 그때 {@code startedAt}도 새로 설정한다. 같은 반경 안에서는 "재진입"이라는 개념 자체가 없기 때문이다.
 *
 * <p><b>이미 감지된 이벤트가 진행 중일 때</b>: 후보 캐시 대신 {@code anomaly:active:{careTargetId}:{type}} 캐시(DB
 * 폴백 포함)로 열린 {@code AnomalyEvent}를 확인한다 — 이 단계부터는 실제 감지 결과(Source of Truth는 PostgreSQL)이므로 후보
 * 캐시와 다른 신뢰 수준을 갖는다. 실시간/HYBRID 승격 알림 발송은 이 서비스가 아니라 {@code AnomalyScheduler}(§4.2)가 담당한다 —
 * 이 서비스는 "감지"까지만 책임진다.
 */
@Service
public class UnregisteredStayDetector {

    private static final Logger log = LoggerFactory.getLogger(UnregisteredStayDetector.class);

    /** Cache_Strategy_Guide.md §3.2 확정값 — anomaly.unregistered-stay-detect-minutes(기본 10분) + 5분 여유. */
    private static final Duration CANDIDATE_TTL = Duration.ofMinutes(15);

    /** Cache_Strategy_Guide.md §3.2 {@code anomaly:active:*} TTL. */
    private static final Duration ACTIVE_TTL = Duration.ofMinutes(5);

    private final PlaceService placeService;
    private final PlaceRepository placeRepository;
    private final AnomalyEventRepository anomalyEventRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheKeyGenerator cacheKeyGenerator;
    private final int detectMinutes;
    private final double stayRadiusMeters;

    public UnregisteredStayDetector(
            PlaceService placeService,
            PlaceRepository placeRepository,
            AnomalyEventRepository anomalyEventRepository,
            RedisTemplate<String, Object> redisTemplate,
            CacheKeyGenerator cacheKeyGenerator,
            @Value("${anomaly.unregistered-stay-detect-minutes}") int detectMinutes,
            @Value("${anomaly.unregistered-stay-radius-meters}") double stayRadiusMeters) {
        this.placeService = placeService;
        this.placeRepository = placeRepository;
        this.anomalyEventRepository = anomalyEventRepository;
        this.redisTemplate = redisTemplate;
        this.cacheKeyGenerator = cacheKeyGenerator;
        this.detectMinutes = detectMinutes;
        this.stayRadiusMeters = stayRadiusMeters;
    }

    /**
     * {@code LocationService}가 {@code geoFenceService.evaluate()} 호출 직후 매번 호출한다. {@code placeMatched}가
     * {@code true}면(등록된 Place 안에 있으면) 진행 중이던 감지를 전부 해제하고 반환한다.
     */
    @Transactional
    public void evaluate(
            Long careTargetId,
            double latitude,
            double longitude,
            Instant recordedAt,
            boolean placeMatched) {
        if (placeMatched) {
            resolveIfOpen(careTargetId, recordedAt);
            evictCandidate(careTargetId);
            return;
        }

        Optional<AnomalyEvent> openEvent = findOpenEvent(careTargetId);
        if (openEvent.isPresent()) {
            handleOpenEvent(openEvent.get(), latitude, longitude, recordedAt);
            return;
        }

        handleCandidate(careTargetId, latitude, longitude, recordedAt);
    }

    /** 등록된 Place에 도착한 시점 — 열려 있던 UNREGISTERED_STAY가 있다면 "정상적으로 등록 장소에 도착"으로 해제한다. */
    private void resolveIfOpen(Long careTargetId, Instant recordedAt) {
        findOpenEvent(careTargetId)
                .ifPresent(
                        event -> {
                            event.resolve(recordedAt);
                            anomalyEventRepository.save(event);
                            evictActiveCache(careTargetId);
                            log.info(
                                    "event=ANOMALY_UNREGISTERED_STAY_RESOLVED_BY_ARRIVAL,"
                                            + " careTargetId={}, anomalyEventId={}",
                                    careTargetId,
                                    event.getId());
                        });
    }

    /** 이미 감지된 이벤트가 진행 중일 때 — 반경을 벗어나면 "떠남"으로 해제하고 그 자리에서 새 후보를 시작한다. */
    private void handleOpenEvent(
            AnomalyEvent event, double latitude, double longitude, Instant recordedAt) {
        double distance =
                GeoDistanceCalculator.distanceInMeters(
                        event.getLatitude().doubleValue(),
                        event.getLongitude().doubleValue(),
                        latitude,
                        longitude);
        if (distance <= stayRadiusMeters) {
            return;
        }

        event.resolve(recordedAt);
        anomalyEventRepository.save(event);
        evictActiveCache(event.getUserId());
        log.info(
                "event=ANOMALY_UNREGISTERED_STAY_RESOLVED_BY_MOVEMENT, careTargetId={},"
                        + " anomalyEventId={}",
                event.getUserId(),
                event.getId());

        writeCandidate(event.getUserId(), new Candidate(latitude, longitude, recordedAt));
    }

    /** 아직 감지 전(임계값 미달) — 후보 캐시를 읽고 시작/유지/리셋/승격(AnomalyEvent 생성) 중 하나를 판단한다. */
    private void handleCandidate(
            Long careTargetId, double latitude, double longitude, Instant recordedAt) {
        Optional<Candidate> candidate = readCandidate(careTargetId);
        if (candidate.isEmpty()) {
            writeCandidate(careTargetId, new Candidate(latitude, longitude, recordedAt));
            return;
        }

        Candidate current = candidate.get();
        double distance =
                GeoDistanceCalculator.distanceInMeters(
                        current.lat(), current.lng(), latitude, longitude);
        if (distance > stayRadiusMeters) {
            // 반경 이탈 — 다른 미등록 지점으로 이동했으므로 새 후보로 교체(startedAt도 새로 설정).
            writeCandidate(careTargetId, new Candidate(latitude, longitude, recordedAt));
            return;
        }

        // 반경 안 — startedAt은 고정 유지(재진입 개념 없음), 임계값 도달 여부만 확인한다.
        if (Duration.between(current.startedAt(), recordedAt).toMinutes() < detectMinutes) {
            return;
        }

        Instant detectedAt = current.startedAt().plus(detectMinutes, ChronoUnit.MINUTES);
        Long nearestPlaceId = findNearestPlaceId(careTargetId, current.lat(), current.lng());
        AnomalyEvent event =
                AnomalyEvent.createUnregisteredStay(
                        careTargetId,
                        nearestPlaceId,
                        BigDecimal.valueOf(current.lat()),
                        BigDecimal.valueOf(current.lng()),
                        detectedAt,
                        current.startedAt());
        anomalyEventRepository.save(event);
        writeActiveCache(careTargetId, event.getId());
        evictCandidate(careTargetId);

        log.info(
                "event=ANOMALY_UNREGISTERED_STAY_DETECTED, careTargetId={}, anomalyEventId={},"
                        + " nearestPlaceId={}",
                careTargetId,
                event.getId(),
                nearestPlaceId);
    }

    /** 반경 안에서 가장 가까운 등록 Place(있으면) — AnomalyEvent.place_id 참고용(§15.2), 없으면 null. */
    private Long findNearestPlaceId(Long careTargetId, double latitude, double longitude) {
        List<PlaceResponse> places = placeService.getPlacesForGeofence(careTargetId);
        if (places.isEmpty()) {
            return null;
        }
        PlaceResponse nearest =
                places.stream()
                        .min(
                                Comparator.comparingDouble(
                                        place ->
                                                GeoDistanceCalculator.distanceInMeters(
                                                        place.getLatitude(),
                                                        place.getLongitude(),
                                                        latitude,
                                                        longitude)))
                        .orElse(null);
        if (nearest == null) {
            return null;
        }
        return placeRepository
                .findByPublicId(UUID.fromString(nearest.getPlaceId()))
                .map(Place::getId)
                .orElse(null);
    }

    /**
     * {@code anomaly:active:{careTargetId}:{type}} 캐시 우선 조회, 미스면 DB로 폴백하고 찾으면 캐시를 다시 채운다
     * (Cache_Strategy_Guide.md §3.2 — PostgreSQL이 Source of Truth, Redis는 가속용).
     */
    private Optional<AnomalyEvent> findOpenEvent(Long careTargetId) {
        Long cachedId = readActiveCache(careTargetId);
        if (cachedId != null) {
            Optional<AnomalyEvent> byId = anomalyEventRepository.findById(cachedId);
            if (byId.isPresent() && byId.get().isOpen()) {
                return byId;
            }
            evictActiveCache(careTargetId);
        }

        Optional<AnomalyEvent> fromDb =
                anomalyEventRepository.findFirstByUserIdAndTypeAndResolvedAtIsNullOrderByDetectedAtDesc(
                        careTargetId, AnomalyEvent.TYPE_UNREGISTERED_STAY);
        fromDb.ifPresent(event -> writeActiveCache(careTargetId, event.getId()));
        return fromDb;
    }

    private Long readActiveCache(Long careTargetId) {
        try {
            Object cached =
                    redisTemplate
                            .opsForValue()
                            .get(
                                    cacheKeyGenerator.anomalyActive(
                                            String.valueOf(careTargetId),
                                            AnomalyEvent.TYPE_UNREGISTERED_STAY));
            return cached instanceof String s ? Long.valueOf(s) : null;
        } catch (DataAccessException | SerializationException | NumberFormatException e) {
            log.warn("event=ANOMALY_ACTIVE_CACHE_READ_FAILED, careTargetId={}", careTargetId, e);
            return null;
        }
    }

    private void writeActiveCache(Long careTargetId, Long anomalyEventId) {
        try {
            redisTemplate
                    .opsForValue()
                    .set(
                            cacheKeyGenerator.anomalyActive(
                                    String.valueOf(careTargetId),
                                    AnomalyEvent.TYPE_UNREGISTERED_STAY),
                            String.valueOf(anomalyEventId),
                            ACTIVE_TTL);
        } catch (DataAccessException | SerializationException e) {
            log.warn("event=ANOMALY_ACTIVE_CACHE_WRITE_FAILED, careTargetId={}", careTargetId, e);
        }
    }

    private void evictActiveCache(Long careTargetId) {
        try {
            redisTemplate.delete(
                    cacheKeyGenerator.anomalyActive(
                            String.valueOf(careTargetId), AnomalyEvent.TYPE_UNREGISTERED_STAY));
        } catch (DataAccessException | SerializationException e) {
            log.warn("event=ANOMALY_ACTIVE_CACHE_EVICT_FAILED, careTargetId={}", careTargetId, e);
        }
    }

    private Optional<Candidate> readCandidate(Long careTargetId) {
        try {
            Object cached =
                    redisTemplate
                            .opsForValue()
                            .get(cacheKeyGenerator.anomalyCandidate(String.valueOf(careTargetId)));
            return cached instanceof Candidate c ? Optional.of(c) : Optional.empty();
        } catch (DataAccessException | SerializationException e) {
            log.warn("event=ANOMALY_CANDIDATE_CACHE_READ_FAILED, careTargetId={}", careTargetId, e);
            return Optional.empty();
        }
    }

    private void writeCandidate(Long careTargetId, Candidate candidate) {
        try {
            redisTemplate
                    .opsForValue()
                    .set(
                            cacheKeyGenerator.anomalyCandidate(String.valueOf(careTargetId)),
                            candidate,
                            CANDIDATE_TTL);
        } catch (DataAccessException | SerializationException e) {
            log.warn("event=ANOMALY_CANDIDATE_CACHE_WRITE_FAILED, careTargetId={}", careTargetId, e);
        }
    }

    private void evictCandidate(Long careTargetId) {
        try {
            redisTemplate.delete(cacheKeyGenerator.anomalyCandidate(String.valueOf(careTargetId)));
        } catch (DataAccessException | SerializationException e) {
            log.warn("event=ANOMALY_CANDIDATE_CACHE_EVICT_FAILED, careTargetId={}", careTargetId, e);
        }
    }

    /** {@code anomaly:candidate:{careTargetId}} Redis 값(Cache_Strategy_Guide.md §3.2). */
    public record Candidate(double lat, double lng, Instant startedAt) {}
}
