package com.tracecare.backend.domain.chat.dto.request;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * API_Specification.md §3.6 {@code POST /api/guardian/ai/report/weekly}. {@code /summary}와 달리 기간을
 * 요청자가 지정하지 않는다 — "이번 주"는 항상 요청 시점 기준 최근 7일(rolling)로 고정한다(근거는 {@code AiChatService} Javadoc 참고).
 * 그래서 {@code careTargetId}만 받는다.
 */
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class WeeklyReportRequest {

    @NotBlank private String careTargetId;
}
