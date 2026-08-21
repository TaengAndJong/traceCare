package com.tracecare.backend.domain.guardian.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.auth.AccessDeniedCustomException;
import com.tracecare.backend.common.exception.business.CareTargetCapacityExceededException;
import com.tracecare.backend.common.exception.business.CareTargetNotFoundException;
import com.tracecare.backend.common.exception.business.GuardianCapacityExceededException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.dto.response.CareTargetResponse;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;

/**
 * Guardian↔CareTarget 관계(GuardianTarget) 자체의 조회/수정/해제와, 초대 승인 시점의 관계 생성(정원 검증 + PRIMARY/SUB 배정)을
 * 책임진다. 초대 토큰(Redis) 자체의 발급/조회/소각은 domain.caretarget.service.GuardianInviteService가 담당하고, 실제 DB 행
 * 생성만 이 클래스({@link #createRelation})에 위임한다(Coding_Convention.md 책임 분리 원칙).
 */
@Service
public class GuardianTargetService {

    /** CareTarget 1명당 ACTIVE Guardian 최대 인원(DATABASE_DESIGN_GUIDE.md §3.2 확정 사항). */
    private static final int MAX_ACTIVE_GUARDIANS = 3;

    private final GuardianTargetRepository guardianTargetRepository;
    private final UserRepository userRepository;
    private final int careTargetLimit;

    public GuardianTargetService(
            GuardianTargetRepository guardianTargetRepository,
            UserRepository userRepository,
            @Value("${guardian.care-target-limit}") int careTargetLimit) {
        this.guardianTargetRepository = guardianTargetRepository;
        this.userRepository = userRepository;
        this.careTargetLimit = careTargetLimit;
    }

    @Transactional(readOnly = true)
    public Page<CareTargetResponse> getCareTargets(Long guardianId, Pageable pageable) {
        Page<GuardianTarget> page =
                guardianTargetRepository.findByGuardianIdAndStatus(
                        guardianId, GuardianTarget.STATUS_ACTIVE, pageable);
        Map<Long, User> targetsById =
                userRepository
                        .findAllById(
                                page.getContent().stream()
                                        .map(GuardianTarget::getTargetId)
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
        return page.map(gt -> CareTargetResponse.of(gt, targetsById.get(gt.getTargetId())));
    }

    @Transactional(readOnly = true)
    public CareTargetResponse getCareTarget(Long guardianId, UUID targetPublicId) {
        User target = findTargetByPublicId(targetPublicId);
        GuardianTarget guardianTarget = findActiveRelation(guardianId, target.getId());
        return CareTargetResponse.of(guardianTarget, target);
    }

    @Transactional
    public CareTargetResponse updateRelation(
            Long guardianId, UUID targetPublicId, String relation, String alias) {
        User target = findTargetByPublicId(targetPublicId);
        GuardianTarget guardianTarget = findActiveRelation(guardianId, target.getId());
        guardianTarget.updateRelation(relation, alias);
        return CareTargetResponse.of(guardianTarget, target);
    }

    /** DELETE /api/guardian/care-targets/{id} — PRIMARY 탈퇴 시 하이브리드 승계(자동 승격) 정책 적용. */
    @Transactional
    public void terminateRelation(Long guardianId, UUID targetPublicId) {
        User target = findTargetByPublicId(targetPublicId);
        GuardianTarget guardianTarget = findActiveRelation(guardianId, target.getId());

        boolean wasPrimary = guardianTarget.isPrimary();
        guardianTarget.terminate();

        if (wasPrimary) {
            List<GuardianTarget> subs =
                    guardianTargetRepository
                            .findByTargetIdAndStatusAndGuardianRoleOrderByCreatedAtAsc(
                                    target.getId(),
                                    GuardianTarget.STATUS_ACTIVE,
                                    GuardianTarget.ROLE_SUB);
            if (!subs.isEmpty()) {
                subs.get(0).promoteToPrimary();
            }
        }
    }

    /**
     * 초대 승인 시점에만 호출된다(GuardianInviteService). 대상 CareTarget 행을 SELECT...FOR UPDATE로 잠근 뒤 정원(3명)을
     * 검증하고, 해당 CareTarget에게 ACTIVE Guardian이 없으면 PRIMARY, 있으면 SUB로 배정한다.
     */
    @Transactional
    public GuardianTarget createRelation(Long guardianId, Long targetId) {
        userRepository.findByIdForUpdate(targetId).orElseThrow(CareTargetNotFoundException::new);

        long activeCount =
                guardianTargetRepository.countByTargetIdAndStatus(
                        targetId, GuardianTarget.STATUS_ACTIVE);
        if (activeCount >= MAX_ACTIVE_GUARDIANS) {
            throw new GuardianCapacityExceededException();
        }

        String role = activeCount == 0 ? GuardianTarget.ROLE_PRIMARY : GuardianTarget.ROLE_SUB;
        return guardianTargetRepository.save(
                GuardianTarget.createActive(guardianId, targetId, role));
    }

    /**
     * (Guardian) 코드 입력 시점에 호출된다(GuardianInviteService). DATABASE_DESIGN_GUIDE.md §13/§14 확정 소프트 상한
     * — GuardianTarget 정원(3명) 처리와 동일 원칙으로 ACTIVE 관계만 카운트한다(TERMINATED는 상한에서 제외). "소프트 상한"이지만 문서가
     * "어뷰징성 대량 등록 차단"을 명시하므로 애플리케이션에서 실제로 차단한다. 3명 정원과 달리 DB Partial Unique 제약이 없고 문서도 이 카운트에 대해
     * 비관적 락을 요구하지 않으므로(§7 동시성 절은 GuardianTarget 신규 등록의 CareTarget 측 정원에만 락을 요구), 이 메서드는 락 없이 카운트만
     * 검증한다 — 여러 코드를 동시에 입력하는 극단적인 경쟁 상황에서 상한을 일시적으로 소폭 넘길 수 있으나, 이 항목이 "소프트" 상한으로 문서화된 취지와 부합한다고
     * 판단했다.
     */
    @Transactional(readOnly = true)
    public void assertCareTargetCapacityAvailable(Long guardianId) {
        long activeCount =
                guardianTargetRepository.countByGuardianIdAndStatus(
                        guardianId, GuardianTarget.STATUS_ACTIVE);
        if (activeCount >= careTargetLimit) {
            throw new CareTargetCapacityExceededException();
        }
    }

    private User findTargetByPublicId(UUID targetPublicId) {
        return userRepository
                .findByPublicId(targetPublicId)
                .orElseThrow(CareTargetNotFoundException::new);
    }

    private GuardianTarget findActiveRelation(Long guardianId, Long targetId) {
        return guardianTargetRepository
                .findByGuardianIdAndTargetIdAndStatus(
                        guardianId, targetId, GuardianTarget.STATUS_ACTIVE)
                .orElseThrow(() -> new AccessDeniedCustomException(ErrorCode.TARGET_002));
    }
}
