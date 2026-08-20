package com.tracecare.backend.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class RefreshTokenRequest {

    @NotBlank private String refreshToken;
}
