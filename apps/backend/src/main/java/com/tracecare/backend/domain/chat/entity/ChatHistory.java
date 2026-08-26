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
 * AI 케어 비서 대화 원문(DATABASE_DESIGN_GUIDE.md §4.8). {@code user_id}는 대화의 소유자 — 이 질문을 한 Guardian 본인이다.
 * {@code target_id}는 이 대화가 다루는 CareTarget(User) 참조로, Place.target_id와 동일한 이유의 설계 당시 누락분을 보완한
 * 컬럼이다(2026-08) — 특정 CareTarget을 언급하지 않는 일반 대화는 {@code NULL}. Embedding 벡터는 {@code ChatEmbedding}에
 * 별도 보관하며(1:1), 이 엔티티는 벡터를 매핑하지 않는다 — 유사도 검색은 JPQL로 표현할 수 없어 raw JDBC({@code ChatEmbeddingStore})로
 * 처리한다.
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

    @Column(name = "target_id", updatable = false)
    private Long targetId;

    @Column(name = "question", nullable = false, updatable = false)
    private String question;

    @Column(name = "answer", nullable = false, updatable = false)
    private String answer;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private ChatHistory(Long userId, Long targetId, String question, String answer) {
        this.userId = userId;
        this.targetId = targetId;
        this.question = question;
        this.answer = answer;
        this.createdAt = Instant.now();
    }

    public static ChatHistory create(Long userId, Long targetId, String question, String answer) {
        return new ChatHistory(userId, targetId, question, answer);
    }
}
