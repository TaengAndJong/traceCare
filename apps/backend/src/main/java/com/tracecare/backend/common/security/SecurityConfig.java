package com.tracecare.backend.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.tracecare.backend.common.cache.CacheKeyGenerator;

/**
 * Security_Guide.md 2~4장 기준 SecurityFilterChain 구성. JWT 파싱/검증 로직은 JwtTokenProvider/
 * JwtAuthenticationFilter가 담당하고, 이 클래스는 Filter Chain 배치와 URL 권한 선언만 책임진다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtTokenProvider jwtTokenProvider,
            RedisTemplate<String, Object> redisTemplate,
            CacheKeyGenerator cacheKeyGenerator,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
            JwtAccessDeniedHandler jwtAccessDeniedHandler)
            throws Exception {
        JwtAuthenticationFilter jwtAuthenticationFilter =
                new JwtAuthenticationFilter(jwtTokenProvider, redisTemplate, cacheKeyGenerator);

        http.csrf(csrf -> csrf.disable())
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/api/auth/oauth/login", "/api/auth/refresh")
                                        .permitAll()
                                        // Security_Guide.md §7.5.1: 인증은 이 HTTP 핸드셰이크가 아니라
                                        // StompAuthChannelInterceptor가 STOMP CONNECT 프레임에서 수행한다.
                                        // 여기서 authenticated()로 막으면 CONNECT 프레임 자체가 도달하지
                                        // 못해 그 검증이 실행되지 않는다.
                                        .requestMatchers("/ws/**")
                                        .permitAll()
                                        .requestMatchers("/internal/**")
                                        .denyAll()
                                        .requestMatchers("/api/guardian/**")
                                        .hasRole("GUARDIAN")
                                        .requestMatchers("/api/care-target/**")
                                        .hasRole("CARE_TARGET")
                                        .requestMatchers("/api/admin/**")
                                        .hasRole("ADMIN")
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        exceptionHandling ->
                                exceptionHandling
                                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                                        .accessDeniedHandler(jwtAccessDeniedHandler))
                .addFilterBefore(
                        jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
