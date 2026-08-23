package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

public class InvalidLocationCoordinateException extends BusinessException {

    public InvalidLocationCoordinateException() {
        super(ErrorCode.LOCATION_001);
    }
}
