package com.tracecare.backend.domain.anomaly.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tracecare.backend.domain.anomaly.entity.PlaceArrivalSchedule;

public interface PlaceArrivalScheduleRepository
        extends JpaRepository<PlaceArrivalSchedule, Long> {

    List<PlaceArrivalSchedule> findByPlaceIdOrderByDayOfWeekAsc(Long placeId);

    Optional<PlaceArrivalSchedule> findByPlaceIdAndDayOfWeek(Long placeId, Integer dayOfWeek);

    /**
     * {@code AnomalyScheduler}가 오늘 요일에 등록된 스케줄 전체를 훑는 조회다. {@code Place}를 조인해 소속
     * CareTarget({@code target_id})과 장소명을 함께 가져온다 — 스케줄러가 매 tick마다 Place를 N+1로 재조회하지 않기 위함.
     */
    @Query(
            "SELECT pas FROM PlaceArrivalSchedule pas "
                    + "JOIN Place p ON p.id = pas.placeId "
                    + "WHERE pas.dayOfWeek = :dayOfWeek")
    List<PlaceArrivalSchedule> findAllByDayOfWeek(@Param("dayOfWeek") Integer dayOfWeek);
}
