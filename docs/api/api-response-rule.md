# API Response Standard

> 이 문서는 Backend-Frontend 간 API 응답 규격을 왜 이렇게 설계했는지, 구조·대안·트레이드오프까지 상세히 작성한다.

**프로젝트**: 아이·노인 케어 위치추적 알림 시스템 (GIS 기반 케어 서비스)
**대상 독자**: Backend(Spring Boot) 개발자, Frontend(Flutter) 개발자
**목적**: Backend와 Frontend 간 API 요청/응답 규격을 통일하여 개발 생산성과 유지보수성을 확보한다.
**적용 범위**: `/api/**` (Guardian API, CareTarget API, Auth API). `/internal/**` 내부 API는 서버 간 통신이므로 본 문서의 클라이언트 처리 규칙(4절, 7절)은 적용 대상이 아니다.

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
      { "careTargetId": 12, "name": "김민준", "relation": "자녀" }
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
| TARGET_002 | 보호 대상자 등록 성공 |
| LOCATION_001 | 위치 조회 성공 |
| LOCATION_002 | 위치 전송 성공 |
| PLACE_001 | 장소(안심구역) 등록/조회 성공 |
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

- `message`는 절대 서버 예외 클래스명·SQL·스택 트레이스를 포함하지 않는다 (9절 보안 원칙, 안전한 오류 처리).
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

#### 보호자 (GUARDIAN)

| code | HTTP Status | 상황 |
|---|---|---|
| GUARDIAN_001 | 403 | Guardian 권한이 아닌 사용자의 Guardian API 접근 |
| GUARDIAN_002 | 404 | 보호자 정보를 찾을 수 없음 |

#### 보호대상자 (TARGET)

| code | HTTP Status | 상황 |
|---|---|---|
| TARGET_001 | 404 | 보호 대상자를 찾을 수 없음 |
| TARGET_002 | 403 | 요청자와 매핑되지 않은 보호대상자 리소스 접근 (관계 미매핑) |
| TARGET_003 | 409 | 이미 등록된 보호자-대상자 관계 |

#### 위치 정보 (LOCATION)

| code | HTTP Status | 상황 |
|---|---|---|
| LOCATION_001 | 400 | 위도/경도 값이 유효 범위를 벗어남 |
| LOCATION_002 | 404 | 조회 가능한 최신 위치 데이터 없음(Redis/DB 모두 없음) |
| LOCATION_003 | 403 | CareTarget이 아닌 사용자가 위치 전송 API 호출 |

#### 장소 관리 (PLACE)

| code | HTTP Status | 상황 |
|---|---|---|
| PLACE_001 | 404 | 등록된 장소(안심구역)를 찾을 수 없음 |
| PLACE_002 | 409 | 동일 좌표/이름의 장소 중복 등록 |
| PLACE_003 | 400 | GeoFence 반경 값이 유효 범위를 벗어남 |

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

---

## 6. Backend 구현 기준 (Spring Boot)

### 6.1 패키지 구조 (Response 관련)

```
com.gis.care
 ├─ common
 │   ├─ response
 │   │   ├─ ApiResponse.java          // 공통 응답 래퍼
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

### 6.3 GlobalExceptionHandler 연동

모든 예외는 컨트롤러에서 직접 try-catch 하지 않고, `CustomException`을 던진 뒤 `@RestControllerAdvice`에서 일괄 변환한다. 이렇게 하면 5절의 Error Code 표와 실제 응답이 항상 1:1로 매핑된다.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("[{}] {}", code.getCode(), e.getMessage()); // 내부 로그에는 상세 기록
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.error(code));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<FieldErrorDetail> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldErrorDetail(f.getField(), f.getDefaultMessage()))
                .toList();
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCode.COMMON_002, errors));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.GUARDIAN_001)); // 상황에 맞는 코드로 세분화
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        log.error("Unhandled exception", e); // 스택 트레이스는 로그에만 남기고 응답에는 노출 금지
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.COMMON_001));
    }
}
```

**핵심 원칙**: 401/403은 Spring Security 레벨(Filter 단계)에서 발생하므로 `GlobalExceptionHandler`가 아니라 `AuthenticationEntryPoint`(401)와 `AccessDeniedHandler`(403)에서 동일한 `ApiResponse` 포맷으로 응답한다. 이 두 경로에서도 반드시 같은 `ApiResponse.error()`를 사용해 형식이 갈라지지 않도록 한다.

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

### 6.4 ErrorCode Enum 예시

```java
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // Common
    COMMON_001(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
    COMMON_002(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다"),

    // Auth
    AUTH_001(HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
    AUTH_002(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다"),

    // Target
    TARGET_002(HttpStatus.FORBIDDEN, "접근 권한이 없는 보호대상자입니다"),

    // AI
    AI_001(HttpStatus.INTERNAL_SERVER_ERROR, "AI 서버 응답이 지연되고 있습니다");

    private final HttpStatus httpStatus;
    private final String message;
}
```

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
    "userId": 101,
    "roleSelected": true
  }
}
```

**Error — 신규 사용자, 첫 로그인이라 Role 미선택 (200, 별도 흐름 안내)**
```json
{
  "success": false,
  "code": "USER_003",
  "message": "역할(보호자/보호대상자) 선택이 필요합니다",
  "data": null
}
```
Flutter는 이 코드를 받으면 Role 선택 화면으로 이동시키고, 이후 `PUT /api/auth/role`로 Role을 확정한다. (별도 회원가입 폼 없이 OAuth 최초 로그인 = 자동 가입, Role 선택으로 온보딩 완료라는 이 프로젝트의 특성을 반영한 흐름이다.)

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
  "data": { "userId": 205, "role": "CARE_TARGET" }
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
    "careTargetId": 205,
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

### 8.5 보호자 관리 — 보호 대상자 등록 (`POST /api/guardian/care-targets`)

**Request**
```json
{ "careTargetUserId": 205, "relation": "자녀", "alias": "우리 아이" }
```

**Success (201)**
```json
{
  "success": true,
  "code": "TARGET_002",
  "message": "보호 대상자 등록 성공",
  "data": { "careTargetId": 12, "name": "김민준", "relation": "자녀" }
}
```

**Error — 이미 등록된 관계 (409)**
```json
{ "success": false, "code": "TARGET_003", "message": "이미 등록된 보호대상자입니다", "data": null }
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

---

## 부록: 체크리스트 (PR 리뷰용)

- [ ] Controller가 `ApiResponse<T>`를 반환하는가 (엔티티 직접 반환 금지)
- [ ] 새 에러 상황에 5절 표 기준 `ErrorCode`가 존재하는가, 없다면 문서와 Enum에 함께 추가했는가
- [ ] 401/403을 커스텀 `EntryPoint`/`AccessDeniedHandler`로 처리해 포맷이 동일한가
- [ ] 목록 API가 `PageResponse` 구조를 따르는가
- [ ] `message`에 예외 메시지·스택 트레이스가 그대로 노출되지 않는가
- [ ] Flutter에서 새 API를 추가할 때 공통 `ApiResponse.fromJson`으로 파싱하는가 (개별 파서 작성 금지)
