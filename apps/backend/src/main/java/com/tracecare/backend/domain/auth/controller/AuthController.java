package com.tracecare.backend.domain.auth.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tracecare.backend.common.response.ApiResponse;
import com.tracecare.backend.common.response.SuccessCode;
import com.tracecare.backend.common.security.SecurityUtils;
import com.tracecare.backend.domain.auth.dto.request.OAuthLoginRequest;
import com.tracecare.backend.domain.auth.dto.request.RefreshTokenRequest;
import com.tracecare.backend.domain.auth.dto.request.RoleConfirmRequest;
import com.tracecare.backend.domain.auth.dto.response.MeResponse;
import com.tracecare.backend.domain.auth.dto.response.OAuthLoginResponse;
import com.tracecare.backend.domain.auth.dto.response.RoleConfirmResponse;
import com.tracecare.backend.domain.auth.dto.response.TokenReissueResponse;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.service.OAuthLoginService;
import com.tracecare.backend.domain.auth.service.TokenService;
import com.tracecare.backend.domain.auth.service.UserService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final OAuthLoginService oAuthLoginService;
    private final TokenService tokenService;
    private final UserService userService;

    public AuthController(
            OAuthLoginService oAuthLoginService,
            TokenService tokenService,
            UserService userService) {
        this.oAuthLoginService = oAuthLoginService;
        this.tokenService = tokenService;
        this.userService = userService;
    }

    @PostMapping("/oauth/login")
    public ApiResponse<OAuthLoginResponse> login(@Valid @RequestBody OAuthLoginRequest request) {
        OAuthLoginService.LoginResult result =
                oAuthLoginService.login(request.getIdToken(), request.getFcmToken());
        return ApiResponse.success(SuccessCode.AUTH_001, OAuthLoginResponse.from(result));
    }

    @PutMapping("/role")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RoleConfirmResponse> confirmRole(
            @Valid @RequestBody RoleConfirmRequest request) {
        User user =
                userService.confirmRole(
                        SecurityUtils.getCurrentUserId(),
                        request.getRole(),
                        request.getName(),
                        request.getBirthDate());
        return ApiResponse.success(SuccessCode.USER_002, RoleConfirmResponse.from(user));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader) {
        String accessToken = resolveBearerToken(authorizationHeader);
        tokenService.logout(SecurityUtils.getCurrentUserId(), accessToken);
        return ApiResponse.success(SuccessCode.AUTH_003);
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenReissueResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request) {
        TokenService.TokenPair tokenPair = tokenService.reissue(request.getRefreshToken());
        return ApiResponse.success(SuccessCode.AUTH_002, TokenReissueResponse.from(tokenPair));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        User user = userService.getUser(SecurityUtils.getCurrentUserId());
        return ApiResponse.success(SuccessCode.USER_001, MeResponse.from(user));
    }

    private String resolveBearerToken(String authorizationHeader) {
        return authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7)
                : authorizationHeader;
    }
}
