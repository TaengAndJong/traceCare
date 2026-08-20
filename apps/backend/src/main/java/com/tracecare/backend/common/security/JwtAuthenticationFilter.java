package com.tracecare.backend.common.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.tracecare.backend.common.cache.CacheKeyGenerator;
import com.tracecare.backend.common.exception.ErrorCode;

/**
 * Security_Guide.md §2.3 그대로: 검증 실패/토큰 없음이어도 여기서 직접 401을 응답하지 않고, 인증되지 않은 상태로 다음 필터로 넘긴다. 실제 차단은
 * AuthorizationFilter + AuthenticationEntryPoint가 담당한다.
 *
 * <p>[판단 근거] 실패 사유는 {@link JwtAuthenticationException}(ErrorCode를 갖는 AuthenticationException)에 담아
 * request attribute로 전달하고, JwtAuthenticationEntryPoint가 이를 읽어 ErrorCode를 구분한다.
 *
 * <p><b>이 예외를 여기서 throw하지 않는 이유</b>: 이 필터는 이 SecurityConfig에서 {@code
 * addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)}로 등록되어
 * Spring Security 기본 체인의 ExceptionTranslationFilter보다 앞에 위치한다. ExceptionTranslationFilter는 자신보다 뒤에
 * 실행되는 필터(AuthorizationFilter 등)가 던진 예외만 try-catch로 잡을 수 있고, 자신보다 앞서 실행된 필터(이 필터)가 던진 예외는 잡지 못한다 —
 * 여기서 throw하면 AuthenticationEntryPoint로 가지 않고 처리되지 않은 예외로 전파(500)된다. 또한 §2.3이 명시한 "인증이 필요 없는 공개
 * 엔드포인트(permitAll)까지 이 Filter를 거치므로 여기서 즉시 차단하지 않는다"는 원칙도 즉시 throw하면 깨진다(permitAll 대상 요청도 토큰이 잘못됐다는
 * 이유만으로 401이 나가버림). 그래서 예외 객체를 만들어 request attribute에 담아두기만 하고, 실제 인증 요구 여부 판단은 항상
 * AuthorizationFilter + AuthenticationEntryPoint에 맡긴다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String JWT_ERROR_ATTRIBUTE = "jwtAuthenticationException";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheKeyGenerator cacheKeyGenerator;

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            RedisTemplate<String, Object> redisTemplate,
            CacheKeyGenerator cacheKeyGenerator) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisTemplate = redisTemplate;
        this.cacheKeyGenerator = cacheKeyGenerator;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);

        if (StringUtils.hasText(token)) {
            try {
                Claims claims = jwtTokenProvider.parseClaims(token);
                if (!jwtTokenProvider.isAccessToken(claims)) {
                    setAuthError(request, ErrorCode.AUTH_003);
                } else if (isBlacklisted(claims)) {
                    log.warn("event=TOKEN_BLACKLISTED, jti={}", jwtTokenProvider.getJti(claims));
                    setAuthError(request, ErrorCode.AUTH_006);
                } else {
                    SecurityContextHolder.getContext()
                            .setAuthentication(jwtTokenProvider.getAuthentication(claims));
                }
            } catch (ExpiredJwtException e) {
                log.info("event=JWT_EXPIRED");
                setAuthError(request, ErrorCode.AUTH_002);
            } catch (JwtException | IllegalArgumentException e) {
                log.warn("event=JWT_VERIFY_FAILED, reason={}", e.getClass().getSimpleName());
                setAuthError(request, ErrorCode.AUTH_003);
            }
        }

        filterChain.doFilter(request, response);
    }

    private void setAuthError(HttpServletRequest request, ErrorCode errorCode) {
        request.setAttribute(JWT_ERROR_ATTRIBUTE, new JwtAuthenticationException(errorCode));
    }

    private boolean isBlacklisted(Claims claims) {
        String key = cacheKeyGenerator.blacklist(jwtTokenProvider.getJti(claims));
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
