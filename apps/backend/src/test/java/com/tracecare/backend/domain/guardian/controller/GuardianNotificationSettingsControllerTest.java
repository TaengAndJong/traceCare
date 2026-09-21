package com.tracecare.backend.domain.guardian.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.SerializationFeature;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.GlobalExceptionHandler;
import com.tracecare.backend.common.exception.auth.AccessDeniedCustomException;
import com.tracecare.backend.common.exception.business.CareTargetNotFoundException;
import com.tracecare.backend.common.exception.infra.DataAccessCustomException;
import com.tracecare.backend.common.security.CustomUserDetails;
import com.tracecare.backend.domain.guardian.dto.response.NotificationSettingsResponse;
import com.tracecare.backend.domain.guardian.service.GuardianNotificationSettingsService;

/**
 * API_Specification.md §3.10 — URI/메서드 매핑, 인증된 사용자 id 전달(요청의 guardianId 무시), 요청 검증(키 누락/명시적 null/범위/모드), 응답 코드와
 * 에러 코드→HTTP Status 매핑을 검증한다. Service는 Mock이다.
 */
@ExtendWith(MockitoExtension.class)
class GuardianNotificationSettingsControllerTest {

    private static final Long GUARDIAN_ID = 7L;
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final String BASE = "/api/guardian/care-targets/" + TARGET_ID + "/notification-settings";

    @Mock private GuardianNotificationSettingsService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new GuardianNotificationSettingsController(service))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                        // 실제 Boot 앱과 같은 직렬화(Instant를 숫자가 아닌 ISO-8601 문자열로)
                        .setMessageConverters(
                                new MappingJackson2HttpMessageConverter(
                                        Jackson2ObjectMapperBuilder.json()
                                                .featuresToDisable(
                                                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                                .build()))
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new CustomUserDetails(GUARDIAN_ID, "GUARDIAN"), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static NotificationSettingsResponse settings(
            String mode, Integer arrival, Integer stay, boolean paused, Instant until) {
        return NotificationSettingsResponse.builder()
                .notificationMode(mode)
                .escalateMinutesArrival(arrival)
                .escalateMinutesStay(stay)
                .paused(paused)
                .pausedUntil(until)
                .build();
    }

    private ResultActions putJson(String body) throws Exception {
        return mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions postPause(String body) throws Exception {
        return mockMvc.perform(
                post(BASE + "/pause").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---------------------------------------------------------------------
    // GET
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("조회 성공 — 인증된 사용자 id로 호출하고 TARGET_011과 5개 응답 필드를 반환한다")
    void getSettings_success_returnsTarget011() throws Exception {
        // given
        Instant until = Instant.parse("2026-09-21T09:00:00Z");
        when(service.getSettings(GUARDIAN_ID, TARGET_ID))
                .thenReturn(settings("REALTIME", 10, null, true, until));

        // when & then
        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("TARGET_011"))
                .andExpect(jsonPath("$.data.notificationMode").value("REALTIME"))
                .andExpect(jsonPath("$.data.escalateMinutesArrival").value(10))
                .andExpect(jsonPath("$.data.escalateMinutesStay").doesNotExist())
                .andExpect(jsonPath("$.data.paused").value(true))
                .andExpect(jsonPath("$.data.pausedUntil").value("2026-09-21T09:00:00Z"));
    }

    @Test
    @DisplayName("조회 — CareTarget이 없으면 TARGET_001(404)이다")
    void getSettings_targetNotFound_returns404() throws Exception {
        // given
        when(service.getSettings(GUARDIAN_ID, TARGET_ID)).thenThrow(new CareTargetNotFoundException());

        // when & then
        mockMvc.perform(get(BASE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TARGET_001"));
    }

    @Test
    @DisplayName("조회 — ACTIVE 관계가 아니면 TARGET_002(403)이다")
    void getSettings_noActiveRelation_returns403() throws Exception {
        // given
        when(service.getSettings(GUARDIAN_ID, TARGET_ID))
                .thenThrow(new AccessDeniedCustomException(ErrorCode.TARGET_002));

        // when & then
        mockMvc.perform(get(BASE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TARGET_002"));
    }

    @Test
    @DisplayName("조회 — {id}가 UUID 형식이 아니면 COMMON_002(400)이고 서비스는 호출하지 않는다")
    void getSettings_malformedId_returnsCommon002() throws Exception {
        mockMvc.perform(get("/api/guardian/care-targets/not-a-uuid/notification-settings"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
        verifyNoInteractions(service);
    }

    // ---------------------------------------------------------------------
    // PUT
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("수정 성공 — 본문의 세 값을 서비스에 그대로 전달하고 TARGET_012를 반환한다")
    void updateSettings_success_returnsTarget012() throws Exception {
        // given
        when(service.updateSettings(GUARDIAN_ID, TARGET_ID, "REALTIME", 15, 45))
                .thenReturn(settings("REALTIME", 15, 45, false, null));

        // when & then
        putJson("{\"notificationMode\":\"REALTIME\",\"escalateMinutesArrival\":15,\"escalateMinutesStay\":45}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("TARGET_012"))
                .andExpect(jsonPath("$.data.notificationMode").value("REALTIME"))
                .andExpect(jsonPath("$.data.paused").value(false));
    }

    @Test
    @DisplayName("수정 — 명시적 null은 '승격 안 함'으로 허용되어 null 그대로 서비스에 전달된다")
    void updateSettings_explicitNullMinutes_isAcceptedAndPassedAsNull() throws Exception {
        // given
        when(service.updateSettings(GUARDIAN_ID, TARGET_ID, "HYBRID", null, null))
                .thenReturn(settings("HYBRID", null, null, false, null));

        // when & then
        putJson("{\"notificationMode\":\"HYBRID\",\"escalateMinutesArrival\":null,\"escalateMinutesStay\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("TARGET_012"));
        verify(service).updateSettings(GUARDIAN_ID, TARGET_ID, "HYBRID", null, null);
    }

    @Test
    @DisplayName("수정 — escalateMinutesArrival 키를 빠뜨리면 COMMON_002(400)이고 errors에 그 필드명이 나오며 서비스는 호출하지 않는다")
    void updateSettings_missingArrivalKey_returnsCommon002WithFieldName() throws Exception {
        putJson("{\"notificationMode\":\"HYBRID\",\"escalateMinutesStay\":60}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("escalateMinutesArrival"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("수정 — 승격 분 키가 둘 다 없으면 두 필드 모두 오류로 알려준다(조용히 승격이 꺼지지 않음)")
    void updateSettings_bothMinuteKeysMissing_returnsBothFieldErrors() throws Exception {
        putJson("{\"notificationMode\":\"HYBRID\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"))
                .andExpect(jsonPath("$.errors.length()").value(2));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("수정 — 승격 분이 0, 음수, 1441이면 COMMON_002(400)이다(1과 1440은 허용)")
    void updateSettings_minutesOutOfRange_returnsCommon002() throws Exception {
        putJson("{\"notificationMode\":\"HYBRID\",\"escalateMinutesArrival\":0,\"escalateMinutesStay\":60}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("escalateMinutesArrival"));
        putJson("{\"notificationMode\":\"HYBRID\",\"escalateMinutesArrival\":30,\"escalateMinutesStay\":-5}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("escalateMinutesStay"));
        putJson("{\"notificationMode\":\"HYBRID\",\"escalateMinutesArrival\":1441,\"escalateMinutesStay\":60}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("escalateMinutesArrival"));
        verifyNoInteractions(service);

        // 경계값은 허용
        when(service.updateSettings(GUARDIAN_ID, TARGET_ID, "HYBRID", 1, 1440))
                .thenReturn(settings("HYBRID", 1, 1440, false, null));
        putJson("{\"notificationMode\":\"HYBRID\",\"escalateMinutesArrival\":1,\"escalateMinutesStay\":1440}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("수정 — 모드가 PAUSED이거나 알 수 없는 값이거나 없으면 COMMON_002(400)이고 필드는 notificationMode이다")
    void updateSettings_invalidMode_returnsCommon002() throws Exception {
        putJson("{\"notificationMode\":\"PAUSED\",\"escalateMinutesArrival\":30,\"escalateMinutesStay\":60}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("notificationMode"));
        putJson("{\"notificationMode\":\"UNKNOWN\",\"escalateMinutesArrival\":30,\"escalateMinutesStay\":60}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("notificationMode"));
        putJson("{\"escalateMinutesArrival\":30,\"escalateMinutesStay\":60}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("notificationMode"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("수정 — 승격 분이 정수가 아니거나 본문이 없으면 500이 아니라 COMMON_002(400)이다")
    void updateSettings_wrongTypeOrNoBody_returnsCommon002() throws Exception {
        putJson("{\"notificationMode\":\"HYBRID\",\"escalateMinutesArrival\":\"abc\",\"escalateMinutesStay\":60}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"))
                .andExpect(jsonPath("$.errors[0].field").value("escalateMinutesArrival"));
        mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("수정 — 본문에 guardianId를 보내도 무시하고 항상 인증된 사용자 id로 본인 행만 다룬다")
    void updateSettings_guardianIdInBody_isIgnoredAndPrincipalIsUsed() throws Exception {
        // given
        when(service.updateSettings(GUARDIAN_ID, TARGET_ID, "HYBRID", 30, 60))
                .thenReturn(settings("HYBRID", 30, 60, false, null));

        // when
        putJson("{\"guardianId\":999,\"notificationMode\":\"HYBRID\",\"escalateMinutesArrival\":30,\"escalateMinutesStay\":60}")
                .andExpect(status().isOk());

        // then — 본문의 999가 아니라 principal(7)로만 호출됐다
        verify(service).updateSettings(GUARDIAN_ID, TARGET_ID, "HYBRID", 30, 60);
        verify(service, never()).updateSettings(eq(999L), any(), any(), any(), any());
    }

    @Test
    @DisplayName("수정 — 락 경합 실패(COMMON_008)는 409로 응답한다(재시도 유도)")
    void updateSettings_lockConflict_returns409() throws Exception {
        // given
        when(service.updateSettings(GUARDIAN_ID, TARGET_ID, "HYBRID", 30, 60))
                .thenThrow(new DataAccessCustomException(ErrorCode.COMMON_008));

        // when & then
        putJson("{\"notificationMode\":\"HYBRID\",\"escalateMinutesArrival\":30,\"escalateMinutesStay\":60}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMON_008"));
    }

    @Test
    @DisplayName("수정 — ACTIVE 관계가 아니면 TARGET_002(403)이다")
    void updateSettings_noActiveRelation_returns403() throws Exception {
        // given
        when(service.updateSettings(GUARDIAN_ID, TARGET_ID, "HYBRID", 30, 60))
                .thenThrow(new AccessDeniedCustomException(ErrorCode.TARGET_002));

        // when & then
        putJson("{\"notificationMode\":\"HYBRID\",\"escalateMinutesArrival\":30,\"escalateMinutesStay\":60}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TARGET_002"));
    }

    // ---------------------------------------------------------------------
    // pause
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("정지 성공 — durationMinutes를 서비스에 전달하고 TARGET_013과 paused=true를 반환한다")
    void pause_success_returnsTarget013() throws Exception {
        // given
        Instant until = Instant.parse("2026-09-21T07:00:00Z");
        when(service.pause(GUARDIAN_ID, TARGET_ID, 30))
                .thenReturn(settings("HYBRID", 30, 60, true, until));

        // when & then
        postPause("{\"durationMinutes\":30}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("TARGET_013"))
                .andExpect(jsonPath("$.data.paused").value(true))
                .andExpect(jsonPath("$.data.pausedUntil").value("2026-09-21T07:00:00Z"))
                .andExpect(jsonPath("$.data.notificationMode").value("HYBRID"));
    }

    @Test
    @DisplayName("정지 경계값 — 1분과 1440분은 허용된다")
    void pause_boundaryDurations_1And1440_areAccepted() throws Exception {
        // given
        when(service.pause(GUARDIAN_ID, TARGET_ID, 1))
                .thenReturn(settings("HYBRID", 30, 60, true, Instant.now()));
        when(service.pause(GUARDIAN_ID, TARGET_ID, 1440))
                .thenReturn(settings("HYBRID", 30, 60, true, Instant.now()));

        // when & then
        postPause("{\"durationMinutes\":1}").andExpect(status().isOk());
        postPause("{\"durationMinutes\":1440}").andExpect(status().isOk());
    }

    @Test
    @DisplayName("정지 — durationMinutes가 0, 음수, 1441이거나 누락이면 COMMON_002(400)이고 서비스는 호출하지 않는다")
    void pause_invalidDuration_returnsCommon002() throws Exception {
        postPause("{\"durationMinutes\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("durationMinutes"));
        postPause("{\"durationMinutes\":-1}").andExpect(status().isBadRequest());
        postPause("{\"durationMinutes\":1441}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("durationMinutes"));
        postPause("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("durationMinutes"));
        verify(service, never()).pause(any(), any(), anyInt());
    }

    @Test
    @DisplayName("정지 — durationMinutes가 정수가 아니거나 본문이 없으면 500이 아니라 COMMON_002(400)이다")
    void pause_wrongTypeOrNoBody_returnsCommon002() throws Exception {
        postPause("{\"durationMinutes\":\"abc\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"))
                .andExpect(jsonPath("$.errors[0].field").value("durationMinutes"));
        mockMvc.perform(post(BASE + "/pause").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
        verify(service, never()).pause(any(), any(), anyInt());
    }

    @Test
    @DisplayName("정지 — ACTIVE 관계가 아니면 TARGET_002(403)이다")
    void pause_noActiveRelation_returns403() throws Exception {
        // given
        when(service.pause(GUARDIAN_ID, TARGET_ID, 30))
                .thenThrow(new AccessDeniedCustomException(ErrorCode.TARGET_002));

        // when & then
        postPause("{\"durationMinutes\":30}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TARGET_002"));
    }

    // ---------------------------------------------------------------------
    // resume
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("재개 성공 — 본문 없이 호출하고 TARGET_014와 paused=false를 반환한다")
    void resume_success_returnsTarget014() throws Exception {
        // given
        when(service.resume(GUARDIAN_ID, TARGET_ID))
                .thenReturn(settings("REALTIME", 10, 20, false, null));

        // when & then
        mockMvc.perform(post(BASE + "/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("TARGET_014"))
                .andExpect(jsonPath("$.data.paused").value(false))
                .andExpect(jsonPath("$.data.pausedUntil").doesNotExist());
    }

    @Test
    @DisplayName("재개 — ACTIVE 관계가 아니면 TARGET_002(403), CareTarget이 없으면 TARGET_001(404)이다")
    void resume_relationOrTargetMissing_returns403Or404() throws Exception {
        // given
        when(service.resume(GUARDIAN_ID, TARGET_ID))
                .thenThrow(new AccessDeniedCustomException(ErrorCode.TARGET_002))
                .thenThrow(new CareTargetNotFoundException());

        // when & then
        mockMvc.perform(post(BASE + "/resume"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TARGET_002"));
        mockMvc.perform(post(BASE + "/resume"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TARGET_001"));
    }
}
