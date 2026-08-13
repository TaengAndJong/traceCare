# CLAUDE.md (Root)

> 이 파일에는 프로젝트 전체에서 항상 알아야 하는 개요, 서비스 구성, 전역 규칙(응답 언어 등)을 작성한다.

## Project overview
- 프로젝트명: TraceCare — 아이·노인 케어 위치추적 알림 시스템 (GIS 기반)
- 핵심 흐름: 위치 수신 → 도착/이탈 판단(GeoFence) → 이상 이동 감지 → 알림(FCM) → AI 분석/비서
- 서비스 구성

  | 서비스 | 역할 | 지침 문서 |
  |---|---|---|
  | Flutter App | Guardian/CareTarget 모바일 앱 | `apps/frontend/CLAUDE.md` |
  | Spring Boot | REST/WebSocket, 인증/인가, 비즈니스 로직 | `apps/backend/CLAUDE.md` |
  | FastAPI | AI 예측(XGBoost/LightGBM) + LLM 연동 | `apps/ai-server/CLAUDE.md` |
  | PostgreSQL(+pgvector) | 영구 저장 | `docs/db/DATABASE_DESIGN_GUIDE.md` |
  | Redis | Cache Aside, 토큰 관리 | `docs/security/Security_Guide.md` [예정] |

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
├── apps/
│   ├── backend/       CLAUDE.md, .env.example
│   ├── frontend/      CLAUDE.md, .env.example
│   └── ai-server/     CLAUDE.md, .env.example
│
├── .claude/rules/
│   ├── git.md
│   ├── api.md         [예정]
│   ├── database.md    [예정]
│   └── security.md    [예정]
│
├── docs/
│   ├── architecture/
│   ├── db/
│   │   ├── DATABASE_DESIGN_GUIDE.md   # 완료
│   │   └── tracecare_schema_ddl.sql   # 완료
│   ├── security/       [예정]
│   ├── api/            [예정]
│   └── backend/        [예정]
│
└── reference/          # 참고자료 (정부 보안 가이드 등 원본 문서) — 코드/설계 산출물 아님
```

## Commands
- 전체 스택 기동: `docker compose up -d`
- 개별 서비스 명령어는 각 하위 CLAUDE.md 참조

## Conventions
### 응답 언어 및 기술 용어
- 모든 설명, 답변, 분석, 검토 결과 및 문서 작성 내용은 한국어로 작성한다.
- 기술 용어, 제품명, 프레임워크명, 라이브러리명, 프로토콜명 및 API 명칭은 공식 명칭을 유지한다.
  - 예: `Spring Boot`, `Spring Security`, `PostgreSQL`, `Redis`, `JWT`, `OAuth2`, `REST API`
- 클래스명, 메서드명, 함수명, 변수명, 필드명, 테이블명, 컬럼명, Enum 값, API URI 등 프로젝트에서 정의한 식별자는 임의로 번역하거나 변경하지 않는다.
- 필요한 경우 기술 용어는 한국어 설명과 공식 용어를 함께 표기한다.
  - 예: 캐시 만료 시간(TTL), 접근 제어(Access Control)
- 코드, 명령어, 설정값, 파일명 및 경로는 원문 그대로 유지한다.
- 영어 문장을 그대로 작성해야 할 특별한 이유가 없는 한 설명 문장은 한국어로 작성한다.

### 프로젝트 문서 및 규칙
- 서비스 간 역할과 상세 규칙은 각 하위 CLAUDE.md 및 `docs/`가 담당하며 Root에서는 재정의하지 않는다.
- 문서/코드 불일치 발견 시 임의로 한쪽을 덮어쓰지 않고 사용자에게 기준을 확인한다.
- 규칙 충돌 시 임의로 하나를 선택하지 않는다.
- `reference/` 폴더는 참고용 원본 자료(정부 시큐어코딩 가이드 등)이며, 프로젝트 설계 산출물이
  아니다 — 이 폴더 내용을 직접 수정하거나 산출물처럼 다루지 않는다.

## Quirks
- 개인 위치정보(민감정보)를 다루는 프로젝트다. 어떤 레이어에서도 로그에 원본 좌표/이름/이메일을
  그대로 남기지 않는다 — 세부 마스킹 기준은 `docs/backend/Logging_Guide.md` [예정] 담당.
