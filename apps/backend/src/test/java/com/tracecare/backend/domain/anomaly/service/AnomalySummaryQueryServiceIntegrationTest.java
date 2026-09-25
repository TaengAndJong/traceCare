package com.tracecare.backend.domain.anomaly.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.tracecare.backend.domain.anomaly.dto.response.SummaryAnomalyResponse;
import com.tracecare.backend.domain.anomaly.entity.AnomalyEvent;
import com.tracecare.backend.domain.anomaly.repository.AnomalyEventRepository;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.guardian.service.GuardianTargetService;
import com.tracecare.backend.domain.notification.entity.NotificationHistory;
import com.tracecare.backend.domain.notification.repository.NotificationHistoryRepository;
import com.tracecare.backend.domain.place.entity.Place;
import com.tracecare.backend.domain.place.repository.PlaceRepository;
import com.tracecare.backend.support.TestSchema;

/**
 * §4.5 — {@code /summary}·{@code /report/weekly}의 이상행동 조회({@link AnomalySummaryQueryService})를 <b>실제 PostgreSQL</b>로
 * 검증한다: 기간 겹침 조건과 경계, 정렬, 20건 상한과 총 건수, 알림 모드/일시정지와 무관한 노출, Guardian별 {@code notified}(SENT/READ/RESPONDED/FAILED/기록
 * 없음), 삭제된 Place. JPQL(CASE 정렬, IN, 겹침 조건)은 실제 DB에서만 의미 있게 검증된다. 각 테스트는 트랜잭션 롤백으로 격리되고 테스트마다 고유한
 * 사용자를 만든다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AnomalySummaryQueryService.class, GuardianTargetService.class})
@Testcontainers
class AnomalySummaryQueryServiceIntegrationTest {

    private static final Instant FROM = Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-20T00:00:00Z");

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

    @Autowired private AnomalySummaryQueryService queryService;
    @Autowired private UserRepository userRepository;
    @Autowired private AnomalyEventRepository anomalyEventRepository;
    @Autowired private NotificationHistoryRepository notificationHistoryRepository;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private GuardianTargetRepository guardianTargetRepository;
    @Autowired private GuardianTargetService guardianTargetService;
    @Autowired private EntityManager em;

    private User newUser(String prefix) {
        String suffix = UUID.randomUUID().toString();
        return userRepository.save(
                User.createFromOAuth(prefix + "-" + suffix + "@example.com", "GOOGLE", prefix + "-" + suffix));
    }

    private Place newPlace(User guardian, User target, String name) {
        return placeRepository.save(
                Place.createActive(
                        guardian.getId(),
                        target.getId(),
                        name,
                        "주소",
                        new BigDecimal("37.5"),
                        new BigDecimal("127.0"),
                        100));
    }

    /** 감지 시각/해소 시각(null이면 진행 중)을 지정해 ARRIVAL_DELAY 이벤트를 만든다. */
    private AnomalyEvent newEvent(User target, Place place, Instant detectedAt, Instant resolvedAt) {
        AnomalyEvent event =
                AnomalyEvent.createArrivalDelay(
                        target.getId(), place == null ? null : place.getId(), detectedAt, detectedAt.minusSeconds(600));
        if (resolvedAt != null) {
            event.resolve(resolvedAt);
        }
        return anomalyEventRepository.save(event);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private static List<Long> ids(AnomalySummaryQueryService.Result result) {
        return result.items().stream().map(SummaryAnomalyResponse::getAnomalyEventId).toList();
    }

    private NotificationHistory sentPush(User guardian, User target, AnomalyEvent event) {
        return notificationHistoryRepository.save(
                NotificationHistory.createForAnomaly(
                        guardian.getId(), target.getId(), UUID.randomUUID(), "장소", event.getId()));
    }

    // ---------------------------------------------------------------------
    // 기간 겹침
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("find — 기간과 겹치는 이상행동만 조회한다(이월 진행 중/경계 포함, 기간 밖 해소·미래 감지 제외)")
    void find_overlappingPeriod_includesCarriedOverAndBoundariesExcludesOutside() {
        User guardian = newUser("g");
        User target = newUser("t");
        Instant beforeFrom = FROM.minusSeconds(86_400);

        AnomalyEvent carriedOverOpen = newEvent(target, null, beforeFrom, null);
        AnomalyEvent resolvedBefore = newEvent(target, null, beforeFrom, FROM.minusSeconds(3_600));
        AnomalyEvent resolvedInside = newEvent(target, null, beforeFrom, FROM.plusSeconds(3_600));
        AnomalyEvent inside = newEvent(target, null, FROM.plusSeconds(7_200), FROM.plusSeconds(10_800));
        AnomalyEvent resolvedAfterTo = newEvent(target, null, TO.minusSeconds(3_600), TO.plusSeconds(3_600));
        AnomalyEvent detectedAfterTo = newEvent(target, null, TO.plusSeconds(1), null);
        AnomalyEvent detectedExactlyAtTo = newEvent(target, null, TO, null);
        AnomalyEvent resolvedExactlyAtFrom = newEvent(target, null, beforeFrom, FROM);
        AnomalyEvent otherTargets = newEvent(newUser("t2"), null, FROM.plusSeconds(3_600), null);
        flushAndClear();

        AnomalySummaryQueryService.Result result =
                queryService.find(guardian.getId(), target.getId(), FROM, TO);

        assertThat(ids(result))
                .containsExactlyInAnyOrder(
                        carriedOverOpen.getId(),
                        resolvedInside.getId(),
                        inside.getId(),
                        resolvedAfterTo.getId(),
                        detectedExactlyAtTo.getId(),
                        resolvedExactlyAtFrom.getId())
                .doesNotContain(
                        resolvedBefore.getId(), detectedAfterTo.getId(), otherTargets.getId());
        assertThat(result.totalCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("find — 진행 중(ONGOING)을 먼저, 그다음 감지 시각 내림차순으로 정렬하고 status/resolvedAt을 채운다")
    void find_ordersOngoingFirstThenDetectedAtDesc() {
        User guardian = newUser("g");
        User target = newUser("t");
        AnomalyEvent resolvedNewest = newEvent(target, null, FROM.plusSeconds(5_000), FROM.plusSeconds(6_000));
        AnomalyEvent ongoingOldest = newEvent(target, null, FROM.plusSeconds(1_000), null);
        AnomalyEvent ongoingNewer = newEvent(target, null, FROM.plusSeconds(3_000), null);
        AnomalyEvent resolvedOlder = newEvent(target, null, FROM.plusSeconds(2_000), FROM.plusSeconds(2_500));
        flushAndClear();

        AnomalySummaryQueryService.Result result =
                queryService.find(guardian.getId(), target.getId(), FROM, TO);

        assertThat(ids(result))
                .containsExactly(
                        ongoingNewer.getId(),
                        ongoingOldest.getId(),
                        resolvedNewest.getId(),
                        resolvedOlder.getId());
        assertThat(result.items())
                .extracting(SummaryAnomalyResponse::getStatus)
                .containsExactly("ONGOING", "ONGOING", "RESOLVED", "RESOLVED");
        assertThat(result.items().get(2).getResolvedAt()).isEqualTo(FROM.plusSeconds(6_000));
        assertThat(result.items().get(0).getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("find — 이상행동이 없으면 totalCount 0과 빈 목록(null 아님)을 반환한다")
    void find_noAnomalies_returnsZeroAndEmptyList() {
        User guardian = newUser("g");
        User target = newUser("t");

        AnomalySummaryQueryService.Result result =
                queryService.find(guardian.getId(), target.getId(), FROM, TO);

        assertThat(result.totalCount()).isZero();
        assertThat(result.items()).isNotNull().isEmpty();
    }

    // ---------------------------------------------------------------------
    // 20건 상한 + 총 건수
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("find — 25건이면 항목은 20건으로 자르고 totalCount는 25를 유지하며 진행 중이 상한 안에 먼저 든다")
    void find_moreThanCap_limitsItemsToTwentyAndKeepsTotalCount() {
        User guardian = newUser("g");
        User target = newUser("t");
        List<Long> resolvedIds = new java.util.ArrayList<>();
        for (int i = 1; i <= 24; i++) {
            resolvedIds.add(
                    newEvent(target, null, FROM.plusSeconds(i * 100L), FROM.plusSeconds(i * 100L + 50)).getId());
        }
        // 가장 오래된 진행 중 이벤트 — 시각순이라면 상한 밖으로 밀리지만 진행 중 우선이라 포함돼야 한다.
        AnomalyEvent oldestOngoing = newEvent(target, null, FROM.plusSeconds(1), null);
        flushAndClear();

        AnomalySummaryQueryService.Result result =
                queryService.find(guardian.getId(), target.getId(), FROM, TO);

        assertThat(result.totalCount()).isEqualTo(25);
        assertThat(result.items()).hasSize(20);
        assertThat(ids(result).get(0)).isEqualTo(oldestOngoing.getId());
        // 나머지 19건은 해소된 것 중 감지 시각이 가장 최신인 순서다.
        assertThat(ids(result).subList(1, 20))
                .containsExactlyElementsOf(resolvedIds.reversed().subList(0, 19));
    }

    // ---------------------------------------------------------------------
    // 알림 모드 / 일시정지와 무관
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("find — REALTIME/HYBRID/REPORT_ONLY/PAUSED 어느 모드여도 같은 이상행동을 전부 노출한다")
    void find_anyNotificationModeOrPaused_exposesSameAnomalies() {
        User guardian = newUser("g");
        User target = newUser("t");
        GuardianTarget relation = guardianTargetService.createRelation(guardian.getId(), target.getId());
        AnomalyEvent ongoing = newEvent(target, null, FROM.plusSeconds(1_000), null);
        AnomalyEvent resolved = newEvent(target, null, FROM.plusSeconds(2_000), FROM.plusSeconds(3_000));
        flushAndClear();

        for (String mode :
                List.of(
                        GuardianTarget.NOTIFICATION_MODE_REALTIME,
                        GuardianTarget.NOTIFICATION_MODE_HYBRID,
                        GuardianTarget.NOTIFICATION_MODE_REPORT_ONLY)) {
            GuardianTarget loaded = guardianTargetRepository.findById(relation.getId()).orElseThrow();
            loaded.updateNotificationMode(mode, null, null);
            flushAndClear();

            AnomalySummaryQueryService.Result result =
                    queryService.find(guardian.getId(), target.getId(), FROM, TO);
            assertThat(ids(result)).as("mode=" + mode).containsExactly(ongoing.getId(), resolved.getId());
            assertThat(result.totalCount()).as("mode=" + mode).isEqualTo(2);
        }

        GuardianTarget loaded = guardianTargetRepository.findById(relation.getId()).orElseThrow();
        loaded.pause(Instant.now(), 60);
        flushAndClear();
        assertThat(guardianTargetRepository.findById(relation.getId()).orElseThrow().isPaused()).isTrue();

        AnomalySummaryQueryService.Result paused =
                queryService.find(guardian.getId(), target.getId(), FROM, TO);
        assertThat(ids(paused)).as("mode=PAUSED").containsExactly(ongoing.getId(), resolved.getId());
        assertThat(paused.totalCount()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // notified
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("find — notified는 이 Guardian에게 FAILED가 아닌 발송 기록(SENT/READ/RESPONDED)이 있을 때만 true다")
    void find_notified_trueOnlyForNonFailedRecordsOfThisGuardian() {
        User guardian = newUser("g");
        User otherGuardian = newUser("g2");
        User target = newUser("t");
        AnomalyEvent sent = newEvent(target, null, FROM.plusSeconds(1_000), null);
        AnomalyEvent read = newEvent(target, null, FROM.plusSeconds(2_000), null);
        AnomalyEvent responded = newEvent(target, null, FROM.plusSeconds(3_000), null);
        AnomalyEvent failed = newEvent(target, null, FROM.plusSeconds(4_000), null);
        AnomalyEvent never = newEvent(target, null, FROM.plusSeconds(5_000), null);
        AnomalyEvent onlyOtherGuardian = newEvent(target, null, FROM.plusSeconds(6_000), null);
        AnomalyEvent failedThenSent = newEvent(target, null, FROM.plusSeconds(7_000), null);

        sentPush(guardian, target, sent);
        NotificationHistory readRow = sentPush(guardian, target, read);
        readRow.markRead();
        NotificationHistory respondedRow = sentPush(guardian, target, responded);
        NotificationHistory failedRow = sentPush(guardian, target, failed);
        failedRow.markFailed();
        sentPush(otherGuardian, target, onlyOtherGuardian);
        NotificationHistory failedFirst = sentPush(guardian, target, failedThenSent);
        failedFirst.markFailed();
        sentPush(guardian, target, failedThenSent);
        em.flush();
        em.createNativeQuery("UPDATE \"NotificationHistory\" SET status = 'RESPONDED' WHERE id = :id")
                .setParameter("id", respondedRow.getId())
                .executeUpdate();
        em.clear();

        AnomalySummaryQueryService.Result result =
                queryService.find(guardian.getId(), target.getId(), FROM, TO);

        java.util.Map<Long, Boolean> notified = new java.util.HashMap<>();
        result.items().forEach(i -> notified.put(i.getAnomalyEventId(), i.isNotified()));
        assertThat(notified.get(sent.getId())).as("SENT").isTrue();
        assertThat(notified.get(read.getId())).as("READ").isTrue();
        assertThat(notified.get(responded.getId())).as("RESPONDED").isTrue();
        assertThat(notified.get(failed.getId())).as("FAILED뿐").isFalse();
        assertThat(notified.get(never.getId())).as("기록 없음").isFalse();
        assertThat(notified.get(onlyOtherGuardian.getId())).as("다른 Guardian에게만 발송").isFalse();
        assertThat(notified.get(failedThenSent.getId())).as("FAILED 뒤 SENT 성공").isTrue();
    }

    // ---------------------------------------------------------------------
    // Place
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("find — Place가 있으면 public_id/이름을, Soft Delete됐거나 없으면 둘 다 null로 내린다")
    void find_placeNames_nullWhenPlaceDeletedOrAbsent() {
        User guardian = newUser("g");
        User target = newUser("t");
        Place alive = newPlace(guardian, target, "학교");
        Place deleted = newPlace(guardian, target, "삭제될장소");
        AnomalyEvent withPlace = newEvent(target, alive, FROM.plusSeconds(1_000), null);
        AnomalyEvent withDeletedPlace = newEvent(target, deleted, FROM.plusSeconds(2_000), null);
        AnomalyEvent withoutPlace = newEvent(target, null, FROM.plusSeconds(3_000), null);
        em.flush();
        deleted.delete();
        flushAndClear();

        AnomalySummaryQueryService.Result result =
                queryService.find(guardian.getId(), target.getId(), FROM, TO);

        java.util.Map<Long, SummaryAnomalyResponse> byId = new java.util.HashMap<>();
        result.items().forEach(i -> byId.put(i.getAnomalyEventId(), i));
        assertThat(byId.get(withPlace.getId()).getPlaceName()).isEqualTo("학교");
        assertThat(byId.get(withPlace.getId()).getPlaceId()).isEqualTo(alive.getPublicId().toString());
        assertThat(byId.get(withDeletedPlace.getId()).getPlaceName()).isNull();
        assertThat(byId.get(withDeletedPlace.getId()).getPlaceId()).isNull();
        assertThat(byId.get(withoutPlace.getId()).getPlaceName()).isNull();
        assertThat(byId.get(withoutPlace.getId()).getPlaceId()).isNull();
    }
}
