# 예외 처리 작업 규칙
>해당파일 경로 .claude/rules/exception.md
> Backend 예외 처리(Exception 계층, GlobalExceptionHandler, 로깅) 관련 작업 시 반드시 준수해야 하는 실행 규칙이다.
> 설계 배경과 상세 근거, Exception 계층 구조, 도메인별 처리 기준은
> `docs/backend/Exception_Handling_Rule.md`를 따른다.
> - API 응답 포맷·ErrorCode 값 체계 → `docs/api/API_Response_Rule.md`
> - 인증/인가·JWT·OAuth2 구조 → `docs/security/Security_Guide.md`
>
> 이 문서와 위 문서의 내용이 충돌하면 위 문서(설계 원본)가 우선한다. 이 파일은 그 요약본이다.

## 중앙 집중 처리

- 모든 예외는 Controller/Service에서 직접 try-catch로 응답을 조립하지 않는다. `CustomException`(`BusinessException` 하위)을 throw하고 `GlobalExceptionHandler`(`@RestControllerAdvice`)에서 최종 처리한다.
- `catch (Exception e)`로 광범위하게 잡고 조용히 넘어가지 않는다(빈 catch 블록 금지). 예외 타입별로 구체적으로 처리한다.
- Repository/외부 연동 계층에서 발생한 저수준 예외(`SQLException`, `WebClientResponseException` 등)는 Service 계층에서 도메인 예외로 변환한 뒤 상위로 전파한다. Controller가 저수준 예외를 직접 받지 않는다.
- 401(1단계 인증)과 403(2단계 Role 인가)은 Spring Security Filter 단계(`AuthenticationEntryPoint`/`AccessDeniedHandler`)에서 처리하므로 `GlobalExceptionHandler`가 아니라 그쪽에서 처리한다. Role 인가는 `authorizeHttpRequests()` URL 패턴으로 처리하고 `@PreAuthorize`를 중복 적용하지 않는다(Security_Guide.md §4.4).
- 403이라도 **리소스 소유권 검증(3단계, 예: 타인의 CareTarget 접근)만** Service 계층에서 직접 DB 관계를 확인해 `AccessDeniedCustomException`을 던지고, 이것만 `GlobalExceptionHandler`가 처리한다(Security_Guide.md §4.5).

## ErrorCode

- `ErrorCode` Enum 값은 반드시 API Response Rule의 `{도메인}_{3자리}` 코드(`AUTH_002`, `TARGET_001` 등)를 그대로 쓴다. `TOKEN_EXPIRED`, `INVALID_INPUT_VALUE` 같은 서술형 이름을 새로 만들지 않는다.
- 새 실패 상황이 생기면 이 문서가 아니라 `docs/api/API_Response_Rule.md` §5.2 표에 먼저 코드를 추가하고, 그 값을 여기서 그대로 참조한다.
- 외부 연동 실패(FastAPI/LLM/FCM)의 HTTP Status는 502/503/504로 세분화하지 않고 500으로 통일한다. 어떤 서비스가 실패했는지는 Status가 아니라 `ErrorCode`(`AI_001`/`AI_002`/`NOTI_002`)로 구분한다.
- 리소스 소유권 불일치(타인의 CareTarget/Place 접근)는 예외 없이 항상 403으로 응답한다. "존재 자체를 숨긴다"는 이유로 404로 대체하지 않는다.

## 안전(Safety) 기능 — fail-safe

- `EMERGENCY`(긴급 연락) 관련 실패는 일반 FCM 발송 실패와 같은 취급(조용한 Fallback)을 하지 않는다. 실패해도 `NotificationHistory.status='FAILED'`로 반드시 이력을 남기고, 일반화된 500 문구가 아니라 `EMERGENCY_003`을 그대로 클라이언트에 내려 재시도/대체 수단 안내가 가능하게 한다(fail-open 금지).
- 안전 관련 로직에서 예외가 나면 재시도 후에도 실패 시 별도 경보로 전환한다.

## Custom Exception 설계

- 클래스명은 `무엇이_왜`를 드러내게 짓는다(`CareTargetNotFoundException` 등). `CommonException` 같은 만능 예외를 만들지 않는다.
- 생성자에서 `ErrorCode`를 필수로 받는다. Handler가 예외 타입별로 상태 코드를 다시 분기하지 않는다.
- 예외 메시지·로그에 email, 위치 좌표 원문, 토큰 원문, 생체 정보를 그대로 넣지 않는다.
- 도메인별 예외는 상위 카테고리(`ResourceNotFoundException` 등)를 상속해, 새 도메인 추가 시 `GlobalExceptionHandler` 수정 없이 확장 가능하게 한다.

## 트랜잭션

- `@Transactional`은 Service 계층에만 선언한다.
- 트랜잭션 내부에서 외부 API 호출(FCM, LLM, Google API)을 수행하지 않는다. DB 작업과 외부 호출을 분리한다.
- 트랜잭션 메서드 내부에서 예외를 catch만 하고 다시 throw하지 않으면 트랜잭션이 정상 커밋되어 정합성이 깨진다 — 롤백이 필요한 예외는 반드시 다시 throw한다.

## 로깅

- 클라이언트 응답에 스택 트레이스, 예외 클래스 전체 경로, SQL, 내부 파일 경로를 포함하지 않는다. 상세 원인은 서버 로그에만 남긴다.
- 절대 로그에 남기지 않는다: 비밀번호, JWT/Refresh Token 원문, 위치 좌표 원문, 생체 정보, 요청/응답 바디 전체.
- ERROR는 미분류 시스템 예외·DB 접근 실패·외부 서비스 완전 다운에만 쓰고, Business Exception·인증/인가 실패·Validation 실패는 WARN으로 원인 요약만 남긴다.

## PR 작성/리뷰 전 자가 점검

□ 새 예외 클래스가 `ErrorCode`(API Response Rule §5.2 코드)를 필수로 받는가
□ 저수준 예외가 Controller까지 그대로 전파되지 않고 Service에서 도메인 예외로 변환됐는가
□ 외부 연동 실패 Status가 500으로 통일됐는가 (502/503/504 사용 금지)
□ 리소스 소유권 불일치가 항상 403인가 (404 대체 없음)
□ EMERGENCY 관련 실패가 이력으로 남고 fail-safe로 처리되는가
□ 응답/로그에 스택 트레이스·SQL·토큰 원문·위치 좌표 원문이 노출되지 않는가
□ `@Transactional` 범위에 외부 I/O가 섞여 있지 않은가
