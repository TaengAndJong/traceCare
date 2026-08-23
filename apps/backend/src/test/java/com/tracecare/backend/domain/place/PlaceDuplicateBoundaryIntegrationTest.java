package com.tracecare.backend.domain.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;

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

import com.tracecare.backend.common.exception.business.DuplicatePlaceException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.service.GuardianTargetService;
import com.tracecare.backend.domain.place.dto.request.PlaceCreateRequest;
import com.tracecare.backend.domain.place.dto.response.PlaceResponse;
import com.tracecare.backend.domain.place.service.PlaceService;

/**
 * Place 중복 등록 판정(PLACE_002)의 거리 경계값을 검증한다. 판정 반경은 {@code place.duplicate-distance-meters}
 * 설정값(2026-08 30m → 50m 완화)이며, 여기서는 application.yml의 기본값(50m)을 그대로 사용한다. 좌표는 {@link
 * com.tracecare.backend.common.util.GeoDistanceCalculatorTest}와 동일한 방식(지구 평균 반지름 6,371,000m 기준 위도
 * 1도의 자오선 호 길이 ≈ 111,194.93m)으로 역산한 정확한 거리를 사용한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class PlaceDuplicateBoundaryIntegrationTest {

    private static final double BASE_LATITUDE = 37.5;
    private static final double BASE_LONGITUDE = 127.0;

    /** 40m/49m/60m에 해당하는 위도 델타(같은 자오선 위 이동, GeoDistanceCalculatorTest와 동일한 역산 방식). */
    private static final double DELTA_LAT_40M = 0.0003597286;

    private static final double DELTA_LAT_49M = 0.0004406676;
    private static final double DELTA_LAT_60M = 0.0005395930;

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
    @Autowired private GuardianTargetService guardianTargetService;
    @Autowired private PlaceService placeService;

    @Test
    @DisplayName("기준 좌표에서 약 40m 떨어진 다른 이름의 장소는 중복(PLACE_002)으로 거부된다")
    void createPlace_fortyMetersApart_isDuplicate() {
        User target = createConfirmedUser("place-boundary-40m-target", "CARE_TARGET");
        User primaryGuardian = createConfirmedUser("place-boundary-40m-primary", "GUARDIAN");
        guardianTargetService.createRelation(primaryGuardian.getId(), target.getId());
        createBasePlace(target, primaryGuardian);

        assertThatThrownBy(
                        () ->
                                placeService.createPlace(
                                        primaryGuardian.getId(),
                                        nearbyRequest(target, "40m 떨어진 다른이름", DELTA_LAT_40M)))
                .isInstanceOf(DuplicatePlaceException.class);
    }

    @Test
    @DisplayName("기준 좌표에서 약 49m 떨어진 다른 이름의 장소도 중복(PLACE_002)으로 거부된다")
    void createPlace_fortyNineMetersApart_isDuplicate() {
        User target = createConfirmedUser("place-boundary-49m-target", "CARE_TARGET");
        User primaryGuardian = createConfirmedUser("place-boundary-49m-primary", "GUARDIAN");
        guardianTargetService.createRelation(primaryGuardian.getId(), target.getId());
        createBasePlace(target, primaryGuardian);

        assertThatThrownBy(
                        () ->
                                placeService.createPlace(
                                        primaryGuardian.getId(),
                                        nearbyRequest(target, "49m 떨어진 다른이름", DELTA_LAT_49M)))
                .isInstanceOf(DuplicatePlaceException.class);
    }

    @Test
    @DisplayName("기준 좌표에서 약 60m 떨어진 장소는 중복 판정 반경(50m)을 벗어나 정상 등록된다")
    void createPlace_sixtyMetersApart_isNotDuplicate() {
        User target = createConfirmedUser("place-boundary-60m-target", "CARE_TARGET");
        User primaryGuardian = createConfirmedUser("place-boundary-60m-primary", "GUARDIAN");
        guardianTargetService.createRelation(primaryGuardian.getId(), target.getId());
        createBasePlace(target, primaryGuardian);

        PlaceResponse response =
                placeService.createPlace(
                        primaryGuardian.getId(),
                        nearbyRequest(target, "60m 떨어진 장소", DELTA_LAT_60M));

        assertThat(response.getName()).isEqualTo("60m 떨어진 장소");
    }

    private void createBasePlace(User target, User primaryGuardian) {
        PlaceCreateRequest baseRequest =
                PlaceCreateRequest.builder()
                        .careTargetId(target.getPublicId().toString())
                        .name("기준장소")
                        .address("서울")
                        .latitude(BASE_LATITUDE)
                        .longitude(BASE_LONGITUDE)
                        .radius(100)
                        .build();
        placeService.createPlace(primaryGuardian.getId(), baseRequest);
    }

    private PlaceCreateRequest nearbyRequest(User target, String name, double deltaLatitude) {
        return PlaceCreateRequest.builder()
                .careTargetId(target.getPublicId().toString())
                .name(name)
                .address("서울")
                .latitude(BASE_LATITUDE + deltaLatitude)
                .longitude(BASE_LONGITUDE)
                .radius(100)
                .build();
    }

    private User createConfirmedUser(String oauthId, String role) {
        User user = User.createFromOAuth(oauthId + "@example.com", "GOOGLE", oauthId);
        user.confirmRole(role, "Place Duplicate Boundary Test User", LocalDate.of(1990, 1, 1));
        return userRepository.save(user);
    }
}
