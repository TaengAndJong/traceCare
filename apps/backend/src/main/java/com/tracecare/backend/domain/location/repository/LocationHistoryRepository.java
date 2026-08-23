package com.tracecare.backend.domain.location.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.tracecare.backend.domain.location.entity.LocationHistory;
import com.tracecare.backend.domain.location.entity.LocationHistoryId;

/**
 * 의도적으로 {@code findById(Long)}류 단독 조회 메서드를 추가하지 않는다 — LocationHistory는 PK가 (id, recorded_at) 복합키이고,
 * id만으로 조회하면 파티션 프루닝이 되지 않는다(DATABASE_DESIGN_GUIDE.md §5.1). 모든 조회 메서드는 반드시 {@code userId}를 포함한다.
 */
public interface LocationHistoryRepository
        extends JpaRepository<LocationHistory, LocationHistoryId> {

    Optional<LocationHistory> findFirstByUserIdOrderByRecordedAtDesc(Long userId);

    Page<LocationHistory> findByUserIdAndRecordedAtBetweenOrderByRecordedAtDesc(
            Long userId, Instant from, Instant to, Pageable pageable);
}
