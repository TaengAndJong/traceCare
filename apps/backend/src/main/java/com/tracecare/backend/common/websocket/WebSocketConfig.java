package com.tracecare.backend.common.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * API_Specification.md §5, Security_Guide.md §7.5 — 개인화 큐(`convertAndSendToUser`) 방식만 쓴다. 공용
 * Topic(`/topic/location/{id}`)은 등록하지 않는다(SUBSCRIBE 시점 소유권 검증 부담을 구조적으로 없애기 위함, §7.5.2).
 *
 * <p>이번 Phase 2에서는 `/ws/guardian/location`(Server → Guardian 개인화 큐 수신)만 등록한다.
 * `/ws/care-target/location`(CareTarget → Server GPS 실시간 송신 채널)은 REST {@code POST
 * /api/care-target/location}(Phase 1)과 별개의 새 수신 경로라 이번 세션 범위 밖으로 남겼다 (LocationController 참고).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    public WebSocketConfig(StompAuthChannelInterceptor stompAuthChannelInterceptor) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/guardian/location");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
