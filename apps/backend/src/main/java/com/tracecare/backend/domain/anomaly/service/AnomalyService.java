package com.tracecare.backend.domain.anomaly.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.auth.AccessDeniedCustomException;
import com.tracecare.backend.common.exception.business.AnomalyEventNotFoundException;
import com.tracecare.backend.common.exception.business.CareTargetNotFoundException;
import com.tracecare.backend.common.exception.business.InvalidAnomalyQuestionException;
import com.tracecare.backend.common.exception.validation.InvalidRequestException;
import com.tracecare.backend.common.util.GeoDistanceCalculator;
import com.tracecare.backend.domain.anomaly.dto.response.AnomalyEventResponse;
import com.tracecare.backend.domain.anomaly.dto.response.AnomalyExplainResponse;
import com.tracecare.backend.domain.anomaly.dto.response.AnomalyQuestionResponse;
import com.tracecare.backend.domain.anomaly.entity.AnomalyEvent;
import com.tracecare.backend.domain.anomaly.repository.AnomalyEventRepository;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.location.entity.LocationHistory;
import com.tracecare.backend.domain.location.repository.LocationHistoryRepository;
import com.tracecare.backend.domain.location.service.LocationCacheStore;
import com.tracecare.backend.domain.place.entity.Place;
import com.tracecare.backend.domain.place.repository.PlaceRepository;
import com.tracecare.backend.domain.visit.entity.VisitHistory;
import com.tracecare.backend.domain.visit.repository.VisitHistoryRepository;

/**
 * API_Specification.md §3.9 — 이상행동 목록/질문 카탈로그/설명(`/explain`) 조회. <b>LLM을 호출하지 않는다</b>: 설명은 선택된
 * 질문({@link AnomalyQuestionKey})에 대응하는 저장 데이터를 조회해 문장으로 조립한다(DATABASE_DESIGN_GUIDE.md §15.7).
 * 외부 호출이 없어 트랜잭션과 분리할 대상이 없으므로 조회 메서드는 전부 {@code @Transactional(readOnly = true)}다.
 *
 * <p><b>값이 없는 경우</b>: Soft Delete된 Place, 스냅샷 컬럼({@code scheduled_at}/{@code stay_started_at}) 추가 이전에
 * 생성돼 값이 null인 이벤트 등은 에러가 아니라 "확인할 수 없습니다"류의 안내 문장으로 답한다(§15.7).
 *
 * <p>시각은 서버 기본 타임존({@code ZoneId.systemDefault()}, {@code AiChatService}와 동일한 프로젝트 관행) 기준
 * "yyyy년 M월 d일 HH:mm"으로 표기한다.
 */
@Service
public class AnomalyService {

    /** 조회 기간 미지정 시 기본 창(to−7일), API_Specification.md §3.9 확정값. */
    static final int DEFAULT_WINDOW_DAYS = 7;

    /** 목록 조회 최대 범위(to−from), 초과하면 COMMON_002. */
    static final int MAX_RANGE_DAYS = 90;

    /** {@code STAY_SIMILAR_PAST} 조회 기간 — 목록 API 최대 범위와 동일(§15.7 F). */
    static final int SIMILAR_LOOKBACK_DAYS = 90;

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm").withZone(ZONE);
    private static final double METERS_PER_DEGREE = 111_320.0;

    private final UserRepository userRepository;
    private final GuardianTargetRepository guardianTargetRepository;
    private final AnomalyEventRepository anomalyEventRepository;
    private final PlaceRepository placeRepository;
    private final VisitHistoryRepository visitHistoryRepository;
    private final LocationCacheStore locationCacheStore;
    private final LocationHistoryRepository locationHistoryRepository;
    private final double similarRadiusMeters;
    private final int stayDetectMinutes;

    public AnomalyService(
            UserRepository userRepository,
            GuardianTargetRepository guardianTargetRepository,
            AnomalyEventRepository anomalyEventRepository,
            PlaceRepository placeRepository,
            VisitHistoryRepository visitHistoryRepository,
            LocationCacheStore locationCacheStore,
            LocationHistoryRepository locationHistoryRepository,
            @Value("${anomaly.unregistered-stay-radius-meters}") double similarRadiusMeters,
            @Value("${anomaly.unregistered-stay-detect-minutes}") int stayDetectMinutes) {
        this.userRepository = userRepository;
        this.guardianTargetRepository = guardianTargetRepository;
        this.anomalyEventRepository = anomalyEventRepository;
        this.placeRepository = placeRepository;
        this.visitHistoryRepository = visitHistoryRepository;
        this.locationCacheStore = locationCacheStore;
        this.locationHistoryRepository = locationHistoryRepository;
        this.similarRadiusMeters = similarRadiusMeters;
        this.stayDetectMinutes = stayDetectMinutes;
    }

    // ---------------------------------------------------------------------
    // GET /api/guardian/anomalies — 목록
    // ---------------------------------------------------------------------

    /**
     * 검증 순서: careTargetId 소유권(TARGET_001/TARGET_002) → type/기간(COMMON_002) → 조회. 정렬은 서버에서 {@code
     * detected_at DESC}로 고정하므로 클라이언트가 보낸 {@code sort}는 무시한다(페이지 번호/크기만 사용). 0건이면 예외 없이 빈
     * 페이지를 돌려준다(API_Specification.md §3.9).
     */
    @Transactional(readOnly = true)
    public Page<AnomalyEventResponse> getAnomalies(
            Long guardianId,
            UUID careTargetPublicId,
            String type,
            Instant from,
            Instant to,
            Pageable pageable) {
        User target =
                userRepository
                        .findByPublicId(careTargetPublicId)
                        .orElseThrow(CareTargetNotFoundException::new);
        assertActiveRelation(guardianId, target.getId());

        validateType(type);
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(DEFAULT_WINDOW_DAYS, ChronoUnit.DAYS);
        if (effectiveFrom.isAfter(effectiveTo)
                || Duration.between(effectiveFrom, effectiveTo).compareTo(Duration.ofDays(MAX_RANGE_DAYS))
                        > 0) {
            throw new InvalidRequestException(ErrorCode.COMMON_002);
        }

        Pageable fixedOrder = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Page<AnomalyEvent> events =
                type == null
                        ? anomalyEventRepository.findByUserIdAndDetectedAtBetweenOrderByDetectedAtDesc(
                                target.getId(), effectiveFrom, effectiveTo, fixedOrder)
                        : anomalyEventRepository
                                .findByUserIdAndTypeAndDetectedAtBetweenOrderByDetectedAtDesc(
                                        target.getId(), type, effectiveFrom, effectiveTo, fixedOrder);

        // placeName은 페이지의 Place를 IN 쿼리 한 번으로 일괄 조회해 채운다(N+1 없음, 스냅샷 컬럼 아님).
        Map<Long, Place> placesById = loadPlaces(events.getContent());
        return events.map(event -> AnomalyEventResponse.of(event, placesById.get(event.getPlaceId())));
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

    // ---------------------------------------------------------------------
    // GET /api/guardian/anomalies/questions — 카탈로그
    // ---------------------------------------------------------------------

    /**
     * {@code type}은 필수이며 허용되지 않은 값이면 COMMON_002. 리소스를 다루지 않아 소유권 검증은 없다. 감지 기준 시간이 들어가는 문구는
     * 조회 시점의 설정값({@code anomaly.unregistered-stay-detect-minutes})으로 채워, 답변 문장과 항상 같은 값을 쓴다.
     */
    public List<AnomalyQuestionResponse> getQuestions(String type) {
        if (type == null) {
            throw new InvalidRequestException(ErrorCode.COMMON_002);
        }
        validateType(type);
        String detectMinutesText = formatMinutes(stayDetectMinutes);
        return AnomalyQuestionKey.forType(type).stream()
                .map(key -> AnomalyQuestionResponse.of(key, detectMinutesText))
                .toList();
    }

    private void validateType(String type) {
        if (type == null) {
            return;
        }
        if (!AnomalyEvent.TYPE_ARRIVAL_DELAY.equals(type)
                && !AnomalyEvent.TYPE_UNREGISTERED_STAY.equals(type)) {
            throw new InvalidRequestException(ErrorCode.COMMON_002);
        }
    }

    // ---------------------------------------------------------------------
    // GET /api/guardian/anomalies/{anomalyEventId}/explain?question=
    // ---------------------------------------------------------------------

    /**
     * 검증 순서: question 누락(COMMON_002) → 이벤트 없음(ANOMALY_001) → 소유권(TARGET_002) → 질문 키가 이 이벤트의 유형에
     * 속하는지(ANOMALY_002). 키 검증은 이벤트의 유형을 알아야 하므로 소유권 검증 뒤에 두어, 소유자가 아닌 호출자는 키가 무엇이든 403을
     * 받는다.
     */
    @Transactional(readOnly = true)
    public AnomalyExplainResponse explain(Long guardianId, Long anomalyEventId, String question) {
        if (question == null || question.isBlank()) {
            throw new InvalidRequestException(ErrorCode.COMMON_002);
        }
        AnomalyEvent event =
                anomalyEventRepository
                        .findById(anomalyEventId)
                        .orElseThrow(AnomalyEventNotFoundException::new);
        assertActiveRelation(guardianId, event.getUserId());

        AnomalyQuestionKey key =
                AnomalyQuestionKey.find(question)
                        .filter(candidate -> candidate.belongsTo(event.getType()))
                        .orElseThrow(InvalidAnomalyQuestionException::new);

        return AnomalyExplainResponse.builder().answer(answer(key, event)).build();
    }

    private String answer(AnomalyQuestionKey key, AnomalyEvent event) {
        return switch (key) {
            case DELAY_REASON -> delayReason(event);
            case DELAY_EXPECTED_TIME -> delayExpectedTime(event);
            case DELAY_DURATION -> delayDuration(event);
            case DELAY_CURRENT_LOCATION -> currentLocation(event);
            case DELAY_LAST_VISIT -> delayLastVisit(event);
            case DELAY_COUNT_7D -> delayCount(event, 7);
            case DELAY_COUNT_30D -> delayCount(event, 30);
            case STAY_REASON -> stayReason(event);
            case STAY_LOCATION -> stayLocation(event);
            case STAY_STARTED_AT -> stayStartedAt(event);
            case STAY_ELAPSED -> stayElapsed(event);
            case STAY_ONGOING -> stayOngoing(event);
            case STAY_NEAREST_PLACE_DISTANCE -> stayNearestPlaceDistance(event);
            case STAY_COUNT_7D -> stayCount7d(event);
            case STAY_SIMILAR_PAST -> staySimilarPast(event);
        };
    }

    // ---- ARRIVAL_DELAY ----

    private String delayReason(AnomalyEvent event) {
        if (event.getScheduledAt() == null) {
            return "예정 도착 시각이 기록되기 전에 발생한 이상행동이라 자세한 감지 근거를 확인할 수 없습니다.";
        }
        Optional<Place> found = findPlace(event.getPlaceId());
        if (found.isEmpty()) {
            return "이 이상행동에 연결된 장소 정보가 없어 자세한 감지 근거를 확인할 수 없습니다.";
        }
        String place = "'" + found.get().getName() + "'";
        long minutes = minutesBetween(event.getScheduledAt(), event.getDetectedAt());
        return place
                + "에 "
                + formatTime(event.getScheduledAt())
                + "까지 도착할 예정이었는데, 예정 시각 "
                + formatMinutes(minutes)
                + " 뒤까지 도착 기록이 없어 감지됐습니다.";
    }

    private String delayExpectedTime(AnomalyEvent event) {
        if (event.getScheduledAt() == null) {
            return "예정 도착 시각이 기록되기 전에 발생한 이상행동이라 원래 도착 예정 시각을 확인할 수 없습니다.";
        }
        return "원래 도착 예정 시각은 " + formatTime(event.getScheduledAt()) + "이었습니다.";
    }

    private String delayDuration(AnomalyEvent event) {
        if (event.getScheduledAt() == null) {
            return "예정 도착 시각이 기록되기 전에 발생한 이상행동이라 지연 시간을 확인할 수 없습니다.";
        }
        if (event.isOpen()) {
            long minutes = minutesBetween(event.getScheduledAt(), Instant.now());
            return "예정 시각보다 " + formatMinutes(minutes) + " 지연되고 있습니다. 아직 도착이 확인되지 않았습니다.";
        }
        long minutes = minutesBetween(event.getScheduledAt(), event.getResolvedAt());
        return "예정 시각보다 약 " + formatMinutes(minutes) + " 늦게 도착이 확인됐습니다.";
    }

    /** Redis {@code location:latest} 우선, 없으면 {@code LocationHistory}(기존 위치 조회와 동일한 폴백 순서). */
    private String currentLocation(AnomalyEvent event) {
        Double latitude = null;
        Double longitude = null;
        Instant recordedAt = null;

        Optional<User> target = userRepository.findById(event.getUserId());
        if (target.isPresent()) {
            LocationCacheStore.CachedLocation cached = locationCacheStore.read(target.get().getPublicId());
            if (cached != null) {
                latitude = cached.latitude();
                longitude = cached.longitude();
                recordedAt = cached.recordedAt();
            }
        }
        if (latitude == null) {
            Optional<LocationHistory> latest =
                    locationHistoryRepository.findFirstByUserIdOrderByRecordedAtDesc(event.getUserId());
            if (latest.isPresent()) {
                latitude = latest.get().getLatitude().doubleValue();
                longitude = latest.get().getLongitude().doubleValue();
                recordedAt = latest.get().getRecordedAt();
            }
        }
        if (latitude == null) {
            return "확인할 수 있는 위치 정보가 없습니다.";
        }
        return "마지막으로 확인된 위치는 "
                + formatCoordinates(latitude, longitude)
                + "이고, "
                + formatTime(recordedAt)
                + "에 수신됐습니다.";
    }

    /**
     * 장소 자체를 알 수 없는 경우(이상행동에 {@code place_id}가 없거나, Soft Delete로 Place가 조회되지 않음)와 "장소는 있는데 방문
     * 이력만 없는" 정상 경우를 구분한다. 앞의 경우는 {@code DELAY_REASON}과 같은 안내 문장으로 답한다.
     */
    private String delayLastVisit(AnomalyEvent event) {
        if (findPlace(event.getPlaceId()).isEmpty()) {
            return "이 이상행동에 연결된 장소 정보가 없어 방문 기록을 확인할 수 없습니다.";
        }
        Page<VisitHistory> latest =
                visitHistoryRepository.findByUserIdAndPlaceIdOrderByArrivalTimeDesc(
                        event.getUserId(), event.getPlaceId(), PageRequest.of(0, 1));
        if (latest.isEmpty()) {
            return "이 장소의 방문 기록이 아직 없습니다.";
        }
        return "이 장소를 마지막으로 방문한 때는 " + formatTime(latest.getContent().get(0).getArrivalTime()) + "입니다.";
    }

    /**
     * 같은 CareTarget·같은 장소(장소 정보가 없으면 유형 전체)의 최근 N일 지연 횟수. 집계 창이 "지금 기준 N일"이라 이 이상행동 자신이
     * 창 안에 있을 때만 {@code "이번을 포함해"}를 붙인다(N일보다 오래된 이상행동에 붙이면 사실과 다르다).
     */
    private String delayCount(AnomalyEvent event, int days) {
        Instant from = Instant.now().minus(days, ChronoUnit.DAYS);
        String prefix = includesSelfPrefix(event, from);
        if (event.getPlaceId() == null) {
            long count =
                    anomalyEventRepository.countByUserIdAndTypeAndDetectedAtGreaterThanEqual(
                            event.getUserId(), AnomalyEvent.TYPE_ARRIVAL_DELAY, from);
            return prefix + "최근 " + days + "일간 " + count + "번 지연됐습니다.";
        }
        long count =
                anomalyEventRepository.countByUserIdAndTypeAndPlaceIdAndDetectedAtGreaterThanEqual(
                        event.getUserId(), AnomalyEvent.TYPE_ARRIVAL_DELAY, event.getPlaceId(), from);
        return prefix + "최근 " + days + "일간 이 장소에서 " + count + "번 지연됐습니다.";
    }

    // ---- UNREGISTERED_STAY ----

    private String stayReason(AnomalyEvent event) {
        if (event.getStayStartedAt() == null) {
            return "체류 시작 시각이 기록되기 전에 발생한 이상행동이라 자세한 감지 근거를 확인할 수 없습니다.";
        }
        long minutes = minutesBetween(event.getStayStartedAt(), event.getDetectedAt());
        return "등록되지 않은 장소에서 " + formatMinutes(minutes) + " 이상 머물러 감지됐습니다.";
    }

    private String stayLocation(AnomalyEvent event) {
        if (event.getLatitude() == null || event.getLongitude() == null) {
            return "이 이상행동의 위치 정보를 확인할 수 없습니다.";
        }
        return formatCoordinates(event.getLatitude().doubleValue(), event.getLongitude().doubleValue())
                + " 근처입니다.";
    }

    private String stayStartedAt(AnomalyEvent event) {
        if (event.getStayStartedAt() == null) {
            return "체류 시작 시각이 기록되기 전에 발생한 이상행동이라 시작 시각을 확인할 수 없습니다.";
        }
        return formatTime(event.getStayStartedAt()) + "부터 머물렀습니다.";
    }

    private String stayElapsed(AnomalyEvent event) {
        if (event.getStayStartedAt() == null) {
            return "체류 시작 시각이 기록되기 전에 발생한 이상행동이라 머문 시간을 확인할 수 없습니다.";
        }
        if (event.isOpen()) {
            long minutes = minutesBetween(event.getStayStartedAt(), Instant.now());
            return formatMinutes(minutes) + " 동안 머물고 있습니다.";
        }
        long minutes = minutesBetween(event.getStayStartedAt(), event.getResolvedAt());
        return formatMinutes(minutes) + " 동안 머물렀습니다.";
    }

    private String stayOngoing(AnomalyEvent event) {
        if (event.isOpen()) {
            return "네, 아직 머물고 있습니다.";
        }
        return "아니요, " + formatTime(event.getResolvedAt()) + "에 해제됐습니다(등록 장소 도착 또는 이동).";
    }

    /** 이벤트의 {@code place_id}(감지 시점의 최근접 등록 장소)의 현재 좌표와 이벤트 좌표 사이 거리. */
    private String stayNearestPlaceDistance(AnomalyEvent event) {
        Optional<Place> place = findPlace(event.getPlaceId());
        if (place.isEmpty() || event.getLatitude() == null || event.getLongitude() == null) {
            return "가까운 등록 장소 정보를 확인할 수 없습니다.";
        }
        double meters =
                GeoDistanceCalculator.distanceInMeters(
                        place.get().getLatitude().doubleValue(),
                        place.get().getLongitude().doubleValue(),
                        event.getLatitude().doubleValue(),
                        event.getLongitude().doubleValue());
        return "가장 가까운 등록 장소('" + place.get().getName() + "')까지 약 " + Math.round(meters) + "m 떨어져 있습니다.";
    }

    private String stayCount7d(AnomalyEvent event) {
        Instant from = Instant.now().minus(7, ChronoUnit.DAYS);
        long count =
                anomalyEventRepository.countByUserIdAndTypeAndDetectedAtGreaterThanEqual(
                        event.getUserId(), AnomalyEvent.TYPE_UNREGISTERED_STAY, from);
        return includesSelfPrefix(event, from) + "최근 7일간 " + count + "번 발생했습니다.";
    }

    /**
     * 과거 {@code UNREGISTERED_STAY} 이벤트(자기 자신 제외, 최근 {@value #SIMILAR_LOOKBACK_DAYS}일) 중 좌표가 이 이벤트로부터
     * {@code anomaly.unregistered-stay-radius-meters} 이내인 것. Bounding Box로 후보를 좁히고 Haversine으로 재계산한다
     * (§15.7 F). 답변 문장의 "N분 이상"은 감지 기준 설정값({@code anomaly.unregistered-stay-detect-minutes})을 쓴다.
     */
    private String staySimilarPast(AnomalyEvent event) {
        if (event.getLatitude() == null || event.getLongitude() == null) {
            return "이 이상행동의 위치 정보가 없어 비슷한 위치의 이력을 확인할 수 없습니다.";
        }
        double latitude = event.getLatitude().doubleValue();
        double longitude = event.getLongitude().doubleValue();
        double latDelta = similarRadiusMeters / METERS_PER_DEGREE;
        double lngDelta =
                similarRadiusMeters
                        / (METERS_PER_DEGREE * Math.max(Math.cos(Math.toRadians(latitude)), 1e-6));

        List<AnomalyEvent> similar =
                anomalyEventRepository
                        .findSimilarUnregisteredStayCandidates(
                                event.getUserId(),
                                event.getId(),
                                Instant.now().minus(SIMILAR_LOOKBACK_DAYS, ChronoUnit.DAYS),
                                BigDecimal.valueOf(latitude - latDelta),
                                BigDecimal.valueOf(latitude + latDelta),
                                BigDecimal.valueOf(longitude - lngDelta),
                                BigDecimal.valueOf(longitude + lngDelta))
                        .stream()
                        .filter(
                                candidate ->
                                        GeoDistanceCalculator.distanceInMeters(
                                                        latitude,
                                                        longitude,
                                                        candidate.getLatitude().doubleValue(),
                                                        candidate.getLongitude().doubleValue())
                                                <= similarRadiusMeters)
                        .toList();

        if (similar.isEmpty()) {
            return "최근 "
                    + SIMILAR_LOOKBACK_DAYS
                    + "일 안에 비슷한 위치에서 "
                    + formatMinutes(stayDetectMinutes)
                    + " 이상 머문 이력은 없습니다.";
        }
        return "최근 "
                + SIMILAR_LOOKBACK_DAYS
                + "일 안에 비슷한 위치에서 "
                + formatMinutes(stayDetectMinutes)
                + " 이상 머문 이력이 "
                + similar.size()
                + "번 있습니다. 가장 최근은 "
                + formatTime(similar.get(0).getDetectedAt())
                + "입니다.";
    }

    // ---- 공통 ----

    private Optional<Place> findPlace(Long placeId) {
        if (placeId == null) {
            return Optional.empty();
        }
        return placeRepository.findAllById(List.of(placeId)).stream().findFirst();
    }

    private void assertActiveRelation(Long guardianId, Long targetId) {
        guardianTargetRepository
                .findByGuardianIdAndTargetIdAndStatus(
                        guardianId, targetId, GuardianTarget.STATUS_ACTIVE)
                .orElseThrow(() -> new AccessDeniedCustomException(ErrorCode.TARGET_002));
    }

    /** 집계 창({@code from} 이후)에 이 이상행동 자신이 들어 있으면 "이번을 포함해 ", 아니면 빈 문자열. */
    private static String includesSelfPrefix(AnomalyEvent event, Instant from) {
        return event.getDetectedAt().isBefore(from) ? "" : "이번을 포함해 ";
    }

    private static long minutesBetween(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toMinutes());
    }

    private static String formatTime(Instant instant) {
        return TIME_FORMAT.format(instant);
    }

    static String formatMinutes(long minutes) {
        if (minutes < 60) {
            return minutes + "분";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + "시간" : hours + "시간 " + rest + "분";
    }

    /** {@code Locale.ROOT}로 고정해 로케일에 따라 소수점이 쉼표로 나오지 않게 한다. */
    private static String formatCoordinates(double latitude, double longitude) {
        return String.format(Locale.ROOT, "위도 %.6f, 경도 %.6f", latitude, longitude);
    }
}
