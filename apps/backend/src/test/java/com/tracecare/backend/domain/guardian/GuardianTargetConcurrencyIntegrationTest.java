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

    private User createConfirmedUser(String oauthId, String role) {
        User user = User.createFromOAuth(oauthId + "@example.com", "GOOGLE", oauthId);
        user.confirmRole(role, "Concurrency Test User", LocalDate.of(1990, 1, 1));
        return userRepository.save(user);
    }
}
