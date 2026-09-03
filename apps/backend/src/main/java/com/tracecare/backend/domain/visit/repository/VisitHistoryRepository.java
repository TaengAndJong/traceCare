package com.tracecare.backend.domain.visit.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * AI 케어 비서 이동 요약/검색(§3.6 `/summary`, `/search`)이 기간 내 방문 전체를 집계·LLM 컨텍스트로 넘기는 데 쓴다. 위 페이지 버전과
     * 달리 페이징하지 않는 이유는, "이 기간 방문 몇 건"이라는 집계 자체가 목적이라 일부만 가져오면 집계가 틀어지기 때문이다 — 호출부( {@code
     * AiChatService})가 자체적으로 상한(MAX_CANDIDATES)을 적용해 LLM 토큰 비용을 제어한다.
     */
    List<VisitHistory> findByUserIdAndArrivalTimeBetweenOrderByArrivalTimeDesc(
            Long userId, Instant from, Instant to);

    Page<VisitHistory> findByUserIdAndPlaceIdOrderByArrivalTimeDesc(
            Long userId, Long placeId, Pageable pageable);

    /** AI 방문 예측 Stub(StubAiPredictionClient)이 "학습 데이터 충분한지" 판단하는 기준으로 쓴다. */
    long countByUserId(Long userId);

    /**
     * 등록 장소 방문만 대상으로 방문 빈도 상위 N개를 집계한다 — 진짜 ML은 아니지만 "가장 많이 방문한 장소일수록 오늘도 갈 확률이 높다"는 단순 규칙으로 AI 예측을
     * 흉내 낸다(StubAiPredictionClient 참고). {@code Pageable}로 상위 N개만 제한한다.
     */
    @Query(
            "SELECT v.placeName AS placeName, COUNT(v) AS visitCount FROM VisitHistory v "
                    + "WHERE v.userId = :userId AND v.registeredPlace = true "
                    + "GROUP BY v.placeName ORDER BY COUNT(v) DESC")
    List<PlaceFrequency> findTopVisitedPlaces(@Param("userId") Long userId, Pageable pageable);

    interface PlaceFrequency {
        String getPlaceName();

        Long getVisitCount();
    }
}
