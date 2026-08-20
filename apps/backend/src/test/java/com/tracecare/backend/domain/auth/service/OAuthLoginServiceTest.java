package com.tracecare.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import com.tracecare.backend.common.cache.CacheKeyGenerator;
import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.business.DuplicateResourceException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;

/**
 * OAuthLoginService의 회원 매핑 분기(Security_Guide.md §6.7)를 검증한다. Google ID Token 검증 자체는 실제 Google 토큰이
 * 필요해 여기서는 GoogleIdTokenVerifier를 Mock으로 대체한다.
 */
@ExtendWith(MockitoExtension.class)
class OAuthLoginServiceTest {

    private static final String OAUTH_ID = "google-oauth-id-123";
    private static final String EMAIL = "user@example.com";

    @Mock private GoogleIdTokenVerifier googleIdTokenVerifier;
    @Mock private UserRepository userRepository;
    @Mock private TokenService tokenService;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private CacheKeyGenerator cacheKeyGenerator;

    @Test
    @DisplayName("완전 신규 사용자는 role/name/birthDate가 null인 상태로 User가 생성되고 로그인은 성공한다")
    void login_newUser_createsUserWithNullRoleAndSucceeds() {
        // given
        OAuthLoginService service =
                new OAuthLoginService(
                        googleIdTokenVerifier,
                        userRepository,
                        tokenService,
                        redisTemplate,
                        cacheKeyGenerator);

        GoogleIdToken.Payload payload =
                new GoogleIdToken.Payload().setSubject(OAUTH_ID).setEmail(EMAIL);
        when(googleIdTokenVerifier.verify("id-token")).thenReturn(payload);
        when(userRepository.findByOauthProviderAndOauthId("GOOGLE", OAUTH_ID))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(tokenService.issueTokens(any(), any()))
                .thenReturn(new TokenService.TokenPair("access-token", "refresh-token"));

        // when
        OAuthLoginService.LoginResult result = service.login("id-token", null);

        // then
        assertThat(result.role()).isNull();
        assertThat(result.roleSelected()).isFalse();
        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");

        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("oauth_provider+oauth_id가 일치하는 기존 회원은 재가입 없이 로그인만 처리된다")
    void login_existingUser_reusesUserWithoutSaving() {
        // given
        OAuthLoginService service =
                new OAuthLoginService(
                        googleIdTokenVerifier,
                        userRepository,
                        tokenService,
                        redisTemplate,
                        cacheKeyGenerator);

        User existingUser = User.createFromOAuth(EMAIL, "GOOGLE", OAUTH_ID);
        existingUser.confirmRole("GUARDIAN", "Hong Gildong", LocalDate.of(1990, 1, 1));

        GoogleIdToken.Payload payload =
                new GoogleIdToken.Payload().setSubject(OAUTH_ID).setEmail(EMAIL);
        when(googleIdTokenVerifier.verify("id-token")).thenReturn(payload);
        when(userRepository.findByOauthProviderAndOauthId("GOOGLE", OAUTH_ID))
                .thenReturn(Optional.of(existingUser));
        when(tokenService.issueTokens(any(), any()))
                .thenReturn(new TokenService.TokenPair("access-token", "refresh-token"));

        // when
        OAuthLoginService.LoginResult result = service.login("id-token", null);

        // then
        assertThat(result.role()).isEqualTo("GUARDIAN");
        assertThat(result.roleSelected()).isTrue();
        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("oauth_id는 새로우나 이메일이 기존 회원과 같으면 USER_002로 로그인을 거부한다")
    void login_emailAlreadyRegisteredWithDifferentOauthId_throwsDuplicateResourceException() {
        // given
        OAuthLoginService service =
                new OAuthLoginService(
                        googleIdTokenVerifier,
                        userRepository,
                        tokenService,
                        redisTemplate,
                        cacheKeyGenerator);

        User existingUser = User.createFromOAuth(EMAIL, "GOOGLE", "different-oauth-id");

        GoogleIdToken.Payload payload =
                new GoogleIdToken.Payload().setSubject(OAUTH_ID).setEmail(EMAIL);
        when(googleIdTokenVerifier.verify("id-token")).thenReturn(payload);
        when(userRepository.findByOauthProviderAndOauthId("GOOGLE", OAUTH_ID))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existingUser));

        // when & then
        assertThatThrownBy(() -> service.login("id-token", null))
                .isInstanceOf(DuplicateResourceException.class)
                .satisfies(
                        e ->
                                assertThat(((DuplicateResourceException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.USER_002));

        verify(userRepository, never()).save(any(User.class));
    }
}
