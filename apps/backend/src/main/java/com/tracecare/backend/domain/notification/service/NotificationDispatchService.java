package com.tracecare.backend.domain.notification.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.common.exception.business.CareTargetNotFoundException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.notification.entity.NotificationHistory;
import com.tracecare.backend.domain.notification.fcm.FcmSender;
import com.tracecare.backend.domain.notification.repository.NotificationHistoryRepository;

/**
 * System_Overview.md §3의 {@code /internal/fcm/send} 단계. GeoFenceService의 {@code
 * /internal/geofence/check}와 같은 이유로 별도 HTTP 엔드포인트를 열지 않고 내부 메서드로 둔다({@code
 * VisitNotificationListener}가 이벤트 리스너에서 직접 호출) — API_Response_Rule.md §8.6도 "클라이언트 미노출"이라고 명시한다.
 *
 * <p>실패 처리: 일반 알림(이번 범위 — ARRIVAL)은 Guardian 1명에게 보내다 실패해도 나머지 Guardian 발송과 리스너 전체 흐름을 막지 않는다 —
 * {@code status=FAILED}로 이력만 남기고 예외를 던지지 않는다. Exception_Handling_Rule.md §9.2의 EMERGENCY
 * fail-safe(재시도/사용자에게 실패 자체를 알림)는 긴급 연락(`POST /api/care-target/emergency/call`, 사용자가 직접 트리거하고 응답을
 * 기다리는 동기 흐름) 전용 요구사항이라 이번 범위(GeoFence 도착, 비동기 백그라운드 흐름)에는 적용하지 않는다 — 이 판단 근거는 결과 보고에도 남긴다.
 */
@Service
public class NotificationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

    private final GuardianTargetRepository guardianTargetRepository;
    private final UserRepository userRepository;
    private final NotificationHistoryRepository notificationHistoryRepository;
    private final FcmSender fcmSender;

    public NotificationDispatchService(
            GuardianTargetRepository guardianTargetRepository,
            UserRepository userRepository,
            NotificationHistoryRepository notificationHistoryRepository,
            FcmSender fcmSender) {
        this.guardianTargetRepository = guardianTargetRepository;
        this.userRepository = userRepository;
        this.notificationHistoryRepository = notificationHistoryRepository;
        this.fcmSender = fcmSender;
    }

    /** ACTIVE Guardian 전원(PRIMARY+SUB)에게 개별 행을 생성한다 — 같은 트리거는 동일 {@code event_id}로 묶는다. */
    @Transactional
    public void dispatchArrival(Long careTargetId, String placeName) {
        List<GuardianTarget> guardians =
                guardianTargetRepository.findByTargetIdAndStatus(
                        careTargetId, GuardianTarget.STATUS_ACTIVE);
        if (guardians.isEmpty()) {
            return;
        }

        User target =
                userRepository.findById(careTargetId).orElseThrow(CareTargetNotFoundException::new);
        String title = "도착 알림";
        String body = target.getName() + "님이 '" + placeName + "'에 도착했습니다";
        UUID eventId = UUID.randomUUID();

        for (GuardianTarget guardianTarget : guardians) {
            sendToGuardian(
                    guardianTarget.getGuardianId(), careTargetId, eventId, title, body, placeName);
        }
    }

    private void sendToGuardian(
            Long guardianId,
            Long targetId,
            UUID eventId,
            String title,
            String body,
            String placeName) {
        NotificationHistory notification =
                NotificationHistory.create(
                        guardianId, targetId, NotificationHistory.TYPE_ARRIVAL, eventId, placeName);

        boolean sent = fcmSender.send(guardianId, title, body);
        if (!sent) {
            notification.markFailed();
            log.warn(
                    "event=NOTIFICATION_SEND_FAILED, guardianId={}, targetId={}, type={}",
                    guardianId,
                    targetId,
                    NotificationHistory.TYPE_ARRIVAL);
        }
        notificationHistoryRepository.save(notification);
    }
}
