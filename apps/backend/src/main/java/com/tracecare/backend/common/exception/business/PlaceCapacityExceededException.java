package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

public class PlaceCapacityExceededException extends BusinessException {

    public PlaceCapacityExceededException() {
        super(ErrorCode.PLACE_004);
    }
}
