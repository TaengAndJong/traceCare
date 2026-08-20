package com.tracecare.backend.common.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.response.ApiResponse;

/**
 * 인증은 됐지만 Role 불일치로 URL 패턴 인가(2단계)에서 걸린 경우 호출된다(Security_Guide.md §2.6, §8.2). 리소스 소유권 불일치(3단계)는 이
 * Handler를 거치지 않고 Service 계층의 AccessDeniedCustomException → GlobalExceptionHandler 경로로
 * 처리된다(Security_Guide.md §8.1).
 *
 * <p>Guardian API(`/api/guardian/**`)에 대한 Role 불일치는 API_Specification.md에 명시된 대로 GUARDIAN_001을 그대로
 * 쓰고, 그 외 Role 전용 URL(`/api/care-target/**` 등)에 대한 Role 불일치는 도메인별 코드가 문서에 없으므로 범용 코드 COMMON_006을
 * 쓴다(API_Response_Rule.md §5.2).
 */
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private static final String GUARDIAN_PATH_PREFIX = "/api/guardian/";

    private static final Logger log = LoggerFactory.getLogger(JwtAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;

    public JwtAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        String uri = request.getRequestURI();
        ErrorCode errorCode = resolveErrorCode(uri);
        log.warn("event=ACCESS_DENIED, uri={}, errorCode={}", uri, errorCode);

        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(errorCode)));
    }

    private ErrorCode resolveErrorCode(String uri) {
        if (uri != null && uri.startsWith(GUARDIAN_PATH_PREFIX)) {
            return ErrorCode.GUARDIAN_001;
        }
        return ErrorCode.COMMON_006;
    }
}
