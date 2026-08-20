package com.tracecare.backend.domain.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.auth.entity.User;

@Getter
@Builder
@AllArgsConstructor
public class RoleConfirmResponse {

    private String userId;
    private String role;

    public static RoleConfirmResponse from(User user) {
        return RoleConfirmResponse.builder()
                .userId(user.getPublicId().toString())
                .role(user.getRole())
                .build();
    }
}
