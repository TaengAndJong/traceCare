package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

public class InvalidDelegationTargetException extends BusinessException {

    public InvalidDelegationTargetException() {
        super(ErrorCode.GUARDIAN_005);
    }
}
