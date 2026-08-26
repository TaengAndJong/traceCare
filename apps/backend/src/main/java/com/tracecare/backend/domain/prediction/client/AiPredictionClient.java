package com.tracecare.backend.domain.prediction.client;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code /internal/ai/predict}(FastAPI, API Key 인증) 경계(System_Overview.md §4).
 * `docker-compose.yml`에 ai-server가 아직 컨테이너화되지 않아(추후 추가 예정) 실제 HTTP 클라이언트 대신 {@link
 * StubAiPredictionClient}로 시작한다 — FCM/EmergencyDispatcher와 동일한 패턴. 실제 FastAPI 연동 시 이 인터페이스를 구현하는 새
 * {@code @Component}(WebClient 기반)로 교체하면 되고 호출부({@code AiPredictionService})는 변경할 필요가 없다.
 */
public interface AiPredictionClient {

    /**
     * 학습 데이터(VisitHistory)가 부족하면 {@link
     * com.tracecare.backend.common.exception.business.PredictionNotFoundException}(AI_003)을 던진다 —
     * 실제 ML 서버도 이 경우 결과 없음을 응답할 것이므로 Stub이 흉내 내야 하는 진짜 실패 케이스다. 그 외 연동 자체가 실패하면(Stub 구현체가 원인 불명
     * RuntimeException을 던지는 경우 등) 호출부가 {@link
     * com.tracecare.backend.common.exception.external.AiServerException}(AI_001)으로 변환한다.
     */
    List<PlacePrediction> predict(Long careTargetId);

    record PlacePrediction(String placeName, BigDecimal probability) {}
}
