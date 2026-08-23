package com.tracecare.backend.domain.location.guardian.dto.response;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.location.entity.LocationHistory;

/**
 * {@code locationId}와 {@code recordedAt}을 함께 내려준다 — DATABASE_DESIGN_GUIDE.md §5.1이 지적하는 "지도 마커 클릭 →
 * 상세 재조회" 시나리오에서 프론트엔드가 복합 PK(id, recorded_at)를 둘 다 갖고 있어야 이후 상세 조회를 id 단독이 아닌 안전한 방식으로 요청할 수 있다.
 */
@Getter
@Builder
@AllArgsConstructor
public class LocationHistoryItemResponse {

    private Long locationId;
    private Double latitude;
    private Double longitude;
    private Instant recordedAt;

    public static LocationHistoryItemResponse of(LocationHistory location) {
        return LocationHistoryItemResponse.builder()
                .locationId(location.getId())
                .latitude(location.getLatitude().doubleValue())
                .longitude(location.getLongitude().doubleValue())
                .recordedAt(location.getRecordedAt())
                .build();
    }
}
