package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.ResourceNotFoundException;

public class CareTargetNotFoundException extends ResourceNotFoundException {

    public CareTargetNotFoundException() {
        super(ErrorCode.TARGET_001);
    }
}
