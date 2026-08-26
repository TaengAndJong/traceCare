package com.tracecare.backend.domain.chat.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 케어 비서 대화 원문(DATABASE_DESIGN_GUIDE.md §4.8). {@code user_id}는 대화의 소유자 — 이 질문을 한 Guardian
 * 본인이다(CareTarget별로 나뉘지 않는다). 질문이 특정 CareTarget을 다루는지는 요청 시점에만 쓰이는 정보라 이 테이블에 저장하지 않는다({@code
 * AiChatService} 참고) — 컬럼 자체가 이 테이블에 없어(스키마 확인 완료) Guardian 단위로 대화가 쌓이는 설계임을 확정할 수 있었다. Embedding
 * 벡터는 {@code ChatEmbedding}에 별도 보관하며(1:1), 이 엔티티는 벡터를 매핑하지 않는다 — 유사도 검색은 JPQL로 표현할 수 없어 raw
 * JDBC({@code ChatEmbeddingStore})로 처리한다.
 */
@Entity
@Table(name = "ChatHistory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "question", nullable = false, updatable = false)
    private String question;

    @Column(name = "answer", nullable = false, updatable = false)
    private String answer;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private ChatHistory(Long userId, String question, String answer) {
        this.userId = userId;
        this.question = question;
        this.answer = answer;
        this.createdAt = Instant.now();
    }

    public static ChatHistory create(Long userId, String question, String answer) {
        return new ChatHistory(userId, question, answer);
    }
}
