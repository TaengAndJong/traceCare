# Frontend Coding Convention
>해당파일 경로 docs/frontend/Coding_Convention.md

프로젝트: TraceCare — 아이·노인 케어 위치추적 알림 시스템
문서 위치: `docs/frontend/Coding_Convention.md`
담당 앱: Flutter App (`apps/frontend/`) — Guardian/CareTarget 공용
버전: v1.0 — **일반적인 Flutter/Dart 추천안**(팀 확정 규칙 아님, 실제 개발 착수 시 재검토)

> 이 문서는 **Dart/Flutter 코드를 어떤 스타일로 작성할 것인가**를 담당한다.
> API 요청/응답 파싱 규칙(`ApiResponse.fromJson`, Dio 인터셉터)은 이미 `docs/api/API_Response_Rule.md` 7장에서 확정돼 있으므로 이 문서는 그 구현을 재정의하지 않고, "그 코드를 어느 폴더에 어떤 스타일로 둘 것인가"만 다룬다.

---

## 목차

1. 프로젝트 구조
2. 상태 관리
3. 네이밍 규칙
4. API 연동 (기존 확정 사항 연결)
5. Role 기반 화면 분기
6. 위젯 작성 규칙
7. 포맷터 / 린터
8. 테스트
9. 개발 체크리스트

---

## 1. 프로젝트 구조 (추천안)

Guardian과 CareTarget이 같은 앱 안에서 Role에 따라 화면이 갈리는 구조이므로(API_Specification.md 권한 분기표), feature-first 구조를 추천한다.

```
apps/frontend/lib
 ├─ main.dart
 ├─ core
 │   ├─ network                 # Dio 설정, ApiResponse 파서, 인터셉터 (API_Response_Rule.md 7장)
 │   ├─ router                  # Role 기반 라우팅 분기 (5장)
 │   └─ storage                 # flutter_secure_storage 래퍼 (Security_Guide.md 3.1)
 │
 ├─ features                    # API_Specification.md 도메인과 1:1 대응
 │   ├─ auth                     # 로그인, Role 선택
 │   ├─ guardian
 │   │   ├─ care_target           # 보호대상자 관리
 │   │   ├─ place                 # 장소(안심구역) 관리
 │   │   └─ ai_report              # AI 예측/케어 비서
 │   ├─ care_target
 │   │   ├─ location               # 위치 전송
 │   │   ├─ arrival                # 도착 확인
 │   │   └─ emergency              # 긴급 연락
 │   └─ notification              # Guardian/CareTarget 공용 알림
 │
 └─ shared                       # 여러 feature가 함께 쓰는 위젯/유틸
```

각 `feature` 폴더 내부는 `presentation`(화면/위젯), `application`(상태관리 로직), `data`(Repository, API 호출)로 나눈다.

## 2. 상태 관리

여러 선택지(Provider, Riverpod, Bloc) 중 하나를 프로젝트 시작 시점에 확정하고 전체 앱에서 통일한다 — 기능별로 다른 도구를 섞어 쓰지 않는다. 이 프로젝트 규모(1인 개발, 화면 수 제한적)에서는 **Riverpod**을 1차 추천한다: 보일러플레이트가 Bloc보다 적고, `flutter_secure_storage`/Dio 인터셉터 같은 비동기 의존성 주입이 Provider보다 명시적이다.

> 확정 전이므로, 이 절은 실제 개발 착수 시 팀(1인 개발자) 판단으로 재검토하고 이 문서를 갱신한다.

## 3. 네이밍 규칙

| 대상 | 규칙 | 예시 |
|---|---|---|
| 파일명 | snake_case | `care_target_list_screen.dart` |
| 클래스 | UpperCamelCase | `CareTargetListScreen` |
| 변수/함수 | lowerCamelCase | `fetchCareTargets` |
| 상수 | lowerCamelCase(Dart 관례, `const`) | `defaultPageSize` |
| Screen 위젯 | `{기능}Screen` | `EmergencyContactScreen` |
| 재사용 위젯 | `{역할}Widget` 또는 기능이 드러나는 이름(불필요한 `Widget` 접미사는 생략 가능) | `CareTargetCard` |
| Repository | `{도메인}Repository` (Backend 네이밍과 대칭) | `PlaceRepository` |

## 4. API 연동 (기존 확정 사항 연결)

아래는 이미 `API_Response_Rule.md` 7장에서 확정된 내용이며, 이 문서에서 다시 정의하지 않는다. Frontend 코드를 작성할 때 반드시 그 문서의 구현을 그대로 따른다.

- 모든 API 응답은 공통 `ApiResponse<T>.fromJson`으로 파싱한다(개별 파서 금지) — §7.1
- `success: false` 또는 4xx/5xx는 Dio `ApiErrorInterceptor`가 공통 처리한다 — §7.3
- Access Token 만료(`AUTH_002`) 시 인터셉터에서 자동 재발급 1회 시도, 실패 시 로그아웃 — §7.5
- HTTP Status별 화면 처리 기준(403 다이얼로그, 404 토스트 등)은 §7.4/§7.6 표를 그대로 따른다

Frontend 코드에서 새로운 에러 처리 분기가 필요하면, 이 문서가 아니라 `API_Response_Rule.md` 7장에 먼저 추가한다.

## 5. Role 기반 화면 분기

- 로그인 성공 시 JWT의 `role` 클레임(API_Specification.md §2.1)을 기준으로 `core/router`에서 Guardian 홈/CareTarget 홈으로 분기한다.
- CareTarget 전용 화면에서 실수로 Guardian 전용 API를 호출하지 않도록, Repository 계층에서 호출 가능한 Role을 주석 또는 타입으로 명시한다(실제 차단은 Backend가 하지만, Frontend에서도 불필요한 API 호출을 미리 막아 UX를 개선한다).
- Role은 최초 로그인 이후 변경 불가(API_Specification.md §2.2, `USER_004`)하므로, Role 선택 화면은 최초 온보딩 플로우에서만 노출하고 이후 설정 메뉴 등에 다시 노출하지 않는다.

## 6. 위젯 작성 규칙

- 위젯은 200줄을 넘어가면 하위 위젯으로 분리한다.
- `build()` 메서드 안에서 비즈니스 로직(계산, API 호출)을 직접 수행하지 않는다 — `application` 계층(상태관리)으로 위임한다.
- 재사용 가능한 위젯(카드, 리스트 아이템 등)은 `shared/widgets`로 옮기고, feature 폴더 안에 중복 구현하지 않는다.
- `const` 생성자를 쓸 수 있는 위젯은 항상 `const`를 붙인다(불필요한 리빌드 방지).

## 7. 포맷터 / 린터

| 도구 | 용도 |
|---|---|
| `dart format` | 공식 포맷터, 기본 설정 그대로 사용(커스텀 규칙 최소화) |
| `flutter_lints` | 공식 린트 규칙 세트, `analysis_options.yaml`에 적용 |
| CI 연동 | PR마다 `dart format --set-exit-if-changed`와 `flutter analyze`를 필수 체크로 건다 |

## 8. 테스트

- Widget Test: 화면 단위 렌더링/상호작용 검증(`flutter_test`).
- Unit Test: Repository/상태관리 로직 검증, API 호출부는 Mock(`mocktail` 등)으로 대체.
- 최소한 인증 흐름(로그인, 토큰 만료 시 자동 로그아웃)과 긴급 연락(안전 기능) 플로우는 통합 테스트로 커버한다 — 이 두 흐름은 실패 시 사용자 안전/보안에 직접 영향을 준다.

## 9. 개발 체크리스트

- [ ] 새 API 연동이 공통 `ApiResponse.fromJson`/`ApiErrorInterceptor`를 거치는가(개별 파서 작성 금지)
- [ ] 새 화면이 올바른 `features/` 폴더(1장)에 위치하는가
- [ ] 상태관리 도구가 프로젝트 전체에서 통일돼 있는가
- [ ] `build()` 안에 비즈니스 로직이 섞여 있지 않은가
- [ ] `dart format`/`flutter analyze`를 통과하는가
- [ ] Role 선택 화면이 최초 온보딩 이후에도 재노출되지 않는가
