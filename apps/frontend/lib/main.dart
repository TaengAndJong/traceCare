import 'package:flutter/material.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'core/network/navigation_service.dart';
import 'core/router/app_router.dart';

void main() {
  // Foreground Service(백그라운드 위치 전송) isolate가 sendDataToMain()으로 보내는 데이터를
  // 메인 isolate가 실제로 수신하려면 이 통신 포트를 먼저 등록해야 한다 — 등록 없이
  // addTaskDataCallback만 걸어두면 콜백이 호출되지 않고 데이터가 조용히 유실된다(실기기 실측으로
  // 확인된 문제, 2026-09). 위치 전송 자체(GPS 수집→백엔드 저장)는 이 포트와 무관하게 정상 동작하며,
  // 이건 순수하게 "성공 여부를 화면에 보여주기 위한" 채널이다.
  FlutterForegroundTask.initCommunicationPort();
  runApp(const ProviderScope(child: TraceCareApp()));
}

class TraceCareApp extends StatelessWidget {
  const TraceCareApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'TraceCare',
      navigatorKey: NavigationService.navigatorKey,
      theme: ThemeData(colorSchemeSeed: Colors.teal, useMaterial3: true),
      initialRoute: AppRouter.initialRoute,
      routes: AppRouter.routes,
    );
  }
}
