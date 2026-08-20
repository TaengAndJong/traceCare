package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.ResourceNotFoundException;

public class ArrivalHistoryNotFoundException extends ResourceNotFoundException {

    public ArrivalHistoryNotFoundException() {
        super(ErrorCode.ARRIVAL_003);
    }
}
