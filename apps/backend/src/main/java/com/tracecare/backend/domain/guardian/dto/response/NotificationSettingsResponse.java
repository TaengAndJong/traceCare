package com.tracecare.backend.domain.guardian.dto.response;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.guardian.entity.GuardianTarget;

/**
 * API_Specification.md §3.10 응답(GET/PUT/pause/resume 공통). 정지는 모드 값이 아니라 오버레이({@code paused}/{@code pausedUntil})로
 * 표현하므로 {@code notificationMode}에는 {@code PAUSED}가 나오지 않는다 — 정지 중이면 정지가 풀린 뒤 돌아갈 "기본 모드"를 내려준다.
 */
@Getter
@Builder
@AllArgsConstructor
public class NotificationSettingsResponse {

    private String notificationMode;
    private Integer escalateMinutesArrival;
    private Integer escalateMinutesStay;
    private boolean paused;
    private Instant pausedUntil;

    public static NotificationSettingsResponse of(GuardianTarget guardianTarget) {
        return NotificationSettingsResponse.builder()
                .notificationMode(guardianTarget.baseNotificationMode())
                .escalateMinutesArrival(guardianTarget.getEscalateMinutesArrival())
                .escalateMinutesStay(guardianTarget.getEscalateMinutesStay())
                .paused(guardianTarget.isPaused())
                .pausedUntil(guardianTarget.isPaused() ? guardianTarget.getPausedUntil() : null)
                .build();
    }
}
