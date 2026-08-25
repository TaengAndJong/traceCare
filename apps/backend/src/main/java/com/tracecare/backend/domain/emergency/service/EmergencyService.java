package com.tracecare.backend.domain.emergency.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.tracecare.backend.common.exception.business.CareTargetNotFoundException;
import com.tracecare.backend.common.exception.business.EmergencyContactMissingException;
import com.tracecare.backend.common.exception.external.EmergencyDispatchException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.emergency.dispatcher.EmergencyDispatcher;
import com.tracecare.backend.domain.emergency.dispatcher.EmergencyEscalationNotifier;
import com.tracecare.backend.domain.emergency.dto.response.EmergencyResponse;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.location.caretarget.dto.request.LocationSendRequest;
import com.tracecare.backend.domain.location.caretarget.service.LocationService;
import com.tracecare.backend.domain.notification.entity.NotificationHistory;
import com.tracecare.backend.domain.notification.fcm.FcmSender;
import com.tracecare.backend.domain.notification.repository.NotificationHistoryRepository;

/**
 * API_Specification.md §4.4, Exception_Handling_Rule.md §9.2(fail-safe 대상, Fallback 제외). 이 클래스의
 * 메서드는 의도적으로 클래스/메서드 레벨 {@code @Transactional}을 걸지 않는다 — database.md가 금지하는 "트랜잭션 내부에서 외부 API 호출"
 * 상황을 원천적으로 피하기 위해, Guardian별 {@code NotificationHistory} 저장은 {@code
 * notificationHistoryRepository.save()} 호출 하나하나가 Spring Data JPA 자체의 트랜잭션 경계를 쓰도록 둔다(발송 시도 자체는 트랜잭션
 * 밖에서 일어남).
 *
 * <p><b>재시도 책임</b>: API_Response_Rule.md §8.9가 "Frontend 자동 재시도 1회"라고 명시하므로, 이 클래스는 서버 사이드 재시도 없이
 * 1회 시도 결과를 그대로 즉시 반환한다.
 *
 * <p><b>성공 판단 기준</b>: ACTIVE Guardian 전원에게 개별 시도하되, 1명 이상 성공하면 전체 성공으로 응답한다(연락 가능성 최대화가 안전 기능의 우선순위
 * — 일부 실패로 전체를 실패 처리하면 실제로 연락이 닿았는데도 사용자에게 "실패"로 보여 대체 수단을 찾다가 오히려 지연될 수 있다). 전원 실패했을 때만 {@link
 * EmergencyDispatchException}(EMERGENCY_003).
 */
@Service
public class EmergencyService {

    private static final Logger log = LoggerFactory.getLogger(EmergencyService.class);

    private static final String CHANNEL_CALL = "CALL";
    private static final String CHANNEL_MESSAGE = "MESSAGE";
    private static final String CHANNEL_LOCATION = "LOCATION";

    private final GuardianTargetRepository guardianTargetRepository;
    private final UserRepository userRepository;
    private final NotificationHistoryRepository notificationHistoryRepository;
    private final EmergencyDispatcher emergencyDispatcher;
    private final FcmSender fcmSender;
    private final LocationService locationService;
    private final EmergencyEscalationNotifier escalationNotifier;

    public EmergencyService(
            GuardianTargetRepository guardianTargetRepository,
            UserRepository userRepository,
            NotificationHistoryRepository notificationHistoryRepository,
            EmergencyDispatcher emergencyDispatcher,
            FcmSender fcmSender,
            LocationService locationService,
            EmergencyEscalationNotifier escalationNotifier) {
        this.guardianTargetRepository = guardianTargetRepository;
        this.userRepository = userRepository;
        this.notificationHistoryRepository = notificationHistoryRepository;
        this.emergencyDispatcher = emergencyDispatcher;
        this.fcmSender = fcmSender;
        this.locationService = locationService;
        this.escalationNotifier = escalationNotifier;
    }

    /** POST /api/care-target/emergency/call */
    public EmergencyResponse call(Long callerId) {
        return dispatchViaChannel(callerId, CHANNEL_CALL);
    }

    /** POST /api/care-target/emergency/message */
    public EmergencyResponse message(Long callerId) {
        return dispatchViaChannel(callerId, CHANNEL_MESSAGE);
    }

    /**
     * POST /api/care-target/emergency/location — §4.3(share/location)의 위치 전달 로직(Redis 갱신 +
     * WebSocket 개인화 큐 발행 + GeoFence 판정 + 비동기 저장)을 완전히 재사용한다. 그 로직 자체를 복제하지 않고, "긴급 상황이니 Guardian이
     * 지금 앱을 보고 있지 않아도 알아채게 만드는" 능동적 알림(FCM push)만 별도로 얹는다 — WebSocket 발행은 앱을 켜고 봐야 보이는 수동적 채널이라 안전
     * 기능의 "즉시 인지"라는 목적에는 그 자체로 부족하다.
     */
    public EmergencyResponse location(Long callerId, LocationSendRequest request) {
        List<GuardianTarget> guardians = activeGuardiansOrThrow(callerId);
        locationService.shareLocation(callerId, request);
        return dispatch(
                callerId,
                guardians,
                CHANNEL_LOCATION,
                (guardianId, message) -> sendLocationAlert(guardianId, message));
    }

    private List<GuardianTarget> activeGuardiansOrThrow(Long callerId) {
        List<GuardianTarget> guardians =
                guardianTargetRepository.findByTargetIdAndStatus(
                        callerId, GuardianTarget.STATUS_ACTIVE);
        if (guardians.isEmpty()) {
            throw new EmergencyContactMissingException();
        }
        return guardians;
    }

    private EmergencyResponse dispatchViaChannel(Long callerId, String channel) {
        List<GuardianTarget> guardians = activeGuardiansOrThrow(callerId);
        return dispatch(
                callerId,
                guardians,
                channel,
                (guardianId, message) ->
                        emergencyDispatcher.dispatch(guardianId, channel, message));
    }

    /**
     * 전원 실패는 이 서비스에서 벌어질 수 있는 가장 심각한 실패 상황이라 WARN이 아니라 ERROR로 남기고, 감사용 {@code NotificationHistory}
     * 이력 저장과는 별개로 {@link EmergencyEscalationNotifier}를 호출해 사람이 지금 당장 인지해야 하는 경보 경로도 함께 태운다.
     */
    private EmergencyResponse dispatch(
            Long callerId,
            List<GuardianTarget> guardians,
            String channel,
            DispatchAttempt attempt) {
        User target =
                userRepository.findById(callerId).orElseThrow(CareTargetNotFoundException::new);
        String message = target.getName() + "님이 긴급 연락을 요청했습니다";
        UUID eventId = UUID.randomUUID();

        boolean anySucceeded = false;
        for (GuardianTarget guardianTarget : guardians) {
            Long guardianId = guardianTarget.getGuardianId();
            boolean sent = attempt.tryDispatch(guardianId, message);
            saveNotification(guardianId, callerId, eventId, sent);
            anySucceeded = anySucceeded || sent;
        }

        if (!anySucceeded) {
            log.error(
                    "event=EMERGENCY_DISPATCH_ALL_FAILED, careTargetId={}, eventId={}, channel={}",
                    callerId,
                    eventId,
                    channel);
            escalationNotifier.notifyAllFailed(callerId, eventId, channel);
            throw new EmergencyDispatchException();
        }
        return EmergencyResponse.builder().eventId(eventId).guardianContacted(true).build();
    }

    private boolean sendLocationAlert(Long guardianId, String message) {
        return fcmSender.send(guardianId, "긴급 위치 공유", message);
    }

    private void saveNotification(Long guardianId, Long targetId, UUID eventId, boolean sent) {
        NotificationHistory notification =
                NotificationHistory.create(
                        guardianId, targetId, NotificationHistory.TYPE_EMERGENCY, eventId, null);
        if (!sent) {
            notification.markFailed();
        }
        notificationHistoryRepository.save(notification);
    }

    @FunctionalInterface
    private interface DispatchAttempt {
        boolean tryDispatch(Long guardianId, String message);
    }
}
