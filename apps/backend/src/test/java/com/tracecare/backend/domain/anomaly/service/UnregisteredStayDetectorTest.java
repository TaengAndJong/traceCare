package com.tracecare.backend.domain.anomaly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.tracecare.backend.common.cache.CacheKeyGenerator;
import com.tracecare.backend.domain.anomaly.entity.AnomalyEvent;
import com.tracecare.backend.domain.anomaly.repository.AnomalyEventRepository;
import com.tracecare.backend.domain.place.repository.PlaceRepository;
import com.tracecare.backend.domain.place.service.PlaceService;

/**
 * DATABASE_DESIGN_GUIDE.md §15.2 상태 전이(후보 시작/유지/리셋/승격, 진행 중 이벤트 해제)를 검증한다. 특히
 * "반경 안에서는 startedAt을 갱신하지 않는다" 규칙(감지 로직의 핵심 불변조건)을 최우선으로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class UnregisteredStayDetectorTest {

    private static final Long CARE_TARGET_ID = 1L;
    private static final int DETECT_MINUTES = 10;
    private static final double RADIUS_METERS = 50.0;
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    @Mock private PlaceService placeService;
    @Mock private PlaceRepository placeRepository;
    @Mock private AnomalyEventRepository anomalyEventRepository;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;

    private final CacheKeyGenerator cacheKeyGenerator = new CacheKeyGenerator();

    private UnregisteredStayDetector detector() {
        return new UnregisteredStayDetector(
                placeService,
                placeRepository,
                anomalyEventRepository,
                redisTemplate,
                cacheKeyGenerator,
                DETECT_MINUTES,
                RADIUS_METERS);
    }

    private String candidateKey() {
        return cacheKeyGenerator.anomalyCandidate(String.valueOf(CARE_TARGET_ID));
    }

    private String activeKey() {
        return cacheKeyGenerator.anomalyActive(
                String.valueOf(CARE_TARGET_ID), AnomalyEvent.TYPE_UNREGISTERED_STAY);
    }

    /** 위도 0도(적도)에서 순수 경도 이동 거리는 Haversine 공식이 R×Δλ(rad)로 정확히 환산되므로 반경 경계 테스트에 쓴다. */
    private static double lngOffsetForMeters(double meters) {
        return Math.toDegrees(meters / EARTH_RADIUS_METERS);
    }

    private void givenNoOpenEventInDb() {
        when(anomalyEventRepository.findFirstByUserIdAndTypeAndResolvedAtIsNullOrderByDetectedAtDesc(
                        CARE_TARGET_ID, AnomalyEvent.TYPE_UNREGISTERED_STAY))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("후보 캐시가 없고 진행 중인 이벤트도 없으면 새 후보를 Redis에 기록한다")
    void evaluate_noCandidateNoOpenEvent_writesNewCandidate() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(activeKey())).thenReturn(null);
        givenNoOpenEventInDb();
        when(valueOperations.get(candidateKey())).thenReturn(null);

        Instant recordedAt = Instant.parse("2026-09-15T00:00:00Z");

        // when
        detector().evaluate(CARE_TARGET_ID, 37.5, 127.0, recordedAt, false);

        // then
        verify(valueOperations)
                .set(
                        eq(candidateKey()),
                        eq(new UnregisteredStayDetector.Candidate(37.5, 127.0, recordedAt)),
                        eq(Duration.ofMinutes(15)));
        verify(anomalyEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("후보가 반경 안에 있고 임계값(10분) 전이면 AnomalyEvent를 생성하지 않고 startedAt도 갱신하지 않는다")
    void evaluate_candidateWithinRadiusBeforeThreshold_doesNotCreateEventOrTouchStartedAt() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(activeKey())).thenReturn(null);
        givenNoOpenEventInDb();

        Instant startedAt = Instant.parse("2026-09-15T00:00:00Z");
        when(valueOperations.get(candidateKey()))
                .thenReturn(new UnregisteredStayDetector.Candidate(37.5, 127.0, startedAt));

        // 같은 반경(같은 좌표) 안에서, 임계값(10분) 직전인 9분 경과 시점에 위치 수신
        Instant recordedAt = startedAt.plus(9, ChronoUnit.MINUTES);

        // when
        detector().evaluate(CARE_TARGET_ID, 37.5, 127.0, recordedAt, false);

        // then — 감지도, 후보 재기록(=startedAt 갱신)도 일어나지 않아야 한다
        verify(anomalyEventRepository, never()).save(any());
        verify(valueOperations, never()).set(eq(candidateKey()), any(), any());
    }

    @Test
    @DisplayName("후보가 반경 안에서 임계값(10분)을 넘기면 AnomalyEvent(UNREGISTERED_STAY)를 생성하고 active 캐시에 id를 기록한다")
    void evaluate_candidateWithinRadiusPastThreshold_createsAnomalyEventAndWritesActiveCache() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(activeKey())).thenReturn(null);
        givenNoOpenEventInDb();

        Instant startedAt = Instant.parse("2026-09-15T00:00:00Z");
        when(valueOperations.get(candidateKey()))
                .thenReturn(new UnregisteredStayDetector.Candidate(37.5, 127.0, startedAt));
        when(placeService.getPlacesForGeofence(CARE_TARGET_ID)).thenReturn(List.of());
        when(anomalyEventRepository.save(any(AnomalyEvent.class)))
                .thenAnswer(
                        invocation -> {
                            AnomalyEvent event = invocation.getArgument(0);
                            ReflectionTestUtils.setField(event, "id", 999L);
                            return event;
                        });

        Instant recordedAt = startedAt.plus(DETECT_MINUTES, ChronoUnit.MINUTES);

        // when
        detector().evaluate(CARE_TARGET_ID, 37.5, 127.0, recordedAt, false);

        // then
        ArgumentCaptor<AnomalyEvent> captor = ArgumentCaptor.forClass(AnomalyEvent.class);
        verify(anomalyEventRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(AnomalyEvent.TYPE_UNREGISTERED_STAY);
        assertThat(captor.getValue().getUserId()).isEqualTo(CARE_TARGET_ID);

        verify(valueOperations).set(eq(activeKey()), eq("999"), eq(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName(
            "이미 진행 중인 이벤트가 있고 여전히 반경 안이면(해제/승격 조건 둘 다 아님) AnomalyEvent를 중복 생성하지 않는다"
                    + " — anomaly:active:* 캐시 히트로 이 분기가 일찍 걸러지는지까지 확인한다")
    void evaluate_openEventStillWithinRadius_doesNotCreateDuplicateEvent() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        AnomalyEvent openEvent =
                AnomalyEvent.createUnregisteredStay(
                        CARE_TARGET_ID,
                        null,
                        BigDecimal.valueOf(37.5),
                        BigDecimal.valueOf(127.0),
                        Instant.parse("2026-09-15T00:00:00Z"));
        ReflectionTestUtils.setField(openEvent, "id", 444L);
        when(valueOperations.get(activeKey())).thenReturn("444");
        when(anomalyEventRepository.findById(444L)).thenReturn(Optional.of(openEvent));

        // 이벤트 anchor(37.5, 127.0)와 같은 반경 안, 임계값과 무관하게 이미 승격 여부는 AnomalyScheduler(§4.2)
        // 소관이므로 여기서는 "새 이벤트가 또 생기지 않는지"만 확인한다.
        Instant recordedAt = Instant.parse("2026-09-15T00:07:00Z");

        // when
        detector().evaluate(CARE_TARGET_ID, 37.5, 127.0, recordedAt, false);

        // then — 새 이벤트/후보 모두 생성되지 않고, 기존 이벤트도 해제되지 않는다
        verify(anomalyEventRepository, never()).save(any());
        assertThat(openEvent.getResolvedAt()).isNull();
        verify(valueOperations, never()).set(any(), any(), any());

        // then — active 캐시 히트로 candidate 캐시 조회 자체가 아예 발생하지 않는다(조기 분기 확인)
        verify(valueOperations, never()).get(candidateKey());
        // then — DB fallback(findFirstBy...)도 호출되지 않는다(캐시만으로 열린 이벤트를 확정)
        verify(anomalyEventRepository, never())
                .findFirstByUserIdAndTypeAndResolvedAtIsNullOrderByDetectedAtDesc(any(), any());
    }

    @Test
    @DisplayName("후보가 있는 상태에서 반경을 벗어나면 새 위치/시각으로 후보가 교체된다")
    void evaluate_candidateOutsideRadius_replacesWithNewCandidate() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(activeKey())).thenReturn(null);
        givenNoOpenEventInDb();

        Instant startedAt = Instant.parse("2026-09-15T00:00:00Z");
        when(valueOperations.get(candidateKey()))
                .thenReturn(new UnregisteredStayDetector.Candidate(37.5, 127.0, startedAt));

        Instant recordedAt = startedAt.plusSeconds(120);
        double farLatitude = 37.6; // 반경(50m)을 훨씬 벗어난 위치(약 11km)

        // when
        detector().evaluate(CARE_TARGET_ID, farLatitude, 127.0, recordedAt, false);

        // then
        verify(valueOperations)
                .set(
                        eq(candidateKey()),
                        eq(new UnregisteredStayDetector.Candidate(farLatitude, 127.0, recordedAt)),
                        eq(Duration.ofMinutes(15)));
        verify(anomalyEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("진행 중인 이벤트가 있는 상태에서 반경을 벗어나면 이벤트를 resolved_at과 함께 해제하고 그 자리에서 새 후보를 시작한다")
    void evaluate_openEventOutsideRadius_resolvesEventAndStartsNewCandidate() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        AnomalyEvent openEvent =
                AnomalyEvent.createUnregisteredStay(
                        CARE_TARGET_ID,
                        null,
                        BigDecimal.valueOf(37.5),
                        BigDecimal.valueOf(127.0),
                        Instant.parse("2026-09-15T00:00:00Z"));
        ReflectionTestUtils.setField(openEvent, "id", 555L);
        when(valueOperations.get(activeKey())).thenReturn("555");
        when(anomalyEventRepository.findById(555L)).thenReturn(Optional.of(openEvent));

        Instant recordedAt = Instant.parse("2026-09-15T00:20:00Z");
        double farLatitude = 37.6;

        // when
        detector().evaluate(CARE_TARGET_ID, farLatitude, 127.0, recordedAt, false);

        // then — 이벤트 해제와 새 후보 시작이 같은 흐름에서 함께 일어나야 한다
        assertThat(openEvent.getResolvedAt()).isEqualTo(recordedAt);
        verify(anomalyEventRepository).save(openEvent);
        verify(redisTemplate).delete(activeKey());
        verify(valueOperations)
                .set(
                        eq(candidateKey()),
                        eq(new UnregisteredStayDetector.Candidate(farLatitude, 127.0, recordedAt)),
                        eq(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("진행 중인 이벤트가 있을 때 등록 장소에 도착(placeMatched=true)하면 이벤트를 해제하고 후보도 정리한다")
    void evaluate_openEventAndPlaceMatched_resolvesEventAndEvictsCandidate() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        AnomalyEvent openEvent =
                AnomalyEvent.createUnregisteredStay(
                        CARE_TARGET_ID,
                        null,
                        BigDecimal.valueOf(37.5),
                        BigDecimal.valueOf(127.0),
                        Instant.parse("2026-09-15T00:00:00Z"));
        ReflectionTestUtils.setField(openEvent, "id", 777L);
        when(valueOperations.get(activeKey())).thenReturn("777");
        when(anomalyEventRepository.findById(777L)).thenReturn(Optional.of(openEvent));

        Instant recordedAt = Instant.parse("2026-09-15T00:05:00Z");

        // when
        detector().evaluate(CARE_TARGET_ID, 37.55, 127.05, recordedAt, true);

        // then
        assertThat(openEvent.getResolvedAt()).isEqualTo(recordedAt);
        verify(anomalyEventRepository).save(openEvent);
        verify(redisTemplate).delete(activeKey());
        verify(redisTemplate).delete(candidateKey());
        verify(valueOperations, never()).set(eq(candidateKey()), any(), any());
    }

    @Test
    @DisplayName("Redis 조회가 실패해도 원본 예외를 그대로 전파하지 않고 PostgreSQL(진행 중인 이벤트 여부)로 폴백한다")
    void evaluate_redisReadFails_doesNotPropagateAndFallsBackToDatabase() {
        // given — opsForValue() 호출 자체가 매번 실패하는 상황(Redis 연결 장애)을 가정한다
        when(redisTemplate.opsForValue())
                .thenThrow(new RedisConnectionFailureException("연결 실패(테스트)"));
        givenNoOpenEventInDb();

        // when & then — try-catch(DataAccessException)로 내부에서 흡수되어 원본 예외가 밖으로 전파되지 않는다.
        // PlaceService의 기존 Cache Aside 실패 처리 패턴과 동일하게, 이 캐시(anomaly:candidate:*)는
        // 감지 전 임시 상태라 도메인 예외로 변환하지 않고 조용히 다음 시도에서 자동 복구되도록 설계했다
        // (Cache_Strategy_Guide.md §3.2 각주 — EMERGENCY_*류의 fail-safe 대상과는 성격이 다름).
        assertThatCode(
                        () ->
                                detector()
                                        .evaluate(
                                                CARE_TARGET_ID,
                                                37.5,
                                                127.0,
                                                Instant.parse("2026-09-15T00:00:00Z"),
                                                false))
                .doesNotThrowAnyException();

        // then — active 캐시 조회 실패 후 PostgreSQL로 정상 폴백했는지 확인
        verify(anomalyEventRepository)
                .findFirstByUserIdAndTypeAndResolvedAtIsNullOrderByDetectedAtDesc(
                        CARE_TARGET_ID, AnomalyEvent.TYPE_UNREGISTERED_STAY);
        verify(anomalyEventRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "반경(50m) 경계 — 정확히 49m 지점은 반경 '안'으로 처리되어 후보가 교체되지 않는다"
                    + "(실좌표로 정확히 50.000m 동일값을 재현할 수 없어 근접 값으로 '>' 연산자 동작을 확인한다)")
    void evaluate_justInsideRadius_keepsCandidate() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(activeKey())).thenReturn(null);
        givenNoOpenEventInDb();

        Instant startedAt = Instant.parse("2026-09-15T00:00:00Z");
        when(valueOperations.get(candidateKey()))
                .thenReturn(new UnregisteredStayDetector.Candidate(0.0, 0.0, startedAt));

        Instant recordedAt = startedAt.plusSeconds(30);
        double lng = lngOffsetForMeters(49);

        // when
        detector().evaluate(CARE_TARGET_ID, 0.0, lng, recordedAt, false);

        // then — 반경 안이므로 후보를 교체하지 않는다
        verify(valueOperations, never()).set(eq(candidateKey()), any(), any());
        verify(anomalyEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("반경(50m) 경계 — 정확히 51m 지점은 반경 '밖'으로 처리되어 새 후보로 교체된다")
    void evaluate_justOutsideRadius_replacesCandidate() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(activeKey())).thenReturn(null);
        givenNoOpenEventInDb();

        Instant startedAt = Instant.parse("2026-09-15T00:00:00Z");
        when(valueOperations.get(candidateKey()))
                .thenReturn(new UnregisteredStayDetector.Candidate(0.0, 0.0, startedAt));

        Instant recordedAt = startedAt.plusSeconds(30);
        double lng = lngOffsetForMeters(51);

        // when
        detector().evaluate(CARE_TARGET_ID, 0.0, lng, recordedAt, false);

        // then — 반경 밖이므로 새 후보로 교체한다
        verify(valueOperations)
                .set(
                        eq(candidateKey()),
                        eq(new UnregisteredStayDetector.Candidate(0.0, lng, recordedAt)),
                        eq(Duration.ofMinutes(15)));
    }
}
