package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.ResourceNotFoundException;

public class LocationNotFoundException extends ResourceNotFoundException {

    public LocationNotFoundException() {
        super(ErrorCode.LOCATION_002);
    }
}
