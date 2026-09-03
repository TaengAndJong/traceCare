package com.tracecare.backend.domain.chat.dto.request;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * API_Specification.md §3.6 {@code POST /api/guardian/ai/summary}. {@code /chat}과 달리 {@code
 * careTargetId}가 필수다 — "요약"은 항상 특정 CareTarget을 대상으로 하는 게 자연스럽기 때문이다. {@code from}/{@code to}는
 * Location Phase 1({@code LocationQueryController#getHistory})의 기간 조회 파라미터 설계와 동일하게 {@code
 * Instant}를 쓴다.
 */
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class SummaryRequest {

    @NotBlank private String careTargetId;

    @NotNull private Instant from;

    @NotNull private Instant to;
}
