import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../shared/widgets/loading_indicator.dart';
import '../application/auth_providers.dart';

/// 앱 시작 시 저장된 토큰이 있으면 로그인 화면을 건너뛰고 바로 위치 전송 화면으로 이동한다(§5).
class SplashScreen extends ConsumerWidget {
  const SplashScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final sessionAsync = ref.watch(hasStoredSessionProvider);

    return Scaffold(
      body: sessionAsync.when(
        data: (hasSession) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            Navigator.of(context)
                .pushReplacementNamed(hasSession ? '/location' : '/login');
          });
          return const LoadingIndicator();
        },
        loading: () => const LoadingIndicator(),
        error: (_, _) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            Navigator.of(context).pushReplacementNamed('/login');
          });
          return const LoadingIndicator();
        },
      ),
    );
  }
}
