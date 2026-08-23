package com.tracecare.backend.domain.visit.event;

import java.time.Instant;

/** {@link VisitArrivedEvent}와 동일한 확장 지점 원칙 — 이탈(GeoFence Exit) 감지 시 발행한다. */
public record VisitDepartedEvent(
        Long careTargetId,
        Long placeId,
        String placeName,
        Instant departureTime,
        int stayMinutes) {}
