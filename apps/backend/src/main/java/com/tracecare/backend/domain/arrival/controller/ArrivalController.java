package com.tracecare.backend.domain.arrival.controller;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tracecare.backend.common.response.ApiResponse;
import com.tracecare.backend.common.response.PageResponse;
import com.tracecare.backend.common.response.SuccessCode;
import com.tracecare.backend.common.security.SecurityUtils;
import com.tracecare.backend.domain.arrival.dto.request.ArrivalCheckRequest;
import com.tracecare.backend.domain.arrival.dto.response.ArrivalCheckResponse;
import com.tracecare.backend.domain.arrival.service.ArrivalService;

/** API_Specification.md §4.2. */
@RestController
@RequestMapping("/api/care-target/arrival")
public class ArrivalController {

    private final ArrivalService arrivalService;

    public ArrivalController(ArrivalService arrivalService) {
        this.arrivalService = arrivalService;
    }

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/check")
    public ApiResponse<ArrivalCheckResponse> checkArrival(
            @Valid @RequestBody ArrivalCheckRequest request) {
        return ApiResponse.success(
                SuccessCode.ARRIVAL_001,
                arrivalService.checkArrival(SecurityUtils.getCurrentUserId(), request));
    }

    @GetMapping("/history")
    public ApiResponse<PageResponse<ArrivalCheckResponse>> getHistory(Pageable pageable) {
        return ApiResponse.success(
                SuccessCode.ARRIVAL_001,
                PageResponse.of(
                        arrivalService.getHistory(SecurityUtils.getCurrentUserId(), pageable)));
    }
}
