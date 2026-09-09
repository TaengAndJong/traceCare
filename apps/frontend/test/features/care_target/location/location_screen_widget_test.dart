import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tracecare_app/features/care_target/location/presentation/location_screen.dart';

/// Coding_Convention.md §8 — 위치 전송 화면은 최소한의 렌더링 확인이면 충분하다.
/// 실제 권한 요청/Foreground Service 시작은 플랫폼 채널을 타므로, 첫 프레임(권한 확인 중 상태)만
/// 검증하고 `pumpAndSettle`은 사용하지 않는다(플랫폼 채널 미모킹 시 응답이 오지 않아 타임아웃 위험).
void main() {
  testWidgets('LocationScreen이 크래시 없이 렌더링되고 초기 로딩 상태를 보여준다', (tester) async {
    await tester.pumpWidget(
      const ProviderScope(child: MaterialApp(home: LocationScreen())),
    );
    await tester.pump();

    expect(find.text('위치 전송'), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
  });
}
