package com.tracecare.backend.domain.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * API_Specification.md §3.6 {@code POST /api/guardian/ai/chat}. {@code careTargetId}는 선택 — 문서에 이
 * 필드가 명시돼 있지 않고 {@code ChatHistory}도 Guardian 단위로만 저장되지만(스키마 확인, {@code ChatHistory} Javadoc 참고),
 * Guardian이 여러 CareTarget을 관리할 수 있어 "이 질문이 특정 CareTarget에 대한 것"임을 알려줄 방법이 없으면 위치 기반 질문에 답할 수 없다고
 * 판단해 선택 필드로 뒀다 — 제공되면 ACTIVE 관계를 검증하고, 없으면 CareTarget 특정 없이 일반 대화로 처리한다(근거는 결과 보고 참고). {@code
 * message} 길이 제한은 Security_Guide.md §11.5(Prompt Injection 방어 — 길이 제한)를 따른다.
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
