package com.tracecare.backend.domain.arrival.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.common.exception.business.ArrivalHistoryNotFoundException;
import com.tracecare.backend.common.exception.business.ArrivalNotRegisteredException;
import com.tracecare.backend.common.exception.business.InvalidLocationCoordinateException;
import com.tracecare.backend.common.exception.business.PlaceNotFoundException;
import com.tracecare.backend.common.util.GeoDistanceCalculator;
import com.tracecare.backend.common.validation.LatitudeValidator;
import com.tracecare.backend.common.validation.LongitudeValidator;
import com.tracecare.backend.domain.arrival.dto.request.ArrivalCheckRequest;
import com.tracecare.backend.domain.arrival.dto.response.ArrivalCheckResponse;
import com.tracecare.backend.domain.arrival.entity.ArrivalHistory;
import com.tracecare.backend.domain.arrival.repository.ArrivalHistoryRepository;
import com.tracecare.backend.domain.place.entity.Place;
import com.tracecare.backend.domain.place.repository.PlaceRepository;

/**
 * API_Specification.md §4.2 — CareTarget이 능동적으로 확인하는 도착. Location Phase 3의 자동 GeoFence 판정 ({@code
 * VisitHistory}/{@code GeoFenceService})과는 완전히 별개로 둔다 — 문서 어디에도 두 기록을 연동하라는 근거가 없고, "사용자가 능동적으로 누른
 * 확인 버튼"과 "시스템이 위치 수신마다 자동 판정하는 상태 전이"는 성격이 다른 사건이라 하나가 다른 하나를 트리거하거나 참조하면 오히려 두 기록의 의미가
 * 흐려진다(DATABASE_DESIGN_GUIDE.md §3.10).
 */
@Service
public class ArrivalService {

    private final LatitudeValidator latitudeValidator = new LatitudeValidator();
    private final LongitudeValidator longitudeValidator = new LongitudeValidator();

    private final PlaceRepository placeRepository;
    private final ArrivalHistoryRepository arrivalHistoryRepository;

    public ArrivalService(
            PlaceRepository placeRepository, ArrivalHistoryRepository arrivalHistoryRepository) {
        this.placeRepository = placeRepository;
        this.arrivalHistoryRepository = arrivalHistoryRepository;
    }

    /**
     * POST /api/care-target/arrival/check — Place 소유권 불일치는 {@link PlaceNotFoundException}(404)로
     * 처리한다. Guardian이 타인의 Place/CareTarget에 접근하는 IDOR과 달리, CareTarget은 애초에 다른 CareTarget의 Place를
     * 열람/나열할 방법이 전혀 없어(그런 조회 API 자체가 없음) 존재 자체를 숨겨도 정보 노출 위험이 실질적으로 없다 — 그래서 새 403 코드를 만들지 않고 이미 있는
     * PLACE_001을 그대로 재사용한다(근거는 결과 보고에도 남김).
     */
    @Transactional
    public ArrivalCheckResponse checkArrival(Long callerId, ArrivalCheckRequest request) {
        BigDecimal latitude = validateAndConvertLatitude(request.getLatitude());
        BigDecimal longitude = validateAndConvertLongitude(request.getLongitude());

        Place place =
                placeRepository
                        .findByPublicId(UUID.fromString(request.getPlaceId()))
                        .filter(candidate -> candidate.getTargetId().equals(callerId))
                        .orElseThrow(PlaceNotFoundException::new);

        double distance =
                GeoDistanceCalculator.distanceInMeters(
                        place.getLatitude().doubleValue(),
                        place.getLongitude().doubleValue(),
                        request.getLatitude(),
                        request.getLongitude());
        if (distance > place.getRadius()) {
            throw new ArrivalNotRegisteredException();
        }

        ArrivalHistory arrival =
                ArrivalHistory.confirm(
                        callerId, place.getId(), place.getName(), latitude, longitude);
        arrivalHistoryRepository.save(arrival);
        return ArrivalCheckResponse.of(arrival);
    }

    /** GET /api/care-target/arrival/history — 결과 없으면 VisitHistory와 동일한 기존 프로젝트 관례대로 404. */
    @Transactional(readOnly = true)
    public Page<ArrivalCheckResponse> getHistory(Long callerId, Pageable pageable) {
        Page<ArrivalCheckResponse> result =
                arrivalHistoryRepository
                        .findByUserIdOrderByConfirmedAtDesc(callerId, pageable)
                        .map(ArrivalCheckResponse::of);
        if (result.isEmpty()) {
            throw new ArrivalHistoryNotFoundException();
        }
        return result;
    }

    private BigDecimal validateAndConvertLatitude(Double latitude) {
        if (!latitudeValidator.isValid(latitude, null)) {
            throw new InvalidLocationCoordinateException();
        }
        return BigDecimal.valueOf(latitude);
    }

    private BigDecimal validateAndConvertLongitude(Double longitude) {
        if (!longitudeValidator.isValid(longitude, null)) {
            throw new InvalidLocationCoordinateException();
        }
        return BigDecimal.valueOf(longitude);
    }
}
