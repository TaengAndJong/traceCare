package com.tracecare.backend.domain.emergency.dispatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.tracecare.backend.domain.auth.repository.UserRepository;

/**
 * 실제 통신사/SMS 연동 전까지 쓰는 Stub({@link EmergencyDispatcher} Javadoc 참고). {@link LoggingFcmSender}가
 * {@code fcm:token} 등록 여부로 실패를 재현했던 것과 동일한 방식으로, 대상 Guardian의 {@code User.phone}이 등록돼 있지 않으면 실패를
 * 재현한다 — 전화/SMS 발송은 연락처가 없으면 애초에 시도할 수 없는 게 현실이라, 이 Stub의 실패 모드가 실제로 의미 있는 신호다.
 *
 * @see com.tracecare.backend.domain.notification.fcm.LoggingFcmSender
 */
@Component
public class LoggingEmergencyDispatcher implements EmergencyDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmergencyDispatcher.class);

    private final UserRepository userRepository;

    public LoggingEmergencyDispatcher(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean dispatch(Long guardianId, String channel, String message) {
        boolean hasPhone =
                userRepository
                        .findById(guardianId)
                        .map(user -> user.getPhone() != null && !user.getPhone().isBlank())
                        .orElse(false);
        if (!hasPhone) {
            log.warn(
                    "event=EMERGENCY_DISPATCH_SKIPPED_NO_PHONE, guardianId={}, channel={}",
                    guardianId,
                    channel);
            return false;
        }
        log.info("event=EMERGENCY_DISPATCH_STUB, guardianId={}, channel={}", guardianId, channel);
        return true;
    }
}
