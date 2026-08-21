package com.tracecare.backend.domain.caretarget.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class InviteRedeemResponse {

    private String careTargetId;
    private String name;
    private String status;
}
