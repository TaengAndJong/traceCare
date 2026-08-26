package com.tracecare.backend.domain.prediction.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.common.cache.CacheKeyGenerator;
import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.auth.AccessDeniedCustomException;
import com.tracecare.backend.common.exception.business.CareTargetNotFoundException;
import com.tracecare.backend.common.exception.business.PredictionNotFoundException;
import com.tracecare.backend.common.exception.external.AiServerException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.prediction.client.AiPredictionClient;
import com.tracecare.backend.domain.prediction.dto.response.PredictReportResponse;
import com.tracecare.backend.domain.prediction.dto.response.PredictResponse;
import com.tracecare.backend.domain.prediction.dto.response.PredictionHistoryItemResponse;
import com.tracecare.backend.domain.prediction.dto.response.PredictionItemResponse;
import com.tracecare.backend.domain.prediction.entity.PredictionHistory;
import com.tracecare.backend.domain.prediction.repository.PredictionHistoryRepository;

/**
 * API_Specification.md §3.5, System_Overview.md §4. 캐시 흐름: {@code prediction:{careTargetId}:{date}}
 * Redis 조회 → 미스 시 <b>DB(PredictionHistory)부터 먼저 확인</b> → 그것도 없을 때만 {@link AiPredictionClient} 호출 →
 * 저장 → 캐시 적재. "미스 시 곧장 AI 클라이언트 호출"이 아니라 DB를 한 번 더 보는 이유: Redis TTL(24시간)이 만료됐거나 Redis 장애로 캐시가 비어도
 * 당일자 예측이 DB에는 이미 있을 수 있는데, 이 경우를 건너뛰고 곧장 다시 계산해 저장하면 {@code uq_ph_user_date_place} UNIQUE 제약을 위반한다
 * — DB를 Source of Truth로 먼저 확인하는 편이 이 충돌 자체를 원천적으로 피한다(Cache_Strategy_Guide.md "PostgreSQL이 Source
 * of Truth" 원칙과 동일).
 */
@Service
public class AiPredictionService {

    private static final Logger log = LoggerFactory.getLogger(AiPredictionService.class);
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final UserRepository userRepository;
    private final GuardianTargetRepository guardianTargetRepository;
    private final PredictionHistoryRepository predictionHistoryRepository;
    private final AiPredictionClient aiPredictionClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheKeyGenerator cacheKeyGenerator;

    public AiPredictionService(
            UserRepository userRepository,
            GuardianTargetRepository guardianTargetRepository,
            PredictionHistoryRepository predictionHistoryRepository,
            AiPredictionClient aiPredictionClient,
            RedisTemplate<String, Object> redisTemplate,
            CacheKeyGenerator cacheKeyGenerator) {
        this.userRepository = userRepository;
        this.guardianTargetRepository = guardianTargetRepository;
        this.predictionHistoryRepository = predictionHistoryRepository;
        this.aiPredictionClient = aiPredictionClient;
        this.redisTemplate = redisTemplate;
        this.cacheKeyGenerator = cacheKeyGenerator;
    }

    /** GET /api/guardian/ai/predict */
    @Transactional
    public PredictResponse predict(Long guardianId, UUID careTargetPublicId) {
        User target = findTargetByPublicId(careTargetPublicId);
        assertActiveRelation(guardianId, target.getId());
        LocalDate today = LocalDate.now(ZoneId.systemDefault());

        List<PredictionItemResponse> items =
                getOrCompute(target.getId(), careTargetPublicId, today);
        return PredictResponse.builder()
                .careTargetId(careTargetPublicId.toString())
                .predictionDate(today)
                .predictions(items)
                .build();
    }

    /**
     * GET /api/guardian/ai/predict/report — {@code /predict}와 같은 예측 데이터를 재사용하고 사람이 읽는 요약 문장만
     * 얹는다(클래스 선택 이유는 {@link PredictReportResponse} Javadoc 참고).
     */
    @Transactional
    public PredictReportResponse predictReport(Long guardianId, UUID careTargetPublicId) {
        User target = findTargetByPublicId(careTargetPublicId);
        assertActiveRelation(guardianId, target.getId());
        LocalDate today = LocalDate.now(ZoneId.systemDefault());

        List<PredictionItemResponse> items =
                getOrCompute(target.getId(), careTargetPublicId, today);
        return PredictReportResponse.builder()
                .careTargetId(careTargetPublicId.toString())
                .predictionDate(today)
                .predictions(items)
                .summary(buildSummary(target, items))
                .build();
    }

    /** GET /api/guardian/ai/history */
    @Transactional(readOnly = true)
    public Page<PredictionHistoryItemResponse> getHistory(
            Long guardianId, UUID careTargetPublicId, Pageable pageable) {
        User target = findTargetByPublicId(careTargetPublicId);
        assertActiveRelation(guardianId, target.getId());

        Page<PredictionHistoryItemResponse> result =
                predictionHistoryRepository
                        .findByUserIdOrderByPredictionDateDesc(target.getId(), pageable)
                        .map(PredictionHistoryItemResponse::of);
        if (result.isEmpty()) {
            throw new PredictionNotFoundException();
        }
        return result;
    }

    private List<PredictionItemResponse> getOrCompute(
            Long targetId, UUID careTargetPublicId, LocalDate date) {
        String cacheKey =
                cacheKeyGenerator.prediction(careTargetPublicId.toString(), date.toString());

        List<PredictionItemResponse> cached = readCache(cacheKey);
        if (cached != null) {
            log.info(
                    "event=AI_PREDICT_CACHE_HIT, careTargetId={}, date={}",
                    careTargetPublicId,
                    date);
            return cached;
        }

        List<PredictionHistory> existing =
                predictionHistoryRepository.findByUserIdAndPredictionDate(targetId, date);
        List<PredictionItemResponse> items;
        if (!existing.isEmpty()) {
            log.info(
                    "event=AI_PREDICT_DB_FALLBACK_HIT, careTargetId={}, date={}",
                    careTargetPublicId,
                    date);
            items = existing.stream().map(PredictionItemResponse::of).toList();
        } else {
            items = computeAndSave(targetId, date);
        }

        writeCache(cacheKey, items);
        return items;
    }

    private List<PredictionItemResponse> computeAndSave(Long targetId, LocalDate date) {
        List<AiPredictionClient.PlacePrediction> predicted;
        try {
            predicted = aiPredictionClient.predict(targetId);
        } catch (PredictionNotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("event=AI_PREDICT_CLIENT_FAILED, careTargetId={}", targetId, e);
            throw new AiServerException("ai-server", ErrorCode.AI_001);
        }

        List<PredictionHistory> saved =
                predictionHistoryRepository.saveAll(
                        predicted.stream()
                                .map(
                                        p ->
                                                PredictionHistory.create(
                                                        targetId,
                                                        p.placeName(),
                                                        p.probability(),
                                                        date))
                                .toList());
        log.info(
                "event=AI_PREDICT_STUB_SAVED, careTargetId={}, date={}, count={}",
                targetId,
                date,
                saved.size());
        return saved.stream().map(PredictionItemResponse::of).toList();
    }

    private String buildSummary(User target, List<PredictionItemResponse> items) {
        if (items.isEmpty()) {
            return target.getName() + "님의 오늘 방문 예측 데이터가 없습니다";
        }
        PredictionItemResponse top = items.get(0);
        int percent = top.getProbability().multiply(java.math.BigDecimal.valueOf(100)).intValue();
        return String.format(
                "오늘은 %s님이 '%s'에 방문할 확률이 가장 높습니다(%d%%)",
                target.getName(), top.getPlaceName(), percent);
    }

    private User findTargetByPublicId(UUID targetPublicId) {
        return userRepository
                .findByPublicId(targetPublicId)
                .orElseThrow(CareTargetNotFoundException::new);
    }

    private void assertActiveRelation(Long guardianId, Long targetId) {
        guardianTargetRepository
                .findByGuardianIdAndTargetIdAndStatus(
                        guardianId, targetId, GuardianTarget.STATUS_ACTIVE)
                .orElseThrow(() -> new AccessDeniedCustomException(ErrorCode.TARGET_002));
    }

    private List<PredictionItemResponse> readCache(String key) {
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            return cached instanceof PredictionCache predictionCache
                    ? predictionCache.predictions()
                    : null;
        } catch (DataAccessException | SerializationException e) {
            log.warn("event=AI_PREDICT_CACHE_READ_FAILED, key={}", key, e);
            return null;
        }
    }

    private void writeCache(String key, List<PredictionItemResponse> items) {
        try {
            redisTemplate
                    .opsForValue()
                    .set(key, new PredictionCache(new ArrayList<>(items)), CACHE_TTL);
        } catch (DataAccessException | SerializationException e) {
            log.warn("event=AI_PREDICT_CACHE_WRITE_FAILED, key={}", key, e);
        }
    }

    /** {@code prediction:{careTargetId}:{date}} Redis 값(Cache_Strategy_Guide.md §3.2). */
    public record PredictionCache(List<PredictionItemResponse> predictions) {}
}
