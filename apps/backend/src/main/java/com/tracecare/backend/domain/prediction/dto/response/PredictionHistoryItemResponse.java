package com.tracecare.backend.domain.prediction.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.prediction.entity.PredictionHistory;

/**
 * API_Specification.md §3.5 {@code GET /api/guardian/ai/history} — 여러 날짜에 걸친 이력이라 predictionDate를
 * 포함한다.
 */
@Getter
@Builder
@AllArgsConstructor
public class PredictionHistoryItemResponse {

    private String placeName;
    private BigDecimal probability;
    private LocalDate predictionDate;

    public static PredictionHistoryItemResponse of(PredictionHistory history) {
        return PredictionHistoryItemResponse.builder()
                .placeName(history.getPredictedPlace())
                .probability(history.getProbability())
                .predictionDate(history.getPredictionDate())
                .build();
    }
}
