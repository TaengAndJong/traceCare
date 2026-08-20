package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

public class EmergencyContactMissingException extends BusinessException {

    public EmergencyContactMissingException() {
        super(ErrorCode.EMERGENCY_002);
    }
}
