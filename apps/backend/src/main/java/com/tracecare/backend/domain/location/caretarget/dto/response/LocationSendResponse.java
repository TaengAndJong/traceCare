package com.tracecare.backend.domain.location.caretarget.dto.response;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class LocationSendResponse {

    private Long locationId;
    private Instant recordedAt;
}
