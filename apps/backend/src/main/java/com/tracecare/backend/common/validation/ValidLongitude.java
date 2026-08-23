package com.tracecare.backend.common.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * GeoFence 중심 경도 값 검증(Exception_Handling_Rule.md §6.2). DB CHECK 제약(ck_place_lng, -180~180)과 동일한
 * 범위를 Controller 진입 시점에 1차로 방어한다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = LongitudeValidator.class)
public @interface ValidLongitude {

    String message() default "경도 값이 올바르지 않습니다 (-180 ~ 180)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
