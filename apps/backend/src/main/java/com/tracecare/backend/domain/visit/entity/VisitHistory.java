package com.tracecare.backend.domain.visit.entity;

import java.math.BigDecimal;
import java.time.Duration;
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
 * GPS 원본을 분석해 만든 "방문 단위" 가공 이력(DATABASE_DESIGN_GUIDE.md §3.5/§4.5). 도착 시 {@link #arrive}로 생성하고, 이탈
 * 시 {@link #depart}로 같은 행에 {@code departureTime}/{@code stayMinutes}를 채운다 — 이력 데이터는 원칙적으로 UPDATE
 * 금지이지만, NotificationHistory의 {@code read_at}처럼 "후속 상태 기록"은 예외로 허용된다(§3.1 원칙).
 *
 * <p>이번 세션(Location Phase 3) 범위는 등록된 Place 반경 진입/이탈 감지까지이므로 {@code isRegisteredPlace}는 항상 {@code
 * true}로 생성한다. 미등록 장소 체류 감지(예: GPS 클러스터링으로 자주 머무는 미등록 장소를 찾아내는 것)는 이번 범위 밖이라 이 엔티티를 통해 생성되는 행은 없다 —
 * 컬럼 자체는 DB 설계에 이미 존재하므로 향후 그 기능이 추가되면 별도 생성 경로만 추가하면 된다.
 */
@Entity
@Table(name = "VisitHistory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "place_id", updatable = false)
    private Long placeId;

    @Column(name = "place_name", updatable = false)
    private String placeName;

    @Column(name = "latitude", nullable = false, updatable = false)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, updatable = false)
    private BigDecimal longitude;

    @Column(name = "arrival_time", nullable = false, updatable = false)
    private Instant arrivalTime;

    @Column(name = "departure_time")
    private Instant departureTime;

    @Column(name = "stay_minutes")
    private Integer stayMinutes;

    @Column(name = "is_registered_place", nullable = false, updatable = false)
    private boolean registeredPlace;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private VisitHistory(
            Long userId,
            Long placeId,
            String placeName,
            BigDecimal latitude,
            BigDecimal longitude,
            Instant arrivalTime,
            boolean registeredPlace) {
        this.userId = userId;
        this.placeId = placeId;
        this.placeName = placeName;
        this.latitude = latitude;
        this.longitude = longitude;
        this.arrivalTime = arrivalTime;
        this.registeredPlace = registeredPlace;
        this.createdAt = Instant.now();
    }

    public static VisitHistory arrive(
            Long userId,
            Long placeId,
            String placeName,
            BigDecimal latitude,
            BigDecimal longitude,
            Instant arrivalTime) {
        return new VisitHistory(userId, placeId, placeName, latitude, longitude, arrivalTime, true);
    }

    public boolean isOpen() {
        return departureTime == null;
    }

    public void depart(Instant departureTime) {
        this.departureTime = departureTime;
        this.stayMinutes = (int) Duration.between(arrivalTime, departureTime).toMinutes();
    }
}
