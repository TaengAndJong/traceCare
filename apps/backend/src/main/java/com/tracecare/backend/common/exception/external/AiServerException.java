package com.tracecare.backend.common.exception.external;

import com.tracecare.backend.common.exception.ErrorCode;

public class AiServerException extends ExternalApiException {

    public AiServerException(String targetService, ErrorCode errorCode) {
        super(targetService, errorCode);
    }
}
