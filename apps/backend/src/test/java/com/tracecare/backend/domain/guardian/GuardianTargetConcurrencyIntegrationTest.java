package com.tracecare.backend.domain.guardian;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import com.tracecare.backend.common.exception.business.GuardianCapacityExceededException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.guardian.service.GuardianTargetService;

/**
 * DATABASE_DESIGN_GUIDE.md §7 "GuardianTarget 신규 등록" 락(SELECT...FOR UPDATE)이 실제 동시 요청 상황에서도 정원(3명)을
 * 초과시키지 않는지 검증한다. 초대(Redis) 계층은 이 락과 무관하므로 우회하고 {@link GuardianTargetService#createRelation} 자체를 여러
 * 스레드에서 동시 호출한다.
 *
 * <p>PostgreSQL은 TokenRefreshIntegrationTest와 동일하게 실제 서비스와 같은 pgvector 이미지를 쓴다. 이 테스트는 Redis를 쓰지
 * 않으므로 Redis 컨테이너는 띄우지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class GuardianTargetConcurrencyIntegrationTest {

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
    @Autowired private GuardianTargetRepository guardianTargetRepository;
    @Autowired private GuardianTargetService guardianTargetService;

    @Test
    @DisplayName("6명이 동시에 승인을 시도해도 ACTIVE Guardian은 정확히 3명만 남고 나머지는 정원 초과로 거부된다")
    void createRelation_concurrentApprovals_neverExceedsCapacity() throws Exception {
        // given
        User target = createConfirmedUser("concurrency-target-oauth-id", "CARE_TARGET");
        int guardianCount = 6;
        List<Long> guardianIds = new ArrayList<>();
        for (int i = 0; i < guardianCount; i++) {
            guardianIds.add(
                    createConfirmedUser("concurrency-guardian-oauth-id-" + i, "GUARDIAN").getId());
        }

        ExecutorService executor = Executors.newFixedThreadPool(guardianCount);
        CountDownLatch readyLatch = new CountDownLatch(guardianCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        // when
        List<Future<Boolean>> results = new ArrayList<>();
        for (Long guardianId : guardianIds) {
            results.add(
                    executor.submit(
                            () -> {
                                readyLatch.countDown();
                                startLatch.await();
                                try {
                                    guardianTargetService.createRelation(
                                            guardianId, target.getId());
                                    return true;
                                } catch (GuardianCapacityExceededException e) {
                                    return false;
                                }
                            }));
        }
        readyLatch.await();
        startLatch.countDown();

        long succeeded = 0;
        long rejected = 0;
        for (Future<Boolean> result : results) {
            if (result.get(10, TimeUnit.SECONDS)) {
                succeeded++;
            } else {
                rejected++;
            }
        }
        executor.shutdown();

        // then
        assertThat(succeeded).isEqualTo(3);
        assertThat(rejected).isEqualTo(3);
        long activeCount =
                guardianTargetRepository.countByTargetIdAndStatus(
                        target.getId(), GuardianTarget.STATUS_ACTIVE);
        assertThat(activeCount).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "같은 PRIMARY가 서로 다른 SUB에게 동시에 위임을 시도해도 ACTIVE PRIMARY는 정확히 1명만 남는다"
                    + "(uq_gt_primary_per_target 제약이 깨지지 않는다)")
    void delegatePrimary_concurrentDelegations_neverViolatesPrimaryUniqueness() throws Exception {
        // given
        User target = createConfirmedUser("delegation-target-oauth-id", "CARE_TARGET");
        User primaryGuardian = createConfirmedUser("delegation-primary-oauth-id", "GUARDIAN");
        User subGuardianB = createConfirmedUser("delegation-sub-b-oauth-id", "GUARDIAN");
        User subGuardianC = createConfirmedUser("delegation-sub-c-oauth-id", "GUARDIAN");

        guardianTargetService.createRelation(primaryGuardian.getId(), target.getId());
        guardianTargetService.createRelation(subGuardianB.getId(), target.getId());
        guardianTargetService.createRelation(subGuardianC.getId(), target.getId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        // when
        Future<ErrorCode> delegateToB =
                executor.submit(
                        () ->
                                attemptDelegation(
                                        primaryGuardian.getId(),
                                        target.getPublicId(),
                                        subGuardianB.getPublicId(),
                                        readyLatch,
                                        startLatch));
        Future<ErrorCode> delegateToC =
                executor.submit(
                        () ->
                                attemptDelegation(
                                        primaryGuardian.getId(),
                                        target.getPublicId(),
                                        subGuardianC.getPublicId(),
                                        readyLatch,
                                        startLatch));
        readyLatch.await();
        startLatch.countDown();

        ErrorCode resultB = delegateToB.get(10, TimeUnit.SECONDS);
        ErrorCode resultC = delegateToC.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // then — 둘 중 정확히 하나만 성공(null)하고, 늦게 락을 잡은 쪽은 PostgreSQL REPEATABLE READ의 직렬화 실패를
        // 재시도 유도 코드(COMMON_008, 409)로 변환받아 실패한다(호출자가 이미 SUB로 내려가 있어 GUARDIAN_004로 거부되는 게
        // 아니라, 락 획득 자체가 직렬화 실패로 끝난다 — GuardianTargetService.lockActiveRelation 참고)
        boolean succeededB = resultB == null;
        boolean succeededC = resultC == null;
        assertThat(succeededB ^ succeededC).isTrue();
        ErrorCode failureCode = succeededB ? resultC : resultB;
        assertThat(failureCode).isEqualTo(ErrorCode.COMMON_008);

        long activePrimaryCount =
                guardianTargetRepository
                        .findByTargetIdAndStatusAndGuardianRoleOrderByCreatedAtAsc(
                                target.getId(),
                                GuardianTarget.STATUS_ACTIVE,
                                GuardianTarget.ROLE_PRIMARY)
                        .size();
        assertThat(activePrimaryCount).isEqualTo(1);
    }

    /** 성공하면 {@code null}, 실패하면 던져진 {@link BusinessException}의 {@link ErrorCode}를 반환한다. */
    private ErrorCode attemptDelegation(
            Long callerId,
            UUID targetPublicId,
            UUID newPrimaryGuardianPublicId,
            CountDownLatch readyLatch,
            CountDownLatch startLatch)
            throws InterruptedException {
        readyLatch.countDown();
        startLatch.await();
        try {
            guardianTargetService.delegatePrimary(
                    callerId, targetPublicId, newPrimaryGuardianPublicId);
            return null;
        } catch (BusinessException e) {
            return e.getErrorCode();
        }
    }

    private User createConfirmedUser(String oauthId, String role) {
        User user = User.createFromOAuth(oauthId + "@example.com", "GOOGLE", oauthId);
        user.confirmRole(role, "Concurrency Test User", LocalDate.of(1990, 1, 1));
        return userRepository.save(user);
    }
}
