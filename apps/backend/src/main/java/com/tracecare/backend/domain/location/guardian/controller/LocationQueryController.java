package com.tracecare.backend.domain.location.guardian.controller;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tracecare.backend.common.response.ApiResponse;
import com.tracecare.backend.common.response.PageResponse;
import com.tracecare.backend.common.response.SuccessCode;
import com.tracecare.backend.common.security.SecurityUtils;
import com.tracecare.backend.domain.location.guardian.dto.response.CurrentLocationResponse;
import com.tracecare.backend.domain.location.guardian.dto.response.LocationHistoryItemResponse;
import com.tracecare.backend.domain.location.guardian.service.LocationQueryService;

/** API_Specification.md §3.3. */
@RestController
@RequestMapping("/api/guardian/location")
public class LocationQueryController {

    private final LocationQueryService locationQueryService;

    public LocationQueryController(LocationQueryService locationQueryService) {
        this.locationQueryService = locationQueryService;
    }

    @GetMapping("/current")
    public ApiResponse<CurrentLocationResponse> getCurrentLocation(
            @RequestParam UUID careTargetId) {
        return ApiResponse.success(
                SuccessCode.LOCATION_001,
                locationQueryService.getCurrentLocation(
                        SecurityUtils.getCurrentUserId(), careTargetId));
    }

    @GetMapping("/history")
    public ApiResponse<PageResponse<LocationHistoryItemResponse>> getHistory(
            @RequestParam UUID careTargetId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            Pageable pageable) {
        return ApiResponse.success(
                SuccessCode.LOCATION_001,
                PageResponse.of(
                        locationQueryService.getHistory(
                                SecurityUtils.getCurrentUserId(),
                                careTargetId,
                                from,
                                to,
                                pageable)));
    }
}
