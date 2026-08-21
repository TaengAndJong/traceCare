package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

public class GuardianCapacityExceededException extends BusinessException {

    public GuardianCapacityExceededException() {
        super(ErrorCode.TARGET_005);
    }
}
