import 'package:flutter/foundation.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:permission_handler/permission_handler.dart';

import '../data/location_permission_service.dart';
import 'location_task_handler.dart';

enum TrackingPhase { idle, running, error }

class LocationTrackingState {
  final TrackingPhase phase;
  final DateTime? lastSentAt;
  final String? lastErrorMessage;

  const LocationTrackingState({
    this.phase = TrackingPhase.idle,
    this.lastSentAt,
    this.lastErrorMessage,
  });

  LocationTrackingState copyWith({
    TrackingPhase? phase,
    DateTime? lastSentAt,
    String? lastErrorMessage,
    bool clearError = false,
  }) {
    return LocationTrackingState(
      phase: phase ?? this.phase,
      lastSentAt: lastSentAt ?? this.lastSentAt,
      lastErrorMessage: clearError
          ? null
          : (lastErrorMessage ?? this.lastErrorMessage),
    );
  }
}

/// 위치 전송 화면(§6)의 상태를 관리한다. 실제 GPS 수집/전송은 [LocationTaskHandler]가 별도
/// isolate(Foreground Service)에서 수행하고, 이 컨트롤러는 서비스 시작/중지와 상태 표시만 담당한다.
class LocationTrackingController extends Notifier<LocationTrackingState> {
  /// 전송 주기 — 5~15분 권장 범위(과제 지시) 중 중간값인 10분을 택했다. 문서에 정확한 주기가 명시돼
  /// 있지 않아 자체 결정 — 너무 잦으면 배터리 소모가 커지고, 너무 뜸하면 방문/이탈(GeoFence) 판정에
  /// 필요한 시간 해상도가 떨어진다는 두 요구 사이의 절충이다.
  static const _sendIntervalMillis = 10 * 60 * 1000;
  static const _serviceId = 5000;

  @override
  LocationTrackingState build() {
    _initForegroundTask();
    FlutterForegroundTask.addTaskDataCallback(_onTaskData);
    ref.onDispose(
      () => FlutterForegroundTask.removeTaskDataCallback(_onTaskData),
    );
    return const LocationTrackingState();
  }

  void _initForegroundTask() {
    FlutterForegroundTask.init(
      androidNotificationOptions: AndroidNotificationOptions(
        channelId: 'tracecare_location_channel',
        channelName: '위치 전송',
        channelDescription: 'TraceCare가 보호자에게 위치를 전달하기 위해 실행 중입니다.',
        onlyAlertOnce: true,
      ),
      iosNotificationOptions: const IOSNotificationOptions(),
      foregroundTaskOptions: ForegroundTaskOptions(
        eventAction: ForegroundTaskEventAction.repeat(_sendIntervalMillis),
        allowWakeLock: true,
        allowWifiLock: true,
      ),
    );
  }

  void _onTaskData(Object data) {
    debugPrint('[LocationTrackingController._onTaskData] $data');
    if (data is! Map) return;
    final status = data['status'] as String?;
    final timestamp = DateTime.tryParse(data['timestamp'] as String? ?? '');
    if (status == 'success') {
      state = state.copyWith(
        phase: TrackingPhase.running,
        lastSentAt: timestamp,
        clearError: true,
      );
    } else if (status == 'error') {
      state = state.copyWith(
        phase: TrackingPhase.running,
        lastErrorMessage: data['message'] as String?,
      );
    }
  }

  /// 1단계(앱 사용 중 위치) → 2단계(항상 허용) 순서로 권한을 요청한다(Android 정책, §6.2).
  /// 둘 다 승인되어야 `true`.
  Future<bool> ensurePermissionsGranted(
    LocationPermissionService permissionService,
  ) async {
    if (!await permissionService.isLocationServiceEnabled()) {
      return false;
    }

    var foreground = await permissionService.foregroundStatus();
    if (!foreground.isGranted) {
      foreground = await permissionService.requestForeground();
    }
    if (!foreground.isGranted) {
      return false;
    }

    var background = await permissionService.backgroundStatus();
    if (!background.isGranted) {
      background = await permissionService.requestBackground();
    }

    await permissionService.requestNotification();

    return background.isGranted;
  }

  Future<bool> start() async {
    final result = await FlutterForegroundTask.startService(
      serviceId: _serviceId,
      serviceTypes: const [ForegroundServiceTypes.location],
      notificationTitle: 'TraceCare 위치 전송 중',
      notificationText: '보호자에게 위치를 안전하게 전달하고 있어요.',
      callback: locationTaskCallback,
    );
    final started = result is ServiceRequestSuccess;
    state = state.copyWith(
      phase: started ? TrackingPhase.running : TrackingPhase.error,
    );
    return started;
  }

  Future<void> stop() async {
    await FlutterForegroundTask.stopService();
    state = const LocationTrackingState();
  }
}

final locationPermissionServiceProvider = Provider<LocationPermissionService>((
  ref,
) {
  return LocationPermissionService();
});

final locationTrackingControllerProvider =
    NotifierProvider<LocationTrackingController, LocationTrackingState>(
      LocationTrackingController.new,
    );
