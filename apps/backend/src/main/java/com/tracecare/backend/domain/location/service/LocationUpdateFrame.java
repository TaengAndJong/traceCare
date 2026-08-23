package com.tracecare.backend.domain.location.service;

import java.time.Instant;

/**
 * `/queue/location`으로 발행되는 WebSocket 메시지 프레임(API_Specification.md §5). 문서의 기존 예시에는 {@code
 * careTargetId}가 없었는데, 그건 CareTarget별 공용 Topic(`/topic/location/{id}`)을 전제로 한 구버전 예시였다 — 개인화
 * 큐(`convertAndSendToUser`)로 바꾸면 한 Guardian의 모든 CareTarget 위치가 같은
 * 큐(`/user/{guardianId}/queue/location`)로 들어오므로, 페이로드 자체에 어느 CareTarget인지 식별자가 반드시 있어야 한다(2026-08
 * Phase 2에서 문서에 반영, careTargetId는 public_id).
 */
public record LocationUpdateFrame(String type, Payload payload, Instant timestamp) {

    private static final String TYPE_LOCATION_UPDATE = "LOCATION_UPDATE";

    public static LocationUpdateFrame of(
            String careTargetId, Double latitude, Double longitude, Instant recordedAt) {
        return new LocationUpdateFrame(
                TYPE_LOCATION_UPDATE,
                new Payload(careTargetId, latitude, longitude, recordedAt),
                Instant.now());
    }

    public record Payload(
            String careTargetId, Double latitude, Double longitude, Instant recordedAt) {}
}
