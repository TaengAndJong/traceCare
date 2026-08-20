# Logging Guide
>해당파일 경로 docs/backend/Logging_Guide.md

프로젝트: 아이·노인 케어 위치추적 알림 시스템 (GIS)
문서 위치: `docs/backend/Logging_Guide.md`
담당 서버: Spring Boot Backend (FastAPI AI Server 통신 포함)
용도: 장애 분석, 보안 감사, 성능 분석, 운영 모니터링이 가능한 프로젝트 표준 로그 작성 기준
버전: v1.0 (작성일 2026-08-06)

> 이 문서는 **로그를 어떻게 기록할 것인가(포맷, 레벨, 필드, 마스킹, 보관)**를 담당한다.
> "어떤 예외를 어떤 ErrorCode로 분류하는가"는 **Exception Handling Rule**, "어떤 인증/인가 이벤트가 존재하는가"는 **Security_Guide.md**, "OWASP 관점에서 어떤 이벤트를 감사해야 하는가"는 **OWASP_Security_Guide.md**를 따른다. 본 문서는 그 세 문서에서 정의된 이벤트들을 실제로 어떤 로그 레벨/포맷/필드로 남길지에 대한 공통 표준만 정의하며, 각 문서에서 이미 정의한 예외 분류·인증 흐름·OWASP 대응 항목을 다시 설명하지 않는다.

---

## 목차

1. Logging Architecture
2. Logging Framework Standard
3. Log Level Policy
4. Application Logging Rule
5. Security Logging Policy
6. JWT Logging Policy
7. 개인정보 보호 Logging Rule
8. Exception Logging Policy
9. Global Exception Handler Logging
10. Performance Logging
11. AI Server Communication Logging
12. Audit Logging
13. Log Format Standard
14. Distributed Trace 고려
15. Log Monitoring 운영 기준
16. Developer Logging Checklist

---

## 1. Logging Architecture

### 1.1 계층별 로그 흐름

```
Client (Flutter App)
   ↓  HTTP Request
Spring Security Filter Chain
   ↓  Security Log (5장)
Controller Layer
   ↓  Application Log - 요청 시작/종료 (4.1)
Service Layer
   ↓  Application Log - 비즈니스 처리, 외부 호출 (4.2)
Repository Layer (MyBatis/JPA)
   ↓  Application Log - Query 수행 (4.3)
PostgreSQL / Redis
   ↓
Response
   ↓  Performance Log - 처리 시간 (10장)
Client
```

예외가 발생한 경로는 별도로 GlobalExceptionHandler(9장) 또는 Security Exception Handler(5장)를 거쳐 Error Log로 수렴한다.

### 1.2 로그 종류별 책임 정의

| 로그 종류 | 목적 | 주 사용처 | 담당 장 |
|---|---|---|---|
| Application Log | 정상 처리 흐름 추적, 장애 발생 시 흐름 재구성 | Controller/Service/Repository | 4장 |
| Security Log | 인증/인가 이벤트 기록, 보안 감사 | Spring Security Filter, GlobalExceptionHandler | 5~6장 |
| Error Log | 예외 발생 시 원인 진단 | GlobalExceptionHandler, Security Exception Handler | 8~9장 |
| Performance Log | 응답 지연/병목 구간 식별 | Controller AOP, 외부 API 호출부 | 10장 |

### 1.3 계층별 로그 책임 요약

| 계층 | 책임 | 상세 |
|---|---|---|
| Controller | 요청의 시작과 끝을 기록(누가, 무엇을, 결과) | 4.1 |
| Service | 비즈니스 처리의 시작/완료, 외부 서비스 호출 결과, 상태 변경 | 4.2 |
| Repository | 중요 Query 수행 여부와 결과 건수(SQL 원문은 운영 환경 비노출) | 4.3 |
| Security Filter / Exception Handler | 인증/인가 성공·실패, 예외 분류 결과 | 5, 8, 9장 |

각 계층은 자신이 담당하는 레벨의 로그만 남기고, 하위 계층에서 이미 남긴 로그를 상위 계층이 중복 기록하지 않는다(예: Repository가 Query 결과를 남겼다면 Service는 그 결과를 바탕으로 한 "판단"만 로그로 남긴다).

---

## 2. Logging Framework Standard

### 2.1 사용 기술

| 구성 | 용도 |
|---|---|
| SLF4J | 로깅 API 추상화, 구현체(Logback)에 종속되지 않는 코드 작성 |
| Logback | Spring Boot 기본 로깅 구현체, `logback-spring.xml`로 환경별 설정 분리 |
| Spring Boot Logging | `application.yml`의 `logging.level.*` 로 패키지별 로그 레벨 제어 |

### 2.2 Logger 사용 기준

```java
private static final Logger log = LoggerFactory.getLogger(CareTargetService.class);
```

- Logger는 클래스 단위로 `private static final`로 선언한다. `System.out.println`, `e.printStackTrace()` 직접 호출을 금지한다(정부 보안가이드 "오류 메시지를 통한 정보노출" 대응, Exception Handling Rule 2장과 동일 원칙).
- 로그 메시지는 파라미터화된 형태(`log.info("event={}, userId={}", event, userId)`)로 작성하고 문자열 `+` 연결을 지양한다. 로그 레벨이 비활성화된 경우 불필요한 문자열 연산을 방지하기 위함이다.
- 하나의 로그 메시지는 하나의 이벤트만 표현한다(여러 이벤트를 한 줄에 몰아 넣지 않는다) — 13장의 구조화 로그 포맷과 연계된다.

### 2.3 로그 레벨 정책

| 레벨 | 용도 |
|---|---|
| DEBUG | 개발 환경 상세 흐름 확인(파라미터 값, 중간 계산 결과). 운영 환경에서는 비활성화 |
| INFO | 정상 처리 흐름(요청 시작/종료, 로그인 성공, 주요 상태 변경) |
| WARN | 잠재적 문제(Business Exception, Validation 실패, 재시도 가능한 일시적 실패) |
| ERROR | 예외 발생(System Exception, 외부 서비스 완전 실패, 미분류 예외) |

레벨 선택 기준은 8장(Exception Logging Policy)에서 예외 종류별로 더 구체적으로 정의한다.

### 2.4 운영 환경별 로그 설정

| 환경 | 최소 로그 레벨 | 출력 대상 | 비고 |
|---|---|---|---|
| Local/Development | DEBUG | Console | 상세 흐름 확인, SQL 로그 활성화 가능 |
| Staging | INFO | Console + 파일 | 운영과 유사한 설정으로 사전 검증 |
| Production (AWS) | INFO | 파일 + 로그 수집기(예: CloudWatch Logs) | DEBUG 비활성화, SQL 전체 로그 비활성화(4.3) |

---

## 3. Log Level Policy

### 3.1 Development

| 항목 | 정책 |
|---|---|
| DEBUG 활성화 | `logging.level.com.tracecare.backend=DEBUG` — 요청 파라미터, 중간 처리 값 등 상세 흐름 확인 가능 |
| 상세 요청 흐름 확인 | Controller 진입/Service 호출/Repository Query까지 전 구간 로그 출력 |
| SQL 로그 | `spring.jpa.show-sql=true`, MyBatis Mapper 로그 활성화 허용(개발 편의 목적) |
| 민감정보 예외 | 개발 환경이라도 실제 사용자 데이터(운영 DB 복제본 등)를 다루는 경우 7장 마스킹 규칙을 동일하게 적용한다 — "개발 환경이니 괜찮다"는 예외를 두지 않는다 |

### 3.2 Production

| 항목 | 정책 |
|---|---|
| 로그 레벨 | INFO 이상만 기록(`logging.level.root=INFO`), DEBUG/TRACE 비활성화 |
| 민감 데이터 제외 | 7장 개인정보 보호 Logging Rule을 예외 없이 적용 |
| 성능 영향 최소화 | 동기 로그 Appender 대신 비동기 Appender(`AsyncAppender`) 사용, 과도한 INFO 로그(반복 조회 API 등)는 샘플링 또는 WARN 이상으로 조정 |
| SQL 전체 로그 | `spring.jpa.show-sql=false`, MyBatis Mapper 로그 비활성화(4.3 참고) — 대신 Query 식별자와 소요 시간만 기록 |

---

## 4. Application Logging Rule

### 4.1 Controller Layer

**기록 대상**

| 항목 | 예시 |
|---|---|
| API 요청 시작 | `event=API_REQUEST_START, method=GET, uri=/api/guardian/care-targets` |
| 요청 사용자 | `userId`(SecurityContext에서 추출, 이메일/이름 아님) |
| 요청 URI | 경로 파라미터 포함 가능(단, 조회 파라미터의 개인정보는 7장 마스킹 규칙 적용) |
| 처리 결과 | `event=API_REQUEST_END, status=200, elapsedMs=42` |

**주의사항**

- Request Body 전체를 로그로 남기지 않는다. 필요한 경우 요청을 식별할 수 있는 최소 필드(예: `careTargetId`)만 선택적으로 기록한다.
- Controller는 비즈니스 로직 결과를 판단하지 않고 "요청이 들어왔다/끝났다"만 기록한다. 비즈니스 판단 로그는 Service 계층 책임이다(4.2).

**예시**

```java
log.info("event=API_REQUEST_START, userId={}, method={}, uri={}", userId, request.getMethod(), request.getRequestURI());
// ... 처리 ...
log.info("event=API_REQUEST_END, userId={}, uri={}, status={}, elapsedMs={}", userId, uri, status, elapsed);
```

### 4.2 Service Layer

**기록 대상**

| 항목 | 예시 |
|---|---|
| 주요 비즈니스 처리 시작/완료 | `event=CARE_TARGET_REGISTER_START`, `event=CARE_TARGET_REGISTER_SUCCESS` |
| 외부 서비스 호출 결과 | `event=AI_PREDICT_CALL, result=SUCCESS, elapsedMs=320`(11장 상세 규칙 참고) |
| 주요 상태 변경 | GeoFence 진입/이탈 판정, 알림 발송 상태 변경 등 |

**설계 원칙**

- Service 계층은 "무엇을 판단했는가"를 로그로 남긴다(예: GeoFence 이탈 판정, 관계 검증 결과). 판단에 사용된 원본 좌표 값은 7장 마스킹/제외 규칙을 따른다.
- 하나의 유스케이스 안에서 여러 Service 메서드를 거치는 경우, 동일한 `traceId`(14장)로 묶여 하나의 흐름으로 추적 가능해야 한다.

### 4.3 Repository Layer

**기록 대상**

| 항목 | 예시 |
|---|---|
| 중요 Query 수행 | 대량 조회(위치 이력), 쓰기 작업(등록/수정/삭제) 수행 여부 |
| 데이터 처리 결과 | 조회 건수, 영향받은 row 수 (`event=LOCATION_HISTORY_QUERY, resultCount=120`) |

**주의사항**

- SQL 전체 로그(바인딩 파라미터 포함)는 운영 환경에서 출력하지 않는다. SQL 원문 노출은 정부 보안가이드의 "오류 메시지를 통한 정보노출" 및 OWASP **A02:2025(Security Misconfiguration)** 대응 원칙과 충돌한다(OWASP_Security_Guide.md 참고).
- 개발 환경에서는 `spring.jpa.show-sql=true` 또는 MyBatis 로그로 SQL을 확인할 수 있으나, 운영 환경에서는 Query 식별자(Mapper ID, Repository 메서드명)와 소요 시간만 남긴다.
- 단순 단건 조회(`findById` 등 반복 호출되는 경량 조회)까지 매번 로그를 남기지 않는다. "중요 Query"는 대량 조회, 쓰기 작업, 도메인상 의미 있는 조회(관계 검증 조회 등)로 한정한다.

---

## 5. Security Logging Policy

Spring Security 기반 인증/인가 이벤트는 Security_Guide.md에서 정의한 Filter Chain 구조(2장) 및 GlobalExceptionHandler/Security Exception Handler 역할 구분(8.3)을 전제로, 아래 이벤트를 표준 이벤트명으로 기록한다.

### 5.1 Authentication Log

| 이벤트명 | 발생 시점 | 로그 레벨 |
|---|---|---|
| `LOGIN_SUCCESS` | OAuth2 로그인 성공, JWT 발급 완료 | INFO |
| `LOGIN_FAILURE` | OAuth2 ID Token 검증 실패, 이메일 미검증 등 | WARN |
| `JWT_VERIFY_FAILED` | JWT 서명 위변조 검증 실패 | WARN |
| `JWT_EXPIRED` | Access Token 만료 | INFO (정상적인 흐름의 일부, 재발급으로 이어짐) |
| `TOKEN_BLACKLISTED` | 로그아웃된 토큰 재사용 시도 | WARN |
| `TOKEN_REFRESH_SUCCESS` / `TOKEN_REFRESH_FAILED` | Refresh Token 재발급 성공/실패 | INFO / WARN |
| `LOGOUT` | 로그아웃 처리(Blacklist 등록 완료) | INFO |

### 5.2 Authorization Log

| 이벤트명 | 발생 시점 | 로그 레벨 |
|---|---|---|
| `ACCESS_DENIED` | Role 불일치로 403 발생(Security_Guide.md 2.6) | WARN |
| `RESOURCE_ACCESS_DENIED` | 리소스 소유권 불일치로 403 발생(Service 계층, Security_Guide.md 4.5) | WARN |
| `ADMIN_API_ACCESS` | `/api/admin/**` 호출(성공 여부 무관, 전건 기록) | INFO (12장 Audit Log와 연계, 별도 채널 병행 기록) |

> 위치/장소 등 보호 데이터에 대한 리소스 소유권 불일치도 별도 이벤트명을 쓰지 않고 `RESOURCE_ACCESS_DENIED`로 통합한다. 어떤 도메인에서 발생했는지는 함께 기록되는 `errorCode`(`TARGET_002`/`LOCATION_003`/`LOCATION_004`/`ARRIVAL_001`/`EMERGENCY_001` 등)로 구분하며, 이벤트명을 도메인별로 새로 만들지 않는다 — 동일한 실패 유형(3단계 리소스 소유권 불일치)에 이벤트명이 여러 개 생기면 어떤 걸 써야 할지 매번 헷갈리기 때문이다.

### 5.3 예시

```java
log.warn("event=ACCESS_DENIED, userId={}, requiredRole={}, uri={}", userId, requiredRole, uri);
log.info("event=LOGIN_SUCCESS, userId={}, provider=google", userId);
```

---

## 6. JWT Logging Policy

| 구분 | 항목 |
|---|---|
| 기록 가능 | Token 검증 성공 여부(`valid=true/false`), Token 만료 여부, 사용자 ID(`userId`), `jti`(토큰 고유 식별자, Blacklist 조회/추적용) |
| 기록 금지 | Access Token 전체 문자열, Refresh Token 전체 문자열, JWT Secret Key, Google Client Secret |

```java
// 허용
log.warn("event=JWT_VERIFY_FAILED, jti={}, reason=SIGNATURE_MISMATCH", jti);

// 금지 (절대 이렇게 기록하지 않는다)
log.debug("token={}", accessToken); // NG
```

토큰 자체를 추적해야 하는 경우(예: 특정 로그인 세션의 흐름 추적)에는 토큰 원문 대신 `jti`를 사용한다. `jti`는 토큰을 역산할 수 없는 식별자이므로 로그에 남겨도 토큰 탈취로 이어지지 않는다.

---

## 7. 개인정보 보호 Logging Rule

이 프로젝트는 위치 데이터와 개인 식별 정보를 다루므로, 모든 로그(Application/Security/Error/Performance/Audit)에 예외 없이 아래 규칙을 적용한다. 본 절의 원칙은 Exception Handling Rule 12.2, Security_Guide.md 10.2, OWASP_Security_Guide.md 9.3에서 이미 선언된 것과 동일하며, 본 문서에서는 실제 마스킹 구현 규칙까지 구체화한다.

### 7.1 절대 기록 금지

- Password (평문/해시 모두 금지)
- JWT Token 원문 (Access/Refresh 모두, 6장 참고)
- OAuth Client Secret, LLM API Key, 기타 모든 Secret 값
- 개인정보 전체(이름, 전화번호, 이메일 전체 — 마스킹 없이는 금지, 7.2 참고)
- 얼굴 인증 이미지/특징 데이터
- 위치 좌표 원본(위도/경도 원시값) — 필요한 경우 정밀도를 낮춘 근사값이나 Place 단위 식별자로 대체

### 7.2 마스킹 대상 및 규칙

| 데이터 | 원본 | 마스킹 결과 | 규칙 |
|---|---|---|---|
| Email | `user@example.com` | `u***@example.com` | 로컬 파트 첫 글자만 노출, 나머지 `*` 처리 |
| Phone | `01012345678` | `010****5678` | 앞 3자리 + 뒤 4자리만 노출 |
| 이름 | `홍길동` | `홍*동` | 첫 글자/마지막 글자만 노출(2자 이름은 첫 글자만 노출 후 `*`) |
| GPS 좌표 | `37.123456, 127.123456` | 소수점 2자리로 절사(`37.12, 127.12`) 또는 `LOC_MASKED` | 정확한 위치 추적이 불가능한 수준으로 정밀도 저하, 상세 원본은 감사 필요 시에만 별도 접근 통제된 저장소에서 조회 |

마스킹은 로그 출력 직전(Logger 호출부 또는 공용 `LogMaskingUtil`)에서 일괄 적용하며, 각 개발자가 매번 수동으로 마스킹 로직을 작성하지 않도록 공용 유틸리티로 표준화한다.

```java
log.info("event=USER_PROFILE_UPDATED, userId={}, email={}", userId, LogMaskingUtil.maskEmail(email));
```

### 7.3 예외적으로 원본이 필요한 경우

장애 분석/보안 사고 대응을 위해 원본 데이터 확인이 불가피한 경우, 일반 애플리케이션 로그가 아닌 별도의 접근 통제된 감사 저장소(12장 Audit Logging)를 통해서만 조회하도록 하고, 이 경우도 조회 자체가 감사 대상이 된다(누가 원본 데이터를 조회했는지 기록).

---

## 8. Exception Logging Policy

### 8.1 Business Exception

| 예시 | 로그 레벨 |
|---|---|
| 사용자 없음(`CareTargetNotFoundException` 등) | WARN |
| 권한 없음(`AccessDeniedCustomException`) | WARN |
| 잘못된 요청(Validation 실패) | WARN |

Business Exception은 시스템 결함이 아니라 정상적인 업무 흐름 중 발생 가능한 예외이므로 WARN으로 기록한다(Exception Handling Rule 2장 "예외 처리 원칙"과 동일한 분류 기준을 로그 레벨에 반영). Stack Trace는 기록하지 않고, 예외 메시지와 관련 식별자(`careTargetId` 등)만 기록한다.

### 8.2 System Exception

| 예시 | 로그 레벨 |
|---|---|
| Database 오류(`DataAccessCustomException`) | ERROR |
| 외부 API 오류(`ExternalApiException`, `AiServerException`) | ERROR |
| 서버 장애(미분류 `Exception`) | ERROR |

System Exception은 예상치 못한 시스템 결함이므로 ERROR로 기록하고, 서버 로그에 한해 Stack Trace를 포함한다.

### 8.3 Stack Trace 기록 기준

| 상황 | Stack Trace 기록 여부 |
|---|---|
| Business Exception (WARN) | 미기록. 예외 메시지 + 식별자만 |
| System Exception (ERROR) | 서버 로그에만 전체 Stack Trace 기록 |
| 클라이언트 응답 | 어떤 경우든 Stack Trace 미포함 (Exception Handling Rule 2장, API Response Rule 참고) |

```java
// Business Exception
log.warn("event=BUSINESS_EXCEPTION, code={}, careTargetId={}", errorCode, careTargetId);

// System Exception
log.error("event=SYSTEM_EXCEPTION, code={}", errorCode, e); // e를 마지막 인자로 전달해 Stack Trace 포함
```

### 8.4 사용자 응답 메시지와 로그의 분리

클라이언트에게 전달되는 메시지(ErrorCode + 안내 문구, API Response Rule 관리 대상)와 서버 로그에 남는 상세 메시지는 서로 다른 문자열을 사용한다. 로그 메시지는 개발자가 원인을 파악하기 위한 것이고, 응답 메시지는 사용자가 이해할 수 있는 것이어야 하므로 목적 자체가 다르다.

---

## 9. Global Exception Handler Logging

### 9.1 @RestControllerAdvice 역할

`GlobalExceptionHandler`(Exception Handling Rule 3장에서 상세 정의)는 예외를 최종적으로 잡아 (1) 로그를 남기고 (2) 클라이언트 응답을 생성하는 두 가지 역할을 동시에 수행하는 지점이다. 본 문서는 이 중 "로그를 어떻게 남기는가"만 다룬다.

### 9.2 Exception 분류별 로그 처리

| 분류 | 처리 |
|---|---|
| Validation Exception | WARN, 실패한 필드 목록 기록(요청 바디 전체는 미기록) |
| Business Exception | WARN, ErrorCode + 관련 식별자 |
| Authentication/Authorization Exception (Service 계층에서 발생하는 것) | WARN, `event=RESOURCE_ACCESS_DENIED`(5.2절) 사용 — `ACCESS_DENIED`는 Role 불일치(2단계, Filter의 `AccessDeniedHandler`)에서 쓰는 이벤트명이라 GlobalExceptionHandler 로그 대상이 아니다(Exception Handling Rule §8.1 확정 구조 참고) |
| External Service Exception | ERROR, 대상 서비스명(`targetService`) 포함 |
| Database Exception | ERROR |
| 미분류 Exception | ERROR, 전체 Stack Trace |

### 9.3 Error Response 관리와 로그의 흐름

```
Client Response  ← ApiResponse.error(ErrorCode)  (API Response Rule 포맷)
      ↑
  Error Code 결정 (Exception Handling Rule 3.3 우선순위 매칭)
      ↑
  Server Log 기록 (본 절 9.2 기준)
      ↑
  Exception 발생 (Controller/Service/Repository)
```

로그 기록과 응답 생성은 GlobalExceptionHandler 내 동일한 `@ExceptionHandler` 메서드 안에서 함께 처리하되, "로그에 무엇을 남길지"는 본 문서를, "응답에 무엇을 담을지"는 API Response Rule과 Exception Handling Rule을 따르는 것으로 책임을 분리한다.

---

## 10. Performance Logging

### 10.1 측정 대상

| 대상 | 이벤트명 | 측정 방법 |
|---|---|---|
| API Response Time | `REQUEST_TIME` | Controller 진입~응답 완료(AOP 또는 Filter에서 측정, 4.1과 연동) |
| Database Query Time | `DB_QUERY_TIME` | Repository 메서드 실행 시간(MyBatis Interceptor 또는 JPA `@EntityListeners`, Micrometer 연동 가능) |
| External API 호출 시간 | `EXTERNAL_API_TIME` | Google Maps/Places API, FCM 호출 전후 시각 차 |
| AI Server Response Time | `AI_API_TIME` | Spring Boot → FastAPI 호출 전후 시각 차(11장과 연계) |

### 10.2 기록 기준

- 모든 API 응답에 대해 `elapsedMs`를 4.1의 `API_REQUEST_END` 로그에 함께 기록한다(별도 이벤트로 분리하지 않고 결합).
- 임계값(예: 1000ms)을 초과하는 요청은 INFO가 아닌 WARN으로 격상해 병목 구간을 눈에 띄게 한다.
- DB Query Time은 개별 Query 단위가 아니라 "느린 Query"(예: 300ms 초과)만 선별적으로 WARN 기록한다. 모든 Query를 기록하면 로그 양이 과도해져 운영 비용과 분석 효율을 해친다.

```java
log.info("event=REQUEST_TIME, uri={}, elapsedMs={}", uri, elapsed);
if (elapsed > 1000) {
    log.warn("event=SLOW_REQUEST, uri={}, elapsedMs={}", uri, elapsed);
}
```

---

## 11. AI Server Communication Logging

Spring Boot ↔ FastAPI 간 통신(Security_Guide.md 11장에서 정의한 API Key 기반 Server-to-Server 통신)의 로그 기준이다.

### 11.1 기록 대상

| 항목 | 예시 |
|---|---|
| 요청 시간 | 호출 시각(`timestamp`) |
| API 호출 성공 여부 | `result=SUCCESS` / `result=FAILURE` |
| 응답 시간 | `elapsedMs`(10장 `AI_API_TIME`과 동일 값 재사용) |
| Error Code | 실패 시 `AiServerException`의 ErrorCode(Exception Handling Rule 9장) |

### 11.2 기록 금지

- AI 서버로 전달되는 입력에 포함된 개인정보(이름, 연락처 등)
- 얼굴 인증 관련 이미지 데이터
- 위치 예측 등에 사용되는 민감 Feature 원본 데이터(원본 좌표, 이동 패턴 상세값) — 로그에는 요청 식별자(`careTargetId`, `predictionRequestId`)만 남기고, 실제 입력 데이터는 필요 시 별도 감사 채널에서만 확인한다.

```java
log.info("event=AI_PREDICT_CALL, careTargetId={}, result={}, elapsedMs={}", careTargetId, result, elapsed);
log.error("event=AI_SERVER_ERROR, targetService=fastapi-ai-server, errorCode={}", errorCode);
```

---

## 12. Audit Logging

일반 Application/Error 로그와 별도로, "누가 언제 무엇을 했는가"를 장기 보관·조회 가능한 형태로 남기는 감사 로그다. 일반 로그는 장애 분석이 목적이지만, Audit Log는 사후 추적과 책임 소재 확인이 목적이므로 별도 테이블(`AuditLog`, PostgreSQL) 또는 별도 로그 스트림으로 분리 저장하는 것을 권장한다.

### 12.1 Audit 대상

| 대상 | 설명 |
|---|---|
| 로그인 | 성공/실패 모두(5.1 이벤트 재사용) |
| 회원 정보 변경 | 이메일, Role 등 계정 속성 변경 |
| 권한 변경 | Role 변경(예: 최초 온보딩 시 Guardian/CareTarget 배정, Admin 권한 부여) |
| 보호 대상자 등록 | Guardian-CareTarget 관계 생성/해제 |
| 위치 조회 | Guardian의 CareTarget 위치 조회(현재/이력) — 조회 자체가 민감 행위이므로 감사 대상 |
| 관리자 기능 수행 | `/api/admin/**` 전 API 호출(5.2 `ADMIN_API_ACCESS`와 동일 이벤트를 Audit 테이블에도 적재) |

### 12.2 저장 데이터

| 필드 | 설명 |
|---|---|
| `actorId` | 행위자 userId |
| `actorRole` | 행위 시점의 Role |
| `action` | 행위 유형(`LOGIN`, `CARE_TARGET_REGISTER`, `LOCATION_VIEW`, `ROLE_CHANGE`, `ADMIN_ACTION` 등) |
| `targetId` | 행위 대상 식별자(예: 조회당한 CareTarget ID) |
| `result` | 성공/실패 |
| `timestamp` | 행위 발생 시각 |
| `ip` | 요청 IP(선택) |

개인정보 원본(위치 좌표, 이메일 전체 등)은 Audit Log에도 7장 마스킹 규칙을 동일하게 적용한다. Audit Log는 "무슨 행위를 했는가"의 기록이지 "행위의 원본 데이터 백업"이 아니다.

### 12.3 보관 기간

- 기본 보관 기간은 서비스 정책에 따라 최소 1년을 권장하며(보안 사고 발생 시 사후 추적 목적), 개인정보 보호법상 별도 보관 기간이 정해진 항목(예: 접속 기록)은 관련 법령 기준을 따른다.
- 보관 기간 초과 시 자동 삭제 또는 식별자를 제거한 통계 데이터로만 전환한다.

### 12.4 조회 권한

- Audit Log 조회는 `ROLE_ADMIN` 중에서도 별도 감사 권한이 있는 계정으로 한정한다(일반 Admin과 감사 담당자를 분리 운영하는 것을 권장).
- Audit Log 조회 행위 자체도 기록한다(누가 감사 로그를 열람했는지).

---

## 13. Log Format Standard

### 13.1 표준 포맷: JSON Log Format

운영 환경에서는 사람이 읽기 위한 텍스트 로그 대신 로그 수집기(CloudWatch Logs 등)가 파싱 가능한 JSON 구조화 로그를 사용한다.

**포함 필드**

| 필드 | 설명 |
|---|---|
| `timestamp` | 로그 발생 시각(ISO 8601) |
| `level` | 로그 레벨(DEBUG/INFO/WARN/ERROR) |
| `service` | 서비스명(`backend`, `ai-server`) — 향후 로그 수집기에서 서비스별 필터링 |
| `traceId` | 요청 단위 추적 ID(14장) |
| `userId` | 행위자 ID (없으면 `anonymous`) |
| `requestUri` | 요청 경로 |
| `eventType` | 표준 이벤트명(5장 `LOGIN_SUCCESS` 등) |
| `message` | 사람이 읽을 수 있는 설명(마스킹 적용된 값만 포함) |

**예시**

```json
{
  "timestamp": "2026-08-06T09:12:33.512Z",
  "level": "WARN",
  "service": "backend",
  "traceId": "a1b2c3d4",
  "userId": "1024",
  "requestUri": "/api/guardian/care-targets/55",
  "eventType": "RESOURCE_ACCESS_DENIED",
  "message": "requested careTargetId does not belong to requesting guardian"
}
```

### 13.2 Logback 설정 방향

- 운영 프로파일에서는 `logstash-logback-encoder` 등을 사용해 위 필드 구조의 JSON을 표준 출력으로 내보내고, 개발 환경에서는 가독성을 위한 패턴 레이아웃(텍스트)을 병행 사용한다(`logback-spring.xml`의 `springProfile` 태그로 환경 분기).
- 모든 로그는 MDC(Mapped Diagnostic Context)를 통해 `traceId`, `userId`를 자동으로 주입받도록 구성해, 각 로그 호출부에서 매번 명시적으로 전달하지 않아도 되게 한다(단, 위 예시처럼 이벤트별 핵심 필드는 명시적으로 남기는 것을 권장).

---

## 14. Distributed Trace 고려

### 14.1 필요성

Spring Boot(Backend)와 FastAPI(AI Server)가 분리되어 있고, WebSocket을 통한 실시간 통신까지 존재하는 구조이므로, 하나의 사용자 요청이 여러 서비스를 거치는 흐름을 하나의 ID로 묶어 추적할 수 있어야 장애 분석이 가능하다.

### 14.2 사용 식별자

| 식별자 | 범위 | 생성 위치 |
|---|---|---|
| Request ID | 단일 HTTP 요청 단위 | Spring Boot Filter(요청 진입 시 생성, 없으면 신규 생성) |
| Trace ID | 하나의 사용자 유스케이스 전체(Backend → AI Server 호출 포함) | Request ID를 그대로 Trace ID로 승계하거나, 최초 진입점에서 별도 발급 |
| Correlation ID | 서비스 간 호출 시 전달되는 식별자(HTTP 헤더로 전파) | Backend가 FastAPI 호출 시 헤더에 실어 전달 |

이 프로젝트 규모에서는 Request ID와 Trace ID를 동일하게 취급(하나의 요청 = 하나의 Trace)하는 것으로 충분하며, 향후 하나의 요청이 여러 비동기 작업으로 분기되는 경우(예: 위치 수신 후 GeoFence 판단 + AI 예측이 병렬로 실행)에는 Trace ID 하위에 Span 개념을 도입하는 확장을 고려한다.

### 14.3 Spring Boot ↔ FastAPI 요청 추적 방법

```
Flutter App → Spring Boot
   ↓ Filter에서 traceId 생성(또는 기존 값 승계) → MDC 저장
Spring Boot → FastAPI
   ↓ HTTP Header: X-Trace-Id: <traceId> 로 전달
FastAPI
   ↓ 수신한 X-Trace-Id를 자체 로그의 traceId 필드로 사용(FastAPI 측 로깅 정책은 AI 서버 별도 문서에서 상세화 가능하며, 본 문서는 Backend 관점에서 헤더 전달 규칙까지만 정의)
```

- `traceId`는 사용자 식별 정보를 포함하지 않는 무작위 값(UUID 등)으로 생성해, 그 자체로는 개인정보가 아니다.
- MDC에 저장된 `traceId`는 요청 처리가 끝나면 반드시 제거(`MDC.clear()`)해 스레드 재사용 시 다른 요청에 값이 섞이지 않도록 한다.

---

## 15. Log Monitoring 운영 기준

운영자가 정기적으로(또는 알림을 통해) 확인해야 하는 로그 패턴이다. OWASP_Security_Guide.md 9장(**A09:2025** Security Logging and Alerting Failures)의 "로그가 있어도 알림이 없으면 무의미하다"는 원칙을 반영한다.

| 모니터링 대상 | 기준 | 대응 |
|---|---|---|
| 로그인 실패 증가 | 동일 IP/계정 짧은 시간 내 다수 `LOGIN_FAILURE` | 계정 탈취 시도 의심, Rate Limit 강화 검토 |
| JWT 오류 증가 | `JWT_VERIFY_FAILED` 급증 | 토큰 위변조 시도 또는 클라이언트 배포 버그 의심 |
| API Error(4xx/5xx) 증가 | 특정 엔드포인트의 에러율 급등 | 배포 직후라면 롤백 검토, 아니라면 공격/장애 구분 필요 |
| Database 오류 증가 | `DataAccessCustomException` 빈발 | 커넥션 풀 고갈, PostgreSQL 장애 여부 확인 |
| AI 서버 장애 | `AI_SERVER_ERROR` 빈발, `AI_API_TIME` 급증 | FastAPI 서버 상태 확인, 필요 시 AI 기능 일시 비활성화(Exception Handling Rule 9장 Fallback 전략과 연계) |
| 관리자 API 호출 | `ADMIN_API_ACCESS` 발생 시 즉시 확인 가능하도록 별도 채널 알림 권장 | 예상치 못한 관리자 행위 여부 확인 |

운영 초기 단계에서는 CloudWatch Logs Insights 쿼리 또는 로그 수집기의 Alert 규칙으로 위 항목의 임계치 기반 알림을 최소한으로 구성하고, 이후 운영 데이터가 쌓이면 임계치를 조정한다.

---

## 16. Developer Logging Checklist

□ 민감정보(Password, Token 원문, 위치 원본, 얼굴 데이터) 로그 출력 여부 확인
□ Email/Phone 등 마스킹 대상 필드에 마스킹 유틸리티 적용 여부 확인
□ Exception StackTrace가 System Exception(ERROR)에만 기록되고 Business Exception(WARN)에는 미기록되는지 확인
□ 클라이언트 응답에 Stack Trace/SQL/내부 경로가 포함되지 않는지 확인
□ INFO 로그 과다 출력 여부 확인 (반복 호출되는 경량 API까지 매번 상세 로그를 남기고 있지 않은지)
□ API 요청이 `traceId`로 Controller~Repository까지 추적 가능한지 확인
□ 보안 이벤트(5장 표준 이벤트명)가 정의된 이벤트명 그대로 기록되는지 확인
□ AI 서버 호출 로그에 개인정보/이미지/민감 Feature가 포함되지 않는지 확인
□ Audit 대상 행위(로그인, 정보 변경, 권한 변경, 위치 조회, 관리자 기능)가 Audit Log에 정상 적재되는지 확인
□ 운영 환경에서 DEBUG/SQL 전체 로그가 비활성화되어 있는지 확인
□ 신규 로그 추가 시 JSON 표준 필드(13장)를 따르고 있는지 확인
