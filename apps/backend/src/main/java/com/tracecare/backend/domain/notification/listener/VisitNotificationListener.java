package com.tracecare.backend.domain.notification.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.tracecare.backend.domain.notification.service.NotificationDispatchService;
import com.tracecare.backend.domain.visit.event.VisitArrivedEvent;
import com.tracecare.backend.domain.visit.event.VisitDepartedEvent;

/**
 * {@code VisitArrivedEvent}/{@code VisitDepartedEvent} Javadoc이 남겨둔 확장 지점을 그대로 따른다: {@code
 * GeoFenceService}가 이벤트를 발행하는 시점이 VisitHistory 트랜잭션 내부이므로, FCM 발송(외부 I/O)이 그 트랜잭션의 커밋 여부와 무관하게 실행되지
 * 않도록 {@code AFTER_COMMIT}에서만 반응한다. {@code @Async}를 함께 적용해 원래 트랜잭션(위치 처리 요청)이 FCM 발송 완료를 기다리지 않게 한다
 * — 실패해도 원래 요청/트랜잭션에는 영향이 없다({@code NotificationDispatchService}가 예외를 전파하지 않고 이력만 남기므로, 여기서도 별도 예외
 * 처리를 하지 않는다).
 */
@Component
public class VisitNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(VisitNotificationListener.class);

    private final NotificationDispatchService notificationDispatchService;

    public VisitNotificationListener(NotificationDispatchService notificationDispatchService) {
        this.notificationDispatchService = notificationDispatchService;
    }

    @Async("notificationTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVisitArrived(VisitArrivedEvent event) {
        notificationDispatchService.dispatchArrival(event.careTargetId(), event.placeName());
    }

    /**
     * 이탈(GeoFence Exit)은 NotificationHistory.type CHECK 제약의 7종에 포함되지 않는다(DATABASE_DESIGN_GUIDE.md
     * §13 — 근거 부족으로 명시적 제외 확정). 존재하지 않는 type으로 행을 만들 수 없으므로 알림을 만들지 않고 로그만 남긴다 — 이벤트 자체는 구독해 둬서(확장
     * 지점 유지) 향후 정책이 바뀌면 이 메서드 하나만 채우면 된다.
     */
    @Async("notificationTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVisitDeparted(VisitDepartedEvent event) {
        log.debug(
                "event=VISIT_DEPARTED_NOTIFICATION_SKIPPED, careTargetId={}, reason=type_not_in_whitelist",
                event.careTargetId());
    }
}
