package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.ResourceNotFoundException;

/** {@code /explain} 대상 이상행동 이벤트가 존재하지 않을 때(ANOMALY_001, API_Specification.md §3.9). */
public class AnomalyEventNotFoundException extends ResourceNotFoundException {

    public AnomalyEventNotFoundException() {
        super(ErrorCode.ANOMALY_001);
    }
}
