package com.tracecare.backend.common.cache;

/**
 * Cache_Strategy_Guide.md §3.2 표에 등록된 캐시 키 prefix 상수 모음. 새 캐시 항목은 코드에 먼저 추가하지 않고 해당 문서 §3.2 표에 먼저
 * 등록한다.
 */
public final class CacheKeys {

    public static final String LOCATION_LATEST = "location:latest:";
    public static final String PLACE_LIST = "place:list:";
    public static final String USER_INFO = "user:info:";
    public static final String PREDICTION = "prediction:";
    public static final String CHAT_CACHE = "chat:cache:";
    public static final String PLACES_EXTERNAL = "places:external:";
    public static final String GEOFENCE = "geofence:";
    public static final String FCM_TOKEN = "fcm:token:";
    public static final String REFRESH = "refresh:";
    public static final String BLACKLIST = "blacklist:";
    public static final String INVITE_TOKEN = "invite:token:";
    public static final String INVITE_PENDING = "invite:pending:";
    public static final String INVITE_COUNT = "invite:count:";
    public static final String INVITE_FAIL = "invite:fail:";

    private CacheKeys() {}
}
