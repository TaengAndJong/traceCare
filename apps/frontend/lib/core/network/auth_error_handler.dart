import 'package:dio/dio.dart';

import '../storage/secure_token_storage.dart';
import 'app_error_handler.dart';
import 'navigation_service.dart';
import 'token_refresh_client.dart';

/// API_Response_Rule.md §7.5 Token 만료 처리를 그대로 구현한다.
///
/// - `AUTH_002`(Access Token 만료): Refresh Token으로 재발급을 1회 시도한다.
/// - 재발급 API 자체가 실패(401 등)하거나 `code`가 `AUTH_002`가 아닌 다른 401(예: `AUTH_004`, `AUTH_003`,
///   `AUTH_006`)이면 그 자리에서 즉시 로그아웃한다 — 여기서 다시 재시도를 걸지 않아 무한 루프를 만들지 않는다.
/// - 동시에 여러 API가 401을 받는 상황(화면 진입 시 다중 호출)을 고려해, 진행 중인 재발급이 있으면 새로
///   시작하지 않고 그 결과를 함께 기다린다(Lock).
class AuthErrorHandler {
  AuthErrorHandler({
    required SecureTokenStorage tokenStorage,
    required TokenRefreshClient refreshClient,
  }) : _tokenStorage = tokenStorage,
       _refreshClient = refreshClient;

  final SecureTokenStorage _tokenStorage;
  final TokenRefreshClient _refreshClient;

  Future<bool>? _inFlightRefresh;

  /// 재발급에 성공해 원래 요청을 재시도해도 되면 `true`, 그렇지 않으면(로그아웃 처리됨) `false`를 반환한다.
  Future<bool> handleUnauthorized(String code) async {
    if (code == 'AUTH_002') {
      final refreshed = await _refreshAccessTokenOnce();
      if (refreshed) {
        return true;
      }
    }
    await _forceLogout();
    return false;
  }

  Future<bool> _refreshAccessTokenOnce() {
    return _inFlightRefresh ??= _doRefresh().whenComplete(() {
      _inFlightRefresh = null;
    });
  }

  Future<bool> _doRefresh() async {
    final refreshToken = await _tokenStorage.readRefreshToken();
    if (refreshToken == null || refreshToken.isEmpty) {
      return false;
    }
    try {
      final (accessToken, newRefreshToken) = await _refreshClient.refresh(
        refreshToken,
      );
      await _tokenStorage.saveTokens(
        accessToken: accessToken,
        refreshToken: newRefreshToken,
      );
      return true;
    } on DioException {
      return false;
    }
  }

  Future<void> _forceLogout() async {
    await _tokenStorage.clear();
    NavigationService.goToLoginAndClearStack();
    AppErrorHandler.showToast('로그인이 만료되었습니다. 다시 로그인해주세요.');
  }
}
