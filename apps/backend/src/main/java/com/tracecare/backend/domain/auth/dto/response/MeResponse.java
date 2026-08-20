package com.tracecare.backend.domain.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.auth.entity.User;

@Getter
@Builder
@AllArgsConstructor
public class MeResponse {

    private String userId;
    private String role;
    private String name;
    private String phone;
    private String profileImage;

    public static MeResponse from(User user) {
        return MeResponse.builder()
                .userId(user.getPublicId().toString())
                .role(user.getRole())
                .name(user.getName())
                .phone(user.getPhone())
                .profileImage(user.getProfileImage())
                .build();
    }
}
