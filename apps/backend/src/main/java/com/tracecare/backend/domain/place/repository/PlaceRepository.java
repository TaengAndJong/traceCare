package com.tracecare.backend.domain.place.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tracecare.backend.domain.place.entity.Place;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    Optional<Place> findByPublicId(UUID publicId);

    /**
     * 중복 판정(이름 일치 또는 Haversine 실거리 {@code place.duplicate-distance-meters} 이내, PlaceService 참고)은
     * SQL로 표현할 수 없어 이 목록을 애플리케이션(PlaceService)에서 순회하며 계산한다. CareTarget 1인당 최대 15개 소프트 상한이 걸려
     * 있어(PLACE_004) 전체 순회 비용은 무시할 수준이다.
     */
    List<Place> findByTargetIdOrderByCreatedAtAsc(Long targetId);

    long countByTargetId(Long targetId);
}
