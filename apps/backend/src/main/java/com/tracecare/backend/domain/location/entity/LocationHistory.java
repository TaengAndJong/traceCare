package com.tracecare.backend.domain.location.entity;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * CareTarget GPS 원본 위치(DATABASE_DESIGN_GUIDE.md §3.4/§4.4). 월 단위 Range Partitioning(recorded_at
 * 기준)이 걸려 있어 PK가 (id, recorded_at) 복합키다({@link LocationHistoryId}). 시계열 이력 데이터라 Place/User와 달리
 * {@code public_id}를 두지 않는다(`.claude/rules/api.md` — 대용량 이력 데이터는 내부 PK 노출 허용).
 *
 * <p>이 테이블에 대한 모든 조회는 반드시 {@code user_id}(+ {@code recorded_at} 범위)를 함께 조건으로 걸어야 한다 — {@code id} 단독
 * 조회는 파티션 프루닝이 되지 않아 전체 파티션을 스캔한다(§5.1 구현 체크리스트). 이 원칙을 코드로 강제하기 위해 {@link
 * LocationHistoryRepository}에 {@code findById}류 단독 조회 메서드를 노출하지 않는다.
 *
 * <p>이 엔티티는 <b>조회 전용</b>이다 — Hibernate 6은 IDENTITY 생성 전략과 복합 PK 조합을 지원하지 않아("Identity generation
 * isn't supported for composite ids") {@code repository.save()}로 새 행을 만들 수 없다. 신규 저장은 {@link
 * LocationHistoryWriter}가 JDBC로 직접 {@code INSERT ... RETURNING id}를 수행한다.
 */
@Entity
@Table(name = "LocationHistory")
@IdClass(LocationHistoryId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LocationHistory {

    @Id
    @Column(name = "id", updatable = false)
    private Long id;

    @Id
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "latitude", nullable = false, updatable = false)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, updatable = false)
    private BigDecimal longitude;
}
