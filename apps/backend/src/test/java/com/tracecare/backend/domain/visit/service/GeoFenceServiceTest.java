package com.tracecare.backend.domain.visit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.tracecare.backend.domain.place.repository.PlaceRepository;
import com.tracecare.backend.domain.place.service.PlaceService;
import com.tracecare.backend.domain.visit.entity.VisitHistory;
import com.tracecare.backend.domain.visit.repository.VisitHistoryRepository;

/** GeoFenceService의 역순 GPS 이벤트 방어(evaluate() 진입점의 recordedAt 순서 검증)를 확인한다. */
@ExtendWith(MockitoExtension.class)
class GeoFenceServiceTest {

    private static final Long CARE_TARGET_ID = 1L;

    @Mock private PlaceService placeService;
    @Mock private PlaceRepository placeRepository;
    @Mock private VisitHistoryRepository visitHistoryRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private GeoFenceService geoFenceService() {
        return new GeoFenceService(
                placeService, placeRepository, visitHistoryRepository, eventPublisher);
    }

    @Test
    @DisplayName("현재 열린 방문의 arrivalTime보다 과거인 이벤트는 판정을 건너뛴다")
    void evaluate_staleEventBeforeOpenVisitArrival_skipsJudgement() {
        // given
        Instant arrivalTime = Instant.parse("2026-08-24T05:00:00Z");
        VisitHistory openVisit =
                VisitHistory.arrive(
                        CARE_TARGET_ID,
                        10L,
                        "PlaceA",
                        BigDecimal.valueOf(37.5),
                        BigDecimal.valueOf(127.0),
                        arrivalTime);
        when(visitHistoryRepository.findFirstByUserIdOrderByArrivalTimeDesc(CARE_TARGET_ID))
                .thenReturn(Optional.of(openVisit));

        Instant staleRecordedAt = arrivalTime.minusSeconds(60);

        // when
        geoFenceService().evaluate(CARE_TARGET_ID, 37.5, 127.0, staleRecordedAt);

        // then — Place 조회조차 하지 않고 즉시 반환해야 한다
        verify(placeService, never()).getPlacesForGeofence(any());
        verify(visitHistoryRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("이미 종료된 마지막 방문의 departureTime보다 과거인 이벤트는 판정을 건너뛴다")
    void evaluate_staleEventBeforeLastDeparture_skipsJudgement() {
        // given
        Instant arrivalTime = Instant.parse("2026-08-24T05:00:00Z");
        Instant departureTime = Instant.parse("2026-08-24T05:10:00Z");
        VisitHistory closedVisit =
                VisitHistory.arrive(
                        CARE_TARGET_ID,
                        10L,
                        "PlaceA",
                        BigDecimal.valueOf(37.5),
                        BigDecimal.valueOf(127.0),
                        arrivalTime);
        closedVisit.depart(departureTime);
        when(visitHistoryRepository.findFirstByUserIdOrderByArrivalTimeDesc(CARE_TARGET_ID))
                .thenReturn(Optional.of(closedVisit));

        Instant staleRecordedAt = departureTime.minusSeconds(30);

        // when
        geoFenceService().evaluate(CARE_TARGET_ID, 38.0, 128.0, staleRecordedAt);

        // then
        verify(placeService, never()).getPlacesForGeofence(any());
        verify(visitHistoryRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("정상 순서(과거 기준시각 이후)의 이벤트는 평소대로 판정이 진행된다")
    void evaluate_inOrderEvent_proceedsWithJudgement() {
        // given
        Instant arrivalTime = Instant.parse("2026-08-24T05:00:00Z");
        Instant departureTime = Instant.parse("2026-08-24T05:10:00Z");
        VisitHistory closedVisit =
                VisitHistory.arrive(
                        CARE_TARGET_ID,
                        10L,
                        "PlaceA",
                        BigDecimal.valueOf(37.5),
                        BigDecimal.valueOf(127.0),
                        arrivalTime);
        closedVisit.depart(departureTime);
        when(visitHistoryRepository.findFirstByUserIdOrderByArrivalTimeDesc(CARE_TARGET_ID))
                .thenReturn(Optional.of(closedVisit));
        when(placeService.getPlacesForGeofence(CARE_TARGET_ID)).thenReturn(List.of());

        Instant freshRecordedAt = departureTime.plusSeconds(60);

        // when — 아무 Place에도 매칭되지 않고, 이미 닫힌 방문뿐이므로 depart/arrive 둘 다 발생하지 않아야 정상
        geoFenceService().evaluate(CARE_TARGET_ID, 40.0, 130.0, freshRecordedAt);

        // then — 건너뛰지 않고 Place 목록까지는 조회했는지로 "판정이 진행됐음"을 확인
        verify(placeService, times(1)).getPlacesForGeofence(CARE_TARGET_ID);
        verify(visitHistoryRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("이전 방문 기록이 전혀 없으면(첫 위치 수신) 항상 판정이 진행된다")
    void evaluate_noPriorVisit_alwaysProceeds() {
        // given
        when(visitHistoryRepository.findFirstByUserIdOrderByArrivalTimeDesc(CARE_TARGET_ID))
                .thenReturn(Optional.empty());
        when(placeService.getPlacesForGeofence(CARE_TARGET_ID)).thenReturn(List.of());

        // when
        geoFenceService()
                .evaluate(CARE_TARGET_ID, 37.5, 127.0, Instant.parse("2020-01-01T00:00:00Z"));

        // then
        verify(placeService, times(1)).getPlacesForGeofence(CARE_TARGET_ID);
    }
}
