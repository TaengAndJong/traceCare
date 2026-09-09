import '../../../core/network/token_refresh_client.dart';
import '../../../core/storage/secure_token_storage.dart';
import 'auth_api.dart';

/// `PUT /api/auth/role` 확정 직후 토큰의 role 클레임 문제(2026-09 확인)에 대한 처리를 포함한다:
/// Backend `JwtAuthenticationFilter`는 Access Token의 `role` 클레임만으로 인가를 판단하고 DB를 다시
/// 조회하지 않으므로, Role을 막 확정한 직후에도 기존 토큰은 여전히 이전 role(대개 `null`)을 담고 있다.
/// 반면 `TokenService.reissue()`(`/api/auth/refresh`)는 재발급 시점에 DB의 최신 role을 다시 읽어 토큰에
/// 반영하도록 이미 구현돼 있으므로, Role 확정 성공 직후 이 refresh를 한 번 더 호출해 확정된 role이 담긴
/// 새 토큰을 즉시 받아둔다(Backend 수정 없이 Frontend에서 한 단계만 추가 — 사용자 확인 완료 사항).
class AuthRepository {
  AuthRepository({
    required AuthApi authApi,
    required SecureTokenStorage tokenStorage,
    required TokenRefreshClient refreshClient,
  }) : _authApi = authApi,
       _tokenStorage = tokenStorage,
       _refreshClient = refreshClient;

  final AuthApi _authApi;
  final SecureTokenStorage _tokenStorage;
  final TokenRefreshClient _refreshClient;

  Future<LoginResult> loginWithGoogle({
    required String idToken,
    String? fcmToken,
  }) async {
    final result = await _authApi.login(idToken: idToken, fcmToken: fcmToken);
    await _tokenStorage.saveTokens(
      accessToken: result.accessToken,
      refreshToken: result.refreshToken,
    );
    return result;
  }

  /// 이번 Phase는 CareTarget Role 선택만 지원한다(§5 판단 — 결과 보고 참고).
  Future<void> confirmCareTargetRole({
    required String name,
    required String birthDate,
  }) async {
    await _authApi.confirmRole(
      role: 'CARE_TARGET',
      name: name,
      birthDate: birthDate,
    );
    await _refreshTokenWithConfirmedRole();
  }

  Future<void> _refreshTokenWithConfirmedRole() async {
    final refreshToken = await _tokenStorage.readRefreshToken();
    if (refreshToken == null || refreshToken.isEmpty) {
      throw StateError('Role 확정 직후 저장된 Refresh Token이 없습니다.');
    }
    final (accessToken, newRefreshToken) = await _refreshClient.refresh(
      refreshToken,
    );
    await _tokenStorage.saveTokens(
      accessToken: accessToken,
      refreshToken: newRefreshToken,
    );
  }

  Future<bool> hasStoredSession() => _tokenStorage.hasTokens();

  Future<void> clearSession() => _tokenStorage.clear();
}
