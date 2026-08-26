package com.tracecare.backend.domain.prediction.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.tracecare.backend.domain.prediction.entity.PredictionHistory;

public interface PredictionHistoryRepository extends JpaRepository<PredictionHistory, Long> {

    /** 캐시 미스 시 DB(Source of Truth)에 오늘자 예측이 이미 있는지 먼저 확인한다(AiPredictionService 참고). */
    List<PredictionHistory> findByUserIdAndPredictionDate(Long userId, LocalDate predictionDate);

    Page<PredictionHistory> findByUserIdOrderByPredictionDateDesc(Long userId, Pageable pageable);
}
