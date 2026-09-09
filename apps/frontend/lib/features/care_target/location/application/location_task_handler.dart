import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:geolocator/geolocator.dart';

import '../../../../core/network/env.dart';
import '../../../../core/network/token_refresh_client.dart';
import '../../../../core/storage/secure_token_storage.dart';
import '../data/location_api.dart';

/// Android가 Foreground Service를 별도 격리된 isolate(엔진)에서 구동하므로 진입점을
/// top-level 함수로 노출해야 한다(`@pragma('vm:entry-point')` 필수 — 릴리스 빌드 트리 셰이킹에서
/// 제외되도록 지정).
@pragma('vm:entry-point')
void locationTaskCallback() {
  FlutterForegroundTask.setTaskHandler(LocationTaskHandler());
}

/// 백그라운드 Foreground Service isolate에서 주기적으로 실행된다. 메인 isolate의 Riverpod
/// `ProviderContainer`를 공유할 수 없으므로, `core/network`·`core/storage`의 순수 Dart 클래스를
/// 그대로 재사용해 이 안에서 직접 의존성을 구성한다(Secure Storage는 두 isolate가 같은 네이티브
/// Keystore를 플랫폼 채널로 공유하므로 토큰이 자연히 동기화된다).
class LocationTaskHandler extends TaskHandler {
  final SecureTokenStorage _tokenStorage = SecureTokenStorage();
  final TokenRefreshClient _refreshClient = TokenRefreshClient();
  final Dio _dio = Dio(BaseOptions(baseUrl: Env.apiBaseUrl));
  late final LocationApi _locationApi = LocationApi(_dio);

  @override
  Future<void> onStart(DateTime timestamp, TaskStarter starter) async {
    debugPrint('[LocationTaskHandler.onStart] starter=$starter');
    await _sendCurrentLocation();
  }

  @override
  void onRepeatEvent(DateTime timestamp) {
    debugPrint('[LocationTaskHandler.onRepeatEvent] $timestamp');
    // TaskHandler.onRepeatEvent는 void를 반환하므로 내부에서 비동기 작업을 fire-and-forget으로 실행한다.
    _sendCurrentLocation();
  }

  @override
  Future<void> onDestroy(DateTime timestamp, bool isTimeout) async {
    debugPrint('[LocationTaskHandler.onDestroy] isTimeout=$isTimeout');
  }

  Future<void> _sendCurrentLocation() async {
    try {
      debugPrint('[LocationTaskHandler] requesting current position...');
      final position = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.high,
        ),
      );
      debugPrint(
        '[LocationTaskHandler] got position lat=${position.latitude}, lng=${position.longitude}, '
        'time=${position.timestamp}',
      );
      await _sendWithAuthRetry(position);
      debugPrint('[LocationTaskHandler] send succeeded');
      FlutterForegroundTask.sendDataToMain({
        'status': 'success',
        'timestamp': DateTime.now().toIso8601String(),
      });
    } catch (e, stackTrace) {
      // TODO(temp-debug): 실기기 위치 전송 실패 원인 조사용 임시 로그 — 원인 파악 후 제거.
      debugPrint(
        '[LocationTaskHandler] FAILED ${e.runtimeType}: $e\n$stackTrace',
      );
      // 오프라인 큐잉 등 정교한 재시도는 이번 범위 밖 — 실패해도 다음 주기에 다시 시도한다(§6.2).
      FlutterForegroundTask.sendDataToMain({
        'status': 'error',
        'message': e.toString(),
        'timestamp': DateTime.now().toIso8601String(),
      });
    }
  }

  Future<void> _sendWithAuthRetry(Position position) async {
    await _attachAuthHeader();
    try {
      await _postLocation(position);
    } on DioException catch (e) {
      final body = e.response?.data;
      final code = body is Map<String, dynamic>
          ? body['code'] as String?
          : null;
      if (e.response?.statusCode == 401 &&
          code == 'AUTH_002' &&
          await _refreshAccessToken()) {
        await _attachAuthHeader();
        await _postLocation(position);
        return;
      }
      rethrow;
    }
  }

  Future<void> _postLocation(Position position) {
    return _locationApi.sendLocation(
      latitude: position.latitude,
      longitude: position.longitude,
      recordedAt: position.timestamp,
    );
  }

  Future<void> _attachAuthHeader() async {
    final token = await _tokenStorage.readAccessToken();
    _dio.options.headers['Authorization'] = token != null
        ? 'Bearer $token'
        : null;
  }

  /// API_Response_Rule.md §7.5와 동일한 원칙 — 재발급 실패 시 재시도하지 않고 즉시 포기한다.
  /// 이 isolate는 UI가 없어 로그인 화면 전환은 하지 않는다 — 다음에 앱을 열었을 때 저장된 토큰이
  /// 비어 있으므로 Splash 화면이 정상적으로 로그인 화면으로 보낸다.
  Future<bool> _refreshAccessToken() async {
    final refreshToken = await _tokenStorage.readRefreshToken();
    if (refreshToken == null || refreshToken.isEmpty) return false;
    try {
      final (accessToken, newRefreshToken) = await _refreshClient.refresh(
        refreshToken,
      );
      await _tokenStorage.saveTokens(
        accessToken: accessToken,
        refreshToken: newRefreshToken,
      );
      return true;
    } on DioException {
      await _tokenStorage.clear();
      return false;
    }
  }
}
