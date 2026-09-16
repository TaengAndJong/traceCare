package com.tracecare.backend.domain.anomaly.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    /** HYBRID 미승격 이상행동을 {@code /summary}에 포함시키기 위한 조회(§15.2). */
    List<AnomalyEvent> findByUserIdAndDetectedAtBetweenAndEscalatedAtIsNull(
            Long userId, Instant from, Instant to);
}
