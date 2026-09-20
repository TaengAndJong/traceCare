package com.tracecare.backend.domain.anomaly.dto.response;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.anomaly.entity.AnomalyEvent;
import com.tracecare.backend.domain.place.entity.Place;

/**
 * API_Specification.md §3.9 {@code GET /api/guardian/anomalies}의 {@code content} 항목. {@code escalatedAt}은
 * 의도적으로 포함하지 않는다("최초 승격 시각(참고용)"이라 이 Guardian이 푸시를 받았는지를 뜻하지 않음, DATABASE_DESIGN_GUIDE.md
 * §15.2/§15.6). {@code placeId}는 Master Data(Place)라 내부 PK가 아니라 {@code public_id}로 내려간다.
 */
@Getter
@Builder
@AllArgsConstructor
public class AnomalyEventResponse {

    public static final String STATUS_ONGOING = "ONGOING";
    public static final String STATUS_RESOLVED = "RESOLVED";

    private Long anomalyEventId;
    private String type;
    private String status;
    private Instant detectedAt;
    private Instant resolvedAt;
    private String placeId;
    private String placeName;
    private Double latitude;
    private Double longitude;

    /**
     * @param place 이 이벤트의 {@code place_id}에 해당하는 Place. {@code place_id}가 null이거나 Soft Delete된 Place면 {@code
     *     null}이며, 이때 {@code placeId}/{@code placeName}은 둘 다 null로 내려간다(삭제된 행은 {@code @SQLRestriction}으로
     *     JPA에서 조회되지 않아 {@code public_id}도 복원할 수 없다).
     */
    public static AnomalyEventResponse of(AnomalyEvent event, Place place) {
        return AnomalyEventResponse.builder()
                .anomalyEventId(event.getId())
                .type(event.getType())
                .status(event.isOpen() ? STATUS_ONGOING : STATUS_RESOLVED)
                .detectedAt(event.getDetectedAt())
                .resolvedAt(event.getResolvedAt())
                .placeId(place != null ? place.getPublicId().toString() : null)
                .placeName(place != null ? place.getName() : null)
                .latitude(event.getLatitude() != null ? event.getLatitude().doubleValue() : null)
                .longitude(event.getLongitude() != null ? event.getLongitude().doubleValue() : null)
                .build();
    }
}
