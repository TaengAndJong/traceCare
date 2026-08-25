package com.tracecare.backend.domain.arrival.dto.response;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.arrival.entity.ArrivalHistory;

/** API_Specification.md §4.2, API_Response_Rule.md §8.8. */
@Getter
@Builder
@AllArgsConstructor
public class ArrivalCheckResponse {

    private Long arrivalId;
    private String placeName;
    private Instant confirmedAt;

    public static ArrivalCheckResponse of(ArrivalHistory arrival) {
        return ArrivalCheckResponse.builder()
                .arrivalId(arrival.getId())
                .placeName(arrival.getPlaceName())
                .confirmedAt(arrival.getConfirmedAt())
                .build();
    }
}
