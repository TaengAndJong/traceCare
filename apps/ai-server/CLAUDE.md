# CLAUDE.md (apps/ai-server — FastAPI)

## Project overview
- 역할: 방문 장소 예측(XGBoost/LightGBM), LLM 기반 자연어 응답(AI 케어 비서)
- Uvicorn(ASGI) 위에서 FastAPI 구동, Backend(Spring Boot)로부터 요청을 받아 처리
- 전체 프로젝트 맥락은 루트 `CLAUDE.md` 참조 (경로: `../../CLAUDE.md`)

## Directory map
- (실제 구조 미확정) [추가 필요]

## Commands

| 구분 | 명령어 |
|---|---|
| 실행 | TBD (`uvicorn main:app --reload` 추정, 확인 필요) |
| 테스트 | TBD |

## Conventions
- 응답 언어 등 전역 규칙은 루트 `CLAUDE.md` Conventions를 따른다 (재정의하지 않음)
- Request/Response는 Pydantic 모델로 검증한다
- 예측 파이프라인: Pandas 전처리 → Feature 생성 → XGBoost/LightGBM 학습·예측
- 벡터 임베딩(pgvector) 연계는 `../../docs/db/DATABASE_DESIGN_GUIDE.md` §3.8 `ChatEmbedding`
  설계를 따른다 — 대화 원문(`ChatHistory`)과 별도 테이블로 분리되어 있음

## Quirks
- Hugging Face Space(모델 서빙) 유휴 시 sleep될 수 있음 — Backend 쪽에서 Redis 캐시 우선
  조회 후 없을 때만 이 서버를 호출하는 흐름을 전제로 설계되어 있다 (캐시 유무 판단은
  Backend 책임 영역).
- 학습된 모델 파일(`models/*.pkl`, `*.joblib`, `*.h5`)은 git에 커밋하지 않는다
  (`.gitignore`에서 완전 제외, 확정된 정책). 학습 스크립트(Pandas 전처리 →
  XGBoost/LightGBM 학습)로 언제든 재현 가능하기 때문 — 새 환경에서는 모델 파일을
  받아오는 게 아니라 학습을 다시 돌려서 생성한다. 향후 재학습 비용이 커지면
  Git LFS 전환을 재검토한다.
