import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:tracecare_app/core/network/navigation_service.dart';
import 'package:tracecare_app/core/router/app_router.dart';
import 'package:tracecare_app/features/auth/application/auth_providers.dart';
import 'package:tracecare_app/features/auth/data/auth_api.dart';
import 'package:tracecare_app/features/auth/data/auth_repository.dart';
import 'package:tracecare_app/features/auth/data/google_auth_service.dart';

class _MockGoogleAuthService extends Mock implements GoogleAuthService {}

class _MockAuthRepository extends Mock implements AuthRepository {}

/// Coding_Convention.md §8 — 인증 흐름(로그인)의 화면 분기 통합 테스트.
/// Google 로그인 성공 → `roleSelected`에 따라 Role 선택 화면/위치 전송 화면으로 정확히
/// 이동하는지 검증한다(§5). 실제 네트워크/플랫폼 채널은 Mock으로 대체한다.
void main() {
  late _MockGoogleAuthService googleAuthService;
  late _MockAuthRepository authRepository;

  setUp(() {
    googleAuthService = _MockGoogleAuthService();
    authRepository = _MockAuthRepository();
  });

  Widget buildApp() {
    return ProviderScope(
      overrides: [
        googleAuthServiceProvider.overrideWithValue(googleAuthService),
        authRepositoryProvider.overrideWithValue(authRepository),
      ],
      child: MaterialApp(
        navigatorKey: NavigationService.navigatorKey,
        initialRoute: '/login',
        routes: AppRouter.routes,
      ),
    );
  }

  testWidgets('roleSelected:false면 Role 선택 화면으로 이동한다', (tester) async {
    when(() => googleAuthService.signIn())
        .thenAnswer((_) async => 'fake-id-token');
    when(() => authRepository.loginWithGoogle(idToken: any(named: 'idToken')))
        .thenAnswer(
          (_) async => LoginResult(
            accessToken: 'a',
            refreshToken: 'r',
            role: null,
            userId: 'user-1',
            roleSelected: false,
          ),
        );

    await tester.pumpWidget(buildApp());
    await tester.tap(find.text('Google로 로그인'));
    await tester.pumpAndSettle();

    expect(find.text('보호대상자 정보 등록'), findsOneWidget);
  });

  testWidgets('roleSelected:true, role:CARE_TARGET이면 위치 전송 화면으로 이동한다', (
    tester,
  ) async {
    when(() => googleAuthService.signIn())
        .thenAnswer((_) async => 'fake-id-token');
    when(() => authRepository.loginWithGoogle(idToken: any(named: 'idToken')))
        .thenAnswer(
          (_) async => LoginResult(
            accessToken: 'a',
            refreshToken: 'r',
            role: 'CARE_TARGET',
            userId: 'user-1',
            roleSelected: true,
          ),
        );

    await tester.pumpWidget(buildApp());
    await tester.tap(find.text('Google로 로그인'));
    // LocationScreen 진입 후 내부적으로 실제 권한/Foreground Service 플랫폼 채널을 타므로
    // pumpAndSettle 대신 라우트 전환에 필요한 만큼만 프레임을 진행한다(무기한 대기 방지).
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('위치 전송'), findsOneWidget);
  });

  testWidgets('사용자가 Google 로그인을 취소하면 로그인 화면에 그대로 머문다', (tester) async {
    when(() => googleAuthService.signIn()).thenAnswer((_) async => null);

    await tester.pumpWidget(buildApp());
    await tester.tap(find.text('Google로 로그인'));
    await tester.pumpAndSettle();

    expect(find.text('Google로 로그인'), findsOneWidget);
    verifyNever(
      () => authRepository.loginWithGoogle(idToken: any(named: 'idToken')),
    );
  });
}
