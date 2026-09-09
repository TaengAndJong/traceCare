import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../application/auth_controller.dart';

/// Google OAuth2 로그인 화면. 로그인 성공 후 `roleSelected`에 따라 Role 선택 화면 또는 위치 전송
/// 화면으로 이동한다(§5).
class LoginScreen extends ConsumerWidget {
  const LoginScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authState = ref.watch(authControllerProvider);

    ref.listen(authControllerProvider, (previous, next) {
      final message = next.errorMessage;
      if (message != null) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(message)));
      }
    });

    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                'TraceCare',
                style: TextStyle(fontSize: 28, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 48),
              if (authState.isLoading)
                const CircularProgressIndicator()
              else
                ElevatedButton(
                  onPressed: () => _handleSignIn(context, ref),
                  child: const Text('Google로 로그인'),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _handleSignIn(BuildContext context, WidgetRef ref) async {
    final result = await ref
        .read(authControllerProvider.notifier)
        .signInWithGoogle();
    if (result == null || !context.mounted) return;

    if (!result.roleSelected) {
      Navigator.of(context).pushReplacementNamed('/role-select');
      return;
    }

    if (result.role != 'CARE_TARGET') {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('이 앱은 아직 보호자(Guardian) 화면을 지원하지 않습니다.')),
      );
      return;
    }

    Navigator.of(context).pushReplacementNamed('/location');
  }
}
