package com.tracecare.backend.common.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 이 프로젝트 최초의 {@code @Async} 사용처(domain.location)를 위한 공용 스레드풀. Spring 기본값(요청마다 새 스레드를 무제한 생성하는
 * {@code SimpleAsyncTaskExecutor})은 운영 환경에서 위험해 명시적으로 크기를 제한한 {@link ThreadPoolTaskExecutor}를 등록한다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "locationTaskExecutor")
    public Executor locationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("location-async-");
        executor.initialize();
        return executor;
    }
}
