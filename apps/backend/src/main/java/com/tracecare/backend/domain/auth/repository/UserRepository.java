package com.tracecare.backend.domain.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tracecare.backend.domain.auth.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByOauthProviderAndOauthId(String oauthProvider, String oauthId);

    Optional<User> findByPublicId(UUID publicId);

    Optional<User> findByEmail(String email);
}
