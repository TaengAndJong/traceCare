package com.tracecare.backend.domain.guardian.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class PrimaryDelegationResponse {

    private String careTargetId;
    private String previousPrimaryGuardianId;
    private String newPrimaryGuardianId;
}
