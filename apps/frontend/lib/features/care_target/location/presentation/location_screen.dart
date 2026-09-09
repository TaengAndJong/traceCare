import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../shared/widgets/loading_indicator.dart';
import '../application/location_tracking_controller.dart';

/// 위치 전송 화면(§6) — 최초 진입 시 위치 권한을 단계적으로 요청하고, 승인되면 백그라운드
/// Foreground Service를 시작한다. 디자인은 최소한으로 하고 "전송 중" 상태만 보여준다(§6.2).
class LocationScreen extends ConsumerStatefulWidget {
  const LocationScreen({super.key});

  @override
  ConsumerState<LocationScreen> createState() => _LocationScreenState();
}

enum _ScreenPhase { checking, permissionDenied, ready }

class _LocationScreenState extends ConsumerState<LocationScreen> {
  _ScreenPhase _phase = _ScreenPhase.checking;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _setup());
  }

  Future<void> _setup() async {
    final controller = ref.read(locationTrackingControllerProvider.notifier);
    final permissionService = ref.read(locationPermissionServiceProvider);

    final granted = await controller.ensurePermissionsGranted(
      permissionService,
    );
    debugPrint('[LocationScreen._setup] permissionsGranted=$granted');
    if (!mounted) return;

    if (!granted) {
      setState(() => _phase = _ScreenPhase.permissionDenied);
      return;
    }

    final started = await controller.start();
    debugPrint('[LocationScreen._setup] serviceStarted=$started');
    if (!mounted) return;
    setState(() => _phase = _ScreenPhase.ready);
  }

  @override
  Widget build(BuildContext context) {
    final trackingState = ref.watch(locationTrackingControllerProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('위치 전송')),
      body: switch (_phase) {
        _ScreenPhase.checking => const LoadingIndicator(),
        _ScreenPhase.permissionDenied => _PermissionDeniedView(onRetry: _setup),
        _ScreenPhase.ready => _TrackingStatusView(state: trackingState),
      },
    );
  }
}

class _PermissionDeniedView extends StatelessWidget {
  const _PermissionDeniedView({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              '위치 권한(항상 허용)이 필요합니다.\n'
              '설정에서 위치 권한을 "항상 허용"으로 바꿔주세요.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            ElevatedButton(onPressed: onRetry, child: const Text('다시 시도')),
          ],
        ),
      ),
    );
  }
}

class _TrackingStatusView extends StatelessWidget {
  const _TrackingStatusView({required this.state});

  final LocationTrackingState state;

  @override
  Widget build(BuildContext context) {
    final lastSentAt = state.lastSentAt;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.location_on, size: 64, color: Colors.green),
            const SizedBox(height: 16),
            const Text(
              '위치 전송 중',
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(
              lastSentAt == null
                  ? '첫 전송을 준비하고 있어요.'
                  : '마지막 전송: ${lastSentAt.toLocal()}',
            ),
            if (state.lastErrorMessage != null) ...[
              const SizedBox(height: 8),
              Text(
                '최근 전송 실패 — 다음 주기에 재시도합니다.',
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
