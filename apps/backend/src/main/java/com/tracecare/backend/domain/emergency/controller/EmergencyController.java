package com.tracecare.backend.domain.emergency.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tracecare.backend.common.response.ApiResponse;
import com.tracecare.backend.common.response.SuccessCode;
import com.tracecare.backend.common.security.SecurityUtils;
import com.tracecare.backend.domain.emergency.dto.response.EmergencyResponse;
import com.tracecare.backend.domain.emergency.service.EmergencyService;
import com.tracecare.backend.domain.location.caretarget.dto.request.LocationSendRequest;

/** API_Specification.md §4.4. */
@RestController
@RequestMapping("/api/care-target/emergency")
public class EmergencyController {

    private final EmergencyService emergencyService;

    public EmergencyController(EmergencyService emergencyService) {
        this.emergencyService = emergencyService;
    }

    @PostMapping("/call")
    public ApiResponse<EmergencyResponse> call() {
        return ApiResponse.success(
                SuccessCode.EMERGENCY_001, emergencyService.call(SecurityUtils.getCurrentUserId()));
    }

    @PostMapping("/message")
    public ApiResponse<EmergencyResponse> message() {
        return ApiResponse.success(
                SuccessCode.EMERGENCY_001,
                emergencyService.message(SecurityUtils.getCurrentUserId()));
    }

    @PostMapping("/location")
    public ApiResponse<EmergencyResponse> location(
            @Valid @RequestBody LocationSendRequest request) {
        return ApiResponse.success(
                SuccessCode.EMERGENCY_001,
                emergencyService.location(SecurityUtils.getCurrentUserId(), request));
    }
}
