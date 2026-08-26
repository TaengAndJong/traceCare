package com.tracecare.backend.domain.prediction.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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
 * AI 서버(FastAPI, 현재는 Stub)가 생성한 방문 예측 결과(DATABASE_DESIGN_GUIDE.md §3.6/§4.6). Derived Data —
 * 원본(VisitHistory/LocationHistory)이 보존돼 있으면 재계산 가능하다. {@code (user_id, prediction_date,
 * predicted_place)} UNIQUE 제약은 배치/캐시 미스 재실행 시 중복 적재를 막기 위함이다 — 이 제약을 믿고 저장 시 별도 존재 확인 없이 INSERT를
 * 시도하되, 위반 시 {@code AiPredictionService}가 DB를 Source of Truth로 다시 읽는다.
 */
@Entity
@Table(name = "PredictionHistory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PredictionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "predicted_place", nullable = false, updatable = false)
    private String predictedPlace;

    @Column(name = "probability", nullable = false, updatable = false)
    private BigDecimal probability;

    @Column(name = "prediction_date", nullable = false, updatable = false)
    private LocalDate predictionDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private PredictionHistory(
            Long userId, String predictedPlace, BigDecimal probability, LocalDate predictionDate) {
        this.userId = userId;
        this.predictedPlace = predictedPlace;
        this.probability = probability;
        this.predictionDate = predictionDate;
        this.createdAt = Instant.now();
    }

    public static PredictionHistory create(
            Long userId, String predictedPlace, BigDecimal probability, LocalDate predictionDate) {
        return new PredictionHistory(userId, predictedPlace, probability, predictionDate);
    }
}
