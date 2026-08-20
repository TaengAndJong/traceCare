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
