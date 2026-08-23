package com.tracecare.backend.domain.place;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.service.GuardianTargetService;
import com.tracecare.backend.domain.place.dto.request.PlaceCreateRequest;
import com.tracecare.backend.domain.place.dto.request.PlaceUpdateRequest;
import com.tracecare.backend.domain.place.entity.Place;
import com.tracecare.backend.domain.place.repository.PlaceRepository;
import com.tracecare.backend.domain.place.service.PlaceService;

/**
 * Place.version(낙관적 락)이 실제 동시 수정 상황에서 정확히 동작하는지 검증한다 — 같은 Place를 두 스레드가 동시에 PUT하면 하나만 성공하고, 늦게
 * flush한 쪽은 {@code COMMON_008}(409)로 거부되어야 한다(PlaceService.flushOrTranslateConflict 참고).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class PlaceConcurrencyIntegrationTest {

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer(
                            DockerImageName.parse("pgvector/pgvector:pg18")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("tracecare")
                    .withUsername("tracecare")
                    .withPassword("test");

    @BeforeAll
    static void initSchema() throws Exception {
        String ddl = Files.readString(Paths.get("../../docs/db/tracecare_schema_ddl_1.0.sql"));
        try (Connection connection =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
        registry.add(
                "jwt.secret",
                () -> "integration-test-secret-key-do-not-use-in-production-1234567890");
        registry.add("jwt.access-token-expiration", () -> "1800000");
        registry.add("jwt.refresh-token-expiration", () -> "1209600000");
        registry.add("google.client-id", () -> "integration-test-client-id");
    }

    @Autowired private UserRepository userRepository;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private GuardianTargetService guardianTargetService;
    @Autowired private PlaceService placeService;

    @Test
    @DisplayName("같은 Place를 두 요청이 동시에 수정하면 하나만 성공하고 나머지는 COMMON_008로 거부된다")
    void updatePlace_concurrentUpdates_onlyOneSucceeds() throws Exception {
        // given
        User target = createConfirmedUser("place-concurrency-target-oauth-id", "CARE_TARGET");
        User primaryGuardian =
                createConfirmedUser("place-concurrency-primary-oauth-id", "GUARDIAN");
        guardianTargetService.createRelation(primaryGuardian.getId(), target.getId());

        PlaceCreateRequest createRequest =
                PlaceCreateRequest.builder()
                        .careTargetId(target.getPublicId().toString())
                        .name("우리집")
                        .address("서울시 강남구")
                        .latitude(37.5)
                        .longitude(127.0)
                        .radius(100)
                        .build();
        var created = placeService.createPlace(primaryGuardian.getId(), createRequest);
        java.util.UUID placePublicId = java.util.UUID.fromString(created.getPlaceId());

        PlaceUpdateRequest updateA =
                PlaceUpdateRequest.builder()
                        .name("학교(A)")
                        .address("A 주소")
                        .latitude(37.6)
                        .longitude(127.1)
                        .radius(150)
                        .build();
        PlaceUpdateRequest updateB =
                PlaceUpdateRequest.builder()
                        .name("학원(B)")
                        .address("B 주소")
                        .latitude(37.7)
                        .longitude(127.2)
                        .radius(200)
                        .build();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        // when
        Future<ErrorCode> resultA =
                executor.submit(
                        () ->
                                attemptUpdate(
                                        primaryGuardian.getId(),
                                        placePublicId,
                                        updateA,
                                        readyLatch,
                                        startLatch));
        Future<ErrorCode> resultB =
                executor.submit(
                        () ->
                                attemptUpdate(
                                        primaryGuardian.getId(),
                                        placePublicId,
                                        updateB,
                                        readyLatch,
                                        startLatch));
        readyLatch.await();
        startLatch.countDown();

        ErrorCode codeA = resultA.get(10, TimeUnit.SECONDS);
        ErrorCode codeB = resultB.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // then — 둘 중 정확히 하나만 성공(null)하고, 나머지는 COMMON_008로 거부된다
        boolean succeededA = codeA == null;
        boolean succeededB = codeB == null;
        assertThat(succeededA ^ succeededB).isTrue();
        ErrorCode failureCode = succeededA ? codeB : codeA;
        assertThat(failureCode).isEqualTo(ErrorCode.COMMON_008);

        Place saved = placeRepository.findByPublicId(placePublicId).orElseThrow();
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getName()).isIn("학교(A)", "학원(B)");
    }

    private ErrorCode attemptUpdate(
            Long callerId,
            java.util.UUID placePublicId,
            PlaceUpdateRequest request,
            CountDownLatch readyLatch,
            CountDownLatch startLatch)
            throws InterruptedException {
        readyLatch.countDown();
        startLatch.await();
        try {
            placeService.updatePlace(callerId, placePublicId, request);
            return null;
        } catch (BusinessException e) {
            return e.getErrorCode();
        }
    }

    private User createConfirmedUser(String oauthId, String role) {
        User user = User.createFromOAuth(oauthId + "@example.com", "GOOGLE", oauthId);
        user.confirmRole(role, "Place Concurrency Test User", LocalDate.of(1990, 1, 1));
        return userRepository.save(user);
    }
}
