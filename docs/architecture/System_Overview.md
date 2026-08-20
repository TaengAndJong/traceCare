# System Overview
>해당파일 경로 docs/architecture/System_Overview.md

프로젝트: TraceCare — 아이·노인 케어 위치추적 알림 시스템
문서 위치: `docs/architecture/System_Overview.md`
버전: v1.0

> 이 문서는 **전체 시스템이 어떻게 맞물려 돌아가는지 한눈에 보여주는 것**만 담당한다.
> 각 컴포넌트의 상세 설계(왜 이렇게 만들었는지, 구체적인 API/스키마/정책)는 이 문서에서 다시 설명하지 않고 아래 원본 문서로 연결한다. 이 문서만 보고 "전체 그림"을 파악한 다음, 필요한 부분만 해당 문서로 들어가면 된다.

---

## 목차

1. 전체 시스템 구성도
2. 컴포넌트별 역할 요약
3. 핵심 데이터 흐름 — 위치 추적 → 알림
4. 핵심 데이터 흐름 — AI 예측/케어 비서
5. 인증 흐름 요약
6. 로컬 개발 환경 vs 배포 구조

---

## 1. 전체 시스템 구성도

```mermaid
graph TB
    subgraph Client["클라이언트"]
        Flutter["Flutter App<br/>(Guardian / CareTarget)"]
    end

    subgraph Infra["인프라 (docker-compose)"]
        Nginx["Nginx<br/>Reverse Proxy / TLS"]
        Backend["Spring Boot Backend<br/>com.tracecare.backend"]
        AIServer["FastAPI AI Server<br/>예측(XGBoost/LightGBM) + LLM 연동"]
        DB[("PostgreSQL + pgvector<br/>tracecare-db")]
        Redis[("Redis<br/>tracecare-redis")]
    end

    subgraph External["외부 서비스"]
        Google["Google OAuth2"]
        FCM["Firebase Cloud Messaging"]
        Gemini["Gemini LLM / Embedding API"]
    end

    Flutter -->|REST / WebSocket, HTTPS| Nginx
    Nginx --> Backend
    Backend -->|internal API, API Key 인증| AIServer
    Backend --> DB
    Backend --> Redis
    AIServer -->|모델 추론| AIServer
    AIServer --> Gemini
    Backend -->|OAuth 로그인| Google
    Backend -->|Push 발송| FCM
    FCM -->|Push 알림| Flutter
```

- 지금은 1인 개발·EC2 1대 포트폴리오 규모라 모든 컴포넌트가 `docker-compose.yml` 하나로 기동된다(현재는 `db`, `redis`만 정의됨 — `backend`/`ai-server` 컨테이너화는 추후 추가 예정).
- 설계 자체는 대규모 운영(수십만~수백만 사용자)을 전제로 되어 있다(`DATABASE_DESIGN_GUIDE.md` §1.1, 루트 `CLAUDE.md` IMPORTANT 항목) — 지금 인프라가 단순하다고 설계까지 단순화하지 않는다.

## 2. 컴포넌트별 역할 요약

| 컴포넌트 | 역할 | 상세 문서 |
|---|---|---|
| Flutter App | Guardian/CareTarget 공용 앱, Role에 따라 화면 분기 | `docs/frontend/Coding_Convention.md` |
| Spring Boot Backend | REST/WebSocket API, 인증/인가, 비즈니스 로직, 외부 서비스 오케스트레이션 | `docs/backend/Coding_Convention.md`, `docs/api/API_Specification.md` |
| FastAPI AI Server | 방문 예측(ML), AI Care Chat(LLM) — Backend만 호출하는 내부 전용 서버 | `docs/ai-server/Coding_Convention.md` |
| PostgreSQL (+pgvector) | 영구 저장. Master/Time-Series/Derived 3그룹으로 분류해 설계 | `docs/db/DATABASE_DESIGN_GUIDE.md` |
| Redis | Cache Aside(위치/장소/예측 등), Refresh Token, JWT Blacklist | `docs/backend/Cache_Strategy_Guide.md`, `docs/security/Security_Guide.md` §5 |
| Nginx | TLS Termination, Reverse Proxy | `docs/security/Security_Guide.md` §4.4(HTTPS) |
| Google OAuth2 | 소셜 로그인 전용(자체 비밀번호 미보관) | `docs/security/Security_Guide.md` §6 |
| FCM | Push 알림 발송 | `docs/backend/Cache_Strategy_Guide.md`(FCM Token 캐시), `docs/api/API_Response_Rule.md` §8.6 |
| Gemini API | LLM 응답 생성 + Embedding(RAG용 벡터 검색) | `docs/db/DATABASE_DESIGN_GUIDE.md` §11.3 |

## 3. 핵심 데이터 흐름 — 위치 추적 → 알림

루트 `CLAUDE.md`가 정의한 이 프로젝트의 핵심 흐름이다: **위치 수신 → 도착/이탈 판단(GeoFence) → 이상 이동 감지 → 알림(FCM) → AI 분석/비서**.

```mermaid
sequenceDiagram
    participant CT as CareTarget App
    participant BE as Spring Boot Backend
    participant Redis
    participant DB as PostgreSQL
    participant FCM
    participant GA as Guardian App

    CT->>BE: POST /api/care-target/location (GPS 좌표)
    BE->>Redis: location:latest:{careTargetId} 덮어쓰기
    BE->>DB: LocationHistory INSERT (비동기, 월 파티션)
    BE->>BE: /internal/geofence/check — Place(Redis 캐시) 반경 판정
    alt 등록 장소 도착/이탈 감지
        BE->>DB: VisitHistory 기록
        BE->>DB: NotificationHistory 기록 (type: ARRIVAL 등, §4.7)
        BE->>FCM: /internal/fcm/send
        FCM-->>GA: Push 알림 수신
    end
    GA->>BE: WebSocket 구독 (/ws/guardian/location)
    BE-->>GA: 실시간 위치 업데이트 (개인화 큐, Security_Guide.md §7.5)
```

- GeoFence 판정에 쓰는 Place 데이터는 매번 DB를 안 보고 Redis 캐시(`place:list:{guardianId}`, 5~10분 TTL)를 우선 사용한다(`Cache_Strategy_Guide.md` §3.2).
- FCM 발송 실패는 일반 알림과 긴급 알림(`EMERGENCY`)을 다르게 처리한다 — 긴급 알림은 실패해도 반드시 이력이 남고 fail-safe로 처리된다(`Exception_Handling_Rule.md` §9.2, `docs/api/API_Response_Rule.md` §5.2 EMERGENCY 도메인).
- WebSocket 인가(연결/구독)는 REST와 동일한 3단계 인가 구조를 따른다(`Security_Guide.md` §7.5).

## 4. 핵심 데이터 흐름 — AI 예측/케어 비서

```mermaid
sequenceDiagram
    participant GA as Guardian App
    participant BE as Spring Boot Backend
    participant Redis
    participant AI as FastAPI AI Server
    participant DB as PostgreSQL(+pgvector)
    participant Gemini

    Note over GA,DB: AI 방문 예측 (머신러닝)
    GA->>BE: GET /api/guardian/ai/predict/report
    BE->>Redis: prediction:{careTargetId}:{date} 캐시 조회
    alt 캐시 미스
        BE->>AI: /internal/ai/predict (API Key 인증)
        AI-->>BE: 예측 결과 (XGBoost/LightGBM)
        BE->>DB: PredictionHistory 저장
        BE->>Redis: 캐시 적재 (TTL 24시간)
    end
    BE-->>GA: 예측 결과 응답

    Note over GA,Gemini: AI 케어 비서 (LLM, RAG)
    GA->>BE: POST /api/guardian/ai/chat
    BE->>DB: ChatEmbedding에서 유사 대화 HNSW 검색(pgvector)
    BE->>Gemini: 관련 대화 + 질문 전달 (System/User 분리, Prompt Injection 방어)
    Gemini-->>BE: 응답 생성
    BE->>DB: ChatHistory 저장
    BE->>AI: Embedding 생성 요청 (gemini-embedding-001)
    BE->>DB: ChatEmbedding 저장
    BE-->>GA: 응답 반환
```

- AI 서버 응답 없음/LLM 호출 실패는 500으로 통일해서 처리한다(`Exception_Handling_Rule.md` §9.3, `AI_001`/`AI_002`).
- Prompt Injection 방어(시스템 프롬프트/사용자 입력 분리)는 `Security_Guide.md` §9 A05:2025(Injection) 대응 항목을 따른다.

## 5. 인증 흐름 요약

```mermaid
sequenceDiagram
    participant App as Flutter App
    participant BE as Spring Boot Backend
    participant Google
    participant Redis

    App->>Google: OAuth2 로그인
    Google-->>App: ID Token
    App->>BE: POST /api/auth/oauth/login (ID Token)
    BE->>Google: 토큰 검증
    BE->>BE: oauth_provider+oauth_id로 User 매핑/생성
    BE->>Redis: Refresh Token 저장 (§5.5)
    BE-->>App: Access/Refresh Token 발급
    Note over App: Role 미확정 시 PUT /api/auth/role 추가 호출 (최초 1회만)
```

- Access Token 만료 시 Flutter는 `AUTH_002` 코드를 받아 자동으로 `/api/auth/refresh`를 호출한다.
- 상세 흐름·Token Rotation·Blacklist 정책은 `docs/security/Security_Guide.md` §1~§5가 원본이다.

## 6. 로컬 개발 환경 vs 배포 구조

| 구분 | 로컬 개발 | 배포(목표) |
|---|---|---|
| 인프라 | `docker compose up -d` (db, redis) + 각 앱 로컬 실행 | EC2 1대, Docker Compose (포트폴리오 단계) |
| DB 접속 | `localhost:5433` | 컨테이너 내부 네트워크 |
| Redis | `localhost:6379`, 인증 없음 | `requirepass` 적용 필요(`docker-compose.yml` 주석 참고) |
| TLS | 없음(HTTP) | Nginx에서 TLS Termination 필수 |
| CI/CD | 수동 실행 | Jenkins + GitHub Webhook (`OWASP_Security_Guide.md` §3.3, §8) |

배포 아키텍처의 상세(Nginx 설정, Jenkins 파이프라인 구조, 스케일 아웃 전략)는 아직 별도 문서가 없다 — 필요해지면 `docs/architecture/Deployment_Architecture.md`로 분리한다(지금은 범위 밖).
