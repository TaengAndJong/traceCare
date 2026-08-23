package com.tracecare.backend.domain.guardian.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tracecare.backend.domain.guardian.entity.GuardianTarget;

public interface GuardianTargetRepository extends JpaRepository<GuardianTarget, Long> {

    Page<GuardianTarget> findByGuardianIdAndStatus(
            Long guardianId, String status, Pageable pageable);

    Optional<GuardianTarget> findByGuardianIdAndTargetIdAndStatus(
            Long guardianId, Long targetId, String status);

    long countByTargetIdAndStatus(Long targetId, String status);

    long countByGuardianIdAndStatus(Long guardianId, String status);

    List<GuardianTarget> findByTargetIdAndStatusAndGuardianRoleOrderByCreatedAtAsc(
            Long targetId, String status, String guardianRole);

    /** PRIMARY/SUB 구분 없이 해당 CareTarget의 ACTIVE Guardian 전원 — WebSocket 실시간 위치 발행 대상 조회용. */
    List<GuardianTarget> findByTargetIdAndStatus(Long targetId, String status);

    /**
     * PRIMARY 위임(DATABASE_DESIGN_GUIDE.md §7)처럼 특정 관계 행을 잠근 채로 조회해야 하는 트랜잭션에서 사용한다. {@code
     * UserRepository.findByIdForUpdate}와 동일한 패턴.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT gt FROM GuardianTarget gt "
                    + "WHERE gt.guardianId = :guardianId AND gt.targetId = :targetId AND gt.status = 'ACTIVE'")
    Optional<GuardianTarget> findActiveByGuardianIdAndTargetIdForUpdate(
            @Param("guardianId") Long guardianId, @Param("targetId") Long targetId);
}
