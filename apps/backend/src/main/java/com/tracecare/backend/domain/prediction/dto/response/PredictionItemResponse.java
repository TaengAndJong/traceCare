package com.tracecare.backend.domain.prediction.dto.response;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import com.tracecare.backend.domain.prediction.entity.PredictionHistory;

/**
 * API_Specification.md §3.5 — {@code /predict}, {@code /predict/report}가 공유하는 항목 하나. {@code
 * AiPredictionService.PredictionCache}로 감싸져 Redis 캐시 값으로도 저장되므로, Jackson이 역직렬화할 때 쓸 생성자가 필요해
 * {@code @Jacksonized}를 붙인다(PlaceResponse와 동일한 이유 — 발견/수정: 이 어노테이션 없이 캐시에 저장했다가 다시 읽을 때 "no
 * Creators" SerializationException으로 항상 실패해 매 요청이 DB 폴백만 타고 있었다).
 */
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class PredictionItemResponse {

    private String placeName;
    private BigDecimal probability;

    public static PredictionItemResponse of(PredictionHistory history) {
        return PredictionItemResponse.builder()
                .placeName(history.getPredictedPlace())
                .probability(history.getProbability())
                .build();
    }
}
