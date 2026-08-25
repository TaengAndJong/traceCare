package com.tracecare.backend.domain.emergency.dispatcher;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 실제 운영 경보 채널(Slack/PagerDuty 등) 연동 전까지 쓰는 Stub({@link EmergencyEscalationNotifier} Javadoc 참고).
 */
@Component
public class LoggingEscalationNotifier implements EmergencyEscalationNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingEscalationNotifier.class);

    @Override
    public void notifyAllFailed(Long careTargetId, UUID eventId, String channel) {
        log.error(
                "event=EMERGENCY_ESCALATION, careTargetId={}, eventId={}, channel={}, reason=ALL_GUARDIANS_UNREACHABLE",
                careTargetId,
                eventId,
                channel);
    }
}
