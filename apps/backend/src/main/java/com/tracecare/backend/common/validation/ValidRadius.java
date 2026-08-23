package com.tracecare.backend.common.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * GeoFence 반경(m) 값 검증(Exception_Handling_Rule.md §6.2). DB CHECK 제약(ck_place_radius, {@code radius
 * > 0})과 동일한 범위를 Controller 진입 시점에 1차로 방어한다 — 문서에 상한선 근거가 없어 하한(양수)만 검증한다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = RadiusValidator.class)
public @interface ValidRadius {

    String message() default "GeoFence 반경 값이 올바르지 않습니다 (양수만 허용)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
