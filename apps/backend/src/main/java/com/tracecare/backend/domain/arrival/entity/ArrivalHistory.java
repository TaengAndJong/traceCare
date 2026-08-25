package com.tracecare.backend.domain.arrival.entity;

import java.math.BigDecimal;
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
 * CareTarget이 능동적으로 확인한 등록 장소 도착 기록(DATABASE_DESIGN_GUIDE.md §3.10/§4.10). 백그라운드에서 GeoFence 판정이
 * 자동으로 만드는 {@code VisitHistory}와는 완전히 별개의 기록이다 — 서로 참조하지 않는다(설계 가이드 §3.10 근거). {@code place_id}는
 * VisitHistory와 달리 항상 NOT NULL이다(등록 Place를 명시적으로 골라 호출하는 행위라 "미등록 장소" 개념이 없음).
 */
@Entity
@Table(name = "ArrivalHistory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ArrivalHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "place_id", nullable = false, updatable = false)
    private Long placeId;

    @Column(name = "place_name", nullable = false, updatable = false)
    private String placeName;

    @Column(name = "latitude", nullable = false, updatable = false)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, updatable = false)
    private BigDecimal longitude;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    private ArrivalHistory(
            Long userId,
            Long placeId,
            String placeName,
            BigDecimal latitude,
            BigDecimal longitude) {
        this.userId = userId;
        this.placeId = placeId;
        this.placeName = placeName;
        this.latitude = latitude;
        this.longitude = longitude;
        this.confirmedAt = Instant.now();
    }

    public static ArrivalHistory confirm(
            Long userId,
            Long placeId,
            String placeName,
            BigDecimal latitude,
            BigDecimal longitude) {
        return new ArrivalHistory(userId, placeId, placeName, latitude, longitude);
    }
}
