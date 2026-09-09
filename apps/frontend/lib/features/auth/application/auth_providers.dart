import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/env.dart';
import '../../../core/network/network_providers.dart';
import '../../../core/storage/storage_providers.dart';
import '../data/auth_api.dart';
import '../data/auth_repository.dart';
import '../data/google_auth_service.dart';

final authApiProvider = Provider<AuthApi>((ref) {
  return AuthApi(ref.watch(dioProvider));
});

final authRepositoryProvider = Provider<AuthRepository>((ref) {
  return AuthRepository(
    authApi: ref.watch(authApiProvider),
    tokenStorage: ref.watch(secureTokenStorageProvider),
    refreshClient: ref.watch(tokenRefreshClientProvider),
  );
});

final googleAuthServiceProvider = Provider<GoogleAuthService>((ref) {
  return GoogleAuthService(serverClientId: Env.googleServerClientId);
});

/// 앱 시작 시 저장된 세션이 있는지 1회 확인한다(§5 — 재시작 후 로그인 상태 유지).
final hasStoredSessionProvider = FutureProvider<bool>((ref) {
  return ref.watch(authRepositoryProvider).hasStoredSession();
});
