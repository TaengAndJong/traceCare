package com.tracecare.backend.domain.visit.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.common.exception.business.PlaceNotFoundException;
import com.tracecare.backend.common.util.GeoDistanceCalculator;
import com.tracecare.backend.domain.place.dto.response.PlaceResponse;
import com.tracecare.backend.domain.place.entity.Place;
import com.tracecare.backend.domain.place.repository.PlaceRepository;
import com.tracecare.backend.domain.place.service.PlaceService;
import com.tracecare.backend.domain.visit.entity.VisitHistory;
import com.tracecare.backend.domain.visit.event.VisitArrivedEvent;
import com.tracecare.backend.domain.visit.event.VisitDepartedEvent;
import com.tracecare.backend.domain.visit.repository.VisitHistoryRepository;

/**
 * System_Overview.md §3 흐름도의 {@code /internal/geofence/check} 판정 로직. 시퀀스 다이어그램에 {@code BE->>BE}(자기
 * 호출)로 그려져 있고, Security_Guide.md/OWASP_Security_Guide.md가 반복 강조하는 "/internal/** 외부 노출 차단" 원칙을 감안하면
 * 별도 HTTP 엔드포인트를 새로 열어 인증/네트워크 노출면을 넓히는 것보다, 같은 프로세스 안의 메서드 호출로 두는 쪽이 더 안전하고 단순하다 — 그래서 실제 HTTP
 * 엔드포인트를 만들지 않고 {@link com.tracecare.backend.domain.location.caretarget.service.LocationService}가
 * 위치 저장 흐름 안에서 이 메서드를 직접 호출한다.
 *
 * <p><b>겹치는 Place 처리</b>: 여러 Place의 반경에 동시에 걸치면(도심 GPS 오차 등으로 흔함) 문서에 명시가 없어, 현재 위치에서 가장 가까운 Place
 * 하나만 선택한다 — "어디에 있냐"는 질문에 대한 가장 직관적인 답이고, VisitHistory는 한 CareTarget당 동시에 하나의 열린 방문만 허용하는 구조(§2
 * 참고)와도 자연스럽게 맞는다.
 *
 * <p><b>같은 장소인지 판정 기준</b>: 현재 열려 있는 방문(VisitHistory)이 지금 매칭된 Place와 같은 곳인지는 내부 PK가 아니라 장소
 * 이름(place_name 스냅샷)으로 비교한다. Place 목록은 {@code place:list:{targetId}} Redis 캐시(PlaceResponse,
 * public_id만 포함 — API 응답 겸용 DTO라 내부 PK를 담지 않음)로만 조회하므로, 이 흔한 "그대로 머무름" 케이스에서 내부 PK를 알아내려고 매번 DB를
 * 추가로 조회하지 않기 위함이다. 같은 CareTarget 안에서 장소 이름은 이미 PlaceService.isDuplicate()가 등록 시점에 유일함을
 * 보장하므로(PLACE_002), 이름 비교가 내부 PK 비교와 동일하게 안전하다. 내부 PK는 실제로 방문 행을 새로 생성해야 하는 시점(도착 감지, 위치 수신 대비 훨씬
 * 드묾)에만 {@link PlaceRepository#findByPublicId}로 조회한다.
 */
@Service
public class GeoFenceService {

    private static final Logger log = LoggerFactory.getLogger(GeoFenceService.class);

    private final PlaceService placeService;
    private final PlaceRepository placeRepository;
    private final VisitHistoryRepository visitHistoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GeoFenceService(
            PlaceService placeService,
            PlaceRepository placeRepository,
            VisitHistoryRepository visitHistoryRepository,
            ApplicationEventPublisher eventPublisher) {
        this.placeService = placeService;
        this.placeRepository = placeRepository;
        this.visitHistoryRepository = visitHistoryRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * CareTarget의 위치 수신(저장) 흐름에서 매번 호출된다. NotificationHistory 기록/FCM 발송은 여기서 하지 않고, 도착/이탈이 감지된 시점에
     * {@link VisitArrivedEvent}/{@link VisitDepartedEvent}만 발행해 리스너에게 위임한다.
     *
     * <p><b>역순 이벤트 방어</b>: 네트워크 재전송/오프라인 배치 동기화로 {@code recordedAt} 기준 과거 이벤트가 최근 이벤트보다 늦게 도착할 수
     * 있다. "마지막으로 GeoFence 판정을 거친 시각"보다 이번 이벤트가 과거면 도착/이탈 판단 자체를 건너뛴다 — 원본 위치는 이 메서드 호출 이전에 이미
     * {@code LocationHistory}에 그대로 저장되므로(호출부인 LocationService 참고) 여기서 건너뛰어도 원본 기록은 유실되지 않는다. 새 컬럼이나
     * 캐시를 추가하지 않고, 이미 조회하는 최신 VisitHistory 행에서 기준 시각을 그대로 뽑아 쓴다 — 열린 방문이면 그 행의 {@code
     * arrivalTime}(마지막 도착 판정 시각), 없으면(이미 종료된 방문만 있으면) 그 행의 {@code departureTime}(마지막 이탈 판정 시각)이
     * "마지막으로 판정을 거친 시각"과 정확히 같다 — 둘 다 이 메서드가 실제로 상태를 바꾼 시점에만 기록되는 값이기 때문이다.
     */
    @Transactional
    public void evaluate(Long careTargetId, Double latitude, Double longitude, Instant recordedAt) {
        Optional<VisitHistory> lastVisit =
                visitHistoryRepository.findFirstByUserIdOrderByArrivalTimeDesc(careTargetId);

        Instant lastEvaluatedAt =
                lastVisit
                        .map(
                                visit ->
                                        visit.isOpen()
                                                ? visit.getArrivalTime()
                                                : visit.getDepartureTime())
                        .orElse(null);
        if (lastEvaluatedAt != null && recordedAt.isBefore(lastEvaluatedAt)) {
            log.warn(
                    "event=GEOFENCE_STALE_EVENT_SKIPPED, careTargetId={}, recordedAt={}, lastEvaluatedAt={}",
                    careTargetId,
                    recordedAt,
                    lastEvaluatedAt);
            return;
        }

        List<PlaceResponse> places = placeService.getPlacesForGeofence(careTargetId);
        PlaceResponse matched = findClosestMatch(places, latitude, longitude);
        Optional<VisitHistory> openVisit = lastVisit.filter(VisitHistory::isOpen);

        if (matched != null
                && openVisit.isPresent()
                && matched.getName().equals(openVisit.get().getPlaceName())) {
            return;
        }

        openVisit.ifPresent(visit -> depart(careTargetId, visit, recordedAt));
        if (matched != null) {
            arrive(careTargetId, matched, latitude, longitude, recordedAt);
        }
    }

    private PlaceResponse findClosestMatch(
            List<PlaceResponse> places, double latitude, double longitude) {
        return places.stream()
                .filter(place -> distanceTo(place, latitude, longitude) <= place.getRadius())
                .min(Comparator.comparingDouble(place -> distanceTo(place, latitude, longitude)))
                .orElse(null);
    }

    private double distanceTo(PlaceResponse place, double latitude, double longitude) {
        return GeoDistanceCalculator.distanceInMeters(
                place.getLatitude(), place.getLongitude(), latitude, longitude);
    }

    private void arrive(
            Long careTargetId,
            PlaceResponse matched,
            double latitude,
            double longitude,
            Instant recordedAt) {
        Place place =
                placeRepository
                        .findByPublicId(UUID.fromString(matched.getPlaceId()))
                        .orElseThrow(PlaceNotFoundException::new);

        VisitHistory visit =
                VisitHistory.arrive(
                        careTargetId,
                        place.getId(),
                        place.getName(),
                        BigDecimal.valueOf(latitude),
                        BigDecimal.valueOf(longitude),
                        recordedAt);
        visitHistoryRepository.save(visit);

        log.info(
                "event=GEOFENCE_ARRIVAL, careTargetId={}, placeId={}", careTargetId, place.getId());
        eventPublisher.publishEvent(
                new VisitArrivedEvent(careTargetId, place.getId(), place.getName(), recordedAt));
    }

    private void depart(Long careTargetId, VisitHistory visit, Instant recordedAt) {
        visit.depart(recordedAt);

        log.info(
                "event=GEOFENCE_DEPARTURE, careTargetId={}, placeId={}, stayMinutes={}",
                careTargetId,
                visit.getPlaceId(),
                visit.getStayMinutes());
        eventPublisher.publishEvent(
                new VisitDepartedEvent(
                        careTargetId,
                        visit.getPlaceId(),
                        visit.getPlaceName(),
                        recordedAt,
                        visit.getStayMinutes()));
    }
}
