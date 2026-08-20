package com.tracecare.backend.common.response;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ErrorResponse {

    private final boolean success;
    private final String code;
    private final String message;
    private final Object data;
    private final List<FieldErrorDetail> errors;

    public static ErrorResponse of(
            com.tracecare.backend.common.exception.ErrorCode code, List<FieldErrorDetail> errors) {
        return ErrorResponse.builder()
                .success(false)
                .code(code.getCode())
                .message(code.getMessage())
                .data(null)
                .errors(errors)
                .build();
    }

    @Getter
    @Builder
    public static class FieldErrorDetail {
        private final String field;
        private final String reason;
    }
}
