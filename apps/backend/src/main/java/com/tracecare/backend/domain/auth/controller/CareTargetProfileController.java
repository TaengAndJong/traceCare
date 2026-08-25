package com.tracecare.backend.domain.auth.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tracecare.backend.common.response.ApiResponse;
import com.tracecare.backend.common.response.SuccessCode;
import com.tracecare.backend.common.security.SecurityUtils;
import com.tracecare.backend.domain.auth.dto.request.ProfileUpdateRequest;
import com.tracecare.backend.domain.auth.dto.response.MeResponse;
import com.tracecare.backend.domain.auth.service.UserService;

/** API_Specification.md §4.6. GuardianProfileController와 동일한 UserService를 재사용한다(§3.8과 필드 동일). */
@RestController
@RequestMapping("/api/care-target/profile")
public class CareTargetProfileController {

    private final UserService userService;

    public CareTargetProfileController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<MeResponse> getProfile() {
        return ApiResponse.success(
                SuccessCode.USER_001,
                MeResponse.from(userService.getUser(SecurityUtils.getCurrentUserId())));
    }

    @PutMapping
    public ApiResponse<MeResponse> updateProfile(@Valid @RequestBody ProfileUpdateRequest request) {
        return ApiResponse.success(
                SuccessCode.USER_002,
                MeResponse.from(
                        userService.updateProfile(
                                SecurityUtils.getCurrentUserId(),
                                request.getName(),
                                request.getPhone())));
    }
}
