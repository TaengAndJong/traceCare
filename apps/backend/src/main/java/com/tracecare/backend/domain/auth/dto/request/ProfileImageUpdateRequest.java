package com.tracecare.backend.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * API_Specification.md §3.8 {@code PUT /api/guardian/profile/image}. {@code profile_image}는 파일
 * 바이너리가 아니라 URL 문자열이다(DATABASE_DESIGN_GUIDE.md §4.1 "프로필 이미지 URL") — 이 프로젝트에 파일 업로드/스토리지(S3 등) 연동이
 * 아직 없어(build.gradle.kts/.env.example 확인 완료), 클라이언트가 별도 경로로 업로드해 얻은 URL을 여기 그대로 전달받아 저장하는 것이 기존에 이미
 * 확정된 설계다.
 */
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class ProfileImageUpdateRequest {

    @NotBlank private String imageUrl;
}
