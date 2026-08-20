package com.tracecare.backend.common.exception.auth;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

public class AuthenticationFailedException extends BusinessException {

    public AuthenticationFailedException(ErrorCode errorCode) {
        super(errorCode);
    }
}
