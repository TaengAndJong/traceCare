package com.tracecare.backend.domain.notification.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tracecare.backend.common.response.ApiResponse;
import com.tracecare.backend.common.response.PageResponse;
import com.tracecare.backend.common.response.SuccessCode;
import com.tracecare.backend.common.security.SecurityUtils;
import com.tracecare.backend.domain.notification.dto.response.NotificationResponse;
import com.tracecare.backend.domain.notification.service.NotificationQueryService;

/**
 * API_Specification.md §4.6. CareTarget용 별도 "이력" 엔드포인트가 문서에 없어, 이 하나뿐인 조회는 전체 이력을 반환한다 ({@link
 * NotificationQueryService#getHistory} 재사용 — Javadoc 참고). Guardian과 달리 대상 CareTarget과의 관계 검증이 필요 없다
 * — 조회 대상이 항상 호출자 자신이 받은 알림이기 때문이다.
 */
@RestController
@RequestMapping("/api/care-target/notifications")
public class CareTargetNotificationController {

    private final NotificationQueryService notificationQueryService;

    public CareTargetNotificationController(NotificationQueryService notificationQueryService) {
        this.notificationQueryService = notificationQueryService;
    }

    @GetMapping
    public ApiResponse<PageResponse<NotificationResponse>> getNotifications(Pageable pageable) {
        return ApiResponse.success(
                SuccessCode.NOTI_001,
                PageResponse.of(
                        notificationQueryService.getHistory(
                                SecurityUtils.getCurrentUserId(), pageable)));
    }

    @PutMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        notificationQueryService.markRead(SecurityUtils.getCurrentUserId(), id);
        return ApiResponse.success(SuccessCode.NOTI_002);
    }
}
