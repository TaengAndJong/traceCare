package com.tracecare.backend.domain.location.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * {@link LocationHistory}의 복합 PK(id, recorded_at)를 위한 {@code @IdClass} (DATABASE_DESIGN_GUIDE.md
 * §5.1 — 월 단위 Range Partitioning은 파티션 키 컬럼이 PK에 포함되어야 하는 PostgreSQL 제약 때문에 단일 컬럼 PK를 쓸 수 없다). 필드명이
 * 엔티티의 {@code @Id} 필드명(id, recordedAt)과 정확히 일치해야 한다. 이 프로젝트에 복합 PK 엔티티가 이번이 처음이라
 * {@code @EmbeddedId}보다 필드 접근이 단순한 {@code @IdClass}를 선택했다.
 */
public class LocationHistoryId implements Serializable {

    private Long id;
    private Instant recordedAt;

    public LocationHistoryId() {}

    public LocationHistoryId(Long id, Instant recordedAt) {
        this.id = id;
        this.recordedAt = recordedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LocationHistoryId that)) {
            return false;
        }
        return Objects.equals(id, that.id) && Objects.equals(recordedAt, that.recordedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, recordedAt);
    }
}
