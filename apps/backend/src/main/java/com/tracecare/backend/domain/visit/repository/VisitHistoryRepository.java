package com.tracecare.backend.domain.visit.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.tracecare.backend.domain.visit.entity.VisitHistory;

public interface VisitHistoryRepository extends JpaRepository<VisitHistory, Long> {

    /**
     * 이 CareTarget의 "현재 열려 있는 방문" 판단에 쓴다. {@code departure_time IS NULL}로 별도 필터링하지 않고 가장 최근 행 하나만
     * 가져와 애플리케이션에서 {@link VisitHistory#isOpen()}을 확인하는 이유는, 기존 인덱스 {@code idx_vh_user_arrival
     * (user_id, arrival_time DESC)}를 그대로 재사용할 수 있기 때문이다 — 매 위치 수신마다 실행되는 조회라 새 인덱스를 추가하는 대신 기존 인덱스로
     * 충분한 이 방식을 택했다(GeoFenceService 참고).
     */
    Optional<VisitHistory> findFirstByUserIdOrderByArrivalTimeDesc(Long userId);

    Page<VisitHistory> findByUserIdAndArrivalTimeBetweenOrderByArrivalTimeDesc(
            Long userId, Instant from, Instant to, Pageable pageable);

    Page<VisitHistory> findByUserIdAndPlaceIdOrderByArrivalTimeDesc(
            Long userId, Long placeId, Pageable pageable);
}
