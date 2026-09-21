package com.tracecare.backend.common.exception;

import java.util.List;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.fasterxml.jackson.databind.JsonMappingException;

import com.tracecare.backend.common.exception.auth.AccessDeniedCustomException;
import com.tracecare.backend.common.exception.external.ExternalApiException;
import com.tracecare.backend.common.exception.infra.DataAccessCustomException;
import com.tracecare.backend.common.response.ApiResponse;
import com.tracecare.backend.common.response.ErrorResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 필드를 특정할 수 없는 본문 오류(JSON 문법 오류, 본문 없음)에서 {@code errors[].field}에 쓰는 이름. */
    private static final String BODY_FIELD = "body";

    /** 로그/응답에 남기는 필드 경로의 최대 길이(경로에 클라이언트가 만든 문자열이 섞이는 경우 방어). */
    private static final int MAX_FIELD_PATH_LENGTH = 100;

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

    /**
     * 요청 본문을 읽지 못한 경우(JSON 문법 오류, 숫자 자리에 문자열 등 타입 불일치, 날짜 형식 오류, 본문 자체가 없음)는 클라이언트 입력
     * 오류이므로 COMMON_002(400)로 응답한다. 이 핸들러가 없으면 {@code Exception} 핸들러로 떨어져 500(COMMON_001)이 나간다.
     *
     * <p>Bean Validation({@code @Valid}) 실패는 역직렬화가 끝난 뒤의 검증이라 {@link MethodArgumentNotValidException}으로 따로
     * 처리되며 이 핸들러와 겹치지 않는다.
     *
     * <p><b>입력 값 원문을 응답과 로그에 남기지 않는다.</b> Jackson 예외 메시지에는 거부된 값 원문(좌표, 이름 등)과 내부 클래스명이 들어갈 수
     * 있어 {@code e.getMessage()}는 쓰지 않고, 응답에는 필드 경로와 고정 문구만, 로그에는 필드 경로와 원인 예외 종류만 남긴다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        String fieldPath = unreadableFieldPath(e);
        String reason;
        if (fieldPath != null) {
            reason = "형식이 올바르지 않습니다";
        } else {
            fieldPath = BODY_FIELD;
            reason =
                    e.getCause() == null
                            ? "요청 본문이 필요합니다"
                            : "요청 본문이 올바른 JSON 형식이 아닙니다";
        }
        log.warn(
                "event=VALIDATION_FAILED, fields={}, cause={}",
                List.of(fieldPath),
                e.getCause() == null ? "EMPTY_BODY" : e.getCause().getClass().getSimpleName());
        return ResponseEntity.status(ErrorCode.COMMON_002.getHttpStatus())
                .body(
                        ErrorResponse.of(
                                ErrorCode.COMMON_002,
                                List.of(
                                        ErrorResponse.FieldErrorDetail.builder()
                                                .field(fieldPath)
                                                .reason(reason)
                                                .build())));
    }

    /**
     * Jackson이 어느 필드에서 실패했는지를 {@code items[1].value} 같은 경로로 만든다. 경로를 알 수 없으면(문법 오류, 본문 없음, 루트 타입
     * 불일치) {@code null}이다. 클래스명을 노출하는 {@code JsonMappingException#getPathReference()}는 쓰지 않는다.
     */
    private static String unreadableFieldPath(HttpMessageNotReadableException e) {
        if (!(e.getCause() instanceof JsonMappingException mapping) || mapping.getPath().isEmpty()) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : mapping.getPath()) {
            if (reference.getFieldName() != null) {
                if (path.length() > 0) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        if (path.length() == 0) {
            return null;
        }
        return path.length() > MAX_FIELD_PATH_LENGTH
                ? path.substring(0, MAX_FIELD_PATH_LENGTH)
                : path.toString();
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
