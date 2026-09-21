package com.tracecare.backend.domain.guardian.dto.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * {@link NotificationSettingsUpdateRequest}의 {@code escalateMinutes*} 키가 요청 JSON에 <b>존재</b>하는지 검증한다(값이 {@code
 * null}인 것은 허용, 키 자체가 없는 것은 거부). 클래스 레벨 제약이지만 위반은 프로퍼티 노드로 붙여 {@code errors[].field}가 실제 필드명으로 나가게
 * 한다({@link NotificationSettingsKeysValidator}).
 */
@Documented
@Constraint(validatedBy = NotificationSettingsKeysValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidNotificationSettingsKeys {

    String message() default "필수 키입니다";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
