package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

public class SelfDelegationException extends BusinessException {

    public SelfDelegationException() {
        super(ErrorCode.GUARDIAN_006);
    }
}
