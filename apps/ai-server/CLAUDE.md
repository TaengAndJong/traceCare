# CLAUDE.md (apps/ai-server)

## 이 앱은
FastAPI 기반 AI 서버. 방문 예측(XGBoost/LightGBM), AI Care Chat(LLM 연동)을 담당하며 사용자가 직접 호출하지 않는다 — Spring Boot(apps/backend)만 `/internal/*`로 호출하는 서버 간 통신 대상이다.

## 지침 문서

| 주제 | 문서 |
|---|---|
| 코딩 스타일, 프로젝트 구조 | `docs/ai-server/Coding_Convention.md` (일반 추천안, 확정 아님) |
| Spring Boot ↔ FastAPI 통신 보안(API Key, Timeout) | `docs/security/Security_Guide.md` 11장 |
| 외부 연동 실패 시 예외 처리 원칙 | `docs/backend/Exception_Handling_Rule.md` 9장 |
| 로그 포맷(JSON, traceId) | `docs/backend/Logging_Guide.md` 13~14장 |

이 파일에서 위 내용을 재설명하지 않는다. 충돌 시 위 문서가 원본이다.

## 실행

```
docker compose up -d          # 프로젝트 루트에서
uvicorn app.main:app --reload # apps/ai-server에서
```

## 반드시 지킬 것
- 사용자 JWT를 받지 않는다 — Spring Boot가 보낸 `X-Internal-Api-Key`로만 인증한다(Security_Guide.md §11.1)
- 요청/응답은 Pydantic 모델로만 받는다. raw dict 금지(Coding_Convention.md §3)
- 외부 API(Hugging Face, LLM) 호출에 timeout을 반드시 설정한다
- 로그에 개인정보, 원본 GPS 좌표, LLM 입력 원문을 남기지 않는다
- Spring Boot가 보낸 `X-Trace-Id`를 그대로 로그에 반영한다
