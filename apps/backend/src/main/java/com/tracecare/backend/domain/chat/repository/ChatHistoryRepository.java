package com.tracecare.backend.domain.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tracecare.backend.domain.chat.entity.ChatHistory;

public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {}
