package com.tracecare.backend.domain.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * API_Specification.md §3.6 {@code POST /api/guardian/ai/chat}. {@code careTargetId}(CareTarget의
 * {@code public_id})는 선택 — 제공되면 호출자가 해당 CareTarget의 ACTIVE Guardian인지 검증한 뒤 {@code
 * ChatHistory.target_id}에 저장되고, 없으면 {@code target_id}는 {@code NULL}(일반 대화)로 저장된다. RAG 검색 범위도 이 값에
 * 따라 CareTarget 단위로 분리된다({@code AiChatService} Javadoc 참고). {@code message} 길이 제한은
 * Security_Guide.md §11.5(Prompt Injection 방어 — 길이 제한)를 따른다.
 */
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class ChatRequest {

    @NotBlank
    @Size(max = 500)
    private String message;

    private String careTargetId;
}
