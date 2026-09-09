import 'package:flutter/material.dart';

import 'navigation_service.dart';

/// API_Response_Rule.md §7.4/§7.6 HTTP Status별 사용자 메시지 표시 기준을 그대로 구현한다.
/// 화면 코드에서 status code를 직접 분기하지 않고 이 클래스를 거친다.
class AppErrorHandler {
  AppErrorHandler._();

  static void showToast(String message) {
    final context = NavigationService.navigatorKey.currentContext;
    if (context == null) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }

  static void showNetworkError() {
    showToast('네트워크 연결을 확인해주세요.');
  }

  static void showForbidden(String message) {
    final context = NavigationService.navigatorKey.currentContext;
    if (context == null) return;
    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        content: Text(message),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('확인'),
          ),
        ],
      ),
    );
  }
}
