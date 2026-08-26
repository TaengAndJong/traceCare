package com.tracecare.backend.domain.chat.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tracecare.backend.common.response.ApiResponse;
import com.tracecare.backend.common.response.SuccessCode;
import com.tracecare.backend.common.security.SecurityUtils;
import com.tracecare.backend.domain.chat.dto.request.ChatRequest;
import com.tracecare.backend.domain.chat.dto.response.ChatResponse;
import com.tracecare.backend.domain.chat.service.AiChatService;

/** API_Specification.md §3.6. */
@RestController
@RequestMapping("/api/guardian/ai")
public class AiChatController {

    private final AiChatService aiChatService;

    public AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.success(
                SuccessCode.AI_001, aiChatService.chat(SecurityUtils.getCurrentUserId(), request));
    }
}
