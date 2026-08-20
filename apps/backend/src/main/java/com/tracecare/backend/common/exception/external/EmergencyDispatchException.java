package com.tracecare.backend.common.exception.external;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

/**
 * 긴급 연락(전화/SMS) 발송 자체가 실패한 경우 전용 예외(EMERGENCY_003). Exception_Handling_Rule.md §9.2 fail-safe 원칙 대상
 * — 일반 외부 연동 실패(ExternalApiException)와 달리 항상 이력을 남기고 code를 그대로 노출해야 하므로 별도 타입으로 분리한다.
 */
public class EmergencyDispatchException extends BusinessException {

    public EmergencyDispatchException() {
        super(ErrorCode.EMERGENCY_003);
    }
}
