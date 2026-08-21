package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.ErrorCode;

public class DuplicatePendingInviteException extends DuplicateResourceException {

    public DuplicatePendingInviteException() {
        super(ErrorCode.TARGET_006);
    }
}
