package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

public class InvalidInviteCodeException extends BusinessException {

    public InvalidInviteCodeException() {
        super(ErrorCode.TARGET_004);
    }
}
