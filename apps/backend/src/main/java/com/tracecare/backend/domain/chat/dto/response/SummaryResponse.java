package com.tracecare.backend.domain.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * API_Specification.md §3.6. {@code visitCount}는 요약의 근거가 된 방문 건수 — 전체 {@code VisitHistory} 데이터를 그대로
 * 응답에 싣지 않고(중복 API 없음, `/history/date`가 이미 담당) 근거의 규모만 가볍게 알려준다.
 */
@Getter
@Builder
@AllArgsConstructor
public class SummaryResponse {

    private String answer;
    private int visitCount;
}
