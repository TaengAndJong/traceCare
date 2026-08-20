package com.tracecare.backend.domain.auth.service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.Collections;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.http.javanet.DefaultConnectionFactory;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.auth.AuthenticationFailedException;

/**
 * Google ID Token 검증 전담(Security_Guide.md §6.1, §6.6). Google 공식 라이브러리의 GoogleIdTokenVerifier가
 * 서명(JWK)/발급자(iss)/대상(aud)/만료(exp)를 검증하고, 이 클래스는 추가로 email_verified까지 확인한다. Google 연동은 auth 도메인 고유
 * 로직이라 common이 아니라 여기 둔다.
 */
@Service
public class GoogleIdTokenVerifier {

    /**
     * Google 공개키(JWK) 조회용 connect/read timeout(ms). {@code GoogleIdTokenVerifier.Builder}/ {@code
     * GooglePublicKeysManager.Builder}는 요청 단위 초기화 훅(HttpRequestInitializer)을 받지 않아 timeout을 걸 수 없다
     * — 대신 {@code NetHttpTransport}에 커스텀 {@code ConnectionFactory}를 꽂아 전송 계층에서 모든 요청에 공통으로 적용한다.
     * googleapis.com은 고가용 인프라라 연결은 빠르게 실패시키고 (Security_Guide.md §11.4의 FastAPI 호출 connect 2초 기준을
     * 참고해 약간 여유를 둠), 응답 자체는 작은 JSON(공개키 목록)이라 5초면 충분하다고 판단했다.
     */
    private static final int CONNECT_TIMEOUT_MILLIS = 3000;

    private static final int READ_TIMEOUT_MILLIS = 5000;

    private final com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier delegate;

    public GoogleIdTokenVerifier(@Value("${google.client-id}") String clientId) {
        NetHttpTransport transport =
                new NetHttpTransport.Builder()
                        .setConnectionFactory(new TimeoutConnectionFactory())
                        .build();
        this.delegate =
                new com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier.Builder(
                                transport, GsonFactory.getDefaultInstance())
                        .setAudience(Collections.singletonList(clientId))
                        .build();
    }

    /** 서명/발급자/대상/만료/email_verified 검증 후 Payload를 반환한다. 실패 시 AUTH_005로 변환해서 던진다. */
    public GoogleIdToken.Payload verify(String idTokenString) {
        GoogleIdToken idToken;
        try {
            idToken = delegate.verify(idTokenString);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new AuthenticationFailedException(ErrorCode.AUTH_005);
        }
        if (idToken == null) {
            throw new AuthenticationFailedException(ErrorCode.AUTH_005);
        }

        GoogleIdToken.Payload payload = idToken.getPayload();
        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new AuthenticationFailedException(ErrorCode.AUTH_005);
        }
        return payload;
    }

    /** DefaultConnectionFactory에 connect/read timeout만 얹는 얇은 래퍼. */
    private static final class TimeoutConnectionFactory extends DefaultConnectionFactory {

        @Override
        public HttpURLConnection openConnection(URL url) throws IOException {
            HttpURLConnection connection = super.openConnection(url);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            return connection;
        }
    }
}
