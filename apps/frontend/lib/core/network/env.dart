/// 환경 설정 — `--dart-define`으로 주입한다(.env 파일을 앱에 번들링하지 않음).
///
/// 로컬 개발(실기기 USB 연결) 시 Backend 접속 방식: `adb reverse tcp:8080 tcp:8080`을 사용해
/// PC의 8080 포트를 폰의 localhost:8080으로 그대로 매핑한다. 그래서 기본값은 `localhost`다.
///
/// PC의 실제 네트워크 IP(예: `192.168.0.5:8080`)를 직접 넣는 대안도 검토했으나, USB로 연결된
/// 실기기 개발 환경에서는 `adb reverse`가 더 안정적이다 — Wi-Fi가 꺼져 있거나 폰과 PC가 다른
/// 서브넷에 있어도 동작하고, 방화벽 설정을 새로 열 필요가 없다(USB 연결 자체가 통로가 된다).
/// 단점은 `adb reverse`가 세션성이라 기기를 재연결하거나 PC를 재부팅하면 다시 실행해야 한다는
/// 것뿐이라, 실제 기기 없이 여러 명이 같은 코드를 받는 것도 아닌 1인 개발 단계에서는 이 쪽이 더
/// 간단하다고 판단했다.
class Env {
  Env._();

  static const String apiBaseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://localhost:8080',
  );

  /// Backend `google.client-id`(Web OAuth Client ID)와 동일한 값이어야 한다 — Google Cloud Console에서
  /// 발급, `.env.example`의 `GOOGLE_CLIENT_ID` 참고. Android OAuth Client ID가 아니다.
  static const String googleServerClientId = String.fromEnvironment(
    'GOOGLE_CLIENT_ID',
  );
}
