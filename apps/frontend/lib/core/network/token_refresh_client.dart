import 'package:dio/dio.dart';

import 'env.dart';

/// `POST /api/auth/refresh` 전용 클라이언트. `ApiErrorInterceptor`가 걸린 메인 Dio 인스턴스를 그대로 쓰면
/// 이 호출 자체가 401을 받았을 때 인터셉터가 다시 재발급을 시도해 무한 루프에 빠질 수 있으므로,
/// 인터셉터가 붙지 않은 별도의 순수 Dio 인스턴스로 분리한다.
class TokenRefreshClient {
  TokenRefreshClient({Dio? dio})
    : _dio = dio ?? Dio(BaseOptions(baseUrl: Env.apiBaseUrl));

  final Dio _dio;

  /// 성공 시 (accessToken, refreshToken) 쌍을 반환한다. 실패(401 등)하면 [DioException]을 그대로 던진다 —
  /// 호출자(AuthErrorHandler)가 이를 "재발급 실패 → 로그아웃"으로 해석한다.
  Future<(String accessToken, String refreshToken)> refresh(
    String refreshToken,
  ) async {
    final response = await _dio.post<Map<String, dynamic>>(
      '/api/auth/refresh',
      data: {'refreshToken': refreshToken},
    );
    final data = response.data!['data'] as Map<String, dynamic>;
    return (data['accessToken'] as String, data['refreshToken'] as String);
  }
}
