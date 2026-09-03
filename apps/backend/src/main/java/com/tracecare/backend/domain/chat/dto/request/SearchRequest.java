package com.tracecare.backend.domain.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * API_Specification.md §3.6 {@code POST /api/guardian/ai/search}. {@code /chat}과 달리 {@code
 * careTargetId}가 필수다(§2 설계 방향 참고). {@code query} 길이 제한은 {@code ChatRequest.message}와 동일한 근거
 * (Security_Guide.md §11.5)로 500자로 맞춘다.
 */
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class SearchRequest {

    @NotBlank private String careTargetId;

    @NotBlank
    @Size(max = 500)
    private String query;
}
