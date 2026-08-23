package com.tracecare.backend.domain.location.caretarget.dto.request;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * 위도/경도 범위(-90~90, -180~180)는 이 DTO에 {@code @ValidLatitude}/{@code @ValidLongitude}를 걸지 않는다 — 그렇게
 * 하면 Bean Validation 경로(COMMON_002)로 응답이 나가는데, API_Specification.md §4.1이 범위 오류를 명시적으로 {@code
 * LOCATION_001}로 지정하고 있어(Place의 반경 검증과 다른 지점) Service 계층에서 같은
 * 검증기(LatitudeValidator/LongitudeValidator)를 직접 호출해 {@code LOCATION_001}을 던진다(LocationService 참고).
 * 여기서는 값이 아예 없는 경우만 {@code @NotNull}로 걸러 COMMON_002로 응답한다.
 */
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class LocationSendRequest {

    @NotNull private Double latitude;

    @NotNull private Double longitude;

    @NotNull private Instant recordedAt;
}
