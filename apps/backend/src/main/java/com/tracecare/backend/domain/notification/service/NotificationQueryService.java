package com.tracecare.backend.domain.notification.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.common.exception.business.NotificationNotFoundException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.notification.dto.response.NotificationResponse;
import com.tracecare.backend.domain.notification.entity.NotificationHistory;
import com.tracecare.backend.domain.notification.repository.NotificationHistoryRepository;

/**
 * API_Specification.md §3.7(Guardian)/§4.6(CareTarget) — 알림 목록(안읽음)/이력(전체) 조회, 읽음 처리. {@code
 * NotificationHistory.user_id}는 수신자가 Guardian/CareTarget 둘 다 될 수 있어(§4.7), 이 Service는 어느 Role이
 * 호출하는지 구분하지 않고 인증된 본인의 {@code userId}만 받는다 — Guardian용/CareTarget용 두 Controller가 각자 {@code
 * SecurityUtils.getCurrentUserId()}만 넘겨 그대로 재사용한다("대상 CareTarget 조회"가 아니라 "내가 받은 알림 조회"라
 * Place/VisitHistory처럼 관계 검증이 필요 없다).
 *
 * <p>"목록"과 "이력"의 구분: {@code idx_nh_user_status(user_id, status) WHERE status<>'READ'}와 {@code
 * idx_nh_user_sent(user_id, sent_at DESC)} 두 인덱스가 공존하는 이유이기도 하다 — 목록은 안읽은 알림만(트리아지용), 이력은 전체(감사/과거
 * 조회용)로 확정했다(API_Specification.md §3.7에 명문화).
 */
@Service
public class NotificationQueryService {

    private final NotificationHistoryRepository notificationHistoryRepository;
    private final UserRepository userRepository;

    public NotificationQueryService(
            NotificationHistoryRepository notificationHistoryRepository,
            UserRepository userRepository) {
        this.notificationHistoryRepository = notificationHistoryRepository;
        this.userRepository = userRepository;
    }

    /** GET /api/guardian/notifications — 안읽은 알림만(idx_nh_user_status 재사용). */
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getUnread(Long userId, Pageable pageable) {
        return toResponsePage(
                notificationHistoryRepository.findByUserIdAndStatusNotOrderBySentAtDesc(
                        userId, NotificationHistory.STATUS_READ, pageable));
    }

    /**
     * GET /api/guardian/notifications/history — 전체 이력(idx_nh_user_sent 재사용). CareTarget 쪽(§4.6
     * {@code GET /api/care-target/notifications})은 별도 "이력" 엔드포인트가 문서에 없어, 하나뿐인 조회 엔드포인트를 이 메서드로 연결해
     * 전체를 반환한다(API_Specification.md §4.6에 명문화).
     */
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getHistory(Long userId, Pageable pageable) {
        return toResponsePage(
                notificationHistoryRepository.findByUserIdOrderBySentAtDesc(userId, pageable));
    }

    /**
     * PUT .../notifications/{id}/read — 본인 수신 알림만 처리(소유권 검증, Time-Series 데이터라 내부 PK 그대로 사용).
     * Guardian/CareTarget 공용.
     */
    @Transactional
    public void markRead(Long userId, Long notificationId) {
        NotificationHistory notification =
                notificationHistoryRepository
                        .findByIdAndUserId(notificationId, userId)
                        .orElseThrow(NotificationNotFoundException::new);
        notification.markRead();
    }

    private Page<NotificationResponse> toResponsePage(Page<NotificationHistory> page) {
        Map<Long, User> targetsById =
                userRepository
                        .findAllById(
                                page.getContent().stream()
                                        .map(NotificationHistory::getTargetId)
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
        List<NotificationResponse> content =
                page.getContent().stream()
                        .map(
                                notification ->
                                        NotificationResponse.of(
                                                notification,
                                                targetsById.get(notification.getTargetId())))
                        .toList();
        return new PageImpl<>(content, page.getPageable(), page.getTotalElements());
    }
}
