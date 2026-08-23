package com.tracecare.backend.domain.place.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.common.cache.CacheKeyGenerator;
import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.auth.AccessDeniedCustomException;
import com.tracecare.backend.common.exception.business.CareTargetNotFoundException;
import com.tracecare.backend.common.exception.business.DuplicatePlaceException;
import com.tracecare.backend.common.exception.business.InvalidPlaceRangeException;
import com.tracecare.backend.common.exception.business.PlaceCapacityExceededException;
import com.tracecare.backend.common.exception.business.PlaceNotFoundException;
import com.tracecare.backend.common.exception.business.PlaceNotPrimaryGuardianException;
import com.tracecare.backend.common.exception.infra.DataAccessCustomException;
import com.tracecare.backend.common.util.GeoDistanceCalculator;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.place.dto.request.PlaceCreateRequest;
import com.tracecare.backend.domain.place.dto.request.PlaceUpdateRequest;
import com.tracecare.backend.domain.place.dto.response.PlaceResponse;
import com.tracecare.backend.domain.place.entity.Place;
import com.tracecare.backend.domain.place.repository.PlaceRepository;

/**
 * Place(안심구역) CRUD. 소유권/권한 검증은 GuardianTargetRepository를 직접 조회해 이 도메인의 예외 클래스로 던진다 — 실제 조회 로직은
 * GuardianTarget의 것을 그대로 재사용하되(Coding_Convention.md §1.3 "관계 조회는 관계의 주인 쪽에 둔다"의 취지는 Repository
 * 재사용으로 충족), 예외 클래스는 Exception_Handling_Rule.md §7.2("도메인이 다르면 예외 클래스를 분리한다")에 따라 Place 도메인 전용으로
 * 던진다.
 */
@Service
public class PlaceService {

    private static final Logger log = LoggerFactory.getLogger(PlaceService.class);

    /** Cache_Strategy_Guide.md §3.2 "Place/GeoFence 목록" TTL 범위(5~10분) 중 상한을 채택. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final PlaceRepository placeRepository;
    private final UserRepository userRepository;
    private final GuardianTargetRepository guardianTargetRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheKeyGenerator cacheKeyGenerator;
    private final int placeLimit;
    private final double duplicateDistanceMeters;

    public PlaceService(
            PlaceRepository placeRepository,
            UserRepository userRepository,
            GuardianTargetRepository guardianTargetRepository,
            RedisTemplate<String, Object> redisTemplate,
            CacheKeyGenerator cacheKeyGenerator,
            @Value("${place.care-target-limit}") int placeLimit,
            @Value("${place.duplicate-distance-meters}") double duplicateDistanceMeters) {
        this.placeRepository = placeRepository;
        this.userRepository = userRepository;
        this.guardianTargetRepository = guardianTargetRepository;
        this.redisTemplate = redisTemplate;
        this.cacheKeyGenerator = cacheKeyGenerator;
        this.placeLimit = placeLimit;
        this.duplicateDistanceMeters = duplicateDistanceMeters;
    }

    /** GET /api/guardian/places?careTargetId={id} — PRIMARY/SUB 모두 조회 가능. Cache Aside. */
    @Transactional(readOnly = true)
    public List<PlaceResponse> getPlaces(Long callerId, UUID careTargetPublicId) {
        User target = findTargetByPublicId(careTargetPublicId);
        assertActiveRelation(callerId, target.getId());

        String cacheKey = cacheKeyGenerator.placeList(String.valueOf(target.getId()));
        List<PlaceResponse> cached = readListCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<PlaceResponse> result =
                placeRepository.findByTargetIdOrderByCreatedAtAsc(target.getId()).stream()
                        .map(place -> PlaceResponse.of(place, target))
                        .toList();
        writeListCache(cacheKey, result);
        return result;
    }

    /** POST /api/guardian/places — ACTIVE PRIMARY 전용. */
    @Transactional
    public PlaceResponse createPlace(Long callerId, PlaceCreateRequest request) {
        User target = findTargetByPublicId(UUID.fromString(request.getCareTargetId()));
        assertActivePrimary(callerId, target.getId());

        BigDecimal latitude = BigDecimal.valueOf(request.getLatitude());
        BigDecimal longitude = BigDecimal.valueOf(request.getLongitude());
        if (isDuplicate(target.getId(), null, request.getName(), latitude, longitude)) {
            throw new DuplicatePlaceException();
        }

        long activeCount = placeRepository.countByTargetId(target.getId());
        if (activeCount >= placeLimit) {
            throw new PlaceCapacityExceededException();
        }

        Place place =
                Place.createActive(
                        callerId,
                        target.getId(),
                        request.getName(),
                        request.getAddress(),
                        latitude,
                        longitude,
                        request.getRadius());
        try {
            place = placeRepository.saveAndFlush(place);
        } catch (DataIntegrityViolationException e) {
            log.warn("event=PLACE_CONSTRAINT_VIOLATION, targetId={}", target.getId());
            throw new InvalidPlaceRangeException();
        }

        evictListCache(target.getId());
        return PlaceResponse.of(place, target);
    }

    /** PUT /api/guardian/places/{id} — ACTIVE PRIMARY 전용, 낙관적 락(version) 충돌 시 COMMON_008. */
    @Transactional
    public PlaceResponse updatePlace(
            Long callerId, UUID placePublicId, PlaceUpdateRequest request) {
        Place place = findPlaceByPublicId(placePublicId);
        assertActivePrimary(callerId, place.getTargetId());

        BigDecimal latitude = BigDecimal.valueOf(request.getLatitude());
        BigDecimal longitude = BigDecimal.valueOf(request.getLongitude());
        if (isDuplicate(
                place.getTargetId(), place.getId(), request.getName(), latitude, longitude)) {
            throw new DuplicatePlaceException();
        }

        place.update(
                request.getName(), request.getAddress(), latitude, longitude, request.getRadius());
        flushOrTranslateConflict(place.getTargetId());

        User target =
                userRepository
                        .findById(place.getTargetId())
                        .orElseThrow(CareTargetNotFoundException::new);
        evictListCache(place.getTargetId());
        return PlaceResponse.of(place, target);
    }

    /** DELETE /api/guardian/places/{id} — ACTIVE PRIMARY 전용, Soft Delete. */
    @Transactional
    public void deletePlace(Long callerId, UUID placePublicId) {
        Place place = findPlaceByPublicId(placePublicId);
        assertActivePrimary(callerId, place.getTargetId());

        place.delete();
        flushOrTranslateConflict(place.getTargetId());
        evictListCache(place.getTargetId());
    }

    /**
     * {@code place.update()}/{@code place.delete()} 이후 명시적으로 flush해 낙관적 락 충돌 ({@link
     * OptimisticLockingFailureException})을 이 메서드 안에서 즉시 확인한다 — 명시적 flush 없이 트랜잭션 커밋 시점(메서드 반환 이후)에
     * 자동 flush가 일어나면 이 try-catch 밖에서 예외가 발생해 GlobalExceptionHandler의 범용 500 처리로 흘러가 버린다.
     */
    private void flushOrTranslateConflict(Long targetId) {
        try {
            placeRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.warn("event=PLACE_UPDATE_LOCK_CONFLICT, targetId={}", targetId);
            throw new DataAccessCustomException(ErrorCode.COMMON_008);
        }
    }

    /**
     * 같은 CareTarget 내 등록된 Place들 중 이름이 같거나(정확 일치), Haversine 실거리가 {@code
     * place.duplicate-distance-meters}(기본 50m) 이내인 것이 있으면 중복으로 판단한다(PLACE_002). 좌표 완전 일치가 아니라 실거리
     * 기준으로 바꾼 이유는 모바일 GPS 특성상 같은 장소를 다시 등록해도 좌표가 미세하게 달라지는 게 일반적이기 때문이다(2026-08 확정, 최초 30m에서 실내/도심
     * GPS 오차를 감안해 50m로 완화). {@code excludePlaceId}는 PUT에서 자기 자신을 비교 대상에서 빼기 위함이며, POST에서는 {@code
     * null}을 넘긴다. CareTarget당 최대 15개 소프트 상한이 있어 애플리케이션에서 전체 순회해도 부담이 없다(SQL로는 Haversine 조건을 표현할 수
     * 없어 PlaceRepository가 아니라 여기서 계산한다).
     */
    private boolean isDuplicate(
            Long targetId,
            Long excludePlaceId,
            String name,
            BigDecimal latitude,
            BigDecimal longitude) {
        return placeRepository.findByTargetIdOrderByCreatedAtAsc(targetId).stream()
                .filter(existing -> !existing.getId().equals(excludePlaceId))
                .anyMatch(
                        existing ->
                                existing.getName().equals(name)
                                        || GeoDistanceCalculator.distanceInMeters(
                                                        existing.getLatitude().doubleValue(),
                                                        existing.getLongitude().doubleValue(),
                                                        latitude.doubleValue(),
                                                        longitude.doubleValue())
                                                <= duplicateDistanceMeters);
    }

    private void assertActiveRelation(Long guardianId, Long targetId) {
        guardianTargetRepository
                .findByGuardianIdAndTargetIdAndStatus(
                        guardianId, targetId, GuardianTarget.STATUS_ACTIVE)
                .orElseThrow(() -> new AccessDeniedCustomException(ErrorCode.TARGET_002));
    }

    private void assertActivePrimary(Long guardianId, Long targetId) {
        GuardianTarget relation =
                guardianTargetRepository
                        .findByGuardianIdAndTargetIdAndStatus(
                                guardianId, targetId, GuardianTarget.STATUS_ACTIVE)
                        .orElseThrow(() -> new AccessDeniedCustomException(ErrorCode.TARGET_002));
        if (!relation.isPrimary()) {
            throw new PlaceNotPrimaryGuardianException();
        }
    }

    private User findTargetByPublicId(UUID targetPublicId) {
        return userRepository
                .findByPublicId(targetPublicId)
                .orElseThrow(CareTargetNotFoundException::new);
    }

    private Place findPlaceByPublicId(UUID placePublicId) {
        return placeRepository
                .findByPublicId(placePublicId)
                .orElseThrow(PlaceNotFoundException::new);
    }

    /**
     * Place/GeoFence 목록은 PostgreSQL이 Source of Truth이므로, Redis 장애 시 예외를 던지지 않고 DB 원본 조회로 자동
     * 폴백한다(Cache_Strategy_Guide.md §6, Exception_Handling_Rule.md §10.4).
     *
     * <p>{@code List<PlaceResponse>}를 {@code RedisTemplate<String, Object>}에 그대로 저장하면 {@link
     * com.tracecare.backend.common.cache.RedisConfig}의 {@code GenericJackson2JsonRedisSerializer}가
     * 최상위 List 자체에는 다형성 타입 정보(`@class`)를 붙이지 않아, 다시 읽어올 때(값 타입을 모르는 {@code Object}로 역직렬화) "need
     * String, Number of Boolean value that contains type id"로 실패한다. 리스트를 {@link PlaceListCache} 같은
     * 구체 클래스로 한 번 감싸면 그 래퍼 자체에 `@class`가 붙어 정상적으로 왕복한다(캐시 값을 단일 객체로 저장해온 다른 도메인의 기존 패턴과 동일).
     */
    /**
     * {@link SerializationException}을 {@link DataAccessException}과 별도로 잡는다 — 캐시 값이 RedisConfig의
     * {@code @class} 화이트리스트 밖 타입으로 역직렬화를 시도하면 발생하는데, {@code NestedRuntimeException}을 상속해 {@code
     * DataAccessException} 계열이 아니다(실제로 화이트리스트 밖 클래스명을 주입해 재현하기 전까지 놓치고 있었다). 조작되었거나 호환되지 않는 캐시 값도
     * Redis 장애와 동일하게 DB 폴백으로 처리한다.
     */
    private List<PlaceResponse> readListCache(String key) {
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            return cached instanceof PlaceListCache placeListCache ? placeListCache.places() : null;
        } catch (DataAccessException | SerializationException e) {
            log.warn("event=PLACE_CACHE_READ_FAILED, key={}", key, e);
            return null;
        }
    }

    private void writeListCache(String key, List<PlaceResponse> value) {
        try {
            // Stream.toList()가 반환하는 java.util.ImmutableCollections$ListN은 Jackson이 역직렬화할 수 있는
            // 표준 생성자가 없어(내부 전용 클래스) 왕복이 깨진다 — 캐시에는 항상 평범한 ArrayList로 담는다.
            redisTemplate
                    .opsForValue()
                    .set(key, new PlaceListCache(new ArrayList<>(value)), CACHE_TTL);
        } catch (DataAccessException | SerializationException e) {
            log.warn("event=PLACE_CACHE_WRITE_FAILED, key={}", key, e);
        }
    }

    private void evictListCache(Long targetId) {
        try {
            redisTemplate.delete(cacheKeyGenerator.placeList(String.valueOf(targetId)));
        } catch (DataAccessException | SerializationException e) {
            log.warn("event=PLACE_CACHE_EVICT_FAILED, targetId={}", targetId, e);
        }
    }

    /** {@code place:list:{targetId}} Redis 값(Cache_Strategy_Guide.md §3.2). */
    public record PlaceListCache(List<PlaceResponse> places) {}
}
