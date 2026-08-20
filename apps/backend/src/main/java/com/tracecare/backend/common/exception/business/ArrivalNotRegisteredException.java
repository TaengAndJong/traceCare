package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

public class ArrivalNotRegisteredException extends BusinessException {

    public ArrivalNotRegisteredException() {
        super(ErrorCode.ARRIVAL_002);
    }
}
