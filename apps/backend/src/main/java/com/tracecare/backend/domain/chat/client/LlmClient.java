package com.tracecare.backend.domain.chat.client;

import java.util.List;

/**
 * Gemini(Flash 계열) 실연동 경계 — 이번 세션은 Stub 없이 바로 실연동한다({@link GeminiLlmClient}). 자동화 테스트(빌드 시 실행)는 이
 * 인터페이스를 Mockito로 대체해 실제 API를 호출하지 않는다(무료 티어 한도 보호).
 */
public interface LlmClient {

    /**
     * {@code systemInstruction}(고정 지침, 사용자 입력이 절대 섞이지 않음)과 {@code turns}(RAG로 검색된 과거 대화 + 이번 질문,
     * user/model role이 명확히 분리된 메시지 목록)를 받아 답변 텍스트를 반환한다. 사용자 입력을 시스템 프롬프트 문자열에 결합하지 않는 구조 자체가
     * Prompt Injection 방어의 핵심이다(Security_Guide.md §11.5, OWASP §5).
     */
    String generateAnswer(String systemInstruction, List<Turn> turns);

    /** {@code role}은 "user" 또는 "model"(Gemini Content.role 규약과 동일). */
    record Turn(String role, String text) {}
}
