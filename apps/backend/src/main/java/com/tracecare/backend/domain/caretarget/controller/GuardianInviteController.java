package com.tracecare.backend.domain.caretarget.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageImpl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tracecare.backend.common.response.ApiResponse;
import com.tracecare.backend.common.response.PageResponse;
import com.tracecare.backend.common.response.SuccessCode;
import com.tracecare.backend.common.security.SecurityUtils;
import com.tracecare.backend.domain.caretarget.dto.response.InviteApproveResponse;
import com.tracecare.backend.domain.caretarget.dto.response.InviteCodeResponse;
import com.tracecare.backend.domain.caretarget.dto.response.PendingGuardianResponse;
import com.tracecare.backend.domain.caretarget.service.GuardianInviteService;

/** API_Specification.md §4.7 — 초대 코드 생성/승인 대기 목록/승인/거절. */
@RestController
@RequestMapping("/api/care-target/guardians")
public class GuardianInviteController {

    private final GuardianInviteService guardianInviteService;

    public GuardianInviteController(GuardianInviteService guardianInviteService) {
        this.guardianInviteService = guardianInviteService;
    }

    @PostMapping("/invite-code")
    public ApiResponse<InviteCodeResponse> generateInviteCode() {
        return ApiResponse.success(
                SuccessCode.TARGET_003,
                guardianInviteService.generateInviteCode(SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/pending")
    public ApiResponse<PageResponse<PendingGuardianResponse>> getPendingRequests() {
        List<PendingGuardianResponse> pending =
                guardianInviteService.getPendingRequests(SecurityUtils.getCurrentUserId());
        return ApiResponse.success(
                SuccessCode.TARGET_004, PageResponse.of(new PageImpl<>(pending)));
    }

    @PostMapping("/pending/{guardianId}/approve")
    public ApiResponse<InviteApproveResponse> approve(@PathVariable UUID guardianId) {
        return ApiResponse.success(
                SuccessCode.TARGET_002,
                guardianInviteService.approve(SecurityUtils.getCurrentUserId(), guardianId));
    }

    @PostMapping("/pending/{guardianId}/reject")
    public ApiResponse<Void> reject(@PathVariable UUID guardianId) {
        guardianInviteService.reject(SecurityUtils.getCurrentUserId(), guardianId);
        return ApiResponse.success(SuccessCode.TARGET_006);
    }
}
