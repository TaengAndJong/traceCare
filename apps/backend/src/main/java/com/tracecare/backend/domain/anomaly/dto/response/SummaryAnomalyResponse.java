package com.tracecare.backend.domain.anomaly.dto.response;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.anomaly.entity.AnomalyEvent;
import com.tracecare.backend.domain.place.entity.Place;

/**
 * API_Specification.md §3.6 {@code /summary}·{@code /report/weekly} 응답의 {@code anomalies} 항목. §3.9 목록 항목({@link
 * AnomalyEventResponse})과 달리 좌표를 내리지 않고(상세는 {@code /explain}으로 유도), Guardian별로 달라지는 {@code notified}를
 * 가진다. {@code placeId}는 Master Data(Place)라 내부 PK가 아니라 {@code public_id}로 내려가고, Place가 삭제됐으면 {@code
 * placeId}/{@code placeName}은 둘 다 null이다.
 */
@Getter
@Builder
@AllArgsConstructor
public class SummaryAnomalyResponse {

    private Long anomalyEventId;
    private String type;
    private String status;
    private Instant detectedAt;
    private Instant resolvedAt;
    private String placeId;
    private String placeName;

    /**
     * 요청한 Guardian 본인이 이 이상행동의 승격 푸시를 실제로 받았는가. {@code NotificationHistory}에 {@code FAILED}가 아닌
     * 기록이 있으면 true, 기록이 없거나 {@code FAILED}뿐이면 false. {@code AnomalyEvent.escalated_at}은 "최초 승격 시각(참고용)"이라
     * Guardian별 판별에 쓸 수 없다(DATABASE_DESIGN_GUIDE.md §15.6).
     */
    private boolean notified;

    public static SummaryAnomalyResponse of(AnomalyEvent event, Place place, boolean notified) {
        return SummaryAnomalyResponse.builder()
                .anomalyEventId(event.getId())
                .type(event.getType())
                .status(
                        event.isOpen()
                                ? AnomalyEventResponse.STATUS_ONGOING
                                : AnomalyEventResponse.STATUS_RESOLVED)
                .detectedAt(event.getDetectedAt())
                .resolvedAt(event.getResolvedAt())
                .placeId(place != null ? place.getPublicId().toString() : null)
                .placeName(place != null ? place.getName() : null)
                .notified(notified)
                .build();
    }
}
