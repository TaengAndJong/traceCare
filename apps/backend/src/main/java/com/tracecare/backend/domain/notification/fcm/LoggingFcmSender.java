package com.tracecare.backend.domain.notification.fcm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

import com.tracecare.backend.common.cache.CacheKeyGenerator;

/**
 * 실제 Firebase 연동 전까지 쓰는 Stub({@link FcmSender} Javadoc 참고). 완전히 아무 일도 하지 않는 대신, 로그인 시 이미 저장해두는
 * {@code fcm:token:{userId}} 등록 여부(Cache_Strategy_Guide.md §3.2)는 실제로 확인한다 — 그래야 "FCM 토큰이 없는 기기"라는
 * 실패 케이스(NOTI_003)를 실제 Firebase 없이도 재현/테스트할 수 있다.
 */
@Component
public class LoggingFcmSender implements FcmSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingFcmSender.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheKeyGenerator cacheKeyGenerator;

    public LoggingFcmSender(
            RedisTemplate<String, Object> redisTemplate, CacheKeyGenerator cacheKeyGenerator) {
        this.redisTemplate = redisTemplate;
        this.cacheKeyGenerator = cacheKeyGenerator;
    }

    @Override
    public boolean send(Long guardianId, String title, String body) {
        if (!hasRegisteredToken(guardianId)) {
            log.warn("event=FCM_SEND_SKIPPED_NO_TOKEN, guardianId={}", guardianId);
            return false;
        }
        log.info("event=FCM_SEND_STUB, guardianId={}, title={}", guardianId, title);
        return true;
    }

    private boolean hasRegisteredToken(Long guardianId) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.hasKey(cacheKeyGenerator.fcmToken(String.valueOf(guardianId))));
        } catch (DataAccessException | SerializationException e) {
            log.warn("event=FCM_TOKEN_LOOKUP_FAILED, guardianId={}", guardianId, e);
            return false;
        }
    }
}
