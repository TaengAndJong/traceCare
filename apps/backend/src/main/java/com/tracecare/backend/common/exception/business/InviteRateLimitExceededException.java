package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

public class InviteRateLimitExceededException extends BusinessException {

    public InviteRateLimitExceededException() {
        super(ErrorCode.TARGET_007);
    }
}
