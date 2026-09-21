package com.tracecare.backend.domain.guardian;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
import com.tracecare.backend.support.TestSchema;

/**
 * §4.4 2단계 — GuardianTarget 알림 설정 불변식이 <b>실제 PostgreSQL 제약(ck_gt_prev_notification_mode, notification_mode NOT
 * NULL)</b> 앞에서도 지켜지는지 검증한다. 단위 테스트는 엔티티 필드만 보므로, "DB에서 500(DataIntegrityViolation)이 난다"는 원래 버그의
 * 증상 자체를 재현하려면 실제 DB가 필요하다. JPA 슬라이스만 띄워(스케줄러/Redis 없이) 가볍게 실행한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class GuardianTargetNotificationInvariantIntegrationTest {

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
        String ddl = TestSchema.readLatestDdl();
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
    }

    @Autowired private UserRepository userRepository;
    @Autowired private GuardianTargetRepository guardianTargetRepository;
    @Autowired private EntityManager entityManager;

    private GuardianTarget newRelation(String suffix) {
        User guardianUser =
                userRepository.save(
                        User.createFromOAuth("g-" + suffix + "@example.com", "GOOGLE", "g-" + suffix));
        User targetUser =
                userRepository.save(
                        User.createFromOAuth("t-" + suffix + "@example.com", "GOOGLE", "t-" + suffix));
        return guardianTargetRepository.save(
                GuardianTarget.createActive(
                        guardianUser.getId(), targetUser.getId(), GuardianTarget.ROLE_SUB));
    }

    @Test
    @DisplayName("버그 A 재현 — 정지 중 pause를 다시 호출해도 DB CHECK(ck_gt_prev_notification_mode) 위반 없이 저장된다")
    void pause_alreadyPaused_persistsWithoutCheckViolation() {
        // given
        GuardianTarget relation = newRelation("bug-a");
        Instant now = Instant.now();
        relation.pause(now, 10);
        guardianTargetRepository.saveAndFlush(relation);

        // when — 정지 중에 다시 pause (수정 전에는 previous_notification_mode='PAUSED'가 되어 flush에서 CHECK 위반)
        relation.pause(now, 20);
        guardianTargetRepository.saveAndFlush(relation);
        entityManager.clear();

        // then
        GuardianTarget reloaded = guardianTargetRepository.findById(relation.getId()).orElseThrow();
        assertThat(reloaded.getNotificationMode()).isEqualTo(GuardianTarget.NOTIFICATION_MODE_PAUSED);
        assertThat(reloaded.getPreviousNotificationMode())
                .isEqualTo(GuardianTarget.NOTIFICATION_MODE_HYBRID);
    }

    @Test
    @DisplayName("버그 B 재현 — previous가 null인 PAUSED 행을 복귀시켜도 NOT NULL 위반 없이 HYBRID로 저장된다")
    void resumeFromPause_previousModeNull_persistsHybridWithoutNotNullViolation() {
        // given — 복귀할 모드가 기록되지 않은 비정상 데이터(레거시/수동 수정 가정)를 DB에 직접 만든다
        GuardianTarget relation = newRelation("bug-b");
        entityManager.flush();
        entityManager
                .createNativeQuery(
                        "UPDATE \"GuardianTarget\" SET notification_mode = 'PAUSED',"
                                + " previous_notification_mode = NULL, paused_until = NULL WHERE id = :id")
                .setParameter("id", relation.getId())
                .executeUpdate();
        entityManager.clear();

        // when — 수정 전에는 notification_mode=null이 되어 flush에서 NOT NULL 위반
        GuardianTarget loaded = guardianTargetRepository.findById(relation.getId()).orElseThrow();
        loaded.resumeFromPause();
        guardianTargetRepository.saveAndFlush(loaded);
        entityManager.clear();

        // then
        GuardianTarget reloaded = guardianTargetRepository.findById(relation.getId()).orElseThrow();
        assertThat(reloaded.getNotificationMode()).isEqualTo(GuardianTarget.NOTIFICATION_MODE_HYBRID);
        assertThat(reloaded.getPreviousNotificationMode()).isNull();
        assertThat(reloaded.getPausedUntil()).isNull();
    }
}
