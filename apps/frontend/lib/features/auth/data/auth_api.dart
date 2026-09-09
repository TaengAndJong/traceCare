import 'package:dio/dio.dart';

import '../../../core/network/api_response.dart';

/// API_Specification.md §2 그대로 — 요청/응답 필드는 Backend `AuthController`/DTO와 1:1로 맞췄다.
class LoginResult {
  final String accessToken;
  final String refreshToken;
  final String? role;
  final String userId;
  final bool roleSelected;

  LoginResult({
    required this.accessToken,
    required this.refreshToken,
    required this.role,
    required this.userId,
    required this.roleSelected,
  });

  factory LoginResult.fromJson(Map<String, dynamic> json) => LoginResult(
    accessToken: json['accessToken'] as String,
    refreshToken: json['refreshToken'] as String,
    role: json['role'] as String?,
    userId: json['userId'] as String,
    roleSelected: json['roleSelected'] as bool,
  );
}

class RoleConfirmResult {
  final String userId;
  final String role;

  RoleConfirmResult({required this.userId, required this.role});

  factory RoleConfirmResult.fromJson(Map<String, dynamic> json) =>
      RoleConfirmResult(
        userId: json['userId'] as String,
        role: json['role'] as String,
      );
}

/// `POST /api/auth/oauth/login`, `PUT /api/auth/role` 호출부. 이번 Phase는 CareTarget만 다루지만
/// Backend API 자체는 두 Role을 모두 받으므로 `role` 파라미터를 그대로 통과시킨다.
class AuthApi {
  AuthApi(this._dio);

  final Dio _dio;

  Future<LoginResult> login({required String idToken, String? fcmToken}) async {
    final response = await _dio.post<Map<String, dynamic>>(
      '/api/auth/oauth/login',
      data: {'idToken': idToken, 'fcmToken': ?fcmToken},
    );
    final body = ApiResponse<LoginResult>.fromJson(
      response.data!,
      (json) => LoginResult.fromJson(json as Map<String, dynamic>),
    );
    return body.data!;
  }

  Future<RoleConfirmResult> confirmRole({
    required String role,
    required String name,
    required String birthDate, // yyyy-MM-dd
  }) async {
    final response = await _dio.put<Map<String, dynamic>>(
      '/api/auth/role',
      data: {'role': role, 'name': name, 'birthDate': birthDate},
    );
    final body = ApiResponse<RoleConfirmResult>.fromJson(
      response.data!,
      (json) => RoleConfirmResult.fromJson(json as Map<String, dynamic>),
    );
    return body.data!;
  }
}
