package com.tracecare.backend.domain.notification.fcm;

/**
 * FCM Push 발송 경계(API_Specification.md §5 {@code /internal/fcm/send}에 해당). 이 프로젝트에 Firebase Admin
 * SDK 의존성과 실제 서비스 계정 키(`FCM_CREDENTIALS_PATH`)가 아직 없어(build.gradle.kts/.env.example 확인 완료), 인터페이스만
 * 이번에 확정하고 구현체는 {@link LoggingFcmSender}(Stub)로 둔다 — 실제 Firebase 연동이 준비되면 이 인터페이스를 구현하는 새
 * {@code @Component}로 교체하기만 하면 되고, 호출부({@code NotificationDispatchService})는 변경할 필요가 없다.
 */
public interface FcmSender {

    /** 발송 성공 여부만 반환한다 — 실패 사유(토큰 없음/Firebase 오류 등)는 구현체가 로그로 남긴다. */
    boolean send(Long guardianId, String title, String body);
}
