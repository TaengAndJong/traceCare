import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:tracecare_app/core/network/auth_error_handler.dart';
import 'package:tracecare_app/core/network/token_refresh_client.dart';
import 'package:tracecare_app/core/storage/secure_token_storage.dart';

class _MockSecureTokenStorage extends Mock implements SecureTokenStorage {}

class _MockTokenRefreshClient extends Mock implements TokenRefreshClient {}

/// Coding_Convention.md §8: "인증 흐름(로그인, 토큰 만료 시 자동 로그아웃)은 반드시 통합 테스트로
/// 커버한다" — API_Response_Rule.md §7.5의 핵심 분기(AUTH_002 재발급 성공/실패, 그 외 코드는 즉시
/// 로그아웃, 재발급 자체 실패 시 무한 루프 없음)를 검증한다.
void main() {
  // NavigationService.goToLoginAndClearStack()가 GlobalKey.currentState를 거치며
  // WidgetsBinding.instance를 참조하므로, 위젯 트리 없이 순수 로직만 테스트하는 이 파일에서도
  // 바인딩을 먼저 초기화해야 한다.
  TestWidgetsFlutterBinding.ensureInitialized();

  late _MockSecureTokenStorage tokenStorage;
  late _MockTokenRefreshClient refreshClient;
  late AuthErrorHandler handler;

  setUp(() {
    tokenStorage = _MockSecureTokenStorage();
    refreshClient = _MockTokenRefreshClient();
    handler = AuthErrorHandler(
      tokenStorage: tokenStorage,
      refreshClient: refreshClient,
    );
  });

  group('AUTH_002 (Access Token 만료)', () {
    test('재발급에 성공하면 새 토큰을 저장하고 재시도 신호(true)를 반환한다', () async {
      when(() => tokenStorage.readRefreshToken())
          .thenAnswer((_) async => 'old-refresh');
      when(() => refreshClient.refresh('old-refresh'))
          .thenAnswer((_) async => ('new-access', 'new-refresh'));
      when(
        () => tokenStorage.saveTokens(
          accessToken: any(named: 'accessToken'),
          refreshToken: any(named: 'refreshToken'),
        ),
      ).thenAnswer((_) async {});

      final shouldRetry = await handler.handleUnauthorized('AUTH_002');

      expect(shouldRetry, isTrue);
      verify(
        () => tokenStorage.saveTokens(
          accessToken: 'new-access',
          refreshToken: 'new-refresh',
        ),
      ).called(1);
      verifyNever(() => tokenStorage.clear());
    });

    test('재발급 API 자체가 실패하면 재시도하지 않고(false) 즉시 로그아웃(토큰 삭제)한다', () async {
      when(() => tokenStorage.readRefreshToken())
          .thenAnswer((_) async => 'old-refresh');
      when(() => refreshClient.refresh('old-refresh')).thenThrow(
        DioException(
          requestOptions: RequestOptions(path: '/api/auth/refresh'),
          response: null,
        ),
      );
      when(() => tokenStorage.clear()).thenAnswer((_) async {});

      final shouldRetry = await handler.handleUnauthorized('AUTH_002');

      expect(shouldRetry, isFalse);
      verify(() => tokenStorage.clear()).called(1);
      // 재발급 실패 시 다시 refresh를 호출하지 않는다(무한 루프 방지) — 1회만 호출됐는지 확인.
      verify(() => refreshClient.refresh('old-refresh')).called(1);
    });

    test('저장된 Refresh Token 자체가 없으면 재발급을 시도하지 않고 즉시 로그아웃한다', () async {
      when(() => tokenStorage.readRefreshToken()).thenAnswer((_) async => null);
      when(() => tokenStorage.clear()).thenAnswer((_) async {});

      final shouldRetry = await handler.handleUnauthorized('AUTH_002');

      expect(shouldRetry, isFalse);
      verifyNever(() => refreshClient.refresh(any()));
      verify(() => tokenStorage.clear()).called(1);
    });
  });

  group('AUTH_004 등 그 외 401', () {
    test('AUTH_004(Refresh Token 만료)는 재발급 시도 없이 바로 로그아웃한다', () async {
      when(() => tokenStorage.clear()).thenAnswer((_) async {});

      final shouldRetry = await handler.handleUnauthorized('AUTH_004');

      expect(shouldRetry, isFalse);
      verifyNever(() => refreshClient.refresh(any()));
      verifyNever(() => tokenStorage.readRefreshToken());
      verify(() => tokenStorage.clear()).called(1);
    });
  });

  group('동시 요청(Lock)', () {
    test('동시에 여러 401이 들어와도 재발급은 1회만 호출된다', () async {
      when(() => tokenStorage.readRefreshToken())
          .thenAnswer((_) async => 'old-refresh');
      when(() => refreshClient.refresh('old-refresh')).thenAnswer((_) async {
        await Future<void>.delayed(const Duration(milliseconds: 20));
        return ('new-access', 'new-refresh');
      });
      when(
        () => tokenStorage.saveTokens(
          accessToken: any(named: 'accessToken'),
          refreshToken: any(named: 'refreshToken'),
        ),
      ).thenAnswer((_) async {});

      final results = await Future.wait([
        handler.handleUnauthorized('AUTH_002'),
        handler.handleUnauthorized('AUTH_002'),
        handler.handleUnauthorized('AUTH_002'),
      ]);

      expect(results, everyElement(isTrue));
      verify(() => refreshClient.refresh('old-refresh')).called(1);
    });
  });
}
