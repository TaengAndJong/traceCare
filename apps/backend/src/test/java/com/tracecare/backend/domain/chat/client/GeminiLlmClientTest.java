package com.tracecare.backend.domain.chat.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.external.AiServerException;

/**
 * SDK 예외 → {@code ErrorCode} 변환 로직만 단위 테스트한다. {@code Client}/{@code Models}는 SDK 쪽에서 {@code
 * final}이라 Mockito로 {@code generateContent} 호출 자체를 실패시키는 테스트를 만들 수 없어(모의 객체는 생성자를 타지 않아 {@code
 * client.models}가 항상 {@code null}), {@code mapApiException}/{@code mapIoException}만 직접 호출한다({@code
 * client} 필드는 이 두 메서드에서 쓰이지 않으므로 {@code null}로 생성해도 안전하다).
 */
class GeminiLlmClientTest {

    private final GeminiLlmClient client = new GeminiLlmClient(null);

    @Test
    @DisplayName("ApiException(429)는 AI_004로 변환한다")
    void mapApiException_withRateLimitCode_returnsAi004() {
        AiServerException result =
                client.mapApiException(new ApiException(429, "RESOURCE_EXHAUSTED", "rate limited"));

        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.AI_004);
    }

    @Test
    @DisplayName("ApiException(429 이외)은 AI_002로 변환한다 — 기존 동작 회귀 없음")
    void mapApiException_withOtherCode_returnsAi002() {
        AiServerException result =
                client.mapApiException(new ApiException(500, "INTERNAL", "boom"));

        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.AI_002);
    }

    @Test
    @DisplayName("GenAiIOException(네트워크 타임아웃 등)은 COMMON_001이 아니라 AI_002로 변환한다 — 버그 수정 회귀 테스트")
    void mapIoException_returnsAi002NotGenericError() {
        AiServerException result = client.mapIoException(new GenAiIOException("connect timeout"));

        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.AI_002);
    }
}
