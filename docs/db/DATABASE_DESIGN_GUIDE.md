# TraceCare 프로젝트 — 최종 데이터베이스 설계 문서

- **프로젝트**: 아이·노인 케어 위치추적 알림 시스템 (GIS)
- **대상 DBMS**: PostgreSQL (+ pgvector), 캐시: Redis
- **문서 버전**: v6.1 (최종 확정본 — idx_vh_place 인덱스 §9 정식 반영, Partial Index 개선)
- **전제**: 포트폴리오 프로젝트이나, 실 서비스 운영 시 수십만~수백만 사용자·수억 건의 위치 데이터를 처리하는 상황을 가정하여 설계

본 문서는 데이터베이스 스키마·저장 전략·권한 모델의 DB 반영을 담당한다. 인증/인가 상세 정책은 Security Guide, 예외 처리는 Exception Handling Rule, API 응답 형식은 API Response Rule, 로그 보관·마스킹은 Logging Guide를 따르며 본 문서에서 재정의하지 않는다.

- **원본 기준선**: 05_trace_care_프로젝트_기획서_0806.pdf의 8개 테이블 DDL
- **반영 문서**: DATABASE_DESIGN_GUIDE.pdf v1.0 (정부/공공기관 DB 설계 기준 + 대규모 트래픽 대응 관점 검토 완료본)
- **추가 반영 ①**: Guardian-CareTarget 권한 정책 검토 결과 (PRIMARY/SUB 역할 모델 확정)
- **추가 반영 ②**: §14 미확정 사항 12개 항목 정책 결정 결과 (NotificationHistory 구조 보완, 보관기간·임베딩 차원·PostgreSQL 버전 확정, PRIMARY 승계 정책, 긴급 알림 그룹핑 등)
- **추가 반영 ③**: Guardian-CareTarget 초대/승인 기반 관계 생성 절차 확정 (Redis 기반, DB 테이블 없음), NotificationHistory.type 최종 7종 확정 및 is_retry 컬럼 보완, 얼굴 인증 스코프아웃 최종 확정
- **추가 반영 ④**: §14 잔여 3개 항목 (등록 상한 수치, 초대 코드 Rate Limit 수치, type API 표기)까지 전부 확정 — §1~§14 전 항목 결정 완료
- **추가 반영 ⑤**: §5에 LocationHistory 복합 PK 관련 "id 단독 조회" 실무 유의사항 (구현 체크리스트) 보강
- **추가 반영 ⑥**: AI Care Assistant Embedding 계층을 Google Gemini Embedding(gemini-embedding-001, VECTOR(768))으로 최종 확정 (공식 문서 근거), RAG 목적 정의 수정, HNSW 유지 재검증, Gemini 사용량 3분류(LLM/Embedding/Vector 검색) 명시
- **추가 반영 ⑦**: DDL 실행 검증 (PostgreSQL 16 + pgvector 실제 실행) 과정에서 발견된 idx_vh_place 인덱스 누락을 §9에 정식 반영 (Partial Index로 개선), DDL과 설계 문서 간 완전 동기화. SQL DDL 스크립트는 본 단계에서 생성하지 않는다 (다음 단계 작업 대상).

---

## 목차

1. 최종 데이터베이스 설계 개요
2. 최종 테이블 목록
3. 테이블별 상세 정의
4. 컬럼 정의
5. PK / FK 정의
6. 관계 및 카디널리티
7. 제약조건 정의
8. ID 생성 전략
9. 인덱스 정의
10. 이력 데이터 보존 정책
11. PostgreSQL / pgvector 설계
12. 기존 기획서 대비 변경사항
13. 설계 결정 및 근거
14. 미확정 사항 및 결정 필요 항목

---

## 1. 최종 데이터베이스 설계 개요

### 1.1 대상 및 전제

| 항목 | 내용 |
|---|---|
| DBMS | PostgreSQL (+ pgvector 확장) |
| 캐시 | Redis (Cache Aside — 본 문서 범위 밖, Security Guide/Exception Handling Rule 참조) |
| 설계 전제 | 현재는 1인 개발·EC2 1대 포트폴리오 규모이나, 상용화 시 수십만~수백만 사용자·수억 건의 위치 데이터를 처리하는 상황을 가정 |
| 원본 스키마 | 8개 테이블 (User, GuardianTarget, Place, LocationHistory, VisitHistory, PredictionHistory, NotificationHistory, ChatHistory) |
| 확정 스키마 | 9개 테이블 (원본 8개 + ChatEmbedding 1개 신설) |

### 1.2 데이터 그룹 분류 (설계 원칙의 출발점)

| 그룹 | 해당 테이블 | 설계 우선순위 |
|---|---|---|
| Master Data | User, GuardianTarget, Place | 정합성(FK/UNIQUE/NOT NULL) 최우선, 쓰기 적음·읽기 많음 |
| Time-Series Data | LocationHistory, VisitHistory, NotificationHistory | 쓰기 처리량·파티셔닝·보관주기 우선 |
| Derived Data | PredictionHistory, ChatHistory, ChatEmbedding | 재생성 가능성 전제, 정합성보다 가용성 우선, 백업 우선순위 낮음 |

이 분류는 정규화 수준, Cascade 정책, 인덱스, 백업 우선순위 등 모든 하위 절의 판단 기준으로 일관되게 적용했다.

### 1.3 확정 원칙 요약

- **정규화**: Master Data는 3NF 기본. VisitHistory.place_name 같은 스냅샷 컬럼은 "이력 불변성 보장" 목적이므로 비정규화가 아닌 정상 설계로 유지.
- **Soft Delete**: 참조 무결성이 중요하고 이력 조회 가능성이 있는 User, Place에만 적용. 이벤트성 로그 테이블은 Hard Delete(보관기간 경과 후) 원칙.
- **PK 전략**: 단일 Primary 노드 구조이므로 Auto Increment(BIGINT IDENTITY) 유지 + 외부 노출용 UUID(public_id) 병행.
- **이력 데이터 불변성**: LocationHistory/VisitHistory/NotificationHistory/ChatHistory/PredictionHistory는 생성 후 원칙적으로 UPDATE 금지 (단, 알림의 read_at/response_at처럼 "후속 상태 기록"은 예외).
- **Guardian 권한 모델**: Guardian 내부에 PRIMARY/SUB 2단계 역할을 도입하여, 안전 관련 기능은 전원 공유하고 관리형 기능(정보 수정, 구성원 변경, Place 등록 포함)은 대표 1인에게 집중시켜 다자 간 데이터 충돌을 구조적으로 방지한다.

---

## 2. 최종 테이블 목록

| No | 테이블명 | 분류 | Soft Delete | PK 타입 | 파티셔닝 | 변경 여부 |
|---|---|---|---|---|---|---|
| 1 | User | Master | 적용 | BIGINT IDENTITY | 불필요 | 컬럼 추가/변경 |
| 2 | GuardianTarget | Master | 상태 컬럼 대체 | BIGINT IDENTITY | 불필요 | 컬럼 추가/변경 (역할 모델 반영) |
| 3 | Place | Master | 적용 | BIGINT IDENTITY | 불필요 | 컬럼 추가/변경 (등록 권한 정책 반영) |
| 4 | LocationHistory | Time-Series | 미적용 (정책 삭제) | BIGINT + recorded_at 복합 PK | **필수 적용** | 구조 변경 (파티셔닝) |
| 5 | VisitHistory | Time-Series | 미적용 | BIGINT IDENTITY | 조건부 권장 | 컬럼 추가/변경 |
| 6 | PredictionHistory | Derived | 미적용 | BIGINT IDENTITY | 불필요 | 컬럼 추가/변경 |
| 7 | NotificationHistory | Time-Series | 미적용 | BIGINT IDENTITY | 조건부 권장 | 컬럼 추가/변경 |
| 8 | ChatHistory | Derived | 미적용 (요청 시 즉시 삭제) | BIGINT IDENTITY | 불필요 | 타입 변경만 |
| 9 | ChatEmbedding (신규) | Derived | 미적용 | BIGINT IDENTITY | 불필요 | 신규 |

ChatEmbedding은 임의로 추가한 기능이 아니라, 원본 기획서 기술스택 표에 이미 "pgvector: AI Vector Embedding 저장 및 유사도 검색"이 명시되어 있음에도 기준선 DDL에서 누락된 테이블이다. 즉 **이미 계획된 기능의 누락 보완**이다 (근거는 13장 참고).

---

## 3. 테이블별 상세 정의

### 3.1 User — 전체 사용자(Guardian/CareTarget/Admin) 통합 관리

Role 컬럼으로 구분하는 Single Table 설계를 유지한다. Guardian과 CareTarget이 프로필 구조상 크게 다르지 않다는 원본 설계 의도가 타당하므로 테이블 분리는 하지 않는다.

- **주요 변경**: 로그인 식별 기준을 email → oauth_id 기준으로 전환 (이메일은 변경 가능한 값이라 식별키로 부적합), 외부 노출 식별자(public_id) 분리, Soft Delete 도입.

### 3.2 GuardianTarget — Guardian-CareTarget 다대다 관계 해소 테이블 (권한 모델 반영)

관계 자체가 부가 정보(relation, 역할, 상태)를 가지므로 복합 PK가 아닌 대리키(id)를 유지한다.

- **주요 변경**: 관계 해제 시 물리적 삭제 대신 상태(ACTIVE/TERMINATED) 컬럼으로 이력을 남기도록 변경. 여기에 더해 guardian_role(PRIMARY/SUB) 컬럼을 신설하여 CareTarget 1명당 대표 보호자를 정확히 1명으로 강제하고, 관리형 기능(구성원 변경, Place 등록 등)의 권한 기준점으로 사용한다.
- **정원 확정**: CareTarget 1명당 ACTIVE Guardian은 최대 3명 (확정 사항).
- **관계 생성 절차 확정 (신규)**: GuardianTarget 행은 직접 INSERT되지 않고, 반드시 초대(Invitation) + CareTarget 승인 절차를 통해서만 생성된다. 초대 토큰은 PostgreSQL이 아닌 Redis에 TTL로 저장하며 (예: `invite:token:{token}` → `{careTargetId, purpose, guardianId?}`), CareTarget의 최종 승인이 완료되는 트랜잭션 내에서 GuardianTarget 행을 생성하고 동시에 Redis 토큰을 폐기한다. 이 트랜잭션이 바로 §7에 정의된 정원(3명) 카운트 검증(SELECT...FOR UPDATE)과 PRIMARY 유일성 제약이 실행되는 지점이다. Invitation은 순간적·소멸성 데이터이므로 별도의 PostgreSQL 테이블을 두지 않는다.

### 3.3 Place — 보호자가 등록하는 GeoFence(안심구역) 기준 정보 (등록 권한 정책 반영)

- **주요 변경**: 위경도/반경 CHECK 제약 추가, Soft Delete, 낙관적 락(version) 추가.
- **권한 정책 반영**: Place의 등록(CREATE)·수정(UPDATE)·삭제(DELETE)는 해당 CareTarget의 ACTIVE PRIMARY Guardian만 수행할 수 있다. SUB Guardian은 조회만 가능하다. 이는 여러 보호자가 동시에 안심구역을 편집하면서 발생하는 데이터 충돌을 구조적으로 차단하기 위한 결정이다.

### 3.4 LocationHistory — CareTarget GPS 원본 위치 (프로젝트에서 가장 빠르게 증가하는 테이블)

CareTarget 10만 명 × 30초 간격 전송 가정 시 1일 약 2.88억 건, 연 약 1,000억 건 규모로 추정된다. 이 규모에서는 파티셔닝이 선택이 아닌 필수다.

- **주요 변경**: 월 단위 Range Partitioning(recorded_at 기준) 적용, 이에 따른 PK 구조 변경, User 탈퇴 시 CASCADE 대신 RESTRICT + 비동기 파기로 전환.

### 3.5 VisitHistory — GPS 원본을 분석해 만든 "방문 단위" 가공 이력

AI 예측(Feature Engineering)의 입력 데이터로 사용된다. place_name은 방문 당시 스냅샷으로, 이후 Place 정보가 바뀌어도 과거 기록이 왜곡되지 않도록 유지한다.

- **주요 변경**: 원본 Place 연결용 place_id(nullable) 추가, 유효성 CHECK 추가, User 탈퇴 시 RESTRICT 전환.

### 3.6 PredictionHistory — AI 서버(FastAPI)가 생성한 방문 예측 결과

원본 데이터(VisitHistory/LocationHistory)가 보존되어 있으면 재계산 가능한 Derived Data이므로 백업 우선순위가 상대적으로 낮다.

- **주요 변경**: 확률 범위 CHECK, 배치 재실행 시 중복 적재 방지용 UNIQUE 추가.

### 3.7 NotificationHistory — 알림 발송/읽음/응답 이력 (정책 결정 반영)

긴급 알림(GeoFence 이탈 등)은 FCM 발송 실패 시에도 감사 목적상 행이 반드시 남아야 한다. 다수 Guardian에게 개별 발송되는 구조이므로, 동일 이벤트에 대한 응답 상태를 event_id로 상관관계 지정해 상호 참조할 수 있게 했다.

- **주요 변경**: status/type 화이트리스트화(CHECK, 7종 최종 확정), 원 기획서에 명시되었으나 누락되어 있던 target_id(보호 대상자 ID)·is_retry(재발송 여부) 컬럼 보완, 다인 발송 그룹핑을 위한 event_id(UUID) 컬럼 신설. 7종 타입 및 채택/제외 근거는 §13 참고.

### 3.8 ChatHistory — AI Care Assistant 대화 이력

- **주요 변경**: 타입만 조정(BIGINT 등), 개인정보 성격이 강해 사용자 삭제 요청 시 즉시 반영이 필요하다는 점을 정책적으로 명시.

### 3.9 ChatEmbedding — (신규) 대화 벡터 임베딩 저장 (Gemini Embedding 최종 반영)

**RAG의 정확한 목적 정의**: 벡터 저장은 "과거 대화를 압축해 저장하는 것"이 아니다. 과거 대화 전체를 LLM에 매번 전달하지 않고, 현재 질문과 의미적으로 관련성이 높은 과거 대화만 벡터 검색으로 조회해 RAG 컨텍스트로 제공함으로써, 불필요한 입력 컨텍스트와 LLM 입력 토큰을 줄이는 것이 목적이다. 대화 원문(ChatHistory)은 삭제되지 않고 그대로 유지되며, ChatEmbedding은 검색을 위한 별도 인덱스 역할만 한다.

**Embedding 모델 확정**: LLM Provider가 Google Gemini로 확정됨에 따라, Embedding도 동일 생태계인 Gemini Embedding(gemini-embedding-001)으로 통일한다. 동일 API 키로 LLM·Embedding을 함께 관리할 수 있어 사용량 추적이 단순해진다(§8 참고). 출력 차원은 공식 권장 3종(768/1536/3072) 중 768차원으로 확정한다 — MRL(Matryoshka Representation Learning) 기법상 차원을 낮춰도 품질 손실이 크지 않으면서, 포트폴리오~초기 상용화 규모에서 저장 공간·인덱스 크기·검색 속도 이점이 더 실질적이기 때문이다 (비교 근거는 §13 참고).

---

## 4. 컬럼 정의

### 4.1 User

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| id | BIGINT (IDENTITY) | NOT NULL | — | 내부 PK |
| public_id | UUID | NOT NULL | gen_random_uuid() | 외부 노출용 식별자 (URL/API 응답) |
| email | VARCHAR(255) | NOT NULL | — | 표시/알림 발송용. 계정 식별키 아님 |
| oauth_provider | VARCHAR(20) | NOT NULL | 'GOOGLE' | 소셜 로그인 제공자 |
| oauth_id | VARCHAR(255) | NOT NULL | — | 계정 매핑 기준 키 |
| name | VARCHAR(100) | NOT NULL | — | 사용자명 |
| phone | VARCHAR(20) | NULL | — | 연락처 (비상연락 등) |
| role | VARCHAR(20) | NOT NULL | — | ADMIN / GUARDIAN / CARE_TARGET |
| profile_image | TEXT | NULL | — | 프로필 이미지 URL |
| created_at | TIMESTAMPTZ | NOT NULL | CURRENT_TIMESTAMP | 생성 시각 |
| updated_at | TIMESTAMPTZ | NOT NULL | CURRENT_TIMESTAMP | 수정 시각 (트리거 자동 갱신) |
| deleted_at | TIMESTAMPTZ | NULL | — | Soft Delete 시각 |

### 4.2 GuardianTarget (권한 모델 반영 — 최종)

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| id | BIGINT (IDENTITY) | NOT NULL | — | PK |
| guardian_id | BIGINT | NOT NULL | — | User(보호자) 참조 |
| target_id | BIGINT | NOT NULL | — | User(보호대상자) 참조 |
| guardian_role | VARCHAR(10) | NOT NULL | 'SUB' | PRIMARY(대표 보호자) / SUB(보조 보호자) |
| relation | VARCHAR(50) | NULL | — | 관계 라벨 (가족관계 등) |
| status | VARCHAR(20) | NOT NULL | 'ACTIVE' | ACTIVE / TERMINATED |
| created_at | TIMESTAMPTZ | NOT NULL | CURRENT_TIMESTAMP | 관계 등록 시각 (대표 승계 판단 시 선임순 기준으로도 활용 가능) |
| terminated_at | TIMESTAMPTZ | NULL | — | 관계 해제 시각 |

### 4.3 Place

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| id | BIGINT (IDENTITY) | NOT NULL | — | PK |
| user_id | BIGINT | NOT NULL | — | User(보호자, 등록 시점 기준 ACTIVE PRIMARY Guardian) 참조 |
| name | VARCHAR(150) | NOT NULL | — | 장소명 |
| address | TEXT | NULL | — | 주소 |
| latitude | NUMERIC(10,7) | NOT NULL | — | 위도 |
| longitude | NUMERIC(11,7) | NOT NULL | — | 경도 |
| radius | INT | NOT NULL | 100 | GeoFence 반경(m) |
| version | INT | NOT NULL | 0 | 낙관적 락 |
| created_at | TIMESTAMPTZ | NOT NULL | CURRENT_TIMESTAMP | 생성 시각 |
| updated_at | TIMESTAMPTZ | NOT NULL | CURRENT_TIMESTAMP | 수정 시각 |
| deleted_at | TIMESTAMPTZ | NULL | — | Soft Delete 시각 |

### 4.4 LocationHistory

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| id | BIGINT (IDENTITY) | NOT NULL | — | PK 구성요소 (순번) |
| user_id | BIGINT | NOT NULL | — | User(보호대상자) 참조 |
| latitude | NUMERIC(10,7) | NOT NULL | — | 위도 |
| longitude | NUMERIC(11,7) | NOT NULL | — | 경도 |
| recorded_at | TIMESTAMPTZ | NOT NULL | — | 위치 기록 시각 (파티션 키, PK 구성요소) |

### 4.5 VisitHistory

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| id | BIGINT (IDENTITY) | NOT NULL | — | PK |
| user_id | BIGINT | NOT NULL | — | User(보호대상자) 참조 |
| place_id | BIGINT | NULL | — | Place 참조 (등록 장소 방문 시) |
| place_name | VARCHAR(150) | NULL | — | 방문 당시 장소명 스냅샷 |
| latitude | NUMERIC(10,7) | NOT NULL | — | 위도 |
| longitude | NUMERIC(11,7) | NOT NULL | — | 경도 |
| arrival_time | TIMESTAMPTZ | NOT NULL | — | 도착 시각 |
| departure_time | TIMESTAMPTZ | NULL | — | 출발 시각 |
| stay_minutes | INT | NULL | — | 체류 시간(분) |
| is_registered_place | BOOLEAN | NOT NULL | FALSE | 등록 장소 방문 여부 |
| created_at | TIMESTAMPTZ | NOT NULL | CURRENT_TIMESTAMP | 행 생성 시각 (누락분 보완) |

### 4.6 PredictionHistory

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| id | BIGINT (IDENTITY) | NOT NULL | — | PK |
| user_id | BIGINT | NOT NULL | — | User(보호대상자) 참조 |
| predicted_place | VARCHAR(150) | NOT NULL | — | 예측 장소 |
| probability | NUMERIC(4,3) | NOT NULL | — | 예측 확률(0~1) |
| prediction_date | DATE | NOT NULL | — | 예측 대상 일자 |
| created_at | TIMESTAMPTZ | NOT NULL | CURRENT_TIMESTAMP | 행 생성 시각 (누락분 보완) |

### 4.7 NotificationHistory (정책 결정 반영 — 최종)

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| id | BIGINT (IDENTITY) | NOT NULL | — | PK |
| user_id | BIGINT | NOT NULL | — | User 참조 (알림 수신자, Guardian/CareTarget 모두 가능) |
| target_id | BIGINT | NOT NULL | — | User(보호 대상자) 참조 — 원 기획서 "알림 이력 관리" 저장 항목에 명시된 "보호 대상자 ID"의 누락분 보완 |
| type | VARCHAR(50) | NOT NULL | — | 알림 종류 — CHECK로 7종 최종 확정 (§7 참고) |
| event_id | UUID | NOT NULL | gen_random_uuid() | 동일 트리거로 다수 Guardian에게 발송된 행을 상호 식별하는 그룹 키 (§14 항목 12 결정 반영) |
| is_retry | BOOLEAN | NOT NULL | FALSE | 재발송 여부 — 원 기획서 저장 항목("재발송 여부")의 누락분 보완. "재알림"은 별도 type이 아니라 동일 type의 재발송으로 처리 |
| sent_at | TIMESTAMPTZ | NOT NULL | CURRENT_TIMESTAMP | 발송 시각 |
| read_at | TIMESTAMPTZ | NULL | — | 읽음 시각 |
| response_at | TIMESTAMPTZ | NULL | — | 응답 시각 |
| status | VARCHAR(20) | NOT NULL | 'SENT' | SENT/READ/RESPONDED/FAILED |

target_id·is_retry 추가는 새로운 결정이 아니라 원 기획서에 이미 명시된 요구사항("저장 항목: 사용자 ID, 보호 대상자 ID, 재발송 여부")의 반영 누락을 바로잡은 것이다. event_id는 하나의 이벤트(예: 긴급 알림 1건)가 여러 Guardian에게 개별 행으로 발송될 때, 같은 이벤트임을 식별해 "다른 보호자가 이미 확인함" 등을 표시하기 위한 상관관계 키다. type의 최종 7종 확정 근거는 §13 참고.

### 4.8 ChatHistory

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| id | BIGINT (IDENTITY) | NOT NULL | — | PK |
| user_id | BIGINT | NOT NULL | — | User 참조 |
| question | TEXT | NOT NULL | — | 질문 원문 |
| answer | TEXT | NOT NULL | — | 답변 원문 |
| created_at | TIMESTAMPTZ | NOT NULL | CURRENT_TIMESTAMP | 대화 생성 시각 |

### 4.9 ChatEmbedding (신규)

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| id | BIGINT (IDENTITY) | NOT NULL | — | PK |
| chat_history_id | BIGINT | NOT NULL | — | ChatHistory 1:1 참조 |
| embedding | VECTOR(768) | NOT NULL | — | 임베딩 벡터 — Gemini gemini-embedding-001, output_dimensionality=768 기준 최종 확정 (§13 근거). 모델 교체 시 전량 재임베딩 필요 (§11.3 마이그레이션 원칙 참고) |
| created_at | TIMESTAMPTZ | NOT NULL | CURRENT_TIMESTAMP | 생성 시각 |

---

## 5. PK / FK 정의

| 테이블 | PK | FK | 참조 대상 |
|---|---|---|---|
| User | id | — | — |
| GuardianTarget | id | guardian_id, target_id | User(id), User(id) |
| Place | id | user_id | User(id) |
| LocationHistory | (id, recorded_at) — 복합 | user_id | User(id) |
| VisitHistory | id | user_id, place_id | User(id), Place(id) |
| PredictionHistory | id | user_id | User(id) |
| NotificationHistory | id | user_id, target_id | User(id), User(id) |
| ChatHistory | id | user_id | User(id) |
| ChatEmbedding | id | chat_history_id | ChatHistory(id) |

LocationHistory의 PK가 (id, recorded_at) 복합키인 이유: PostgreSQL 선언적 파티셔닝은 파티션 키 컬럼이 PK/UNIQUE에 포함되어야 하는 제약이 있다. 애플리케이션에서 id 단독으로 특정 행을 조회하는 로직이 있다면, 조회 조건에 recorded_at(또는 범위)을 함께 제공하도록 API/쿼리 설계를 조정해야 한다 — 백엔드 구현 단계에서 반드시 확인 필요.

### 5.1 LocationHistory "id 단독 조회" 실무 유의사항 (구현 단계 체크리스트)

기획서상 LocationHistory 관련 조회 기능은 대부분 user_id + 날짜/시간 범위 기반으로 설계되어 있어(§5.1-A), 원래 설계 의도대로 구현하면 이 문제가 거의 발생하지 않는다. 다만 구현 과정에서 개발자가 무심코 "id 단독 조회"에 빠지기 쉬운 지점이 있어(§5.1-B) 실무 체크리스트로 별도 정리한다.

#### A. 이미 안전하게 설계된 부분 (기획서 근거)

| 기능 | 조회 방식 | 근거 |
|---|---|---|
| 특정 기간 위치 이력 조회 | user_id + 날짜 기반 Select | "ID/날짜 기반 Select 쿼리" |
| AI 케어비서 "오늘 이동기록 요약해줘" | 당일 날짜 범위로 LocationHistory 조회 | AI 케어 비서 시나리오 |
| 자연어 검색 "지난주 병원 방문 기록 보여줘" | 지난주 날짜 범위로 LocationHistory 검색 | 자연어 검색 시나리오 |
| AI 이상행동 설명 | 특정 시간대 범위 분석("2시간 이상 머무름") | 이상행동 설명 시나리오 |

이 조회들은 원래부터 recorded_at 조건이 자연스럽게 포함되므로 파티셔닝과 충돌 없이 그대로 동작한다.

#### B. 실무에서 id 단독 조회 함정에 빠지기 쉬운 지점

| 지점 | 상황 | 위험도 | 대응 방안 |
|---|---|---|---|
| 지도 마커 클릭 → 상세 조회 | 이동경로 지도에 표시된 특정 지점을 클릭해 상세 정보(정확한 시각, 주소)를 재조회하는 API를 설계할 때, id만 서버로 전달하기 쉬움 | 높음 (실사용 시나리오상 거의 확실히 발생) | 지도에 포인트 데이터를 내려줄 때부터 id와 recorded_at을 함께 프론트엔드에 전달하고, 상세 조회 API도 두 값을 함께 받도록 설계 |
| AI 이상행동 "근거 지점" 상세 보기 | Guardian이 이상행동 알림의 근거가 된 위치를 다시 조회하려 할 때, AI 서버 응답에 LocationHistory id만 담기 쉬움 | 중간 (기능 고도화 시 발생 가능) | AI 서버 응답 스펙에 id뿐 아니라 recorded_at(또는 최소 날짜)도 함께 포함 |
| Spring Data JPA 기본 메서드 오남용 | `JpaRepository<LocationHistory, Long>` 형태로 PK를 단일 컬럼처럼 선언하고 `findById(id)`를 그대로 사용 | 높음 (개발자 실수로 흔히 발생) | LocationHistory는 복합키 `@IdClass`/`@EmbeddedId`로 매핑하고, `findById()` 단독 사용을 코드 리뷰 체크리스트에서 금지 |
| 향후 NotificationHistory ↔ LocationHistory 연결 기능 | "이 알림이 발생한 그 위치"를 클릭해 상세를 보는 기능이 추가될 경우, 알림 저장 시 LocationHistory id만 저장하기 쉬움 | 낮음 (현재 스키마에는 해당 FK 없음) | 향후 이런 참조 컬럼을 추가한다면 recorded_at도 함께 저장 |

위 표는 새로운 기능이나 컬럼을 추가하는 결정이 아니라, 이미 확정된 LocationHistory 복합 PK 구조를 실제로 구현할 때 놓치기 쉬운 지점을 미리 문서화한 구현 가이드다. 백엔드 팀 온보딩 및 코드 리뷰 체크리스트에 반영할 것을 권장한다.

---

## 6. 관계 및 카디널리티 (권한 모델 반영 — 최종)

```
User(Guardian) ──1:N──> GuardianTarget <──N:1── User(CareTarget)
User(Guardian,PRIMARY 한정) ──1:N──> Place ──0..N:1(optional)──> VisitHistory
User(CareTarget) ──1:N──> LocationHistory
User(CareTarget) ──1:N──> VisitHistory
User(CareTarget) ──1:N──> PredictionHistory
User ──1:N──> NotificationHistory
User ──1:N──> ChatHistory ──1:0..1──> ChatEmbedding
```

| 관계 | 카디널리티 | 비고 |
|---|---|---|
| User(Guardian) ↔ User(CareTarget) | M:N (GuardianTarget으로 해소) | CareTarget 1명당 ACTIVE Guardian 최대 3명(확정), 그중 ACTIVE PRIMARY는 정확히 1명. Guardian 1명이 담당하는 CareTarget 수는 별도 상한 없음 |
| User(Guardian, PRIMARY 한정) → Place | 1:N | Place 등록(CREATE)·수정·삭제는 PRIMARY Guardian만 수행. SUB는 조회만 가능 (권한 정책 확정 반영) |
| User(CareTarget) → LocationHistory | 1:N | 사용자당 초~분 단위로 무한 증가 |
| User(CareTarget) → VisitHistory | 1:N | LocationHistory보다 느린 증가 속도 |
| Place → VisitHistory | 0..1:N (optional) | 미등록 장소 방문 시 place_id NULL |
| User → PredictionHistory / NotificationHistory / ChatHistory | 1:N | — |
| ChatHistory → ChatEmbedding | 1:0..1 | 임베딩 생성 실패/미처리 시 0 |

GuardianTarget 카디널리티 및 Place 등록 권한 범위는 이전 [결정 필요] 항목이었으나 권한 정책 검토를 통해 완전히 확정되었다.

---

## 7. 제약조건 정의 (권한 모델 반영 — 최종)

| 테이블 | 제약 유형 | 내용 | 비고 |
|---|---|---|---|
| User | UNIQUE | (oauth_provider, oauth_id) | — |
| User | UNIQUE (Partial) | email — WHERE deleted_at IS NULL | 탈퇴 후 동일 이메일 재가입 허용 |
| User | UNIQUE | public_id | — |
| User | CHECK | role IN ('ADMIN','GUARDIAN','CARE_TARGET') | — |
| GuardianTarget | CHECK | guardian_id <> target_id | 자기 자신 등록 방지 |
| GuardianTarget | CHECK | status IN ('ACTIVE','TERMINATED') | — |
| GuardianTarget | CHECK | guardian_role IN ('PRIMARY','SUB') | 신규 |
| GuardianTarget | UNIQUE (Partial) | (guardian_id, target_id) — WHERE status='ACTIVE' | 동일 쌍 중복 등록 방지 |
| GuardianTarget | UNIQUE (Partial, 신규) | (target_id) — WHERE guardian_role='PRIMARY' AND status='ACTIVE' | CareTarget당 ACTIVE 대표는 정확히 1명이라는 불변식을 DB가 강제 |
| GuardianTarget | 문서화 (DB 제약 아님) | CareTarget당 ACTIVE 관계 최대 3건 | 카운트 기반 규칙은 선언적 제약으로 표현 불가 → 애플리케이션에서 SELECT ... FOR UPDATE 후 카운트 검증 (운영 규모 확대 시 트리거 도입 재검토) |
| GuardianTarget | 문서화 (DB 제약 아님, 최종 확정) | Guardian 1인당 등록 가능 CareTarget 수 | 하드 상한 없음, 애플리케이션 소프트 상한 10명 적용 (설정값으로 관리). 다자녀·다중 부모 돌봄 등 현실적 가족 구성을 여유 있게 수용하면서 어뷰징성 대량 등록은 차단 |
| Place | CHECK | latitude BETWEEN -90 AND 90 | — |
| Place | CHECK | longitude BETWEEN -180 AND 180 | — |
| Place | CHECK | radius > 0 | — |
| Place | 문서화 (DB 제약 아님) | 등록(INSERT)·수정(UPDATE)·삭제(DELETE)는 요청자가 해당 CareTarget의 ACTIVE PRIMARY Guardian인 경우에만 허용 | Guardian의 role은 GuardianTarget 테이블 값이므로 Place 테이블 자체 CHECK로 표현 불가한 교차 테이블 규칙 → Service 계층 책임 (Exception Handling Rule 7장 Business Exception) |
| Place | 문서화 (DB 제약 아님, 최종 확정) | CareTarget 1인당 Place 등록 수 상한 | 하드 상한 없음, 애플리케이션 소프트 상한 15개 적용 (설정값으로 관리). 가이드의 평균 추정치(3~5개)의 약 3배 여유. GuardianTarget 정원 처리와 동일 원칙 (DB 제약 대신 애플리케이션 카운트 검증) 적용 |
| LocationHistory | CHECK | latitude/longitude 범위 | — |
| VisitHistory | CHECK | departure_time >= arrival_time (또는 NULL) | — |
| VisitHistory | CHECK | stay_minutes >= 0 (또는 NULL) | — |
| PredictionHistory | CHECK | probability BETWEEN 0 AND 1 | — |
| PredictionHistory | UNIQUE | (user_id, prediction_date, predicted_place) | — |
| NotificationHistory | CHECK | status IN ('SENT','READ','RESPONDED','FAILED') | — |
| NotificationHistory | CHECK (최종 확정) | type IN ('ARRIVAL','ARRIVAL_CONFIRM_REQUEST','ARRIVAL_CONFIRMED','EMERGENCY','AI_ANOMALY','AI_PREDICTION','AI_WEEKLY_REPORT') | 원 기획서 GeoFence 상세 시나리오까지 정밀 재검토해 최종 7종 확정(§13). GEOFENCE_EXIT·UNREGISTERED_PLACE·GUARDIAN_TARGET_LINKED(초대 이벤트로 별도 관리)는 근거 부족 또는 명시적 제외로 미포함 |
| ChatEmbedding | UNIQUE | chat_history_id | 1:1 보장 |

**NOT NULL 적용 기준**: "일단 nullable로 두고 나중에 채운다"는 설계를 지양하고, 값이 없을 수 있는 경우(phone, address, profile_image, place_name, departure_time, stay_minutes, place_id 등)에만 명시적으로 nullable 처리했다.

### ON DELETE / ON UPDATE 정책 (전체 확정)

| FK | ON DELETE | ON UPDATE | 근거 |
|---|---|---|---|
| GuardianTarget → User | CASCADE | NO ACTION | 관계 데이터 양이 적어 부담 낮음. User는 Soft Delete가 기본이므로 실제 CASCADE는 완전 파기(Hard Delete) 시점에만 발동 |
| Place → User | CASCADE | NO ACTION | 동일 이유, 데이터량 적음 |
| LocationHistory → User | RESTRICT | NO ACTION | 수억 건 자식 행 동기 CASCADE 시 장시간 락 발생 → 비동기 배치(파티션 DROP/익명화)로 처리 |
| VisitHistory → User | RESTRICT | NO ACTION | 위와 동일 |
| VisitHistory → Place | SET NULL | NO ACTION | 원본 장소가 삭제돼도 place_name 스냅샷으로 이력은 유지 |
| PredictionHistory → User | RESTRICT | NO ACTION | 위와 동일 |
| NotificationHistory → User(수신자, user_id) | RESTRICT | NO ACTION | 위와 동일 |
| NotificationHistory → User(보호대상자, target_id) | RESTRICT | NO ACTION | 동일 이유. target_id는 §14 항목 3 결정으로 신규 추가된 컬럼 |
| ChatHistory → User | RESTRICT | NO ACTION | 위와 동일. 단 개인정보 삭제 요청 시 애플리케이션이 즉시 삭제 처리 |
| ChatEmbedding → ChatHistory | CASCADE | NO ACTION | 1:1 파생 데이터, 원본 삭제 시 함께 삭제되는 것이 자연스러움 |

모든 FK의 ON UPDATE는 NO ACTION(기본값)을 유지한다 — PK 값은 애플리케이션에서 변경되지 않는 값이므로 CASCADE가 불필요하다.

### 동시성 제어 관련 운영 참고사항 (제약과 함께 지켜야 할 규칙)

- **GuardianTarget 신규 등록**: 대상 CareTarget(User) 행에 `SELECT ... FOR UPDATE`(비관적 락) 적용 후 정원 카운트 검증 — 정원 제약을 DB가 아닌 애플리케이션이 담당하는 만큼, 이 락 없이는 동시 등록 시 3명을 초과할 수 있음. 이 락과 카운트 검증은 초대(Invitation) 승인 트랜잭션의 마지막 단계, 즉 CareTarget이 승인해 GuardianTarget 행을 생성하는 바로 그 시점에 실행된다(§3.2 참고).
- **초대 코드 생성/입력 시도 Rate Limit(최종 확정)**: 짧은 시간 내 반복적인 초대 코드 생성이나 무차별 입력 시도를 막기 위해 Redis 카운터 기반의 Rate Limit을 적용한다. 코드 생성: CareTarget 1인당 5회/일(Redis 카운터, 자정 TTL), 토큰 유효기간: 10분, 코드 입력 실패 허용 횟수: 토큰당 5회 실패 시 즉시 폐기. DB 스키마 변경은 없으며 전부 Redis에서 처리한다.
- **대표(PRIMARY) 위임**: 기존 PRIMARY→SUB, 대상 SUB→PRIMARY 전환을 하나의 트랜잭션(Isolation Level REPEATABLE READ)으로 원자적 처리 — Partial Unique 제약이 트랜잭션 중간 상태에서 일시적으로 위반되지 않도록 두 UPDATE의 순서(기존 PRIMARY를 먼저 SUB로 내린 뒤 대상을 PRIMARY로 올림)를 지켜야 한다.
- **PRIMARY 탈퇴(관계 해제) — 하이브리드 승계 정책(§14 항목 10 결정)**: PRIMARY 탈퇴 시 애플리케이션은 (1) 사전 위임 여부를 우선 확인하고, (2) 위임 없이 탈퇴가 강행되는 경우 같은 트랜잭션 내에서 ACTIVE 상태인 SUB 중 created_at 최솟값(최선임)을 자동으로 PRIMARY로 승격한다. 이 판단에는 기존 GuardianTarget.created_at 컬럼을 그대로 사용하므로 스키마 변경은 없다.
- **알림 이벤트 그룹 채번**: 하나의 트리거(예: 긴급 알림)로 여러 Guardian(user_id)에게 발송되는 NotificationHistory 행들은 애플리케이션이 이벤트 발생 시점에 event_id(UUID)를 한 번만 생성해 모든 수신자 행에 동일하게 기록해야 한다(§14 항목 12 결정).

---

## 8. ID 생성 전략

| 항목 | 결정 |
|---|---|
| 내부 PK | 전 테이블 `BIGINT GENERATED ALWAYS AS IDENTITY` (SERIAL 대신 SQL 표준 방식 채택 — 시퀀스 권한 관리가 더 안전) |
| 근거 | 단일 Primary 쓰기 노드 구조이므로 다중 노드 분산 채번 문제가 없음. Auto Increment가 UUID 대비 인덱스 삽입 성능(B-Tree 오른쪽 끝 삽입)에서 유리 |
| 외부 노출 식별자 | User에 한해 `public_id UUID DEFAULT gen_random_uuid()` 병행 — API/URL 경로 노출 시 순차 ID 추측(IDOR) 방지 |
| 확장 여부 | 향후 멀티 리전/멀티 쓰기 노드로 확장하는 시점에 ULID/Snowflake 등 분산 채번으로 전환 검토 — 현재 단계에서 선제 도입은 불필요한 복잡도(YAGNI)이므로 보류 |
| INT → BIGINT 전환 이유 | INT(SERIAL)는 약 21억까지만 표현 가능. LocationHistory처럼 연간 수백억 건이 쌓이는 테이블은 수년 내 고갈 가능 → 전 테이블 표준을 BIGINT로 통일 |

---

## 9. 인덱스 정의 (권한 모델 반영 — 최종)

| 테이블 | 인덱스명 | 대상 컬럼 | 종류 | 목적 |
|---|---|---|---|---|
| User | uq_user_oauth | (oauth_provider, oauth_id) | UNIQUE B-Tree | 로그인 시 매 요청 조회 |
| User | uq_user_email_active | email | Partial UNIQUE | 활성 사용자 내 이메일 유일성 |
| GuardianTarget | idx_gt_guardian | guardian_id | B-Tree | 보호자의 보호대상자 목록 조회 |
| GuardianTarget | idx_gt_target_role (기존 idx_gt_target 대체) | (target_id, guardian_role) | Composite B-Tree | 보호대상자의 보호자 목록 조회 + 정원 카운트 조회·PRIMARY 조회를 하나의 인덱스로 커버 |
| GuardianTarget | uq_gt_active_pair | (guardian_id, target_id) WHERE status='ACTIVE' | Partial UNIQUE | 활성 관계 중복 방지 |
| GuardianTarget | uq_gt_primary_per_target | (target_id) WHERE guardian_role='PRIMARY' AND status='ACTIVE' | Partial UNIQUE | 대표 유일성 강제 겸 대표 조회용 인덱스 |
| Place | idx_place_user | user_id | Partial (WHERE deleted_at IS NULL) | 장소 목록 조회 |
| LocationHistory | idx_lh_user_recorded | (user_id, recorded_at DESC) | Composite B-Tree | 최신 위치/기간별 이력 조회 (파티션별 로컬 인덱스 자동 생성) |
| VisitHistory | idx_vh_user_arrival | (user_id, arrival_time DESC) | Composite B-Tree | 방문 이력 조회 |
| VisitHistory | idx_vh_registered | (user_id, is_registered_place) | Partial (WHERE is_registered_place=false) | 미등록 장소 이상행동 탐지 |
| VisitHistory | **idx_vh_place (보완, v6.1)** | place_id | Partial (WHERE place_id IS NOT NULL) | place_id는 §5 FK 대상 컬럼이나 §9 목록에서 누락되어 있었음. "모든 FK 대상 컬럼에 명시적 인덱스 필요"라는 본 절 공통 원칙을 그대로 적용해 보완 (§13 근거). place_id가 nullable이므로 NULL은 제외해 인덱스 크기 최소화 |
| PredictionHistory | idx_ph_user_date | (user_id, prediction_date DESC) | Composite B-Tree | 예측 이력 조회 |
| NotificationHistory | idx_nh_user_status | (user_id, status) | Partial (WHERE status<>'READ') | 안읽은 알림 조회 |
| NotificationHistory | idx_nh_user_sent | (user_id, sent_at DESC) | Composite B-Tree | 알림 목록 시간순 조회 |
| NotificationHistory | idx_nh_target_sent (신규) | (target_id, sent_at DESC) | Composite B-Tree | 특정 CareTarget에 대한 알림 이력 조회 (§14 항목 3 결정 반영) |
| NotificationHistory | idx_nh_event (신규) | event_id | B-Tree | 동일 이벤트로 발송된 다른 Guardian 수신 행 조회 (§14 항목 12 결정 반영) |
| ChatHistory | idx_ch_user_created | (user_id, created_at DESC) | Composite B-Tree | 대화 이력 조회 |
| ChatEmbedding | idx_chat_embedding_hnsw | embedding | HNSW (vector_cosine_ops) | 유사 질문 벡터 검색 (RAG) |

**공통 원칙**: (1) 복합 인덱스는 선택도(Cardinality)가 높은 컬럼을 앞에 배치, (2) "삭제되지 않은 것만/특정 상태만" 조회되는 경우 Partial Index 적극 활용, (3) 쓰기가 압도적인 테이블은 인덱스를 최소로 유지, (4) PostgreSQL은 FK에 자동으로 인덱스를 생성하지 않으므로 모든 FK 대상 컬럼에 명시적 인덱스 필요.

**변경점**: 기존 `idx_gt_target(target_id)` 단독 인덱스를 `idx_gt_target_role(target_id, guardian_role)`로 대체했다. 선두 컬럼이 동일해 기존 조회 패턴을 그대로 커버하면서, 정원 검증(COUNT)과 PRIMARY 단건 조회까지 하나의 인덱스로 지원해 인덱스 개수를 늘리지 않고 목적을 통합했다 (과도한 인덱스 지양 원칙 준수).

**옵션 (현재 미적용, 향후 확장 시 고려)**: ChatHistory.question/answer 키워드 검색이 필요해지면 GIN(tsvector) 인덱스 추가 — 현재 API 명세에 키워드 검색 기능이 없어 즉시 적용 대상 아님.

---

## 10. 이력 데이터 보존 정책 (§14 항목 4 결정 반영 — 잠정 확정)

| 테이블 | 삭제/보관 방식 | 보관 기간 (잠정 확정) | 비고 |
|---|---|---|---|
| User | Soft Delete(deleted_at) | 법령 기준에 따라 개인정보 파기 절차 별도 적용 | Security Guide 개인정보 정책과 연계 |
| Place | Soft Delete(deleted_at) | — | — |
| GuardianTarget | 상태 컬럼(TERMINATED) | — | 물리 삭제 없이 이력 유지 |
| LocationHistory | Hard Delete(파티션 DROP) | 6개월 | 월 단위 파티션 DETACH + DROP. 가이드 제시 범위(3~6개월) 중 최솟값 채택 — 법무 검토 시 재조정 가능 |
| VisitHistory | Hard Delete(배치) | 1년 | 가이드 제시 범위(1~2년) 중 최솟값 채택 |
| PredictionHistory | Hard Delete(배치) | 6개월 | Derived Data(원본으로 재계산 가능)라 상대적으로 짧게 채택 |
| NotificationHistory | Hard Delete(배치) | 1년 (감사 목적) | 가이드 확정값 유지 |
| ChatHistory / ChatEmbedding | 서비스 정책에 따름 + 사용자 삭제 요청 시 즉시 반영 | 1년 | 사용자 삭제 요청 시 보관주기와 무관하게 즉시 삭제 예외 유지 |

위 보관 기간은 가이드가 제시한 범위 내에서 가장 보수적인(짧은) 값을 잠정 채택한 것이다 — 최솟값을 기본으로 정하면 이후 법무 검토에서 기간을 늘리는 것은 쉽지만, 처음부터 길게 잡아 나중에 줄이면 "이미 삭제됐어야 할 데이터를 과다 보관"한 상태가 만들어질 수 있어 최솟값이 더 안전한 기본값이다. **개인정보 처리방침이 정식 수립되면 최종 확정값으로 재조정한다.**

**핵심 원칙**: 대용량 시계열 테이블(LocationHistory 등)은 DELETE 문 대신 파티션 단위 DETACH+DROP을 사용한다 — 대용량 DELETE보다 훨씬 빠르고 락 부담이 없다. 사용자의 개인정보 삭제 요청(특히 ChatHistory)은 보관주기 배치와 무관하게 즉시 처리해야 한다. 위 보관 기간 수치는 하드코딩하지 않고 배치 설정값(운영 환경변수 또는 설정 테이블)으로 분리해, 법적 요건 변경 시 코드 배포 없이 조정 가능하게 구현한다.

---

## 11. PostgreSQL / pgvector 설계

### 11.0 대상 PostgreSQL 메이저 버전 (§14 항목 6 결정 — 확정)

| 항목 | 결정 |
|---|---|
| 확정 버전 | **PostgreSQL 15 이상** |
| 근거 | 가이드가 요구하는 최소 기능(IDENTITY 10+, DEFAULT 컬럼 추가 최적화 11+, 파티션 테이블 FK 지원 12+, gen_random_uuid 내장 13+)을 모두 안전하게 포괄하는 가장 낮은 안정 버전대 |
| 개발 환경 | Docker Compose 기준 postgres:15 이상 이미지로 고정 |
| 상용 배포 시 | AWS RDS 등 실제 배포 인프라의 지원 버전 목록을 배포 시점에 재확인 (메이저 버전 15 이상 유지, 마이너 버전은 배포 시점 최신 보안 패치 적용) |

### 11.1 확장 (Extension)

| 확장 | 용도 | 비고 |
|---|---|---|
| vector (pgvector) | ChatEmbedding.embedding 컬럼 및 HNSW 인덱스 | 원본 기획서 기술스택에 이미 명시된 요구사항 반영 |
| 내장 함수 (gen_random_uuid) | public_id, event_id 등 UUID 채번 | PostgreSQL 13+ 부터 core 내장, 별도 확장 불필요 (§14 항목 6 결정 — 대상 버전 15+로 확정되어 pgcrypto 확장 자체가 불필요해짐) |
| pg_partman (선택) | LocationHistory 월 파티션 자동 생성/관리 | 수동 배치로 대체 가능하나 실수 방지 위해 권장 |

### 11.2 파티셔닝 설계 (LocationHistory)

| 항목 | 결정 |
|---|---|
| 파티션 방식 | Range Partitioning |
| 파티션 키 | recorded_at |
| 단위 | 월 단위 |
| 신규 파티션 생성 | 매월 사전 생성 배치 (다음 달 파티션 부재로 인한 INSERT 실패 방지) |
| PK 영향 | (id, recorded_at) 복합 PK로 변경 필요 (5장 참고) |
| FK 영향 | PostgreSQL 12+ 부터 파티션 테이블도 FK 부모/자식 가능하나 제약 있음, 배포 전 대상 버전 지원 범위 확인 필요 |

VisitHistory/NotificationHistory는 "권장" 수준이며 필수는 아니다. 데이터량이 임계치(수천만 건)를 넘는 시점에 동일한 방식으로 전환하는 것을 목표 아키텍처로 문서화해 두고, 현 단계에서는 단순 BIGINT 단일 PK로 시작한다.

### 11.3 벡터 인덱스 (ChatEmbedding) — Gemini Embedding 최종 반영

| 항목 | 결정 |
|---|---|
| Embedding 제공자/모델 | Google Gemini Embedding — gemini-embedding-001 (GA, 텍스트 전용, Gemini API embed_content 엔드포인트) |
| 벡터 차원 | 768 (output_dimensionality=768 파라미터로 축소 지정) |
| 인덱스 종류 | HNSW (IVFFlat 대비 정확도-속도 균형이 좋고 사전 튜닝 부담이 적음 — 차원이 768로 축소되어 인덱스 크기·빌드 비용 이점이 더 커짐) |
| 거리 연산자 | vector_cosine_ops (코사인 유사도) |
| 용도 | AI Care Assistant의 과거 유사 질문/답변 검색(RAG) — 관련성 높은 대화만 선별해 Gemini LLM 입력 토큰 절감 |

**차원 확정 근거 (요약)**: Gemini Embedding은 MRL(Matryoshka Representation Learning) 기법으로 3072/1536/768 중 출력 차원을 선택할 수 있으며, 낮은 차원에서도 품질 손실이 크지 않도록 설계되어 있다. ChatHistory는 Derived Data(1.2절, 백업 우선순위 낮음)이고 RAG 목적 자체가 "정밀한 순위 매기기"가 아닌 "관련 대화 선별"이므로, 저장 공간·인덱스 크기·검색 속도에서 이점이 큰 768차원을 채택한다. 상세 비교는 §13 참고.

#### 11.3.1 Embedding 모델 교체 시 마이그레이션 원칙

| 시나리오 | 필요 조치 |
|---|---|
| 동일 차원, 다른 모델로 교체 | 모델이 다르면 벡터 공간 자체가 다르므로 전량 재임베딩 필수. 컬럼 타입 변경은 불필요하나 HNSW 인덱스는 재구축 권장 |
| 차원이 달라지는 모델로 교체 | VECTOR(768) 컬럼 타입 변경(사실상 테이블 재생성) + 전량 재임베딩 + HNSW 인덱스 재구축이 모두 필요 |
| 부분 재임베딩 가능 여부 | 불가능에 가까움 — 신구 벡터가 같은 공간에 섞이면 유사도 비교 자체가 왜곡되므로 전량 재임베딩이 원칙 |

현재 단계에서는 모델 버전 관리용 컬럼(예: embedding_model_version)을 미리 추가하지 않는다. ChatEmbedding은 언제든 재생성 가능한 Derived Data이고 현재 단일 모델만 사용하므로 여러 버전이 공존할 필요가 없기 때문이다(YAGNI). 실제로 모델을 교체하는 시점에는 배치 재임베딩이 완료될 때까지 신·구 벡터가 검색에 섞이지 않도록 운영 절차(예: 교체 기간 중 검색 일시 중단 또는 임시 버전 구분)를 마련해야 한다.

#### 11.3.2 AI 사용량 3분류 (§8 근거, DB 설계와의 경계 명시)

| 구분 | 내용 | Gemini API 호출 여부 |
|---|---|---|
| LLM API 사용량 | Gemini가 답변을 생성할 때 소모 | 호출 O |
| Embedding API 사용량 | 질문/과거 대화를 벡터로 변환할 때 소모 (gemini-embedding-001) | 호출 O — LLM과 별도 과금·한도 단위 |
| Vector 검색 | ChatEmbedding에서 HNSW로 유사 벡터를 찾는 연산 | 호출 X — PostgreSQL 내부 연산, Gemini API와 무관 |

VECTOR(n)의 차원과 Gemini 무료 사용량 한도는 직접적 관계가 없다. 차원은 저장 공간·검색 속도에 영향을 주고, 무료 사용량 한도는 LLM·Embedding 각각의 토큰/요청 수 소진으로 결정된다. 무료 사용량 초과 시 API 호출을 제한하는 구체적인 임계값·Redis 카운터 구현은 애플리케이션 구현 단계(Exception Handling Rule과 연계)에서 다루며, 본 DB 설계 문서 범위 밖이다.

### 11.4 서버 설정 권고 (운영 확장 단계 목표치)

| 설정 | 방향 |
|---|---|
| shared_buffers | 인스턴스 메모리의 약 25% |
| work_mem | 정렬/해시 조인 부하가 큰 위치 이력 대량 조회 쿼리 기준으로 조정 |
| random_page_cost | 클라우드 SSD(RDS/EBS gp3) 환경에서는 기본값(4.0)보다 낮게(1.1~2.0) 조정 검토 |
| autovacuum | 기본 활성화 유지, Archive로 인한 대량 DELETE/파티션 DROP 이후 수동 VACUUM 고려 |

---

## 12. 기존 기획서 대비 변경사항

| 구분 | 기존 기획서 (기준선) | 최종 확정 | 변경 근거 |
|---|---|---|---|
| PK 타입 | SERIAL(User/GuardianTarget/Place), BIGSERIAL(나머지) | 전 테이블 BIGINT GENERATED ALWAYS AS IDENTITY | INT 범위 고갈 위험 제거, SQL 표준 방식 채택 |
| 로그인 식별 기준 | email UNIQUE | oauth_provider+oauth_id 기준, email은 부분 유니크로 전환 | 이메일은 변경 가능한 값 → 계정 매핑 키로 부적합 |
| 외부 노출 식별자 | 내부 PK(id) 그대로 노출 | public_id(UUID) 별도 병행 | IDOR(ID 추측 공격) 방지 |
| Soft Delete | 없음 (전 테이블 물리 삭제) | User/Place에 도입, GuardianTarget은 상태 컬럼 | 참조 무결성·이력 조회 필요성 반영 |
| updated_at | 없음 | User/Place에 추가 | 수정 이력 추적 필요 |
| CHECK 제약 | 거의 없음(role만) | 위경도 범위, 반경, 확률, 상태값 등 다수 추가 | 물리적으로 불가능한 값의 DB 레벨 차단 |
| LocationHistory User FK | ON DELETE CASCADE | ON DELETE RESTRICT + 비동기 파기 배치 | 수억 건 동기 CASCADE 시 장시간 락 위험 |
| VisitHistory/PredictionHistory/NotificationHistory/ChatHistory User FK | ON DELETE CASCADE | ON DELETE RESTRICT + 비동기 파기 (ChatHistory는 사용자 요청 시 즉시 삭제 예외) | 동일 이유 |
| LocationHistory PK/파티셔닝 | 단일 BIGSERIAL, 파티셔닝 없음 | (id, recorded_at) 복합 PK, 월 단위 Range Partitioning | 연 약 1,000억 건 규모 추정 → 단일 테이블 한계 |
| VisitHistory | place_name 스냅샷만 존재 | place_id(nullable) 추가 | 등록 장소 원본과의 연결로 향후 분석 유연성 확보 |
| ChatHistory | pgvector 관련 테이블 없음 | ChatEmbedding 신규 테이블 추가 | 기획서 기술스택에 이미 선언된 요구사항의 누락 보완 |
| GuardianTarget 삭제 정책 | UNIQUE(guardian_id, target_id) 고정, CASCADE 삭제 | 상태 컬럼 + Partial UNIQUE(ACTIVE 한정), CHECK(guardian_id<>target_id) | 관계 해제 이력 보존, 자기참조 데이터 오류 방지 |
| GuardianTarget 권한 모델 | 없음 (전원 동등) | guardian_role(PRIMARY/SUB) 컬럼 신설, CareTarget당 ACTIVE PRIMARY 정확히 1명 강제(Partial UNIQUE) | 다수 Guardian 간 데이터 충돌·임의 축출·프로필 동시 수정 문제를 구조적으로 방지 (권한 정책 검토 결과 반영) |
| GuardianTarget 정원 | 명시 없음 | CareTarget 1명당 ACTIVE Guardian 최대 3명(확정) | 비즈니스 정책 확정 반영 |
| Place 등록 권한 | 명시 없음 (Guardian이면 누구나 가능한 것으로 암묵 가정) | 등록(CREATE)·수정·삭제는 ACTIVE PRIMARY Guardian 전용, SUB는 조회만 가능 | 다자 간 동시 편집 충돌을 애초에 발생하지 않도록 구조적으로 차단 (권한 정책 검토 결과 반영) |
| NotificationHistory 저장 항목 | user_id만 존재 | target_id(보호 대상자 ID) 컬럼 추가 | 원 기획서 "알림 이력 관리" 절에 이미 명시된 저장 항목("사용자 ID", "보호 대상자 ID")의 반영 누락 보완 — §14 항목 3 |
| NotificationHistory.type | 자유 문자열, 값 목록 미정 | CHECK로 7종 최종 확정 (ARRIVAL/ARRIVAL_CONFIRM_REQUEST/ARRIVAL_CONFIRMED/EMERGENCY/AI_ANOMALY/AI_PREDICTION/AI_WEEKLY_REPORT) | 원 기획서 GeoFence 상세 시나리오까지 정밀 재검토해 "확인 완료 알림"(ARRIVAL_CONFIRMED) 유형을 추가 발견, 최종 확정 |
| NotificationHistory 재발송 여부 | 컬럼 없음 | is_retry(BOOLEAN) 컬럼 추가 | 원 기획서 "알림 이력 관리" 저장 항목에 명시된 "재발송 여부"의 반영 누락 보완, "재알림"을 별도 type이 아닌 플래그로 처리 |
| GuardianTarget 관계 생성 절차 | 명시 없음 (직접 등록으로 암묵 가정) | 초대(Invitation) + CareTarget 승인 절차로만 생성, Redis TTL 토큰 기반, PostgreSQL 테이블 없음 | CareTarget 동의 없이 관계가 생성되는 것을 방지, email/OAuth 전원 필수 정책과 정합 |
| NotificationHistory 다인 발송 그룹핑 | 없음 (다중 Guardian 개념 자체가 원 기획서에 없음) | event_id(UUID) 컬럼 추가 | 동일 이벤트로 여러 Guardian에게 개별 발송되는 행을 정확히 상관관계 지정 — §14 항목 12 |
| 이력 테이블 보관 기간 | 범위로만 제시(가이드), 원 기획서엔 없음 | LocationHistory 6개월/VisitHistory 1년/PredictionHistory 6개월/NotificationHistory 1년/ChatHistory 1년 (잠정) | 가이드 제시 범위 내 최솟값 채택 — 개인정보 최소보관 원칙에 부합, 법무 검토 시 재조정 — §14 항목 4 |
| ChatEmbedding 벡터 차원/제공자 | 미정 → OpenAI 기준 잠정 확정(v3.0~v5.1) | VECTOR(768), Google Gemini Embedding(gemini-embedding-001) 최종 확정 | LLM Provider가 Gemini로 확정됨에 따라 Embedding도 동일 생태계로 통일. 공식 문서 기준 MRL 권장 3종(768/1536/3072) 중 포트폴리오~초기 상용화 규모에 적합한 768차원 채택 — §13 근거 |
| PostgreSQL 메이저 버전 | 미정 | 15 이상으로 확정 | 가이드가 요구하는 모든 최소 기능(IDENTITY/파티션-FK/UUID 내장)을 안전하게 충족 — §14 항목 6 |
| PRIMARY 탈퇴 시 승계 정책 | 없음 | 하이브리드(수동 위임 우선 + SUB 존재 시 최선임 자동 승계 폴백) | "대표 없는 CareTarget" 무기한 방치 리스크와 임의 승계 리스크를 동시에 최소화 — §14 항목 10 |

---

## 13. 설계 결정 및 근거

| 결정 | 채택 근거 |
|---|---|
| VisitHistory에 place_id(nullable) 추가 | 리스크가 낮고(nullable 컬럼 추가일 뿐) 원본 장소 반경/좌표 변경 이력 분석이라는 명확한 실익이 있어 최종 채택 |
| PredictionHistory UNIQUE(user_id, prediction_date, predicted_place) 추가 | 배치 재실행 시 중복 적재를 막고 ON CONFLICT DO UPDATE로 멱등 처리가 가능해짐 |
| GuardianTarget 상태 컬럼 + Partial UNIQUE(ACTIVE 한정) | User.email에 이미 적용한 "Soft Delete와 UNIQUE 충돌 해결" 패턴을 동일하게 적용한 것으로, 기존 원칙의 일관된 적용 |
| ChatEmbedding 테이블 신설 | 원본 기획서 기술스택 표에 이미 명시된 요구사항(pgvector)이 기준선 DDL에서만 누락되어 있었던 것을 확인해 확정 |
| VisitHistory·NotificationHistory 파티셔닝을 "조건부"로 보류 | "필수"가 아닌 "권장(임계치 도달 시)"으로 구분됨. 서비스 초기 규모에서 선제 파티셔닝은 불필요한 복잡도(YAGNI 원칙) |
| LocationHistory만 즉시 파티셔닝 적용 | 유일하게 "필수"로 명시되었고, 연 약 1,000억 건 규모 추정 근거가 명확하여 서비스 초기부터 구조를 갖추지 않으면 이후 무중단 전환 비용이 훨씬 커짐 |
| GuardianTarget에 PRIMARY/SUB 역할 모델 도입 | "전원 동등" 방식은 동시 수정 충돌·임의 축출·프로필 관리 주체 부재 문제를 해소하지 못함. 세분화된 권한 매트릭스는 현재 규모에 과도(YAGNI). 관리형 기능을 대표 1인에게 집중시키고 안전 기능(조회/수신/긴급연락)만 전원 공유하는 절충안이 충돌 발생면을 최소화하면서 서비스 본질(안전)과도 상충하지 않아 채택 |
| CareTarget당 ACTIVE PRIMARY 정확히 1명을 Partial UNIQUE로 강제 | 대표 유일성은 "동일 target_id로 PRIMARY 2건 INSERT 시 즉시 거부"라는 형태로 DB가 원자적으로 표현 가능한 규칙이므로, 애플리케이션 버그로도 깨지지 않는 최후 방어선으로 DB에 둠 |
| 정원(3명) 제약은 DB가 아닌 애플리케이션에서 관리 | "동일 target_id의 행 개수"를 세는 규칙은 PostgreSQL 선언적 제약(CHECK/UNIQUE)으로 표현 불가. 트리거로는 가능하나 숨은 비즈니스 로직이 되어 유지보수성이 떨어지므로, 1차 방어는 애플리케이션의 비관적 락(SELECT...FOR UPDATE) + 카운트 검증으로 처리하고, DB는 PRIMARY 유일성 등 표현 가능한 규칙까지만 담당 |
| Place 등록을 PRIMARY 전용으로 제한 | 등록까지 SUB에게 허용하는 대안도 검토했으나("학원 픽업 담당 보조 보호자가 즉시 등록" 등 실용성 있음), 확정 결정에 따라 등록·수정·삭제 전체를 PRIMARY로 일원화. Place 테이블 자체에는 이 규칙을 표현할 CHECK가 없으므로 Service 계층이 GuardianTarget.guardian_role을 조회해 검증하는 교차 테이블 규칙으로 문서화 |
| NotificationHistory에 target_id 추가 | 원 기획서 "알림 이력 관리" 절이 저장 항목으로 "사용자 ID"와 "보호 대상자 ID"를 별도로 명시했음에도 기준선 DDL에는 user_id만 존재. 새로운 결정이 아니라 이미 문서화된 요구사항의 반영 누락을 바로잡은 것 |
| NotificationHistory.type을 CHECK로 고정(참조 테이블 미채택) | 원 기획서 "알림" 시나리오에 유형이 7종으로 명확히 열거되어 있고 자주 늘어날 근거가 없어, 참조 테이블 분리는 현재 규모에 과도한 설계(YAGNI) |
| "재알림"을 별도 type이 아닌 is_retry 플래그로 처리 | 원 기획서가 "저장 항목"에서 "알림타입"과 "재발송 여부"를 별개 항목으로 명시했으므로, 이를 그대로 반영하면 재알림은 독립된 type이 아니라 동일 type의 재발송 플래그여야 함 |
| ARRIVAL_CONFIRMED를 신규 7종에 포함 | GeoFence 상세 시나리오에 "CareTarget 확인 완료 시 Guardian에게 별도 완료 통지"가 명시되어 있음을 재확인하여, 기존에 놓쳤던 실제 알림 이벤트를 뒤늦게 반영 |
| GEOFENCE_EXIT·UNREGISTERED_PLACE를 7종에서 제외 | GeoFence 이탈은 VisitHistory 계산용 내부 이벤트로만 쓰이고, 미등록 장소 감지는 인앱 UI 제안일 뿐 어디에도 "Push 발송" 문구가 없어 근거 부족. 근거 없는 타입을 임의로 추가하지 않는다는 원칙 준수 |
| GuardianTarget 관계를 초대(Invitation)+승인 절차로만 생성, Invitation 전용 테이블은 두지 않음 | 초대 토큰은 TTL을 가진 일시적 데이터이므로 이미 문서 전반에 적용된 Redis Cache Aside 패턴과 아키텍처 일관성이 있음. 또한 이 절차는 이전에 확정한 "email/OAuth 전원 필수" 정책(CareTarget이 먼저 자체 계정으로 가입되어 있어야 초대 코드를 생성할 수 있음)을 그대로 뒷받침해 별도 충돌 없이 통합됨 |
| 초대 코드 생성/입력에 경량 Rate Limit 권장 | 정원(3명) 제약은 최종 결과를 막아주지만, 그 이전 단계인 코드 생성·무차별 입력 시도 자체를 제어하는 수단은 아니므로 별도 보완이 필요. Redis 카운터만 추가하면 되어 비용이 매우 낮음 |
| Guardian 1인당 CareTarget 상한 10명 / CareTarget 1인당 Place 상한 15개로 확정 | 원 기획서 전반이 "가족 단위 케어"를 전제로 설계되어 있어 대량 등록을 정상적으로 필요로 하는 사용자 유형이 없음. 다자녀·다중 부모 돌봄 등 극단적 가족 구성까지 여유 있게 수용하면서 어뷰징성 대량 등록은 차단되는 수준으로 설정. Place는 가이드의 평균 추정치(3~5개)의 약 3배 여유를 두어 집·학교·학원·병원·조부모집 등 현실적 등록을 모두 수용 |
| 초대 코드 생성 5회/일, 토큰 TTL 10분, 입력 실패 5회 제한으로 확정 | 정상 사용자는 최초 1~2명의 보호자를 순차 등록하는 정도이므로 5회/일이면 충분한 여유. TTL 10분은 Guardian에게 코드를 문자·메신저로 전달할 물리적 시간을 고려한 실용적 하한. 입력 실패 5회 제한은 정상 사용자의 오타는 허용하면서 무차별 대입 공격의 성공 확률은 무시할 수준으로 낮춤 |
| NotificationHistory.type 문자열을 API 응답 필드에도 동일하게 사용, 별도 변환 매핑 없음 | "API와 DB에서 동일한 문자열 사용"이라는 기존 명명 원칙을 그대로 따름. 7종이라는 적은 개수와 외부 시스템 연동 요구사항 부재를 고려하면 별도 변환 계층은 불필요한 복잡도 |
| NotificationHistory에 event_id(UUID) 추가 | Guardian별 개별 행을 유지한다는 기존 확정 설계는 그대로 두면서, 정확한 이벤트 그룹핑(부정확한 시간 근접 휴리스틱 대신 명시적 식별자)을 컬럼 1개 추가라는 최소 비용으로 달성 |
| 이력 테이블 보관 기간을 가이드 범위의 최솟값으로 잠정 확정 | 개인정보보호법의 "필요 최소 기간 보관" 원칙에 부합하며, 최솟값에서 늘리는 재조정이 그 반대보다 안전. 배치 로직 구현 자체가 지금 가능해짐 |
| ChatEmbedding을 Gemini Embedding(gemini-embedding-001)으로 최종 확정 | LLM Provider가 Gemini로 확정됨에 따라 Embedding도 동일 API 키/계정으로 통합 관리 가능한 Gemini Embedding으로 통일. gemini-embedding-2-preview는 멀티모달·Preview 상태라 텍스트 전용 RAG에는 GA 상태인 gemini-embedding-001이 더 안정적 |
| VECTOR(768)로 최종 확정 (1536에서 하향 조정) | Gemini Embedding은 MRL 기법으로 768/1536/3072 중 선택 가능하며 공식 문서가 셋 다 권장. 낮은 차원에서도 품질 손실이 적도록 설계되어 있고, RAG 목적 자체가 "정밀한 순위 매기기"가 아닌 "관련 대화 선별"이므로 저장 공간·인덱스 크기·검색 속도 이점이 더 실질적. ChatHistory가 Derived Data(백업 우선순위 낮음)라는 기존 분류와도 방향이 일치 |
| HNSW + vector_cosine_ops를 차원 변경 후에도 유지 | 차원이 1536→768로 줄면 인덱스 크기·빌드 비용이 더 작아져 HNSW의 장점(정확도-속도 균형, 낮은 튜닝 부담)이 더 잘 발휘됨. IVFFlat은 데이터 분포에 맞춘 사전 클러스터링 튜닝이 필요해 데이터가 적고 계속 늘어나는 초기 단계에는 부담 |
| RAG 목적 정의를 "압축 저장"에서 "관련 대화 선별을 통한 입력 토큰 절감"으로 수정 | 벡터는 원문을 대체하는 압축 저장소가 아니라 검색용 별도 인덱스라는 점을 명확히 해, ChatHistory 원문이 삭제되지 않고 그대로 유지된다는 기존 FK 설계(ON DELETE CASCADE는 파생 데이터인 벡터 쪽에만 적용)와 정합성을 맞춤 |
| Embedding 모델 버전 관리 컬럼을 미리 추가하지 않음 | 현재 단일 모델만 사용하고 ChatEmbedding은 언제든 재생성 가능한 Derived Data이므로 여러 버전이 공존할 필요가 없음(YAGNI). 실제 모델 교체 시점에만 전량 재임베딩 절차를 적용하는 것으로 원칙만 문서화 |
| PostgreSQL 15 이상으로 확정 | 가이드가 산발적으로 요구한 최소 버전(10+/11+/12+/13+)을 모두 안전하게 포괄하는 가장 낮은 안정 버전. 개발 환경(Docker 이미지)을 지금 고정해야 구현이 시작될 수 있음 |
| Guardian당 CareTarget 수, CareTarget당 Place 수는 DB 제약 없이 애플리케이션 소프트 상한만 적용 | 카운트 기반 규칙(동일 guardian_id/user_id의 행 개수)은 PostgreSQL 선언적 제약으로 표현 불가하다는 점에서 GuardianTarget 정원(3명) 처리와 동일한 제약. 정확한 숫자는 문서 근거가 없어 확정하지 않고 정책값으로 남김 |
| PRIMARY 탈퇴 시 하이브리드 승계 정책 채택 | 완전 자동 승계는 가족 관계의 민감성상 원치 않는 권한 이전 리스크가 있고, 완전 수동 위임 강제는 연락 두절 등 위급 상황에서 "대표 없는 CareTarget"이 무기한 방치될 리스크가 있어, 안전 서비스 본질에 맞게 두 리스크를 절충 |
| VisitHistory.place_id에 idx_vh_place(Partial) 인덱스 보완 | DDL 작성 단계에서 place_id가 §5에 FK 대상 컬럼으로 명시되어 있음에도 §9 인덱스 목록에서 누락되어 있었음을 발견. §9 공통 원칙("모든 FK 대상 컬럼에 명시적 인덱스 필요")을 그대로 적용한 것이므로 새로운 설계 판단이 아니라 문서 내부 일관성 보완. place_id가 nullable(미등록 장소 방문 시 NULL)이므로 WHERE place_id IS NOT NULL Partial Index로 만들어 이미 문서 전반에 적용된 패턴(idx_place_user, idx_vh_registered와 동일한 "불필요한 값 제외" 원칙)과 통일, 인덱스 크기도 최소화 |

---

## 14. 미확정 사항 및 결정 필요 항목 (전체 해소 — 최종)

§14에 남아 있던 마지막 3개 항목(Guardian/CareTarget/Place 등록 상한, 초대 코드 Rate Limit 임계값, NotificationHistory.type 최종 표기)이 모두 확정되어 더 이상 [결정 필요] 항목이 없다.

| 항목 | 최종 결정 | 근거 |
|---|---|---|
| Guardian 1인당 CareTarget 등록 수 상한 | 10명 (애플리케이션 소프트 상한, 설정값 관리) | §13 참고 |
| CareTarget 1인당 Place 등록 수 상한 | 15개 (애플리케이션 소프트 상한, 설정값 관리) | §13 참고 |
| 초대 코드 생성 제한 | 5회/일 (Redis 카운터, 자정 TTL) | §13 참고 |
| 초대 토큰 유효기간 | 10분 (Redis TTL) | §13 참고 |
| 초대 코드 입력 실패 제한 | 토큰당 5회 (초과 시 즉시 폐기) | §13 참고 |
| NotificationHistory.type API 표기 | DB CHECK 7종 문자열을 API 응답 필드에도 동일하게 사용, 별도 변환 매핑 없음 | §13 참고 |

위 소프트 상한·Rate Limit 수치는 모두 애플리케이션 설정값(예: application.yml) 또는 Redis 카운터로 구현되며, PostgreSQL 스키마에는 직접적인 변경이 없다. 운영 데이터가 쌓여 값 조정이 필요해지면 코드 배포 없이 설정 변경만으로 대응 가능하도록 구현하는 것을 원칙으로 한다.

**본 데이터베이스 설계 문서는 이번 갱신으로 §1~§14 전 항목이 확정되었다.** 다음 단계는 본 설계를 기준으로 한 SQL DDL 스크립트 작성이다.

> 본 문서는 SQL DDL을 포함하지 않는다. 본 설계를 기준으로 한 SQL 스크립트 작성이 다음 단계 작업이다.
