package com.tracecare.backend.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 이 프로젝트 최초의 {@code @Scheduled} 사용처({@code AnomalyScheduler}, DATABASE_DESIGN_GUIDE.md §15.1)를
 * 위한 활성화 설정. {@code @Scheduled} 메서드가 하나뿐이라(5분 주기 tick 1개) 별도 {@code TaskScheduler} 스레드풀을
 * 두지 않고 Spring Boot 기본값(단일 스레드 풀)을 그대로 쓴다 — {@link AsyncConfig}가 {@code @Async}용 스레드풀을 명시적으로
 * 등록한 것과 달리, 여기서는 동시에 실행될 스케줄 작업이 하나뿐이라 풀 크기를 키울 이유가 없다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
