package com.tracecare.backend.domain.emergency.dto.response;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * API_Specification.md §4.4, API_Response_Rule.md §8.9. 문서 예시의 {@code notificationId}(단일 int)는
 * ACTIVE Guardian 전원에게 개별 행이 생성되는 실제 구조(NotificationHistory.event_id 그룹핑)와 맞지 않아 {@code
 * eventId}(UUID)로 대체했다 — 근거는 결과 보고 참고.
 */
@Getter
@Builder
@AllArgsConstructor
public class EmergencyResponse {

    private UUID eventId;
    private boolean guardianContacted;
}
