package com.tracecare.backend.domain.anomaly.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.anomaly.service.AnomalyQuestionKey;

/** API_Specification.md §3.9 {@code GET /api/guardian/anomalies/questions}의 {@code content} 항목. */
@Getter
@Builder
@AllArgsConstructor
public class AnomalyQuestionResponse {

    private String questionKey;
    private String text;

    /** @param detectMinutesText 감지 기준 시간의 문장용 표기(예: "10분") — 문구의 자리표시자에 채워진다. */
    public static AnomalyQuestionResponse of(AnomalyQuestionKey key, String detectMinutesText) {
        return AnomalyQuestionResponse.builder()
                .questionKey(key.name())
                .text(key.render(detectMinutesText))
                .build();
    }
}
