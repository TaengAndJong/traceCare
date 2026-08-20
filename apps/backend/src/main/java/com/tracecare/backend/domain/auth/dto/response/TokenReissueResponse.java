package com.tracecare.backend.domain.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.auth.service.TokenService;

@Getter
@Builder
@AllArgsConstructor
public class TokenReissueResponse {

    private String accessToken;
    private String refreshToken;

    public static TokenReissueResponse from(TokenService.TokenPair tokenPair) {
        return TokenReissueResponse.builder()
                .accessToken(tokenPair.accessToken())
                .refreshToken(tokenPair.refreshToken())
                .build();
    }
}
