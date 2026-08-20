package com.tracecare.backend.common.exception.infra;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

public class DataAccessCustomException extends BusinessException {

    public DataAccessCustomException(ErrorCode errorCode) {
        super(errorCode);
    }
}
