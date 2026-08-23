package com.tracecare.backend.domain.location.caretarget.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.common.exception.business.CareTargetNotFoundException;
import com.tracecare.backend.common.exception.business.InvalidLocationCoordinateException;
import com.tracecare.backend.common.exception.business.LocationNotFoundException;
import com.tracecare.backend.common.validation.LatitudeValidator;
import com.tracecare.backend.common.validation.LongitudeValidator;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.location.caretarget.dto.request.LocationSendRequest;
import com.tracecare.backend.domain.location.caretarget.dto.response.LocationSendResponse;
import com.tracecare.backend.domain.location.entity.LocationHistory;
import com.tracecare.backend.domain.location.guardian.dto.response.CurrentLocationResponse;
import com.tracecare.backend.domain.location.repository.LocationHistoryRepository;
import com.tracecare.backend.domain.location.repository.LocationHistoryWriter;
import com.tracecare.backend.domain.location.service.LocationCacheStore;
import com.tracecare.backend.domain.location.service.LocationHistoryAsyncWriter;
import com.tracecare.backend.domain.location.service.LocationRealtimePublisher;

/**
 * API_Specification.md §4.1(위치 전송/자기 조회), §4.3(즉시 공유). CareTarget Role 여부는
 * SecurityConfig(`/api/care-target/**` → `hasRole("CARE_TARGET")`)가 이미 Filter 단계에서 걸러내므로 Service
 * 계층에서 중복 검증하지 않는다(LOCATION_003/004는 JwtAccessDeniedHandler가 경로별로 매핑).
 */
@Service
public class LocationService {

    private final LatitudeValidator latitudeValidator = new LatitudeValidator();
    private final LongitudeValidator longitudeValidator = new LongitudeValidator();

    private final UserRepository userRepository;
    private final LocationHistoryRepository locationHistoryRepository;
    private final LocationHistoryWriter locationHistoryWriter;
    private final LocationCacheStore locationCacheStore;
    private final LocationHistoryAsyncWriter locationHistoryAsyncWriter;
    private final LocationRealtimePublisher locationRealtimePublisher;

    public LocationService(
            UserRepository userRepository,
            LocationHistoryRepository locationHistoryRepository,
            LocationHistoryWriter locationHistoryWriter,
            LocationCacheStore locationCacheStore,
            LocationHistoryAsyncWriter locationHistoryAsyncWriter,
            LocationRealtimePublisher locationRealtimePublisher) {
        this.userRepository = userRepository;
        this.locationHistoryRepository = locationHistoryRepository;
        this.locationHistoryWriter = locationHistoryWriter;
        this.locationCacheStore = locationCacheStore;
        this.locationHistoryAsyncWriter = locationHistoryAsyncWriter;
        this.locationRealtimePublisher = locationRealtimePublisher;
    }

    /**
     * POST /api/care-target/location — 응답에 생성된 PK({@code locationId})를 그대로 돌려줘야 해서(§4.1) 저장을 동기로
     * 처리한다. Redis 캐시 갱신도 같은 요청 안에서 동기로 수행해, 이 응답을 받은 클라이언트가 곧바로 자기 위치를 재조회해도 최신 값이 보이게 한다.
     */
    @Transactional
    public LocationSendResponse sendLocation(Long callerId, LocationSendRequest request) {
        BigDecimal latitude = validateAndConvertLatitude(request.getLatitude());
        BigDecimal longitude = validateAndConvertLongitude(request.getLongitude());

        Long locationId =
                locationHistoryWriter.insert(
                        callerId, latitude, longitude, request.getRecordedAt());

        User caller = findCaller(callerId);
        locationCacheStore.write(
                caller.getPublicId(),
                request.getLatitude(),
                request.getLongitude(),
                request.getRecordedAt());
        locationRealtimePublisher.publish(
                callerId,
                caller.getPublicId().toString(),
                request.getLatitude(),
                request.getLongitude(),
                request.getRecordedAt());

        return LocationSendResponse.builder()
                .locationId(locationId)
                .recordedAt(request.getRecordedAt())
                .build();
    }

    /** GET /api/care-target/location — 본인의 최신 위치, Redis 우선 조회 후 DB 폴백(§3.3과 동일 형식 재사용). */
    @Transactional(readOnly = true)
    public CurrentLocationResponse getMyLocation(Long callerId) {
        User caller = findCaller(callerId);

        LocationCacheStore.CachedLocation cached = locationCacheStore.read(caller.getPublicId());
        if (cached != null) {
            return CurrentLocationResponse.of(
                    caller,
                    cached.latitude(),
                    cached.longitude(),
                    cached.recordedAt(),
                    CurrentLocationResponse.SOURCE_REDIS_CACHE);
        }

        LocationHistory latest =
                locationHistoryRepository
                        .findFirstByUserIdOrderByRecordedAtDesc(callerId)
                        .orElseThrow(LocationNotFoundException::new);
        return CurrentLocationResponse.of(
                caller,
                latest.getLatitude().doubleValue(),
                latest.getLongitude().doubleValue(),
                latest.getRecordedAt(),
                CurrentLocationResponse.SOURCE_DB);
    }

    /**
     * POST /api/care-target/share/location — Phase 1에서는 Redis 갱신 + LocationHistory 저장까지만 구현했고,
     * Phase 2(이번)에서 WebSocket 개인화 큐 발행을 마저 연결했다. DB 저장은 여전히 {@link LocationHistoryAsyncWriter}로 완전히
     * 비동기 처리한다(§4.3 Response에 PK가 없어 응답을 기다릴 필요가 없음). Redis 캐시 갱신과 WebSocket 발행은 "즉시 공유"라는 이름에 맞게
     * 동기로 수행한다.
     */
    public void shareLocation(Long callerId, LocationSendRequest request) {
        BigDecimal latitude = validateAndConvertLatitude(request.getLatitude());
        BigDecimal longitude = validateAndConvertLongitude(request.getLongitude());

        User caller = findCaller(callerId);
        locationCacheStore.write(
                caller.getPublicId(),
                request.getLatitude(),
                request.getLongitude(),
                request.getRecordedAt());
        locationRealtimePublisher.publish(
                callerId,
                caller.getPublicId().toString(),
                request.getLatitude(),
                request.getLongitude(),
                request.getRecordedAt());
        locationHistoryAsyncWriter.persist(callerId, latitude, longitude, request.getRecordedAt());
    }

    private BigDecimal validateAndConvertLatitude(Double latitude) {
        if (!latitudeValidator.isValid(latitude, null)) {
            throw new InvalidLocationCoordinateException();
        }
        return BigDecimal.valueOf(latitude);
    }

    private BigDecimal validateAndConvertLongitude(Double longitude) {
        if (!longitudeValidator.isValid(longitude, null)) {
            throw new InvalidLocationCoordinateException();
        }
        return BigDecimal.valueOf(longitude);
    }

    private User findCaller(Long callerId) {
        return userRepository.findById(callerId).orElseThrow(CareTargetNotFoundException::new);
    }
}
