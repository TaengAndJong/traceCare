package com.tracecare.backend.domain.guardian.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Guardian-CareTarget 관계 해소 테이블(DATABASE_DESIGN_GUIDE.md §3.2). 행 생성은 초대(Invitation)+CareTarget 승인
 * 절차로만 이뤄지므로({@link #createActive}), 이 클래스에는 의도적으로 "직접 INSERT용" public 생성자를 두지 않는다.
 */
@Entity
@Table(name = "GuardianTarget")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuardianTarget {

    public static final String ROLE_PRIMARY = "PRIMARY";
    public static final String ROLE_SUB = "SUB";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_TERMINATED = "TERMINATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "guardian_id", nullable = false, updatable = false)
    private Long guardianId;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Column(name = "guardian_role", nullable = false)
    private String guardianRole;

    @Column(name = "relation")
    private String relation;

    @Column(name = "alias")
    private String alias;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "terminated_at")
    private Instant terminatedAt;

    private GuardianTarget(Long guardianId, Long targetId, String guardianRole) {
        this.guardianId = guardianId;
        this.targetId = targetId;
        this.guardianRole = guardianRole;
        this.status = STATUS_ACTIVE;
        this.createdAt = Instant.now();
    }

    /** CareTarget 승인 트랜잭션(GuardianTargetService)에서만 호출된다 — 정원/PRIMARY 검증은 호출부 책임. */
    public static GuardianTarget createActive(Long guardianId, Long targetId, String guardianRole) {
        return new GuardianTarget(guardianId, targetId, guardianRole);
    }

    public boolean isPrimary() {
        return ROLE_PRIMARY.equals(guardianRole);
    }

    public void updateRelation(String relation, String alias) {
        this.relation = relation;
        this.alias = alias;
    }

    public void promoteToPrimary() {
        this.guardianRole = ROLE_PRIMARY;
    }

    public void demoteToSub() {
        this.guardianRole = ROLE_SUB;
    }

    public void terminate() {
        this.status = STATUS_TERMINATED;
        this.terminatedAt = Instant.now();
    }
}
