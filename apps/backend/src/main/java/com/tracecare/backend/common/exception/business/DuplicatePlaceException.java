package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.ErrorCode;

public class DuplicatePlaceException extends DuplicateResourceException {

    public DuplicatePlaceException() {
        super(ErrorCode.PLACE_002);
    }
}
