package com.tracecare.backend.common.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.response.ApiResponse;

/**
 * 인증 자체가 안 된 상태(토큰 없음/만료/위변조/Blacklist)에서 호출된다(Security_Guide.md §2.5). 실패 사유는
 * JwtAuthenticationFilter가 request attribute로 남긴 JwtAuthenticationException에서 꺼낸다. 토큰 자체가 없어서
 * (permitAll이 아닌 URL에 Authorization 헤더 없이 접근) 이 필터가 attribute를 남기지 않은 경우에는 기본값 AUTH_001을 쓴다.
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        Object attribute = request.getAttribute(JwtAuthenticationFilter.JWT_ERROR_ATTRIBUTE);
        ErrorCode errorCode =
                attribute instanceof JwtAuthenticationException jwtException
                        ? jwtException.getErrorCode()
                        : ErrorCode.AUTH_001;

        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(errorCode)));
    }
}
