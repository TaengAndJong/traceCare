package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

/**
 * Bean Validation(Custom Validator)을 우회해 DB CHECK 제약(ck_place_lat/lng/radius)까지 도달한 경우의 최종
 * 방어선(Exception_Handling_Rule.md §6.2/§10.3). 정상 흐름에서는 Controller 진입 시점의 Custom
 * Validator(`@ValidLatitude` 등)가 먼저 걸러내므로 실제로는 거의 발생하지 않는다.
 */
public class InvalidPlaceRangeException extends BusinessException {

    public InvalidPlaceRangeException() {
        super(ErrorCode.PLACE_003);
    }
}
