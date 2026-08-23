package com.tracecare.backend.common.websocket;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.tracecare.backend.common.cache.CacheKeyGenerator;
import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.auth.AuthenticationFailedException;
import com.tracecare.backend.common.security.JwtTokenProvider;

/**
 * Security_Guide.md §7.5.1 그대로 구현 — CONNECT 프레임에서만 JWT를 검증하고, 성공하면 {@code
 * accessor.setUser(authentication)}로 STOMP 세션에 Principal을 심는다. 이 Principal의 {@code getName()}(내부
 * userId 문자열, {@link com.tracecare.backend.common.security.CustomUserDetails#getUsername()} 참고)이
 * {@code convertAndSendToUser(userId, ...)}가 매칭하는 대상이 되므로, REST와 동일하게 항상 내부 PK를 기준으로 삼는다(§7.5.2가
 * 강조하는 "SUBSCRIBE 시점 소유권 재검증"은 개인화 큐 설계상 애초에 필요 없다 — 클라이언트가 다른 사용자의 큐를 지정할 방법이 없기 때문).
 *
 * <p>REST의 {@code JwtAuthenticationFilter}와 달리 여기서는 검증 실패를 즉시 throw한다 — REST는 permitAll 엔드포인트까지 이
 * 필터를 거치므로 즉시 차단하지 않고 AuthenticationEntryPoint로 미루지만, WebSocket CONNECT는 애초에 인증이 필요 없는 경로가
 * 없으므로(§7.5 문서 예시와 동일) 여기서 바로 거부해도 된다.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheKeyGenerator cacheKeyGenerator;

    public StompAuthChannelInterceptor(
            JwtTokenProvider jwtTokenProvider,
            RedisTemplate<String, Object> redisTemplate,
            CacheKeyGenerator cacheKeyGenerator) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisTemplate = redisTemplate;
        this.cacheKeyGenerator = cacheKeyGenerator;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticate(accessor);
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String token = resolveToken(accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION));
        if (!StringUtils.hasText(token)) {
            log.warn("event=WS_CONNECT_REJECTED, reason=NO_TOKEN");
            throw new AuthenticationFailedException(ErrorCode.AUTH_001);
        }

        Claims claims;
        try {
            claims = jwtTokenProvider.parseClaims(token);
        } catch (ExpiredJwtException e) {
            log.info("event=WS_CONNECT_REJECTED, reason=TOKEN_EXPIRED");
            throw new AuthenticationFailedException(ErrorCode.AUTH_002);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("event=WS_CONNECT_REJECTED, reason=TOKEN_INVALID");
            throw new AuthenticationFailedException(ErrorCode.AUTH_003);
        }

        if (!jwtTokenProvider.isAccessToken(claims)) {
            log.warn("event=WS_CONNECT_REJECTED, reason=NOT_ACCESS_TOKEN");
            throw new AuthenticationFailedException(ErrorCode.AUTH_003);
        }
        if (isBlacklisted(claims)) {
            log.warn(
                    "event=WS_CONNECT_REJECTED, reason=BLACKLISTED, jti={}",
                    jwtTokenProvider.getJti(claims));
            throw new AuthenticationFailedException(ErrorCode.AUTH_006);
        }

        accessor.setUser(jwtTokenProvider.getAuthentication(claims));
        log.info("event=WS_CONNECT_AUTHENTICATED, userId={}", jwtTokenProvider.getUserId(claims));
    }

    private boolean isBlacklisted(Claims claims) {
        String key = cacheKeyGenerator.blacklist(jwtTokenProvider.getJti(claims));
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    private String resolveToken(String header) {
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return header;
    }
}
