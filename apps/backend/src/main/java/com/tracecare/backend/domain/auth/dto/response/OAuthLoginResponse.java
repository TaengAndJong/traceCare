package com.tracecare.backend.domain.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.auth.service.OAuthLoginService;

@Getter
@Builder
@AllArgsConstructor
public class OAuthLoginResponse {

    private String accessToken;
    private String refreshToken;
    private String role;
    private String userId;
    private boolean roleSelected;

    public static OAuthLoginResponse from(OAuthLoginService.LoginResult result) {
        return OAuthLoginResponse.builder()
                .accessToken(result.accessToken())
                .refreshToken(result.refreshToken())
                .role(result.role())
                .userId(result.userId().toString())
                .roleSelected(result.roleSelected())
                .build();
    }
}
