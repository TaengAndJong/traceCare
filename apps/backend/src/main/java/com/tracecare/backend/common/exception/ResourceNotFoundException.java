package com.tracecare.backend.common.exception;

public abstract class ResourceNotFoundException extends BusinessException {

    protected ResourceNotFoundException(ErrorCode errorCode) {
        super(errorCode);
    }
}
