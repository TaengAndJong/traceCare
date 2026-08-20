package com.tracecare.backend.domain.auth.service;

import java.util.UUID;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.tracecare.backend.common.cache.CacheKeyGenerator;
import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.business.DuplicateResourceException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;

/**
 * Google OAuth 로그인 처리 순서(Security_Guide.md §6.4): ID Token 검증 → 이메일 검증(§6.6, Verifier가 수행) → 기존 회원
 * 매핑(§6.7) 또는 신규 가입 → JWT 발급 및 Refresh Token 저장 → 응답 반환.
 */
@Service
public class OAuthLoginService {

    private static final String OAUTH_PROVIDER_GOOGLE = "GOOGLE";

    private static final Logger log = LoggerFactory.getLogger(OAuthLoginService.class);

    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheKeyGenerator cacheKeyGenerator;

    public OAuthLoginService(
            GoogleIdTokenVerifier googleIdTokenVerifier,
            UserRepository userRepository,
            TokenService tokenService,
            RedisTemplate<String, Object> redisTemplate,
            CacheKeyGenerator cacheKeyGenerator) {
        this.googleIdTokenVerifier = googleIdTokenVerifier;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.redisTemplate = redisTemplate;
        this.cacheKeyGenerator = cacheKeyGenerator;
    }

    @Transactional
    public LoginResult login(String idToken, String fcmToken) {
        GoogleIdToken.Payload payload = googleIdTokenVerifier.verify(idToken);
        String oauthId = payload.getSubject();
        String email = payload.getEmail();

        User user = resolveUser(oauthId, email);

        TokenService.TokenPair tokens = tokenService.issueTokens(user.getId(), user.getRole());

        if (StringUtils.hasText(fcmToken)) {
            redisTemplate
                    .opsForValue()
                    .set(cacheKeyGenerator.fcmToken(String.valueOf(user.getId())), fcmToken);
        }

        log.info(
                "event=LOGIN_SUCCESS, userId={}, provider={}", user.getId(), OAUTH_PROVIDER_GOOGLE);
        return new LoginResult(
                tokens.accessToken(),
                tokens.refreshToken(),
                user.getRole(),
                user.getPublicId(),
                user.isRoleSelected());
    }

    /**
     * Security_Guide.md §6.7 매핑 정책. oauth_id가 새로운데 이메일이 기존 회원과 같은 경우는 자동 병합하지 않고, DB의
     * uq_user_email_active 제약상 "신규 계정으로 처리"도 불가능하므로 USER_002(이미 가입된 사용자)로 로그인을 거부한다(사용자 확인 완료 사항).
     */
    private User resolveUser(String oauthId, String email) {
        return userRepository
                .findByOauthProviderAndOauthId(OAUTH_PROVIDER_GOOGLE, oauthId)
                .orElseGet(
                        () -> {
                            if (userRepository.findByEmail(email).isPresent()) {
                                log.warn(
                                        "event=LOGIN_FAILURE, reason=EMAIL_ALREADY_REGISTERED_DIFFERENT_OAUTH_ID");
                                throw new DuplicateResourceException(ErrorCode.USER_002);
                            }
                            return userRepository.save(
                                    User.createFromOAuth(email, OAUTH_PROVIDER_GOOGLE, oauthId));
                        });
    }

    public record LoginResult(
            String accessToken,
            String refreshToken,
            String role,
            UUID userId,
            boolean roleSelected) {}
}
