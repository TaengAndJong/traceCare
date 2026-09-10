# CLAUDE.md (apps/frontend)
>해당파일 경로 apps/frontend/CLAUDE.md

## 이 앱은
Flutter 기반 모바일 앱. Guardian(보호자)과 CareTarget(보호대상자)이 Role에 따라 다른 화면을 쓰는 단일 앱이다.

## 지침 문서

| 주제 | 문서 |
|---|---|
| 코딩 스타일, 프로젝트 구조, 상태관리 | `docs/frontend/Coding_Convention.md` (일반 추천안, 확정 아님) |
| API 요청/응답 파싱, 에러 처리, Token 재발급 흐름 | `docs/api/API_Response_Rule.md` 7장 (이미 확정 — 재구현하지 말고 그대로 따를 것) |
| 전체 엔드포인트 목록 | `docs/api/API_Specification.md` |
| Token 저장 위치(Secure Storage) | `docs/security/Security_Guide.md` §3.1 |
| Role 변경 불가 정책 | `docs/api/API_Specification.md` §2.2 (`USER_004`) |

이 파일에서 위 내용을 재설명하지 않는다. 충돌 시 위 문서가 원본이다.

## 실행

```
flutter pub get
flutter run
```

## 코드 작성 시 최우선 확인 순서
1. 새 API 연동이 공통 `ApiResponse.fromJson` / `ApiErrorInterceptor`를 거치는가 (개별 파서 작성 금지, API_Response_Rule.md §7.1/§7.3)
2. 호출하려는 API가 `API_Specification.md`에 정의된 URI/권한과 일치하는가
3. Access/Refresh Token을 `SharedPreferences`가 아니라 `flutter_secure_storage`에 저장하는가
4. Role 선택 화면을 최초 온보딩 이후에 다시 노출하지 않는가 (Role은 최초 1회만 선택 가능)


## 디버깅 세션 리포트 규칙

디버그 로그 추가, 버그 원인 조사, 실기기 테스트처럼 "문제를 진단하는 성격의 작업"을 수행한 세션에서는,
작업이 끝날 때 그날 날짜로 된 텍스트 리포트를 로컬 파일로 남긴다.

- 저장 위치: `docs/debug-reports/frontend/YYYY-MM-DD.txt` (해당 날짜 폴더/파일이 없으면 새로 생성, 있으면 그날 파일에 이어서 추가)
  - 포함 내용:
    - 그 세션에서 조사한 문제(증상)
    - 시도한 해결 방법과 각각의 결과 (성공/실패 무관하게 전부 기록, 시도 순서대로)
        - 예: "① adb reverse 설정 여부 확인 → 미설정 확인, 설정 후 재시도 → 실패", "② 시스템 시간 자동설정 확인 → 이미 켜져 있음 → 원인 아님으로 배제", "③ ..."
        - 실패한 시도도 "왜 그게 원인이 아니라고 판단했는지"까지 한 줄로 남긴다 (나중에 비슷한 증상이 재발했을 때, 이미 배제된 원인을 또 시도하는 시간 낭비를 막기 위함)
    - 확인된 원인(코드 버그 / 환경 문제 / 원인 불명 등 구분)
    - 실제로 변경한 파일과 변경 요지
    - 남겨둔 디버그 로그(`TODO(temp-debug)` 등)의 위치 목록
    - 아직 미해결이거나 추가 확인이 필요한 사항
  
- 이 파일은 커밋 대상에 포함한다(다른 코드 변경과 함께 커밋해도 무방 — 별도 커밋 강제하지 않음).
- 같은 날 여러 번 디버깅 세션이 있으면 하나의 파일에 시간 구분선(`--- 14:30 세션 ---` 등)을 넣어 이어서 작성한다.
- 목적: 매 세션 대화 내용을 다시 훑지 않고도, 나중에 이 폴더의 파일들만 모아서 그 주간의 디버깅 이력을 훑어볼 수 있게 하기 위함이다.