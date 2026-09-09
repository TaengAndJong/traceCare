import 'package:flutter/material.dart';

import '../../features/auth/presentation/login_screen.dart';
import '../../features/auth/presentation/role_select_screen.dart';
import '../../features/auth/presentation/splash_screen.dart';
import '../../features/care_target/location/presentation/location_screen.dart';

/// 이번 Phase는 CareTarget 로그인+위치 전송만 다루므로 Role 기반 라우팅을 별도 분기 없이
/// 고정 경로로 둔다(Coding_Convention.md §5) — Guardian 화면이 추가되는 다음 Phase에서
/// `role` 클레임 기반 분기를 도입한다.
class AppRouter {
  AppRouter._();

  static const initialRoute = '/';

  static Map<String, WidgetBuilder> get routes => {
    '/': (_) => const SplashScreen(),
    '/login': (_) => const LoginScreen(),
    '/role-select': (_) => const RoleSelectScreen(),
    '/location': (_) => const LocationScreen(),
  };
}
