package com.tracecare.backend.domain.guardian.dto.request;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link ValidNotificationSettingsKeys} 구현. 빠진 키마다 해당 필드명으로 위반을 등록한다. */
public class NotificationSettingsKeysValidator
        implements ConstraintValidator<ValidNotificationSettingsKeys, NotificationSettingsUpdateRequest> {

    private static final String MESSAGE = "필수 키입니다(승격을 끄려면 null을 명시하세요)";

    @Override
    public boolean isValid(
            NotificationSettingsUpdateRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        boolean valid = true;
        context.disableDefaultConstraintViolation();
        if (!request.isEscalateMinutesArrivalPresent()) {
            addViolation(context, "escalateMinutesArrival");
            valid = false;
        }
        if (!request.isEscalateMinutesStayPresent()) {
            addViolation(context, "escalateMinutesStay");
            valid = false;
        }
        return valid;
    }

    private static void addViolation(ConstraintValidatorContext context, String field) {
        context.buildConstraintViolationWithTemplate(MESSAGE)
                .addPropertyNode(field)
                .addConstraintViolation();
    }
}
