package com.tracecare.backend.domain.anomaly.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tracecare.backend.domain.anomaly.entity.AnomalyEvent;

public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {

    /** §1.6 목록 조회(GET /api/guardian/ai/anomaly) — idx_ae_user_type_detected 그대로 사용. */
    List<AnomalyEvent> findByUserIdAndTypeAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(
            Long userId, String type, Instant from);

    /** /explain "최근 N일 발생 횟수" 집계 질문 전용 — 목록 전체가 아니라 건수만 필요하다. */
    long countByUserIdAndTypeAndDetectedAtGreaterThanEqual(Long userId, String type, Instant from);

    /**
     * {@code UnregisteredStayDetector}/{@code GeoFenceService}가 "이 CareTarget이 지금 진행 중인
     * 이상행동이 있는지" 확인할 때 캐시 미스 시 DB 폴백으로 쓴다({@code anomaly:active:{careTargetId}} 캐시,
     * Cache_Strategy_Guide.md §3.2).
     */
    Optional<AnomalyEvent> findFirstByUserIdAndTypeAndResolvedAtIsNullOrderByDetectedAtDesc(
            Long userId, String type);

    /**
     * {@code AnomalyScheduler} 역할2(승격) — 진행 중인 이벤트 전체를 CareTarget 구분 없이 스캔한다. {@code
     * escalatedAt IS NULL}로 필터링하지 않는다 — §4.2 확정(A): 같은 이벤트에 Guardian이 여러 명이고 각자
     * escalate_minutes가 다르면, 한 Guardian이 먼저 승격됐다고 다른 Guardian(더 긴 임계값)을 스캔에서 제외하면 안 되기
     * 때문이다(Guardian 개인별 자율성 원칙 우선, 성능 비용은 감수). {@code idx_ae_open_unescalated(escalated_at)
     * WHERE resolved_at IS NULL} 파티얼 인덱스의 WHERE 절과 이 쿼리의 조건이 정확히 일치해 그대로 재사용된다(인덱스가
     * "미승격"이 아니라 "진행 중" 전체를 커버하는 셈).
     */
    List<AnomalyEvent> findByResolvedAtIsNull();

    /** {@code AnomalyScheduler} 역할1 — 오늘 이미 같은 Place에 대해 생성된 ARRIVAL_DELAY가 있는지(중복 생성 방지). */
    @Query(
            "SELECT ae FROM AnomalyEvent ae "
                    + "WHERE ae.userId = :userId AND ae.placeId = :placeId "
                    + "AND ae.type = 'ARRIVAL_DELAY' AND ae.resolvedAt IS NULL")
    Optional<AnomalyEvent> findOpenArrivalDelay(
            @Param("userId") Long userId, @Param("placeId") Long placeId);

    /**
     * §3.9 목록 조회(유형 필터 없음) — {@code idx_ae_user_type_detected}의 {@code user_id} 접두사 + {@code detected_at}
     * 범위를 타고 정렬은 별도 Sort로 수행한다(유형이 중간 컬럼이라 인덱스 순서를 그대로 못 쓰지만 CareTarget당 건수가 적어 감수).
     */
    Page<AnomalyEvent> findByUserIdAndDetectedAtBetweenOrderByDetectedAtDesc(
            Long userId, Instant from, Instant to, Pageable pageable);

    /** §3.9 목록 조회(유형 필터) — {@code idx_ae_user_type_detected}를 정렬까지 그대로 사용한다. */
    Page<AnomalyEvent> findByUserIdAndTypeAndDetectedAtBetweenOrderByDetectedAtDesc(
            Long userId, String type, Instant from, Instant to, Pageable pageable);

    /** {@code DELAY_COUNT_7D}/{@code DELAY_COUNT_30D} — 같은 CareTarget·같은 장소의 유형별 발생 횟수. */
    long countByUserIdAndTypeAndPlaceIdAndDetectedAtGreaterThanEqual(
            Long userId, String type, Long placeId, Instant from);

    /**
     * {@code STAY_SIMILAR_PAST} — 자기 자신을 제외한 과거 {@code UNREGISTERED_STAY} 이벤트 중 좌표가 위경도 범위(Bounding
     * Box) 안인 후보. PostGIS 없이 좌표 인덱스도 없으므로 {@code (user_id, type, detected_at)} 접두사로 범위를 좁힌 뒤 좌표를
     * 필터링하고, 호출부가 Haversine({@code GeoDistanceCalculator})으로 실제 반경을 재계산한다(DATABASE_DESIGN_GUIDE.md §15.2/§15.7).
     */
    @Query(
            "SELECT ae FROM AnomalyEvent ae "
                    + "WHERE ae.userId = :userId AND ae.type = 'UNREGISTERED_STAY' "
                    + "AND ae.id <> :excludeId AND ae.detectedAt >= :from "
                    + "AND ae.latitude BETWEEN :minLat AND :maxLat "
                    + "AND ae.longitude BETWEEN :minLng AND :maxLng "
                    + "ORDER BY ae.detectedAt DESC")
    List<AnomalyEvent> findSimilarUnregisteredStayCandidates(
            @Param("userId") Long userId,
            @Param("excludeId") Long excludeId,
            @Param("from") Instant from,
            @Param("minLat") BigDecimal minLat,
            @Param("maxLat") BigDecimal maxLat,
            @Param("minLng") BigDecimal minLng,
            @Param("maxLng") BigDecimal maxLng);

    /**
     * {@code /summary}·{@code /report/weekly} 기간 내 이상행동(§3.6) — 기간과 <b>겹치는</b> 이벤트: {@code detected_at <= to}
     * 이고 ({@code resolved_at IS NULL} 또는 {@code resolved_at >= from}). 기간 이전에 감지돼 지금도 진행 중인 이벤트를 포함한다.
     * 진행 중(미해소) 우선, 그다음 {@code detected_at} 내림차순. 상한은 {@code Pageable}로 건다.
     */
    @Query(
            "SELECT ae FROM AnomalyEvent ae "
                    + "WHERE ae.userId = :userId AND ae.detectedAt <= :to "
                    + "AND (ae.resolvedAt IS NULL OR ae.resolvedAt >= :from) "
                    + "ORDER BY CASE WHEN ae.resolvedAt IS NULL THEN 0 ELSE 1 END, ae.detectedAt DESC")
    List<AnomalyEvent> findOverlapping(
            @Param("userId") Long userId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /** {@link #findOverlapping}과 같은 겹침 조건의 총 건수({@code anomalyCount}). */
    @Query(
            "SELECT COUNT(ae) FROM AnomalyEvent ae "
                    + "WHERE ae.userId = :userId AND ae.detectedAt <= :to "
                    + "AND (ae.resolvedAt IS NULL OR ae.resolvedAt >= :from)")
    long countOverlapping(
            @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);
}
