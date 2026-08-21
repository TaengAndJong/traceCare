package com.tracecare.backend.domain.auth.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tracecare.backend.domain.auth.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByOauthProviderAndOauthId(String oauthProvider, String oauthId);

    Optional<User> findByPublicId(UUID publicId);

    Optional<User> findByEmail(String email);

    /**
     * GuardianTarget 신규 등록(초대 승인) 시 정원(3명) 카운트 검증 전에 대상 CareTarget 행을 잠근다 (DATABASE_DESIGN_GUIDE.md
     * §7 "동시성 제어 관련 운영 참고사항").
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
