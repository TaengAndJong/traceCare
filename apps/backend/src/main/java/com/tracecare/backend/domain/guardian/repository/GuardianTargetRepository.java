package com.tracecare.backend.domain.guardian.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
