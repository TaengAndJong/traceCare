package com.tracecare.backend.domain.auth.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class RoleConfirmRequest {

    /** ADMIN은 API로 셀프 부여할 수 없다 — GUARDIAN/CARE_TARGET만 허용한다. */
    @NotBlank
    @Pattern(regexp = "GUARDIAN|CARE_TARGET")
    private String role;

    @NotBlank private String name;

    @NotNull private LocalDate birthDate;
}
