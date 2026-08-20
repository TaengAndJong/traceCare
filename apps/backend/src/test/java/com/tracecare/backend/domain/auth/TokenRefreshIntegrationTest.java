package com.tracecare.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Paths;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.tracecare.backend.common.cache.CacheKeyGenerator;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.auth.service.TokenService;

/**
 * Refresh Token Rotation(Security_Guide.md §5.5)의 "정상 재발급 성공"/"재사용 감지 시 전체 세션 강제 만료" 두 시나리오를 실제
 * HTTP 요청 + 실제 PostgreSQL/Redis(Testcontainers)로 검증한다.
 *
 * <p>PostgreSQL은 H2 등으로 대체하지 않고 실제 서비스와 동일한 {@code pgvector/pgvector:pg18} 이미지를 쓴다 — 이 프로젝트는 대소문자
 * Quoted 식별자({@code "User"})와 {@code gen_random_uuid()}/{@code vector} 확장을 실제로 쓰고 있어 H2 호환 모드로는 동일
 * 동작을 보장할 수 없다. Redis는 org.testcontainers에 전용 모듈이 없어 GenericContainer로 docker-compose.yml과 동일한
 * {@code redis:7.4-alpine}을 띄운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class TokenRefreshIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                            DockerImageName.parse("pgvector/pgvector:pg18")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("tracecare")
                    .withUsername("tracecare")
                    .withPassword("test")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(
                                    Paths.get("../../docs/db/tracecare_schema_ddl_1.0.sql")),
                            "/docker-entrypoint-initdb.d/schema.sql");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add(
                "jwt.secret",
                () -> "integration-test-secret-key-do-not-use-in-production-1234567890");
        registry.add("jwt.access-token-expiration", () -> "1800000");
        registry.add("jwt.refresh-token-expiration", () -> "1209600000");
        registry.add("google.client-id", () -> "integration-test-client-id");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenService tokenService;
    @Autowired private RedisTemplate<String, Object> redisTemplate;
    @Autowired private CacheKeyGenerator cacheKeyGenerator;

    @Test
    @DisplayName("유효한 Refresh Token으로 재발급하면 새 토큰 쌍이 발급되고 Redis 값이 교체된다")
    void refresh_withValidToken_rotatesTokenPair() throws Exception {
        // given
        User user = createConfirmedUser("refresh-success-oauth-id");
        TokenService.TokenPair initialTokens =
                tokenService.issueTokens(user.getId(), user.getRole());
        String redisKeyBeforeRefresh = readStoredRefreshToken(user.getId());
        assertThat(redisKeyBeforeRefresh).isEqualTo(initialTokens.refreshToken());

        // when & then
        mockMvc.perform(
                        post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"refreshToken\":\""
                                                + initialTokens.refreshToken()
                                                + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("AUTH_002"))
                .andExpect(jsonPath("$.data.accessToken").value(notNullValue()))
                .andExpect(jsonPath("$.data.refreshToken").value(notNullValue()));

        String redisKeyAfterRefresh = readStoredRefreshToken(user.getId());
        assertThat(redisKeyAfterRefresh).isNotEqualTo(initialTokens.refreshToken());
    }

    @Test
    @DisplayName(
            "이미 회전되어 무효화된 Refresh Token을 재사용하면 401/AUTH_004가 나가고, 그 이후 최신 토큰으로도 전체 세션이 강제 만료된다")
    void refresh_withReusedToken_rejectsAndInvalidatesEntireSession() throws Exception {
        // given
        User user = createConfirmedUser("refresh-reuse-oauth-id");
        TokenService.TokenPair firstTokens = tokenService.issueTokens(user.getId(), user.getRole());

        // 최초 Refresh Token으로 1회 정상 재발급 (Redis에는 이제 두 번째 토큰만 남음)
        String refreshResponse =
                mockMvc.perform(
                                post("/api/auth/refresh")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"refreshToken\":\""
                                                        + firstTokens.refreshToken()
                                                        + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String secondRefreshToken = extractRefreshToken(refreshResponse);

        // when & then: 이미 사용된(무효화된) 최초 Refresh Token 재사용 시도 → 거부 + Redis 키 완전 삭제
        mockMvc.perform(
                        post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"refreshToken\":\""
                                                + firstTokens.refreshToken()
                                                + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AUTH_004"));

        assertThat(readStoredRefreshToken(user.getId())).isNull();

        // then: 재사용 감지로 세션이 통째로 강제 만료됐으므로, 아직 안 쓴 두 번째(최신) Refresh Token도 실패해야 한다
        mockMvc.perform(
                        post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"" + secondRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));
    }

    private User createConfirmedUser(String oauthId) {
        User user = User.createFromOAuth(oauthId + "@example.com", "GOOGLE", oauthId);
        user.confirmRole("GUARDIAN", "Integration Test User", LocalDate.of(1990, 1, 1));
        return userRepository.save(user);
    }

    private String readStoredRefreshToken(Long userId) {
        Object stored =
                redisTemplate.opsForValue().get(cacheKeyGenerator.refresh(String.valueOf(userId)));
        return stored instanceof String storedToken ? storedToken : null;
    }

    private String extractRefreshToken(String jsonResponse) {
        String marker = "\"refreshToken\":\"";
        int start = jsonResponse.indexOf(marker) + marker.length();
        int end = jsonResponse.indexOf('"', start);
        return jsonResponse.substring(start, end);
    }
}
