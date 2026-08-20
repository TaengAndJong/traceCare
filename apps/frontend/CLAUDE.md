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
