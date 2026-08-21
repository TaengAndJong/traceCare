package com.tracecare.backend.domain.auth.service;

import java.time.Duration;
import java.time.Instant;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.tracecare.backend.common.cache.CacheKeyGenerator;
import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.auth.AuthenticationFailedException;
import com.tracecare.backend.common.exception.infra.DataAccessCustomException;
import com.tracecare.backend.common.security.JwtTokenProvider;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;

/**
 * JWT 발급/재발급(Rotation)/로그아웃(Blacklist) 전담(Security_Guide.md §5). JWT 파싱/생성 자체는
 * common.security.JwtTokenProvider, 캐시 키 조립은 common.cache.CacheKeyGenerator를 재사용하고 이 클래스는 Redis
 * 저장/삭제 시점과 Rotation/Blacklist 정책만 책임진다.
 *
 * <p>Refresh Token/JWT Blacklist는 Redis가 Source of Truth인 보안 데이터이므로(Cache_Strategy_Guide.md §6),
 * Redis 장애 시 폴백하지 않고 COMMON_007(503)로 명시적으로 실패 처리한다(Exception_Handling_Rule.md §10.4). 이건 "값이
 * 없음/일치하지 않음"(정상 응답, AUTH_004 대상)과는 구분되는 별도 케이스다 — Redis 호출 자체를 {@link #redisGet}/{@link
 * #redisSet}/{@link #redisDelete}로 감싸 연결 실패만 골라 COMMON_007로 변환한다. Lettuce/Spring Data Redis가 던지는
 * 저수준 예외(연결 실패, timeout 등)는 전부 {@code org.springframework.dao.DataAccessException} 하위로 번역되므로
 * (RedisConnectionFailureException, RedisSystemException, QueryTimeoutException 등 개별 하위 타입을 일일이
 * 나열하지 않고) 이 공통 상위 타입 하나로 잡는다.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheKeyGenerator cacheKeyGenerator;
    private final UserRepository userRepository;

    public TokenService(
            JwtTokenProvider jwtTokenProvider,
            RedisTemplate<String, Object> redisTemplate,
            CacheKeyGenerator cacheKeyGenerator,
            UserRepository userRepository) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisTemplate = redisTemplate;
        this.cacheKeyGenerator = cacheKeyGenerator;
        this.userRepository = userRepository;
    }

    /** 신규 Access/Refresh 쌍을 발급하고 Refresh Token을 Redis에 저장한다(Security_Guide.md §5.5). */
    public TokenPair issueTokens(Long userId, String role) {
        String accessToken = jwtTokenProvider.generateAccessToken(userId, role);
        JwtTokenProvider.TokenInfo refreshTokenInfo =
                jwtTokenProvider.generateRefreshToken(userId, role);
        storeRefreshToken(userId, refreshTokenInfo);
        return new TokenPair(accessToken, refreshTokenInfo.token());
    }

    /**
     * Refresh Token Rotation(Security_Guide.md §5.5): 제출된 Refresh Token이 Redis에 저장된 최신 값과 다르면(이미
     * 회전되어 무효화된 토큰의 재사용 시도로 간주) 탈취 대응으로 해당 사용자의 세션을 즉시 강제 만료시키고 재로그인을 요구한다. 재발급 시점의 role은 Refresh
     * Token 발급 당시 값이 아니라 DB의 현재 값을 다시 조회한다 — 로그인 이후 PUT /api/auth/role로 Role이 확정됐을 수 있기 때문이다.
     */
    public TokenPair reissue(String refreshToken) {
        Claims claims = parseOrReject(refreshToken);
        if (!jwtTokenProvider.isRefreshToken(claims)) {
            throw new AuthenticationFailedException(ErrorCode.AUTH_004);
        }

        Long userId = jwtTokenProvider.getUserId(claims);
        String key = cacheKeyGenerator.refresh(String.valueOf(userId));
        Object stored = redisGet(key);

        if (!(stored instanceof String storedToken) || !storedToken.equals(refreshToken)) {
            log.warn("event=TOKEN_REFRESH_FAILED, userId={}, reason=REUSE_OR_MISSING", userId);
            redisDelete(key);
            throw new AuthenticationFailedException(ErrorCode.AUTH_004);
        }

        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new AuthenticationFailedException(ErrorCode.AUTH_004));

        log.info("event=TOKEN_REFRESH_SUCCESS, userId={}", userId);
        return issueTokens(userId, user.getRole());
    }

    /** Access Token을 Blacklist에 등록하고 Refresh Token을 삭제한다(Security_Guide.md §5.6). */
    public void logout(Long userId, String accessToken) {
        try {
            Claims claims = jwtTokenProvider.parseClaims(accessToken);
            blacklist(claims);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("event=LOGOUT_TOKEN_ALREADY_INVALID, userId={}", userId);
        }
        redisDelete(cacheKeyGenerator.refresh(String.valueOf(userId)));
        log.info("event=LOGOUT, userId={}", userId);
    }

    private Claims parseOrReject(String token) {
        try {
            return jwtTokenProvider.parseClaims(token);
        } catch (JwtException | IllegalArgumentException e) {
            throw new AuthenticationFailedException(ErrorCode.AUTH_004);
        }
    }

    private void blacklist(Claims claims) {
        long remainingMillis = jwtTokenProvider.getRemainingMillis(claims);
        if (remainingMillis <= 0) {
            return;
        }
        String key = cacheKeyGenerator.blacklist(jwtTokenProvider.getJti(claims));
        redisSet(key, Boolean.TRUE, Duration.ofMillis(remainingMillis));
    }

    private void storeRefreshToken(Long userId, JwtTokenProvider.TokenInfo refreshTokenInfo) {
        String key = cacheKeyGenerator.refresh(String.valueOf(userId));
        Duration ttl = Duration.between(Instant.now(), refreshTokenInfo.expiresAt());
        redisSet(key, refreshTokenInfo.token(), ttl);
    }

    private Object redisGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (DataAccessException e) {
            log.error("event=REDIS_UNAVAILABLE, operation=GET", e);
            throw new DataAccessCustomException(ErrorCode.COMMON_007);
        }
    }

    private void redisSet(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (DataAccessException e) {
            log.error("event=REDIS_UNAVAILABLE, operation=SET", e);
            throw new DataAccessCustomException(ErrorCode.COMMON_007);
        }
    }

    private void redisDelete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException e) {
            log.error("event=REDIS_UNAVAILABLE, operation=DELETE", e);
            throw new DataAccessCustomException(ErrorCode.COMMON_007);
        }
    }

    public record TokenPair(String accessToken, String refreshToken) {}
}
