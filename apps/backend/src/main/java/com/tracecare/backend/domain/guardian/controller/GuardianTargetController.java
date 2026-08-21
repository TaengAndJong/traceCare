package com.tracecare.backend.domain.guardian.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tracecare.backend.common.response.ApiResponse;
import com.tracecare.backend.common.response.PageResponse;
import com.tracecare.backend.common.response.SuccessCode;
import com.tracecare.backend.common.security.SecurityUtils;
import com.tracecare.backend.domain.caretarget.dto.response.InviteRedeemResponse;
import com.tracecare.backend.domain.caretarget.service.GuardianInviteService;
import com.tracecare.backend.domain.guardian.dto.request.InviteCodeRedeemRequest;
import com.tracecare.backend.domain.guardian.dto.request.RelationUpdateRequest;
import com.tracecare.backend.domain.guardian.dto.response.CareTargetResponse;
import com.tracecare.backend.domain.guardian.service.GuardianTargetService;

/**
 * API_Specification.md §3.1. 코드 입력(연결 요청)의 Redis 토큰 처리는
 * domain.caretarget.service.GuardianInviteService에 위임한다(이번 세션 작업 지시서 §4 패키지 구조).
 */
@RestController
@RequestMapping("/api/guardian/care-targets")
public class GuardianTargetController {

    private final GuardianTargetService guardianTargetService;
    private final GuardianInviteService guardianInviteService;

    public GuardianTargetController(
            GuardianTargetService guardianTargetService,
            GuardianInviteService guardianInviteService) {
        this.guardianTargetService = guardianTargetService;
        this.guardianInviteService = guardianInviteService;
    }

    @GetMapping
    public ApiResponse<PageResponse<CareTargetResponse>> getCareTargets(Pageable pageable) {
        return ApiResponse.success(
                SuccessCode.TARGET_001,
                PageResponse.of(
                        guardianTargetService.getCareTargets(
                                SecurityUtils.getCurrentUserId(), pageable)));
    }

    @PostMapping
    public ApiResponse<InviteRedeemResponse> redeemInviteCode(
            @Valid @RequestBody InviteCodeRedeemRequest request) {
        return ApiResponse.success(
                SuccessCode.TARGET_005,
                guardianInviteService.redeemInviteCode(
                        SecurityUtils.getCurrentUserId(), request.getInviteCode()));
    }

    @GetMapping("/{id}")
    public ApiResponse<CareTargetResponse> getCareTarget(@PathVariable UUID id) {
        return ApiResponse.success(
                SuccessCode.TARGET_001,
                guardianTargetService.getCareTarget(SecurityUtils.getCurrentUserId(), id));
    }

    @PutMapping("/{id}")
    public ApiResponse<CareTargetResponse> updateRelation(
            @PathVariable UUID id, @Valid @RequestBody RelationUpdateRequest request) {
        return ApiResponse.success(
                SuccessCode.TARGET_008,
                guardianTargetService.updateRelation(
                        SecurityUtils.getCurrentUserId(),
                        id,
                        request.getRelation(),
                        request.getAlias()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> terminateRelation(@PathVariable UUID id) {
        guardianTargetService.terminateRelation(SecurityUtils.getCurrentUserId(), id);
        return ApiResponse.success(SuccessCode.TARGET_009);
    }
}
