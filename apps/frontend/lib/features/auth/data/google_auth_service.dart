import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:google_sign_in/google_sign_in.dart';

/// Google OAuth2 로그인(1차 인증) 전담. Backend가 자체 JWT를 발급하는 데 필요한 Google ID Token만
/// 뽑아 넘겨준다 — 이 ID Token 자체를 API 인증에 재사용하지 않는다(Security_Guide.md, `.claude/rules/security.md` §1).
///
/// `serverClientId`는 Backend `GoogleIdTokenVerifier`가 `aud` 클레임 검증에 쓰는 Web OAuth Client ID와
/// 반드시 동일해야 한다(`google.client-id` 설정값, Google Cloud Console에서 발급) — Android OAuth Client ID와는
/// 다른 값이니 혼동하지 않는다.
class GoogleAuthService {
  GoogleAuthService({required String serverClientId})
    : _serverClientId = serverClientId;

  final String _serverClientId;
  bool _initialized = false;

  Future<void> _ensureInitialized() async {
    if (_initialized) return;
    await GoogleSignIn.instance.initialize(serverClientId: _serverClientId);
    _initialized = true;
  }

  /// 로그인 성공 시 Google ID Token을 반환한다. 사용자가 로그인을 취소하면 `null`을 반환한다.
  Future<String?> signIn() async {
    await _ensureInitialized();
    final signIn = GoogleSignIn.instance;

    if (!signIn.supportsAuthenticate()) {
      throw StateError('이 플랫폼은 대화형 Google 로그인을 지원하지 않습니다.');
    }

    final completer = Completer<String?>();
    late final StreamSubscription<GoogleSignInAuthenticationEvent> subscription;
    subscription = signIn.authenticationEvents.listen(
      (event) {
        subscription.cancel();
        if (event is GoogleSignInAuthenticationEventSignIn) {
          completer.complete(event.user.authentication.idToken);
        } else {
          completer.complete(null);
        }
      },
      onError: (Object error, StackTrace stackTrace) {
        subscription.cancel();
        // TODO(temp-debug): 실기기 로그인 실패 원인 조사용 임시 로그 — 원인 파악 후 제거.
        debugPrint(
          '[GoogleAuthService.authenticationEvents.onError] ${error.runtimeType}: $error\n$stackTrace',
        );
        if (!completer.isCompleted) completer.completeError(error, stackTrace);
      },
    );

    try {
      await signIn.authenticate();
    } catch (e, stackTrace) {
      // TODO(temp-debug): 실기기 로그인 실패 원인 조사용 임시 로그 — 원인 파악 후 제거.
      debugPrint(
        '[GoogleAuthService.signIn authenticate()] ${e.runtimeType}: $e\n$stackTrace',
      );
      // 사용자가 로그인 창을 취소한 경우 등 — authenticationEvents로 실패가 전달되지 않을 수 있어
      // authenticate() 자체의 예외도 취소로 간주한다.
      if (!completer.isCompleted) {
        subscription.cancel();
        return null;
      }
    }

    return completer.future;
  }
}
