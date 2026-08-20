package com.tracecare.backend.common.response;

import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.common.exception.ErrorCode;

@Getter
@Builder
public class ApiResponse<T> {

    private final boolean success;
    private final String code;
    private final String message;
    private final T data;

    public static <T> ApiResponse<T> success(SuccessCode code, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .code(code.getCode())
                .message(code.getMessage())
                .data(data)
                .build();
    }

    public static ApiResponse<Void> success(SuccessCode code) {
        return success(code, null);
    }

    public static ApiResponse<Void> error(ErrorCode code) {
        return ApiResponse.<Void>builder()
                .success(false)
                .code(code.getCode())
                .message(code.getMessage())
                .data(null)
                .build();
    }
}
