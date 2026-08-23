package com.tracecare.backend.domain.visit.dto.response;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.visit.entity.VisitHistory;

/** API_Specification.md §3.4, API_Response_Rule.md §8.7. */
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
}
