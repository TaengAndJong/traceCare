package com.tracecare.backend.domain.caretarget.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;

@Getter
@Builder
@AllArgsConstructor
public class InviteApproveResponse {

    private String guardianId;
    private String guardianRole;
    private String relation;
    private String alias;

    public static InviteApproveResponse of(GuardianTarget guardianTarget, User guardian) {
        return InviteApproveResponse.builder()
                .guardianId(guardian.getPublicId().toString())
                .guardianRole(guardianTarget.getGuardianRole())
                .relation(guardianTarget.getRelation())
                .alias(guardianTarget.getAlias())
                .build();
    }
}
