package com.tracecare.backend.domain.place.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.place.entity.Place;

/**
 * 이 Response DTO는 API 응답 직렬화뿐 아니라 {@code place:list:{targetId}} Redis 캐시 값으로도 그대로 저장된다(Cache
 * Aside). 캐시에서 다시 읽어올 때 Jackson이 역직렬화해야 하므로, Request DTO에 적용하던 것과 동일한 이유로 {@code @Jacksonized}가
 * 필요하다 — 순수 응답 전용이었다면 불필요했을 것이다.
 */
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class PlaceResponse {

    private String placeId;
    private String careTargetId;
    private String name;
    private String address;
    private Double latitude;
    private Double longitude;
    private Integer radius;

    public static PlaceResponse of(Place place, User target) {
        return PlaceResponse.builder()
                .placeId(place.getPublicId().toString())
                .careTargetId(target.getPublicId().toString())
                .name(place.getName())
                .address(place.getAddress())
                .latitude(place.getLatitude().doubleValue())
                .longitude(place.getLongitude().doubleValue())
                .radius(place.getRadius())
                .build();
    }
}
