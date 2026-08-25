package com.tracecare.backend.domain.emergency.dispatcher;

/**
 * 긴급 연락(전화 연결/SMS) 발송 경계. 이 프로젝트에 통신사 API/SMS 게이트웨이 자격 증명이 아직 없어(build.gradle.kts에 관련 의존성 없음,
 * .env.example에도 플레이스홀더조차 없음 — FCM_CREDENTIALS_PATH만 있고 SMS/통신사 키는 확인되지 않음), FCM 때와 동일한 패턴으로 인터페이스만
 * 확정하고 구현체는 {@link LoggingEmergencyDispatcher}(Stub)로 둔다. 실제 연동이 준비되면 이 인터페이스를 구현하는 새
 * {@code @Component}로 교체하면 되고 호출부({@code EmergencyService})는 변경할 필요가 없다.
 *
 * <p>Exception_Handling_Rule.md §9.2가 EMERGENCY를 Fallback 대상에서 명시적으로 제외하므로, 이 인터페이스의 구현체는 실패를 삼키지
 * 않고 boolean으로 정확히 알려야 한다 — 호출부가 그 결과로 {@code NotificationHistory.status}를 SENT/FAILED로 정확히 나눈다.
 */
public interface EmergencyDispatcher {

    /** {@code channel}은 "CALL"/"MESSAGE" — 실패 사유는 구현체가 로그로 남긴다. */
    boolean dispatch(Long guardianId, String channel, String message);
}
