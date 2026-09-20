package com.tracecare.backend.common.exception;

import java.util.List;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

    /**
     * 필수 쿼리 파라미터 누락/타입 불일치(잘못된 UUID·날짜 형식 등)는 클라이언트 입력 오류이므로 COMMON_002(400)로 응답한다. 이 핸들러가
     * 없으면 {@code Exception} 핸들러로 떨어져 500(COMMON_001)이 나간다. 파라미터 이름만 남기고 입력 값 원문은 로그에 남기지 않는다.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException e) {
        log.warn("event=VALIDATION_FAILED, fields={}", List.of(e.getParameterName()));
        return ResponseEntity.status(ErrorCode.COMMON_002.getHttpStatus())
                .body(
                        ErrorResponse.of(
                                ErrorCode.COMMON_002,
                                List.of(
                                        ErrorResponse.FieldErrorDetail.builder()
                                                .field(e.getParameterName())
                                                .reason("필수 파라미터입니다")
                                                .build())));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("event=VALIDATION_FAILED, fields={}", List.of(e.getName()));
        return ResponseEntity.status(ErrorCode.COMMON_002.getHttpStatus())
                .body(
                        ErrorResponse.of(
                                ErrorCode.COMMON_002,
                                List.of(
                                        ErrorResponse.FieldErrorDetail.builder()
                                                .field(e.getName())
                                                .reason("형식이 올바르지 않습니다")
                                                .build())));
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
