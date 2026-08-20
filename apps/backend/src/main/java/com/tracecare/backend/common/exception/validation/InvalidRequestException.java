package com.tracecare.backend.common.exception.validation;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

public class InvalidRequestException extends BusinessException {

    public InvalidRequestException(ErrorCode errorCode) {
        super(errorCode);
    }
}
