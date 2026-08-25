package com.tracecare.backend.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/** API_Specification.md §3.8, §4.6. User.name/phone만 다룬다(relation/alias는 GuardianTarget 도메인 소관). */
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class ProfileUpdateRequest {

    @NotBlank private String name;

    @Pattern(regexp = "^01[0-9]-?\\d{3,4}-?\\d{4}$", message = "휴대전화 번호 형식이 올바르지 않습니다")
    private String phone;
}
