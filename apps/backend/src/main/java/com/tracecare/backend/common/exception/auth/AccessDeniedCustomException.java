package com.tracecare.backend.common.exception.auth;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

/**
 * 3단계 리소스 소유권 검증 실패 전용 예외(Security_Guide.md §4.5, §8.1). Role 불일치(2단계, GUARDIAN_001)는 Filter의
 * AccessDeniedHandler가 직접 처리하므로 이 예외를 거치지 않는다.
 */
public class AccessDeniedCustomException extends BusinessException {

    public AccessDeniedCustomException(ErrorCode errorCode) {
        super(errorCode);
    }
}
