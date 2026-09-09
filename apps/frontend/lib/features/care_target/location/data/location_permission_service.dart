import 'package:geolocator/geolocator.dart';
import 'package:permission_handler/permission_handler.dart';

/// 위치 권한 요청 전담. Android 정책상 "일반 위치 권한"과 "백그라운드(항상 허용) 위치 권한"을
/// 한 번에 요청할 수 없어(백그라운드 권한 다이얼로그 자체가 뜨지 않거나 거부됨) 반드시 2단계로
/// 나눠 순서대로 요청해야 한다(§6.2 조사 결과).
class LocationPermissionService {
  Future<bool> isLocationServiceEnabled() =>
      Geolocator.isLocationServiceEnabled();

  Future<PermissionStatus> foregroundStatus() => Permission.location.status;

  Future<PermissionStatus> backgroundStatus() =>
      Permission.locationAlways.status;

  /// 1단계 — 앱 사용 중 위치 권한.
  Future<PermissionStatus> requestForeground() => Permission.location.request();

  /// 2단계 — 항상 허용(백그라운드) 위치 권한. 1단계가 승인된 뒤에만 호출해야 한다.
  Future<PermissionStatus> requestBackground() =>
      Permission.locationAlways.request();

  Future<PermissionStatus> requestNotification() =>
      Permission.notification.request();
}
