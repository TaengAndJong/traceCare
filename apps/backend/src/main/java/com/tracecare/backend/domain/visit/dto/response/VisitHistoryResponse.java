package com.tracecare.backend.domain.visit.dto.response;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.visit.entity.VisitHistory;

/**
 * API_Specification.md §3.4, API_Response_Rule.md §8.7.
 *
 * <p>발견/수정(2026-08, 알림 연동 세션): Lombok이 {@code boolean isRegisteredPlace} 필드에 만드는 getter {@code
 * isRegisteredPlace()}를 Jackson이 "is" 접두사를 다시 벗겨 암묵적으로 "registeredPlace"라는 별도 프로퍼티로 인식해버렸다(문서 예시는
 * {@code isRegisteredPlace}). 필드에 {@code @JsonProperty}만 붙이면 getter가 만드는 프로퍼티와 합쳐지지 않고 두 키가 동시에 나가는
 * 것까지 직접 확인해, getter를 직접 선언하는 방식으로 고쳤다(Lombok은 이미 있는 메서드는 재생성하지 않는다).
 */
@Getter
@Builder
@AllArgsConstructor
public class VisitHistoryResponse {

    private String placeName;
    private Instant arrivalTime;
    private Instant departureTime;
    private Integer stayMinutes;
    private boolean isRegisteredPlace;

    public static VisitHistoryResponse of(VisitHistory visit) {
        return VisitHistoryResponse.builder()
                .placeName(visit.getPlaceName())
                .arrivalTime(visit.getArrivalTime())
                .departureTime(visit.getDepartureTime())
                .stayMinutes(visit.getStayMinutes())
                .isRegisteredPlace(visit.isRegisteredPlace())
                .build();
    }

    @JsonProperty("isRegisteredPlace")
    public boolean isRegisteredPlace() {
        return isRegisteredPlace;
    }
}
