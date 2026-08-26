package com.tracecare.backend.domain.prediction.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.tracecare.backend.common.exception.business.PredictionNotFoundException;
import com.tracecare.backend.domain.visit.repository.VisitHistoryRepository;

/**
 * 실제 FastAPI 연동 전까지 쓰는 Stub({@link AiPredictionClient} Javadoc 참고). 진짜 ML은 아니지만 아무 값이나 리턴하지 않는다 —
 * VisitHistory 건수가 {@link #MIN_VISIT_COUNT} 미만이면 실제 ML 서버가 응답했을 법한 "학습 데이터 부족" 실패를 그대로 재현하고(FCM
 * Stub이 토큰 미등록을 재현했던 것과 동일한 방식), 충분하면 "등록 장소 중 방문 빈도가 높을수록 오늘도 갈 확률이 높다"는 단순 규칙으로 상위 {@link
 * #TOP_N}개를 계산한다. 확률은 상위 N개끼리의 비중이 아니라 전체 방문 횟수 대비 비중으로 정규화한다 — 그래야 화면에 안 보이는 4번째 이하 장소의 존재가 확률 합계
 * 왜곡을 만들지 않는다(상위 3개 확률 합이 1.0보다 작을 수 있는 게 더 정직하다).
 */
@Component
public class StubAiPredictionClient implements AiPredictionClient {

    private static final Logger log = LoggerFactory.getLogger(StubAiPredictionClient.class);

    private static final long MIN_VISIT_COUNT = 5;
    private static final int TOP_N = 3;
    private static final int PROBABILITY_SCALE = 3;

    private final VisitHistoryRepository visitHistoryRepository;

    public StubAiPredictionClient(VisitHistoryRepository visitHistoryRepository) {
        this.visitHistoryRepository = visitHistoryRepository;
    }

    @Override
    public List<PlacePrediction> predict(Long careTargetId) {
        long totalVisits = visitHistoryRepository.countByUserId(careTargetId);
        if (totalVisits < MIN_VISIT_COUNT) {
            log.info(
                    "event=AI_PREDICT_STUB_INSUFFICIENT_DATA, careTargetId={}, visitCount={}",
                    careTargetId,
                    totalVisits);
            throw new PredictionNotFoundException();
        }

        List<VisitHistoryRepository.PlaceFrequency> topPlaces =
                visitHistoryRepository.findTopVisitedPlaces(careTargetId, PageRequest.of(0, TOP_N));
        BigDecimal total = BigDecimal.valueOf(totalVisits);

        List<PlacePrediction> predictions =
                topPlaces.stream()
                        .map(
                                place ->
                                        new PlacePrediction(
                                                place.getPlaceName(),
                                                BigDecimal.valueOf(place.getVisitCount())
                                                        .divide(
                                                                total,
                                                                PROBABILITY_SCALE,
                                                                RoundingMode.HALF_UP)))
                        .toList();

        log.info(
                "event=AI_PREDICT_STUB_COMPUTED, careTargetId={}, totalVisits={}, placeCount={}",
                careTargetId,
                totalVisits,
                predictions.size());
        return predictions;
    }
}
