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
     * {@code AnomalyScheduler} 역할3(PAUSED 자동 복귀) — 현재 PAUSED인 행만 스캔한다(전체 사용자 규모와 무관하게
     * "현재 일시정지 중"인 부분집합만 대상이라 작게 유지됨, §4.2 확정: D).
     */
    List<GuardianTarget> findByNotificationMode(String notificationMode);

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

    /**
     * {@code AnomalyScheduler}의 PAUSED 자동 복귀처럼 (guardian, target)이 아니라 행 id만 아는 경로에서 행을 잠근 채 상태를
     * <b>다시 확인</b>하기 위한 조회다(DATABASE_DESIGN_GUIDE.md §15.8). 사용자 쓰기 경로와 같은 행 잠금을 잡으므로 두 경로가 서로 순서대로
     * 처리된다. 관계 상태(ACTIVE/TERMINATED)는 거르지 않는다 — 해제된 행의 정지도 기존과 똑같이 복귀시킨다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT gt FROM GuardianTarget gt WHERE gt.id = :id")
    Optional<GuardianTarget> findByIdForUpdate(@Param("id") Long id);
}
