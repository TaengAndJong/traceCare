package com.tracecare.backend.domain.location.caretarget.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tracecare.backend.common.response.ApiResponse;
import com.tracecare.backend.common.response.SuccessCode;
import com.tracecare.backend.common.security.SecurityUtils;
import com.tracecare.backend.domain.location.caretarget.dto.request.LocationSendRequest;
import com.tracecare.backend.domain.location.caretarget.dto.response.LocationSendResponse;
import com.tracecare.backend.domain.location.caretarget.service.LocationService;
import com.tracecare.backend.domain.location.guardian.dto.response.CurrentLocationResponse;

/** API_Specification.md §4.1, §4.3. */
@RestController
@RequestMapping("/api/care-target")
public class LocationController {

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @PostMapping("/location")
    public ApiResponse<LocationSendResponse> sendLocation(
            @Valid @RequestBody LocationSendRequest request) {
        return ApiResponse.success(
                SuccessCode.LOCATION_002,
                locationService.sendLocation(SecurityUtils.getCurrentUserId(), request));
    }

    @GetMapping("/location")
    public ApiResponse<CurrentLocationResponse> getMyLocation() {
        return ApiResponse.success(
                SuccessCode.LOCATION_001,
                locationService.getMyLocation(SecurityUtils.getCurrentUserId()));
    }

    @PostMapping("/share/location")
    public ApiResponse<Void> shareLocation(@Valid @RequestBody LocationSendRequest request) {
        locationService.shareLocation(SecurityUtils.getCurrentUserId(), request);
        return ApiResponse.success(SuccessCode.LOCATION_002);
    }
}
