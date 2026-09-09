import 'package:flutter/material.dart';

/// 인터셉터 등 `BuildContext`가 없는 위치에서 화면 전환이 필요할 때 쓰는 전역 Navigator 키.
/// `MaterialApp(navigatorKey: NavigationService.navigatorKey, ...)`로 연결한다.
class NavigationService {
  NavigationService._();

  static final GlobalKey<NavigatorState> navigatorKey =
      GlobalKey<NavigatorState>();

  /// AUTH_004(Refresh Token 만료) 또는 재발급 실패 시 로그인 화면으로 강제 이동하고 스택을 비운다.
  static void goToLoginAndClearStack() {
    navigatorKey.currentState?.pushNamedAndRemoveUntil(
      '/login',
      (route) => false,
    );
  }
}
