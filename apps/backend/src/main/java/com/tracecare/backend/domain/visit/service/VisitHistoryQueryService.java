package com.tracecare.backend.domain.visit.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.auth.AccessDeniedCustomException;
import com.tracecare.backend.common.exception.business.CareTargetNotFoundException;
import com.tracecare.backend.common.exception.business.PlaceNotFoundException;
import com.tracecare.backend.common.exception.business.VisitHistoryNotFoundException;
import com.tracecare.backend.common.exception.validation.InvalidRequestException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.place.entity.Place;
import com.tracecare.backend.domain.place.repository.PlaceRepository;
import com.tracecare.backend.domain.visit.dto.response.VisitHistoryResponse;
import com.tracecare.backend.domain.visit.repository.VisitHistoryRepository;

/** API_Specification.md §3.4 — VisitHistory 기준 방문 히스토리 조회(PRIMARY/SUB 모두 허용). */
@Service
public class VisitHistoryQueryService {

    private final UserRepository userRepository;
    private final GuardianTargetRepository guardianTargetRepository;
    private final PlaceRepository placeRepository;
    private final VisitHistoryRepository visitHistoryRepository;

    public VisitHistoryQueryService(
            UserRepository userRepository,
            GuardianTargetRepository guardianTargetRepository,
            PlaceRepository placeRepository,
            VisitHistoryRepository visitHistoryRepository) {
        this.userRepository = userRepository;
        this.guardianTargetRepository = guardianTargetRepository;
        this.placeRepository = placeRepository;
        this.visitHistoryRepository = visitHistoryRepository;
    }

    /**
     * GET /api/guardian/history/today — 서버 타임존(GuardianInviteService와 동일한 ZoneId.systemDefault())
     * 기준 오늘.
     */
    @Transactional(readOnly = true)
    public Page<VisitHistoryResponse> getToday(
            Long guardianId, UUID careTargetPublicId, Pageable pageable) {
        return getByDate(
                guardianId, careTargetPublicId, LocalDate.now(ZoneId.systemDefault()), pageable);
    }

    /** GET /api/guardian/history/date — 미래 날짜는 조회 기간 값 오류(VISIT_002)로 처리한다. */
    @Transactional(readOnly = true)
    public Page<VisitHistoryResponse> getByDate(
            Long guardianId, UUID careTargetPublicId, LocalDate date, Pageable pageable) {
        User target = findTargetByPublicId(careTargetPublicId);
        assertActiveRelation(guardianId, target.getId());

        ZoneId zone = ZoneId.systemDefault();
        if (date.isAfter(LocalDate.now(zone))) {
            throw new InvalidRequestException(ErrorCode.VISIT_002);
        }
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();

        Page<VisitHistoryResponse> result =
                visitHistoryRepository
                        .findByUserIdAndArrivalTimeBetweenOrderByArrivalTimeDesc(
                                target.getId(), from, to, pageable)
                        .map(VisitHistoryResponse::of);
        if (result.isEmpty()) {
            throw new VisitHistoryNotFoundException();
        }
        return result;
    }

    /** GET /api/guardian/history/place — 특정 등록 장소에서의 전체 방문 이력. */
    @Transactional(readOnly = true)
    public Page<VisitHistoryResponse> getByPlace(
            Long guardianId, UUID careTargetPublicId, UUID placePublicId, Pageable pageable) {
        User target = findTargetByPublicId(careTargetPublicId);
        assertActiveRelation(guardianId, target.getId());

        Place place =
                placeRepository
                        .findByPublicId(placePublicId)
                        .orElseThrow(PlaceNotFoundException::new);

        Page<VisitHistoryResponse> result =
                visitHistoryRepository
                        .findByUserIdAndPlaceIdOrderByArrivalTimeDesc(
                                target.getId(), place.getId(), pageable)
                        .map(VisitHistoryResponse::of);
        if (result.isEmpty()) {
            throw new VisitHistoryNotFoundException();
        }
        return result;
    }

    private User findTargetByPublicId(UUID targetPublicId) {
        return userRepository
                .findByPublicId(targetPublicId)
                .orElseThrow(CareTargetNotFoundException::new);
    }

    private void assertActiveRelation(Long guardianId, Long targetId) {
        guardianTargetRepository
                .findByGuardianIdAndTargetIdAndStatus(
                        guardianId, targetId, GuardianTarget.STATUS_ACTIVE)
                .orElseThrow(() -> new AccessDeniedCustomException(ErrorCode.TARGET_002));
    }
}
