# API Response Standard
>해당파일 경로 docs/api/API_Response_Rule.md
> 이 문서는 Backend-Frontend 간 API 응답 규격을 왜 이렇게 설계했는지, 구조·대안·트레이드오프까지 상세히 작성한다.

**프로젝트**: 아이·노인 케어 위치추적 알림 시스템 (GIS 기반 케어 서비스)
**대상 독자**: Backend(Spring Boot) 개발자, Frontend(Flutter) 개발자
**목적**: Backend와 Frontend 간 API 요청/응답 규격을 통일하여 개발 생산성과 유지보수성을 확보한다.
**적용 범위**: `/api/**` (Guardian API, CareTarget API, Auth API). `/internal/**` 내부 API는 서버 간 통신이므로 본 문서의 클라이언트 처리 규칙(4절, 7절)은 적용 대상이 아니다.

**관련 문서** (이 문서는 각 영역을 요약만 하며, 상세는 아래 문서가 원본이다)
- 인증/인가/식별자 노출(IDOR) 상세 정책 → `docs/security/Security_Guide.md`
- OWASP Top 10 점검 기준 → `docs/security/OWASP_Security_Guide.md`
- 예외 계층 설계·`GlobalExceptionHandler` 구현 상세 → Exception Handling Rule 문서
- 테이블/식별자(`public_id`) 설계 근거 → `docs/db/DATABASE_DESIGN_GUIDE.md`

---

## 목차

1. API Response 설계 원칙
2. Success Response 규칙
3. Error Response 규칙
4. HTTP Status Code 기준
5. Error Code 관리 규칙
6. Backend 구현 기준
7. Frontend 처리 기준
8. 실제 프로젝트 API 적용 예시

---

## 1. API Response 설계 원칙

### 1.1 왜 공통 Response 구조가 필요한가

이 프로젝트는 Guardian(보호자)과 CareTarget(보호대상자)이라는 두 개의 Role이 서로 다른 화면·다른 API 세트를 사용하고, Flutter 클라이언트 하나가 Auth API·Guardian API·CareTarget API·AI API·알림 API를 모두 호출한다. Backend 개발자가 컨트롤러마다 응답 형태를 다르게 만들면, Flutter에서 API마다 파싱 로직을 따로 작성해야 하고 에러 처리 분기가 늘어나 유지보수가 어려워진다.

따라서 **모든 REST API(성공/실패 무관)는 동일한 최상위 Response 구조를 사용**한다. 이를 통해 Flutter는 단 하나의 공통 파서와 하나의 에러 핸들러만으로 모든 API 응답을 처리할 수 있다.

### 1.2 기본 원칙

| 원칙 | 내용 |
|---|---|
| 단일 구조 | 성공/실패 모두 `success`, `code`, `message`, `data` 4개 필드를 갖는다 |
| 성공 판단 기준 | HTTP Status가 2xx이고 `success: true`인 경우에만 성공으로 간주한다 |
| 실패 판단 기준 | HTTP Status가 4xx/5xx이거나 `success: false`인 경우 실패로 간주한다 |
| data 일관성 | 성공 시 `data`는 실제 응답 객체(또는 배열), 실패 시 `data`는 항상 `null` |
| code 필수 | 성공/실패 모두 `code`를 내려준다. Frontend는 `code` 기준으로 세부 분기 처리를 할 수 있다 |
| message는 노출용 | `message`는 사용자에게 그대로 보여줘도 되는 한국어 문장으로 작성한다 (내부 스택 트레이스 금지) |
| WebSocket 예외 | WebSocket(`/ws/**`)은 실시간 스트리밍 특성상 본 Response 구조를 그대로 적용하지 않고 별도의 메시지 규격(8.3절 참고)을 따른다 |

### 1.3 성공/실패 판단 기준

- **성공**: 요청이 정상 처리되어 클라이언트가 기대한 결과를 반환한 경우. 예) 로그인 성공, 위치 조회 성공, 보호대상자 등록 성공
- **실패**: 아래 4가지 중 하나에 해당하는 경우
  - 요청 값 자체가 잘못된 경우 (Validation 실패)
  - 인증/인가 실패 (토큰 없음, 만료, 권한 없음, 관계 미매핑)
  - 요청한 리소스가 존재하지 않는 경우
  - 서버 내부 오류 또는 외부 연동(AI Server, FCM, Google API) 실패

### 1.4 데이터 처리 방식

- 리스트를 반환하는 API(보호대상자 목록, 알림 목록, 위치 이력 등)는 `data` 내부에 배열을 직접 넣지 않고, **페이징 정보를 포함한 객체**로 감싼다 (6.2절 DTO 구조 참고).
- 단건 조회/등록/수정은 `data`에 해당 리소스 객체를 그대로 반환한다.
- 삭제 API는 `data: null` + `success: true`로 응답한다 (별도 반환할 데이터가 없음).
- 날짜/시간은 항상 **ISO-8601 UTC(`yyyy-MM-dd'T'HH:mm:ss'Z'`)** 로 통일하고, 화면 표시용 로컬 시간 변환은 Flutter에서 수행한다. 위치 좌표(위도/경도)는 `Double` 타입, 소수점 6자리 이상을 유지한다.

### 1.5 식별자 노출 정책 (public_id)

`DATABASE_DESIGN_GUIDE.md`(4.2/4.3절 개선안)는 순차 정수 PK를 API 응답/URL에 그대로 노출하면 ID를 1씩 증가시켜 순회하는 방식의 IDOR 시도가 쉬워진다는 이유로 `public_id`(UUID) 병행을 규정한다. 이 Response 문서도 동일 기준을 따른다.

| 리소스 성격 | 대상 예시 | `data`에 노출하는 식별자 |
|---|---|---|
| Master Data (저빈도 변경, 사용자가 직접 조작) | User(Guardian/CareTarget), Place | `public_id`(UUID 문자열). 내부 `id`(BIGINT/SERIAL)는 절대 응답에 포함하지 않는다 |
| Time-Series Data (대용량 이력, 조회 전용) | LocationHistory, VisitHistory, NotificationHistory, PredictionHistory, ChatHistory | 기존 내부 PK(BIGINT) 그대로 사용 가능. 소유권 검증은 Service 계층에서 별도 수행하므로(4.1절) ID 추측 자체의 위험도가 Master Data보다 낮음 |

> **GuardianTarget 예외**: GuardianTarget은 자체 `public_id` 컬럼을 두지 않는다. (guardian_id, target_id)가 ACTIVE 상태에서 유일하도록 이미 DB 제약(Partial UNIQUE)으로 강제되어 있어, 대상 User의 `public_id`만으로 관계를 충분히 식별할 수 있기 때문이다(근거: `DATABASE_DESIGN_GUIDE.md` §8/§13). 따라서 API 응답의 `careTargetId`는 GuardianTarget의 내부 id가 아니라 **대상 User의 public_id**를 그대로 사용한다.

> 8절의 `userId`, `careTargetId` 예시는 이 정책에 맞춰 UUID 문자열로 갱신했다(8.1, 8.2, 8.4, 8.5 참고). Backend 구현 시 `User.public_id`, `Place.public_id` 등 실제 컬럼이 준비되기 전까지는 이 정책을 임시로 우회하지 않고, DB 컬럼 추가를 선행 작업으로 처리한다.

---

## 2. Success Response 규칙

### 2.1 공통 구조

```json
{
  "success": true,
  "code": "USER_001",
  "message": "조회 성공",
  "data": {}
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| success | boolean | 항상 `true` |
| code | string | 성공 코드. 도메인 접두사 + 일련번호 (5절 참고) |
| message | string | 사용자에게 노출 가능한 성공 메시지 |
| data | object / array / null | 실제 응답 데이터. 반환할 데이터가 없으면 `null` |

### 2.2 목록(List) 응답 규칙

목록 API는 아래와 같이 `data` 내부에 `content`(목록)와 페이징 메타데이터를 함께 포함한다.

```json
{
  "success": true,
  "code": "TARGET_001",
  "message": "보호 대상자 목록 조회 성공",
  "data": {
    "content": [
      { "careTargetId": "3f2b1a10-9c4e-4a3b-8f2c-1d5e6a7b8c9d", "name": "김민준", "relation": "자녀" }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 3,
    "totalPages": 1
  }
}
```

### 2.3 성공 코드 예시 (도메인별)

| code | 상황 |
|---|---|
| AUTH_001 | 로그인 성공 |
| AUTH_002 | 토큰 재발급 성공 |
| AUTH_003 | 로그아웃 성공 |
| USER_001 | 사용자 정보 조회 성공 |
| USER_002 | 프로필 수정 성공 |
| TARGET_001 | 보호 대상자 목록/상세 조회 성공 |
| TARGET_002 | 보호 대상자 등록 성공(초대 승인 시점 포함, §8.5/§8.10 참고) |
| TARGET_003 | 초대 코드 생성 성공 |
| TARGET_004 | 승인 대기 목록 조회 성공 |
| TARGET_005 | 연결 요청 접수 성공 (관계 생성 아님, 승인 대기 상태) |
| TARGET_006 | 연결 요청 거절 처리 성공 |
| TARGET_008 | 관계 정보(relation/alias) 수정 성공 |
| TARGET_009 | 관계 해제 성공 |
| TARGET_010 | PRIMARY 위임 성공 |
| LOCATION_001 | 위치 조회 성공 |
| LOCATION_002 | 위치 전송 성공 |
| PLACE_001 | 장소(안심구역) 등록/조회 성공 |
| PLACE_002 | 장소(안심구역) 수정 성공 |
| PLACE_003 | 장소(안심구역) 삭제 성공 |
| NOTI_001 | 알림 조회 성공 |
| NOTI_002 | 알림 읽음 처리 성공 |
| AI_001 | AI 응답 생성 성공 |

---

## 3. Error Response 규칙

### 3.1 공통 구조

```json
{
  "success": false,
  "code": "AUTH_001",
  "message": "인증이 필요합니다",
  "data": null
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| success | boolean | 항상 `false` |
| code | string | 실패 코드 (5절 참고) |
| message | string | 사용자에게 노출 가능한 실패 메시지 |
| data | null | 항상 `null` 고정 |

> 주의: 에러 코드 `AUTH_001`은 5절 인증 도메인 에러 코드 표에서 "인증 필요(토큰 없음)"를 의미한다. 2.3절의 성공 코드 `AUTH_001`(로그인 성공)과 접두사는 같지만 성공/실패 코드 체계는 서로 다른 번호 공간을 사용하므로 혼동하지 않도록 별도 표로 관리한다 (5.1절 참고).

### 3.2 Validation 실패 시 상세 필드 제공

입력값 검증 실패처럼 어떤 필드가 왜 잘못되었는지 Frontend가 알아야 하는 경우, `data`를 `null`로 두는 대신 `errors` 배열을 message 하위가 아닌 **별도 최상위 필드**로 추가한다 (data는 원칙대로 null 유지).

```json
{
  "success": false,
  "code": "COMMON_002",
  "message": "입력값이 올바르지 않습니다",
  "data": null,
  "errors": [
    { "field": "phoneNumber", "reason": "전화번호 형식이 올바르지 않습니다" }
  ]
}
```

Frontend는 `errors` 필드가 존재하면 폼의 해당 필드 아래에 인라인 에러를, 없으면 `message`를 토스트/다이얼로그로 노출한다.

### 3.3 작성 기준

- `message`는 절대 서버 예외 클래스명·SQL·스택 트레이스를 포함하지 않는다 (안전한 오류 처리 원칙 상세: `docs/security/Security_Guide.md`, 예외 계층/로깅 구조 상세: Exception Handling Rule 문서).
- 동일한 원인이면 항상 같은 `code`를 반환해야 한다 (Frontend가 `code` 기준으로 분기하므로 code가 요청마다 달라지면 안 됨).
- 예상치 못한 서버 오류(500)는 사용자에게 원인을 설명하지 않고 `"일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."` 형태의 일반화된 메시지를 사용한다.

---

## 4. HTTP Status Code 기준

REST 관례상 HTTP Status와 Response Body의 `success`는 항상 일치해야 한다 (2xx ↔ `success: true`, 4xx/5xx ↔ `success: false`).

| Status | 사용 기준 | 프로젝트 적용 예시 |
|---|---|---|
| 200 OK | 조회, 수정, 로그인, 로그아웃 등 일반적인 성공 응답 | 위치 조회, 보호대상자 목록 조회, 알림 읽음 처리 |
| 201 Created | 새 리소스가 생성된 경우 | 보호 대상자 등록, 장소(안심구역) 등록, 위치 전송(신규 이력 생성) |
| 400 Bad Request | 요청 파라미터/바디의 형식 오류, Validation 실패 | 위도/경도 누락, 전화번호 형식 오류, 필수값 누락 |
| 401 Unauthorized | 인증 실패 — 토큰 없음, 만료, 위변조, 재로그인 필요 | JWT 만료로 API 호출 실패, Access Token 없이 요청 |
| 403 Forbidden | 인증은 되었으나 접근 권한이 없는 경우 | CareTarget이 Guardian 전용 API 호출, 다른 보호자의 보호대상자 리소스 접근, 관계 미매핑 |
| 404 Not Found | 요청한 리소스가 존재하지 않음 | 존재하지 않는 careTargetId 조회, 삭제된 장소 조회 |
| 409 Conflict | 리소스 상태 충돌, 중복 등록 | 이미 매핑된 보호자-대상자 관계 중복 등록, 이미 등록된 장소명 중복 |
| 500 Internal Server Error | 서버 내부 오류, 예상치 못한 예외 | DB 연결 실패, NPE 등 처리되지 않은 예외 |

### 4.1 401 vs 403 구분 (이 프로젝트의 핵심 판단 기준)

기획서의 3단계 인가 구조(인증 → Role 분기 → 리소스 관계 검증)에 따라 아래처럼 명확히 구분한다.

| 단계 | 실패 상황 | Status |
|---|---|---|
| 1단계: 인증(Authentication) | JWT 없음 / 만료 / 위변조 | **401** |
| 2단계: Role 인가(Authorization) | ROLE_CARE_TARGET이 `/api/guardian/**` 호출 | **403** |
| 3단계: 리소스 접근 제어 | Guardian이 자신과 매핑되지 않은 CareTarget의 위치 조회 시도 | **403** |

외부 연동 실패(AI Server 응답 없음, FCM 발송 실패)는 클라이언트 요청 자체는 정상이므로 500 계열로 처리하되, AI 관련은 별도 502 대신 500 + `AI_XXX` 코드로 통일한다(5.2절 참고).

---

## 5. Error Code 관리 규칙

### 5.1 코드 네이밍 규칙

```
{도메인}_{3자리 일련번호}
```

- 도메인은 대문자 스네이크/단일 단어 (`AUTH`, `USER`, `GUARDIAN`, `TARGET`, `LOCATION`, `PLACE`, `NOTI`, `AI`, `COMMON`)
- 일련번호는 001부터 3자리로 증가, 도메인별로 별도 관리(도메인이 다르면 001이 여러 번 존재할 수 있음)
- 신규 에러 코드 추가 시 반드시 이 문서(5절)와 Backend의 `ErrorCode` Enum(6.4절)에 **동시에** 추가한다. 둘 중 하나만 갱신되는 것을 방지하기 위해 PR 리뷰 체크리스트에 포함한다.

### 5.2 도메인별 Error Code

#### 공통 (COMMON)

| code | HTTP Status | 상황 |
|---|---|---|
| COMMON_001 | 500 | 알 수 없는 서버 오류 |
| COMMON_002 | 400 | 요청 값 Validation 실패 |
| COMMON_003 | 404 | 요청한 URI/리소스를 찾을 수 없음 |
| COMMON_004 | 405 | 지원하지 않는 HTTP Method |
| COMMON_005 | 429 | 요청 횟수 초과 (Rate Limit, AI/외부 API 보호용) |
| COMMON_006 | 403 | 요청자의 Role로는 접근할 수 없는 API 호출 (Guardian API 이외의 Role 전용 API) |
| COMMON_007 | 503 | Redis가 Source of Truth인 세션/보안 데이터(Refresh Token, JWT Blacklist) 접근 중 Redis 장애 발생 (Exception_Handling_Rule.md §10.4, 폴백 없이 명시적 실패 처리) |
| COMMON_008 | 409 | 동시 요청과 충돌해 트랜잭션이 직렬화 실패함(`PessimisticLockingFailureException`) — 서버 오류가 아니라 정상적인 동시성 경합이므로 클라이언트가 잠시 후 재시도하면 해결됨. 특정 도메인에 국한되지 않고 `REPEATABLE READ` 격리 수준을 쓰는 트랜잭션 전반에서 재발할 수 있어 `COMMON` 도메인에 둠 |

#### 인증 (AUTH)

| code | HTTP Status | 상황 |
|---|---|---|
| AUTH_001 | 401 | Access Token 없음 / 인증 필요 |
| AUTH_002 | 401 | Access Token 만료 |
| AUTH_003 | 401 | Access Token 위변조/서명 검증 실패 |
| AUTH_004 | 401 | Refresh Token 만료 또는 유효하지 않음 (재로그인 필요) |
| AUTH_005 | 401 | Google OAuth 인증 실패 |
| AUTH_006 | 401 | 로그아웃/탈퇴로 인한 JWT Blacklist 등록 토큰 |

#### 회원/사용자 (USER)

| code | HTTP Status | 상황 |
|---|---|---|
| USER_001 | 404 | 사용자를 찾을 수 없음 |
| USER_002 | 409 | 이미 가입된 사용자(OAuth 이메일 중복) |
| USER_003 | 400 | Role 미선택 상태(최초 로그인 후 Guardian/CareTarget 선택 필요) |
| USER_004 | 409 | 이미 Role이 확정된 사용자가 `PUT /api/auth/role`을 재호출(Role은 최초 1회만 선택 가능, 이후 임의 변경 불가 — 기획서 정책) |

#### 보호자 (GUARDIAN)

| code | HTTP Status | 상황 |
|---|---|---|
| GUARDIAN_001 | 403 | Guardian 권한이 아닌 사용자의 Guardian API 접근 |
| GUARDIAN_002 | 404 | 보호자 정보를 찾을 수 없음 |
| GUARDIAN_003 | 409 | Guardian 1인당 등록 가능 CareTarget 수(소프트 상한 10명, `DATABASE_DESIGN_GUIDE.md` §13/§14) 초과 |
| GUARDIAN_004 | 403 | 호출자가 해당 CareTarget의 ACTIVE PRIMARY Guardian이 아님(PRIMARY 전용 액션에 SUB가 접근) — PRIMARY 위임(§3.1), Place 등록·수정·삭제(§3.2)에서 공통 재사용 |
| GUARDIAN_005 | 403 | 위임 대상으로 지정한 Guardian이 해당 CareTarget의 ACTIVE SUB 상태가 아님(다른 CareTarget 소속이거나 PENDING/TERMINATED — Guardian 계정 자체가 존재하지 않는 경우는 `USER_001`을 그대로 씀, "존재 자체를 숨기려" 404로 대체하지 않는다는 기존 원칙과 동일하게 여기서도 "존재하지만 이 CareTarget과 무관/자격 없음"은 403으로 명확히 구분) |
| GUARDIAN_006 | 409 | PRIMARY 위임 대상으로 자기 자신(호출자 본인)을 지정 |

#### 보호대상자 (TARGET)

| code | HTTP Status | 상황 |
|---|---|---|
| TARGET_001 | 404 | 보호 대상자를 찾을 수 없음 |
| TARGET_002 | 403 | 요청자와 매핑되지 않은 보호대상자 리소스 접근 (관계 미매핑) |
| TARGET_003 | 409 | 이미 등록된 보호자-대상자 관계 |
| TARGET_004 | 400 | 초대 코드가 유효하지 않거나 만료됨 (DATABASE_DESIGN_GUIDE.md §7, 토큰 유효기간 10분 또는 입력 실패 5회 초과로 폐기됨) |
| TARGET_005 | 409 | CareTarget당 ACTIVE Guardian 정원(3명) 초과 (DATABASE_DESIGN_GUIDE.md §3.2/§7) |
| TARGET_006 | 409 | 이미 대기 중인 동일 초대 요청이 존재함 |
| TARGET_007 | 429 | 초대 코드 생성 요청 횟수 초과 (DATABASE_DESIGN_GUIDE.md §7, CareTarget 1인당 5회/일) — COMMON_005(AI/외부 API 보호용 Rate Limit)와 원인이 달라 별도 코드로 관리 |

#### 위치 정보 (LOCATION)

| code | HTTP Status | 상황 |
|---|---|---|
| LOCATION_001 | 400 | 위도/경도 값이 유효 범위를 벗어남 |
| LOCATION_002 | 404 | 조회 가능한 최신 위치 데이터 없음(Redis/DB 모두 없음) |
| LOCATION_003 | 403 | CareTarget이 아닌 사용자가 위치 전송 API 호출 |
| LOCATION_004 | 403 | CareTarget이 아닌 사용자가 현재 위치 공유(`POST /api/care-target/share/location`) API 호출 |

#### 장소 관리 (PLACE)

| code | HTTP Status | 상황 |
|---|---|---|
| PLACE_001 | 404 | 등록된 장소(안심구역)를 찾을 수 없음 |
| PLACE_002 | 409 | 동일 CareTarget 내 이름이 같거나, 실제 거리(Haversine)가 `place.duplicate-distance-meters`(기본 50m) 이내인 장소 중복 등록 — 좌표는 정확히 같지 않아도 반경 50m 이내면 동일 장소로 판단(GPS 오차 감안, 2026-08 확정, 실내/도심 GPS 오차가 10~30m를 넘기도 해 30m에서 50m로 완화) |
| PLACE_003 | 400 | GeoFence 반경 값이 유효 범위를 벗어남 |
| PLACE_004 | 409 | CareTarget 1인당 Place 등록 수(소프트 상한 15개, `DATABASE_DESIGN_GUIDE.md` §13/§14) 초과 |

#### 알림 (NOTI)

| code | HTTP Status | 상황 |
|---|---|---|
| NOTI_001 | 404 | 알림을 찾을 수 없음 |
| NOTI_002 | 500 | FCM 발송 실패 |
| NOTI_003 | 400 | FCM Token 미등록 기기 |

#### AI 서비스 (AI)

| code | HTTP Status | 상황 |
|---|---|---|
| AI_001 | 500 | AI Server(FastAPI) 응답 없음/타임아웃 |
| AI_002 | 500 | LLM API 호출 실패(OpenAI/Gemini 오류) |
| AI_003 | 404 | AI 예측 결과 없음(학습 데이터 부족) |
| AI_004 | 429 | LLM API 호출 한도 초과 |

#### 방문 히스토리 (VISIT)

`GET /api/guardian/history/*`(오늘 이동, 날짜별, 장소별 조회)는 `VisitHistory` 테이블 기준이며, 최신 위치 캐시가 대상인 `LOCATION` 도메인과 별개로 관리한다.

| code | HTTP Status | 상황 |
|---|---|---|
| VISIT_001 | 404 | 조회 조건(날짜/장소)에 해당하는 방문 이력이 없음 |
| VISIT_002 | 400 | 조회 기간(시작일-종료일) 값이 유효 범위를 벗어남 |

#### 도착 확인 (ARRIVAL)

`POST /api/care-target/arrival/check`, `GET /api/care-target/arrival/history` 전용 도메인.

| code | HTTP Status | 상황 |
|---|---|---|
| ARRIVAL_001 | 403 | CareTarget이 아닌 사용자가 도착 확인 API 호출 |
| ARRIVAL_002 | 400 | 현재 위치가 등록된 장소(GeoFence) 반경 밖이라 도착 확인이 성립하지 않음 |
| ARRIVAL_003 | 404 | 조회 가능한 도착 기록이 없음 |

#### 긴급 연락 (EMERGENCY)

`POST /api/care-target/emergency/call`, `/message`, `/location` 전용 도메인. 이 도메인은 서비스의 안전(Safety) 핵심 기능이므로, 실패 시에도 `NotificationHistory.status='FAILED'`로 반드시 이력을 남기고 재시도/에스컬레이션한다(fail-safe 원칙, 상세: `docs/security/Security_Guide.md`).

| code | HTTP Status | 상황 |
|---|---|---|
| EMERGENCY_001 | 403 | CareTarget이 아닌 사용자가 긴급 연락 API 호출 |
| EMERGENCY_002 | 400 | 등록된 보호자 연락처가 없어 전화/문자 발송 대상이 없음 |
| EMERGENCY_003 | 500 | 전화 연동(통신사 API 등) 또는 SMS 발송 자체가 실패 |

> EMERGENCY_003은 COMMON_001과 달리 일반화된 메시지로 덮지 않고, Frontend가 "다른 연락 수단(예: 위치 전송만이라도 재시도)"으로 즉시 폴백할 수 있도록 `code`를 그대로 노출한다(7.6절 사용자 메시지 표시 기준에 반영).

---

## 6. Backend 구현 기준 (Spring Boot)

### 6.1 패키지 구조 (Response 관련)

```
com.tracecare.backend
 ├─ common
 │   ├─ response
 │   │   ├─ ApiResponse.java          // 공통 응답 래퍼
 │   │   ├─ SuccessCode.java          // 성공 코드 Enum (2.3절과 1:1 매핑)
 │   │   ├─ PageResponse.java         // 목록 응답용 페이징 DTO
 │   │   └─ ErrorResponse.java        // Validation errors 포함 응답
 │   ├─ exception
 │   │   ├─ ErrorCode.java            // 에러 코드 Enum (5절과 1:1 매핑)
 │   │   ├─ CustomException.java      // 비즈니스 예외 베이스
 │   │   ├─ AuthException.java
 │   │   ├─ ResourceNotFoundException.java
 │   │   └─ GlobalExceptionHandler.java
 │   └─ security
 │       ├─ JwtAuthenticationFilter.java
 │       └─ CustomAccessDeniedHandler.java   // 403 응답 통일
 │       └─ CustomAuthenticationEntryPoint.java // 401 응답 통일
```

### 6.2 ApiResponse / 공통 DTO 설계

```java
@Getter
@Builder
public class ApiResponse<T> {

    private final boolean success;
    private final String code;
    private final String message;
    private final T data;

    public static <T> ApiResponse<T> success(SuccessCode code, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .code(code.getCode())
                .message(code.getMessage())
                .data(data)
                .build();
    }

    public static ApiResponse<Void> success(SuccessCode code) {
        return success(code, null);
    }

    public static ApiResponse<Void> error(ErrorCode code) {
        return ApiResponse.<Void>builder()
                .success(false)
                .code(code.getCode())
                .message(code.getMessage())
                .data(null)
                .build();
    }
}
```

`SuccessCode`는 `ErrorCode`(5.2절)와 동일한 패턴의 Enum이다. 성공 응답은 항상 2xx이므로 `HttpStatus` 필드는 두지 않고 `code`/`message`만 갖는다. 아래는 2.3절 표의 값을 그대로 반영한 예시다(신규 성공 코드가 필요하면 이 문서가 아니라 2.3절 표에 먼저 추가한다).

```java
@Getter
@RequiredArgsConstructor
public enum SuccessCode {
    AUTH_001("AUTH_001", "로그인 성공"),
    AUTH_002("AUTH_002", "토큰 재발급 성공"),
    AUTH_003("AUTH_003", "로그아웃 성공"),
    USER_001("USER_001", "사용자 정보 조회 성공"),
    USER_002("USER_002", "프로필 수정 성공"),
    TARGET_001("TARGET_001", "보호 대상자 목록/상세 조회 성공"),
    TARGET_002("TARGET_002", "보호 대상자 등록 성공"),
    TARGET_003("TARGET_003", "초대 코드 생성 성공"),
    TARGET_004("TARGET_004", "승인 대기 목록 조회 성공"),
    TARGET_005("TARGET_005", "연결 요청 접수 성공"),
    TARGET_006("TARGET_006", "연결 요청 거절 처리 성공"),
    TARGET_008("TARGET_008", "관계 정보(relation/alias) 수정 성공"),
    TARGET_009("TARGET_009", "관계 해제 성공"),
    TARGET_010("TARGET_010", "PRIMARY 위임 성공"),
    LOCATION_001("LOCATION_001", "위치 조회 성공"),
    LOCATION_002("LOCATION_002", "위치 전송 성공"),
    PLACE_001("PLACE_001", "장소(안심구역) 등록/조회 성공"),
    PLACE_002("PLACE_002", "장소(안심구역) 수정 성공"),
    PLACE_003("PLACE_003", "장소(안심구역) 삭제 성공"),
    NOTI_001("NOTI_001", "알림 조회 성공"),
    NOTI_002("NOTI_002", "알림 읽음 처리 성공"),
    AI_001("AI_001", "AI 응답 생성 성공");

    private final String code;
    private final String message;
}
```

```java
@Getter
@Builder
public class PageResponse<T> {
    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;

    public static <T> PageResponse<T> of(Page<T> page) {
        return PageResponse.<T>builder()
                .content(page.getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build();
    }
}
```

Controller에서는 반드시 `ApiResponse<T>`를 반환 타입으로 사용하고, 엔티티를 직접 반환하지 않는다(항상 Response DTO로 변환). 예:

```java
@GetMapping("/api/guardian/care-targets")
public ApiResponse<PageResponse<CareTargetResponse>> getCareTargets(
        @AuthenticationPrincipal CustomUserDetails user,
        Pageable pageable) {
    Page<CareTargetResponse> result = careTargetService.getCareTargets(user.getId(), pageable);
    return ApiResponse.success(SuccessCode.TARGET_001, PageResponse.of(result));
}
```

### 6.3 예외 처리와 Response 포맷의 매핑 계약

> 이 절은 **Response 포맷 준수 관점**만 다룬다. `CustomException` 계층 설계, `@RestControllerAdvice` 핸들러별 상세 구현, 로깅 레벨 기준은 Exception Handling Rule 문서가 담당하며 여기서 다시 정의하지 않는다.

이 문서가 Backend 구현에 요구하는 계약은 아래 3가지뿐이다.

1. **어떤 예외 경로를 거치든 최종 응답 Body는 반드시 `ApiResponse.error(ErrorCode)` 형식**이어야 한다 — Controller/Service 계층 예외(`GlobalExceptionHandler`), Filter 계층 예외(`AuthenticationEntryPoint`/`AccessDeniedHandler`) 모두 동일.
2. `ErrorCode`의 `httpStatus`와 5절 표의 HTTP Status가 항상 일치해야 한다(4절 원칙).
3. 401/403은 Spring Security 필터 단계에서 발생해 `GlobalExceptionHandler`(Controller/Service 계층)를 거치지 않으므로, `AuthenticationEntryPoint`/`AccessDeniedHandler`에서도 **같은 `ApiResponse.error()`**를 사용해 포맷이 갈라지지 않게 한다. 예:

```java
@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {
    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex)
            throws IOException {
        res.setStatus(HttpStatus.UNAUTHORIZED.value());
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(ErrorCode.AUTH_001)));
    }
}
```

상세 예외 계층(`CustomException`, `AuthException`, `ResourceNotFoundException` 등), `GlobalExceptionHandler`의 `@ExceptionHandler`별 처리 순서, Validation 예외의 `errors` 필드 조립 로직은 Exception Handling Rule 문서를 따른다.

### 6.4 ErrorCode Enum 예시

```java
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // Common
    COMMON_001("COMMON_001", HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
    COMMON_002("COMMON_002", HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다"),

    // Auth
    AUTH_001("AUTH_001", HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
    AUTH_002("AUTH_002", HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다"),

    // Target
    TARGET_002("TARGET_002", HttpStatus.FORBIDDEN, "접근 권한이 없는 보호대상자입니다"),

    // AI
    AI_001("AI_001", HttpStatus.INTERNAL_SERVER_ERROR, "AI 서버 응답이 지연되고 있습니다"),

    // Emergency
    EMERGENCY_003("EMERGENCY_003", HttpStatus.INTERNAL_SERVER_ERROR, "긴급 연락 발송에 실패했습니다. 다시 시도해주세요");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
```

> `code` 필드는 원래 예시에서 누락되어 있었으나(§6.2 `ApiResponse.error()`가 `code.getCode()`를 호출하는 것과 모순), `SuccessCode`와 동일한 패턴으로 보완했다.

---

## 7. Frontend 처리 기준 (Flutter)

### 7.1 공통 파서

모든 API 호출은 `Dio`(또는 `http`) 인터셉터를 거쳐 아래 모델로 파싱한다.

```dart
class ApiResponse<T> {
  final bool success;
  final String code;
  final String message;
  final T? data;

  ApiResponse({
    required this.success,
    required this.code,
    required this.message,
    this.data,
  });

  factory ApiResponse.fromJson(
    Map<String, dynamic> json,
    T Function(dynamic) fromJsonT,
  ) {
    return ApiResponse(
      success: json['success'] as bool,
      code: json['code'] as String,
      message: json['message'] as String,
      data: json['data'] != null ? fromJsonT(json['data']) : null,
    );
  }
}
```

### 7.2 성공 응답 처리 방식

- `success: true`인 경우에만 `data`를 파싱해 화면 상태(State)에 반영한다.
- 목록 API는 `data.content`를 리스트로, 나머지 페이징 필드는 Pagination 컨트롤러 상태로 별도 저장한다.
- `message`는 등록/수정/삭제처럼 사용자 액션에 대한 결과 확인이 필요한 경우에만 스낵바(SnackBar)로 노출한다(단순 GET 조회에는 노출하지 않음).

### 7.3 API Error 처리 방식

Dio의 `Interceptor`에서 `success: false` 또는 4xx/5xx 응답을 가로채 공통 에러 핸들러로 위임한다.

```dart
class ApiErrorInterceptor extends Interceptor {
  @override
  void onError(DioException err, ErrorInterceptorHandler handler) async {
    final response = err.response;

    if (response == null) {
      // 네트워크 자체 실패 (타임아웃, 서버 무응답)
      AppErrorHandler.showNetworkError();
      return handler.next(err);
    }

    final body = response.data as Map<String, dynamic>;
    final code = body['code'] as String? ?? 'COMMON_001';
    final message = body['message'] as String? ?? '알 수 없는 오류가 발생했습니다';

    switch (response.statusCode) {
      case 401:
        await AuthErrorHandler.handleUnauthorized(code); // 7.5절 참고
        break;
      case 403:
        AppErrorHandler.showForbidden(message);
        break;
      case 404:
        AppErrorHandler.showToast(message);
        break;
      case 409:
        AppErrorHandler.showToast(message);
        break;
      default:
        AppErrorHandler.showToast(message); // 400, 500 등
    }
    return handler.next(err);
  }
}
```

### 7.4 HTTP Status별 처리 기준

| Status | Flutter 처리 |
|---|---|
| 200 / 201 | 정상 데이터 파싱 후 화면 갱신, 필요 시 성공 토스트 |
| 400 | 폼 화면이면 `errors` 필드를 필드별 인라인 에러로 표시, 아니면 토스트 |
| 401 | 7.5절 Token 만료 처리 로직으로 위임 |
| 403 | 접근 불가 다이얼로그 표시 후 이전 화면(또는 Role에 맞는 홈)으로 이동 |
| 404 | "정보를 찾을 수 없습니다" 토스트, 목록 새로고침 |
| 409 | 충돌 안내 다이얼로그(예: "이미 등록된 장소입니다") |
| 500 | 공통 에러 다이얼로그 + 재시도 버튼 |

### 7.5 Token 만료 처리

`code`가 `AUTH_002`(Access Token 만료)인지, `AUTH_004`(Refresh Token 만료)인지에 따라 분기한다.

```dart
class AuthErrorHandler {
  static Future<void> handleUnauthorized(String code) async {
    if (code == 'AUTH_002') {
      // Access Token만 만료 → Refresh Token으로 재발급 시도
      final refreshed = await AuthRepository.refreshAccessToken();
      if (refreshed) {
        return; // Dio 인터셉터에서 원래 요청 재시도
      }
    }
    // AUTH_004(Refresh 만료) 또는 재발급 실패 → 강제 로그아웃
    await AuthRepository.clearTokens();
    NavigationService.goToLoginAndClearStack();
    AppErrorHandler.showToast('로그인이 만료되었습니다. 다시 로그인해주세요.');
  }
}
```

- Access Token 재발급(`/api/auth/refresh`)은 Dio 인터셉터 안에서 **1회만** 자동 시도한다. 재발급 API 자체가 401을 반환하면 즉시 로그아웃 처리하여 무한 루프를 방지한다.
- 동시에 여러 API가 401을 받는 상황(화면 진입 시 다중 호출)을 고려해 재발급 요청은 Lock/큐로 중복 호출을 막는다.

### 7.6 사용자 메시지 표시 기준

| 상황 | UI 컴포넌트 |
|---|---|
| 단순 조회 실패 (404, 네트워크 오류) | 화면 내 인라인 에러 상태 + 재시도 버튼 |
| 폼 입력 오류 (400 + errors) | 필드 아래 인라인 텍스트 |
| 권한 오류 (403) | Dialog (확인 버튼만) |
| 인증 만료 (401 → 로그아웃) | Toast + 자동 화면 전환 |
| 서버 오류 (500) | Dialog + "다시 시도" 버튼 |
| AI 응답 지연 (AI_001) | 로딩 상태 유지 + "AI 응답이 지연되고 있어요" 안내 텍스트 (다이얼로그로 끊지 않음) |
| 긴급 연락 실패 (EMERGENCY_003) | Dialog + "다시 시도" 버튼과 함께 대체 연락 수단(예: 위치만 재전송) 버튼을 동시에 노출 — 일반 500과 동일한 단순 재시도 UI로 처리하지 않는다 |

---

## 8. 실제 프로젝트 API 적용 예시

### 8.1 로그인 (`POST /api/auth/oauth/login`)

Google OAuth로 1차 인증 후, Spring Boot가 자체 JWT(Access/Refresh)를 발급한다.

**Request**
```json
{
  "idToken": "google-id-token-string",
  "fcmToken": "device-fcm-token"
}
```

**Success (200)**
```json
{
  "success": true,
  "code": "AUTH_001",
  "message": "로그인 성공",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIs...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIs...",
    "role": "GUARDIAN",
    "userId": "a1b2c3d4-e5f6-47a8-9b0c-1d2e3f4a5b6c",
    "roleSelected": true
  }
}
```

**Success — 신규 사용자, 첫 로그인이라 Role 미선택 (200)**

토큰은 Role 확정 여부와 무관하게 항상 정상 발급한다 — `PUT /api/auth/role`도 인증이 필요한 API이므로, 이 시점에 토큰을 주지 않으면 클라이언트가 그 API 자체를 호출할 수 없다. `success: true`, `code: AUTH_001`은 그대로 두고 `role`이 `null`, `roleSelected`가 `false`인 것으로 "Role 미선택" 상태를 나타낸다. `USER_003`은 이 로그인 응답에서 쓰지 않는다(용도: `PUT /api/auth/role` 외의 인증된 API를 Role 미확정 사용자가 호출할 때 등, 이 문서 5.2절 표 그대로 400).

```json
{
  "success": true,
  "code": "AUTH_001",
  "message": "로그인 성공",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIs...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIs...",
    "role": null,
    "userId": "a1b2c3d4-e5f6-47a8-9b0c-1d2e3f4a5b6c",
    "roleSelected": false
  }
}
```
Flutter는 `roleSelected: false`를 보고 Role 선택 화면으로 이동시키고, 발급받은 토큰으로 `PUT /api/auth/role`을 호출해 Role을 확정한다. (별도 회원가입 폼 없이 OAuth 최초 로그인 = 자동 가입, Role 선택으로 온보딩 완료라는 이 프로젝트의 특성을 반영한 흐름이다.)

**Error — Google 인증 실패 (401)**
```json
{ "success": false, "code": "AUTH_005", "message": "Google 인증에 실패했습니다", "data": null }
```

### 8.2 회원가입(=최초 로그인/Role 확정) (`PUT /api/auth/role`)

이 프로젝트는 이메일/비밀번호 회원가입이 없고 OAuth 최초 로그인 시 자동 가입되므로, "회원가입"에 해당하는 절차는 Role 확정 단계다.

**Request**
```json
{ "role": "CARE_TARGET", "name": "김민준", "birthDate": "2016-03-02" }
```

**Success (201)**
```json
{
  "success": true,
  "code": "USER_002",
  "message": "회원 정보 등록이 완료되었습니다",
  "data": { "userId": "b2c3d4e5-f6a7-48b9-0c1d-2e3f4a5b6c7d", "role": "CARE_TARGET" }
}
```

### 8.3 위치 전송 (`POST /api/care-target/location`, CareTarget 전용)

**Request**
```json
{ "latitude": 37.501234, "longitude": 127.039876, "recordedAt": "2026-08-06T09:15:00Z" }
```

**Success (201)** — Redis(최신 위치)와 PostgreSQL(LocationHistory) 동시 저장
```json
{
  "success": true,
  "code": "LOCATION_002",
  "message": "위치 전송 성공",
  "data": { "locationId": 88231, "recordedAt": "2026-08-06T09:15:00Z" }
}
```

**Error — Guardian 계정이 이 API를 호출한 경우 (403)**
```json
{ "success": false, "code": "LOCATION_003", "message": "보호대상자만 위치를 전송할 수 있습니다", "data": null }
```

> WebSocket(`/ws/care-target/location`)으로 실시간 전송하는 경우에는 위 REST 규격을 그대로 쓰지 않고, 메시지 프레임에 `{ "type": "LOCATION_UPDATE", "payload": {...}, "timestamp": "..." }` 형태의 경량 포맷을 사용한다. 이는 REST Response 표준의 적용 범위 밖(1.2절)임을 Frontend·Backend 모두 인지해야 한다.

### 8.4 위치 조회 (`GET /api/guardian/location/current`, Guardian 전용)

**Success (200)**
```json
{
  "success": true,
  "code": "LOCATION_001",
  "message": "위치 조회 성공",
  "data": {
    "careTargetId": "b2c3d4e5-f6a7-48b9-0c1d-2e3f4a5b6c7d",
    "latitude": 37.501234,
    "longitude": 127.039876,
    "recordedAt": "2026-08-06T09:15:00Z",
    "source": "REDIS_CACHE"
  }
}
```

**Error — 자신과 매핑되지 않은 보호대상자 조회 시도 (403, 3단계 리소스 접근 제어)**
```json
{ "success": false, "code": "TARGET_002", "message": "접근 권한이 없는 보호대상자입니다", "data": null }
```

**Error — 아직 위치 데이터가 없는 경우 (404)**
```json
{ "success": false, "code": "LOCATION_002", "message": "조회 가능한 위치 정보가 없습니다", "data": null }
```

### 8.5 보호자 연결 — 초대 코드 생성 → 연결 요청 → 승인 대기 조회 → 승인

> 관계 생성은 직접 INSERT가 아니라 초대(Invitation)+CareTarget 승인 절차로만 이뤄진다(`DATABASE_DESIGN_GUIDE.md` §3.2/§7 확정, `API_Specification.md` §3.1/§4.7). 아래 4단계는 실제 구현(`GuardianInviteService`/`GuardianTargetService`)의 응답 형태를 그대로 옮긴 것이다 — 문서를 먼저 설계하지 않고 코드에 맞춰 재작성했다.

**1) CareTarget: 초대 코드 생성 (`POST /api/care-target/guardians/invite-code`, 요청 본문 없음)**

Success (200)
```json
{
  "success": true,
  "code": "TARGET_003",
  "message": "초대 코드 생성 성공",
  "data": { "inviteCode": "L74A5V5R", "expiresAt": "2026-08-06T08:42:10Z" }
}
```

Error — 코드 생성 Rate Limit(5회/일) 초과 (429)
```json
{ "success": false, "code": "TARGET_007", "message": "초대 코드 생성 횟수를 초과했습니다", "data": null }
```

**2) Guardian: 코드 입력 → 연결 요청 (`POST /api/guardian/care-targets`)**

Request
```json
{ "inviteCode": "L74A5V5R" }
```

Success (200) — 관계가 이 시점에 생성되는 것은 아니고, 승인 대기 상태로 접수될 뿐이다
```json
{
  "success": true,
  "code": "TARGET_005",
  "message": "연결 요청 접수 성공",
  "data": { "careTargetId": "3f2b1a10-9c4e-4a3b-8f2c-1d5e6a7b8c9d", "name": "김민준", "status": "PENDING" }
}
```

Error — 코드가 유효하지 않거나 만료됨 (400)
```json
{ "success": false, "code": "TARGET_004", "message": "초대 코드가 유효하지 않거나 만료되었습니다", "data": null }
```

Error — 이미 같은 대상에게 대기 중인 요청이 있음 (409)
```json
{ "success": false, "code": "TARGET_006", "message": "이미 대기 중인 연결 요청이 있습니다", "data": null }
```

**3) CareTarget: 승인 대기 목록 조회 (`GET /api/care-target/guardians/pending`)**

Success (200)
```json
{
  "success": true,
  "code": "TARGET_004",
  "message": "승인 대기 목록 조회 성공",
  "data": {
    "content": [ { "guardianId": "9531018e-3f0a-4a6d-809e-0fbe3e4623b9", "name": "이수진" } ],
    "page": 0,
    "size": 1,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

**4) CareTarget: 승인 (`POST /api/care-target/guardians/pending/{guardianId}/approve`, 요청 본문 없음)**

`{guardianId}` = 위 대기 목록에 나타난 요청자 Guardian의 `public_id`(원본 초대 토큰이 아님).

Success (200) — `GuardianTarget` 행이 이 시점에 실제로 생성되므로 §3.1의 등록 성공 코드(`TARGET_002`)를 재사용한다
```json
{
  "success": true,
  "code": "TARGET_002",
  "message": "보호 대상자 등록 성공",
  "data": { "guardianId": "9531018e-3f0a-4a6d-809e-0fbe3e4623b9", "guardianRole": "PRIMARY", "relation": null, "alias": null }
}
```

`relation`/`alias`는 승인 직후 항상 `null`이며, 이후 Guardian이 `PUT /api/guardian/care-targets/{id}`로 별도 설정한다.

Error — CareTarget당 ACTIVE Guardian 정원(3명) 초과 (409)
```json
{ "success": false, "code": "TARGET_005", "message": "보호자 등록 정원을 초과했습니다", "data": null }
```

### 8.6 알림 전송 (내부 트리거 → FCM → 이력 저장 → Guardian 조회)

알림 발송 자체는 `/internal/fcm/send`(서버 내부 API, 클라이언트 미노출)에서 처리되며, Frontend는 결과를 `GET /api/guardian/notifications`로 조회한다.

**Success (200)**
```json
{
  "success": true,
  "code": "NOTI_001",
  "message": "알림 조회 성공",
  "data": {
    "content": [
      {
        "notificationId": 5510,
        "type": "ARRIVAL",
        "title": "도착 알림",
        "body": "김민준님이 '학교'에 도착했습니다",
        "isRead": false,
        "sentAt": "2026-08-06T08:32:10Z"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

**Error — FCM 발송 자체가 실패한 경우 (서버 로그/내부 처리용, 클라이언트에는 알림이 누락되어 보일 뿐 별도 응답 없음)**
```json
{ "success": false, "code": "NOTI_002", "message": "알림 발송에 실패했습니다", "data": null }
```
이 에러는 사용자에게 직접 전달되지 않고 서버 로그와 재발송 큐 처리에 사용되므로, `NotificationHistory`의 상태 컬럼(`FAILED`)으로 남긴다. GeoFence 도착 감지 후 5분 미응답 시 재알림 로직(기획서 8.3절 참고)도 동일한 `NOTI_002` 기준으로 재시도 여부를 판단한다.

### 8.7 방문 히스토리 (`GET /api/guardian/history/date`, Guardian 전용)

**Success (200)**
```json
{
  "success": true,
  "code": "VISIT_001",
  "message": "방문 히스토리 조회 성공",
  "data": {
    "content": [
      { "placeName": "학교", "arrivalTime": "2026-08-06T08:35:00Z", "departureTime": "2026-08-06T15:20:00Z", "stayMinutes": 405, "isRegisteredPlace": true }
    ],
    "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
  }
}
```

**Error — 조회 조건에 해당하는 방문 이력 없음 (404)**
```json
{ "success": false, "code": "VISIT_001", "message": "조회 가능한 방문 이력이 없습니다", "data": null }
```
> `VISIT_001`은 5.2절 정의상 성공/실패 번호 공간이 분리되므로(5.1절), 조회 성공 코드와 404 에러 코드가 같은 문자열을 공유하는 것처럼 보이지 않도록 Backend 구현 시 `SuccessCode.VISIT_001`과 `ErrorCode.VISIT_001`을 별도 Enum으로 관리한다(3.1절 주의 사항과 동일한 패턴).

### 8.8 도착 확인 (`POST /api/care-target/arrival/check`, CareTarget 전용)

**Request**
```json
{ "placeId": "c3d4e5f6-a7b8-49c0-1d2e-3f4a5b6c7d8e", "latitude": 37.501234, "longitude": 127.039876 }
```

**Success (201)**
```json
{ "success": true, "code": "ARRIVAL_001", "message": "도착 확인 성공", "data": { "arrivalId": 4021, "placeName": "학교", "confirmedAt": "2026-08-06T08:35:00Z" } }
```

**Error — 등록 장소 반경 밖에서 요청 (400)**
```json
{ "success": false, "code": "ARRIVAL_002", "message": "등록된 장소 범위를 벗어나 도착 확인이 불가능합니다", "data": null }
```

### 8.9 긴급 연락 (`POST /api/care-target/emergency/call`, CareTarget 전용)

**Success (200)** — 통신사 API/SMS 연동을 통해 보호자에게 즉시 연락
```json
{ "success": true, "code": "NOTI_001", "message": "보호자에게 긴급 연락을 전송했습니다", "data": { "notificationId": 5599, "guardianContacted": true } }
```

**Error — 연동 자체가 실패 (500, fail-safe 대상)**
```json
{ "success": false, "code": "EMERGENCY_003", "message": "긴급 연락 발송에 실패했습니다. 다시 시도해주세요", "data": null }
```
> `EMERGENCY_003`은 재시도 로직(Frontend 자동 재시도 1회 + 실패 시 대체 수단 안내)과 반드시 함께 구현한다. 안전 기능이므로 COMMON_001의 일반화된 재시도 문구로 대체하지 않는다(안전 관련 fail-safe 원칙, `docs/security/Security_Guide.md` 및 `.claude/rules/security.md` 4절 참고).

---

## 부록: 체크리스트 (PR 리뷰용)

- [ ] Controller가 `ApiResponse<T>`를 반환하는가 (엔티티 직접 반환 금지)
- [ ] 새 에러 상황에 5절 표 기준 `ErrorCode`가 존재하는가, 없다면 문서와 Enum에 함께 추가했는가
- [ ] 401/403을 커스텀 `EntryPoint`/`AccessDeniedHandler`로 처리해 포맷이 동일한가
- [ ] 목록 API가 `PageResponse` 구조를 따르는가
- [ ] `message`에 예외 메시지·스택 트레이스가 그대로 노출되지 않는가
- [ ] Flutter에서 새 API를 추가할 때 공통 `ApiResponse.fromJson`으로 파싱하는가 (개별 파서 작성 금지)
- [ ] Master Data(User/Place) 식별자를 응답에 노출할 때 내부 PK가 아닌 `public_id`(UUID)를 사용했는가, GuardianTarget(`careTargetId`)은 대상 User의 `public_id`를 쓰고 있는가 (1.5절)
- [ ] EMERGENCY 등 안전(Safety) 관련 API의 실패가 `NotificationHistory.status='FAILED'` 등으로 반드시 이력이 남는가 (fail-open 금지)
