package com.tracecare.backend.domain.location.service;

import java.math.BigDecimal;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.domain.location.repository.LocationHistoryWriter;

/**
 * {@code POST /api/care-target/share/location}(§4.3) 전용 — 이 엔드포인트는 응답에 {@code locationId}를 돌려줄 필요가
 * 없으므로(API_Specification.md §4.3에 Response 필드 자체가 없음) 진짜 fire-and-forget 비동기 저장이 가능하다. 반대로 {@code
 * POST /api/care-target/location}(§4.1)은 응답에 생성된 PK를 포함해야 해서 저장을 동기로 유지한다(LocationService 참고) —
 * System_Overview.md 시퀀스 다이어그램의 "비동기" 표기를 이 프로젝트 최초의 {@code @Async} 도입 지점으로 이 엔드포인트에 한해 그대로 적용했다.
 *
 * <p>별도 클래스로 분리한 이유: {@code @Async}는 Spring AOP 프록시를 거쳐야 동작하므로, 같은 클래스 안에서
 * self-invocation(this.method())으로 호출하면 무시된다.
 */
@Service
public class LocationHistoryAsyncWriter {

    private static final Logger log = LoggerFactory.getLogger(LocationHistoryAsyncWriter.class);

    private final LocationHistoryWriter locationHistoryWriter;

    public LocationHistoryAsyncWriter(LocationHistoryWriter locationHistoryWriter) {
        this.locationHistoryWriter = locationHistoryWriter;
    }

    @Async("locationTaskExecutor")
    @Transactional
    public void persist(
            Long userId, BigDecimal latitude, BigDecimal longitude, Instant recordedAt) {
        try {
            locationHistoryWriter.insert(userId, latitude, longitude, recordedAt);
        } catch (RuntimeException e) {
            // 이미 클라이언트에 응답이 나간 뒤라 이 실패를 알릴 방법이 없다 — 서버 로그에만 남긴다(재시도는 이번 범위 밖).
            log.error("event=LOCATION_ASYNC_SAVE_FAILED, userId={}", userId, e);
        }
    }
}
