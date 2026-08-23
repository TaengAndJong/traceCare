package com.tracecare.backend.domain.visit.event;

/**
 * 도착 감지 시점의 확장 지점(System_Overview.md §3 흐름도의 다음 단계: NotificationHistory 기록 + FCM 발송). 이번
 * 세션(Location Phase 3)은 알림 도메인이 범위 밖이라 리스너를 등록하지 않는다 — 다음 세션에서 {@code @EventListener}(또는 FCM 발송처럼
 * DB 트랜잭션 밖에서 실행돼야 하는 외부 I/O가 있다면 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} +
 * {@code @Async})로 이 이벤트를 구독해 이어붙이면 된다. {@code GeoFenceService}가 이 이벤트를 발행하는 시점은 VisitHistory 트랜잭션
 * 내부이므로, 트랜잭션 커밋 전에 외부 API를 호출하지 않는다는 원칙(database.md)을 지키려면 리스너 쪽에서 AFTER_COMMIT을 쓰는 것을 권장한다.
 */
public record VisitArrivedEvent(
        Long careTargetId, Long placeId, String placeName, java.time.Instant arrivalTime) {}
