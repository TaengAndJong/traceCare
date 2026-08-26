package com.tracecare.backend.domain.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** API_Specification.md §3.6. */
@Getter
@Builder
@AllArgsConstructor
public class ChatResponse {

    private Long chatId;
    private String answer;
}
