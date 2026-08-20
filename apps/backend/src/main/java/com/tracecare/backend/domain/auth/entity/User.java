package com.tracecare.backend.domain.auth.entity;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.business.DuplicateResourceException;

/**
 * User/Guardian/CareTarget/Admin 통합 테이블(DATABASE_DESIGN_GUIDE.md §3.1 Single Table, role 컬럼으로 구분).
 * name/role/birth_date는 최초 로그인 시점에는 미확정(null)일 수 있고 {@link #confirmRole}로 1회만 확정한다
 * (Security_Guide.md §6.7, API_Specification.md §2.2).
 */
@Entity
@Table(name = "User")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "oauth_provider", nullable = false, updatable = false)
    private String oauthProvider;

    @Column(name = "oauth_id", nullable = false, updatable = false)
    private String oauthId;

    @Column(name = "name")
    private String name;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "phone")
    private String phone;

    @Column(name = "role")
    private String role;

    @Column(name = "profile_image")
    private String profileImage;

    private User(String email, String oauthProvider, String oauthId) {
        this.publicId = UUID.randomUUID();
        this.email = email;
        this.oauthProvider = oauthProvider;
        this.oauthId = oauthId;
    }

    public static User createFromOAuth(String email, String oauthProvider, String oauthId) {
        return new User(email, oauthProvider, oauthId);
    }

    public boolean isRoleSelected() {
        return role != null;
    }

    /** Role은 최초 1회만 선택 가능하다(API_Specification.md §2.2) — 이미 확정된 경우 USER_004로 거부한다. */
    public void confirmRole(String role, String name, LocalDate birthDate) {
        if (isRoleSelected()) {
            throw new DuplicateResourceException(ErrorCode.USER_004);
        }
        this.role = role;
        this.name = name;
        this.birthDate = birthDate;
    }
}
