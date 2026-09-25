package com.tracecare.backend.domain.anomaly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.auth.AccessDeniedCustomException;
import com.tracecare.backend.domain.anomaly.dto.response.AnomalyEventResponse;
import com.tracecare.backend.domain.anomaly.dto.response.AnomalyQuestionResponse;
import com.tracecare.backend.domain.anomaly.entity.AnomalyEvent;
import com.tracecare.backend.domain.anomaly.repository.AnomalyEventRepository;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.location.entity.LocationHistory;
import com.tracecare.backend.domain.location.repository.LocationHistoryRepository;
import com.tracecare.backend.domain.location.service.LocationCacheStore;
import com.tracecare.backend.domain.place.entity.Place;
import com.tracecare.backend.domain.place.repository.PlaceRepository;
import com.tracecare.backend.domain.visit.entity.VisitHistory;
import com.tracecare.backend.domain.visit.repository.VisitHistoryRepository;

/**
 * API_Specification.md §3.9 / DATABASE_DESIGN_GUIDE.md §15.7 — 목록/카탈로그/설명 조회 규칙(검증 순서, 기본 기간, 값 없음
 * 안내 문장)을 검증한다. 시각 문자열은 서버 타임존에 따라 달라지므로 문장 전체가 아니라 핵심 내용(장소명·분·횟수·안내 문구)으로 단언한다.
 */
@ExtendWith(MockitoExtension.class)
class AnomalyServiceTest {

    private static final Long GUARDIAN_ID = 1L;
    private static final Long TARGET_ID = 2L;
    private static final Long PLACE_ID = 10L;
    private static final Long EVENT_ID = 500L;
    private static final double RADIUS = 50.0;
    private static final int DETECT_MINUTES = 10;

    private static final Instant SCHEDULED_AT = Instant.parse("2026-09-15T00:00:00Z");
    private static final Instant DETECTED_AT = SCHEDULED_AT.plus(10, ChronoUnit.MINUTES);

    @Mock private UserRepository userRepository;
    @Mock private GuardianTargetRepository guardianTargetRepository;
    @Mock private AnomalyEventRepository anomalyEventRepository;
    @Mock private PlaceRepository placeRepository;
    @Mock private VisitHistoryRepository visitHistoryRepository;
    @Mock private LocationCacheStore locationCacheStore;
    @Mock private LocationHistoryRepository locationHistoryRepository;

    private User target;
    private final UUID targetPublicId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        target = User.createFromOAuth("care@example.com", "GOOGLE", "oauth-id");
        ReflectionTestUtils.setField(target, "id", TARGET_ID);
        ReflectionTestUtils.setField(target, "publicId", targetPublicId);
    }

    private AnomalyService service() {
        return new AnomalyService(
                userRepository,
                guardianTargetRepository,
                anomalyEventRepository,
                placeRepository,
                visitHistoryRepository,
                locationCacheStore,
                locationHistoryRepository,
                RADIUS,
                DETECT_MINUTES);
    }

    // ---------------------------------------------------------------------
    // 공통 픽스처
    // ---------------------------------------------------------------------

    private void givenActiveRelation() {
        when(guardianTargetRepository.findByGuardianIdAndTargetIdAndStatus(
                        GUARDIAN_ID, TARGET_ID, GuardianTarget.STATUS_ACTIVE))
                .thenReturn(
                        Optional.of(
                                GuardianTarget.createActive(
                                        GUARDIAN_ID, TARGET_ID, GuardianTarget.ROLE_SUB)));
    }

    private void givenNoRelation() {
        when(guardianTargetRepository.findByGuardianIdAndTargetIdAndStatus(
                        GUARDIAN_ID, TARGET_ID, GuardianTarget.STATUS_ACTIVE))
                .thenReturn(Optional.empty());
    }

    private void givenTargetFound() {
        when(userRepository.findByPublicId(targetPublicId)).thenReturn(Optional.of(target));
    }

    private Place place(Long id, String name) {
        Place place =
                Place.createActive(
                        GUARDIAN_ID,
                        TARGET_ID,
                        name,
                        "주소",
                        BigDecimal.valueOf(37.5),
                        BigDecimal.valueOf(127.0),
                        100);
        ReflectionTestUtils.setField(place, "id", id);
        return place;
    }

    private AnomalyEvent arrivalDelay(Instant scheduledAt) {
        AnomalyEvent event =
                AnomalyEvent.createArrivalDelay(TARGET_ID, PLACE_ID, DETECTED_AT, scheduledAt);
        ReflectionTestUtils.setField(event, "id", EVENT_ID);
        return event;
    }

    private AnomalyEvent unregisteredStay(Instant stayStartedAt) {
        AnomalyEvent event =
                AnomalyEvent.createUnregisteredStay(
                        TARGET_ID,
                        PLACE_ID,
                        BigDecimal.valueOf(37.5),
                        BigDecimal.valueOf(127.0),
                        DETECTED_AT,
                        stayStartedAt);
        ReflectionTestUtils.setField(event, "id", EVENT_ID);
        return event;
    }

    private void givenEvent(AnomalyEvent event) {
        when(anomalyEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        givenActiveRelation();
    }

    private String explain(AnomalyQuestionKey key) {
        return service().explain(GUARDIAN_ID, EVENT_ID, key.name()).getAnswer();
    }

    private void assertBusinessError(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    // ---------------------------------------------------------------------
    // 목록 조회
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("목록 조회 성공 — Place는 IN 쿼리 한 번으로 조회해 placeName을 채우고 status를 ONGOING/RESOLVED로 매핑한다")
    void getAnomalies_success_mapsPlaceNameAndStatus() {
        // given
        givenTargetFound();
        givenActiveRelation();
        AnomalyEvent ongoing = arrivalDelay(SCHEDULED_AT);
        AnomalyEvent resolved = unregisteredStay(SCHEDULED_AT);
        resolved.resolve(DETECTED_AT.plusSeconds(600));
        Pageable pageable = PageRequest.of(0, 20);
        when(anomalyEventRepository.findByUserIdAndDetectedAtBetweenOrderByDetectedAtDesc(
                        eq(TARGET_ID), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ongoing, resolved), pageable, 2));
        Place company = place(PLACE_ID, "회사");
        when(placeRepository.findAllById(List.of(PLACE_ID))).thenReturn(List.of(company));

        // when
        Page<AnomalyEventResponse> result =
                service().getAnomalies(GUARDIAN_ID, targetPublicId, null, null, null, pageable);

        // then
        assertThat(result.getContent()).hasSize(2);
        AnomalyEventResponse first = result.getContent().get(0);
        assertThat(first.getStatus()).isEqualTo(AnomalyEventResponse.STATUS_ONGOING);
        assertThat(first.getPlaceName()).isEqualTo("회사");
        assertThat(first.getPlaceId()).isEqualTo(company.getPublicId().toString());
        assertThat(result.getContent().get(1).getStatus())
                .isEqualTo(AnomalyEventResponse.STATUS_RESOLVED);
        // 같은 장소가 두 이벤트에 걸쳐도 Place 조회는 한 번(N+1 없음)
        verify(placeRepository).findAllById(any());
    }

    @Test
    @DisplayName("목록 조회 결과가 0건이면 예외 없이 빈 페이지를 반환하고 Place는 조회하지 않는다")
    void getAnomalies_noResults_returnsEmptyPage() {
        // given
        givenTargetFound();
        givenActiveRelation();
        when(anomalyEventRepository.findByUserIdAndDetectedAtBetweenOrderByDetectedAtDesc(
                        eq(TARGET_ID), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        // when
        Page<AnomalyEventResponse> result =
                service()
                        .getAnomalies(
                                GUARDIAN_ID, targetPublicId, null, null, null, PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).isEmpty();
        verify(placeRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("Soft Delete된 Place는 조회되지 않으므로 placeId/placeName이 모두 null이다")
    void getAnomalies_softDeletedPlace_returnsNullPlaceFields() {
        // given
        givenTargetFound();
        givenActiveRelation();
        Pageable pageable = PageRequest.of(0, 20);
        when(anomalyEventRepository.findByUserIdAndDetectedAtBetweenOrderByDetectedAtDesc(
                        eq(TARGET_ID), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(arrivalDelay(SCHEDULED_AT)), pageable, 1));
        when(placeRepository.findAllById(List.of(PLACE_ID))).thenReturn(List.of());

        // when
        AnomalyEventResponse response =
                service()
                        .getAnomalies(GUARDIAN_ID, targetPublicId, null, null, null, pageable)
                        .getContent()
                        .get(0);

        // then
        assertThat(response.getPlaceId()).isNull();
        assertThat(response.getPlaceName()).isNull();
    }

    @Test
    @DisplayName("한 페이지의 모든 이벤트가 place_id null이어도 NPE 없이 placeId/placeName이 null이고 Place는 조회하지 않는다")
    void getAnomalies_allEventsWithoutPlaceId_returnsNullPlaceFieldsWithoutNpe() {
        // given — nearest place가 없는 UNREGISTERED_STAY만 있는 페이지(placeIds가 비어 loadPlaces가 빈 맵을 반환)
        givenTargetFound();
        givenActiveRelation();
        Pageable pageable = PageRequest.of(0, 20);
        AnomalyEvent noPlace =
                AnomalyEvent.createUnregisteredStay(
                        TARGET_ID,
                        null,
                        BigDecimal.valueOf(37.5),
                        BigDecimal.valueOf(127.0),
                        DETECTED_AT,
                        SCHEDULED_AT);
        ReflectionTestUtils.setField(noPlace, "id", EVENT_ID);
        when(anomalyEventRepository.findByUserIdAndDetectedAtBetweenOrderByDetectedAtDesc(
                        eq(TARGET_ID), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(noPlace), pageable, 1));

        // when
        AnomalyEventResponse response =
                service()
                        .getAnomalies(GUARDIAN_ID, targetPublicId, null, null, null, pageable)
                        .getContent()
                        .get(0);

        // then
        assertThat(response.getPlaceId()).isNull();
        assertThat(response.getPlaceName()).isNull();
        verify(placeRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("기간을 지정하지 않으면 to=현재, from=to−7일 기본 창으로 조회한다")
    void getAnomalies_noPeriod_usesDefaultSevenDayWindow() {
        // given
        givenTargetFound();
        givenActiveRelation();
        when(anomalyEventRepository.findByUserIdAndDetectedAtBetweenOrderByDetectedAtDesc(
                        eq(TARGET_ID), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        // when
        service()
                .getAnomalies(GUARDIAN_ID, targetPublicId, null, null, null, PageRequest.of(0, 20));

        // then
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(anomalyEventRepository)
                .findByUserIdAndDetectedAtBetweenOrderByDetectedAtDesc(
                        eq(TARGET_ID), from.capture(), to.capture(), any(Pageable.class));
        assertThat(Duration.between(from.getValue(), to.getValue())).isEqualTo(Duration.ofDays(7));
        assertThat(Duration.between(to.getValue(), Instant.now()).abs()).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("type을 지정하면 유형 필터 쿼리로 조회하고 정렬은 서버 고정(detected_at DESC)이라 클라이언트 sort를 무시한다")
    void getAnomalies_typeFilter_usesTypeQuery() {
        // given
        givenTargetFound();
        givenActiveRelation();
        when(anomalyEventRepository.findByUserIdAndTypeAndDetectedAtBetweenOrderByDetectedAtDesc(
                        eq(TARGET_ID), eq(AnomalyEvent.TYPE_ARRIVAL_DELAY), any(), any(), any()))
                .thenReturn(Page.empty());
        Pageable clientSorted =
                PageRequest.of(0, 20, org.springframework.data.domain.Sort.by("detectedAt").ascending());

        // when
        service()
                .getAnomalies(
                        GUARDIAN_ID,
                        targetPublicId,
                        AnomalyEvent.TYPE_ARRIVAL_DELAY,
                        null,
                        null,
                        clientSorted);

        // then
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(anomalyEventRepository)
                .findByUserIdAndTypeAndDetectedAtBetweenOrderByDetectedAtDesc(
                        eq(TARGET_ID),
                        eq(AnomalyEvent.TYPE_ARRIVAL_DELAY),
                        any(),
                        any(),
                        captor.capture());
        assertThat(captor.getValue().getSort().isSorted()).isFalse();
        verify(anomalyEventRepository, never())
                .findByUserIdAndDetectedAtBetweenOrderByDetectedAtDesc(any(), any(), any(), any());
    }

    @Test
    @DisplayName("존재하지 않는 CareTarget이면 TARGET_001이다")
    void getAnomalies_targetNotFound_throwsTarget001() {
        // given
        when(userRepository.findByPublicId(targetPublicId)).thenReturn(Optional.empty());

        // when & then
        assertBusinessError(
                () ->
                        service()
                                .getAnomalies(
                                        GUARDIAN_ID, targetPublicId, null, null, null, PageRequest.of(0, 20)),
                ErrorCode.TARGET_001);
    }

    @Test
    @DisplayName("Guardian과 ACTIVE 관계가 아닌 CareTarget이면 TARGET_002(403)이고 이벤트는 조회하지 않는다")
    void getAnomalies_noPermission_throwsTarget002() {
        // given
        givenTargetFound();
        givenNoRelation();

        // when & then
        assertThatThrownBy(
                        () ->
                                service()
                                        .getAnomalies(
                                                GUARDIAN_ID,
                                                targetPublicId,
                                                null,
                                                null,
                                                null,
                                                PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedCustomException.class);
        verify(anomalyEventRepository, never())
                .findByUserIdAndDetectedAtBetweenOrderByDetectedAtDesc(any(), any(), any(), any());
    }

    @Test
    @DisplayName("조회 기간이 90일을 초과하면 COMMON_002이다")
    void getAnomalies_periodExceeds90Days_throwsCommon002() {
        // given
        givenTargetFound();
        givenActiveRelation();
        Instant to = Instant.now();

        // when & then
        assertBusinessError(
                () ->
                        service()
                                .getAnomalies(
                                        GUARDIAN_ID,
                                        targetPublicId,
                                        null,
                                        to.minus(91, ChronoUnit.DAYS),
                                        to,
                                        PageRequest.of(0, 20)),
                ErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("조회 기간이 정확히 90일이면 허용된다(경계값)")
    void getAnomalies_periodExactly90Days_isAllowed() {
        // given
        givenTargetFound();
        givenActiveRelation();
        Instant to = Instant.now();
        when(anomalyEventRepository.findByUserIdAndDetectedAtBetweenOrderByDetectedAtDesc(
                        eq(TARGET_ID), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        // when
        Page<AnomalyEventResponse> result =
                service()
                        .getAnomalies(
                                GUARDIAN_ID,
                                targetPublicId,
                                null,
                                to.minus(90, ChronoUnit.DAYS),
                                to,
                                PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("from이 to보다 늦으면 COMMON_002이다")
    void getAnomalies_fromAfterTo_throwsCommon002() {
        // given
        givenTargetFound();
        givenActiveRelation();
        Instant to = Instant.now();

        // when & then
        assertBusinessError(
                () ->
                        service()
                                .getAnomalies(
                                        GUARDIAN_ID,
                                        targetPublicId,
                                        null,
                                        to.plusSeconds(60),
                                        to,
                                        PageRequest.of(0, 20)),
                ErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("허용되지 않은 type 값이면 COMMON_002이다")
    void getAnomalies_invalidType_throwsCommon002() {
        // given
        givenTargetFound();
        givenActiveRelation();

        // when & then
        assertBusinessError(
                () ->
                        service()
                                .getAnomalies(
                                        GUARDIAN_ID,
                                        targetPublicId,
                                        "UNKNOWN",
                                        null,
                                        null,
                                        PageRequest.of(0, 20)),
                ErrorCode.COMMON_002);
    }

    // ---------------------------------------------------------------------
    // 질문 카탈로그
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("ARRIVAL_DELAY 카탈로그는 7개 질문을 스펙 순서대로 반환한다")
    void getQuestions_arrivalDelay_returnsSevenQuestionsInOrder() {
        // when
        List<AnomalyQuestionResponse> result = service().getQuestions(AnomalyEvent.TYPE_ARRIVAL_DELAY);

        // then
        assertThat(result)
                .extracting(AnomalyQuestionResponse::getQuestionKey)
                .containsExactly(
                        "DELAY_REASON",
                        "DELAY_EXPECTED_TIME",
                        "DELAY_DURATION",
                        "DELAY_CURRENT_LOCATION",
                        "DELAY_LAST_VISIT",
                        "DELAY_COUNT_7D",
                        "DELAY_COUNT_30D");
        assertThat(result.get(0).getText()).isEqualTo("왜 감지됐습니까?");
    }

    @Test
    @DisplayName("UNREGISTERED_STAY 카탈로그는 8개 질문을 반환한다")
    void getQuestions_unregisteredStay_returnsEightQuestions() {
        // when
        List<AnomalyQuestionResponse> result =
                service().getQuestions(AnomalyEvent.TYPE_UNREGISTERED_STAY);

        // then
        assertThat(result).hasSize(8);
        assertThat(result).allSatisfy(q -> assertThat(q.getQuestionKey()).startsWith("STAY_"));
    }

    @Test
    @DisplayName("카탈로그 질문 문구는 15개 전부 합니다체 의문형(~습니까?/~입니까?)이다")
    void getQuestions_allTextsUseFormalQuestionEnding() {
        // when
        List<AnomalyQuestionResponse> all = new java.util.ArrayList<>();
        all.addAll(service().getQuestions(AnomalyEvent.TYPE_ARRIVAL_DELAY));
        all.addAll(service().getQuestions(AnomalyEvent.TYPE_UNREGISTERED_STAY));

        // then
        assertThat(all).hasSize(15);
        assertThat(all).allSatisfy(q -> assertThat(q.getText()).endsWith("니까?").doesNotContain("나요"));
    }

    @Test
    @DisplayName("STAY_SIMILAR_PAST 질문 문구의 '분'은 고정 문자열이 아니라 감지 기준 설정값을 조회 시점에 반영한다")
    void getQuestions_similarPastText_usesConfiguredDetectMinutes() {
        // given
        AnomalyService fifteenMinutes =
                new AnomalyService(
                        userRepository,
                        guardianTargetRepository,
                        anomalyEventRepository,
                        placeRepository,
                        visitHistoryRepository,
                        locationCacheStore,
                        locationHistoryRepository,
                        RADIUS,
                        15);

        // when & then — 기본 서비스(10분)와 15분 서비스의 문구가 각각 설정값을 따른다
        assertThat(similarPastText(service()))
                .isEqualTo("과거 비슷한 위치에서 10분 이상 머문 이력이 있습니까?");
        assertThat(similarPastText(fifteenMinutes))
                .isEqualTo("과거 비슷한 위치에서 15분 이상 머문 이력이 있습니까?");
        assertThat(similarPastText(fifteenMinutes)).doesNotContain(AnomalyQuestionKey.MINUTES_PLACEHOLDER);
    }

    private static String similarPastText(AnomalyService service) {
        return service.getQuestions(AnomalyEvent.TYPE_UNREGISTERED_STAY).stream()
                .filter(q -> q.getQuestionKey().equals("STAY_SIMILAR_PAST"))
                .findFirst()
                .orElseThrow()
                .getText();
    }

    @Test
    @DisplayName("카탈로그 type이 허용되지 않은 값이거나 누락되면 COMMON_002이다")
    void getQuestions_invalidOrMissingType_throwsCommon002() {
        assertBusinessError(() -> service().getQuestions("UNKNOWN"), ErrorCode.COMMON_002);
        assertBusinessError(() -> service().getQuestions(null), ErrorCode.COMMON_002);
    }

    // ---------------------------------------------------------------------
    // /explain — 검증 순서
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("question 파라미터가 비어 있으면 COMMON_002이고 이벤트는 조회하지 않는다")
    void explain_blankQuestion_throwsCommon002() {
        assertBusinessError(
                () -> service().explain(GUARDIAN_ID, EVENT_ID, " "), ErrorCode.COMMON_002);
        assertBusinessError(
                () -> service().explain(GUARDIAN_ID, EVENT_ID, null), ErrorCode.COMMON_002);
        verify(anomalyEventRepository, never()).findById(any());
    }

    @Test
    @DisplayName("존재하지 않는 이상행동 이벤트면 ANOMALY_001이다")
    void explain_eventNotFound_throwsAnomaly001() {
        // given
        when(anomalyEventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

        // when & then
        assertBusinessError(
                () -> service().explain(GUARDIAN_ID, EVENT_ID, "DELAY_REASON"), ErrorCode.ANOMALY_001);
    }

    @Test
    @DisplayName("이벤트 대상 CareTarget과 ACTIVE 관계가 아니면 질문 키가 무엇이든 TARGET_002(403)이다")
    void explain_noPermission_throwsTarget002BeforeQuestionValidation() {
        // given
        when(anomalyEventRepository.findById(EVENT_ID))
                .thenReturn(Optional.of(arrivalDelay(SCHEDULED_AT)));
        givenNoRelation();

        // when & then — 유효하지 않은 질문 키여도 소유권 검증이 먼저다
        assertThatThrownBy(() -> service().explain(GUARDIAN_ID, EVENT_ID, "NOT_A_KEY"))
                .isInstanceOf(AccessDeniedCustomException.class);
    }

    @Test
    @DisplayName("카탈로그에 없는 질문 키면 ANOMALY_002이다")
    void explain_unknownQuestionKey_throwsAnomaly002() {
        // given
        givenEvent(arrivalDelay(SCHEDULED_AT));

        // when & then
        assertBusinessError(
                () -> service().explain(GUARDIAN_ID, EVENT_ID, "NOT_A_KEY"), ErrorCode.ANOMALY_002);
    }

    @Test
    @DisplayName("다른 유형의 질문 키(ARRIVAL_DELAY 이벤트에 STAY_*)면 ANOMALY_002이다")
    void explain_questionOfOtherType_throwsAnomaly002() {
        // given
        givenEvent(arrivalDelay(SCHEDULED_AT));

        // when & then
        assertBusinessError(
                () -> service().explain(GUARDIAN_ID, EVENT_ID, "STAY_REASON"), ErrorCode.ANOMALY_002);
    }

    // ---------------------------------------------------------------------
    // /explain — ARRIVAL_DELAY 질문
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("DELAY_REASON — 장소명과 예정 시각 대비 경과 분을 문장으로 조립한다")
    void explain_delayReason_assemblesPlaceNameAndMinutes() {
        // given
        givenEvent(arrivalDelay(SCHEDULED_AT));
        when(placeRepository.findAllById(List.of(PLACE_ID)))
                .thenReturn(List.of(place(PLACE_ID, "회사")));

        // when
        String answer = explain(AnomalyQuestionKey.DELAY_REASON);

        // then
        assertThat(answer).contains("'회사'").contains("10분").contains("감지됐습니다");
    }

    @Test
    @DisplayName("DELAY_REASON — 장소가 조회되지 않으면(삭제 등) 조용히 축약하지 않고 명시적 안내 문장을 반환한다")
    void explain_delayReasonPlaceMissing_returnsExplicitGuidance() {
        // given
        givenEvent(arrivalDelay(SCHEDULED_AT));
        when(placeRepository.findAllById(List.of(PLACE_ID))).thenReturn(List.of());

        // when
        String answer = explain(AnomalyQuestionKey.DELAY_REASON);

        // then
        assertThat(answer)
                .contains("이 이상행동에 연결된 장소 정보가 없어")
                .contains("확인할 수 없습니다")
                .doesNotContain("예정된 장소");
    }

    @Test
    @DisplayName("안내 문장은 개발자 용어 '이벤트' 대신 '이상행동'을 쓴다")
    void explain_guidanceSentences_useAnomalyWordingNotEvent() {
        // given — 스냅샷이 없는 과거 이상행동
        givenEvent(arrivalDelay(null));

        // when
        String answer = explain(AnomalyQuestionKey.DELAY_REASON);

        // then
        assertThat(answer).contains("이상행동").doesNotContain("이벤트");
    }

    @Test
    @DisplayName("DELAY_REASON — scheduled_at이 없는 과거 이벤트는 에러가 아니라 '확인할 수 없습니다' 안내를 반환한다")
    void explain_delayReasonWithoutScheduledAt_returnsGuidance() {
        // given
        givenEvent(arrivalDelay(null));

        // when
        String answer = explain(AnomalyQuestionKey.DELAY_REASON);

        // then
        assertThat(answer).contains("확인할 수 없습니다");
    }

    @Test
    @DisplayName("DELAY_EXPECTED_TIME — scheduled_at이 있으면 예정 시각을, 없으면 안내 문장을 반환한다")
    void explain_delayExpectedTime_returnsTimeOrGuidance() {
        // given
        AnomalyEvent withSnapshot = arrivalDelay(SCHEDULED_AT);
        when(anomalyEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(withSnapshot));
        givenActiveRelation();

        // when & then
        String answer = explain(AnomalyQuestionKey.DELAY_EXPECTED_TIME);
        assertThat(answer).contains("도착 예정 시각은");
        // 시각은 연도를 포함한 "yyyy년 M월 d일 HH:mm" 형식이다
        assertThat(answer).containsPattern("\\d{4}년 \\d{1,2}월 \\d{1,2}일 \\d{2}:\\d{2}");

        // given — 스냅샷이 없는 이벤트
        when(anomalyEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(arrivalDelay(null)));

        // when & then
        assertThat(explain(AnomalyQuestionKey.DELAY_EXPECTED_TIME)).contains("확인할 수 없습니다");
    }

    @Test
    @DisplayName("DELAY_DURATION — 해제된 이벤트는 예정 시각과 해제 시각의 차이(분)를 반환한다")
    void explain_delayDurationResolved_returnsElapsedUntilResolved() {
        // given — 예정 00:00, 도착 확인(해제) 00:25
        AnomalyEvent event = arrivalDelay(SCHEDULED_AT);
        event.resolve(SCHEDULED_AT.plus(25, ChronoUnit.MINUTES));
        givenEvent(event);

        // when
        String answer = explain(AnomalyQuestionKey.DELAY_DURATION);

        // then
        assertThat(answer).contains("25분").contains("늦게 도착이 확인됐습니다");
    }

    @Test
    @DisplayName("DELAY_CURRENT_LOCATION — Redis 최신 위치가 있으면 DB는 조회하지 않는다")
    void explain_currentLocation_prefersRedisOverDatabase() {
        // given
        givenEvent(arrivalDelay(SCHEDULED_AT));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(locationCacheStore.read(targetPublicId))
                .thenReturn(
                        new LocationCacheStore.CachedLocation(37.123456, 127.654321, DETECTED_AT));

        // when
        String answer = explain(AnomalyQuestionKey.DELAY_CURRENT_LOCATION);

        // then
        assertThat(answer).contains("37.123456").contains("127.654321");
        verify(locationHistoryRepository, never()).findFirstByUserIdOrderByRecordedAtDesc(any());
    }

    @Test
    @DisplayName("DELAY_CURRENT_LOCATION — Redis에 없으면 LocationHistory 최신 행으로 폴백한다")
    void explain_currentLocationCacheMiss_fallsBackToLocationHistory() {
        // given
        givenEvent(arrivalDelay(SCHEDULED_AT));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(locationCacheStore.read(targetPublicId)).thenReturn(null);
        LocationHistory latest = mock(LocationHistory.class);
        when(latest.getLatitude()).thenReturn(BigDecimal.valueOf(36.5));
        when(latest.getLongitude()).thenReturn(BigDecimal.valueOf(128.25));
        when(latest.getRecordedAt()).thenReturn(DETECTED_AT);
        when(locationHistoryRepository.findFirstByUserIdOrderByRecordedAtDesc(TARGET_ID))
                .thenReturn(Optional.of(latest));

        // when
        String answer = explain(AnomalyQuestionKey.DELAY_CURRENT_LOCATION);

        // then
        assertThat(answer).contains("36.500000").contains("128.250000");
    }

    @Test
    @DisplayName("DELAY_CURRENT_LOCATION — Redis/DB 모두 위치가 없으면 에러가 아니라 안내 문장을 반환한다")
    void explain_currentLocationNowhere_returnsGuidance() {
        // given
        givenEvent(arrivalDelay(SCHEDULED_AT));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(locationCacheStore.read(targetPublicId)).thenReturn(null);
        when(locationHistoryRepository.findFirstByUserIdOrderByRecordedAtDesc(TARGET_ID))
                .thenReturn(Optional.empty());

        // when
        String answer = explain(AnomalyQuestionKey.DELAY_CURRENT_LOCATION);

        // then
        assertThat(answer).contains("확인할 수 있는 위치 정보가 없습니다");
    }

    @Test
    @DisplayName("DELAY_LAST_VISIT — 장소는 있는데 방문 기록이 없으면 '기록이 아직 없습니다', 있으면 마지막 도착 시각 문장을 반환한다")
    void explain_delayLastVisit_returnsNoRecordOrLastVisit() {
        // given — 장소 존재, 방문 기록 없음
        givenEvent(arrivalDelay(SCHEDULED_AT));
        when(placeRepository.findAllById(List.of(PLACE_ID)))
                .thenReturn(List.of(place(PLACE_ID, "회사")));
        when(visitHistoryRepository.findByUserIdAndPlaceIdOrderByArrivalTimeDesc(
                        eq(TARGET_ID), eq(PLACE_ID), any(Pageable.class)))
                .thenReturn(Page.empty());

        // when & then
        assertThat(explain(AnomalyQuestionKey.DELAY_LAST_VISIT)).contains("방문 기록이 아직 없습니다");

        // given — 기록 있음
        VisitHistory visit = mock(VisitHistory.class);
        when(visit.getArrivalTime()).thenReturn(SCHEDULED_AT.minus(1, ChronoUnit.DAYS));
        when(visitHistoryRepository.findByUserIdAndPlaceIdOrderByArrivalTimeDesc(
                        eq(TARGET_ID), eq(PLACE_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(visit)));

        // when & then
        assertThat(explain(AnomalyQuestionKey.DELAY_LAST_VISIT)).contains("마지막으로 방문한 때는");
    }

    @Test
    @DisplayName("DELAY_LAST_VISIT — 장소가 삭제돼 조회되지 않으면 방문 기록을 묻지 않고 DELAY_REASON과 같은 패턴의 안내 문장을 반환한다")
    void explain_delayLastVisitPlaceDeleted_returnsSamePatternGuidance() {
        // given — 이상행동에는 place_id가 남아 있지만 Place는 Soft Delete로 조회되지 않음
        givenEvent(arrivalDelay(SCHEDULED_AT));
        when(placeRepository.findAllById(List.of(PLACE_ID))).thenReturn(List.of());

        // when
        String answer = explain(AnomalyQuestionKey.DELAY_LAST_VISIT);

        // then — "방문 기록이 아직 없습니다"(정상 케이스)와 구분되고, 방문 이력 조회 자체를 하지 않는다
        assertThat(answer)
                .isEqualTo("이 이상행동에 연결된 장소 정보가 없어 방문 기록을 확인할 수 없습니다.")
                .doesNotContain("아직 없습니다");
        verify(visitHistoryRepository, never())
                .findByUserIdAndPlaceIdOrderByArrivalTimeDesc(any(), any(), any());
    }

    @Test
    @DisplayName("DELAY_LAST_VISIT — 이상행동에 place_id 자체가 없으면 같은 안내 문장을 반환한다")
    void explain_delayLastVisitNoPlaceId_returnsSamePatternGuidance() {
        // given
        AnomalyEvent noPlace = arrivalDelay(SCHEDULED_AT);
        ReflectionTestUtils.setField(noPlace, "placeId", null);
        givenEvent(noPlace);

        // when & then
        assertThat(explain(AnomalyQuestionKey.DELAY_LAST_VISIT))
                .isEqualTo("이 이상행동에 연결된 장소 정보가 없어 방문 기록을 확인할 수 없습니다.");
    }

    @Test
    @DisplayName("DELAY_COUNT_7D/30D — 같은 장소의 최근 N일 지연 횟수를 집계하고, 이번 이상행동이 창 안이면 '이번을 포함해'를 붙인다")
    void explain_delayCounts_useSamePlaceCounts() {
        // given — 집계 창은 "지금 기준"이므로 이상행동도 최근 시각으로 만든다
        givenEvent(recentArrivalDelay(Instant.now().minus(1, ChronoUnit.HOURS)));
        when(anomalyEventRepository.countByUserIdAndTypeAndPlaceIdAndDetectedAtGreaterThanEqual(
                        eq(TARGET_ID), eq(AnomalyEvent.TYPE_ARRIVAL_DELAY), eq(PLACE_ID), any()))
                .thenReturn(3L, 8L);

        // when & then
        assertThat(explain(AnomalyQuestionKey.DELAY_COUNT_7D))
                .isEqualTo("이번을 포함해 최근 7일간 이 장소에서 3번 지연됐습니다.");
        assertThat(explain(AnomalyQuestionKey.DELAY_COUNT_30D))
                .isEqualTo("이번을 포함해 최근 30일간 이 장소에서 8번 지연됐습니다.");
    }

    @Test
    @DisplayName("DELAY_COUNT — 이번 이상행동이 집계 창보다 오래됐으면 '이번을 포함해'를 붙이지 않는다(사실과 다르므로)")
    void explain_delayCountsOlderThanWindow_omitsIncludesSelfPrefix() {
        // given — 40일 전 이상행동: 7일/30일 창 모두 밖
        givenEvent(recentArrivalDelay(Instant.now().minus(40, ChronoUnit.DAYS)));
        when(anomalyEventRepository.countByUserIdAndTypeAndPlaceIdAndDetectedAtGreaterThanEqual(
                        eq(TARGET_ID), eq(AnomalyEvent.TYPE_ARRIVAL_DELAY), eq(PLACE_ID), any()))
                .thenReturn(0L);

        // when & then
        assertThat(explain(AnomalyQuestionKey.DELAY_COUNT_7D))
                .isEqualTo("최근 7일간 이 장소에서 0번 지연됐습니다.");
        assertThat(explain(AnomalyQuestionKey.DELAY_COUNT_30D))
                .isEqualTo("최근 30일간 이 장소에서 0번 지연됐습니다.");
    }

    private AnomalyEvent recentArrivalDelay(Instant detectedAt) {
        AnomalyEvent event =
                AnomalyEvent.createArrivalDelay(
                        TARGET_ID, PLACE_ID, detectedAt, detectedAt.minus(10, ChronoUnit.MINUTES));
        ReflectionTestUtils.setField(event, "id", EVENT_ID);
        return event;
    }

    // ---------------------------------------------------------------------
    // /explain — UNREGISTERED_STAY 질문
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("STAY_REASON — 체류 시작~감지 시각의 분을 문장으로 조립하고, stay_started_at이 없으면 안내 문장이다")
    void explain_stayReason_returnsMinutesOrGuidance() {
        // given
        givenEvent(unregisteredStay(SCHEDULED_AT));

        // when & then
        assertThat(explain(AnomalyQuestionKey.STAY_REASON)).contains("10분 이상 머물러 감지됐습니다");

        // given — 스냅샷 없는 이벤트
        when(anomalyEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(unregisteredStay(null)));

        // when & then
        assertThat(explain(AnomalyQuestionKey.STAY_REASON)).contains("확인할 수 없습니다");
    }

    @Test
    @DisplayName("STAY_LOCATION — 이벤트 좌표를 소수점 6자리로 반환한다")
    void explain_stayLocation_returnsCoordinates() {
        // given
        givenEvent(unregisteredStay(SCHEDULED_AT));

        // when
        String answer = explain(AnomalyQuestionKey.STAY_LOCATION);

        // then
        assertThat(answer).contains("37.500000").contains("127.000000");
    }

    @Test
    @DisplayName("STAY_ONGOING — 진행 중이면 '아직 머물고 있습니다', 해제됐으면 해제 안내를 반환한다")
    void explain_stayOngoing_dependsOnResolvedAt() {
        // given — 진행 중
        AnomalyEvent open = unregisteredStay(SCHEDULED_AT);
        when(anomalyEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(open));
        givenActiveRelation();

        // when & then
        assertThat(explain(AnomalyQuestionKey.STAY_ONGOING)).contains("아직 머물고 있습니다");

        // given — 해제됨
        AnomalyEvent resolved = unregisteredStay(SCHEDULED_AT);
        resolved.resolve(DETECTED_AT.plusSeconds(300));
        when(anomalyEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(resolved));

        // when & then
        assertThat(explain(AnomalyQuestionKey.STAY_ONGOING)).contains("해제됐습니다");
    }

    @Test
    @DisplayName("STAY_NEAREST_PLACE_DISTANCE — 최근접 장소가 조회되지 않으면(삭제 등) 안내 문장을 반환한다")
    void explain_nearestPlaceDeleted_returnsGuidance() {
        // given
        givenEvent(unregisteredStay(SCHEDULED_AT));
        when(placeRepository.findAllById(List.of(PLACE_ID))).thenReturn(List.of());

        // when
        String answer = explain(AnomalyQuestionKey.STAY_NEAREST_PLACE_DISTANCE);

        // then
        assertThat(answer).contains("확인할 수 없습니다");
    }

    @Test
    @DisplayName("STAY_NEAREST_PLACE_DISTANCE — 등록 장소명과 이벤트 좌표까지의 거리(m)를 반환한다")
    void explain_nearestPlaceDistance_returnsPlaceNameAndMeters() {
        // given — 이벤트 좌표(37.5, 127.0)에서 위도 0.001도(약 111m) 떨어진 장소
        givenEvent(unregisteredStay(SCHEDULED_AT));
        Place nearby = place(PLACE_ID, "집");
        ReflectionTestUtils.setField(nearby, "latitude", BigDecimal.valueOf(37.501));
        when(placeRepository.findAllById(List.of(PLACE_ID))).thenReturn(List.of(nearby));

        // when
        String answer = explain(AnomalyQuestionKey.STAY_NEAREST_PLACE_DISTANCE);

        // then
        assertThat(answer).contains("'집'").contains("m 떨어져 있습니다");
    }

    @Test
    @DisplayName("STAY_COUNT_7D — 최근 7일 UNREGISTERED_STAY 발생 횟수를 반환한다")
    void explain_stayCount7d_returnsCount() {
        // given
        AnomalyEvent recent = unregisteredStay(SCHEDULED_AT);
        ReflectionTestUtils.setField(recent, "detectedAt", Instant.now().minus(1, ChronoUnit.HOURS));
        givenEvent(recent);
        when(anomalyEventRepository.countByUserIdAndTypeAndDetectedAtGreaterThanEqual(
                        eq(TARGET_ID), eq(AnomalyEvent.TYPE_UNREGISTERED_STAY), any()))
                .thenReturn(4L);

        // when
        String answer = explain(AnomalyQuestionKey.STAY_COUNT_7D);

        // then — 이번 이상행동이 창(최근 7일) 안이므로 "이번을 포함해"가 붙는다
        assertThat(answer).isEqualTo("이번을 포함해 최근 7일간 4번 발생했습니다.");
    }

    @Test
    @DisplayName("STAY_SIMILAR_PAST — 반경 밖 후보는 Haversine 재계산으로 제외하고 반경 안 이력만 센다")
    void explain_similarPastWithinAndOutsideRadius_countsOnlyWithinRadius() {
        // given
        givenEvent(unregisteredStay(SCHEDULED_AT));
        AnomalyEvent near = unregisteredStayAt(37.50010, 127.0); // 약 11m — 반경(50m) 안
        AnomalyEvent far = unregisteredStayAt(37.5008, 127.0); // 약 89m — 반경 밖(Bounding Box 후보엔 포함될 수 있음)
        when(anomalyEventRepository.findSimilarUnregisteredStayCandidates(
                        eq(TARGET_ID), eq(EVENT_ID), any(), any(), any(), any(), any()))
                .thenReturn(List.of(near, far));

        // when
        String answer = explain(AnomalyQuestionKey.STAY_SIMILAR_PAST);

        // then
        assertThat(answer).contains("이력이 1번 있습니다");
    }

    @Test
    @DisplayName("STAY_SIMILAR_PAST — 'N분 이상'은 고정 문자열이 아니라 감지 기준 설정값을 그대로 반영한다")
    void explain_similarPast_usesConfiguredDetectMinutes() {
        // given — 설정값 15분인 서비스 (기본 테스트 서비스는 10분)
        givenEvent(unregisteredStay(SCHEDULED_AT));
        when(anomalyEventRepository.findSimilarUnregisteredStayCandidates(
                        eq(TARGET_ID), eq(EVENT_ID), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        AnomalyService fifteenMinutes =
                new AnomalyService(
                        userRepository,
                        guardianTargetRepository,
                        anomalyEventRepository,
                        placeRepository,
                        visitHistoryRepository,
                        locationCacheStore,
                        locationHistoryRepository,
                        RADIUS,
                        15);

        // when
        String answer =
                fifteenMinutes
                        .explain(GUARDIAN_ID, EVENT_ID, AnomalyQuestionKey.STAY_SIMILAR_PAST.name())
                        .getAnswer();

        // then
        assertThat(answer).contains("15분 이상").doesNotContain("10분");
    }

    @Test
    @DisplayName("STAY_SIMILAR_PAST — 비슷한 위치 이력이 없으면 '없습니다' 문장을 반환한다")
    void explain_similarPastNone_returnsNoHistorySentence() {
        // given
        givenEvent(unregisteredStay(SCHEDULED_AT));
        when(anomalyEventRepository.findSimilarUnregisteredStayCandidates(
                        eq(TARGET_ID), eq(EVENT_ID), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        // when
        String answer = explain(AnomalyQuestionKey.STAY_SIMILAR_PAST);

        // then
        assertThat(answer).contains("이력은 없습니다");
    }

    private AnomalyEvent unregisteredStayAt(double latitude, double longitude) {
        AnomalyEvent event =
                AnomalyEvent.createUnregisteredStay(
                        TARGET_ID,
                        null,
                        BigDecimal.valueOf(latitude),
                        BigDecimal.valueOf(longitude),
                        DETECTED_AT.minus(1, ChronoUnit.DAYS),
                        SCHEDULED_AT.minus(1, ChronoUnit.DAYS));
        ReflectionTestUtils.setField(event, "id", 400L);
        return event;
    }
}
