package com.tracecare.backend.domain.caretarget.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class PendingGuardianResponse {

    private String guardianId;
    private String name;
}
