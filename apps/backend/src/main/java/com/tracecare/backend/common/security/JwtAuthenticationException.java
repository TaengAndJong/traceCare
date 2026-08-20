package com.tracecare.backend.common.security;

import org.springframework.security.core.AuthenticationException;

import com.tracecare.backend.common.exception.ErrorCode;

/**
 * JwtAuthenticationFilter의 인증 실패 사유를 ErrorCode와 함께 담아 JwtAuthenticationEntryPoint까지 전달하는 예외.
 * common.exception.BusinessException이 자기 ErrorCode를 갖는 것과 동일한 패턴이다.
 *
 * <p>이 예외는 필터에서 throw되지 않고 request attribute에 담겨 전달된다 — 이유는 JwtAuthenticationFilter 클래스 상단 주석 참고.
 */
public class JwtAuthenticationException extends AuthenticationException {

    private final ErrorCode errorCode;

    public JwtAuthenticationException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
