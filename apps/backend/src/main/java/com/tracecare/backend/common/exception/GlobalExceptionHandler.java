package com.tracecare.backend.common.exception;

import java.util.List;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.tracecare.backend.common.exception.auth.AccessDeniedCustomException;
import com.tracecare.backend.common.exception.external.ExternalApiException;
import com.tracecare.backend.common.exception.infra.DataAccessCustomException;
import com.tracecare.backend.common.response.ApiResponse;
import com.tracecare.backend.common.response.ErrorResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<ErrorResponse.FieldErrorDetail> errors =
                e.getBindingResult().getFieldErrors().stream()
                        .map(
                                fe ->
                                        ErrorResponse.FieldErrorDetail.builder()
                                                .field(fe.getField())
                                                .reason(fe.getDefaultMessage())
                                                .build())
                        .toList();
        log.warn(
                "event=VALIDATION_FAILED, fields={}",
                errors.stream().map(ErrorResponse.FieldErrorDetail::getField).toList());
        return ResponseEntity.status(ErrorCode.COMMON_002.getHttpStatus())
                .body(ErrorResponse.of(ErrorCode.COMMON_002, errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        List<ErrorResponse.FieldErrorDetail> errors =
                e.getConstraintViolations().stream()
                        .map(
                                cv ->
                                        ErrorResponse.FieldErrorDetail.builder()
                                                .field(cv.getPropertyPath().toString())
                                                .reason(cv.getMessage())
                                                .build())
                        .toList();
        log.warn(
                "event=VALIDATION_FAILED, fields={}",
                errors.stream().map(ErrorResponse.FieldErrorDetail::getField).toList());
        return ResponseEntity.status(ErrorCode.COMMON_002.getHttpStatus())
                .body(ErrorResponse.of(ErrorCode.COMMON_002, errors));
    }

    @ExceptionHandler(AccessDeniedCustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceAccessDenied(
            AccessDeniedCustomException e) {
        log.warn("event=RESOURCE_ACCESS_DENIED, errorCode={}", e.getErrorCode());
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.error(e.getErrorCode()));
    }

    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleExternal(ExternalApiException e) {
        log.error(
                "event=EXTERNAL_API_EXCEPTION, targetService={}, errorCode={}",
                e.getTargetService(),
                e.getErrorCode(),
                e);
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.error(e.getErrorCode()));
    }

    @ExceptionHandler(DataAccessCustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccess(DataAccessCustomException e) {
        log.error("event=DATA_ACCESS_EXCEPTION, errorCode={}", e.getErrorCode(), e);
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.error(e.getErrorCode()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn(
                "event=BUSINESS_EXCEPTION, errorCode={}, message={}",
                e.getErrorCode(),
                e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.error(e.getErrorCode()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("event=UNHANDLED_EXCEPTION", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.COMMON_001));
    }
}
