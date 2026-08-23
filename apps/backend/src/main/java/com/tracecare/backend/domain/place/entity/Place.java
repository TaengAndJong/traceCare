package com.tracecare.backend.domain.place.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * Guardian이 등록하는 GeoFence(안심구역) 기준 정보(DATABASE_DESIGN_GUIDE.md §3.3/§4.3). 등록·수정·삭제는 해당 CareTarget의
 * ACTIVE PRIMARY Guardian만 가능하다(Service 계층 검증). {@code target_id}는 이 장소가 속한 CareTarget(User) 참조로,
 * DB 설계 당시 누락되어 있던 컬럼을 보완한 것이다(2026-08).
 *
 * <p>Soft Delete는 {@link SQLRestriction}으로 강제한다 — 이 엔티티를 대상으로 하는 모든 JPA 파생/JPQL 조회에 {@code WHERE
 * deleted_at IS NULL}이 자동으로 붙으므로 Repository 쿼리 메서드마다 조건을 반복해서 넣지 않는다. {@code updated_at}은 DB
 * 트리거({@code trg_place_set_updated_at})가 갱신하므로 이 엔티티에서는 읽기 전용으로만 매핑한다.
 */
@Entity
@Table(name = "Place")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "address")
    private String address;

    @Column(name = "latitude", nullable = false)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false)
    private BigDecimal longitude;

    @Column(name = "radius", nullable = false)
    private Integer radius;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    private Place(
            Long userId,
            Long targetId,
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            Integer radius) {
        this.publicId = UUID.randomUUID();
        this.userId = userId;
        this.targetId = targetId;
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radius = radius;
        this.createdAt = Instant.now();
    }

    public static Place createActive(
            Long userId,
            Long targetId,
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            Integer radius) {
        return new Place(userId, targetId, name, address, latitude, longitude, radius);
    }

    public void update(
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            Integer radius) {
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radius = radius;
    }

    public void delete() {
        this.deletedAt = Instant.now();
    }
}
