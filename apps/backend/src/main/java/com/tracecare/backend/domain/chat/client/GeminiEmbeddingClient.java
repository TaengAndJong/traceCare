package com.tracecare.backend.domain.chat.client;

import java.util.List;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.external.AiServerException;

/**
 * Gemini Embedding 실연동. 모델/차원은 DATABASE_DESIGN_GUIDE.md §3.9/§4.9가 이미 확정한 {@code
 * gemini-embedding-001}, 768차원을 그대로 쓴다 — 2026-08 기준 공식 모델 목록에서도 여전히 활성 상태로 확인해 (deprecated 아님) 별도
 * 변경 없이 그대로 재사용했다.
 */
@Component
public class GeminiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingClient.class);
    private static final String MODEL = "gemini-embedding-001";
    private static final int OUTPUT_DIMENSIONALITY = 768;
    private static final int RATE_LIMIT_STATUS = 429;

    private final Client client;

    public GeminiEmbeddingClient(Client geminiClient) {
        this.client = geminiClient;
    }

    @Override
    public float[] embed(String text) {
        EmbedContentConfig config =
                EmbedContentConfig.builder().outputDimensionality(OUTPUT_DIMENSIONALITY).build();
        try {
            EmbedContentResponse response = client.models.embedContent(MODEL, text, config);
            List<ContentEmbedding> embeddings = response.embeddings().orElseThrow();
            List<Float> values = embeddings.get(0).values().orElseThrow();
            float[] result = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                result[i] = values.get(i);
            }
            return result;
        } catch (ApiException e) {
            if (e.code() == RATE_LIMIT_STATUS) {
                log.warn("event=GEMINI_EMBED_RATE_LIMITED, code={}", e.code());
                throw new AiServerException("gemini", ErrorCode.AI_004);
            }
            log.error("event=GEMINI_EMBED_FAILED, code={}, status={}", e.code(), e.status(), e);
            throw new AiServerException("gemini", ErrorCode.AI_002);
        }
    }
}
