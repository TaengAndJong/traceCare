package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

public class NotPrimaryGuardianException extends BusinessException {

    public NotPrimaryGuardianException() {
        super(ErrorCode.GUARDIAN_004);
    }
}
