package com.tracecare.backend.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.tracecare.backend.common.exception.business.CareTargetNotFoundException;

/**
 * {@link GlobalExceptionHandler}의 프레임워크 라우팅/협상 단계 예외 처리를 검증한다(API_Response_Rule.md §5.2, Exception_Handling_Rule.md).
 * 존재하지 않는 URI는 404(COMMON_003), 허용되지 않은 HTTP Method는 405(COMMON_004), 지원하지 않는 Content-Type은
 * 415(COMMON_009)로 응답해야 하며(이전에는 처리되지 않은 예외로 분류돼 500), 응답 메시지와 로그에는 요청 URI/Method/미디어 타입 원문이
 * 남지 않아야 한다. 기존 Business/미분류 예외 처리와 정상 요청에는 영향이 없어야 한다.
 */
class FrameworkExceptionHandlerTest {

    private static final String SECRET_PATH = "/test/secret-path-abc123";

    @RestController
    static class Controller {

        @GetMapping("/test/ok")
        String ok() {
            return "ok";
        }

        @GetMapping("/test/get-only")
        String getOnly() {
            return "ok";
        }

        @PostMapping(value = "/test/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        String json(@RequestBody String body) {
            return "ok";
        }

        /** Spring Boot 실제 앱에서 정적 리소스 핸들러가 던지는 예외를 그대로 재현한다. */
        @GetMapping("/test/no-resource")
        String noResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, SECRET_PATH);
        }

        @GetMapping("/test/business")
        String business() {
            throw new CareTargetNotFoundException();
        }

        @GetMapping("/test/unexpected")
        String unexpected() {
            throw new IllegalStateException("예상하지 못한 오류(테스트)");
        }
    }

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger handlerLogger;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new Controller())
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

    private ILoggingEvent onlyLogEvent() {
        assertThat(logAppender.list).hasSize(1);
        return logAppender.list.get(0);
    }

    @Test
    @DisplayName("존재하지 않는 URI 요청은 500이 아니라 404(COMMON_003)로 응답한다")
    void unmappedUri_returns404Common003() throws Exception {
        mockMvc.perform(get("/test/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_003"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("정적 리소스 핸들러가 던지는 NoResourceFoundException도 404(COMMON_003)로 응답하고 경로 원문을 노출하지 않는다")
    void noResourceFoundException_returns404Common003WithoutPath() throws Exception {
        mockMvc.perform(get("/test/no-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_003"))
                .andExpect(jsonPath("$.message").value(ErrorCode.COMMON_003.getMessage()))
                .andExpect(content().string(not(containsString("secret-path-abc123"))));
    }

    @Test
    @DisplayName("허용되지 않은 HTTP Method 요청은 500이 아니라 405(COMMON_004)로 응답한다")
    void unsupportedMethod_returns405Common004() throws Exception {
        mockMvc.perform(post("/test/get-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_004"))
                .andExpect(jsonPath("$.message").value(ErrorCode.COMMON_004.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type 요청은 500이 아니라 415(COMMON_009)로 응답한다")
    void unsupportedMediaType_returns415Common009() throws Exception {
        mockMvc.perform(post("/test/json").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_009"))
                .andExpect(jsonPath("$.message").value(ErrorCode.COMMON_009.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("응답 메시지에 요청 URI/HTTP Method/미디어 타입 원문이 없고 고정 문구만 나간다")
    void frameworkErrors_messageContainsNoRequestOriginals() throws Exception {
        mockMvc.perform(post("/test/get-only"))
                .andExpect(content().string(not(containsString("/test/get-only"))))
                .andExpect(content().string(not(containsString("POST"))))
                .andExpect(content().string(not(containsString("GET"))));
        mockMvc.perform(post("/test/json").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(content().string(not(containsString("text/plain"))))
                .andExpect(content().string(not(containsString("application/json"))));
    }

    @Test
    @DisplayName("404/405/415는 WARN으로 event=HTTP_REQUEST_REJECTED와 code만 남기고 스택 트레이스와 요청 원문은 남기지 않는다")
    void frameworkErrors_logWarnWithoutStackTraceOrRequestOriginals() throws Exception {
        mockMvc.perform(post("/test/get-only")).andExpect(status().isMethodNotAllowed());

        ILoggingEvent event = onlyLogEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("event=HTTP_REQUEST_REJECTED", "code=COMMON_004")
                .doesNotContain("/test/get-only", "POST");
        assertThat(event.getThrowableProxy()).as("스택 트레이스 없음").isNull();
    }

    @Test
    @DisplayName("404와 415도 같은 방식으로 WARN 한 줄만 남긴다")
    void notFoundAndUnsupportedMediaType_logSingleWarnEach() throws Exception {
        mockMvc.perform(get("/test/no-resource")).andExpect(status().isNotFound());
        ILoggingEvent notFound = onlyLogEvent();
        assertThat(notFound.getFormattedMessage())
                .contains("event=HTTP_REQUEST_REJECTED", "code=COMMON_003")
                .doesNotContain("secret-path-abc123");
        assertThat(notFound.getThrowableProxy()).isNull();

        logAppender.list.clear();
        mockMvc.perform(post("/test/json").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
        ILoggingEvent unsupported = onlyLogEvent();
        assertThat(unsupported.getLevel()).isEqualTo(Level.WARN);
        assertThat(unsupported.getFormattedMessage())
                .contains("event=HTTP_REQUEST_REJECTED", "code=COMMON_009")
                .doesNotContain("text/plain");
        assertThat(unsupported.getThrowableProxy()).isNull();
    }

    // ---------------------------------------------------------------------
    // 회귀: 기존 처리 경로는 그대로다
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("정상 요청은 영향 없이 200으로 처리된다")
    void normalRequest_stillReturns200() throws Exception {
        mockMvc.perform(get("/test/ok")).andExpect(status().isOk()).andExpect(content().string("ok"));
        assertThat(logAppender.list).isEmpty();
    }

    @Test
    @DisplayName("BusinessException은 기존처럼 자기 ErrorCode의 Status로 응답한다")
    void businessException_stillMapsToItsOwnErrorCode() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TARGET_001"));
    }

    @Test
    @DisplayName("처리되지 않은 예외는 기존처럼 500(COMMON_001)로 응답한다")
    void unexpectedException_stillReturns500Common001() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }
}
