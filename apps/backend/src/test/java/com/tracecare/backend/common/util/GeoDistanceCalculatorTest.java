package com.tracecare.backend.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Haversine 거리 계산의 정확성을 검증한다. 실제 지명 좌표(예: 서울역-강남역)는 참고자료마다 소수점 값이 조금씩 달라 "정확한 검증값"으로 삼기 어려워, 대신
 * 수학적으로 정확한 값을 직접 유도할 수 있는 경우(같은 자오선 위 위도 1도 차이, 적도 위 경도 1도 차이)를 기준으로 삼는다 — 지구 평균 반지름 6,371,000m 기준
 * 위도 1도의 자오선 호 길이는 {@code R * (π/180) ≈ 111,194.93m}이고, 적도에서는 경도 1도 호 길이도 동일하다.
 */
class GeoDistanceCalculatorTest {

    private static final double DEGREE_ARC_METERS = 111_194.926644;

    @Test
    @DisplayName("같은 지점 간 거리는 0이다")
    void distanceInMeters_samePoint_returnsZero() {
        double distance = GeoDistanceCalculator.distanceInMeters(37.5, 127.0, 37.5, 127.0);

        assertThat(distance).isEqualTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("같은 자오선 위 위도 1도 차이는 약 111,194.93m다")
    void distanceInMeters_oneDegreeLatitudeOnMeridian_matchesKnownArcLength() {
        double distance = GeoDistanceCalculator.distanceInMeters(37.5, 127.0, 38.5, 127.0);

        assertThat(distance).isCloseTo(DEGREE_ARC_METERS, within(1.0));
    }

    @Test
    @DisplayName("적도 위 경도 1도 차이도 위도 1도와 동일한 호 길이를 가진다")
    void distanceInMeters_oneDegreeLongitudeOnEquator_matchesKnownArcLength() {
        double distance = GeoDistanceCalculator.distanceInMeters(0.0, 0.0, 0.0, 1.0);

        assertThat(distance).isCloseTo(DEGREE_ARC_METERS, within(1.0));
    }

    @Test
    @DisplayName("약 20m 떨어진 두 좌표는 30m 중복 판정 반경 이내다")
    void distanceInMeters_twentyMetersApart_isWithinDuplicateRadius() {
        double deltaLatFor20m = 20.0 / DEGREE_ARC_METERS;
        double distance =
                GeoDistanceCalculator.distanceInMeters(37.5, 127.0, 37.5 + deltaLatFor20m, 127.0);

        assertThat(distance).isCloseTo(20.0, within(0.5));
    }

    @Test
    @DisplayName("약 50m 떨어진 두 좌표는 30m 중복 판정 반경을 벗어난다")
    void distanceInMeters_fiftyMetersApart_exceedsDuplicateRadius() {
        double deltaLatFor50m = 50.0 / DEGREE_ARC_METERS;
        double distance =
                GeoDistanceCalculator.distanceInMeters(37.5, 127.0, 37.5 + deltaLatFor50m, 127.0);

        assertThat(distance).isCloseTo(50.0, within(0.5));
    }
}
