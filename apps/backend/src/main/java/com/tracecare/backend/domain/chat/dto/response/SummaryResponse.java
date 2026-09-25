package com.tracecare.backend.domain.chat.dto.response;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.anomaly.dto.response.SummaryAnomalyResponse;

/**
 * API_Specification.md §3.6. {@code visitCount}는 요약의 근거가 된 방문 건수 — 전체 {@code VisitHistory} 데이터를 그대로
 * 응답에 싣지 않고(중복 API 없음, `/history/date`가 이미 담당) 근거의 규모만 가볍게 알려준다.
 *
 * <p>{@code anomalyCount}/{@code anomalies}는 LLM과 무관하게 서버가 DB 사실을 그대로 붙인다(항상 존재, 없으면 0/빈 배열).
 * {@code anomalies}는 최대 20건이라 총 건수를 {@code anomalyCount}로 따로 낸다.
 */
@Getter
@Builder
@AllArgsConstructor
public class SummaryResponse {

    private String answer;
    private int visitCount;
    private long anomalyCount;
    private List<SummaryAnomalyResponse> anomalies;
}
