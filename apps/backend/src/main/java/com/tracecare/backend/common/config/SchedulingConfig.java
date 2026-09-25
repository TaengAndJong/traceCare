package com.tracecare.backend.common.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 이 프로젝트 최초의 {@code @Scheduled} 사용처({@code AnomalyScheduler}, DATABASE_DESIGN_GUIDE.md §15.1)를
 * 위한 활성화 설정. {@code @Scheduled} 메서드가 하나뿐이라(5분 주기 tick 1개) 별도 {@code TaskScheduler} 스레드풀을
 * 두지 않고 Spring Boot 기본값(단일 스레드 풀)을 그대로 쓴다 — {@link AsyncConfig}가 {@code @Async}용 스레드풀을 명시적으로
 * 등록한 것과 달리, 여기서는 동시에 실행될 스케줄 작업이 하나뿐이라 풀 크기를 키울 이유가 없다.
 *
 * <p>{@link #clock()}: {@code AnomalyScheduler}가 "지금 시각/오늘 날짜/시간대"를 {@code Instant.now()} 등으로 직접 읽지 않고
 * 주입받은 {@link Clock}으로 읽게 하기 위한 시스템 기본 시계 빈이다(테스트가 {@code Clock.fixed}로 시각을 고정해 자정 경계 등 실행
 * 시각에 좌우되지 않게 하려는 것, §4 후속). 다른 클래스의 {@code Instant.now()} 호출은 이번에 바꾸지 않았다(점진 전환 대상).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
