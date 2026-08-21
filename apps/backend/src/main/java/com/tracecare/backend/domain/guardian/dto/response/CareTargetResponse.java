package com.tracecare.backend.domain.guardian.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;

@Getter
@Builder
@AllArgsConstructor
public class CareTargetResponse {

    private String careTargetId;
    private String name;
    private String relation;
    private String alias;

    public static CareTargetResponse of(GuardianTarget guardianTarget, User target) {
        return CareTargetResponse.builder()
                .careTargetId(target.getPublicId().toString())
                .name(target.getName())
                .relation(guardianTarget.getRelation())
                .alias(guardianTarget.getAlias())
                .build();
    }
}
