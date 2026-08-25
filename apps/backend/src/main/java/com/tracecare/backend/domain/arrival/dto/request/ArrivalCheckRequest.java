package com.tracecare.backend.domain.arrival.dto.request;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * API_Specification.md §4.2 / API_Response_Rule.md §8.8. 위도/경도 범위 검증은 LocationSendRequest와 동일한 이유로
 * Bean Validation이 아니라 Service 계층에서 {@code LatitudeValidator}/{@code LongitudeValidator}를 직접 호출해
 * 도메인 코드(LOCATION_001)를 던진다 — GeoDistanceCalculator처럼 좌표 검증도 도메인에 묶이지 않는 공용 개념이라 Location 도메인의 코드를
 * 그대로 재사용한다(ArrivalService 참고).
 */
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class ArrivalCheckRequest {

    @NotNull private String placeId;

    @NotNull private Double latitude;

    @NotNull private Double longitude;
}
