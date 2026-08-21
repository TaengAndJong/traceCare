package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

public class CareTargetCapacityExceededException extends BusinessException {

    public CareTargetCapacityExceededException() {
        super(ErrorCode.GUARDIAN_003);
    }
}
