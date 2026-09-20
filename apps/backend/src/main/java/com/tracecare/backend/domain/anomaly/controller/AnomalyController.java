package com.tracecare.backend.domain.anomaly.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tracecare.backend.common.response.ApiResponse;
import com.tracecare.backend.common.response.PageResponse;
import com.tracecare.backend.common.response.SuccessCode;
import com.tracecare.backend.common.security.CustomUserDetails;
import com.tracecare.backend.domain.anomaly.dto.response.AnomalyEventResponse;
import com.tracecare.backend.domain.anomaly.dto.response.AnomalyExplainResponse;
import com.tracecare.backend.domain.anomaly.dto.response.AnomalyQuestionResponse;
import com.tracecare.backend.domain.anomaly.service.AnomalyService;

/**
 * API_Specification.md §3.9. {@code type}/{@code question}은 값이 없을 때 프레임워크의 파라미터 누락 예외 대신 Service가
 * COMMON_002로 응답하도록 {@code required = false}로 받는다(검증 순서를 Service 한 곳에서 관리하기 위함).
 *
 * <p>{@code /questions}는 {@code /{anomalyEventId}/explain}과 경로 세그먼트 수가 달라 매핑이 충돌하지 않는다.
 */
@RestController
@RequestMapping("/api/guardian/anomalies")
public class AnomalyController {

    private final AnomalyService anomalyService;

    public AnomalyController(AnomalyService anomalyService) {
        this.anomalyService = anomalyService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AnomalyEventResponse>> getAnomalies(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam UUID careTargetId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            Pageable pageable) {
        return ApiResponse.success(
                SuccessCode.ANOMALY_001,
                PageResponse.of(
                        anomalyService.getAnomalies(
                                user.getUserId(), careTargetId, type, from, to, pageable)));
    }

    @GetMapping("/questions")
    public ApiResponse<PageResponse<AnomalyQuestionResponse>> getQuestions(
            @RequestParam(required = false) String type) {
        List<AnomalyQuestionResponse> questions = anomalyService.getQuestions(type);
        return ApiResponse.success(
                SuccessCode.ANOMALY_003, PageResponse.of(new PageImpl<>(questions)));
    }

    @GetMapping("/{anomalyEventId}/explain")
    public ApiResponse<AnomalyExplainResponse> explain(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable Long anomalyEventId,
            @RequestParam(required = false) String question) {
        return ApiResponse.success(
                SuccessCode.ANOMALY_002,
                anomalyService.explain(user.getUserId(), anomalyEventId, question));
    }
}
