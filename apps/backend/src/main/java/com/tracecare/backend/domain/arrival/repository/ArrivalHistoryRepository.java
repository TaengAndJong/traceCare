package com.tracecare.backend.domain.arrival.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.tracecare.backend.domain.arrival.entity.ArrivalHistory;

public interface ArrivalHistoryRepository extends JpaRepository<ArrivalHistory, Long> {

    Page<ArrivalHistory> findByUserIdOrderByConfirmedAtDesc(Long userId, Pageable pageable);
}
