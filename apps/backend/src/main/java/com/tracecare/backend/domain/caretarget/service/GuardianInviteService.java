package com.tracecare.backend.domain.caretarget.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Service;

import com.tracecare.backend.common.cache.CacheKeyGenerator;
import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.business.CareTargetNotFoundException;
import com.tracecare.backend.common.exception.business.DuplicatePendingInviteException;
import com.tracecare.backend.common.exception.business.InvalidInviteCodeException;
import com.tracecare.backend.common.exception.business.InviteRateLimitExceededException;
import com.tracecare.backend.common.exception.business.UserNotFoundException;
import com.tracecare.backend.common.exception.infra.DataAccessCustomException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.caretarget.dto.response.InviteApproveResponse;
import com.tracecare.backend.domain.caretarget.dto.response.InviteCodeResponse;
import com.tracecare.backend.domain.caretarget.dto.response.InviteRedeemResponse;
import com.tracecare.backend.domain.caretarget.dto.response.PendingGuardianResponse;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.service.GuardianTargetService;

/**
 * Guardian↔CareTarget 초대 토큰의 전체 생명주기(발급→Guardian 입력→CareTarget 승인/거절)를 Redis에서만 관리한다
 * (DATABASE_DESIGN_GUIDE.md §3.2 — PostgreSQL 테이블 없음). 실제 GuardianTarget 행 생성(정원 검증 포함)은 {@link
 * GuardianTargetService#createRelation}에 위임하고, 이 클래스는 Redis 토큰 상태와 Rate Limit/실패 카운터만
 * 책임진다(Coding_Convention.md 책임 분리 원칙, 이번 세션 작업 지시서 §4).
 *
 * <p>Redis 키 설계(Cache_Strategy_Guide.md §3.2에 등록됨):
 *
 * <ul>
 *   <li>{@code invite:token:{code}} — 원본 토큰, TTL 10분, Source of Truth
 *   <li>{@code invite:pending:{careTargetId}} — Hash(guardianId→code), CareTarget이 대기 목록을 조회하기 위한
 *       보조 색인(원본 토큰 없이는 존재해도 무효로 취급하는 지연 무효화 방식)
 *   <li>{@code invite:count:{careTargetId}} — 코드 생성 Rate Limit 카운터, 당일 자정 TTL
 *   <li>{@code invite:fail:{code}} — 코드 입력 실패 카운터, TTL 10분
 * </ul>
 *
 * Refresh Token/JWT Blacklist와 마찬가지로 이 데이터의 Source of Truth는 Redis이므로(Cache_Strategy_Guide.md §6),
 * Redis 장애 시 폴백하지 않고 COMMON_007(503)로 명시적으로 실패 처리한다(TokenService와 동일한 패턴).
 */
@Service
public class GuardianInviteService {

    private static final Logger log = LoggerFactory.getLogger(GuardianInviteService.class);

    /**
     * 사람이 입력하기 쉽도록 혼동되는 문자(0/O, 1/I/L)를 제외한 32자 집합. 8자리 조합 시 엔트로피는 log2(32^8) ≈ 40bit로, 토큰당 5회 실패 시
     * 즉시 폐기되는 정책과 결합하면 무차별 대입으로 알아내는 것이 사실상 불가능하다(문서에 정확한 포맷 규정이 없어 이번 세션에서 합리적으로 결정, 결과 보고에 명시).
     */
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private static final int CODE_LENGTH = 8;
    private static final int MAX_CODE_GENERATION_ATTEMPTS = 5;
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);
    private static final long DAILY_GENERATION_LIMIT = 5;
    private static final long INPUT_FAIL_LIMIT = 5;

    private final SecureRandom secureRandom = new SecureRandom();

    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheKeyGenerator cacheKeyGenerator;
    private final UserRepository userRepository;
    private final GuardianTargetService guardianTargetService;

    public GuardianInviteService(
            RedisTemplate<String, Object> redisTemplate,
            CacheKeyGenerator cacheKeyGenerator,
            UserRepository userRepository,
            GuardianTargetService guardianTargetService) {
        this.redisTemplate = redisTemplate;
        this.cacheKeyGenerator = cacheKeyGenerator;
        this.userRepository = userRepository;
        this.guardianTargetService = guardianTargetService;
    }

    /** (CareTarget) 초대 코드 생성 — 5회/일 Rate Limit, TTL 10분(§5.1). */
    public InviteCodeResponse generateInviteCode(Long careTargetId) {
        enforceDailyRateLimit(careTargetId);

        String code = generateUniqueCode();
        Instant expiresAt = Instant.now().plus(TOKEN_TTL);
        redisSet(
                cacheKeyGenerator.inviteToken(code),
                new InvitePayload(careTargetId, null),
                TOKEN_TTL);

        log.info("event=INVITE_CODE_GENERATED, careTargetId={}", careTargetId);
        return InviteCodeResponse.builder().inviteCode(code).expiresAt(expiresAt).build();
    }

    /**
     * (Guardian) 코드 입력 → 연결 요청(§5.2). Guardian 1인당 CareTarget 등록 소프트 상한(DATABASE_DESIGN_GUIDE.md
     * §13/§14)은 CareTarget의 ACTIVE Guardian 정원(3명, 승인 시점 검증)과 축이 다른 Guardian 쪽 제한이므로, 승인이 아니라
     * Guardian 본인이 코드를 입력하는 이 시점에 검증해 더 빠르게 피드백한다.
     */
    public InviteRedeemResponse redeemInviteCode(Long guardianId, String code) {
        InvitePayload payload = getValidPayload(code);
        guardianTargetService.assertCareTargetCapacityAvailable(guardianId);

        String pendingKey = cacheKeyGenerator.invitePending(String.valueOf(payload.careTargetId()));
        HashOperations<String, Object, Object> pendingOps = redisHashOps();
        if (Boolean.TRUE.equals(redisHasHashKey(pendingOps, pendingKey, guardianId.toString()))) {
            throw new DuplicatePendingInviteException();
        }

        Long remainingTtlSeconds = redisTemplate.getExpire(cacheKeyGenerator.inviteToken(code));
        if (remainingTtlSeconds == null || remainingTtlSeconds <= 0) {
            recordFailureAndMaybePurge(code);
            throw new InvalidInviteCodeException();
        }

        redisSet(
                cacheKeyGenerator.inviteToken(code),
                new InvitePayload(payload.careTargetId(), guardianId),
                Duration.ofSeconds(remainingTtlSeconds));
        redisHashPut(pendingOps, pendingKey, guardianId.toString(), code);

        User target =
                userRepository
                        .findById(payload.careTargetId())
                        .orElseThrow(CareTargetNotFoundException::new);

        log.info(
                "event=INVITE_CODE_REDEEMED, guardianId={}, careTargetId={}",
                guardianId,
                payload.careTargetId());
        return InviteRedeemResponse.builder()
                .careTargetId(target.getPublicId().toString())
                .name(target.getName())
                .status("PENDING")
                .build();
    }

    /** (CareTarget) 승인 대기 목록 조회(§5.3) — 만료된 토큰은 조회 시점에 지연 정리한다. */
    public List<PendingGuardianResponse> getPendingRequests(Long careTargetId) {
        String pendingKey = cacheKeyGenerator.invitePending(String.valueOf(careTargetId));
        Map<Object, Object> entries = redisHashEntries(pendingKey);

        return entries.entrySet().stream()
                .map(
                        entry -> {
                            Long guardianId = Long.valueOf(entry.getKey().toString());
                            String code = entry.getValue().toString();
                            InvitePayload payload =
                                    redisGetPayload(cacheKeyGenerator.inviteToken(code));
                            if (payload == null || !guardianId.equals(payload.guardianId())) {
                                redisHashDelete(pendingKey, entry.getKey());
                                return null;
                            }
                            return guardianId;
                        })
                .filter(Objects::nonNull)
                .distinct()
                .map(this::toPendingGuardianResponse)
                .collect(Collectors.toList());
    }

    /** (CareTarget) 요청 승인(§5.4) — 정원/PRIMARY 배정은 GuardianTargetService.createRelation에 위임. */
    public InviteApproveResponse approve(Long careTargetId, UUID guardianPublicId) {
        User guardian =
                userRepository
                        .findByPublicId(guardianPublicId)
                        .orElseThrow(UserNotFoundException::new);
        String code = resolvePendingCode(careTargetId, guardian.getId());

        GuardianTarget guardianTarget =
                guardianTargetService.createRelation(guardian.getId(), careTargetId);

        redisDelete(cacheKeyGenerator.inviteToken(code));
        redisHashDelete(
                cacheKeyGenerator.invitePending(String.valueOf(careTargetId)),
                guardian.getId().toString());
        redisDelete(cacheKeyGenerator.inviteFail(code));

        log.info(
                "event=INVITE_APPROVED, careTargetId={}, guardianId={}, guardianRole={}",
                careTargetId,
                guardian.getId(),
                guardianTarget.getGuardianRole());
        return InviteApproveResponse.of(guardianTarget, guardian);
    }

    /** (CareTarget) 요청 거절(§5.5) — Redis에서만 삭제, DB 변경 없음. */
    public void reject(Long careTargetId, UUID guardianPublicId) {
        User guardian =
                userRepository
                        .findByPublicId(guardianPublicId)
                        .orElseThrow(UserNotFoundException::new);
        String code = resolvePendingCode(careTargetId, guardian.getId());

        redisDelete(cacheKeyGenerator.inviteToken(code));
        redisHashDelete(
                cacheKeyGenerator.invitePending(String.valueOf(careTargetId)),
                guardian.getId().toString());
        redisDelete(cacheKeyGenerator.inviteFail(code));

        log.info(
                "event=INVITE_REJECTED, careTargetId={}, guardianId={}",
                careTargetId,
                guardian.getId());
    }

    private String resolvePendingCode(Long careTargetId, Long guardianId) {
        Object code =
                redisHashGet(
                        cacheKeyGenerator.invitePending(String.valueOf(careTargetId)),
                        guardianId.toString());
        if (code == null) {
            throw new InvalidInviteCodeException();
        }
        String token = code.toString();

        InvitePayload payload = redisGetPayload(cacheKeyGenerator.inviteToken(token));
        if (payload == null
                || !careTargetId.equals(payload.careTargetId())
                || !guardianId.equals(payload.guardianId())) {
            redisHashDelete(
                    cacheKeyGenerator.invitePending(String.valueOf(careTargetId)),
                    guardianId.toString());
            throw new InvalidInviteCodeException();
        }
        return token;
    }

    private PendingGuardianResponse toPendingGuardianResponse(Long guardianId) {
        User guardian = userRepository.findById(guardianId).orElseThrow(UserNotFoundException::new);
        return PendingGuardianResponse.builder()
                .guardianId(guardian.getPublicId().toString())
                .name(guardian.getName())
                .build();
    }

    private InvitePayload getValidPayload(String code) {
        InvitePayload payload = redisGetPayload(cacheKeyGenerator.inviteToken(code));
        if (payload == null) {
            recordFailureAndMaybePurge(code);
            throw new InvalidInviteCodeException();
        }
        return payload;
    }

    /** 토큰당 입력 실패 5회 초과 시 즉시 폐기(DATABASE_DESIGN_GUIDE.md §7). */
    private void recordFailureAndMaybePurge(String code) {
        String failKey = cacheKeyGenerator.inviteFail(code);
        Long failCount = redisIncrement(failKey);
        if (failCount != null && failCount == 1) {
            redisExpire(failKey, TOKEN_TTL);
        }
        if (failCount != null && failCount >= INPUT_FAIL_LIMIT) {
            redisDelete(cacheKeyGenerator.inviteToken(code));
            redisDelete(failKey);
        }
    }

    private void enforceDailyRateLimit(Long careTargetId) {
        String countKey = cacheKeyGenerator.inviteCount(String.valueOf(careTargetId));
        Long count = redisIncrement(countKey);
        if (count != null && count == 1) {
            redisExpire(countKey, secondsUntilMidnight());
        }
        if (count != null && count > DAILY_GENERATION_LIMIT) {
            throw new InviteRateLimitExceededException();
        }
    }

    private Duration secondsUntilMidnight() {
        ZoneId zone = ZoneId.systemDefault();
        Instant nextMidnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();
        return Duration.between(Instant.now(), nextMidnight);
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_CODE_GENERATION_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (!redisHasKey(cacheKeyGenerator.inviteToken(candidate))) {
                return candidate;
            }
        }
        throw new DataAccessCustomException(ErrorCode.COMMON_001);
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(secureRandom.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private InvitePayload redisGetPayload(String key) {
        Object value = redisGet(key);
        return value instanceof InvitePayload invitePayload ? invitePayload : null;
    }

    private Object redisGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (DataAccessException | SerializationException e) {
            log.error("event=REDIS_UNAVAILABLE, operation=GET", e);
            throw new DataAccessCustomException(ErrorCode.COMMON_007);
        }
    }

    private void redisSet(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (DataAccessException | SerializationException e) {
            log.error("event=REDIS_UNAVAILABLE, operation=SET", e);
            throw new DataAccessCustomException(ErrorCode.COMMON_007);
        }
    }

    private void redisDelete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException | SerializationException e) {
            log.error("event=REDIS_UNAVAILABLE, operation=DELETE", e);
            throw new DataAccessCustomException(ErrorCode.COMMON_007);
        }
    }

    private boolean redisHasKey(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (DataAccessException | SerializationException e) {
            log.error("event=REDIS_UNAVAILABLE, operation=HAS_KEY", e);
            throw new DataAccessCustomException(ErrorCode.COMMON_007);
        }
    }

    private Long redisIncrement(String key) {
        try {
            return redisTemplate.opsForValue().increment(key);
        } catch (DataAccessException | SerializationException e) {
            log.error("event=REDIS_UNAVAILABLE, operation=INCREMENT", e);
            throw new DataAccessCustomException(ErrorCode.COMMON_007);
        }
    }

    private void redisExpire(String key, Duration ttl) {
        try {
            redisTemplate.expire(key, ttl);
        } catch (DataAccessException | SerializationException e) {
            log.error("event=REDIS_UNAVAILABLE, operation=EXPIRE", e);
            throw new DataAccessCustomException(ErrorCode.COMMON_007);
        }
    }

    private HashOperations<String, Object, Object> redisHashOps() {
        return redisTemplate.opsForHash();
    }

    private Boolean redisHasHashKey(
            HashOperations<String, Object, Object> ops, String key, Object hashKey) {
        try {
            return ops.hasKey(key, hashKey);
        } catch (DataAccessException | SerializationException e) {
            log.error("event=REDIS_UNAVAILABLE, operation=HASH_HAS_KEY", e);
            throw new DataAccessCustomException(ErrorCode.COMMON_007);
        }
    }

    private void redisHashPut(
            HashOperations<String, Object, Object> ops, String key, Object hashKey, Object value) {
        try {
            ops.put(key, hashKey, value);
        } catch (DataAccessException | SerializationException e) {
            log.error("event=REDIS_UNAVAILABLE, operation=HASH_PUT", e);
            throw new DataAccessCustomException(ErrorCode.COMMON_007);
        }
    }

    private Object redisHashGet(String key, Object hashKey) {
        try {
            return redisHashOps().get(key, hashKey);
        } catch (DataAccessException | SerializationException e) {
            log.error("event=REDIS_UNAVAILABLE, operation=HASH_GET", e);
            throw new DataAccessCustomException(ErrorCode.COMMON_007);
        }
    }

    private Map<Object, Object> redisHashEntries(String key) {
        try {
            return redisHashOps().entries(key);
        } catch (DataAccessException | SerializationException e) {
            log.error("event=REDIS_UNAVAILABLE, operation=HASH_ENTRIES", e);
            throw new DataAccessCustomException(ErrorCode.COMMON_007);
        }
    }

    private void redisHashDelete(String key, Object hashKey) {
        try {
            redisHashOps().delete(key, hashKey);
        } catch (DataAccessException | SerializationException e) {
            log.error("event=REDIS_UNAVAILABLE, operation=HASH_DELETE", e);
            throw new DataAccessCustomException(ErrorCode.COMMON_007);
        }
    }

    /** Redis invite:token:{code} 값(Cache_Strategy_Guide.md §3.2). guardianId는 코드 입력 전에는 null이다. */
    public record InvitePayload(Long careTargetId, Long guardianId) {}
}
