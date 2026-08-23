package com.tracecare.backend.domain.location.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Service;

import com.tracecare.backend.common.cache.CacheKeyGenerator;

/**
 * {@code location:latest:{careTargetId}}(Cache_Strategy_Guide.md §3.2) 읽기/쓰기를 한 곳에 모은다 — Guardian
 * 조회(LocationQueryService)와 CareTarget 전송(LocationService) 양쪽에서 동일한 키 규칙과 TTL 정책을 써야 하기 때문이다. 키의
 * {@code careTargetId}는 문서 각주("API_Response_Rule.md 예시에서 careTargetId를 public_id로 쓰기로 한 정책과 캐시 키
 * 표기를 일치")에 따라 내부 PK가 아닌 CareTarget의 {@code public_id}를 쓴다.
 *
 * <p><b>TTL 24시간으로 결정한 근거</b>: Cache_Strategy_Guide.md §3.2가 "TTL 없음(계속 덮어씀) 또는 긴 TTL(1일)" 두 선택지를
 * 열어뒀는데, 이번 세션에서 24시간으로 확정했다. TTL을 아예 걸지 않으면 CareTarget 앱이 꺼지거나 네트워크가 끊겨도 마지막 위치가 캐시에 영원히 남아 "지금도
 * 위치가 파악되고 있다"는 착각을 줄 수 있다. 안전 서비스 특성상 "위치 추적이 끊겼다"는 신호 자체가 보호자에게 유의미한 정보이므로, 일정 시간(24시간) 이상 갱신이
 * 없으면 캐시가 자연 만료되어 이후 조회는 DB 폴백을 거치게 하고, DB에도 최근 기록이 없으면 LOCATION_002(404)로 "위치 정보 없음"을 명확히 알린다.
 *
 * <p>이 데이터의 Source of Truth는 문서상 Redis이지만(DB는 "이력 보관용"), CareTarget의 최신 위치는 항상 LocationHistory
 * INSERT를 동반하므로 DB로도 재구성 가능하다 — Refresh Token처럼 대체 불가능한 보안 데이터가 아니므로, Redis 장애 시에도 예외를 던지지 않고 DB
 * 조회로 자연스럽게 폴백한다(Exception_Handling_Rule.md §10.4 "캐시 성격 데이터" 취급).
 */
@Service
public class LocationCacheStore {

    private static final Logger log = LoggerFactory.getLogger(LocationCacheStore.class);
    private static final Duration TTL = Duration.ofHours(24);

    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheKeyGenerator cacheKeyGenerator;

    public LocationCacheStore(
            RedisTemplate<String, Object> redisTemplate, CacheKeyGenerator cacheKeyGenerator) {
        this.redisTemplate = redisTemplate;
        this.cacheKeyGenerator = cacheKeyGenerator;
    }

    /**
     * {@link SerializationException}을 {@link DataAccessException}과 별도로 잡는 이유: 캐시에 저장된 값이
     * {@code @class} 화이트리스트(RedisConfig)에 없는 타입으로 역직렬화를 시도하면 이 예외가 발생하는데, {@code
     * SerializationException}은 {@code NestedRuntimeException}을 상속해 {@code DataAccessException} 계열이
     * 아니다 — 실제로 화이트리스트 밖 클래스명을 주입해 재현하기 전까지는 이 catch 블록이 놓치고 있었다. 조작되었거나 호환되지 않는 캐시 값은 Redis 장애와
     * 마찬가지로 "이번 조회는 실패, DB로 폴백"으로 처리하는 것이 맞다.
     */
    public CachedLocation read(UUID careTargetPublicId) {
        try {
            Object cached =
                    redisTemplate
                            .opsForValue()
                            .get(cacheKeyGenerator.locationLatest(careTargetPublicId.toString()));
            return cached instanceof CachedLocation cachedLocation ? cachedLocation : null;
        } catch (DataAccessException | SerializationException e) {
            log.warn("event=LOCATION_CACHE_READ_FAILED, careTargetId={}", careTargetPublicId, e);
            return null;
        }
    }

    public void write(
            UUID careTargetPublicId, Double latitude, Double longitude, Instant recordedAt) {
        try {
            redisTemplate
                    .opsForValue()
                    .set(
                            cacheKeyGenerator.locationLatest(careTargetPublicId.toString()),
                            new CachedLocation(latitude, longitude, recordedAt),
                            TTL);
        } catch (DataAccessException | SerializationException e) {
            log.warn("event=LOCATION_CACHE_WRITE_FAILED, careTargetId={}", careTargetPublicId, e);
        }
    }

    public record CachedLocation(Double latitude, Double longitude, Instant recordedAt) {}
}
