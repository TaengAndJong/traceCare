package com.tracecare.backend.domain.anomaly.repository;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tracecare.backend.domain.anomaly.entity.PlaceArrivalSchedule;

public interface PlaceArrivalScheduleRepository
        extends JpaRepository<PlaceArrivalSchedule, Long> {

    List<PlaceArrivalSchedule> findByPlaceIdOrderByDayOfWeekAsc(Long placeId);

    Optional<PlaceArrivalSchedule> findByPlaceIdAndDayOfWeek(Long placeId, Integer dayOfWeek);

    /**
     * {@code AnomalyScheduler} 역할1(ARRIVAL_DELAY 감지)이 오늘 요일에 등록된 스케줄을 청크 단위로 훑는 조회다
     * (DATABASE_DESIGN_GUIDE.md §15.1 — 상용화 대비, {@code anomaly.arrival-delay-scan-page-size}로 청크 크기
     * 조정). {@code idx_pas_day(day_of_week, place_id)} 인덱스가 이 조회를 지원한다 — 기존 {@code
     * uq_pas_place_day(place_id, day_of_week)}는 place_id가 선두라 "오늘 요일" 필터링에는 쓸 수 없어 별도로
     * 추가했다.
     *
     * <p>{@code Place}를 조인해 {@code target_id}(소속 CareTarget)와 PK를 함께 projection으로 가져온다 — 스케줄러가
     * 매 대상마다 Place를 N+1로 재조회하지 않기 위함. {@code Place}는 {@code @SQLRestriction("deleted_at IS
     * NULL")}이 자동 적용되므로 Soft Delete된 장소의 스케줄은 이 조인에서 자연히 제외된다.
     */
    @Query(
            "SELECT pas.id AS scheduleId, p.id AS placeId, p.targetId AS targetId, "
                    + "pas.expectedArrivalTime AS expectedArrivalTime "
                    + "FROM PlaceArrivalSchedule pas JOIN Place p ON p.id = pas.placeId "
                    + "WHERE pas.dayOfWeek = :dayOfWeek "
                    + "ORDER BY pas.id ASC")
    Page<ScheduledPlace> findScheduledByDayOfWeek(
            @Param("dayOfWeek") Integer dayOfWeek, Pageable pageable);

    interface ScheduledPlace {
        Long getScheduleId();

        Long getPlaceId();

        Long getTargetId();

        LocalTime getExpectedArrivalTime();
    }
}
