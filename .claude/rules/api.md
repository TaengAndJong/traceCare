# API 작업 규칙
>해당파일 경로 .claude/rules/api.md
> API(Controller/Response/에러 코드) 관련 작업 시 반드시 준수해야 하는 실행 규칙이다.
> 설계 배경과 상세 근거, 전체 Error Code 표, Frontend 처리 기준은
> `docs/api/API_Response_Rule.md`를 따른다.
> - 인증/인가/식별자 노출(IDOR) 정책 → `docs/security/Security_Guide.md`
> - 예외 계층·`GlobalExceptionHandler` 구현 상세 → Exception Handling Rule 문서
>
> 이 문서와 위 문서의 내용이 충돌하면 위 문서(설계 원본)가 우선한다. 이 파일은 그 요약본이다.

## Response 구조

- 모든 REST API(`/api/**`)는 `success`/`code`/`message`/`data` 4개 필드를 갖는 `ApiResponse<T>`로만 응답한다. Controller가 엔티티나 임의의 Map을 직접 반환하지 않는다.
- HTTP Status(2xx/4xx/5xx)와 `success` 값은 항상 일치시킨다. 200을 내려주면서 `success: false`를 반환하지 않는다.
- 실패 응답의 `data`는 항상 `null`로 고정한다. Validation 실패의 필드별 상세는 `data`가 아니라 최상위 `errors` 배열에 담는다.
- `/internal/**`(서버 간 통신), `/ws/**`(WebSocket)는 이 Response 구조를 적용하지 않는다. 임의로 두 규격을 섞지 않는다.
- 목록 API는 `data`에 배열을 직접 넣지 않고 `content` + 페이징 메타데이터(`page`/`size`/`totalElements`/`totalPages`)로 감싼다. 새 목록 API를 추가할 때 이 구조를 생략하지 않는다.

## 식별자 노출 (public_id)

- User(Guardian/CareTarget)·Place처럼 사용자가 직접 조작하는 Master Data는 API 응답에 내부 PK(BIGINT/SERIAL)를 노출하지 않고 `public_id`(UUID)만 사용한다. GuardianTarget은 예외 — 자체 `public_id`가 없으므로 `careTargetId`는 대상 User의 `public_id`를 그대로 쓴다(DATABASE_DESIGN_GUIDE.md §8).
- LocationHistory/VisitHistory/NotificationHistory/PredictionHistory/ChatHistory 등 대용량 시계열 이력 데이터는 기존 내부 PK를 그대로 사용해도 된다(소유권 검증은 별도로 Service 계층에서 수행).
- 새 테이블에 외부 노출용 식별자가 필요한데 `public_id` 컬럼이 없다면, 응답 필드부터 만들지 않고 DB 컬럼 추가를 먼저 처리한다.

## Error Code

- 신규 에러 상황이 생기면 `docs/api/API_Response_Rule.md` 5절의 표와 Backend `ErrorCode` Enum을 **같은 PR에서 함께** 갱신한다. 하나만 갱신한 채 머지하지 않는다.
- 코드 형식은 `{도메인}_{3자리}` 고정이다. 임의로 새 네이밍 패턴을 만들지 않는다. 새 API 영역(예: 방문 히스토리, 도착 확인, 긴급 연락)을 추가할 때 기존 도메인에 억지로 끼워 넣지 말고 새 도메인 코드를 정의한다.
- 동일한 실패 원인은 항상 같은 `code`를 반환한다. 같은 상황인데 요청마다 다른 코드가 나가지 않는지 확인한다.
- `EMERGENCY_*` 등 안전(Safety) 관련 도메인의 실패는 절대 조용히 넘어가지 않는다(fail-open 금지). 실패해도 이력(`NotificationHistory.status='FAILED'` 등)을 반드시 남기고, Frontend에는 일반화된 500 문구가 아니라 해당 `code`를 그대로 내려 재시도/대체 수단 안내가 가능하게 한다.

## 401 / 403 처리

- 인증(로그인 여부) → Role 인가 → 리소스 소유권 검증, 3단계를 구분해서 처리한다.
  - 인증 실패(토큰 없음/만료/위변조) → 401
  - Role은 맞지만 접근 자격이 없는 API 호출 → 403
  - Role은 맞지만 본인과 매핑되지 않은 리소스 접근 → 403 (200으로 조용히 빈 데이터를 주지 않는다)
- 401/403은 `GlobalExceptionHandler`가 아니라 `AuthenticationEntryPoint`(401)/`AccessDeniedHandler`(403)에서 처리한다. 이 두 경로에서도 반드시 같은 `ApiResponse.error()` 포맷을 사용한다 — 여기서만 다른 JSON 형태로 응답하지 않는다.

## 예외 처리 경계

- `CustomException` 계층, `@RestControllerAdvice`의 핸들러별 상세 처리, 로깅 레벨 기준은 이 파일이나 API Response Rule 문서에 새로 정의하지 않는다 — Exception Handling Rule 문서를 따른다.
- Controller에서 예외를 직접 try-catch로 삼키지 않는다. `CustomException`을 던지고 공통 핸들러로 위임한다.
- 클라이언트로 나가는 `message`에 예외 클래스명, SQL, 스택 트레이스, 내부 경로를 포함하지 않는다. 상세 원인은 서버 로그에만 남긴다.

## Frontend(Flutter) 연동

- 새 API를 추가할 때 개별 파서를 작성하지 않고 공통 `ApiResponse.fromJson`을 사용한다.
- `success: false` 또는 4xx/5xx 응답은 Dio 공통 인터셉터(`ApiErrorInterceptor`)에서 처리한다. 화면 코드에서 개별적으로 status code를 분기하지 않는다.
- Access Token 재발급은 인터셉터 안에서 1회만 자동 시도한다. 재발급 API 자체가 401을 반환하면 즉시 로그아웃 처리해 무한 루프를 만들지 않는다.

## PR 작성/리뷰 전 자가 점검

□ Controller가 `ApiResponse<T>`를 반환하는가 (엔티티 직접 반환 금지)
□ 새 에러 상황에 `ErrorCode`가 문서(5절)와 Enum에 동시에 추가되었는가
□ Master Data 식별자를 `public_id`가 아닌 내부 PK로 노출하지 않았는가
□ 401/403이 커스텀 `EntryPoint`/`AccessDeniedHandler`를 통해 동일한 포맷으로 나가는가
□ 목록 API가 `content` + 페이징 구조를 따르는가
□ `message`에 예외 메시지·스택 트레이스·SQL이 노출되지 않는가
□ 안전(Safety) 관련 API(EMERGENCY 등)의 실패가 이력으로 남고 fail-safe로 처리되는가
