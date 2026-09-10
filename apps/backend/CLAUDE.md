# CLAUDE.md (apps/backend)
>해당파일 경로 apps/backend/CLAUDE.md

## 이 앱은
Spring Boot 기반 REST/WebSocket API 서버. 인증/인가, Guardian/CareTarget 비즈니스 로직, PostgreSQL/Redis 연동, FastAPI(ai-server) 호출을 담당한다.

## 지침 문서 (상세 설계 — 코드 작성 전 반드시 확인)

| 주제 | 문서 |
|---|---|
| 코딩 스타일, 패키지 구조, 네이밍 | `docs/backend/Coding_Convention.md` |
| API 응답 포맷, Error Code | `docs/api/API_Response_Rule.md`, `.claude/rules/api.md` |
| 전체 엔드포인트 목록 | `docs/api/API_Specification.md` |
| 예외 처리 구조 | `docs/backend/Exception_Handling_Rule.md`, `.claude/rules/exception.md` |
| 로깅 | `docs/backend/Logging_Guide.md`, `.claude/rules/logging.md` |
| Redis 캐시 전략 | `docs/backend/Cache_Strategy_Guide.md`, `.claude/rules/cache.md` |
| 인증/인가, JWT, OAuth2 | `docs/security/Security_Guide.md`, `.claude/rules/security.md` |
| DB 테이블/쿼리 | `docs/db/DATABASE_DESIGN_GUIDE.md`, `.claude/rules/database.md` |

이 파일에서 위 내용을 재설명하지 않는다. 충돌 시 위 문서가 원본이다.

## 패키지 루트
`com.tracecare.backend` (Coding_Convention.md §1 참고)

## 실행

```
docker compose up -d          # 프로젝트 루트에서, PostgreSQL/Redis 기동
./gradlew bootRun             # apps/backend에서
```

## 코드 작성 시 최우선 확인 순서
1. 이 API가 `API_Specification.md`에 이미 정의돼 있는가 (없으면 먼저 그 문서에 추가)
2. Response/Error Code가 `API_Response_Rule.md` 체계(`{도메인}_{3자리}`)를 따르는가
3. 예외를 던질 때 `Exception_Handling_Rule.md`의 계층(Business/Auth/External/Database)에 맞는 Custom Exception을 쓰는가
4. 리소스 소유권 검증이 Service 계층에 명시적으로 있는가 (Security_Guide.md §4.5)


## 디버깅 세션 리포트 규칙

디버그 로그 추가, 버그 원인 조사, 실제 환경 테스트처럼 "문제를 진단하는 성격의 작업"을 수행한 세션에서는,
작업이 끝날 때 그날 날짜로 된 텍스트 리포트를 로컬 파일로 남긴다.

- 저장 위치: `docs/debug-reports/backend/YYYY-MM-DD.txt` (해당 날짜 폴더/파일이 없으면 새로 생성, 있으면 그날 파일에 이어서 추가)
- 포함 내용:
    - 그 세션에서 조사한 문제(증상)
    - 시도한 해결 방법과 각각의 결과 (성공/실패 무관하게 전부 기록, 시도 순서대로)
        - 실패한 시도도 "왜 그게 원인이 아니라고 판단했는지"까지 한 줄로 남긴다
    - 확인된 원인(코드 버그 / 환경 문제 / 원인 불명 등 구분)
    - 실제로 변경한 파일과 변경 요지
    - 남겨둔 디버그 로그(임시 로그 등)의 위치 목록
    - 아직 미해결이거나 추가 확인이 필요한 사항
- 이 파일은 커밋 대상에 포함한다.
- 같은 날 여러 번 세션이 있으면 하나의 파일에 시간 구분선을 넣어 이어서 작성한다.
- **주의**: 이건 `Logging_Guide.md`/`.claude/rules/logging.md`가 다루는 "애플리케이션 운영 로그"와는 별개다
            그 문서들은 서버가 실행 중 자동으로 남기는 로그를 다루고, 이 규칙은 Claude Code의 문제 해결 세션 자체를 기록하는 것이다. 혼동하지 않는다.