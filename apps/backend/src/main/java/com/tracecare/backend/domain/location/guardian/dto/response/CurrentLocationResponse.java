package com.tracecare.backend.domain.location.guardian.dto.response;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import com.tracecare.backend.domain.auth.entity.User;

/**
 * API_Specification.md §3.3(Guardian의 현재 위치 조회) 응답 형식. CareTarget의 자기 위치 조회(§4.1, 문서에 정확한 응답 스펙이
 * 없음)도 동일한 형식을 그대로 재사용한다(domain.location.caretarget.service.LocationService 참고) — 같은 데이터(위치 1건 + 조회
 * 출처)를 나타내므로 응답 DTO를 분리할 이유가 없다고 판단했다.
 */
@Getter
@Builder
@AllArgsConstructor
public class CurrentLocationResponse {

    public static final String SOURCE_REDIS_CACHE = "REDIS_CACHE";
    public static final String SOURCE_DB = "DB";

    private String careTargetId;
    private Double latitude;
    private Double longitude;
    private Instant recordedAt;
    private String source;

    public static CurrentLocationResponse of(
            User target, Double latitude, Double longitude, Instant recordedAt, String source) {
        return CurrentLocationResponse.builder()
                .careTargetId(target.getPublicId().toString())
                .latitude(latitude)
                .longitude(longitude)
                .recordedAt(recordedAt)
                .source(source)
                .build();
    }
}
