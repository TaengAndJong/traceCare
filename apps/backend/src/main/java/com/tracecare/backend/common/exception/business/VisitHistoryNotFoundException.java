package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.ResourceNotFoundException;

public class VisitHistoryNotFoundException extends ResourceNotFoundException {

    public VisitHistoryNotFoundException() {
        super(ErrorCode.VISIT_001);
    }
}
