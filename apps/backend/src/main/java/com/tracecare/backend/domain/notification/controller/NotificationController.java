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

/** API_Specification.md §3.7. */
@RestController
@RequestMapping("/api/guardian/notifications")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    public NotificationController(NotificationQueryService notificationQueryService) {
        this.notificationQueryService = notificationQueryService;
    }

    @GetMapping
    public ApiResponse<PageResponse<NotificationResponse>> getUnread(Pageable pageable) {
        return ApiResponse.success(
                SuccessCode.NOTI_001,
                PageResponse.of(
                        notificationQueryService.getUnread(
                                SecurityUtils.getCurrentUserId(), pageable)));
    }

    @GetMapping("/history")
    public ApiResponse<PageResponse<NotificationResponse>> getHistory(Pageable pageable) {
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
