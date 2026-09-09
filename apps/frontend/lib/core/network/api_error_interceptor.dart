import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'app_error_handler.dart';
import 'auth_error_handler.dart';

/// API_Response_Rule.md §7.3을 그대로 구현한다. `success: false` 또는 4xx/5xx 응답을 가로채 공통 처리한다.
/// 화면 코드에서 개별적으로 status code를 분기하지 않는다.
class ApiErrorInterceptor extends Interceptor {
  ApiErrorInterceptor({
    required Dio dio,
    required AuthErrorHandler authErrorHandler,
  }) : _dio = dio,
       _authErrorHandler = authErrorHandler;

  static const _retriedFlag = 'apiErrorInterceptor.retried';

  final Dio _dio;
  final AuthErrorHandler _authErrorHandler;

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) async {
    final response = err.response;

    if (response == null) {
      // 네트워크 자체 실패(타임아웃, 서버 무응답)
      // TODO(temp-debug): 실기기 로그인 실패 원인 조사용 임시 로그 — 원인 파악 후 제거.
      debugPrint(
        '[ApiErrorInterceptor] network failure — url=${err.requestOptions.uri}, '
        'type=${err.type}, error=${err.error}, message=${err.message}',
      );
      AppErrorHandler.showNetworkError();
      return handler.next(err);
    }

    final body = response.data;
    final code =
        (body is Map<String, dynamic> ? body['code'] as String? : null) ??
        'COMMON_001';
    final message =
        (body is Map<String, dynamic> ? body['message'] as String? : null) ??
        '알 수 없는 오류가 발생했습니다';

    switch (response.statusCode) {
      case 401:
        await _handle401(err, handler, code);
        return;
      case 403:
        AppErrorHandler.showForbidden(message);
        break;
      case 404:
        AppErrorHandler.showToast(message);
        break;
      case 409:
        AppErrorHandler.showToast(message);
        break;
      default:
        AppErrorHandler.showToast(message); // 400, 500 등
    }
    handler.next(err);
  }

  Future<void> _handle401(
    DioException err,
    ErrorInterceptorHandler handler,
    String code,
  ) async {
    final alreadyRetried = err.requestOptions.extra[_retriedFlag] == true;
    if (alreadyRetried) {
      // 재발급 직후 재시도한 요청이 다시 401을 받으면 더 이상 시도하지 않고 그대로 실패 처리한다
      // (무한 루프 방지 — AuthErrorHandler 자체의 재발급 실패 로그아웃과는 별개의 안전장치).
      return handler.next(err);
    }

    final shouldRetry = await _authErrorHandler.handleUnauthorized(code);
    if (!shouldRetry) {
      return handler.next(err);
    }

    try {
      final retryOptions = err.requestOptions;
      retryOptions.extra[_retriedFlag] = true;
      final response = await _dio.fetch<dynamic>(retryOptions);
      return handler.resolve(response);
    } on DioException catch (retryError) {
      return handler.next(retryError);
    }
  }
}
