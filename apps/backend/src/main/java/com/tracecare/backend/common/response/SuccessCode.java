package com.tracecare.backend.common.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SuccessCode {
    AUTH_001("AUTH_001", "로그인 성공"),
    AUTH_002("AUTH_002", "토큰 재발급 성공"),
    AUTH_003("AUTH_003", "로그아웃 성공"),
    USER_001("USER_001", "사용자 정보 조회 성공"),
    USER_002("USER_002", "프로필 수정 성공"),
    TARGET_001("TARGET_001", "보호 대상자 목록/상세 조회 성공"),
    TARGET_002("TARGET_002", "보호 대상자 등록 성공"),
    LOCATION_001("LOCATION_001", "위치 조회 성공"),
    LOCATION_002("LOCATION_002", "위치 전송 성공"),
    PLACE_001("PLACE_001", "장소(안심구역) 등록/조회 성공"),
    NOTI_001("NOTI_001", "알림 조회 성공"),
    NOTI_002("NOTI_002", "알림 읽음 처리 성공"),
    AI_001("AI_001", "AI 응답 생성 성공");

    private final String code;
    private final String message;
}
