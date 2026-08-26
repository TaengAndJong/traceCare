package com.tracecare.backend.domain.chat.client;

import java.util.List;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.external.AiServerException;

/**
 * Gemini Flash 실연동(2026-08 확정). <b>모델은 Flash 계열만 사용한다</b> — Pro 계열은 무료 키로 거부되므로 절대 쓰지 않는다. {@link
 * #MODEL}은 Google AI for Developers 공식 모델 목록(ai.google.dev/gemini-api/docs/models, 2026-08 확인)에서
 * "최신, 가장 강력한 Flash 모델"로 문서화된 값을 그대로 썼다 — {@code gemini-2.5-flash}는 2026-10 서비스 종료 예정이라 신규 연동에 쓰지
 * 않았고, {@code gemini-flash-latest} 별칭은 실험적 모델을 가리켜 더 낮은 호출 한도가 걸릴 수 있다는 문서 설명이 있어(무료 티어를 보수적으로 쓰라는
 * 이번 세션 요구사항과 배치) 고정된 정식 모델명을 선택했다.
 *
 * <p>429(호출 한도 초과)는 {@code GeminiConfig}에서 이미 SDK 자동 재시도를 껐으므로 이 클래스가 재시도하지 않고 그대로 {@code AI_004}로
 * 변환한다. 그 외 실패는 일반화된 {@code AI_002}로 통일한다(Exception_Handling_Rule.md §9.3).
 */
@Component
public class GeminiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);
    private static final String MODEL = "gemini-3.7-flash";
    private static final int RATE_LIMIT_STATUS = 429;

    private final Client client;

    public GeminiLlmClient(Client geminiClient) {
        this.client = geminiClient;
    }

    @Override
    public String generateAnswer(String systemInstruction, List<Turn> turns) {
        Content systemContent = Content.fromParts(Part.fromText(systemInstruction));
        List<Content> contents =
                turns.stream()
                        .map(
                                turn ->
                                        Content.builder()
                                                .role(turn.role())
                                                .parts(Part.fromText(turn.text()))
                                                .build())
                        .toList();
        GenerateContentConfig config =
                GenerateContentConfig.builder().systemInstruction(systemContent).build();

        try {
            GenerateContentResponse response =
                    client.models.generateContent(MODEL, contents, config);
            String text = response.text();
            if (text == null || text.isBlank()) {
                log.error("event=GEMINI_GENERATE_EMPTY_RESPONSE");
                throw new AiServerException("gemini", ErrorCode.AI_002);
            }
            return text;
        } catch (ApiException e) {
            if (e.code() == RATE_LIMIT_STATUS) {
                log.warn("event=GEMINI_RATE_LIMITED, code={}", e.code());
                throw new AiServerException("gemini", ErrorCode.AI_004);
            }
            log.error("event=GEMINI_GENERATE_FAILED, code={}, status={}", e.code(), e.status(), e);
            throw new AiServerException("gemini", ErrorCode.AI_002);
        }
    }
}
