package com.tracecare.backend.domain.guardian.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tracecare.backend.common.response.ApiResponse;
import com.tracecare.backend.common.response.SuccessCode;
import com.tracecare.backend.common.security.CustomUserDetails;
import com.tracecare.backend.domain.guardian.dto.request.NotificationPauseRequest;
import com.tracecare.backend.domain.guardian.dto.request.NotificationSettingsUpdateRequest;
import com.tracecare.backend.domain.guardian.dto.response.NotificationSettingsResponse;
import com.tracecare.backend.domain.guardian.service.GuardianNotificationSettingsService;

/**
 * API_Specification.md §3.10 — 호출자 <b>본인</b>의 이상행동 알림 설정. 요청에 {@code guardianId}를 받지 않고 항상 인증된 사용자
 * ({@code @AuthenticationPrincipal})의 id로 본인 GuardianTarget 행을 찾는다. 이 설정은 이상행동 알림(승격)에만 적용되며 GeoFence 도착 확인
 * 알림과 긴급 연락은 영향받지 않는다.
 */
@RestController
@RequestMapping("/api/guardian/care-targets/{id}/notification-settings")
public class GuardianNotificationSettingsController {

    private final GuardianNotificationSettingsService notificationSettingsService;

    public GuardianNotificationSettingsController(
            GuardianNotificationSettingsService notificationSettingsService) {
        this.notificationSettingsService = notificationSettingsService;
    }

    @GetMapping
    public ApiResponse<NotificationSettingsResponse> getSettings(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID id) {
        return ApiResponse.success(
                SuccessCode.TARGET_011, notificationSettingsService.getSettings(user.getUserId(), id));
    }

    @PutMapping
    public ApiResponse<NotificationSettingsResponse> updateSettings(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID id,
            @Valid @RequestBody NotificationSettingsUpdateRequest request) {
        return ApiResponse.success(
                SuccessCode.TARGET_012,
                notificationSettingsService.updateSettings(
                        user.getUserId(),
                        id,
                        request.getNotificationMode(),
                        request.getEscalateMinutesArrival(),
                        request.getEscalateMinutesStay()));
    }

    @PostMapping("/pause")
    public ApiResponse<NotificationSettingsResponse> pause(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID id,
            @Valid @RequestBody NotificationPauseRequest request) {
        return ApiResponse.success(
                SuccessCode.TARGET_013,
                notificationSettingsService.pause(
                        user.getUserId(), id, request.getDurationMinutes()));
    }

    @PostMapping("/resume")
    public ApiResponse<NotificationSettingsResponse> resume(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID id) {
        return ApiResponse.success(
                SuccessCode.TARGET_014, notificationSettingsService.resume(user.getUserId(), id));
    }
}
