package com.tracecare.backend.domain.visit.controller;

import java.time.LocalDate;
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
import com.tracecare.backend.domain.visit.dto.response.VisitHistoryResponse;
import com.tracecare.backend.domain.visit.service.VisitHistoryQueryService;

/** API_Specification.md §3.4. */
@RestController
@RequestMapping("/api/guardian/history")
public class VisitHistoryController {

    private final VisitHistoryQueryService visitHistoryQueryService;

    public VisitHistoryController(VisitHistoryQueryService visitHistoryQueryService) {
        this.visitHistoryQueryService = visitHistoryQueryService;
    }

    @GetMapping("/today")
    public ApiResponse<PageResponse<VisitHistoryResponse>> getToday(
            @RequestParam UUID careTargetId, Pageable pageable) {
        return ApiResponse.success(
                SuccessCode.VISIT_001,
                PageResponse.of(
                        visitHistoryQueryService.getToday(
                                SecurityUtils.getCurrentUserId(), careTargetId, pageable)));
    }

    @GetMapping("/date")
    public ApiResponse<PageResponse<VisitHistoryResponse>> getByDate(
            @RequestParam UUID careTargetId, @RequestParam LocalDate date, Pageable pageable) {
        return ApiResponse.success(
                SuccessCode.VISIT_001,
                PageResponse.of(
                        visitHistoryQueryService.getByDate(
                                SecurityUtils.getCurrentUserId(), careTargetId, date, pageable)));
    }

    @GetMapping("/place")
    public ApiResponse<PageResponse<VisitHistoryResponse>> getByPlace(
            @RequestParam UUID careTargetId, @RequestParam UUID placeId, Pageable pageable) {
        return ApiResponse.success(
                SuccessCode.VISIT_001,
                PageResponse.of(
                        visitHistoryQueryService.getByPlace(
                                SecurityUtils.getCurrentUserId(),
                                careTargetId,
                                placeId,
                                pageable)));
    }
}
