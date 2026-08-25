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
import com.tracecare.backend.domain.auth.dto.request.ProfileImageUpdateRequest;
import com.tracecare.backend.domain.auth.dto.request.ProfileUpdateRequest;
import com.tracecare.backend.domain.auth.dto.response.MeResponse;
import com.tracecare.backend.domain.auth.service.UserService;

/**
 * API_Specification.md §3.8. 항상 {@code SecurityUtils.getCurrentUserId()}(인증된 본인)만 대상으로 하므로 다른 사람의
 * 프로필을 수정할 방법이 구조적으로 없다 — 경로/요청 바디 어디에도 대상 userId를 받지 않는다.
 */
@RestController
@RequestMapping("/api/guardian/profile")
public class GuardianProfileController {

    private final UserService userService;

    public GuardianProfileController(UserService userService) {
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

    @PutMapping("/image")
    public ApiResponse<MeResponse> updateProfileImage(
            @Valid @RequestBody ProfileImageUpdateRequest request) {
        return ApiResponse.success(
                SuccessCode.USER_002,
                MeResponse.from(
                        userService.updateProfileImage(
                                SecurityUtils.getCurrentUserId(), request.getImageUrl())));
    }
}
