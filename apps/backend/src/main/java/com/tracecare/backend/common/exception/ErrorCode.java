package com.tracecare.backend.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // Common
    COMMON_001("COMMON_001", HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
    COMMON_002("COMMON_002", HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다"),
    COMMON_003("COMMON_003", HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다"),
    COMMON_004("COMMON_004", HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다"),
    COMMON_005("COMMON_005", HttpStatus.TOO_MANY_REQUESTS, "요청 횟수를 초과했습니다"),
    COMMON_006("COMMON_006", HttpStatus.FORBIDDEN, "요청자의 권한으로는 접근할 수 없는 API입니다"),
    COMMON_007(
            "COMMON_007", HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해주세요"),
    COMMON_008("COMMON_008", HttpStatus.CONFLICT, "다른 요청과 충돌했습니다. 잠시 후 다시 시도해주세요"),

    // Auth
    AUTH_001("AUTH_001", HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
    AUTH_002("AUTH_002", HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다"),
    AUTH_003("AUTH_003", HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다"),
    AUTH_004("AUTH_004", HttpStatus.UNAUTHORIZED, "다시 로그인해주세요"),
    AUTH_005("AUTH_005", HttpStatus.UNAUTHORIZED, "Google 인증에 실패했습니다"),
    AUTH_006("AUTH_006", HttpStatus.UNAUTHORIZED, "로그아웃된 토큰입니다"),

    // User
    USER_001("USER_001", HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"),
    USER_002("USER_002", HttpStatus.CONFLICT, "이미 가입된 사용자입니다"),
    USER_003("USER_003", HttpStatus.BAD_REQUEST, "역할(보호자/보호대상자) 선택이 필요합니다"),
    USER_004("USER_004", HttpStatus.CONFLICT, "이미 역할이 확정된 사용자입니다"),

    // Guardian
    GUARDIAN_001("GUARDIAN_001", HttpStatus.FORBIDDEN, "보호자만 접근할 수 있습니다"),
    GUARDIAN_002("GUARDIAN_002", HttpStatus.NOT_FOUND, "보호자 정보를 찾을 수 없습니다"),
    GUARDIAN_003("GUARDIAN_003", HttpStatus.CONFLICT, "등록 가능한 보호대상자 수를 초과했습니다"),
    GUARDIAN_004("GUARDIAN_004", HttpStatus.FORBIDDEN, "대표(PRIMARY) 보호자만 가능합니다"),
    GUARDIAN_005("GUARDIAN_005", HttpStatus.FORBIDDEN, "위임 대상이 활성 상태의 보조 보호자가 아닙니다"),
    GUARDIAN_006("GUARDIAN_006", HttpStatus.CONFLICT, "본인을 위임 대상으로 지정할 수 없습니다"),

    // Target
    TARGET_001("TARGET_001", HttpStatus.NOT_FOUND, "보호 대상자를 찾을 수 없습니다"),
    TARGET_002("TARGET_002", HttpStatus.FORBIDDEN, "접근 권한이 없는 보호대상자입니다"),
    TARGET_003("TARGET_003", HttpStatus.CONFLICT, "이미 등록된 보호대상자입니다"),
    TARGET_004("TARGET_004", HttpStatus.BAD_REQUEST, "초대 코드가 유효하지 않거나 만료되었습니다"),
    TARGET_005("TARGET_005", HttpStatus.CONFLICT, "보호자 등록 정원을 초과했습니다"),
    TARGET_006("TARGET_006", HttpStatus.CONFLICT, "이미 대기 중인 연결 요청이 있습니다"),
    TARGET_007("TARGET_007", HttpStatus.TOO_MANY_REQUESTS, "초대 코드 생성 횟수를 초과했습니다"),

    // Location
    LOCATION_001("LOCATION_001", HttpStatus.BAD_REQUEST, "위도/경도 값이 올바르지 않습니다"),
    LOCATION_002("LOCATION_002", HttpStatus.NOT_FOUND, "조회 가능한 위치 정보가 없습니다"),
    LOCATION_003("LOCATION_003", HttpStatus.FORBIDDEN, "보호대상자만 위치를 전송할 수 있습니다"),
    LOCATION_004("LOCATION_004", HttpStatus.FORBIDDEN, "보호대상자만 위치를 공유할 수 있습니다"),

    // Place
    PLACE_001("PLACE_001", HttpStatus.NOT_FOUND, "등록된 장소를 찾을 수 없습니다"),
    PLACE_002("PLACE_002", HttpStatus.CONFLICT, "이미 등록된 장소입니다"),
    PLACE_003("PLACE_003", HttpStatus.BAD_REQUEST, "GeoFence 반경 값이 올바르지 않습니다"),

    // Notification
    NOTI_001("NOTI_001", HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다"),
    NOTI_002("NOTI_002", HttpStatus.INTERNAL_SERVER_ERROR, "알림 발송에 실패했습니다"),
    NOTI_003("NOTI_003", HttpStatus.BAD_REQUEST, "FCM 토큰이 등록되지 않은 기기입니다"),

    // AI
    AI_001("AI_001", HttpStatus.INTERNAL_SERVER_ERROR, "AI 서버 응답이 지연되고 있습니다"),
    AI_002("AI_002", HttpStatus.INTERNAL_SERVER_ERROR, "AI 응답 생성에 실패했습니다"),
    AI_003("AI_003", HttpStatus.NOT_FOUND, "AI 예측 결과가 없습니다"),
    AI_004("AI_004", HttpStatus.TOO_MANY_REQUESTS, "AI 서비스 호출 한도를 초과했습니다"),

    // Visit
    VISIT_001("VISIT_001", HttpStatus.NOT_FOUND, "조회 가능한 방문 이력이 없습니다"),
    VISIT_002("VISIT_002", HttpStatus.BAD_REQUEST, "조회 기간 값이 올바르지 않습니다"),

    // Arrival
    ARRIVAL_001("ARRIVAL_001", HttpStatus.FORBIDDEN, "보호대상자만 도착 확인을 할 수 있습니다"),
    ARRIVAL_002("ARRIVAL_002", HttpStatus.BAD_REQUEST, "등록된 장소 범위를 벗어나 도착 확인이 불가능합니다"),
    ARRIVAL_003("ARRIVAL_003", HttpStatus.NOT_FOUND, "조회 가능한 도착 기록이 없습니다"),

    // Emergency
    EMERGENCY_001("EMERGENCY_001", HttpStatus.FORBIDDEN, "보호대상자만 긴급 연락을 할 수 있습니다"),
    EMERGENCY_002("EMERGENCY_002", HttpStatus.BAD_REQUEST, "등록된 보호자 연락처가 없습니다"),
    EMERGENCY_003("EMERGENCY_003", HttpStatus.INTERNAL_SERVER_ERROR, "긴급 연락 발송에 실패했습니다. 다시 시도해주세요");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
