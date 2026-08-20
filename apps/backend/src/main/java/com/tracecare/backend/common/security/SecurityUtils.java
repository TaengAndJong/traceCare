package com.tracecare.backend.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.auth.AuthenticationFailedException;

/**
 * SecurityContext에서 현재 인증된 사용자의 내부 PK(userId)를 꺼내는 공용 유틸. Controller가 클라이언트 파라미터의 userId를 신뢰하지 않고 이
 * 값을 기준으로 조회하게 한다(IDOR 방지).
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    public static Long getCurrentUserId() {
        return getCurrentUserDetails().getUserId();
    }

    public static CustomUserDetails getCurrentUserDetails() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof CustomUserDetails userDetails)) {
            throw new AuthenticationFailedException(ErrorCode.AUTH_001);
        }
        return userDetails;
    }
}
