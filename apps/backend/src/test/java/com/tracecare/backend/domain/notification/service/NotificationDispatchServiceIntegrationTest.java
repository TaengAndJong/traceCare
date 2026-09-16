package com.tracecare.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.notification.entity.NotificationHistory;
import com.tracecare.backend.domain.notification.fcm.FcmSender;
import com.tracecare.backend.domain.notification.repository.NotificationHistoryRepository;
import com.tracecare.backend.domain.place.repository.PlaceRepository;

/**
 * dispatchArrival() 트랜잭션 버그 수정의 핵심 확인 지점 — Mockito 단위 테스트({@code
 * NotificationDispatchServiceTest})는 실제 커밋/롤백을 증명할 수 없으므로(Mock은 트랜잭션에 참여하지 않는다), 실제
 * PostgreSQL 트랜잭션이 걸리는 통합 테스트로만 검증 가능하다.
 *
 * <p>수정 전(메서드 전체가 하나의 {@code @Transactional})에는 이 테스트가 실패했을 것이다 — 두 번째 Guardian 처리 중
 * 예외가 나면 전체 트랜잭션이 롤백되어 첫 번째 Guardian의 {@code NotificationHistory}까지 함께 사라진다. 수정 후에는
 * Guardian별 저장이 각각 독립된 트랜잭션으로 커밋되므로 첫 번째 Guardian의 이력만 남는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class NotificationDispatchServiceIntegrationTest {

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
        String ddl =
                Files.readString(
                        Paths.get("../../docs/db/tracecare_schema_ddl_2026-09-16_1.2.sql"));
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
    @Autowired private NotificationHistoryRepository notificationHistoryRepository;
    @Autowired private PlaceRepository placeRepository;

    @Test
    @DisplayName(
            "두 Guardian 중 한 명 처리 중 예외가 나도, 그 전에 이미 저장된 다른 Guardian의 NotificationHistory는"
                    + " 롤백되지 않는다")
    void dispatchArrival_exceptionDuringSecondGuardian_firstGuardianNotificationSurvives() {
        // given
        User target = createConfirmedUser("dispatch-target-oauth-id", "CARE_TARGET");
        User guardianA = createConfirmedUser("dispatch-guardian-a-oauth-id", "GUARDIAN");
        User guardianB = createConfirmedUser("dispatch-guardian-b-oauth-id", "GUARDIAN");
        guardianTargetRepository.save(
                GuardianTarget.createActive(
                        guardianA.getId(), target.getId(), GuardianTarget.ROLE_PRIMARY));
        guardianTargetRepository.save(
                GuardianTarget.createActive(
                        guardianB.getId(), target.getId(), GuardianTarget.ROLE_SUB));

        // 두 번째 호출부터는 FCM SDK가 비정상적으로 예외를 던지는 상황을 가정한다(FcmSender 계약상 보통은
        // boolean만 반환해야 하지만, 방어적으로 "그래도 첫 번째 저장은 롤백되지 않아야 한다"를 증명한다).
        AtomicInteger callCount = new AtomicInteger();
        FcmSender throwingOnSecondCall =
                (guardianId, title, body) -> {
                    if (callCount.incrementAndGet() == 2) {
                        throw new RuntimeException("FCM SDK 비정상 오류(테스트)");
                    }
                    return true;
                };
        NotificationDispatchService service =
                new NotificationDispatchService(
                        guardianTargetRepository,
                        userRepository,
                        notificationHistoryRepository,
                        placeRepository,
                        throwingOnSecondCall);

        // when
        assertThatThrownBy(() -> service.dispatchArrival(target.getId(), "우리집"))
                .isInstanceOf(RuntimeException.class);

        // then — 두 번째 Guardian 처리가 실패해도 첫 번째 Guardian의 이력은 이미 커밋되어 남아 있다
        List<NotificationHistory> saved = notificationHistoryRepository.findAll();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getStatus()).isEqualTo(NotificationHistory.STATUS_SENT);
    }

    private User createConfirmedUser(String oauthId, String role) {
        User user = User.createFromOAuth(oauthId + "@example.com", "GOOGLE", oauthId);
        user.confirmRole(role, "Dispatch Test User", LocalDate.of(1990, 1, 1));
        return userRepository.save(user);
    }
}
