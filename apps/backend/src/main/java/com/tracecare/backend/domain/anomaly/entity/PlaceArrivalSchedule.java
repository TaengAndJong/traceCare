package com.tracecare.backend.domain.anomaly.entity;

import java.time.Instant;
import java.time.LocalTime;

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
 * Guardian이 등록하는 요일별 예상 도착 시각(DATABASE_DESIGN_GUIDE.md §15.1). {@code AnomalyScheduler}가 오늘
 * 요일에 등록된 스케줄 중 아직 도착 기록이 없는 것을 찾아 {@code ARRIVAL_DELAY}를 감지하는 기준 데이터다.
 */
@Entity
@Table(name = "PlaceArrivalSchedule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaceArrivalSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "place_id", nullable = false, updatable = false)
    private Long placeId;

    /** ISO-8601 요일 값(1=월 ~ 7=일), {@link java.time.DayOfWeek#getValue()}와 동일한 규칙. */
    @Column(name = "day_of_week", nullable = false, updatable = false)
    private Integer dayOfWeek;

    @Column(name = "expected_arrival_time", nullable = false)
    private LocalTime expectedArrivalTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private PlaceArrivalSchedule(Long placeId, Integer dayOfWeek, LocalTime expectedArrivalTime) {
        this.placeId = placeId;
        this.dayOfWeek = dayOfWeek;
        this.expectedArrivalTime = expectedArrivalTime;
        this.createdAt = Instant.now();
    }

    public static PlaceArrivalSchedule create(
            Long placeId, Integer dayOfWeek, LocalTime expectedArrivalTime) {
        return new PlaceArrivalSchedule(placeId, dayOfWeek, expectedArrivalTime);
    }

    public void update(LocalTime expectedArrivalTime) {
        this.expectedArrivalTime = expectedArrivalTime;
    }
}
