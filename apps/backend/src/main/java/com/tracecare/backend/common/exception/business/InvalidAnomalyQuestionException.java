package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

/**
 * {@code /explain}의 {@code question} 값이 질문 카탈로그에 없거나, 해당 이벤트의 유형에 속하지 않는 키일 때(ANOMALY_002,
 * API_Specification.md §3.9). {@code InvalidLocationCoordinateException}/{@code InvalidPlaceRangeException}처럼 도메인
 * 전용 400은 {@code InvalidRequestException}(COMMON_002 전용)이 아니라 별도 클래스로 둔다.
 */
public class InvalidAnomalyQuestionException extends BusinessException {

    public InvalidAnomalyQuestionException() {
        super(ErrorCode.ANOMALY_002);
    }
}
