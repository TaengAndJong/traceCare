package com.tracecare.backend.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * JWT 발급/파싱 전담(Security_Guide.md §3.1, §5). sub 클레임은 User 테이블의 내부 PK(Long)이다. 검증 자체(만료/서명/블랙리스트
 * 판단)는 JwtAuthenticationFilter가 이 클래스가 던지는 파싱 결과와 예외를 바탕으로 수행한다 — Provider는 파싱/생성만
 * 책임진다(SecurityConfig가 JWT 파싱까지 갖지 않게 하는 것과 동일한 단일 책임 원칙).
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "typ";
    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String TOKEN_TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration}") long accessTokenExpiration,
            @Value("${jwt.refresh-token-expiration}") long refreshTokenExpiration) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    public String generateAccessToken(Long userId, String role) {
        return generateToken(userId, role, TOKEN_TYPE_ACCESS, accessTokenExpiration).token();
    }

    /**
     * Refresh Token 발급 시 만료 시각을 함께 반환한다. Redis TTL 계산 등 만료 시각이 필요한 호출부(TokenService)가 별도 설정값을 다시 읽지
     * 않고 이 발급 결과를 그대로 쓰게 하기 위함이다 — Blacklist TTL을 토큰을 파싱해 남은 만료 시간을 구하는 방식(getRemainingMillis)과 같은
     * 원칙: "만료 시각 계산은 이 클래스 하나에서만 한다."
     */
    public TokenInfo generateRefreshToken(Long userId, String role) {
        return generateToken(userId, role, TOKEN_TYPE_REFRESH, refreshTokenExpiration);
    }

    private TokenInfo generateToken(Long userId, String role, String type, long expirationMillis) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMillis);
        String token =
                Jwts.builder()
                        .subject(String.valueOf(userId))
                        .claim(CLAIM_ROLE, role)
                        .claim(CLAIM_TYPE, type)
                        .id(UUID.randomUUID().toString())
                        .issuedAt(now)
                        .expiration(expiry)
                        .signWith(key)
                        .compact();
        return new TokenInfo(token, expiry.toInstant());
    }

    /** 서명/만료 검증을 포함해 Claims를 파싱한다. 실패 시 io.jsonwebtoken의 JwtException 계열을 그대로 던진다. */
    public Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public boolean isAccessToken(Claims claims) {
        return TOKEN_TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public boolean isRefreshToken(Claims claims) {
        return TOKEN_TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public Long getUserId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    public String getRole(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }

    public String getJti(Claims claims) {
        return claims.getId();
    }

    public long getRemainingMillis(Claims claims) {
        return Math.max(claims.getExpiration().getTime() - System.currentTimeMillis(), 0);
    }

    public Authentication getAuthentication(Claims claims) {
        CustomUserDetails userDetails = new CustomUserDetails(getUserId(claims), getRole(claims));
        return new UsernamePasswordAuthenticationToken(
                userDetails, null, List.copyOf(userDetails.getAuthorities()));
    }

    public record TokenInfo(String token, Instant expiresAt) {}
}
