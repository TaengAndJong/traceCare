package com.tracecare.backend.common.exception.external;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

public class ExternalApiException extends BusinessException {

    private final String targetService;

    public ExternalApiException(String targetService, ErrorCode errorCode) {
        super(errorCode);
        this.targetService = targetService;
    }

    public String getTargetService() {
        return targetService;
    }
}
