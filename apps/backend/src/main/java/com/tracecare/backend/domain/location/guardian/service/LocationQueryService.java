package com.tracecare.backend.domain.location.guardian.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.auth.AccessDeniedCustomException;
import com.tracecare.backend.common.exception.business.CareTargetNotFoundException;
import com.tracecare.backend.common.exception.business.LocationNotFoundException;
import com.tracecare.backend.common.exception.validation.InvalidRequestException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.location.entity.LocationHistory;
import com.tracecare.backend.domain.location.guardian.dto.response.CurrentLocationResponse;
import com.tracecare.backend.domain.location.guardian.dto.response.LocationHistoryItemResponse;
import com.tracecare.backend.domain.location.repository.LocationHistoryRepository;
import com.tracecare.backend.domain.location.service.LocationCacheStore;

/** API_Specification.md §3.3 — CareTarget 현재 위치/이동 히스토리 조회(PRIMARY/SUB 모두 허용). */
@Service
public class LocationQueryService {

    private final UserRepository userRepository;
    private final GuardianTargetRepository guardianTargetRepository;
    private final LocationHistoryRepository locationHistoryRepository;
    private final LocationCacheStore locationCacheStore;

    public LocationQueryService(
            UserRepository userRepository,
            GuardianTargetRepository guardianTargetRepository,
            LocationHistoryRepository locationHistoryRepository,
            LocationCacheStore locationCacheStore) {
        this.userRepository = userRepository;
        this.guardianTargetRepository = guardianTargetRepository;
        this.locationHistoryRepository = locationHistoryRepository;
        this.locationCacheStore = locationCacheStore;
    }

    @Transactional(readOnly = true)
    public CurrentLocationResponse getCurrentLocation(Long guardianId, UUID careTargetPublicId) {
        User target = findTargetByPublicId(careTargetPublicId);
        assertActiveRelation(guardianId, target.getId());

        LocationCacheStore.CachedLocation cached = locationCacheStore.read(careTargetPublicId);
        if (cached != null) {
            return CurrentLocationResponse.of(
                    target,
                    cached.latitude(),
                    cached.longitude(),
                    cached.recordedAt(),
                    CurrentLocationResponse.SOURCE_REDIS_CACHE);
        }

        LocationHistory latest =
                locationHistoryRepository
                        .findFirstByUserIdOrderByRecordedAtDesc(target.getId())
                        .orElseThrow(LocationNotFoundException::new);
        return CurrentLocationResponse.of(
                target,
                latest.getLatitude().doubleValue(),
                latest.getLongitude().doubleValue(),
                latest.getRecordedAt(),
                CurrentLocationResponse.SOURCE_DB);
    }

    @Transactional(readOnly = true)
    public Page<LocationHistoryItemResponse> getHistory(
            Long guardianId, UUID careTargetPublicId, Instant from, Instant to, Pageable pageable) {
        User target = findTargetByPublicId(careTargetPublicId);
        assertActiveRelation(guardianId, target.getId());

        if (from.isAfter(to)) {
            throw new InvalidRequestException(ErrorCode.COMMON_002);
        }

        return locationHistoryRepository
                .findByUserIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                        target.getId(), from, to, pageable)
                .map(LocationHistoryItemResponse::of);
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
