package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.ResourceNotFoundException;

public class PredictionNotFoundException extends ResourceNotFoundException {

    public PredictionNotFoundException() {
        super(ErrorCode.AI_003);
    }
}
