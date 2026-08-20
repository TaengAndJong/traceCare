# CLAUDE.md (Root)
>

## Project overview
- 프로젝트명: TraceCare — 아이·노인 케어 위치추적 알림 시스템 (GIS 기반)
- 핵심 흐름: 위치 수신 → 도착/이탈 판단(GeoFence) → 이상 이동 감지 → 알림(FCM) → AI 분석/비서
- 서비스 구성

  | 서비스 | 위치 | 역할 | 지침 문서 |
    |---|---|---|---|
  | Flutter App | `apps/frontend/` | Guardian/CareTarget 모바일 앱 | `apps/frontend/CLAUDE.md` |
  | Spring Boot | `apps/backend/` | REST/WebSocket, 인증/인가, 비즈니스 로직 | `apps/backend/CLAUDE.md` |
  | FastAPI | `apps/ai-server/` | AI 예측(XGBoost/LightGBM) + LLM 연동 | `apps/ai-server/CLAUDE.md` |
  | PostgreSQL(+pgvector) | (인프라) | 영구 저장 | `docs/db/DATABASE_DESIGN_GUIDE.md` |
  | Redis | (인프라) | Cache Aside, 캐시 키/TTL | `docs/backend/Cache_Strategy_Guide.md` |
  | Redis | (인프라) | Refresh Token/JWT Blacklist(토큰 관리) | `docs/security/Security_Guide.md` |

- 규모 전제: 현재 포트폴리오 단계(1인 개발, EC2 1대, Docker Compose)이나
  설계는 대규모 운영(수십만~수백만 사용자) 기준으로 작성됨
- IMPORTANT: 이 전제 때문에 "지금 당장 불필요해 보이는" 인덱스/파티셔닝/캐시
  설계를 임의로 단순화하지 않는다. 단순화가 필요하면 먼저 사용자에게 확인한다.

## Directory map

```
project/
├── CLAUDE.md
├── README.md
├── .gitignore
├── Jenkinsfile
├── docker-compose.yml
│
├── apps/                          # 실행 가능한 서비스 3개 그룹
│   ├── backend/    CLAUDE.md, .env.example
│   ├── frontend/   CLAUDE.md, .env.example
│   └── ai-server/  CLAUDE.md, .env.example
│
├── .claude/rules/                 # 짧은 체크리스트 (작업 시 항상 확인)
│   ├── git.md          # 완료
│   ├── database.md     # 완료
│   ├── security.md     # 완료
│   ├── api.md          # 완료
│   ├── exception.md    # 완료
│   ├── logging.md      # 완료
│   └── cache.md        # 완료
│
├── docs/                          # 상세 설계 지식 (필요할 때 참조)
│   ├── architecture/
│   │   └── System_Overview.md              # 완료 — 전체 구성도/데이터 흐름
│   │       (Deployment_Architecture.md, Failure_Scenarios.md는 필요해지면 추가)
│   ├── development/
│   │   └── git_strategy.md                 # 완료 — 브랜치/Commit/PR/Merge 전략
│   ├── db/
│   │   ├── DATABASE_DESIGN_GUIDE.md        # 완료
│   │   └── tracecare_schema_ddl_260818_1.1.sql   # 완료 (Place.public_id 추가, 버전 명시 파일명)
│   ├── security/
│   │   ├── Security_Guide.md               # 완료
│   │   └── OWASP_Security_Guide.md         # 완료 (기준: OWASP Top 10:2025)
│   ├── api/
│   │   ├── API_Response_Rule.md            # 완료
│   │   └── API_Specification.md            # 완료
│   ├── backend/
│   │   ├── Exception_Handling_Rule.md      # 완료
│   │   ├── Logging_Guide.md                # 완료
│   │   ├── Cache_Strategy_Guide.md         # 완료
│   │   └── Coding_Convention.md            # 완료
│   ├── ai-server/
│   │   └── Coding_Convention.md            # 완료 (일반 추천안, 착수 시 재검토)
│   └── frontend/
│       └── Coding_Convention.md            # 완료 (일반 추천안, 착수 시 재검토)
│
└── reference/                     # 참고자료 원본 (정부 보안 가이드 등) — 산출물 아님
```

- `apps/`: backend/frontend/ai-server가 통째로 삭제·교체되어도 `docs/`, `.claude/`,
  `reference/`는 영향받지 않는다 (소스 코드와 설계 지식을 물리적으로 분리)
- `.claude/rules/` vs `docs/`: rules는 "작업 시 항상 지켜야 하는 짧은 규칙",
  docs는 "왜 그렇게 설계했는지까지 담은 상세 지식"이다. 같은 내용을 두 곳에 중복 작성하지
  않는다 — rules에는 체크리스트만, 근거/배경은 docs를 참조하도록 링크만 남긴다.

## Commands
- 전체 스택 기동: `docker compose up -d`
- DB(PostgreSQL) 접속 포트: 호스트 `5433` → 컨테이너 `5432`
- DB 준비 상태 확인: `docker exec tracecare-db pg_isready -U tracecare`
- DDL 적용 등 DB 관련 상세 절차는 `.claude/rules/database.md` 참조
- 개별 서비스 명령어는 각 `apps/*/CLAUDE.md` 참조

## Conventions
- **응답 언어: 한글로 작성한다.** (코드/커밋 메시지의 코드 자체는 관례상 영어 유지 가능,
  설명·주석 본문은 한글)
- 작업 완료 후 응답은 다음 순서로 정리한다.
  1. 무엇을 했는지 요약 (2~3줄)
  2. 변경된 파일 목록
  3. 실행/테스트 방법 (해당되는 경우)
  4. 남은 이슈나 확인이 필요한 사항
- 불필요하게 장황한 설명 없이 핵심 위주로 답한다.
- 서비스 간 역할과 상세 규칙은 각 `apps/*/CLAUDE.md`, `.claude/rules/`, `docs/`가 담당하며
  Root에서는 재정의하지 않는다.
- Git 브랜치 전략: 기능 개발은 `feature/*`에서 수행하고 `main`/`dev`/`test`는 직접 작업하지
  않는다. 상세 절차는 `docs/development/git_strategy.md`, Claude 실행 규칙은
  `.claude/rules/git.md` 참조.
- 문서/코드 불일치 발견 시 임의로 한쪽을 덮어쓰지 않고 사용자에게 기준을 확인한다.
- 규칙 충돌 시 임의로 하나를 선택하지 않는다.
- `reference/` 폴더는 참고용 원본 자료이며 프로젝트 설계 산출물이 아니다 — 직접 수정하거나
  산출물처럼 다루지 않는다.

## Quirks
- 개인 위치정보(민감정보)를 다루는 프로젝트다. 어떤 레이어에서도 로그에 원본 좌표/이름/이메일을
  그대로 남기지 않는다 — 세부 마스킹 기준은 `docs/backend/Logging_Guide.md` 담당.
- Backend Java 패키지 루트는 `com.tracecare.backend`로 통일한다(레포 루트명 `traceCare` 기준,
  `apps/backend` 폴더와 1:1 대응). 상세 패키지 구조는 `docs/backend/Coding_Convention.md` 참조.
