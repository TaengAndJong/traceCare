package com.tracecare.backend.domain.guardian.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;

import lombok.Getter;

/**
 * API_Specification.md §3.10 PUT 요청 — 설정 <b>전체 교체</b>(모드 + 승격 분 2종).
 *
 * <p><b>키 누락과 명시적 null을 구분한다.</b> {@code escalateMinutes*}의 {@code null}은 "이 유형은 승격 안 함"이라는 의미(DATABASE_DESIGN_GUIDE.md
 * §15.2)라서, 클라이언트 실수로 키가 빠진 요청이 조용히 승격을 꺼버리면 안전 기능이 의도치 않게 꺼진다. 일반 DTO는 "키 없음"과 "null"이 모두
 * {@code null}이라 구분할 수 없으므로, 세터가 호출됐는지(= JSON에 키가 있었는지)를 플래그로 기록하고 {@link
 * ValidNotificationSettingsKeys}가 누락을 필드 오류({@code errors[].field})로 거부한다. 명시적 {@code null}도 Jackson은 세터를 호출하므로
 * "키 있음"으로 기록된다.
 *
 * <p>프로젝트의 다른 요청 DTO(불변 {@code @Jacksonized @Builder})와 달리 가변 클래스인 이유가 이것이다. 값 범위(1~1440)는 {@code @Min}/{@code
 * @Max}가 검증하며 {@code null}은 통과시킨다(누락 여부는 위 검증기가 따로 본다). {@code PAUSED}는 허용하지 않는다 — 정지는 기간이 필요한 액션이라
 * {@code pause}로만 진입한다.
 */
@Getter
@ValidNotificationSettingsKeys
public class NotificationSettingsUpdateRequest {

    public static final int MAX_ESCALATE_MINUTES = 1440;

    @NotNull
    @Pattern(
            regexp = "REALTIME|REPORT_ONLY|HYBRID",
            message = "REALTIME, REPORT_ONLY, HYBRID 중 하나여야 합니다")
    private String notificationMode;

    @Min(1)
    @Max(MAX_ESCALATE_MINUTES)
    private Integer escalateMinutesArrival;

    @Min(1)
    @Max(MAX_ESCALATE_MINUTES)
    private Integer escalateMinutesStay;

    @JsonIgnore private boolean escalateMinutesArrivalPresent;

    @JsonIgnore private boolean escalateMinutesStayPresent;

    @JsonSetter("notificationMode")
    public void setNotificationMode(String notificationMode) {
        this.notificationMode = notificationMode;
    }

    @JsonSetter("escalateMinutesArrival")
    public void setEscalateMinutesArrival(Integer escalateMinutesArrival) {
        this.escalateMinutesArrival = escalateMinutesArrival;
        this.escalateMinutesArrivalPresent = true;
    }

    @JsonSetter("escalateMinutesStay")
    public void setEscalateMinutesStay(Integer escalateMinutesStay) {
        this.escalateMinutesStay = escalateMinutesStay;
        this.escalateMinutesStayPresent = true;
    }
}
