package com.tracecare.backend.domain.chat.client;

/** Gemini Embedding(gemini-embedding-001, 768차원) 실연동 경계. */
public interface EmbeddingClient {

    float[] embed(String text);
}
