# 로깅 작업 규칙
>해당파일 경로 .claude/rules/logging.md
> 로그(레벨, 포맷, 이벤트명, 마스킹, 보관) 관련 작업 시 반드시 준수해야 하는 실행 규칙이다.
> 설계 배경과 상세 근거, 표준 이벤트명 전체 목록, 마스킹 규칙, Audit Log 구조는
> `docs/backend/Logging_Guide.md`를 따른다.
> - 예외 종류별 로그 레벨 분류 기준 → `docs/backend/Exception_Handling_Rule.md`
> - 인증/인가 이벤트 발생 지점 → `docs/security/Security_Guide.md`
> - OWASP 로깅 관련 대응 항목 → `docs/security/OWASP_Security_Guide.md`
>
> 이 문서와 위 문서의 내용이 충돌하면 위 문서(설계 원본)가 우선한다. 이 파일은 그 요약본이다.

## 기본 원칙

- `System.out.println`, `e.printStackTrace()`를 직접 호출하지 않는다. `private static final Logger`를 클래스 단위로 선언해 SLF4J로만 로깅한다.
- 로그 메시지는 파라미터화된 형태(`log.info("event={}, userId={}", event, userId)`)로 작성한다. 문자열 `+` 연결을 쓰지 않는다.
- 하나의 로그 메시지는 하나의 이벤트만 표현한다. 여러 이벤트를 한 줄에 몰아넣지 않는다.
- 새 로그를 추가할 때는 Logging_Guide.md §5(Security)/§9(Exception) 등에 이미 정의된 표준 이벤트명이 있는지 먼저 확인하고, 있으면 그대로 쓴다. 임의로 비슷한 이벤트명을 새로 짓지 않는다.

## 로그 레벨

- DEBUG: 개발 환경 상세 흐름만. 운영 환경에서는 비활성화한다.
- INFO: 정상 처리 흐름(요청 시작/종료, 로그인 성공, 상태 변경).
- WARN: Business Exception, Validation 실패, 인증/인가 실패, 재시도 가능한 일시적 실패. Stack Trace는 남기지 않는다.
- ERROR: System Exception(DB 오류, 외부 서비스 완전 실패, 미분류 예외)만. 서버 로그에 한해 전체 Stack Trace를 포함한다.
- 이 구분은 예외 종류가 아니라 "예상 가능한 업무 흐름이냐(WARN) vs 예상 못한 시스템 결함이냐(ERROR)"가 기준이다 — Exception_Handling_Rule.md의 예외 계층과 1:1로 맞춘다.

## 계층별 책임

- Controller: 요청의 시작/끝(`API_REQUEST_START`/`API_REQUEST_END`)만 기록한다. 비즈니스 판단을 여기서 로그로 남기지 않는다.
- Service: 비즈니스 처리 시작/완료, 외부 서비스 호출 결과, 상태 변경(GeoFence 판정 등)을 기록한다.
- Repository: 대량 조회·쓰기 작업 등 "중요 Query"만 기록한다. 반복 호출되는 단건 조회(`findById` 등)까지 매번 남기지 않는다.
- 하위 계층이 이미 남긴 로그를 상위 계층이 중복 기록하지 않는다.

## 절대 금지 (모든 로그 공통)

- 비밀번호(원문/해시 모두)
- JWT/Refresh Token 원문, OAuth Client Secret, LLM API Key 등 모든 Secret 값 — 토큰을 추적해야 하면 원문 대신 `jti`를 쓴다
- 위치 좌표 원본(위도/경도 원시값) — 필요하면 정밀도를 낮추거나 Place 단위 식별자로 대체
- 얼굴 인증 이미지/특징 데이터
- 이메일/전화번호/이름 등 개인정보 — 반드시 마스킹 유틸(`LogMaskingUtil`)을 거친다. 각자 로그 호출부에서 수동으로 마스킹하지 않는다
- Request/Response Body 전체 dump, SQL 바인딩 파라미터 전체(운영 환경)

## SQL / DB 로그

- 운영 환경에서는 `spring.jpa.show-sql=false`, MyBatis Mapper 로그 비활성화. Query 식별자와 소요 시간만 남긴다.
- "느린 Query"(예: 300ms 초과)만 선별적으로 WARN 기록한다. 모든 Query를 다 남기지 않는다.

## traceId

- 요청 진입 시 `traceId`를 생성(또는 승계)해 MDC에 저장하고, Spring Boot → FastAPI 호출 시 `X-Trace-Id` 헤더로 전달한다.
- 요청 처리가 끝나면 반드시 `MDC.clear()`로 제거한다 — 스레드 재사용 시 다른 요청에 값이 섞이지 않게 한다.
- `traceId`는 사용자 식별 정보를 포함하지 않는 무작위 값(UUID)만 쓴다.

## Audit Log

- 로그인, 회원/권한 정보 변경, 보호자-보호대상자 관계 생성/해제, Guardian의 CareTarget 위치 조회, `/api/admin/**` 전 호출은 일반 로그와 별도로 Audit Log에 적재한다(Logging_Guide.md §12).
- Audit Log에도 마스킹 규칙을 동일하게 적용한다 — "행위 기록"이지 "원본 데이터 백업"이 아니다.
- Audit Log 조회 자체도 기록한다(누가 감사 로그를 열람했는지).

## PR 작성/리뷰 전 자가 점검

□ `System.out`/`printStackTrace` 대신 SLF4J Logger를 쓰는가
□ WARN에는 Stack Trace가 없고 ERROR에만 있는가
□ 비밀번호·토큰 원문·위치 좌표 원문·생체 정보가 로그 어디에도 없는가
□ 이메일/전화번호/이름이 마스킹 유틸을 거쳤는가
□ 새 이벤트가 Logging_Guide.md에 이미 정의된 표준 이벤트명을 재사용하는가
□ 운영 환경에서 DEBUG/SQL 전체 로그가 꺼져 있는가
□ Audit 대상 행위가 Audit Log에도 정상 적재되는가
