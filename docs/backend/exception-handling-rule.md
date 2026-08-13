# Backend Exception Handling Rule

프로젝트: 아이·노인 케어 위치추적 알림 시스템 (GIS)
문서 위치: `docs/backend/exception-handling-rule.md`
담당 서버: Spring Boot Backend (Guardian / CareTarget 관리, 인증, 위치, 알림)
버전: v1.0 (작성일 2026-08-06)

> 이 문서는 **Backend 내부 예외 처리 구조**만 정의한다.
> API 응답 포맷은 **API Response Rule** 문서, 인증/인가·JWT·OAuth2 구조는 **Spring Security Guide** 문서를 참고한다. 본 문서는 두 문서를 전제로, "예외가 발생했을 때 서버 내부에서 어떻게 잡고, 어떤 예외 클래스로 분류하고, 어떻게 로깅할 것인가"만 다룬다.

---

## 목차

1. 문서 목적 및 범위
2. 예외 처리 원칙
3. GlobalExceptionHandler 기반 중앙 처리 구조
4. Exception 계층 구조
5. Custom Exception 설계 기준
6. Validation Exception 처리
7. Business Exception 처리
8. Authentication / Authorization Exception 처리
9. External Service Exception 처리
10. Database Exception 처리
11. Transaction 처리 기준
12. Logging 보안 기준
13. API Response Rule과의 연계 방식
14. 구현 체크리스트

---

## 1. 문서 목적 및 범위

### 1.1 목적

Spring Boot Backend에서 발생하는 모든 예외를 **한 곳(GlobalExceptionHandler)에서 일관되게 처리**하기 위한 기준을 정의한다. 목적은 다음 세 가지다.

- 컨트롤러/서비스 계층에 흩어진 `try-catch`를 제거하여 비즈니스 로직 가독성을 높인다.
- 예외 종류에 따라 정해진 HTTP Status와 Error Code로 응답하되, 응답 포맷 자체는 API Response Rule을 따른다.
- 정부 소프트웨어 개발보안 가이드(예외처리 항목: 오류 메시지를 통한 정보노출, 오류 상황 대응 부재, 부적절한 예외 처리)를 준수하여 스택 트레이스·내부 구조가 클라이언트(Flutter)로 노출되지 않도록 한다.

### 1.2 프로젝트 환경 반영 사항

이 프로젝트는 Backend 단독 서비스가 아니라 아래 구조로 구성되어 있으므로, 예외 설계 시 서비스 간 경계를 고려한다.

| 구성 요소 | 역할 | 본 문서와의 관계 |
|---|---|---|
| Spring Boot (Backend) | 인증, Guardian/CareTarget 관리, 위치, 알림, WebSocket | 본 문서의 직접 적용 대상 |
| FastAPI (AI Server) | 위치 예측, LLM 기반 AI Care Assistant | Backend 입장에서는 **External Service**로 취급 (9장 참고) |
| PostgreSQL | 영구 저장소 | Database Exception 처리 대상 (10장) |
| Redis | 캐시, JWT Blacklist, Refresh Token, FCM Token | 외부 인프라 예외로 취급하되 캐시 실패가 서비스 전체 장애로 번지지 않도록 설계 |
| Google OAuth2 / Google Maps·Places API / FCM | 외부 연동 서비스 | External Service Exception 대상 |
| Flutter Client | 최종 예외 응답 수신 주체 | 응답 포맷은 API Response Rule 참고 |

### 1.3 작성 범위 / 제외 범위

**작성 범위**: Exception 계층 설계, GlobalExceptionHandler 구조, Custom Exception, Validation/Business/Auth/External/DB Exception 분류, Transaction 예외 처리 기준, Logging 보안 기준.

**작성 제외 (타 문서 담당)**

| 항목 | 담당 문서 |
|---|---|
| API 공통 응답 포맷, 성공/실패 JSON 구조, HTTP Status 매핑 표, Error Code 관리 정책 | API Response Rule |
| JWT 발급/검증 구조, OAuth2(Google) 인증 흐름, Spring Security Filter Chain, Role(Guardian/CareTarget) 기반 인가 정책 | Spring Security Guide |

---

## 2. 예외 처리 원칙

| 원칙 | 설명 |
|---|---|
| 중앙 집중 처리 | 모든 예외는 컨트롤러가 아닌 `@RestControllerAdvice` 기반 GlobalExceptionHandler에서 최종 처리한다. 컨트롤러/서비스에서 응답을 직접 조립하지 않는다. |
| 구체적 예외 처리 | `catch (Exception e)` 같은 광범위한 예외 처리를 지양하고, 발생 가능한 예외를 구체적으로 분류해 처리한다 (표준프레임워크 보안개발가이드 "부적절한 예외 처리" 항목 반영). |
| Fail-Fast + 명시적 예외 | 비즈니스 규칙 위반은 반환값(boolean, null 등)으로 표현하지 않고 Custom Exception을 throw하여 흐름을 명확히 한다. |
| 예외 삼키지 않기 | 빈 catch 블록, 로깅 없는 예외 무시를 금지한다 (보안가이드 "오류 상황 대응 부재" 항목 반영). |
| 내부 정보 비노출 | 클라이언트 응답에는 스택 트레이스, 예외 클래스 전체 경로, SQL, 내부 파일 경로 등을 포함하지 않는다 (보안가이드 "오류 메시지를 통한 정보노출" 항목 반영). |
| 계층별 책임 분리 | Repository/외부 연동 계층에서 발생한 저수준 예외(SQLException, WebClient 예외 등)는 Service 계층에서 도메인 예외로 변환 후 상위로 전파한다. Presentation 계층은 예외를 직접 생성하지 않는다. |
| 일관된 응답 | 예외 종류에 관계없이 최종 응답 포맷은 API Response Rule의 실패 응답 구조를 따른다. |

---

## 3. GlobalExceptionHandler 기반 중앙 처리 구조

### 3.1 구조 개요

```
Controller
   │  (예외 throw)
   ▼
Service / Repository / External Client
   │  (도메인 예외 or 저수준 예외 throw)
   ▼
GlobalExceptionHandler (@RestControllerAdvice)
   │
   ├─ 예외 타입별 @ExceptionHandler 매칭
   ├─ ErrorCode 결정
   ├─ 보안 로깅 (12장 기준)
   └─ API Response Rule 포맷으로 응답 생성 → Client
```

### 3.2 패키지 구조 (제안)

```
com.gis.backend
 └─ common
     └─ exception
         ├─ GlobalExceptionHandler.java      # @RestControllerAdvice
         ├─ ErrorCode.java                   # Enum, API Response Rule의 Error Code와 연계
         ├─ BusinessException.java           # 최상위 커스텀 예외
         ├─ auth
         │   ├─ AuthenticationFailedException.java
         │   └─ AccessDeniedCustomException.java
         ├─ validation
         │   └─ InvalidRequestException.java
         ├─ business
         │   ├─ CareTargetNotFoundException.java
         │   ├─ PlaceNotFoundException.java
         │   └─ DuplicateResourceException.java
         ├─ external
         │   ├─ ExternalApiException.java     # Google API, FCM 공통
         │   └─ AiServerException.java        # FastAPI 서버 연동 실패
         └─ infra
             └─ DataAccessCustomException.java
```

### 3.3 GlobalExceptionHandler 처리 우선순위

`@ExceptionHandler`는 **더 구체적인 예외를 먼저** 매핑되도록 작성한다(Spring은 상속 계층에서 가장 근접한 타입을 우선 매칭하므로 순서 자체보다 타입 구체성이 중요하다).

| 우선순위 | 예외 타입 | 처리 방식 |
|---|---|---|
| 1 | `MethodArgumentNotValidException`, `ConstraintViolationException` | Validation 오류 → 6장 |
| 2 | Custom Business Exception (`BusinessException` 하위) | 도메인 규칙 위반 → 7장 |
| 3 | `AuthenticationException` 계열 (Custom) | 인증 실패 → 8장 |
| 4 | `AccessDeniedException` (Custom) | 인가 실패 → 8장 |
| 5 | `ExternalApiException`, `AiServerException` | 외부 연동 실패 → 9장 |
| 6 | `DataAccessException`, `DataAccessCustomException` | DB 오류 → 10장 |
| 7 | `Exception` (최종 fallback) | 미분류 시스템 오류, 500 응답 + 상세 서버 로깅 |

### 3.4 GlobalExceptionHandler 예시 (뼈대)

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        // 필드별 오류 메시지 수집 (6장)
        return ApiResponse.fail(ErrorCode.INVALID_INPUT_VALUE, e); // 응답 포맷은 API Response Rule 따름
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("[BUSINESS_EXCEPTION] code={}, message={}", e.getErrorCode(), e.getMessage());
        return ApiResponse.fail(e.getErrorCode());
    }

    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleExternal(ExternalApiException e) {
        log.error("[EXTERNAL_API_EXCEPTION] target={}, code={}", e.getTargetService(), e.getErrorCode(), e);
        return ApiResponse.fail(e.getErrorCode());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("[UNHANDLED_EXCEPTION]", e); // 스택 트레이스는 서버 로그에만 기록
        return ApiResponse.fail(ErrorCode.INTERNAL_SERVER_ERROR);
    }
}
```

`ApiResponse.fail(...)`의 실제 JSON 구조, HTTP Status 매핑, ErrorCode 값 체계는 API Response Rule 문서를 따른다. 본 문서는 "어떤 예외가 어떤 ErrorCode로 변환되는가"의 규칙만 정의한다.

---

## 4. Exception 계층 구조

```
RuntimeException
 └─ BusinessException (abstract, 최상위 커스텀 예외)
     ├─ ErrorCode errorCode        // API Response Rule의 Error Code Enum 참조
     ├─ HttpStatus httpStatus
     │
     ├─ InvalidRequestException          (400)  # 6장
     ├─ AuthenticationFailedException     (401)  # 8장
     ├─ AccessDeniedCustomException       (403)  # 8장
     ├─ ResourceNotFoundException         (404)
     │    ├─ CareTargetNotFoundException
     │    └─ PlaceNotFoundException
     ├─ DuplicateResourceException        (409)
     ├─ ExternalApiException              (502/504)  # 9장
     │    └─ AiServerException
     └─ DataAccessCustomException         (500)  # 10장
```

**설계 원칙**

- 모든 커스텀 예외는 `RuntimeException`을 상속한다. Checked Exception은 Service 경계를 넘기지 않고, 넘겨야 한다면 즉시 `BusinessException` 계열로 감싸서 던진다.
- 각 예외는 자기 자신의 `ErrorCode`와 대응 `HttpStatus`를 갖는다. GlobalExceptionHandler는 이 값을 그대로 읽어 응답을 생성하므로, Handler 안에서 상태 코드를 분기 처리하지 않는다.
- 도메인별 예외(`CareTargetNotFoundException` 등)는 반드시 상위 카테고리(`ResourceNotFoundException`)를 상속해, 신규 도메인 추가 시 GlobalExceptionHandler 수정 없이 확장 가능하게 한다.

---

## 5. Custom Exception 설계 기준

### 5.1 공통 상위 클래스

```java
public abstract class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    protected BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    protected BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
```

### 5.2 설계 규칙

| 규칙 | 이유 |
|---|---|
| 예외 클래스명은 `무엇이_왜` 를 드러내도록 짓는다 (`CareTargetNotFoundException`, `DuplicateGuardianEmailException`) | 로그만 보고도 원인 파악 가능 |
| 생성자에서 `ErrorCode`를 필수로 받는다 | GlobalExceptionHandler가 예외 타입 분기 없이 응답 생성 가능 |
| 메시지에 사용자 식별값(email, id 등)을 그대로 넣지 않는다 | 로그·응답을 통한 개인정보 노출 방지 (12장) |
| 하나의 예외는 하나의 실패 상황만 표현한다 | `CommonException` 류의 만능 예외 금지 |
| 도메인 패키지에 위치시키지 않고 `common.exception` 하위에 모은다 | 계층 구조를 한눈에 파악, 중복 정의 방지 |

### 5.3 예시

```java
public class CareTargetNotFoundException extends BusinessException {
    public CareTargetNotFoundException(Long careTargetId) {
        super(ErrorCode.CARE_TARGET_NOT_FOUND);
        // 상세 원인은 서버 로그에서만 careTargetId로 추적 (응답 메시지에는 미포함)
    }
}
```

---

## 6. Validation Exception 처리

### 6.1 대상

- `@Valid`/`@Validated` 기반 DTO 검증 실패: `MethodArgumentNotValidException`, `BindException`
- `@RequestParam`, `@PathVariable` 단건 검증 실패: `ConstraintViolationException`
- 비즈니스 규칙 수준이 아닌 "형식" 오류 (좌표 범위, 날짜 포맷, 필수값 누락 등)

### 6.2 처리 기준

| 항목 | 기준 |
|---|---|
| 위치 | Controller 진입 시점, DTO 계층에서 Bean Validation(`@NotNull`, `@Email`, `@Pattern` 등)으로 1차 방어 |
| 응답 | 단일 ErrorCode(`INVALID_INPUT_VALUE`) + 필드별 오류 목록(field, reason)을 함께 반환. 필드 오류 목록의 JSON 위치/키 이름은 API Response Rule의 실패 응답 구조를 따른다 |
| 좌표(GPS)/GeoFence 값 | 위경도 범위, 반경(radius) 최소/최대값 등 GIS 특화 검증은 Custom Validator(`@ConstraintValidator`)로 구현하여 Service 로직에서 재검증하지 않도록 한다 |
| 서버 로그 | 어떤 필드가 어떤 값 형식으로 실패했는지만 기록하고, 요청 바디 전체를 로그로 남기지 않는다 |

### 6.3 예시

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
    List<FieldErrorDetail> errors = e.getBindingResult().getFieldErrors().stream()
        .map(fe -> new FieldErrorDetail(fe.getField(), fe.getDefaultMessage()))
        .toList();
    log.warn("[VALIDATION_FAILED] fields={}", errors.stream().map(FieldErrorDetail::field).toList());
    return ApiResponse.fail(ErrorCode.INVALID_INPUT_VALUE, errors);
}
```

---

## 7. Business Exception 처리

### 7.1 대상

DB/외부 연동 오류가 아닌, **도메인 규칙 위반**에 해당하는 상황.

| 도메인 | 예시 |
|---|---|
| Guardian/CareTarget | 이미 연결된 보호대상자 중복 등록, 보호자당 등록 가능 인원 초과 |
| Place(안심구역) | 동일 좌표 반경 내 중복 등록, GeoFence 반경 정책 위반 |
| 위치/알림 | 이미 처리된 알림 재처리 요청, 존재하지 않는 위치 이력 조회 |
| AI 리포트 | 예측 대상 CareTarget의 최소 이동 데이터 미충족 |

### 7.2 처리 기준

- Service 계층에서 규칙 검증 후 즉시 해당 도메인 Custom Exception을 throw한다. Controller나 GlobalExceptionHandler에서 도메인 판단을 하지 않는다.
- 동일한 실패 사유라도 도메인이 다르면 예외 클래스를 분리한다(재사용 금지). 단, ErrorCode는 공통 카테고리로 묶어 관리할 수 있다(ErrorCode 체계는 API Response Rule 담당).
- HTTP Status는 도메인 의미에 맞게 매핑한다: 존재하지 않음 → 404, 중복/충돌 → 409, 정책 위반 → 400 또는 422.

---

## 8. Authentication / Authorization Exception 처리

> JWT 검증 로직, OAuth2(Google) 인증 흐름, Spring Security Filter Chain, Role 기반 인가 정책 자체는 **Spring Security Guide** 문서를 참고한다. 본 절은 그 결과로 발생하는 예외를 Backend가 어떻게 GlobalExceptionHandler까지 전달하고 응답으로 변환하는지만 다룬다.

### 8.1 처리해야 하는 지점

Spring Security의 Filter는 `DispatcherServlet` 이전에 동작하므로, `@RestControllerAdvice`(GlobalExceptionHandler)가 Filter 단계의 예외를 직접 잡을 수 없다. 이 프로젝트는 아래 2단계로 나눠 처리한다.

| 단계 | 발생 위치 | 처리 방식 |
|---|---|---|
| Filter 단계 (JWT 검증) | `JwtAuthenticationFilter` 내부에서 토큰 만료/위변조 발생 | Filter 내부에서 직접 catch 후, Security의 `AuthenticationEntryPoint` / `AccessDeniedHandler`를 통해 API Response Rule 포맷으로 즉시 응답. GlobalExceptionHandler로 전파되지 않음 |
| Controller/Service 단계 | Role 검사 실패, 리소스 소유자 불일치(다른 보호자의 CareTarget 접근 등) | Custom Exception(`AccessDeniedCustomException`) throw → GlobalExceptionHandler에서 처리 |

### 8.2 ErrorCode 구분 기준

| 상황 | HTTP Status | 구분 이유 |
|---|---|---|
| Access Token 없음/형식 오류 | 401 | 인증 자체가 안 된 상태 |
| Access Token 만료 | 401 (별도 ErrorCode 권장: `TOKEN_EXPIRED`) | Flutter 클라이언트가 이 코드를 받으면 자동으로 `/api/auth/refresh` 재시도 |
| Refresh Token 만료/Redis Blacklist 등록됨 | 401 | 재로그인 필요, refresh 재시도 금지 |
| 인증은 됐으나 Role 불일치 (Guardian 전용 API에 CareTarget 접근 등) | 403 | 8.1 참고 |
| 인증은 됐으나 리소스 소유자 불일치 (타인의 CareTarget/Place 접근) | 403 | 인가 실패이지만 존재 여부 노출 방지를 위해 404 대신 403 사용을 원칙으로 하되, 리소스 존재 자체를 숨겨야 하는 민감 데이터는 404로 응답하는 것도 허용 |

### 8.3 원칙

- `TOKEN_EXPIRED`와 일반 `UNAUTHORIZED`를 반드시 다른 ErrorCode로 분리한다. Flutter 클라이언트의 자동 재발급 로직이 이 값에 의존하기 때문이다.
- 인증/인가 실패 응답에는 "왜 실패했는지"에 대한 내부 판단 근거(예: 어떤 Role이 필요했는지, 토큰의 payload 내용)를 포함하지 않는다.
- Role/권한 관련 상세 정책(어떤 API가 Guardian 전용인지 등)은 Spring Security Guide 문서를 참고하며, 본 문서에서 중복 정의하지 않는다.

---

## 9. External Service Exception 처리

### 9.1 대상 서비스

| 외부 서비스 | 연동 방식 | 실패 시 영향 |
|---|---|---|
| FastAPI AI Server (예측/AI Care Chat) | REST 내부 호출 | AI 기능만 영향, 위치추적/알림 등 핵심 기능은 정상 동작해야 함 |
| Google OAuth2 | 로그인 시점 1회 | 로그인 실패로 직결 (Spring Security Guide 영역과 접점) |
| Google Maps / Places API | 장소 검색, 주소 변환 | 장소 등록/검색 기능 저하 |
| Firebase Cloud Messaging (FCM) | Push 알림 발송 | 알림 미수신, 핵심 위치 추적 기능에는 영향 없어야 함 |
| LLM API (OpenAI 등, FastAPI 경유) | AI Care Assistant 응답 생성 | 챗봇 기능만 영향 |

### 9.2 처리 원칙

| 원칙 | 설명 |
|---|---|
| 장애 격리 | 외부 서비스 실패가 핵심 도메인(인증, 위치 추적, GeoFence 알림)까지 전파되지 않도록 예외를 흡수하는 경계를 명확히 한다. 예: FCM 발송 실패는 알림 이력 저장 실패로 이어지지 않아야 한다 |
| Timeout 명시 | 모든 외부 API 호출(WebClient/RestClient)에 connect/read timeout을 반드시 설정한다. 무한 대기로 인한 스레드 고갈을 방지한다 |
| 예외 변환 | 외부 클라이언트가 던지는 저수준 예외(`WebClientResponseException`, `TimeoutException` 등)를 Service 경계에서 `ExternalApiException`/`AiServerException`으로 변환해 상위로 던진다. 원본 예외 타입이 Controller까지 노출되지 않게 한다 |
| 재시도 정책 | 일시 장애(Timeout, 5xx) 성격의 호출은 제한된 횟수의 재시도(exponential backoff)를 적용하고, 최종 실패 시에만 예외를 던진다. 인증성 실패(4xx)는 재시도하지 않는다 |
| Fallback | FCM 발송 실패, Google Places 캐시 미스 등 사용자 경험에 치명적이지 않은 영역은 예외를 던지는 대신 기본값/캐시 데이터로 대체하는 것을 우선 검토한다 |

### 9.3 ErrorCode/HTTP Status 매핑

| 상황 | HTTP Status |
|---|---|
| 외부 서비스가 명시적 오류 응답 (4xx) | 502 (Bad Gateway) — 클라이언트 요청 자체는 유효했으므로 400대신 502 사용 |
| 외부 서비스 Timeout / 무응답 | 504 (Gateway Timeout) |
| 외부 서비스 자체 다운 (Connection Refused 등) | 503 (Service Unavailable) |

### 9.4 예시

```java
public class AiServerException extends BusinessException {
    private final String targetService;

    public AiServerException(String targetService, ErrorCode errorCode) {
        super(errorCode);
        this.targetService = targetService;
    }

    public String getTargetService() {
        return targetService;
    }
}

// Service 계층
try {
    return aiServerClient.requestPrediction(careTargetId);
} catch (WebClientResponseException | WebClientRequestException e) {
    throw new AiServerException("fastapi-ai-server", ErrorCode.AI_SERVER_UNAVAILABLE);
}
```

---

## 10. Database Exception 처리

### 10.1 대상

PostgreSQL(JPA/JDBC) 접근 중 발생하는 예외. Redis는 캐시 계층으로 별도 취급한다(10.4 참고).

| Spring 저수준 예외 | 변환 대상 |
|---|---|
| `DataIntegrityViolationException` (unique/제약조건 위반) | `DuplicateResourceException` 또는 도메인별 Conflict 예외 |
| `EmptyResultDataAccessException` | `ResourceNotFoundException` 계열 |
| `CannotAcquireLockException`, `PessimisticLockingFailureException` | 별도 `DataAccessCustomException` + 재시도 안내 |
| 기타 `DataAccessException` | `DataAccessCustomException` (500) |

### 10.2 처리 원칙

- Repository/DAO 계층에서 발생한 예외를 Controller까지 그대로 흘려보내지 않는다. Service 계층에서 반드시 도메인 예외로 변환한다.
- SQL 구문, 테이블/컬럼명, 제약조건명은 클라이언트 응답에 절대 포함하지 않는다. 서버 로그에만 기록한다.
- 대량 조회(위치 이력 등)는 예외 처리 이전에 페이징/기간 제한으로 사전 방어하여 애초에 DB 부하로 인한 Timeout 예외 발생 가능성을 줄인다.

### 10.3 예시

```java
try {
    return placeRepository.save(place);
} catch (DataIntegrityViolationException e) {
    log.warn("[DUPLICATE_PLACE] guardianId={}", guardianId);
    throw new DuplicateResourceException(ErrorCode.PLACE_ALREADY_EXISTS);
}
```

### 10.4 Redis 관련 예외

Redis는 최신 위치, FCM Token, JWT Blacklist, 각종 캐시(9장 외부 서비스 성격과 유사)를 담당한다. Redis 장애 시:

- **캐시 성격 데이터**(장소 목록 캐시, AI 예측 캐시 등): 예외를 던지지 않고 PostgreSQL 원본 조회로 자동 폴백한다(Cache Aside 전략의 자연스러운 실패 처리).
- **세션/보안 성격 데이터**(JWT Blacklist, Refresh Token): 폴백이 불가능하므로 `DataAccessCustomException`으로 명확히 실패 처리하고 503으로 응답한다. 이 경우 인증 상태를 임의로 "성공"으로 간주하지 않는다(보안 원칙 우선).

---

## 11. Transaction 처리 기준

### 11.1 기본 원칙

| 항목 | 기준 |
|---|---|
| 경계 | `@Transactional`은 Service 계층에만 선언한다. Controller, Repository에는 선언하지 않는다 |
| 롤백 대상 | 기본적으로 `RuntimeException` 및 그 하위(즉 `BusinessException` 계열 전부)는 자동 롤백 대상이므로 `rollbackFor`를 별도 지정할 필요가 없다. Checked Exception을 다뤄야 하는 예외적 경우에만 `rollbackFor` 명시 |
| 읽기 전용 | 조회 전용 메서드는 `@Transactional(readOnly = true)`를 명시하여 불필요한 flush/dirty checking 비용을 줄인다 |
| 트랜잭션 범위 최소화 | 외부 API 호출(9장), FCM 발송, LLM 호출 등 네트워크 I/O는 트랜잭션 내부에 포함하지 않는다. DB 작업과 외부 호출을 분리해, 외부 서비스 지연이 DB 커넥션을 오래 점유하지 않도록 한다 |
| 부분 실패 처리 | 하나의 유스케이스에서 "DB 저장 성공 + 외부 호출 실패"가 발생할 수 있는 흐름(예: 알림 이력 저장 후 FCM 발송)은 DB 트랜잭션을 먼저 커밋하고, 외부 호출 실패는 별도 예외로 격리해 전체 롤백을 유발하지 않는다 |

### 11.2 트랜잭션과 예외 처리 결합 시 주의점

- `@Transactional` 메서드 내부에서 예외를 catch하고 로깅만 한 뒤 삼키면 트랜잭션이 정상 커밋되어 데이터 정합성이 깨질 수 있다. 트랜잭션 롤백이 필요한 예외는 반드시 다시 throw한다.
- self-invocation(같은 클래스 내 메서드 호출)으로는 `@Transactional`이 적용되지 않으므로, 별도 트랜잭션이 필요한 로직은 별도 Bean으로 분리한다.
- GlobalExceptionHandler는 트랜잭션 경계 밖(Presentation 계층)에서 동작하므로, 트랜잭션 롤백 여부와 무관하게 응답 변환 역할만 수행한다.

---

## 12. Logging 보안 기준

정부 소프트웨어 개발보안 가이드의 "오류 메시지를 통한 정보노출" 항목을 Backend 로깅 정책에 반영한다.

### 12.1 로그 레벨 기준

| 레벨 | 대상 |
|---|---|
| ERROR | 미분류 시스템 예외, DB 접근 실패, 외부 서비스 완전 다운 — 스택 트레이스 포함 가능(서버 로그 한정) |
| WARN | Business Exception, 인증/인가 실패, Validation 실패 — 원인 요약만 기록 |
| INFO | 정상 흐름의 주요 이벤트 (로그인, 주요 상태 변경) |
| DEBUG | 개발 환경 한정, 운영 환경에서는 비활성화 |

### 12.2 로그에 포함 금지 항목

- 비밀번호, Access Token/Refresh Token 원문, OAuth 인증 코드
- CareTarget/Guardian의 실시간 위치 좌표 원문(로그 레벨 INFO 이하에서는 마스킹 또는 생략, 장애 분석이 꼭 필요한 경우만 별도 감사 로그로 분리)
- 얼굴 인증 등 생체 정보 관련 원본 데이터
- 요청/응답 바디 전체 dump (필요한 필드만 선택적으로 기록)
- 스택 트레이스를 클라이언트 응답 바디에 포함하는 행위 (서버 로그 전용)

### 12.3 로그와 응답의 분리 원칙

- 클라이언트에게는 "무엇이 실패했는지"까지만 알려주고(ErrorCode + 안내 메시지), "왜 내부적으로 실패했는지"는 서버 로그에만 남긴다.
- 예외 발생 시 로그에는 추적 가능한 식별자(요청 ID, userId, careTargetId 등)를 남겨 운영 중 문제 추적이 가능하게 하되, 개인정보 자체는 남기지 않는다.
- MDC(Mapped Diagnostic Context) 등을 활용해 요청 단위 traceId를 로그에 부여하는 것을 권장한다(운영 환경에서 Nginx/Jenkins 배포 구조와 연계한 로그 추적).

---

## 13. API Response Rule과의 연계 방식

본 문서와 API Response Rule 문서의 책임 경계는 다음과 같다.

| 구분 | 담당 문서 |
|---|---|
| 예외가 어떤 ErrorCode로 변환되는가 | 본 문서 (2~10장) |
| ErrorCode Enum의 값 체계, 코드 네이밍 규칙, 코드-메시지 관리 방식 | API Response Rule |
| 실패 응답의 JSON 필드 구조 (success, code, message, data 등) | API Response Rule |
| HTTP Status와 필드 값의 매핑 규칙 | API Response Rule |
| GlobalExceptionHandler가 최종적으로 어떤 객체를 리턴하는가 (`ApiResponse.fail(...)`) | 본 문서는 호출 지점만 정의, 객체 스펙은 API Response Rule |

즉, GlobalExceptionHandler의 각 `@ExceptionHandler`는 "이 예외 → 이 ErrorCode"라는 매핑만 책임지고, 그 ErrorCode를 실제 HTTP 응답으로 직렬화하는 방식은 API Response Rule의 `ApiResponse` 구조를 그대로 사용한다.

---

## 14. 구현 체크리스트

- [ ] `common.exception` 패키지 하에 `BusinessException` 및 하위 계층 구조 생성
- [ ] `GlobalExceptionHandler`에 우선순위(3.3)에 따라 `@ExceptionHandler` 등록
- [ ] Validation 실패 시 필드별 오류 목록 반환 구조 구현 (API Response Rule 포맷 준수)
- [ ] JWT 인증 실패(Filter 단계)를 `AuthenticationEntryPoint`/`AccessDeniedHandler`로 처리해 API Response Rule 포맷과 일치시킴 (Spring Security Guide와 연계 확인)
- [ ] `TOKEN_EXPIRED`와 일반 `UNAUTHORIZED` ErrorCode 분리
- [ ] FastAPI/Google API/FCM 호출부에 timeout, 재시도, 예외 변환(`ExternalApiException`) 적용
- [ ] Redis 장애 시 캐시성 데이터는 DB 폴백, 보안성 데이터(Blacklist 등)는 명시적 실패 처리로 분기
- [ ] Repository 계층 예외를 Service 계층에서 도메인 예외로 변환
- [ ] `@Transactional` 범위에서 외부 I/O(FCM, LLM, Google API) 제외 확인
- [ ] 스택 트레이스/SQL/토큰 원문이 응답 바디에 포함되지 않는지 점검 (정부 보안가이드 "오류 메시지를 통한 정보노출" 대응)
- [ ] 로그에 개인정보(위치 좌표, 생체 정보, 토큰 원문)가 남지 않는지 점검
