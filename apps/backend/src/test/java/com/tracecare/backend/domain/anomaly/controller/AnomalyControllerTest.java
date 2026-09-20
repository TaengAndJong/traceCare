package com.tracecare.backend.domain.anomaly.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.GlobalExceptionHandler;
import com.tracecare.backend.common.exception.business.AnomalyEventNotFoundException;
import com.tracecare.backend.common.exception.business.InvalidAnomalyQuestionException;
import com.tracecare.backend.common.security.CustomUserDetails;
import com.tracecare.backend.domain.anomaly.dto.response.AnomalyExplainResponse;
import com.tracecare.backend.domain.anomaly.dto.response.AnomalyQuestionResponse;
import com.tracecare.backend.domain.anomaly.service.AnomalyQuestionKey;
import com.tracecare.backend.domain.anomaly.service.AnomalyService;

/**
 * URI/파라미터 매핑, {@code @AuthenticationPrincipal}로 받은 userId 전달, ApiResponse 포맷과 에러 코드→HTTP Status 매핑을
 * 검증한다. Service는 Mock이며, 프레임워크 파라미터 예외(누락/형식 오류)가 500이 아니라 COMMON_002(400)로 나가는지도 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class AnomalyControllerTest {

    private static final Long GUARDIAN_ID = 7L;

    @Mock private AnomalyService anomalyService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new AnomalyController(anomalyService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setCustomArgumentResolvers(
                                new AuthenticationPrincipalArgumentResolver(),
                                new PageableHandlerMethodArgumentResolver())
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

    @Test
    @DisplayName("목록 조회 성공 — 인증된 Guardian의 userId로 Service를 호출하고 ANOMALY_001 + 빈 content를 반환한다")
    void getAnomalies_success_returnsAnomaly001() throws Exception {
        // given
        UUID careTargetId = UUID.randomUUID();
        when(anomalyService.getAnomalies(
                        eq(GUARDIAN_ID), eq(careTargetId), eq("ARRIVAL_DELAY"), any(), any(), any()))
                .thenReturn(Page.empty());

        // when & then
        mockMvc.perform(
                        get("/api/guardian/anomalies")
                                .param("careTargetId", careTargetId.toString())
                                .param("type", "ARRIVAL_DELAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("ANOMALY_001"))
                .andExpect(jsonPath("$.data.content", hasSize(0)));
    }

    @Test
    @DisplayName("목록 조회 시 careTargetId가 없으면 500이 아니라 COMMON_002(400)이다")
    void getAnomalies_missingCareTargetId_returnsCommon002() throws Exception {
        mockMvc.perform(get("/api/guardian/anomalies"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_002"))
                .andExpect(jsonPath("$.errors[0].field").value("careTargetId"));
    }

    @Test
    @DisplayName("목록 조회 시 careTargetId가 UUID 형식이 아니면 COMMON_002(400)이다")
    void getAnomalies_malformedCareTargetId_returnsCommon002() throws Exception {
        mockMvc.perform(get("/api/guardian/anomalies").param("careTargetId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("목록 조회 시 from이 ISO-8601 시각이 아니면 COMMON_002(400)이다")
    void getAnomalies_malformedFrom_returnsCommon002() throws Exception {
        mockMvc.perform(
                        get("/api/guardian/anomalies")
                                .param("careTargetId", UUID.randomUUID().toString())
                                .param("from", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"))
                .andExpect(jsonPath("$.errors[0].field").value("from"));
    }

    @Test
    @DisplayName("질문 카탈로그 조회 성공 — /questions가 /{id}/explain과 충돌하지 않고 ANOMALY_003을 반환한다")
    void getQuestions_success_returnsAnomaly003() throws Exception {
        // given
        when(anomalyService.getQuestions("ARRIVAL_DELAY"))
                .thenReturn(List.of(AnomalyQuestionResponse.of(AnomalyQuestionKey.DELAY_REASON, "10분")));

        // when & then
        mockMvc.perform(get("/api/guardian/anomalies/questions").param("type", "ARRIVAL_DELAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ANOMALY_003"))
                .andExpect(jsonPath("$.data.content[0].questionKey").value("DELAY_REASON"))
                .andExpect(jsonPath("$.data.content[0].text").value("왜 감지됐습니까?"));
    }

    @Test
    @DisplayName("설명 조회 성공 — answer 문자열 하나를 ANOMALY_002로 반환하고 userId·eventId·question을 그대로 전달한다")
    void explain_success_returnsAnswer() throws Exception {
        // given
        when(anomalyService.explain(GUARDIAN_ID, 500L, "DELAY_REASON"))
                .thenReturn(AnomalyExplainResponse.builder().answer("설명 문장").build());

        // when & then
        mockMvc.perform(
                        get("/api/guardian/anomalies/500/explain").param("question", "DELAY_REASON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ANOMALY_002"))
                .andExpect(jsonPath("$.data.answer").value("설명 문장"));
        verify(anomalyService).explain(GUARDIAN_ID, 500L, "DELAY_REASON");
    }

    @Test
    @DisplayName("설명 조회 시 이벤트가 없으면 ANOMALY_001(404)로 응답한다")
    void explain_eventNotFound_returns404() throws Exception {
        // given
        when(anomalyService.explain(GUARDIAN_ID, 999L, "DELAY_REASON"))
                .thenThrow(new AnomalyEventNotFoundException());

        // when & then
        mockMvc.perform(
                        get("/api/guardian/anomalies/999/explain").param("question", "DELAY_REASON"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.ANOMALY_001.getCode()));
    }

    @Test
    @DisplayName("설명 조회 시 지원하지 않는 질문이면 ANOMALY_002(400)로 응답한다")
    void explain_invalidQuestion_returns400() throws Exception {
        // given
        when(anomalyService.explain(GUARDIAN_ID, 500L, "NOPE"))
                .thenThrow(new InvalidAnomalyQuestionException());

        // when & then
        mockMvc.perform(get("/api/guardian/anomalies/500/explain").param("question", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.ANOMALY_002.getCode()));
    }

    @Test
    @DisplayName("설명 조회 시 anomalyEventId가 숫자가 아니면 COMMON_002(400)이다")
    void explain_nonNumericEventId_returnsCommon002() throws Exception {
        mockMvc.perform(
                        get("/api/guardian/anomalies/abc/explain").param("question", "DELAY_REASON"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }
}
