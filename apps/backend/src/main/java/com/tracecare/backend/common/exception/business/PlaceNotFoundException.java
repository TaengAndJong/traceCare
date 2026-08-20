package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.ResourceNotFoundException;

public class PlaceNotFoundException extends ResourceNotFoundException {

    public PlaceNotFoundException() {
        super(ErrorCode.PLACE_001);
    }
}
