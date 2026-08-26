package com.tracecare.backend.domain.prediction.dto.response;

import java.time.LocalDate;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** API_Specification.md §3.5 {@code GET /api/guardian/ai/predict}. */
@Getter
@Builder
@AllArgsConstructor
public class PredictResponse {

    private String careTargetId;
    private LocalDate predictionDate;
    private List<PredictionItemResponse> predictions;
}
