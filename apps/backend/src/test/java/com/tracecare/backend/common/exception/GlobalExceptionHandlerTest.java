package com.tracecare.backend.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.tracecare.backend.domain.location.caretarget.dto.request.LocationSendRequest;

/**
 * {@link GlobalExceptionHandler}의 요청 본문 파싱 오류 처리를 검증한다(Exception_Handling_Rule.md §6). 요청 본문을 읽지 못하면 500이
 * 아니라 COMMON_002(400)로 응답하고, 거부된 입력 값 원문은 응답과 로그 어디에도 남기지 않아야 한다. 기존 Bean Validation 처리와 정상 요청에는
 * 영향이 없어야 한다.
 */
class GlobalExceptionHandlerTest {

    private static final String SECRET_VALUE = "SENSITIVE-COORD-abc123";

    /** 실제 API가 쓰는 DTO(LocationSendRequest)와 중첩/배열/enum 경로를 확인하기 위한 테스트 전용 DTO를 함께 받는다. */
    @RestController
    static class BodyController {

        @PostMapping("/test/location")
        String location(@Valid @RequestBody LocationSendRequest request) {
            return "ok";
        }

        @PostMapping("/test/nested")
        String nested(@Valid @RequestBody NestedRequest request) {
            return "ok";
        }
    }

    public static class NestedRequest {
        @NotNull public Child child;
        public List<Child> items;
        public Mode mode;
    }

    public static class Child {
        public Integer value;
    }

    public enum Mode {
        A,
        B
    }

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger handlerLogger;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new BodyController())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logAppender);
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String uri, String body)
            throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("본문 파싱 오류 — JSON 문법이 깨진 요청은 500이 아니라 COMMON_002(400)이고 필드는 body이다")
    void handleUnreadableBody_malformedJson_returnsCommon002() throws Exception {
        postJson("/test/location", "{ \"latitude\": 37.5, ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_002"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors[0].field").value("body"))
                .andExpect(jsonPath("$.errors[0].reason").value("요청 본문이 올바른 JSON 형식이 아닙니다"));
    }

    @Test
    @DisplayName("본문 파싱 오류 — 본문이 아예 없으면 COMMON_002(400)이고 '요청 본문이 필요합니다'로 안내한다")
    void handleUnreadableBody_missingBody_returnsCommon002() throws Exception {
        mockMvc.perform(post("/test/location").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"))
                .andExpect(jsonPath("$.errors[0].field").value("body"))
                .andExpect(jsonPath("$.errors[0].reason").value("요청 본문이 필요합니다"));
    }

    @Test
    @DisplayName("본문 파싱 오류 — 숫자 자리에 문자열이 오면 COMMON_002(400)이고 필드명만 알려준다")
    void handleUnreadableBody_stringWhereNumberExpected_returnsCommon002WithFieldName()
            throws Exception {
        postJson(
                        "/test/location",
                        "{\"latitude\":\"" + SECRET_VALUE + "\",\"longitude\":127.0,"
                                + "\"recordedAt\":\"2026-09-21T06:00:00Z\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"))
                .andExpect(jsonPath("$.errors[0].field").value("latitude"))
                .andExpect(jsonPath("$.errors[0].reason").value("형식이 올바르지 않습니다"));
    }

    @Test
    @DisplayName("본문 파싱 오류 — 날짜(Instant) 형식이 틀리면 COMMON_002(400)이다")
    void handleUnreadableBody_invalidInstant_returnsCommon002() throws Exception {
        postJson(
                        "/test/location",
                        "{\"latitude\":37.5,\"longitude\":127.0,\"recordedAt\":\"yesterday\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"))
                .andExpect(jsonPath("$.errors[0].field").value("recordedAt"));
    }

    @Test
    @DisplayName("본문 파싱 오류 — 중첩 객체 안의 타입 오류는 점(.)으로 이어진 경로로 알려준다")
    void handleUnreadableBody_nestedTypeMismatch_returnsDottedPath() throws Exception {
        postJson("/test/nested", "{\"child\":{\"value\":\"x\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"))
                .andExpect(jsonPath("$.errors[0].field").value("child.value"));
    }

    @Test
    @DisplayName("본문 파싱 오류 — 배열 원소의 타입 오류는 인덱스가 포함된 경로로 알려준다")
    void handleUnreadableBody_arrayElementTypeMismatch_returnsIndexedPath() throws Exception {
        postJson("/test/nested", "{\"child\":{\"value\":1},\"items\":[{\"value\":1},{\"value\":\"x\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("items[1].value"));
    }

    @Test
    @DisplayName("본문 파싱 오류 — 허용되지 않은 enum 값도 COMMON_002(400)이다")
    void handleUnreadableBody_unknownEnumValue_returnsCommon002() throws Exception {
        postJson("/test/nested", "{\"child\":{\"value\":1},\"mode\":\"C\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"))
                .andExpect(jsonPath("$.errors[0].field").value("mode"));
    }

    @Test
    @DisplayName("본문 파싱 오류 — 객체 자리에 배열이 오는 등 루트 타입이 틀리면 필드를 특정하지 않고 body로 응답한다")
    void handleUnreadableBody_rootTypeMismatch_returnsBodyField() throws Exception {
        postJson("/test/location", "[1,2,3]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"))
                .andExpect(jsonPath("$.errors[0].field").value("body"));
    }

    @Test
    @DisplayName("본문 파싱 오류 — 응답에 거부된 입력 값 원문이나 Jackson/클래스명 같은 내부 정보를 노출하지 않는다")
    void handleUnreadableBody_response_doesNotLeakInputOrInternals() throws Exception {
        postJson(
                        "/test/location",
                        "{\"latitude\":\"" + SECRET_VALUE + "\",\"longitude\":127.0,"
                                + "\"recordedAt\":\"2026-09-21T06:00:00Z\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString(SECRET_VALUE))))
                .andExpect(content().string(not(containsString("com.fasterxml"))))
                .andExpect(content().string(not(containsString("Cannot deserialize"))))
                .andExpect(content().string(not(containsString("LocationSendRequest"))));
    }

    @Test
    @DisplayName("본문 파싱 오류 — 로그에는 WARN으로 필드 경로와 원인 종류만 남기고 입력 값 원문은 남기지 않는다")
    void handleUnreadableBody_log_containsFieldOnlyAndNoInputValue() throws Exception {
        postJson(
                        "/test/location",
                        "{\"latitude\":\"" + SECRET_VALUE + "\",\"longitude\":127.0,"
                                + "\"recordedAt\":\"2026-09-21T06:00:00Z\"}")
                .andExpect(status().isBadRequest());

        assertThat(logAppender.list).hasSize(1);
        ILoggingEvent logged = logAppender.list.get(0);
        assertThat(logged.getLevel()).isEqualTo(Level.WARN);
        assertThat(logged.getFormattedMessage())
                .contains("event=VALIDATION_FAILED")
                .contains("latitude")
                .contains("InvalidFormatException")
                .doesNotContain(SECRET_VALUE);
        assertThat(logged.getThrowableProxy()).isNull(); // WARN에는 스택 트레이스를 남기지 않는다
    }

    @Test
    @DisplayName("기존 동작 유지 — 올바른 JSON이면 정상 처리된다")
    void handleUnreadableBody_validRequest_isUnaffected() throws Exception {
        postJson(
                        "/test/location",
                        "{\"latitude\":37.5,\"longitude\":127.0,\"recordedAt\":\"2026-09-21T06:00:00Z\"}")
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
        assertThat(logAppender.list).isEmpty();
    }

    @Test
    @DisplayName("기존 동작 유지 — 형식은 맞지만 @NotNull을 어긴 요청은 여전히 Bean Validation 핸들러가 필드별 오류로 응답한다")
    void handleUnreadableBody_beanValidationFailure_stillHandledByValidationHandler()
            throws Exception {
        postJson("/test/location", "{\"latitude\":37.5,\"recordedAt\":\"2026-09-21T06:00:00Z\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"))
                .andExpect(jsonPath("$.errors[0].field").value("longitude"));
    }
}
