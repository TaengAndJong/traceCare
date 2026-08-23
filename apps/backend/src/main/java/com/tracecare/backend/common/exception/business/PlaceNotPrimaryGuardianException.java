package com.tracecare.backend.common.exception.business;

import com.tracecare.backend.common.exception.BusinessException;
import com.tracecare.backend.common.exception.ErrorCode;

/**
 * Place 등록/수정/삭제를 ACTIVE PRIMARY가 아닌 Guardian이 시도할 때 던진다. {@code
 * domain.guardian.service.GuardianTargetService}의 {@code NotPrimaryGuardianException}과
 * ErrorCode(GUARDIAN_004)는 같지만, Exception_Handling_Rule.md §7.2("동일한 실패 사유라도 도메인이 다르면 예외 클래스를
 * 분리한다")에 따라 Place 도메인 전용 클래스를 별도로 둔다.
 */
public class PlaceNotPrimaryGuardianException extends BusinessException {

    public PlaceNotPrimaryGuardianException() {
        super(ErrorCode.GUARDIAN_004);
    }
}
