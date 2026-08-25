package com.tracecare.backend.domain.emergency.dispatcher;

import java.util.UUID;

/**
 * ACTIVE Guardian 전원에게 긴급 연락 시도가 전부 실패했을 때(이 서비스에서 벌어질 수 있는 가장 심각한 실패 상황) 호출되는 운영 경보 경계. 지금은 실제
 * Slack/PagerDuty 등 연동이 없어 {@link LoggingEscalationNotifier}(Stub, ERROR 로그만 남김)로 시작한다 — 나중에 실제 경보
 * 채널이 준비되면 이 인터페이스를 구현하는 새 {@code @Component}로 교체하면 되고 호출부 ({@code EmergencyService})는 변경할 필요가
 * 없다({@code EmergencyDispatcher}/{@code FcmSender}와 동일한 패턴).
 *
 * <p>{@code NotificationHistory.status=FAILED} 이력 저장(감사 목적)과는 별개다 — 이 알림은 "나중에 조회 가능한 기록"이 아니라 "지금
 * 당장 사람이 인지해야 하는 경보"를 위한 것이다.
 */
public interface EmergencyEscalationNotifier {

    void notifyAllFailed(Long careTargetId, UUID eventId, String channel);
}
