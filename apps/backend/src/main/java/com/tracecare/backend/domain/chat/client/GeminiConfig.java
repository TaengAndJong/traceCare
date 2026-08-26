package com.tracecare.backend.domain.chat.client;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gemini Developer API 공식 Java SDK({@code com.google.genai:google-genai}, GA 2025-05) 클라이언트 Bean.
 * GoogleIdTokenVerifier(Security_Guide.md §6.1)와 마찬가지로 API 키/타임아웃 설정을 한곳에 모은다.
 *
 * <p><b>재시도 비활성화(필수)</b>: 무료 티어 한도 초과(429) 상황에서 SDK 기본 자동 재시도(408/429/500/502/503/504)가 켜져 있으면 한도를
 * 더 빨리 소진시킨다 — {@code attempts(1)}로 재시도를 끄고 그대로 {@code ApiException}을 던지게 한다 (이 세션의 명시적 요구사항).
 *
 * <p><b>Timeout 설정의 알려진 한계</b>: {@code HttpOptions.timeout()}을 설정하지만, google-genai 1.8.0 이후 내부 HTTP
 * 클라이언트가 Apache HttpClient에서 OkHttp로 바뀌면서 이 설정이 실제로 적용되지 않는 것으로 보이는 이슈가 SDK 저장소에 열려
 * 있다(googleapis/java-genai#520, 2026-08 기준 미해결). 코드상 의도(Security_Guide.md §11.4의 connect/read
 * timeout 필수 원칙)는 명시해두되, 실제로 무한 대기가 발생하는지는 이번 세션에서 실측 검증하지 않았다 — 결과 보고에 한계로 남긴다.
 */
@Configuration
public class GeminiConfig {

    private static final int TIMEOUT_MILLIS = 15000;

    @Bean
    public Client geminiClient(@Value("${gemini.api-key}") String apiKey) {
        HttpOptions httpOptions =
                HttpOptions.builder()
                        .timeout(TIMEOUT_MILLIS)
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                        .build();
        return Client.builder().apiKey(apiKey).httpOptions(httpOptions).build();
    }
}
