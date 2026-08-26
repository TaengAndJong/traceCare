package com.tracecare.backend.domain.prediction.dto.response;

import java.time.LocalDate;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * API_Specification.md §3.5 {@code GET /api/guardian/ai/predict/report}. {@code /predict}와 완전히 같은
 * 예측 데이터(같은 캐시/DB 소스, System_Overview.md §4 시퀀스 다이어그램도 두 엔드포인트를 같은 흐름으로 그림)를 재사용하되, 사람이 바로 읽을 수 있는
 * {@code summary} 문장만 얹은 "리포트" 표현이다 — 문서에 두 엔드포인트의 차이가 명시돼 있지 않아 판단한 부분(결과 보고 참고).
 */
@Getter
@Builder
@AllArgsConstructor
public class PredictReportResponse {

    private String careTargetId;
    private LocalDate predictionDate;
    private List<PredictionItemResponse> predictions;
    private String summary;
}
