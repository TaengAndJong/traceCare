package com.tracecare.backend.domain.anomaly.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.domain.anomaly.dto.response.SummaryAnomalyResponse;
import com.tracecare.backend.domain.anomaly.entity.AnomalyEvent;
import com.tracecare.backend.domain.anomaly.repository.AnomalyEventRepository;
import com.tracecare.backend.domain.notification.entity.NotificationHistory;
import com.tracecare.backend.domain.notification.repository.NotificationHistoryRepository;
import com.tracecare.backend.domain.place.entity.Place;
import com.tracecare.backend.domain.place.repository.PlaceRepository;

/**
 * {@code /summary}·{@code /report/weekly}에 붙일 기간 내 이상행동 조회(API_Specification.md §3.6, DATABASE_DESIGN_GUIDE.md
 * §15.5).
 *
 * <p><b>호출자 책임</b>: 이 컴포넌트는 소유권 검증을 하지 않는다 — 호출자({@code AiChatService})가 이미 "요청자가 이 CareTarget의
 * ACTIVE Guardian"임을 검증한 뒤에만 호출해야 한다(Security_Guide.md §4.5의 3단계 중 Service 계층 검증은 호출부에 있다).
 *
 * <p><b>알림 모드/일시정지와 무관</b>: {@code GuardianTarget}의 알림 모드를 읽지 않는다. 기간에 겹치는 이상행동을 항상 전부
 * 노출한다(모드는 푸시 수신 방식일 뿐 조회 범위가 아니다).
 *
 * <p><b>기간 기준(겹침)</b>: {@code detected_at ≤ to AND (resolved_at IS NULL OR resolved_at ≥ from)} — 기간 이전에 감지돼 지금도
 * 진행 중인 이상행동을 포함한다.
 */
@Service
public class AnomalySummaryQueryService {

    /** 응답에 싣는 최대 항목 수. 초과분은 {@link Result#totalCount()}로만 알린다. */
    static final int MAX_ITEMS = 20;

    private final AnomalyEventRepository anomalyEventRepository;
    private final NotificationHistoryRepository notificationHistoryRepository;
    private final PlaceRepository placeRepository;

    public AnomalySummaryQueryService(
            AnomalyEventRepository anomalyEventRepository,
            NotificationHistoryRepository notificationHistoryRepository,
            PlaceRepository placeRepository) {
        this.anomalyEventRepository = anomalyEventRepository;
        this.notificationHistoryRepository = notificationHistoryRepository;
        this.placeRepository = placeRepository;
    }

    /** @param totalCount 기간에 걸친 이상행동 총 건수(상한 {@value #MAX_ITEMS}과 무관) */
    public record Result(long totalCount, List<SummaryAnomalyResponse> items) {}

    /**
     * 조회는 이벤트 1회 + 건수 1회 + Place IN 1회 + 발송 이력 IN 1회로 끝난다(항목 수와 무관, N+1 없음).
     *
     * @param guardianId 요청한 Guardian(내부 PK) — {@code notified} 판별 기준
     * @param targetId 대상 CareTarget(내부 PK)
     */
    @Transactional(readOnly = true)
    public Result find(Long guardianId, Long targetId, Instant from, Instant to) {
        long totalCount = anomalyEventRepository.countOverlapping(targetId, from, to);
        if (totalCount == 0) {
            return new Result(0, List.of());
        }

        List<AnomalyEvent> events =
                anomalyEventRepository.findOverlapping(
                        targetId, from, to, PageRequest.of(0, MAX_ITEMS));
        Map<Long, Place> placesById = loadPlaces(events);
        Set<Long> notifiedIds = loadNotifiedIds(guardianId, events);

        List<SummaryAnomalyResponse> items =
                events.stream()
                        .map(
                                e ->
                                        SummaryAnomalyResponse.of(
                                                e,
                                                placesById.get(e.getPlaceId()),
                                                notifiedIds.contains(e.getId())))
                        .toList();
        return new Result(totalCount, items);
    }

    private Map<Long, Place> loadPlaces(List<AnomalyEvent> events) {
        List<Long> placeIds =
                events.stream().map(AnomalyEvent::getPlaceId).filter(id -> id != null).distinct().toList();
        if (placeIds.isEmpty()) {
            // Map.of()는 get(null)이 NPE라 쓰지 않는다 — place_id가 null인 이벤트(UNREGISTERED_STAY 등)를 조회하기 때문이다.
            return new HashMap<>();
        }
        return placeRepository.findAllById(placeIds).stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));
    }

    private Set<Long> loadNotifiedIds(Long guardianId, List<AnomalyEvent> events) {
        List<Long> eventIds = events.stream().map(AnomalyEvent::getId).toList();
        return new HashSet<>(
                notificationHistoryRepository.findNotifiedAnomalyEventIds(
                        guardianId, eventIds, NotificationHistory.STATUS_FAILED));
    }
}
