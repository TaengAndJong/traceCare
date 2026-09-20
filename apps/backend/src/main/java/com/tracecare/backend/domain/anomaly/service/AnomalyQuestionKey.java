package com.tracecare.backend.domain.anomaly.service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.tracecare.backend.domain.anomaly.entity.AnomalyEvent;

/**
 * {@code /explain} 고정 질문 템플릿 카탈로그(DATABASE_DESIGN_GUIDE.md §15.7, API_Specification.md §3.9) — 유형별
 * ARRIVAL_DELAY 7개/UNREGISTERED_STAY 8개. 각 키가 속한 이상행동 유형과 표시 문구를 enum 자체가 갖고 있어, 질문 카탈로그 조회와
 * "이 질문이 이 이상행동 유형에 속하는지" 검증(ANOMALY_002) 양쪽이 이 enum 하나를 그대로 재사용한다. DB 테이블이 아니라 코드 상수로
 * 관리하는 이유는 §15.7 참고(키 ↔ 답변 조립 코드가 1:1이라 어차피 코드 배포가 필요).
 *
 * <p>문구는 답변 문장과 톤을 맞춰 합니다체 의문형("~습니까?")이다. 감지 기준 시간처럼 설정값이 들어가는 문구는 문구 안에 {@value
 * #MINUTES_PLACEHOLDER} 자리표시자를 두고, 카탈로그 조회 시점에 {@link #render(String)}로 채운다.
 *
 * <p>선언 순서가 곧 카탈로그 응답의 항목 순서다.
 */
public enum AnomalyQuestionKey {
    DELAY_REASON(AnomalyEvent.TYPE_ARRIVAL_DELAY, "왜 감지됐습니까?"),
    DELAY_EXPECTED_TIME(AnomalyEvent.TYPE_ARRIVAL_DELAY, "원래 도착 예정 시각은 언제였습니까?"),
    DELAY_DURATION(AnomalyEvent.TYPE_ARRIVAL_DELAY, "현재까지 얼마나 지연됐습니까?"),
    DELAY_CURRENT_LOCATION(AnomalyEvent.TYPE_ARRIVAL_DELAY, "지금 어디에 있습니까?"),
    DELAY_LAST_VISIT(AnomalyEvent.TYPE_ARRIVAL_DELAY, "이 장소를 마지막으로 방문한 때는 언제입니까?"),
    DELAY_COUNT_7D(AnomalyEvent.TYPE_ARRIVAL_DELAY, "최근 7일간 몇 번 지연됐습니까?"),
    DELAY_COUNT_30D(AnomalyEvent.TYPE_ARRIVAL_DELAY, "최근 30일간 몇 번 지연됐습니까?"),

    STAY_REASON(AnomalyEvent.TYPE_UNREGISTERED_STAY, "왜 감지됐습니까?"),
    STAY_LOCATION(AnomalyEvent.TYPE_UNREGISTERED_STAY, "정확한 위치는 어디입니까?"),
    STAY_STARTED_AT(AnomalyEvent.TYPE_UNREGISTERED_STAY, "언제부터 머물렀습니까?"),
    STAY_ELAPSED(AnomalyEvent.TYPE_UNREGISTERED_STAY, "얼마나 머물렀습니까?"),
    STAY_ONGOING(AnomalyEvent.TYPE_UNREGISTERED_STAY, "지금도 머물고 있습니까?"),
    STAY_NEAREST_PLACE_DISTANCE(
            AnomalyEvent.TYPE_UNREGISTERED_STAY, "가장 가까운 등록 장소까지 얼마나 떨어져 있습니까?"),
    STAY_COUNT_7D(AnomalyEvent.TYPE_UNREGISTERED_STAY, "최근 7일간 몇 번 발생했습니까?"),
    STAY_SIMILAR_PAST(
            AnomalyEvent.TYPE_UNREGISTERED_STAY, "과거 비슷한 위치에서 {minutes} 이상 머문 이력이 있습니까?");

    /** 문구 안에서 "감지 기준 시간"이 들어갈 자리. */
    public static final String MINUTES_PLACEHOLDER = "{minutes}";

    private final String type;
    private final String textTemplate;

    AnomalyQuestionKey(String type, String textTemplate) {
        this.type = type;
        this.textTemplate = textTemplate;
    }

    public String getType() {
        return type;
    }

    /**
     * 사용자에게 보여줄 질문 문구.
     *
     * @param detectMinutesText 감지 기준 시간을 이미 문장용으로 포맷한 값(예: "10분"). 자리표시자가 없는 문구에는 영향이 없다.
     */
    public String render(String detectMinutesText) {
        return textTemplate.replace(MINUTES_PLACEHOLDER, detectMinutesText);
    }

    /** 이 질문이 주어진 이상행동 유형({@code AnomalyEvent.TYPE_*})에 속하는지. */
    public boolean belongsTo(String anomalyType) {
        return type.equals(anomalyType);
    }

    /** 카탈로그(선언 순서) — 해당 유형에 속한 질문만. */
    public static List<AnomalyQuestionKey> forType(String anomalyType) {
        return Arrays.stream(values()).filter(key -> key.belongsTo(anomalyType)).toList();
    }

    /** 카탈로그에 없는 문자열이면 {@link Optional#empty()}(예외를 던지지 않는다 — 호출부가 ANOMALY_002로 변환). */
    public static Optional<AnomalyQuestionKey> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(candidate -> candidate.name().equals(key)).findFirst();
    }
}
