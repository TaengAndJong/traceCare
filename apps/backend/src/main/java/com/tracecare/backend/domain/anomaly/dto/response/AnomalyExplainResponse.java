package com.tracecare.backend.domain.anomaly.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** API_Specification.md §3.9 {@code /explain} 응답 — 질문 종류와 무관하게 항상 {@code answer} 문자열 하나. */
@Getter
@Builder
@AllArgsConstructor
public class AnomalyExplainResponse {

    private String answer;
}
