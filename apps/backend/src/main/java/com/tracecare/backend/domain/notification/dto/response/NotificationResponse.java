package com.tracecare.backend.domain.notification.dto.response;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.notification.entity.NotificationHistory;

/**
 * API_Specification.md §3.7, API_Response_Rule.md §8.6. {@code title} 컬럼은 DB에 없어 조회 시점에 {@code
 * type}으로 생성하고, {@code body}는 {@code NotificationHistory.place_name} 스냅샷(2026-08 후속 보완)이 있으면 실제
 * 문구("{place_name}에 도착했습니다")를, 없으면(이 컬럼 추가 이전에 생성된 레코드) 일반화된 문구로 폴백한다.
 */
@Getter
@Builder
@AllArgsConstructor
public class NotificationResponse {

    private Long notificationId;
    private String type;
    private String title;
    private String body;
    private boolean isRead;
    private Instant sentAt;

    /**
     * Lombok이 {@code boolean isRead} 필드에 대해 만드는 getter는 {@code isRead()}인데, Jackson은 이 "is" 접두사를
     * 관례상 다시 벗겨내 암묵적으로 "read"라는 별도 프로퍼티를 인식한다 — 필드에 {@code @JsonProperty("isRead")}를 붙여도 이 getter가
     * 만드는 "read" 프로퍼티와 다른 프로퍼티로 취급돼 {@code isRead}/{@code read} 두 키가 동시에 나가버린다(직접 확인). 그래서 이
     * getter를 직접 선언해 {@code @JsonProperty}로 JSON 키를 명시적으로 고정한다 — Lombok이 자동 생성하는 getter는 만들어지지
     * 않는다(직접 선언한 메서드가 우선한다).
     */
    @JsonProperty("isRead")
    public boolean isRead() {
        return isRead;
    }

    public static NotificationResponse of(NotificationHistory notification, User target) {
        return NotificationResponse.builder()
                .notificationId(notification.getId())
                .type(notification.getType())
                .title(title(notification.getType()))
                .body(body(notification, target))
                .isRead(notification.isRead())
                .sentAt(notification.getSentAt())
                .build();
    }

    private static String title(String type) {
        return switch (type) {
            case NotificationHistory.TYPE_ARRIVAL -> "도착 알림";
            case NotificationHistory.TYPE_ARRIVAL_CONFIRM_REQUEST -> "도착 확인 요청";
            case NotificationHistory.TYPE_ARRIVAL_CONFIRMED -> "도착 확인 완료";
            case NotificationHistory.TYPE_EMERGENCY -> "긴급 연락";
            case NotificationHistory.TYPE_AI_ANOMALY -> "이상 이동 감지";
            case NotificationHistory.TYPE_AI_PREDICTION -> "방문 예측 알림";
            case NotificationHistory.TYPE_AI_WEEKLY_REPORT -> "주간 리포트";
            default -> "알림";
        };
    }

    /** {@code place_name}이 null이면(이 컬럼 추가 이전 레코드) 장소명 없이 일반화된 문구로 폴백한다. */
    private static String body(NotificationHistory notification, User target) {
        if (NotificationHistory.TYPE_ARRIVAL.equals(notification.getType())) {
            return notification.getPlaceName() != null
                    ? target.getName() + "님이 '" + notification.getPlaceName() + "'에 도착했습니다"
                    : target.getName() + "님이 등록된 장소에 도착했습니다";
        }
        return title(notification.getType());
    }
}
