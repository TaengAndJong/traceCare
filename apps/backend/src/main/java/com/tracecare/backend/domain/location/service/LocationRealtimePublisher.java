package com.tracecare.backend.domain.location.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;

/**
 * Security_Guide.md §7.5.2 — 공용 Topic 대신 개인화 큐(`convertAndSendToUser`)만 쓴다. 이 방식에서는 "잘못된 CareTarget
 * id를 구독"하는 공격 자체가 성립하지 않는다(클라이언트가 다른 사용자의 큐를 지정할 방법이 없음) — 그래서 SUBSCRIBE 시점 소유권 재검증 로직이 별도로 없다.
 *
 * <p>발행 대상은 {@link GuardianTargetRepository#findByTargetIdAndStatus}로 조회한 해당 CareTarget의 ACTIVE
 * Guardian 전원(PRIMARY+SUB 구분 없음) — Place의 PRIMARY 전용 권한과 달리, 위치 실시간 수신은 안전 기능이라 전원 공유
 * 대상이다(DATABASE_DESIGN_GUIDE.md §3.2 "안전 관련 기능은 전원 공유" 원칙과 동일).
 */
@Service
public class LocationRealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(LocationRealtimePublisher.class);
    private static final String QUEUE_DESTINATION = "/queue/location";

    private final GuardianTargetRepository guardianTargetRepository;
    private final SimpMessagingTemplate simpMessagingTemplate;

    public LocationRealtimePublisher(
            GuardianTargetRepository guardianTargetRepository,
            SimpMessagingTemplate simpMessagingTemplate) {
        this.guardianTargetRepository = guardianTargetRepository;
        this.simpMessagingTemplate = simpMessagingTemplate;
    }

    public void publish(
            Long careTargetInternalId,
            String careTargetPublicId,
            Double latitude,
            Double longitude,
            Instant recordedAt) {
        List<GuardianTarget> activeGuardians =
                guardianTargetRepository.findByTargetIdAndStatus(
                        careTargetInternalId, GuardianTarget.STATUS_ACTIVE);
        if (activeGuardians.isEmpty()) {
            return;
        }

        LocationUpdateFrame frame =
                LocationUpdateFrame.of(careTargetPublicId, latitude, longitude, recordedAt);
        for (GuardianTarget guardianTarget : activeGuardians) {
            sendToGuardian(guardianTarget.getGuardianId(), frame);
        }
    }

    /**
     * 한 Guardian에게 보내다 실패해도(연결 끊김 등) 나머지 Guardian 발행과 REST 응답 흐름에 영향을 주지 않도록 Guardian별로 개별
     * try-catch한다 — 실시간 알림은 최선 노력(best-effort) 전달이고, 원본 데이터는 이미 LocationHistory/Redis에 저장되어 있어 유실되지
     * 않는다.
     */
    private void sendToGuardian(Long guardianId, LocationUpdateFrame frame) {
        try {
            simpMessagingTemplate.convertAndSendToUser(
                    String.valueOf(guardianId), QUEUE_DESTINATION, frame);
        } catch (MessagingException e) {
            log.warn("event=LOCATION_REALTIME_PUBLISH_FAILED, guardianId={}", guardianId, e);
        }
    }
}
