import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/auth_api.dart';
import 'auth_providers.dart';

enum AuthStatus { idle, loading }

class AuthUiState {
  final AuthStatus status;
  final String? errorMessage;

  const AuthUiState({this.status = AuthStatus.idle, this.errorMessage});

  bool get isLoading => status == AuthStatus.loading;
}

/// 로그인/Role 확정 화면의 로딩·에러 상태를 관리한다(`build()` 안에서 직접 API를 호출하지 않기 위한
/// application 계층, Coding_Convention.md §6).
class AuthController extends Notifier<AuthUiState> {
  @override
  AuthUiState build() => const AuthUiState();

  /// 성공 시 로그인 결과(Role 확정 여부 포함)를 반환, 사용자가 취소하면 `null`을 반환한다.
  Future<LoginResult?> signInWithGoogle() async {
    state = const AuthUiState(status: AuthStatus.loading);
    try {
      final idToken = await ref.read(googleAuthServiceProvider).signIn();
      if (idToken == null) {
        state = const AuthUiState();
        return null;
      }
      final result = await ref
          .read(authRepositoryProvider)
          .loginWithGoogle(idToken: idToken);
      state = const AuthUiState();
      return result;
    } catch (e, stackTrace) {
      // TODO(temp-debug): 실기기 로그인 실패 원인 조사용 임시 로그 — 원인 파악 후 제거.
      debugPrint(
        '[AuthController.signInWithGoogle] ${e.runtimeType}: $e\n$stackTrace',
      );
      state = const AuthUiState(errorMessage: '로그인에 실패했습니다. 다시 시도해주세요.');
      return null;
    }
  }

  Future<bool> confirmCareTargetRole({
    required String name,
    required String birthDate,
  }) async {
    state = const AuthUiState(status: AuthStatus.loading);
    try {
      await ref
          .read(authRepositoryProvider)
          .confirmCareTargetRole(name: name, birthDate: birthDate);
      state = const AuthUiState();
      return true;
    } catch (_) {
      state = const AuthUiState(errorMessage: '정보 저장에 실패했습니다. 다시 시도해주세요.');
      return false;
    }
  }
}

final authControllerProvider = NotifierProvider<AuthController, AuthUiState>(
  AuthController.new,
);
