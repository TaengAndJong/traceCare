import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../storage/storage_providers.dart';
import 'api_error_interceptor.dart';
import 'auth_error_handler.dart';
import 'env.dart';
import 'token_refresh_client.dart';

final tokenRefreshClientProvider = Provider<TokenRefreshClient>((ref) {
  return TokenRefreshClient();
});

final authErrorHandlerProvider = Provider<AuthErrorHandler>((ref) {
  return AuthErrorHandler(
    tokenStorage: ref.watch(secureTokenStorageProvider),
    refreshClient: ref.watch(tokenRefreshClientProvider),
  );
});

/// 앱 전역에서 공유하는 단일 Dio 인스턴스. 매 요청마다 Secure Storage에서 Access Token을 읽어
/// `Authorization` 헤더로 붙이고, `ApiErrorInterceptor`로 공통 에러 처리를 적용한다(재정의 금지,
/// API_Response_Rule.md §6~7 그대로).
final dioProvider = Provider<Dio>((ref) {
  final dio = Dio(
    BaseOptions(
      baseUrl: Env.apiBaseUrl,
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 10),
    ),
  );

  final tokenStorage = ref.watch(secureTokenStorageProvider);

  dio.interceptors.add(
    InterceptorsWrapper(
      onRequest: (options, handler) async {
        final token = await tokenStorage.readAccessToken();
        if (token != null && token.isNotEmpty) {
          options.headers['Authorization'] = 'Bearer $token';
        }
        handler.next(options);
      },
    ),
  );

  dio.interceptors.add(
    ApiErrorInterceptor(
      dio: dio,
      authErrorHandler: ref.watch(authErrorHandlerProvider),
    ),
  );

  return dio;
});
