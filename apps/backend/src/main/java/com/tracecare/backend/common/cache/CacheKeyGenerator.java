package com.tracecare.backend.common.cache;

import org.springframework.stereotype.Component;

/**
 * Cache_Strategy_Guide.md §3.1 {도메인}:{용도}:{식별자} 규칙을 강제하는 공용 유틸. Service 코드에서 캐시 키 문자열을 직접 조립하지 않고 이
 * 클래스를 거친다.
 */
@Component
public class CacheKeyGenerator {

    public String locationLatest(String careTargetId) {
        return CacheKeys.LOCATION_LATEST + careTargetId;
    }

    /** Cache_Strategy_Guide.md §3.2(2026-08 수정) — Place 목록 조회가 CareTarget 기준이므로 targetId를 쓴다. */
    public String placeList(String targetId) {
        return CacheKeys.PLACE_LIST + targetId;
    }

    public String userInfo(String userId) {
        return CacheKeys.USER_INFO + userId;
    }

    public String prediction(String careTargetId, String date) {
        return CacheKeys.PREDICTION + careTargetId + ":" + date;
    }

    public String chatCache(String questionHash) {
        return CacheKeys.CHAT_CACHE + questionHash;
    }

    public String placesExternal(String placeQueryHash) {
        return CacheKeys.PLACES_EXTERNAL + placeQueryHash;
    }

    public String geofence(String careTargetId) {
        return CacheKeys.GEOFENCE + careTargetId;
    }

    public String fcmToken(String userId) {
        return CacheKeys.FCM_TOKEN + userId;
    }

    public String refresh(String userId) {
        return CacheKeys.REFRESH + userId;
    }

    public String blacklist(String jti) {
        return CacheKeys.BLACKLIST + jti;
    }

    public String inviteToken(String token) {
        return CacheKeys.INVITE_TOKEN + token;
    }

    public String invitePending(String careTargetId) {
        return CacheKeys.INVITE_PENDING + careTargetId;
    }

    public String inviteCount(String careTargetId) {
        return CacheKeys.INVITE_COUNT + careTargetId;
    }

    public String inviteFail(String token) {
        return CacheKeys.INVITE_FAIL + token;
    }
}
