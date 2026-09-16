package com.tracecare.backend.domain.notification.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.common.exception.business.CareTargetNotFoundException;
import com.tracecare.backend.domain.anomaly.entity.AnomalyEvent;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.notification.entity.NotificationHistory;
import com.tracecare.backend.domain.notification.fcm.FcmSender;
import com.tracecare.backend.domain.notification.repository.NotificationHistoryRepository;
import com.tracecare.backend.domain.place.entity.Place;
import com.tracecare.backend.domain.place.repository.PlaceRepository;

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
    private final PlaceRepository placeRepository;
    private final FcmSender fcmSender;

    public NotificationDispatchService(
            GuardianTargetRepository guardianTargetRepository,
            UserRepository userRepository,
            NotificationHistoryRepository notificationHistoryRepository,
            PlaceRepository placeRepository,
            FcmSender fcmSender) {
        this.guardianTargetRepository = guardianTargetRepository;
        this.userRepository = userRepository;
        this.notificationHistoryRepository = notificationHistoryRepository;
        this.placeRepository = placeRepository;
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

    /**
     * {@code AnomalyScheduler} 역할2(승격) 전용(§4.2 확정: B). 의도적으로 {@code @Transactional}을 이 메서드에
     * 붙이지 않는다 — {@link #dispatchArrival}처럼 메서드 전체를 하나의 트랜잭션으로 감싸면 그 안에서 호출하는 {@link
     * FcmSender#send}(외부 I/O)까지 트랜잭션에 포함돼 {@code database.md}의 "트랜잭션 내부에서 외부 API 호출 금지"
     * 원칙과 어긋난다(실제로 {@link #dispatchArrival}이 이미 이 문제를 갖고 있음을 이번에 발견했다 — 이번 범위에서는
     * 고치지 않고 별도 이슈로만 남긴다). 이 메서드는 그 문제를 새로 만들지 않기 위해 트랜잭션을 아예 선언하지 않고, 대신
     * {@code placeRepository}/{@code userRepository}/{@code notificationHistoryRepository}의 개별 호출이
     * Spring Data JPA({@code SimpleJpaRepository})가 각 메서드에 이미 걸어둔 짧은 트랜잭션에 의존한다 — 조회 2번,
     * FCM 호출(트랜잭션 밖), 저장 1번이 각각 별도의 짧은 트랜잭션으로 실행되는 구조다.
     */
    public void dispatchAnomalyEscalation(AnomalyEscalationTarget target) {
        String placeName = resolvePlaceName(target.placeId());
        String targetName = resolveTargetName(target.careTargetId());
        String title = buildEscalationTitle(target.anomalyType());
        String body = buildEscalationBody(target.anomalyType(), targetName, placeName);

        boolean sent = fcmSender.send(target.guardianId(), title, body);

        NotificationHistory notification =
                NotificationHistory.createForAnomaly(
                        target.guardianId(),
                        target.careTargetId(),
                        UUID.randomUUID(),
                        placeName,
                        target.anomalyEventId());
        if (!sent) {
            notification.markFailed();
            // EMERGENCY_*와 달리 재시도/별도 경보 없이 이력만 남긴다 — Guardian은 AnomalyEvent 목록/
            // /summary로 여전히 확인 가능해 푸시가 유일한 통지 경로가 아니다(§4.2 확정: C, dispatchArrival과
            // 동일한 낮은 심각도).
            log.warn(
                    "event=ANOMALY_NOTIFICATION_SEND_FAILED, guardianId={}, anomalyEventId={}, type={}",
                    target.guardianId(),
                    target.anomalyEventId(),
                    target.anomalyType());
        }
        notificationHistoryRepository.save(notification);
    }

    private String resolvePlaceName(Long placeId) {
        if (placeId == null) {
            return null;
        }
        return placeRepository.findById(placeId).map(Place::getName).orElse(null);
    }

    private String resolveTargetName(Long careTargetId) {
        return userRepository.findById(careTargetId).map(User::getName).orElse("보호대상자");
    }

    private String buildEscalationTitle(String anomalyType) {
        return AnomalyEvent.TYPE_ARRIVAL_DELAY.equals(anomalyType) ? "도착 지연 알림" : "이상 위치 감지";
    }

    private String buildEscalationBody(String anomalyType, String targetName, String placeName) {
        if (AnomalyEvent.TYPE_ARRIVAL_DELAY.equals(anomalyType)) {
            String place = placeName != null ? "'" + placeName + "'" : "예정된 장소";
            return targetName + "님이 " + place + "에 아직 도착하지 않았습니다";
        }
        String near = placeName != null ? "(" + placeName + " 근처) " : "";
        return targetName + "님이 등록되지 않은 장소" + near + "에 머물고 있습니다";
    }

    /** 승격 대상 1건(Guardian×AnomalyEvent 조합, §4.2 확정: A — Guardian별 개별 판단이라 이벤트당 여러 건 나올 수 있음). */
    public record AnomalyEscalationTarget(
            Long guardianId, Long careTargetId, Long anomalyEventId, String anomalyType, Long placeId) {}
}
