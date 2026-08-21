package com.tracecare.backend.domain.caretarget.dto.response;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class InviteCodeResponse {

    private String inviteCode;
    private Instant expiresAt;
}
