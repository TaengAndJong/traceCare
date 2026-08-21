package com.tracecare.backend.domain.guardian.dto.request;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class InviteCodeRedeemRequest {

    @NotBlank private String inviteCode;
}
