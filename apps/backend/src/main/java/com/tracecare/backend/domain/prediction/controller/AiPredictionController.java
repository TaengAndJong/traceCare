package com.tracecare.backend.domain.prediction.controller;

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
import com.tracecare.backend.domain.prediction.dto.response.PredictReportResponse;
import com.tracecare.backend.domain.prediction.dto.response.PredictResponse;
import com.tracecare.backend.domain.prediction.dto.response.PredictionHistoryItemResponse;
import com.tracecare.backend.domain.prediction.service.AiPredictionService;

/** API_Specification.md §3.5. */
@RestController
@RequestMapping("/api/guardian/ai")
public class AiPredictionController {

    private final AiPredictionService aiPredictionService;

    public AiPredictionController(AiPredictionService aiPredictionService) {
        this.aiPredictionService = aiPredictionService;
    }

    @GetMapping("/predict")
    public ApiResponse<PredictResponse> predict(@RequestParam UUID careTargetId) {
        return ApiResponse.success(
                SuccessCode.AI_001,
                aiPredictionService.predict(SecurityUtils.getCurrentUserId(), careTargetId));
    }

    @GetMapping("/predict/report")
    public ApiResponse<PredictReportResponse> predictReport(@RequestParam UUID careTargetId) {
        return ApiResponse.success(
                SuccessCode.AI_001,
                aiPredictionService.predictReport(SecurityUtils.getCurrentUserId(), careTargetId));
    }

    @GetMapping("/history")
    public ApiResponse<PageResponse<PredictionHistoryItemResponse>> getHistory(
            @RequestParam UUID careTargetId, Pageable pageable) {
        return ApiResponse.success(
                SuccessCode.AI_001,
                PageResponse.of(
                        aiPredictionService.getHistory(
                                SecurityUtils.getCurrentUserId(), careTargetId, pageable)));
    }
}
