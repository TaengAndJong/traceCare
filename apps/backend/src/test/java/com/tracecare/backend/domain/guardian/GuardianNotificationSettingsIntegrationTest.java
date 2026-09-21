package com.tracecare.backend.domain.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.dto.response.NotificationSettingsResponse;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.guardian.service.GuardianNotificationSettingsService;
import com.tracecare.backend.domain.guardian.service.GuardianTargetService;
import com.tracecare.backend.support.TestSchema;

/**
 * §4.4 — 이상행동 알림 설정을 <b>실제 PostgreSQL</b>에서 검증한다.
 *
 * <ul>
 *   <li>동시성: 사용자 쓰기와 스케줄러 자동 복귀({@code resumeIfDue})가 같은 행을 두고 경합해도 갱신이 유실되지 않고 순서대로 처리된다(행 단위 비관적
 *       락). 한쪽이 잠금을 쥔 채 멈춰 있는 동안 다른 쪽이 <b>실제로 대기</b>하는지, 대기가 끝난 뒤 <b>잠금 이후의 최신 상태</b>를 보는지 확인한다.
 *   <li>DB 제약: 어떤 순서로 호출해도 {@code ck_gt_prev_notification_mode}/NOT NULL을 위반하지 않고 정지 상태 불변식이 유지된다.
 *   <li>관계 해제 후 재연결 시 설정이 기본값으로 초기화된다.
 * </ul>
 *
 * 여러 스레드가 각자 트랜잭션을 열어야 하므로 테스트 전체 트랜잭션을 끄고({@code NOT_SUPPORTED}) 테스트마다 고유한 사용자를 만들어 서로 간섭하지
 * 않게 한다. JPA 슬라이스만 띄워 스케줄러/Redis 없이 실행한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({GuardianNotificationSettingsService.class, GuardianTargetService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class GuardianNotificationSettingsIntegrationTest {

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
    @Autowired private GuardianNotificationSettingsService settingsService;
    @Autowired private GuardianTargetService guardianTargetService;
    @Autowired private PlatformTransactionManager transactionManager;

    /** 테스트 한 건이 쓰는 (보호자, 대상자, 관계) 묶음. */
    private record Fixture(User guardian, User target, GuardianTarget relation) {
        Long guardianId() {
            return guardian.getId();
        }

        UUID targetPublicId() {
            return target.getPublicId();
        }

        Long relationId() {
            return relation.getId();
        }
    }

    private Fixture newFixture() {
        String suffix = UUID.randomUUID().toString();
        User guardian =
                userRepository.save(
                        User.createFromOAuth("g-" + suffix + "@example.com", "GOOGLE", "g-" + suffix));
        User target =
                userRepository.save(
                        User.createFromOAuth("t-" + suffix + "@example.com", "GOOGLE", "t-" + suffix));
        GuardianTarget relation = guardianTargetService.createRelation(guardian.getId(), target.getId());
        return new Fixture(guardian, target, relation);
    }

    private GuardianTarget reload(Long relationId) {
        return guardianTargetRepository.findById(relationId).orElseThrow();
    }

    private <T> T inTransaction(Callable<T> work) {
        return new TransactionTemplate(transactionManager)
                .execute(
                        status -> {
                            try {
                                return work.call();
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        });
    }

    /** 이미 paused_until이 지난(스케줄러가 복귀시킬 차례인) 정지 상태로 만든다. */
    private void makeDuePaused(Fixture fixture) {
        inTransaction(
                () -> {
                    reload(fixture.relationId()).pause(Instant.now().minus(Duration.ofHours(2)), 30);
                    return null;
                });
    }

    // ---------------------------------------------------------------------
    // 동시성 — 사용자 쓰기 vs 스케줄러 복귀
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("경합 — 사용자가 정지를 연장하는 중(행 잠금 보유)이면 스케줄러 복귀는 실제로 대기하고, 연장 커밋 후 최신 상태로 재확인해 복귀하지 않는다(연장 유지)")
    void resumeIfDue_whileUserHoldsLockAndExtends_waitsThenKeepsExtension() throws Exception {
        // given — 스케줄러가 복귀시킬 차례(paused_until 지남)인 정지 상태
        Fixture fixture = newFixture();
        makeDuePaused(fixture);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch userHoldsLock = new CountDownLatch(1);
        CountDownLatch userMayCommit = new CountDownLatch(1);

        try {
            // 사용자 요청: 잠금을 잡고 → (스케줄러가 대기하는 동안 붙잡고 있다가) → 지금부터 30분 더 연장 → 커밋
            Future<?> user =
                    executor.submit(
                            () ->
                                    inTransaction(
                                            () -> {
                                                GuardianTarget locked =
                                                        guardianTargetRepository
                                                                .findActiveByGuardianIdAndTargetIdForUpdate(
                                                                        fixture.guardianId(),
                                                                        fixture.target().getId())
                                                                .orElseThrow();
                                                userHoldsLock.countDown();
                                                userMayCommit.await(10, TimeUnit.SECONDS);
                                                locked.pause(Instant.now(), 30);
                                                return null;
                                            }));
            assertThat(userHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();

            // when — 스케줄러 자동 복귀 시도(잠금 없이 읽은 "복귀 대상" 목록에 있던 행이라고 가정)
            Future<Boolean> scheduler =
                    executor.submit(() -> settingsService.resumeIfDue(fixture.relationId(), Instant.now()));

            // then 1 — 사용자가 잠금을 쥐고 있는 동안 스케줄러는 끝나지 않고 대기한다
            assertThatThrownBy(() -> scheduler.get(700, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            // then 2 — 사용자가 연장을 커밋하면 스케줄러는 잠금 이후의 최신 상태(아직 안 지남)를 보고 복귀하지 않는다
            userMayCommit.countDown();
            user.get(10, TimeUnit.SECONDS);
            assertThat(scheduler.get(10, TimeUnit.SECONDS)).isFalse();
        } finally {
            executor.shutdownNow();
        }

        GuardianTarget result = reload(fixture.relationId());
        assertThat(result.isPaused()).isTrue();
        assertThat(result.getPreviousNotificationMode())
                .isEqualTo(GuardianTarget.NOTIFICATION_MODE_HYBRID);
        assertThat(result.getPausedUntil()).isAfter(Instant.now().plus(Duration.ofMinutes(25)));
    }

    @Test
    @DisplayName("경합 — 스케줄러 복귀가 잠금을 쥐고 있으면 사용자 pause는 실제로 대기하고, 복귀 커밋 후 최신 상태에서 새 정지를 시작한다(previous는 복귀된 기본 모드)")
    void pause_whileSchedulerHoldsLock_waitsThenStartsFreshPause() throws Exception {
        // given
        Fixture fixture = newFixture();
        makeDuePaused(fixture);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch schedulerHoldsLock = new CountDownLatch(1);
        CountDownLatch schedulerMayCommit = new CountDownLatch(1);

        try {
            // 스케줄러 경로: 행을 잠그고 → 붙잡고 있다가 → 복귀 → 커밋
            Future<?> scheduler =
                    executor.submit(
                            () ->
                                    inTransaction(
                                            () -> {
                                                GuardianTarget locked =
                                                        guardianTargetRepository
                                                                .findByIdForUpdate(fixture.relationId())
                                                                .orElseThrow();
                                                schedulerHoldsLock.countDown();
                                                schedulerMayCommit.await(10, TimeUnit.SECONDS);
                                                locked.resumeFromPause();
                                                return null;
                                            }));
            assertThat(schedulerHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();

            // when — 같은 행에 대한 사용자의 pause(실제 서비스 경로)
            Future<NotificationSettingsResponse> userPause =
                    executor.submit(() -> settingsService.pause(fixture.guardianId(), fixture.targetPublicId(), 30));

            // then 1 — 스케줄러가 잠금을 쥐고 있는 동안 사용자 요청은 대기한다
            assertThatThrownBy(() -> userPause.get(700, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            // then 2 — 복귀가 커밋된 뒤 실행되어 "이미 복귀된" 최신 상태 위에서 새 정지를 시작한다
            schedulerMayCommit.countDown();
            scheduler.get(10, TimeUnit.SECONDS);
            NotificationSettingsResponse response = userPause.get(10, TimeUnit.SECONDS);
            assertThat(response.isPaused()).isTrue();
        } finally {
            executor.shutdownNow();
        }

        GuardianTarget result = reload(fixture.relationId());
        assertThat(result.isPaused()).isTrue();
        // 복귀 전 옛 정지(과거)를 이어받지 않고 지금부터 30분, 돌아갈 모드는 복귀된 HYBRID(null/PAUSED가 아님)
        assertThat(result.getPreviousNotificationMode())
                .isEqualTo(GuardianTarget.NOTIFICATION_MODE_HYBRID);
        assertThat(result.getPausedUntil())
                .isBetween(
                        Instant.now().plus(Duration.ofMinutes(25)),
                        Instant.now().plus(Duration.ofMinutes(31)));
    }

    @Test
    @DisplayName("경합 — 스케줄러 복귀가 잠금을 쥐고 있으면 사용자 설정 변경(PUT)은 대기하고, 복귀 커밋 후 최신 상태(정지 아님)에 모드가 직접 반영된다(정지가 되살아나지 않음)")
    void updateSettings_whileSchedulerHoldsLock_waitsThenAppliesToResumedState() throws Exception {
        // given — 스케줄러가 복귀시킬 차례인 정지 상태(돌아갈 모드 HYBRID)
        Fixture fixture = newFixture();
        makeDuePaused(fixture);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch schedulerHoldsLock = new CountDownLatch(1);
        CountDownLatch schedulerMayCommit = new CountDownLatch(1);

        try {
            Future<?> scheduler =
                    executor.submit(
                            () ->
                                    inTransaction(
                                            () -> {
                                                GuardianTarget locked =
                                                        guardianTargetRepository
                                                                .findByIdForUpdate(fixture.relationId())
                                                                .orElseThrow();
                                                schedulerHoldsLock.countDown();
                                                schedulerMayCommit.await(10, TimeUnit.SECONDS);
                                                locked.resumeFromPause();
                                                return null;
                                            }));
            assertThat(schedulerHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();

            // when — 사용자가 같은 행의 모드를 REPORT_ONLY로 변경(실제 서비스 경로)
            Future<NotificationSettingsResponse> userUpdate =
                    executor.submit(
                            () ->
                                    settingsService.updateSettings(
                                            fixture.guardianId(),
                                            fixture.targetPublicId(),
                                            "REPORT_ONLY",
                                            30,
                                            60));

            // then 1 — 스케줄러가 잠금을 쥐고 있는 동안 사용자 요청은 대기한다
            assertThatThrownBy(() -> userUpdate.get(700, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            // then 2 — 복귀가 커밋된 뒤 "이미 복귀된(정지 아님)" 최신 상태에 적용된다
            schedulerMayCommit.countDown();
            scheduler.get(10, TimeUnit.SECONDS);
            NotificationSettingsResponse response = userUpdate.get(10, TimeUnit.SECONDS);
            assertThat(response.isPaused()).isFalse();
        } finally {
            executor.shutdownNow();
        }

        // 잠금이 없었다면 사용자 요청이 옛 "정지 중" 상태를 기준으로 처리되어 복귀가 되살아나거나 사라진다
        GuardianTarget result = reload(fixture.relationId());
        assertThat(result.isPaused()).isFalse();
        assertThat(result.getNotificationMode()).isEqualTo("REPORT_ONLY");
        assertThat(result.getPreviousNotificationMode()).isNull();
        assertThat(result.getPausedUntil()).isNull();
    }

    @Test
    @DisplayName("경합 — 수정·정지·재개·자동 복귀를 여러 스레드가 뒤섞어 호출해도 예외와 DB 제약 위반 없이 정지 상태 불변식이 유지된다")
    void mixedConcurrentOperations_keepInvariantsWithoutConstraintViolations() throws Exception {
        // given
        Fixture fixture = newFixture();
        int threads = 8;
        int iterationsPerThread = 25;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        String[] modes = {"REALTIME", "REPORT_ONLY", "HYBRID"};

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    try {
                                        start.await();
                                        for (int i = 0; i < iterationsPerThread; i++) {
                                            ThreadLocalRandom random = ThreadLocalRandom.current();
                                            switch (random.nextInt(5)) {
                                                case 0 ->
                                                        settingsService.pause(
                                                                fixture.guardianId(),
                                                                fixture.targetPublicId(),
                                                                1 + random.nextInt(60));
                                                case 1 ->
                                                        settingsService.resume(
                                                                fixture.guardianId(),
                                                                fixture.targetPublicId());
                                                case 2 ->
                                                        settingsService.updateSettings(
                                                                fixture.guardianId(),
                                                                fixture.targetPublicId(),
                                                                modes[random.nextInt(modes.length)],
                                                                random.nextBoolean() ? null : 1 + random.nextInt(100),
                                                                random.nextBoolean() ? null : 1 + random.nextInt(100));
                                                // 스케줄러 자동 복귀 — 먼 미래 시각을 넘겨 "지금 정지 중이면 무조건 due"로 만든다
                                                case 3 ->
                                                        settingsService.resumeIfDue(
                                                                fixture.relationId(),
                                                                Instant.now().plus(Duration.ofDays(2)));
                                                default ->
                                                        settingsService.getSettings(
                                                                fixture.guardianId(), fixture.targetPublicId());
                                            }
                                        }
                                    } catch (Throwable e) {
                                        failures.add(e);
                                    }
                                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        // then — 어떤 요청도 예외(DB CHECK/NOT NULL 위반, 락 실패 포함)를 내지 않았다
        assertThat(failures).isEmpty();
        GuardianTarget result = reload(fixture.relationId());
        assertThat(result.getNotificationMode())
                .isIn("REALTIME", "REPORT_ONLY", "HYBRID", GuardianTarget.NOTIFICATION_MODE_PAUSED);
        if (result.isPaused()) {
            assertThat(result.getPreviousNotificationMode())
                    .isIn("REALTIME", "REPORT_ONLY", "HYBRID"); // null도 PAUSED도 아님
            assertThat(result.getPausedUntil()).isNotNull();
            assertThat(result.getPausedUntil()).isBeforeOrEqualTo(Instant.now().plus(Duration.ofHours(24)));
        } else {
            assertThat(result.getPreviousNotificationMode()).isNull();
            assertThat(result.getPausedUntil()).isNull();
        }
    }

    // ---------------------------------------------------------------------
    // 실제 DB 왕복 — 누적/상한, 오버레이, null 승격 분
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("저장 왕복 — 정지 누적은 DB에 저장되고, 24시간 상한을 넘겨 요청해도 저장된 paused_until은 지금부터 24시간을 넘지 않는다")
    void pause_accumulateAndCap_persistsCappedValue() {
        // given
        Fixture fixture = newFixture();

        // when — 1440분으로 최대 정지 후, 추가로 30분을 더 요청
        settingsService.pause(fixture.guardianId(), fixture.targetPublicId(), 1440);
        Instant afterFirst = reload(fixture.relationId()).getPausedUntil();
        NotificationSettingsResponse second =
                settingsService.pause(fixture.guardianId(), fixture.targetPublicId(), 30);

        // then — 이미 최대라 더 늘지 않는다(상한이 now와 함께 이동하므로 몇 ms 이내의 증가만 허용)
        GuardianTarget stored = reload(fixture.relationId());
        // 응답은 나노초, PostgreSQL timestamptz는 마이크로초 정밀도(드라이버가 반올림)라 1마이크로초 오차를 허용해 비교한다
        assertThat(Duration.between(stored.getPausedUntil(), second.getPausedUntil()).abs())
                .isLessThanOrEqualTo(Duration.of(1, ChronoUnit.MICROS));
        assertThat(stored.getPausedUntil()).isBeforeOrEqualTo(Instant.now().plus(Duration.ofHours(24)));
        assertThat(Duration.between(afterFirst, stored.getPausedUntil())).isLessThan(Duration.ofSeconds(5));
        assertThat(stored.getPreviousNotificationMode()).isEqualTo("HYBRID");
    }

    @Test
    @DisplayName("저장 왕복 — 정지 중 설정 변경은 DB에서도 정지를 유지하고 previous만 바꾸며, null 승격 분('승격 안 함')도 그대로 저장된다")
    void updateSettings_whilePausedAndNullMinutes_persistsOverlayAndNulls() {
        // given
        Fixture fixture = newFixture();
        settingsService.pause(fixture.guardianId(), fixture.targetPublicId(), 30);

        // when
        NotificationSettingsResponse response =
                settingsService.updateSettings(
                        fixture.guardianId(), fixture.targetPublicId(), "REPORT_ONLY", null, 45);

        // then
        GuardianTarget stored = reload(fixture.relationId());
        assertThat(stored.getNotificationMode()).isEqualTo(GuardianTarget.NOTIFICATION_MODE_PAUSED);
        assertThat(stored.getPreviousNotificationMode()).isEqualTo("REPORT_ONLY");
        assertThat(stored.getEscalateMinutesArrival()).isNull();
        assertThat(stored.getEscalateMinutesStay()).isEqualTo(45);
        assertThat(response.isPaused()).isTrue();
        assertThat(response.getNotificationMode()).isEqualTo("REPORT_ONLY");

        // 재개하면 정지 중 바꾼 기본 모드로 돌아온다
        settingsService.resume(fixture.guardianId(), fixture.targetPublicId());
        GuardianTarget resumed = reload(fixture.relationId());
        assertThat(resumed.getNotificationMode()).isEqualTo("REPORT_ONLY");
        assertThat(resumed.getPreviousNotificationMode()).isNull();
        assertThat(resumed.getPausedUntil()).isNull();
    }

    // ---------------------------------------------------------------------
    // 관계 해제 후 재연결
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("관계 해제 후 재연결 — 새 관계 행이 만들어져 설정이 기본값(HYBRID/30/60, 정지 아님)으로 초기화되고 이전 행은 해제 상태로 남는다")
    void relationTerminatedAndRelinked_settingsAreResetToDefaults() {
        // given — 설정을 바꾸고 정지까지 한 관계
        Fixture fixture = newFixture();
        settingsService.updateSettings(
                fixture.guardianId(), fixture.targetPublicId(), "REALTIME", 10, null);
        settingsService.pause(fixture.guardianId(), fixture.targetPublicId(), 30);

        // when — 해제 후 다시 연결(초대 승인과 같은 createRelation)
        guardianTargetService.terminateRelation(fixture.guardianId(), fixture.targetPublicId());

        // then 1 — 해제된 관계로는 설정 조회/수정 모두 TARGET_002
        assertThatThrownBy(() -> settingsService.getSettings(fixture.guardianId(), fixture.targetPublicId()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TARGET_002));
        assertThatThrownBy(
                        () ->
                                settingsService.updateSettings(
                                        fixture.guardianId(), fixture.targetPublicId(), "HYBRID", 30, 60))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TARGET_002));

        // then 2 — 재연결하면 새 행이 기본값으로 시작한다
        GuardianTarget relinked =
                guardianTargetService.createRelation(fixture.guardianId(), fixture.target().getId());
        assertThat(relinked.getId()).isNotEqualTo(fixture.relationId());
        NotificationSettingsResponse fresh =
                settingsService.getSettings(fixture.guardianId(), fixture.targetPublicId());
        assertThat(fresh.getNotificationMode()).isEqualTo("HYBRID");
        assertThat(fresh.getEscalateMinutesArrival()).isEqualTo(30);
        assertThat(fresh.getEscalateMinutesStay()).isEqualTo(60);
        assertThat(fresh.isPaused()).isFalse();
        assertThat(fresh.getPausedUntil()).isNull();

        // then 3 — 이전 행은 해제 상태로 남고 옛 설정을 그대로 보존한다(재연결이 이를 되살리지 않는다)
        GuardianTarget old = reload(fixture.relationId());
        assertThat(old.getStatus()).isEqualTo(GuardianTarget.STATUS_TERMINATED);
        assertThat(old.getNotificationMode()).isEqualTo(GuardianTarget.NOTIFICATION_MODE_PAUSED);
        assertThat(old.getPreviousNotificationMode()).isEqualTo("REALTIME");
    }

    @Test
    @DisplayName("권한 — 다른 Guardian의 관계 행은 조회·수정·정지·재개 어느 것도 할 수 없다(호출자 본인 행이 없으면 TARGET_002)")
    void otherGuardian_cannotTouchSomeoneElsesSettings() {
        // given — fixture의 보호자는 이 대상자의 PRIMARY, 아래 보호자는 이 대상자와 관계가 없다
        Fixture fixture = newFixture();
        String suffix = UUID.randomUUID().toString();
        User otherGuardian =
                userRepository.save(
                        User.createFromOAuth("o-" + suffix + "@example.com", "GOOGLE", "o-" + suffix));

        // when & then — 관계가 없는 보호자는 전부 TARGET_002
        assertThatThrownBy(() -> settingsService.getSettings(otherGuardian.getId(), fixture.targetPublicId()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TARGET_002));
        assertThatThrownBy(() -> settingsService.pause(otherGuardian.getId(), fixture.targetPublicId(), 30))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TARGET_002));

        // 그리고 PRIMARY의 설정은 그대로다(다른 보호자의 시도가 아무 영향도 주지 않음)
        assertThat(reload(fixture.relationId()).isPaused()).isFalse();
    }

    @Test
    @DisplayName("권한 — 같은 대상자의 SUB가 설정을 바꿔도 PRIMARY의 설정은 바뀌지 않는다(각자 자기 행만)")
    void subGuardian_changesOnlyOwnRow_notPrimarys() {
        // given — PRIMARY(fixture)와 같은 대상자에 연결된 SUB
        Fixture fixture = newFixture();
        String suffix = UUID.randomUUID().toString();
        User subGuardian =
                userRepository.save(
                        User.createFromOAuth("s-" + suffix + "@example.com", "GOOGLE", "s-" + suffix));
        GuardianTarget subRelation =
                guardianTargetService.createRelation(subGuardian.getId(), fixture.target().getId());
        assertThat(subRelation.isPrimary()).isFalse();

        // when — SUB가 자기 설정을 바꾸고 정지
        settingsService.updateSettings(subGuardian.getId(), fixture.targetPublicId(), "REPORT_ONLY", 5, 5);
        settingsService.pause(subGuardian.getId(), fixture.targetPublicId(), 60);

        // then — PRIMARY 행은 기본값 그대로, SUB 행만 바뀜
        GuardianTarget primary = reload(fixture.relationId());
        assertThat(primary.getNotificationMode()).isEqualTo("HYBRID");
        assertThat(primary.getEscalateMinutesArrival()).isEqualTo(30);
        assertThat(primary.isPaused()).isFalse();
        GuardianTarget sub = reload(subRelation.getId());
        assertThat(sub.isPaused()).isTrue();
        assertThat(sub.getPreviousNotificationMode()).isEqualTo("REPORT_ONLY");
    }
}
