package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.ResourceNotFoundException;

public class UserNotFoundException extends ResourceNotFoundException {

    public UserNotFoundException() {
        super(ErrorCode.USER_001);
    }
}
