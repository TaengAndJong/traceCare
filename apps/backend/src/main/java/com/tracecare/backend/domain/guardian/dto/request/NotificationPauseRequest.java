package com.tracecare.backend.domain.guardian.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import com.tracecare.backend.domain.guardian.entity.GuardianTarget;

/**
 * API_Specification.md §3.10 pause 요청. {@code durationMinutes}는 <b>요청 1회당</b> 1~1440분이며 누적 결과의 총합 상한(지금부터
 * 24시간)은 엔티티({@link GuardianTarget#pause})가 계산식으로 보장한다. 무기한 정지는 지원하지 않는다.
 */
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class NotificationPauseRequest {

    @NotNull
    @Min(1)
    @Max(GuardianTarget.MAX_PAUSE_MINUTES)
    private Integer durationMinutes;
}
