# API Specification
>해당파일 경로 docs/api/API_Specification.md
> 이 문서는 **엔드포인트 목록 그 자체**(Method/URI/설명/권한/요청·응답 필드)를 담당한다.
> 응답 포맷(`success`/`code`/`message`/`data` 구조), 에러 코드의 의미, HTTP Status 판단 기준은
> 이 문서에서 다시 설명하지 않고 `docs/api/API_Response_Rule.md`를 따른다.

**프로젝트**: 아이·노인 케어 위치추적 알림 시스템 (trace_care)
**대상 독자**: Backend(Spring Boot) 개발자, Frontend(Flutter) 개발자
**출처**: `05_trace_care_프로젝트 기획서_0806.pdf` REST API 명세서를 기준으로 하되, 이후 문서(`API_Response_Rule.md`, `DATABASE_DESIGN_GUIDE.md`)에서 확정된 정책을 함께 반영했다.

**관련 문서**
- 응답 포맷·에러 코드 표·401/403 판단 기준 → `docs/api/API_Response_Rule.md`
- 식별자 노출(`public_id`) 정책 → `docs/api/API_Response_Rule.md` §1.5
- 인증/인가 상세 설계 → `docs/security/Security_Guide.md`
- 테이블/컬럼 설계 근거 → `docs/db/DATABASE_DESIGN_GUIDE.md`

---

## 목차

1. 표기 규칙
2. 인증(Auth) API
3. 보호자(Guardian) API
4. 보호대상자(CareTarget) API
5. WebSocket
6. 내부 API (시스템 전용)
7. 결정 기록 (Decision Log)

---

## 1. 표기 규칙

- **권한** 열은 필요 Role을 표기한다. `All`은 로그인만 하면 Role 무관 호출 가능, `Guardian`/`CareTarget`은 해당 Role만 호출 가능(3단계 인가 중 2단계, 상세: API_Response_Rule.md §4.1).
- **식별자**(`careTargetId`, `placeId`, `notificationId` 등)는 별도 표기가 없으면 API_Response_Rule.md §1.5 정책에 따른다 — Master Data(User/Place)는 `public_id`(UUID 문자열), 시계열 이력(LocationHistory 등)은 내부 PK(정수)를 그대로 사용한다. **`careTargetId`는 예외적으로 GuardianTarget 자체의 식별자가 아니라 대상 User의 `public_id`다**(GuardianTarget은 별도 `public_id` 컬럼을 두지 않음, 근거: DATABASE_DESIGN_GUIDE.md §8).
- **주요 실패 코드**는 해당 엔드포인트에서 자주 발생하는 코드만 나열한 것이며, `COMMON_00X`/`AUTH_00X`(인증 만료 등) 공통 실패는 모든 엔드포인트에 공통 적용되므로 표에서 생략했다.
- `{id}` 형태의 Path Variable은 위 식별자 규칙을 따르는 `public_id` 또는 내부 PK다. 각 절에서 어느 쪽인지 명시한다.

---

## 2. 인증(Auth) API

**Base Path**: `/api/auth`

| Method | URI | 설명 | 권한 |
|---|---|---|---|
| POST | `/api/auth/oauth/login` | Google OAuth ID Token으로 로그인, 자체 JWT 발급 | All |
| PUT | `/api/auth/role` | 최초 로그인 시 Role(Guardian/CareTarget) 확정 | All(최초 1회만) |
| POST | `/api/auth/logout` | 로그아웃, Refresh Token 폐기 및 Access Token 블랙리스트 등록 | All |
| POST | `/api/auth/refresh` | Access Token 재발급 | All |
| GET | `/api/auth/me` | 현재 로그인한 사용자 기본 정보 조회 | All |

### 2.1 `POST /api/auth/oauth/login`

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Request | `idToken` | string | Google OAuth ID Token |
| Request | `fcmToken` | string | 로그인 기기의 FCM Token (Push 발송용) |
| Response | `accessToken`, `refreshToken` | string | JWT |
| Response | `role` | string | `GUARDIAN` / `CARE_TARGET` / `null`(미선택) |
| Response | `userId` | string(UUID) | `public_id` |
| Response | `roleSelected` | boolean | `false`면 Frontend가 Role 선택 화면으로 이동 후 §2.2 호출 |

성공 코드: `AUTH_001`(Role 미선택 상태에서도 로그인 자체는 성공이며, `role: null`/`roleSelected: false`로 응답 — 상세: API_Response_Rule.md §8.1) · 주요 실패 코드: `AUTH_005`(Google 인증 실패)

### 2.2 `PUT /api/auth/role`

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Request | `role` | string | `GUARDIAN` / `CARE_TARGET` |
| Request | `name` | string | 이름 |
| Request | `birthDate` | string(date) | 생년월일 |
| Response | `userId` | string(UUID) | `public_id` |
| Response | `role` | string | 확정된 Role |

성공 코드: `USER_002` · 주요 실패 코드: `USER_004`(이미 Role이 확정된 사용자의 재요청, 409 — Role은 최초 1회만 선택 가능하며 이후 변경 불가)

### 2.3 `GET /api/auth/me`

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Response | `userId` | string(UUID) | `public_id` |
| Response | `role`, `name`, `phone`, `profileImage` | - | User 기본 정보 |

성공 코드: `USER_001`

### 2.4 `POST /api/auth/logout`

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Request | - | - | Body 없음. `Authorization` 헤더의 Access Token 기준으로 처리 |
| Response | `data` | - | `null` |

성공 코드: `AUTH_003`

### 2.5 `POST /api/auth/refresh`

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Request | `refreshToken` | string | 재발급에 사용할 Refresh Token |
| Response | `accessToken`, `refreshToken` | string | 새로 발급된 JWT 쌍(Rotation — 기존 Refresh Token은 이 시점에 즉시 무효화됨) |

성공 코드: `AUTH_002` · 주요 실패 코드: `AUTH_004`(Refresh Token 만료/유효하지 않음/이미 사용된 토큰 재사용 감지 — 재로그인 필요)

---

## 3. 보호자(Guardian) API

**Base Path**: `/api/guardian` · **권한**: 모두 `Guardian` 전용 (`ROLE_CARE_TARGET` 호출 시 `GUARDIAN_001`, 403)

### 3.1 보호 대상자 관리

> **관계 생성 방식(확정)**: `GuardianTarget` 행은 직접 INSERT되지 않고 **초대(Invitation) + CareTarget 승인** 절차로만 생성된다(`DATABASE_DESIGN_GUIDE.md` §3.2/§7). Guardian이 CareTarget의 `public_id`를 알고 있다고 해서 곧바로 관계를 만들 수 없다 — CareTarget이 발급한 초대 코드를 Guardian이 입력해 "연결 요청"을 접수시키고, CareTarget이 그 요청을 승인해야 비로소 관계가 생성된다. 초대 코드 발급/승인/거절 API는 §4.7(CareTarget API) 참고.

| Method | URI | 설명 |
|---|---|---|
| GET | `/api/guardian/care-targets` | 보호 대상자 목록 (페이징, 승인 완료된 관계만) |
| POST | `/api/guardian/care-targets` | 초대 코드 입력으로 연결 요청 (관계는 CareTarget 승인 후 생성됨 — 즉시 생성 아님) |
| GET | `/api/guardian/care-targets/{id}` | 상세 조회 |
| PUT | `/api/guardian/care-targets/{id}` | 관계 정보(관계 라벨, 별칭 등) 수정 — 승인 완료된 관계에 한함 |
| DELETE | `/api/guardian/care-targets/{id}` | 관계 해제(삭제) |
| POST | `/api/guardian/care-targets/{id}/primary-delegation` | PRIMARY 위임 — 호출자(현재 PRIMARY)가 같은 CareTarget의 ACTIVE SUB 중 한 명에게 대표 권한을 직접 넘김 |

`{id}` = 대상 CareTarget(User)의 `public_id` (GuardianTarget 자체는 별도 `public_id`가 없음, §1 참고).

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Request(연결 요청) | `inviteCode` | string | CareTarget이 발급한 초대 코드 |
| Response(연결 요청) | `careTargetId` | string(UUID) | 코드로 확인된 대상 User의 `public_id`(입력한 코드가 맞는 사람인지 확인용) |
| Response(연결 요청) | `name` | string | 대상 CareTarget 이름(표시용) |
| Response(연결 요청) | `status` | string | 항상 `"PENDING"` — 이 응답은 관계 생성이 아니라 요청 접수를 의미 |
| Response(목록/상세) | `careTargetId` | string(UUID) | 대상 User의 `public_id` (GuardianTarget.id 아님) |
| Response(목록/상세) | `name`, `relation` | - | 표시용 정보 |
| Request(PRIMARY 위임) | `newPrimaryGuardianId` | string(UUID) | 새로 PRIMARY가 될 SUB Guardian의 `public_id`(토큰이 아닌 대상자 식별자 원칙 동일 적용) |
| Response(PRIMARY 위임) | `careTargetId` | string(UUID) | 대상 CareTarget의 `public_id` |
| Response(PRIMARY 위임) | `previousPrimaryGuardianId` | string(UUID) | 위임 전 PRIMARY였던(이제 SUB가 된) Guardian의 `public_id` |
| Response(PRIMARY 위임) | `newPrimaryGuardianId` | string(UUID) | 새로 PRIMARY가 된 Guardian의 `public_id` |

`relation`/`alias`는 연결 요청 시점에는 받지 않는다 — 승인이 완료되어 관계가 생성된 뒤 `PUT /api/guardian/care-targets/{id}`로 설정한다(§2 확인 사항).

**PRIMARY 위임**: 호출자가 해당 CareTarget의 ACTIVE PRIMARY가 아니면 거부한다. `newPrimaryGuardianId`는 같은 CareTarget에 대해 현재 ACTIVE SUB 상태여야 하며(다른 CareTarget 소속·PENDING·TERMINATED는 거부), 호출자 자신을 지정할 수 없다. 트랜잭션 순서 등 구현 세부는 `DATABASE_DESIGN_GUIDE.md` §7을 따른다.

성공 코드: `TARGET_001`(목록/상세 조회) / `TARGET_005`(연결 요청 접수, §4.7 참고) / `TARGET_008`(관계 정보 수정) / `TARGET_009`(관계 해제) / `TARGET_010`(PRIMARY 위임) · 주요 실패 코드: `TARGET_001`(404, 대상 없음), `TARGET_002`(403, 관계 미매핑 리소스 접근), `TARGET_004`(400, 초대 코드 무효/만료), `TARGET_006`(409, 이미 대기 중인 동일 요청 존재), `GUARDIAN_003`(409, 코드 입력 시점 Guardian 1인당 CareTarget 등록 수 소프트 상한(10명) 초과, `DATABASE_DESIGN_GUIDE.md` §13/§14), `GUARDIAN_004`(403, 호출자가 PRIMARY 아님), `GUARDIAN_005`(403, 위임 대상이 ACTIVE SUB 아님), `GUARDIAN_006`(409, 자기 자신을 위임 대상으로 지정), `USER_001`(404, `newPrimaryGuardianId`가 존재하지 않는 사용자), `COMMON_008`(409, PRIMARY 위임 동시 요청 충돌 — 재시도 필요)

### 3.2 장소(안심구역) 관리

> Guardian이 여러 CareTarget을 관리할 수 있으므로(Guardian 1인당 CareTarget 등록 소프트 상한 10명), Place는 항상 특정 CareTarget 소속으로 조회·등록된다(`Place.target_id`, 2026-08 DB 설계 누락분 보완). 등록·수정·삭제는 해당 CareTarget의 ACTIVE **PRIMARY** Guardian만 가능하고, SUB Guardian은 조회만 가능하다(`DATABASE_DESIGN_GUIDE.md` §3.3/§7).

| Method | URI | 설명 |
|---|---|---|
| GET | `/api/guardian/places?careTargetId={id}` | 장소 목록(`careTargetId` 쿼리 파라미터 필수) |
| POST | `/api/guardian/places` | 장소 등록 (Google/Kakao/Naver 검색 결과 기반 GeoFence 설정) |
| PUT | `/api/guardian/places/{id}` | 장소/반경 수정 |
| DELETE | `/api/guardian/places/{id}` | 장소 삭제 |

`{id}`(PUT/DELETE 경로) = Place의 `public_id`. `careTargetId`(GET 쿼리, POST 요청 바디) = 대상 CareTarget(User)의 `public_id`.

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Request(등록) | `careTargetId` | string(UUID) | 이 장소가 속할 CareTarget의 `public_id` — Guardian이 여러 CareTarget을 관리할 수 있으므로 등록 시 명시 필요 |
| Request(등록/수정) | `name`, `address` | string | 장소명, 주소 |
| Request(등록/수정) | `latitude`, `longitude` | double | GeoFence 중심 좌표 (위경도 표준 정밀도) |
| Request(등록/수정) | `radius` | int | GeoFence 반경(m), 양수만 허용 |
| Response(목록/상세/등록/수정) | `placeId` | string(UUID) | Place `public_id` |
| Response(목록/상세/등록/수정) | `careTargetId` | string(UUID) | 소속 CareTarget의 `public_id` |
| Response(목록/상세/등록/수정) | `name`, `address`, `latitude`, `longitude`, `radius` | - | 표시용 정보 |

PUT(수정) 요청에는 `careTargetId`를 포함하지 않는다 — 장소의 소속 CareTarget은 등록 후 변경할 수 없다.

성공 코드: `PLACE_001`(목록/상세 조회, 등록) / `PLACE_002`(수정) / `PLACE_003`(삭제) · 주요 실패 코드: `PLACE_001`(404, 장소 없음), `PLACE_002`(409, 동일 CareTarget 내 이름 중복 또는 실거리 50m 이내 중복 등록), `PLACE_003`(400, GeoFence 반경 값 범위 초과), `PLACE_004`(409, CareTarget 1인당 Place 등록 수 소프트 상한(15개) 초과), `TARGET_002`(403, 호출자가 해당 CareTarget의 Guardian이 아님), `GUARDIAN_004`(403, 등록/수정/삭제를 SUB Guardian이 호출 — PRIMARY 전용), `COMMON_008`(409, 동시 수정 충돌 — 낙관적 락 실패, 재시도 필요)

### 3.3 실시간 위치 조회

| Method | URI | 설명 |
|---|---|---|
| GET | `/api/guardian/location/current` | CareTarget 현재 위치 (Redis 캐시 우선) |
| GET | `/api/guardian/location/history` | 이동 히스토리(LocationHistory, 기간 조회) |
| WebSocket | `/ws/guardian/location` | 실시간 위치 수신 (§5 참고) |

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Request(current) | `careTargetId` | string(UUID) | 조회 대상 |
| Response | `careTargetId` | string(UUID) | - |
| Response | `latitude`, `longitude`, `recordedAt` | - | 위치 데이터 |
| Response | `source` | string | `REDIS_CACHE` / `DB` |

성공 코드: `LOCATION_001` · 주요 실패 코드: `TARGET_002`(403, 관계 미매핑), `LOCATION_002`(404, 위치 데이터 없음)

### 3.4 방문 히스토리

| Method | URI | 설명 |
|---|---|---|
| GET | `/api/guardian/history/today` | 오늘 이동 경로 |
| GET | `/api/guardian/history/date` | 날짜별 조회 |
| GET | `/api/guardian/history/place` | 장소별 조회 |

VisitHistory 기준(가공된 "방문 단위" 데이터). 원본 GPS 좌표 나열이 아니라 `placeName`/`arrivalTime`/`departureTime`/`stayMinutes`/`isRegisteredPlace` 단위로 응답한다(상세 예시: API_Response_Rule.md §8.7).

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Request(공통, 쿼리) | `careTargetId` | string(UUID) | 조회 대상 CareTarget의 `public_id` — 3개 엔드포인트 모두 필수 |
| Request(`/history/date`, 쿼리) | `date` | string(`yyyy-MM-dd`) | 조회할 날짜. 서버 타임존(`ZoneId.systemDefault()`) 기준 하루 단위로 조회하며, 오늘보다 미래인 날짜는 `VISIT_002` |
| Request(`/history/place`, 쿼리) | `placeId` | string(UUID) | 조회할 등록 Place의 `public_id`(전체 기간 조회) |
| Response | `content` | array | 표준 목록 페이징 구조(API_Response_Rule.md §1.4). 각 항목은 `placeName`/`arrivalTime`/`departureTime`/`stayMinutes`/`isRegisteredPlace` |

`/history/today`는 `/history/date`에 서버 타임존 기준 오늘 날짜를 넣은 것과 동일하다. 조회 결과가 없으면(해당 기간에 방문 이력 없음) 빈 목록(200)이 아니라 `VISIT_001`(404)을 반환한다(API_Response_Rule.md §8.7 예시와 동일).

성공 코드: `VISIT_001` · 주요 실패 코드: `VISIT_001`(404, 조회 가능한 방문 이력 없음), `VISIT_002`(400, 조회 기간 값 오류 — 미래 날짜 등), `TARGET_002`(403, 관계 미매핑)

### 3.5 AI 방문 예측 (머신러닝, FastAPI 연동)

| Method | URI | 설명 |
|---|---|---|
| GET | `/api/guardian/ai/predict` | 오늘 방문 예상 장소/확률 |
| GET | `/api/guardian/ai/predict/report` | AI 예측 리포트 |
| GET | `/api/guardian/ai/history` | 예측 이력(PredictionHistory) |

성공 코드: `AI_001` · 주요 실패 코드: `AI_001`(500, FastAPI 응답 없음), `AI_003`(404, 예측 결과 없음/학습 데이터 부족)

### 3.6 AI 케어 비서 (LLM 연동)

| Method | URI | 설명 |
|---|---|---|
| POST | `/api/guardian/ai/chat` | 자연어 질의응답 |
| POST | `/api/guardian/ai/summary` | 이동 요약 |
| POST | `/api/guardian/ai/report/weekly` | 주간 리포트 |
| POST | `/api/guardian/ai/search` | 자연어 이동기록 검색 |

> 기획서 원문에 있던 `POST /api/guardian/ai/explain`(이상행동 설명)은 이 절에서 **제거**했다 — LLM을 호출하지 않는 고정 질문 템플릿 방식으로 확정되면서 `GET /api/guardian/anomalies/{anomalyEventId}/explain`(§3.9)으로 대체됐다(§7.3).

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Request(`/chat`, 바디) | `message` | string | 질문 원문(최대 500자) |
| Request(`/chat`, 바디) | `careTargetId` | string(UUID), nullable | 이 질문이 다루는 CareTarget의 `public_id`. 생략하면 특정 CareTarget에 국한되지 않는 일반 대화로 처리되고, 값이 있으면 해당 CareTarget에 대한 대화로 저장·검색 범위가 한정된다(관계 미매핑 시 `TARGET_002`, 2026-08 `ChatHistory.target_id` 추가로 반영). |
| Response(`/chat`) | `chatId` | number | 저장된 `ChatHistory` 내부 PK |
| Response(`/chat`) | `answer` | string | LLM 응답 원문 |

`careTargetId`는 서버가 질문 내용을 분석해서 자동으로 판단하지 않는다. Frontend가 특정 CareTarget에 대한 대화 화면(맥락)에서 질문을 보낼 때는 반드시 이 필드를 채워야 하며, 채우지 않으면 해당 대화는 일반 대화로 저장되어 이후 그 CareTarget에 대한 대화 검색(RAG)에 포함되지 않는다. 범용/비특정 대화 화면에서는 생략한다.

`/summary`(이동 요약), `/search`(자연어 이동기록 검색)는 `/chat`과 달리 `careTargetId`가 **필수**다 — 이 두 기능은 항상 특정 CareTarget의 `VisitHistory`를 대상으로 하므로 자동 판단이나 생략 개념 자체가 없다. 둘 다 원본 GPS 좌표가 아니라 이미 가공된 `VisitHistory`(장소명/시간 단위)만 근거로 사용하며, 응답은 `ChatHistory`에 저장되지 않는다(2026-08 Phase 2B 결정 — 대화 맥락과 성격이 달라 RAG 검색에 섞이지 않도록 분리, 근거는 `AiChatService` Javadoc 참고).

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Request(`/summary`, 바디) | `careTargetId` | string(UUID) | 요약 대상 CareTarget의 `public_id`(필수) |
| Request(`/summary`, 바디) | `from` | string(ISO-8601 Instant) | 요약 기간 시작(포함). Location Phase 1(`/api/guardian/location/history`)의 `from`/`to` 파라미터 설계와 통일 |
| Request(`/summary`, 바디) | `to` | string(ISO-8601 Instant) | 요약 기간 끝. `from`보다 이전이면 `COMMON_002` |
| Response(`/summary`) | `answer` | string | LLM이 생성한 이동 요약 텍스트 |
| Response(`/summary`) | `visitCount` | number | 요약의 근거가 된 방문 건수 |
| Response(`/summary`) | `anomalyCount` | number | 요약 기간에 걸친 이상행동 총 건수(`anomalies`는 최대 20건이라 총 건수를 별도로 내린다). 없으면 `0` |
| Response(`/summary`) | `anomalies` | array | 요약 기간에 걸친 이상행동 목록(최대 20건, 아래 항목 구성). 없으면 필드를 생략하지 않고 빈 배열 `[]` |
| Request(`/search`, 바디) | `careTargetId` | string(UUID) | 검색 대상 CareTarget의 `public_id`(필수) |
| Request(`/search`, 바디) | `query` | string | 자연어 검색어(최대 500자). "지난주"/"이번달" 등 러프한 시간 키워드를 인식해 조회 기간을 좁히고, 인식되는 키워드가 없으면 최근 90일을 기본 기간으로 검색한다 |
| Response(`/search`) | `answer` | string | LLM이 생성한 답변 텍스트. 해당 기간에 일치하는 방문 기록이 없으면 에러가 아니라 "그런 기록이 없다"는 자연어 답변으로 응답한다 |
| Request(`/report/weekly`, 바디) | `careTargetId` | string(UUID) | 리포트 대상 CareTarget의 `public_id`(필수) |
| Response(`/report/weekly`) | `answer` | string | LLM이 생성한 주간 리포트 텍스트 |
| Response(`/report/weekly`) | `visitCount` | number | 리포트의 근거가 된 방문 건수 |
| Response(`/report/weekly`) | `anomalyCount`, `anomalies` | number, array | `/summary`와 동일(기간은 최근 7일) |

**`VISIT_001`(404) 규칙**

- `/search`: 대상 CareTarget에 방문 이력이 **아예 없으면**(조회 기간과 무관하게) `VISIT_001`을 반환하고 LLM을 호출하지 않는다. 기간에 일치하는 기록만 없으면 에러가 아니라 자연어 답변으로 응답한다.
- `/summary`, `/report/weekly`: **요청 기간(`/report/weekly`는 최근 7일)** 에 방문 기록과 이상행동이 모두 없을 때만 `VISIT_001`을 반환하고 LLM을 호출하지 않는다. (2026-09 정정: 이전 문서의 "방문 이력이 아예 없으면(기간 무관)"은 실제 코드 동작과 달랐다. 코드가 기간 기준으로 동작해 왔고, 근거 없는 기간을 LLM에 넘기지 않기 위해 코드 동작을 기준으로 문서를 맞췄다.)

| 기간 내 방문 | 기간 내 이상행동 | 결과 |
|---|---|---|
| 있음 | 있음 | 200 — LLM 요약(`answer`) + `anomalies` |
| 있음 | 없음 | 200 — LLM 요약 + `anomalyCount: 0`, `anomalies: []` |
| 없음 | 있음 | 200 — **LLM을 호출하지 않는다.** `answer`는 고정 문장 "해당 기간의 방문 기록은 없습니다. 아래 이상행동을 확인해 주세요.", `visitCount: 0`, `anomalies` 채움 |
| 없음 | 없음 | `VISIT_001`(404), LLM 미호출 |

이상행동이 있는데 방문 기록이 없는 경우(`ARRIVAL_DELAY`는 도착 기록이 없는 상황이고 `UNREGISTERED_STAY`는 등록 장소가 아니라 `VisitHistory`가 생기지 않는다)가 오히려 정상이므로, 이때 404로 막아 이상행동을 숨기지 않는다.

**`anomalies` 항목** — LLM과 무관하게 서버가 DB의 사실을 그대로 내린다(문장 생성 과정에서 시각/장소/진행 여부가 왜곡되지 않도록 §3.9 `/explain`을 고정 템플릿으로 만든 것과 같은 이유). LLM에는 이상행동 데이터를 전달하지 않고, "이상행동은 별도로 표시되므로 '이상 없음' 같은 단정적 표현을 쓰지 않는다"는 지침 한 문장만 시스템 지침에 포함한다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `anomalyEventId` | number | `AnomalyEvent` 내부 PK(§3.9 `/explain`의 `{anomalyEventId}`) |
| `type` | string | `ARRIVAL_DELAY` / `UNREGISTERED_STAY` |
| `status` | string | `ONGOING`(미해소) / `RESOLVED`(해소됨) |
| `detectedAt` | string(ISO-8601) | 감지 시각 |
| `resolvedAt` | string(ISO-8601), nullable | 해소 시각(`ONGOING`이면 `null`) |
| `placeId` | string(UUID), nullable | 관련 Place의 `public_id`. Place가 삭제됐으면 `null` |
| `placeName` | string, nullable | 관련 Place 이름. Place가 삭제됐으면 `null` |
| `notified` | boolean | **요청한 Guardian 본인이** 이 이상행동의 승격 푸시를 실제로 받았는가(`NotificationHistory`에 `FAILED`가 아닌 기록(`SENT`/`READ`/`RESPONDED`)이 있으면 `true`, 기록이 없거나 `FAILED`뿐이면 `false`). Guardian별로 다를 수 있는 유일한 필드 |

- 좌표는 내리지 않는다. 상세는 `anomalyEventId`로 §3.9 `/explain`을 호출한다.
- **노출 범위**: 알림 모드(`REALTIME`/`HYBRID`/`REPORT_ONLY`)와 일시정지 여부와 무관하게 기간에 걸친 이상행동을 **항상 전부** 노출한다. 알림 모드는 푸시 수신 방식일 뿐 CareTarget에게 일어난 사실의 조회 범위가 아니다(`REPORT_ONLY`는 이 응답이 유일한 창구, 정지는 "방해하지 말라"이지 "숨기라"가 아니다).
- **기간 기준(겹침)**: `detectedAt ≤ to` AND (`resolvedAt` 없음 OR `resolvedAt ≥ from`). 기간 이전에 감지돼 지금도 진행 중인 이상행동을 포함한다.
- **정렬/상한**: `ONGOING` 우선, 그다음 `detectedAt` 내림차순으로 최대 20건. 초과 여부는 `anomalyCount`로 확인한다.
- **중복 노출**: `HYBRID`에서 리포트에 노출된 이상행동이 나중에 승격 푸시로도 전달될 수 있다. 이는 중복이 아니라 "스냅샷"과 "임계 시간 경과"라는 서로 다른 정보이며, 승격을 억제하지 않고 `notified`로만 구분한다(DATABASE_DESIGN_GUIDE.md §15.5).
- LLM 장애(`AI_002`/`AI_004`) 시 기존 에러 계약을 유지한다. 이상행동은 §3.9 목록 API로 별도 확인할 수 있다.

`/report/weekly`는 `/summary`와 요청/응답 구조가 거의 같지만 기간을 요청자가 지정하지 않는다 — "이번 주"는 항상 요청 시점 기준 **최근 7일(rolling)**로 고정이다(문서에 정의가 없어 자체 결정, 근거는 `AiChatService` Javadoc 참고). 과거 특정 주를 조회하는 파라미터는 없으며, 온디맨드(호출 시점에 즉시 생성) 방식만 지원한다 — 정기 배치/자동 생성은 지원하지 않는다.

성공 코드: `AI_001` · 주요 실패 코드: `AI_002`(500, LLM API 호출 실패), `AI_004`(429, LLM 호출 한도 초과), `TARGET_002`(403, `careTargetId` 관계 미매핑), `VISIT_001`(404, `/search`는 방문 이력이 아예 없을 때, `/summary`·`/report/weekly`는 기간 내 방문과 이상행동이 모두 없을 때), `COMMON_002`(400, `careTargetId` 누락 또는 `/summary`의 `from`이 `to`보다 이후)

### 3.7 알림

| Method | URI | 설명 |
|---|---|---|
| GET | `/api/guardian/notifications` | 알림 목록 — **읽지 않은(status≠READ) 알림만** |
| PUT | `/api/guardian/notifications/{id}/read` | 읽음 처리 |
| GET | `/api/guardian/notifications/history` | 알림 이력 전체 — 읽음 여부 무관, 전체 |

**목록 vs 이력**: `/notifications`(목록)는 트리아지용으로 안읽은 알림만 반환하고(`idx_nh_user_status(user_id, status) WHERE status<>'READ'` 인덱스 사용), `/notifications/history`(이력)는 감사/과거 조회용으로 읽음 여부와 무관하게 전체를 반환한다(`idx_nh_user_sent(user_id, sent_at DESC)` 인덱스 사용) — 두 인덱스가 DB에 공존하는 이유이기도 하다.

`{id}` = NotificationHistory 내부 PK(시계열 데이터, `public_id` 정책 미적용).

응답 `content` 항목의 `body`는 도착(ARRIVAL) 알림이면 실제 장소명을 포함한 문구("{장소명}에 도착했습니다")를 우선 사용하고, 장소명 스냅샷이 없는 레코드(2026-08 `place_name` 컬럼 추가 이전 생성분)는 일반화된 문구로 대체한다.

성공 코드: `NOTI_001`(조회) / `NOTI_002`(읽음 처리) · 주요 실패 코드: `NOTI_001`(404)

### 3.8 내 정보

| Method | URI | 설명 |
|---|---|---|
| GET | `/api/guardian/profile` | 내 정보 조회 |
| PUT | `/api/guardian/profile` | 내 정보 수정 |
| PUT | `/api/guardian/profile/image` | 프로필 이미지 변경 |

성공 코드: `USER_001`(조회) / `USER_002`(수정)

### 3.9 이상행동 (ANOMALY)

이상행동(`ARRIVAL_DELAY`/`UNREGISTERED_STAY`) 감지 결과(`AnomalyEvent`, DATABASE_DESIGN_GUIDE.md §15)를 Guardian이 조회하는 API다. **LLM을 호출하지 않는다** — 감지 자체는 스케줄러/위치 수신 흐름이 수행하고(§15.1/§15.2), 이 절의 API는 저장된 데이터만 읽는다. 에러 코드 도메인은 LLM 계열(`AI_*`)이 아니라 `ANOMALY_*`를 쓴다(API_Response_Rule.md §5.1).

| Method | URI | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/guardian/anomalies` | Guardian | 이상행동 목록 조회 |
| GET | `/api/guardian/anomalies/questions` | Guardian | 이상행동 유형별 질문 카탈로그 조회 |
| GET | `/api/guardian/anomalies/{anomalyEventId}/explain?question={questionKey}` | Guardian | 이상행동 설명 — 선택한 질문에 대한 저장 데이터 기반 답변 |

권한은 공통으로 1) 인증, 2) `SecurityConfig`의 `/api/guardian/**` → Guardian Role(불일치 시 `GUARDIAN_001`), 3) 리소스가 있는 API는 Service 계층의 소유권 검증(호출자가 해당 CareTarget의 ACTIVE Guardian인지)의 3단계를 따른다(Security_Guide.md §4.5).

#### `GET /api/guardian/anomalies` — 목록 조회

| 구분 | 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| Query | `careTargetId` | string(UUID) | **필수** | 조회 대상 CareTarget의 `public_id`. `/places`, `/history/*`와 동일하게 CareTarget을 먼저 선택한 뒤 조회하는 흐름이다 |
| Query | `type` | string | 선택 | `ARRIVAL_DELAY` / `UNREGISTERED_STAY`. 생략하면 전체 유형. 허용되지 않은 값은 `COMMON_002`(400) |
| Query | `from` | string(ISO-8601 Instant) | 선택 | 조회 기간 시작(포함) — `detected_at` 기준. 생략 시 `to`−7일 |
| Query | `to` | string(ISO-8601 Instant) | 선택 | 조회 기간 끝. 생략 시 현재 시각 |
| Query | `page`/`size` | number | 선택 | 표준 `Pageable`. 응답은 `PageResponse` 구조(API_Response_Rule.md §2.2) |

- **기간 규칙**: `from`/`to`를 모두 생략하면 "최근 7일"이다. 둘 중 하나만 주면 나머지는 위 기본값으로 채운다. `from`이 `to`보다 이후이거나 `to`−`from`이 **90일을 초과하면 `COMMON_002`(400)** 이다(`/summary`의 `from > to` 처리와 동일한 코드).
- 정렬은 서버에서 `detected_at DESC`로 고정한다(클라이언트 정렬 파라미터 없음). `idx_ae_user_type_detected(user_id, type, detected_at DESC)`를 사용한다(DATABASE_DESIGN_GUIDE.md §9).
- **결과가 0건이면 404가 아니라 빈 목록(200)** 을 반환한다 — "이상 없음"은 오류가 아니라 정상 결과이고, `/places`·`/notifications`와 같은 "현재 상태 나열형" 조회이기 때문이다(방문 히스토리 `VISIT_001`(404)과는 성격이 다르다).
- `careTargetId`에 해당하는 CareTarget이 없으면 `TARGET_001`(404), 호출자가 그 CareTarget의 ACTIVE Guardian이 아니면 빈 목록이 아니라 `TARGET_002`(403)이다.

응답 `content` 항목:

| 필드 | 타입 | 설명 |
|---|---|---|
| `anomalyEventId` | number | `AnomalyEvent` 내부 PK(시계열 이력 데이터라 `public_id` 정책 미적용, API_Response_Rule.md §1.5). 노출된 값이므로 `/explain`에서 소유권 검증이 필수다 |
| `type` | string | `ARRIVAL_DELAY` / `UNREGISTERED_STAY` |
| `status` | string | `ONGOING`(`resolved_at`이 null) / `RESOLVED`. 서버가 계산해 내려준다 |
| `detectedAt` | string(ISO-8601 Instant) | 감지 시각 |
| `resolvedAt` | string, nullable | 해제 시각. 진행 중이면 null |
| `placeId` | string(UUID), nullable | 관련 Place의 `public_id`(Master Data라 내부 PK를 노출하지 않는다). `ARRIVAL_DELAY`는 지연된 예정 장소, `UNREGISTERED_STAY`는 감지 시점에 가장 가까웠던 등록 장소(있으면). **Soft Delete된 Place는 null**(`placeName`과 동일하게 처리하며, 문자열 기본값을 넣지 않는다) |
| `placeName` | string, nullable | 조회 시 페이지의 Place를 `IN` 쿼리로 일괄 조회해 채운다(스냅샷 컬럼 아님, N+1 없음). **Soft Delete된 Place는 null** |
| `latitude`/`longitude` | number, nullable | `UNREGISTERED_STAY`에서만 값이 있다 |

`escalatedAt`은 응답에 포함하지 않는다 — `AnomalyEvent.escalated_at`은 "최초 승격 시각(참고용)"이라 이 Guardian이 실제로 푸시를 받았는지를 뜻하지 않기 때문이다(DATABASE_DESIGN_GUIDE.md §15.2, §15.6).

성공 코드: `ANOMALY_001` · 주요 실패 코드: `TARGET_001`(404, CareTarget 없음), `TARGET_002`(403, 관계 미매핑), `COMMON_002`(400, `careTargetId` 누락, `type` 값 오류, 기간 값 오류)

#### `GET /api/guardian/anomalies/questions` — 질문 카탈로그 조회

이상행동 유형별로 고정된 질문 템플릿의 **키와 표시 문구**를 서버가 내려준다(질문 카탈로그는 Frontend가 하드코딩하지 않는다). Frontend는 이상행동 목록에서 항목을 선택했을 때 그 항목의 `type`에 해당하는 카탈로그를 보여주고, 사용자가 고른 `questionKey`를 `/explain`에 전달한다. 카탈로그는 고정 목록이라 앱 실행 중 캐시해도 된다.

- **목록 API 응답에 질문을 섞지 않고 별도 엔드포인트로 분리했다** — 목록 응답 필드는 위 표로 확정돼 있고, 항목마다 같은 질문 목록을 반복해 내려주면 페이지당 응답 크기만 늘어난다.
- 질문 목록은 DB가 아니라 Backend 코드 상수(enum)로 관리한다(DATABASE_DESIGN_GUIDE.md §15.7).

| 구분 | 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| Query | `type` | string | **필수** | `ARRIVAL_DELAY` / `UNREGISTERED_STAY`. 누락되거나 허용되지 않은 값이면 `COMMON_002`(400) |
| Response `content` 항목 | `questionKey` | string | - | `/explain`의 `question` 파라미터에 넘기는 값 |
| Response `content` 항목 | `text` | string | - | 사용자에게 보여줄 질문 문구 |

응답은 고정 목록이라 페이징하지 않지만 다른 목록 API와 같은 `PageResponse` 구조로 감싸 내려준다(`/places`와 동일한 관행). 항목 순서는 아래 표의 순서다. 리소스(CareTarget/이벤트)를 다루지 않으므로 소유권 검증은 없다.

성공 코드: `ANOMALY_003` · 주요 실패 코드: `COMMON_002`(400)

**질문 카탈로그** (`ARRIVAL_DELAY` 7개, `UNREGISTERED_STAY` 8개 — 답변에 쓰는 데이터 출처는 DATABASE_DESIGN_GUIDE.md §15.7)

| 유형 | `questionKey` | `text` |
|---|---|---|
| `ARRIVAL_DELAY` | `DELAY_REASON` | 왜 감지됐습니까? |
| `ARRIVAL_DELAY` | `DELAY_EXPECTED_TIME` | 원래 도착 예정 시각은 언제였습니까? |
| `ARRIVAL_DELAY` | `DELAY_DURATION` | 현재까지 얼마나 지연됐습니까? |
| `ARRIVAL_DELAY` | `DELAY_CURRENT_LOCATION` | 지금 어디에 있습니까? |
| `ARRIVAL_DELAY` | `DELAY_LAST_VISIT` | 이 장소를 마지막으로 방문한 때는 언제입니까? |
| `ARRIVAL_DELAY` | `DELAY_COUNT_7D` | 최근 7일간 몇 번 지연됐습니까? |
| `ARRIVAL_DELAY` | `DELAY_COUNT_30D` | 최근 30일간 몇 번 지연됐습니까? |
| `UNREGISTERED_STAY` | `STAY_REASON` | 왜 감지됐습니까? |
| `UNREGISTERED_STAY` | `STAY_LOCATION` | 정확한 위치는 어디입니까? |
| `UNREGISTERED_STAY` | `STAY_STARTED_AT` | 언제부터 머물렀습니까? |
| `UNREGISTERED_STAY` | `STAY_ELAPSED` | 얼마나 머물렀습니까? |
| `UNREGISTERED_STAY` | `STAY_ONGOING` | 지금도 머물고 있습니까? |
| `UNREGISTERED_STAY` | `STAY_NEAREST_PLACE_DISTANCE` | 가장 가까운 등록 장소까지 얼마나 떨어져 있습니까? |
| `UNREGISTERED_STAY` | `STAY_COUNT_7D` | 최근 7일간 몇 번 발생했습니까? |
| `UNREGISTERED_STAY` | `STAY_SIMILAR_PAST` | 과거 비슷한 위치에서 {N}분 이상 머문 이력이 있습니까? |

질문 문구는 답변 문장과 같은 합니다체 의문형(~습니까?/~입니까?)이다. `STAY_SIMILAR_PAST`의 `{N}분`은 고정 문자열이 아니라 감지 기준 설정값(`anomaly.unregistered-stay-detect-minutes`, 기본 10분)이며, enum에는 자리표시자로 두고 **카탈로그 조회 시점에 채워서** 내려준다(답변 문장의 "N분 이상"과 항상 같은 값).

#### `GET /api/guardian/anomalies/{anomalyEventId}/explain?question={questionKey}` — 이상행동 설명

- **LLM을 호출하지 않는다.** 서버가 선택된 질문에 대응하는 저장 데이터를 조회해 문장을 조립해서 응답한다. 기존 `POST /api/guardian/ai/explain`은 폐기됐다(§7.3).
- `{anomalyEventId}` = `AnomalyEvent` 내부 PK.

| 구분 | 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| Path | `anomalyEventId` | number | **필수** | 설명을 볼 이상행동 이벤트의 내부 PK |
| Query | `question` | string | **필수** | 위 카탈로그의 `questionKey`. 누락 시 `COMMON_002`(400) |
| Response | `answer` | string | - | 선택한 질문에 대한 답변 문장. **질문 종류와 무관하게 항상 문자열 하나**(`/summary`, `/report/weekly`와 동일 구조) |

**검증 순서**: ① `question` 누락 → `COMMON_002`(400) → ② 이벤트 없음 → `ANOMALY_001`(404) → ③ 호출자가 이 이벤트가 속한 CareTarget의 ACTIVE Guardian이 아님 → `TARGET_002`(403, `AccessDeniedCustomException`) → ④ `questionKey`가 카탈로그에 없거나 **이 이벤트의 `type`에 속하지 않는 키**(예: `ARRIVAL_DELAY` 이벤트에 `STAY_*` 키) → `ANOMALY_002`(400). 키 검증이 이벤트의 유형에 종속되므로 소유권 검증 뒤에 수행한다(소유자가 아닌 호출자는 키가 무엇이든 403을 받는다).

**답변 조립 시 유의사항**
- 시각은 서버 기본 타임존 기준 문장("yyyy년 M월 d일 HH:mm", 연도 포함)으로 표기한다(프로젝트의 기존 관행, `AiChatService`와 동일).
- 답변 문장은 합니다체로 통일하고, 일반 사용자에게 노출되므로 "이벤트"라는 개발자 용어 대신 "이상행동"이라고 쓴다. "이 알림"이라는 표현은 쓰지 않는다(REPORT_ONLY 보호자는 푸시 알림을 받지 않기 때문).
- 이 이상행동에 필요한 값이 없으면(예: Soft Delete된 Place, 스냅샷 컬럼이 추가되기 이전에 생성돼 `scheduled_at`/`stay_started_at`이 null인 이상행동) 에러가 아니라 "확인할 수 없습니다"류의 안내 문장으로 답한다. 질문마다 문구가 다르며 공통 문구 하나로 통일하지 않는다.
- 집계 문장(`DELAY_COUNT_7D`/`DELAY_COUNT_30D`/`STAY_COUNT_7D`)은 이번 이상행동이 집계 창(지금 기준 최근 N일) 안에 있을 때만 "이번을 포함해"를 앞에 붙인다. 창보다 오래된 이상행동에는 붙이지 않는다.
- `STAY_SIMILAR_PAST` 답변의 "N분 이상"은 감지 기준 설정값(`anomaly.unregistered-stay-detect-minutes`)을 그대로 쓴다. 같은 값이 카탈로그의 질문 문구에도 반영된다.
- `DELAY_LAST_VISIT`는 장소 자체를 알 수 없는 경우(이상행동에 `place_id`가 없거나 Soft Delete로 Place가 조회되지 않음)에는 `DELAY_REASON`과 같은 패턴("이 이상행동에 연결된 장소 정보가 없어 방문 기록을 확인할 수 없습니다.")으로 답하고, 장소는 있는데 방문 이력만 없는 경우에는 "이 장소의 방문 기록이 아직 없습니다."로 구분해 답한다.
- 좌표를 포함하는 답변(`DELAY_CURRENT_LOCATION`, `STAY_LOCATION`)이 있으나 **Audit Log는 이번 범위에 포함하지 않는다**(위치 노출 API 전체 공통 도입 로드맵 항목, Logging_Guide.md §12.1).

성공 코드: `ANOMALY_002` · 주요 실패 코드: `ANOMALY_001`(404, 이벤트 없음), `ANOMALY_002`(400, 잘못된 질문 키), `TARGET_002`(403, 소유권 불일치), `COMMON_002`(400, `question` 누락)

### 3.10 알림 설정 — 이상행동 알림 (GuardianTarget)

Guardian이 **자신의** 이상행동 알림 방식(`GuardianTarget.notification_mode`/`escalate_minutes_*`/`paused_until`, DATABASE_DESIGN_GUIDE.md §15.3)을 조회·변경하고 일시정지하는 API다. 대상이 `GuardianTarget` 행이라 성공 코드는 `TARGET_*` 도메인을 쓴다(API_Response_Rule.md §2.3).

> **적용 범위 (반드시 지킬 것)**: 이 절의 설정과 일시정지는 **이상행동 알림(`ARRIVAL_DELAY`/`UNREGISTERED_STAY`의 즉시 알림 승격)에만** 적용된다. **GeoFence 도착 확인 알림과 긴급 연락(`EMERGENCY_*`)은 어떤 모드(`REPORT_ONLY`, 일시정지 포함)에서도 영향받지 않는다.** 현재 `notification_mode`를 읽는 곳은 `AnomalyScheduler`의 승격 판단뿐이며, 안전 기능(긴급 연락)은 이 설정으로 억제하지 않는다(fail-open 금지, .claude/rules/exception.md).

**화면 문구 가이드**: 버튼·라벨은 반드시 **"이상행동 알림 멈추기"**로 쓴다. 그냥 "알림 멈추기"라고 하면 도착 확인 알림·긴급 연락까지 꺼지는 것으로 오해한다.
- 정지 버튼 예: "이상행동 알림 30분 멈추기" / 안내 문구 예: "이상행동 알림만 멈춥니다. 도착 확인 알림과 긴급 연락은 계속 받습니다."
- 정지 중 상태 예: "이상행동 알림 일시정지 중 (오후 3:20까지)"
- **최대 시간 안내는 필요하다** — "더 멈추기"를 눌렀는데 시간이 요청한 만큼 늘지 않으면 사용자는 버튼이 고장 난 것으로 오해한다. 정지 화면(또는 시간 선택 시트)에 "이상행동 알림은 지금부터 최대 24시간까지 멈출 수 있습니다."를 상시 표기한다.
- **상한에 걸린 경우의 피드백**: 서버는 200과 실제 적용된 `pausedUntil`을 내려주므로(에러 아님), Frontend는 요청 전 값과 응답 값을 비교해 **요청한 만큼 늘어나지 않았다면** 안내 문구를 보여준다. 예: 일부만 늘어남 → "최대 24시간까지만 멈출 수 있어 오후 3:00까지로 설정했습니다." / 전혀 늘어나지 않음 → "이미 최대 24시간까지 멈춰 있습니다. 더 늘릴 수 없습니다."
- 이미 최대치에 가까우면 "더 멈추기" 버튼은 **비활성화하지 않아도 된다**(눌러도 안전하게 무변화 200). 다만 위 안내 문구로 이유를 알려준다.
- 서버는 `REPORT_ONLY`(리포트로만 확인)와 정지를 구분해 내려주므로, 화면에서도 두 상태를 다른 표현으로 보여준다.

| Method | URI | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/guardian/care-targets/{id}/notification-settings` | Guardian | 내 이상행동 알림 설정 조회 |
| PUT | `/api/guardian/care-targets/{id}/notification-settings` | Guardian | 설정 **전체 교체**(모드 + 승격 분 2종) |
| POST | `/api/guardian/care-targets/{id}/notification-settings/pause` | Guardian | 이상행동 알림 일시정지 |
| POST | `/api/guardian/care-targets/{id}/notification-settings/resume` | Guardian | 즉시 재개 |

`{id}` = 대상 CareTarget(User)의 `public_id`(§3.1과 동일). 설정은 **호출자 본인의 `GuardianTarget` 행**에 저장된다 — PRIMARY가 SUB의 설정을 대신 바꿀 수 없고(요청에 `guardianId`를 받지 않는다), PRIMARY/SUB 사이에 권한 차이는 없다(각자 자기 설정만 다룬다).

**응답 (4개 엔드포인트 공통)** — 정지는 모드 값이 아니라 **오버레이**(`paused`/`pausedUntil`)로 표현한다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `notificationMode` | string | `REALTIME` / `REPORT_ONLY` / `HYBRID`. **`PAUSED`는 이 필드에 나오지 않는다.** 정지 중이면 "정지가 풀린 뒤 돌아갈 기본 모드"를 내려준다 |
| `escalateMinutesArrival` | integer, nullable | `ARRIVAL_DELAY` 감지 후 이 분을 넘기면 즉시 알림으로 승격. `null` = 승격 안 함(항상 리포트로만) |
| `escalateMinutesStay` | integer, nullable | `UNREGISTERED_STAY`용, 의미 동일 |
| `paused` | boolean | 정지 중 여부 |
| `pausedUntil` | string(ISO-8601 Instant), nullable | 정지 자동 해제 예정 시각. `paused=false`면 `null` |

**PUT 요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `notificationMode` | string | **필수** | `REALTIME` / `REPORT_ONLY` / `HYBRID`. `PAUSED`나 그 밖의 값은 `COMMON_002`(정지는 기간이 필요한 액션이라 `pause`로만 진입) |
| `escalateMinutesArrival` | integer/null | **키 필수** | 1~1440. 명시적 `null` = "승격 안 함". **키를 아예 빠뜨리면 `COMMON_002`** |
| `escalateMinutesStay` | integer/null | **키 필수** | 위와 동일 |

- **왜 키 누락을 거부하는가**: `null`이 "승격 안 함"이라는 의미를 가지므로, 클라이언트 실수로 키가 빠진 요청이 조용히 승격을 꺼버리면 안전 기능이 의도치 않게 꺼진다. 그래서 "키가 없음"(오류)과 "명시적 null"(의도)을 구분한다.
- **전체 교체**: 세 값을 항상 함께 보낸다(부분 수정 없음). 그래서 `REPORT_ONLY` → `HYBRID`로 되돌릴 때도 클라이언트가 분 값을 다시 명시하게 되어 "조용한 null"이 생기지 않는다.
- `REPORT_ONLY`에서 보낸 분 값은 거부·무시하지 않고 **보낸 그대로 저장**한다. 스케줄러가 `REALTIME`/`HYBRID`만 승격 대상으로 보므로 무해하고, `NULL` = "승격 안 함" 규칙(§15.2)은 `REALTIME`/`HYBRID`에서만 의미가 있다.
- **정지 중 PUT**: 정지를 해제하지 않는다. `notificationMode`는 "정지가 풀린 뒤 돌아갈 기본 모드"를 바꾸고(내부적으로 `previous_notification_mode` 갱신), 분 값은 즉시 저장된다. 응답의 `paused`/`pausedUntil`은 그대로다.
- 분 값이 정수가 아니거나(`"abc"` 등) 본문이 올바른 JSON이 아니어도 `COMMON_002`(400)다(500이 아님).

**pause 요청**: `{ "durationMinutes": 30 }`

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `durationMinutes` | integer | **필수** | **1~1440**(1분~24시간). 범위 밖·누락·비정수는 `COMMON_002`. **무기한 정지는 지원하지 않는다**(안전 서비스에서 "잊고 계속 꺼둔" 상태를 막기 위해) |

- **`pausedUntil = min( max(기존 pausedUntil, 현재 시각) + durationMinutes, 현재 시각 + 1440분 )`** — 누를 때마다 시간이 늘어나는 **누적 방식**이되, **정지 남은 시간은 지금부터 24시간을 넘지 못한다.** 정지 중이 아니면(또는 기존 `pausedUntil`이 이미 지난 경우) 현재 시각이 기준이다. 서버가 계산하며 클라이언트 시각은 신뢰하지 않는다.
  - 예(상한 미적용): 정지 중이고 `pausedUntil`이 15:20일 때 15:00에 `30`을 보내면 15:50이 된다(기존 남은 20분 + 30분).
  - 예(24시간 근접): 15:00에 `pausedUntil`이 다음 날 14:30인 상태에서 `60`을 보내면 다음 날 **15:00**이 된다(요청은 60분이지만 상한에 걸려 30분만 늘어남).
  - 예(이미 최대): `pausedUntil`이 이미 "지금부터 24시간"이면 `30`을 보내도 **더 늘어나지 않는다**(값 그대로, 에러 아님).
- **24시간에 도달하면 더 이상 늘어나지 않는다.** 상한에 걸려 잘리거나 전혀 늘어나지 않아도 **200으로 응답**하고 실제 적용된 `pausedUntil`을 내려준다(별도 에러 코드 없음).
- **상한의 정확한 의미**: 상한은 "지금부터 24시간"이라 시간이 지나면 함께 이동한다(최대치에서 10분 뒤 다시 누르면 10분 늘어난다). 즉 **정지의 남은 시간이 항상 24시간 이하**임을 보장하는 것이지, 정지 시작 후 연속 정지 총 길이가 24시간 이하임을 보장하는 것은 아니다. 사용자가 계속 눌러야만 이어지고, 누르지 않으면 최대 24시간 안에 자동 복귀한다.
- **정지 중이 아니었다면** 이 시점의 기본 모드가 "정지가 풀린 뒤 돌아갈 모드"로 저장된다. **이미 정지 중이면 돌아갈 모드는 건드리지 않고 `pausedUntil`만 늘린다**(이미 정지 중인 상태를 "돌아갈 모드"로 저장하면 안 되므로).
- `durationMinutes`의 상한(1440분)은 **요청 1회당** 상한이고, 누적 연장의 총합은 위 계산식의 `현재 시각 + 1440분`으로 제한된다.
- 정지 시간이 지나면 `AnomalyScheduler`가 자동으로 기본 모드로 복귀시킨다(§15.3, 매 tick 확인 — 정지 해제 시각과 실제 복귀 사이에 최대 스케줄러 주기만큼 지연이 있을 수 있다).

> **Frontend 필수 주의 — pause 버튼 더블탭 방지**: pause 요청이 진행되는 동안 버튼을 **반드시 비활성화**해야 한다. 서버는 누적 방식이라 같은 요청이 두 번 도착하면 두 번 연장된다(예: 30분 버튼을 더블탭하면 60분 정지). **Backend만으로는 "실수로 중복된 클릭"과 "의도적으로 한 번 더 연장하려는 요청"을 구분할 수 없다** — 두 경우 모두 동일한 `durationMinutes`를 가진 정상 요청이기 때문이다(요청을 식별하는 값이 없고, 시간 창 기반 중복 제거는 의도적 연장을 막게 된다). 응답의 `pausedUntil`을 화면에 바로 반영해 사용자가 실제 적용된 시각을 확인할 수 있게 한다(24시간 상한에 걸리면 요청한 만큼 늘어나지 않을 수 있다). 더블탭으로 두 번 도착해도 결과는 `현재 시각 + 24시간`을 넘지 않는다.

**resume**: 본문 없음. 정지 중이면 기본 모드로 즉시 복귀하고 `pausedUntil`을 비운다. **정지 중이 아니어도 에러가 아니라 200(no-op)으로 현재 설정을 그대로 반환한다** — 더블탭, 재시도, 스케줄러 자동 복귀와의 경합에서 사용자에게 오류를 보이지 않기 위해서다.

**검증·권한 순서**: ① `@Valid` 본문 검증(`COMMON_002`, Controller 진입 시점) → ② 인증 → ③ `/api/guardian/**` Guardian Role(`GUARDIAN_001`) → ④ CareTarget 없음 `TARGET_001`(404) → ⑤ 호출자와 **ACTIVE 관계가 아니면** `TARGET_002`(403, PENDING/TERMINATED 제외) → 저장. 쓰기(PUT/pause/resume)는 행 단위 비관적 락으로 처리한다(동시 요청 및 스케줄러 자동 복귀와의 경합에서 갱신 유실 방지, DATABASE_DESIGN_GUIDE.md §15.8).

관계가 해제(`TERMINATED`)되었다가 다시 연결되면 새 `GuardianTarget` 행이 생성되므로 설정은 기본값(`HYBRID`, 30분/60분)으로 초기화된다.

성공 코드: `TARGET_011`(조회) / `TARGET_012`(수정) / `TARGET_013`(정지) / `TARGET_014`(재개) · 주요 실패 코드: `TARGET_001`(404), `TARGET_002`(403), `COMMON_002`(400, 모드/분/기간 값 오류·키 누락·잘못된 JSON). **새 에러 코드는 만들지 않는다.**

---

## 4. 보호대상자(CareTarget) API

**Base Path**: `/api/care-target` · **권한**: 모두 `CareTarget` 전용 (`ROLE_GUARDIAN` 호출 시 `COMMON_006`, 403)

### 4.1 현재 위치

| Method | URI | 설명 |
|---|---|---|
| POST | `/api/care-target/location` | 위치 전송(GPS 원본, 주기적 호출) |
| GET | `/api/care-target/location` | 자신의 현재 위치 조회 |
| WebSocket | `/ws/care-target/location` | 실시간 위치 송신 (§5 참고) |

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Request | `latitude`, `longitude` | double | GPS 좌표 (위경도 범위 검증) |
| Request | `recordedAt` | string(ISO-8601 UTC) | 측정 시각 |
| Response | `locationId` | int | LocationHistory 내부 PK(시계열, `public_id` 미적용) |
| Response | `recordedAt` | - | 저장된 시각 |

성공 코드: `LOCATION_002` · 주요 실패 코드: `LOCATION_001`(400, 좌표 범위 오류), `LOCATION_003`(403, Guardian 계정 호출)

### 4.2 도착 확인

| Method | URI | 설명 |
|---|---|---|
| POST | `/api/care-target/arrival/check` | 등록 장소 도착 확인 |
| GET | `/api/care-target/arrival/history` | 도착 기록 조회 |

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Request | `placeId` | string(UUID) | 도착 대상 Place `public_id` |
| Request | `latitude`, `longitude` | double | 현재 좌표(GeoFence 반경 판정용) |
| Response | `arrivalId` | int | ArrivalHistory 내부 PK |
| Response | `placeName`, `confirmedAt` | - | 확인 결과 |

성공 코드: `ARRIVAL_001` · 주요 실패 코드: `ARRIVAL_001`(403, Guardian 호출), `ARRIVAL_002`(400, GeoFence 반경 밖), `ARRIVAL_003`(404, 기록 없음)

### 4.3 현재 위치 공유

| Method | URI | 설명 |
|---|---|---|
| POST | `/api/care-target/share/location` | 현재 위치를 보호자에게 즉시 공유 |

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Request | `latitude`, `longitude` | double | GPS 좌표 (§4.1과 동일한 위경도 범위 검증) |
| Request | `recordedAt` | string(ISO-8601 UTC) | 측정 시각 |
| Response | `data` | - | `null` |

§4.1(위치 전송)과 동일한 요청 형식이다 — "즉시 공유"가 이미 저장된 최신 위치를 재전달하는 게 아니라, 그 순간의 새 좌표를 받아 Redis 갱신 + WebSocket 개인화 큐 발행까지 즉시 수행하는 흐름이기 때문이다(구현 반영, 2026-08). 저장은 비동기로 처리되어 응답에 `locationId`를 포함하지 않는다.

성공 코드: `LOCATION_002` · 주요 실패 코드: `LOCATION_001`(400, 좌표 범위 오류), `LOCATION_004`(403, Guardian 호출)

### 4.4 긴급 연락

| Method | URI | 설명 |
|---|---|---|
| POST | `/api/care-target/emergency/call` | 등록된 보호자에게 즉시 전화 연결 요청 |
| POST | `/api/care-target/emergency/message` | 보호자에게 긴급 문자 발송 |
| POST | `/api/care-target/emergency/location` | 긴급 상황 시 현재 위치 즉시 전송 |

> 안전(Safety) 핵심 기능. 실패 처리 원칙은 API_Response_Rule.md §5.2(EMERGENCY 도메인), §8.9를 반드시 함께 따른다 — 일반화된 500 문구로 뭉개지 않고 재시도/대체 수단을 안내한다.

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Request(`/location`) | `latitude`, `longitude`, `recordedAt` | - | §4.3(현재 위치 공유)과 동일한 형식 — Redis 갱신 + WebSocket 발행까지 그대로 재사용, "긴급" 여부만 알림 발송 경로에 반영된다(구현 반영, 2026-08) |
| Request(`/call`, `/message`) | — | — | 요청 바디 없음(호출자 = 인증된 CareTarget 본인, 대상 = 그 CareTarget의 ACTIVE Guardian 전원으로 자동 결정) |
| Response(공통) | `eventId` | string(UUID) | 이 발송 시도를 묶는 `NotificationHistory.event_id` |
| Response(공통) | `guardianContacted` | boolean | ACTIVE Guardian 중 1명 이상 발송 성공 여부(= 응답 `success:true`와 항상 일치) |

3개 엔드포인트는 응답 형식과 성공/실패 판단 기준(ACTIVE Guardian 전원 시도 → 1명 이상 성공 시 전체 성공, 전원 실패 시에만 `EMERGENCY_003`)을 공유한다. 상세 예시는 API_Response_Rule.md §8.9 참고.

성공 코드: `EMERGENCY_001`(2026-08 정정 — 기존 `NOTI_001`은 문서 오류) · 주요 실패 코드: `EMERGENCY_001`(403, Guardian 호출), `EMERGENCY_002`(400, 등록된 보호자 연락처 없음), `EMERGENCY_003`(500, 연동 자체 실패, fail-safe 대상)

### 4.5 AI 도우미

| Method | URI | 설명 |
|---|---|---|
| POST | `/api/care-target/ai/chat` | AI와 자유 대화 |
| POST | `/api/care-target/ai/help` | 도움 요청 |
| POST | `/api/care-target/ai/navigation` | 등록 장소까지 안내 |
| POST | `/api/care-target/ai/location` | 현재 위치를 말로 설명 |

성공 코드: `AI_001` · 주요 실패 코드: `AI_002`(500), `AI_004`(429)

### 4.6 알림 / 내 정보

| Method | URI | 설명 |
|---|---|---|
| GET | `/api/care-target/notifications` | 알림 조회 — **전체**(읽음 여부 무관) |
| PUT | `/api/care-target/notifications/{id}/read` | 읽음 처리 |
| GET | `/api/care-target/profile` | 내 정보 조회 |
| PUT | `/api/care-target/profile` | 내 정보 수정 |

Guardian 쪽(§3.7)과 달리 "목록(안읽음)"/"이력(전체)" 두 엔드포인트로 나뉘어 있지 않다 — CareTarget에게는 별도 이력 엔드포인트가 없어, 이 하나뿐인 조회 엔드포인트가 §3.7의 `/notifications/history`와 동일하게 전체를 반환한다. 관계 검증(§4.5 등)이 필요 없다 — 조회 대상이 항상 호출자 자신이 수신한 알림이기 때문이다.

성공 코드: `NOTI_001` / `USER_001` / `USER_002`

### 4.7 보호자 연결 관리 (초대)

> Guardian이 CareTarget과 연결되는 유일한 경로다(`DATABASE_DESIGN_GUIDE.md` §3.2/§7 확정). CareTarget이 코드를 발급 → Guardian이 코드를 입력해 요청(§3.1) → CareTarget이 아래에서 승인/거절, 3단계로 구성된다. 코드 생성은 CareTarget 1인당 5회/일로 제한되고, 발급된 코드는 10분간 유효하며, 코드 입력 실패가 토큰당 5회 누적되면 즉시 폐기된다(`DATABASE_DESIGN_GUIDE.md` §7).

| Method | URI | 설명 |
|---|---|---|
| POST | `/api/care-target/guardians/invite-code` | 초대 코드 생성 |
| GET | `/api/care-target/guardians/pending` | 승인 대기 중인 연결 요청 목록 |
| POST | `/api/care-target/guardians/pending/{guardianId}/approve` | 요청 승인 (관계 생성) |
| POST | `/api/care-target/guardians/pending/{guardianId}/reject` | 요청 거절 |

`{guardianId}` = 대기 목록에 나타난 요청자 Guardian의 `public_id`.

| 구분 | 필드 | 타입 | 설명 |
|---|---|---|---|
| Response(코드 생성) | `inviteCode` | string | 발급된 초대 코드 |
| Response(코드 생성) | `expiresAt` | string(ISO-8601 UTC) | 발급 시각 + 10분 |
| Response(대기 목록) | `guardianId` | string(UUID) | 요청한 Guardian의 `public_id` |
| Response(대기 목록) | `name` | string | 요청한 Guardian 이름(승인 대상 확인용) |
| Response(승인) | `guardianId` | string(UUID) | 승인된 Guardian의 `public_id` |
| Response(승인) | `guardianRole` | string | `PRIMARY`(해당 CareTarget의 첫 승인 Guardian) / `SUB`(이후 승인) — §2 확인 사항 |
| Response(승인) | `relation`, `alias` | string(nullable) | 승인 직후에는 항상 `null` — 관계 자체가 이 시점에 막 생성되어 아직 라벨이 없다. Guardian이 이후 `PUT /api/guardian/care-targets/{id}`(§3.1)로 별도 설정한다 |
| Response(거절) | `data` | - | `null` |

성공 코드: `TARGET_003`(코드 생성) / `TARGET_004`(대기 목록 조회) / `TARGET_002`(승인, `GuardianTarget` 행이 실제로 생성되는 시점이므로 §3.1의 "등록 성공"과 동일 코드 재사용) / `TARGET_006`(거절) · 주요 실패 코드: `TARGET_005`(409, ACTIVE Guardian 정원 3명 초과 — 승인 시점에 검증), `TARGET_007`(429, 코드 생성 Rate Limit 초과)

---

## 5. WebSocket

REST Response 표준(§1.2, API_Response_Rule.md)의 적용 범위 밖이다. 별도의 경량 메시지 프레임을 사용한다.

| Path | 방향 | 용도 | 구현 상태 |
|---|---|---|---|
| `/ws/care-target/location` | CareTarget → Server | 실시간 GPS 송신(REST `POST /api/care-target/location`과는 별개의 스트리밍 수신 경로) | 미구현 — 다음 세션(2026-08 Phase 2에서 범위 밖으로 확정) |
| `/ws/guardian/location` | Server → Guardian | 실시간 위치 수신 | 구현 완료(Phase 2) |

`/ws/guardian/location`은 개인화 큐(`convertAndSendToUser`, Security_Guide.md §7.5.2)로만 동작한다 — Guardian이 CONNECT하면 서버가 그 세션에 인증된 사용자를 매핑하고, `POST /api/care-target/location`·`POST /api/care-target/share/location` 처리 흐름 안에서 해당 CareTarget의 ACTIVE Guardian 전원에게 `/user/{guardianId}/queue/location`으로 발행한다. 클라이언트는 `/queue/location`을 구독하면 된다(공용 Topic 없음, id 기반 SUBSCRIBE 자체가 존재하지 않음).

메시지 프레임 공통 형식(2026-08 Phase 2에서 `careTargetId` 추가 — 개인화 큐 구조상 한 Guardian의 여러 CareTarget 위치가 같은 큐로 들어와 페이로드로 구분해야 함):
```json
{ "type": "LOCATION_UPDATE", "payload": { "careTargetId": "3f2b1a10-9c4e-4a3b-8f2c-1d5e6a7b8c9d", "latitude": 37.501234, "longitude": 127.039876, "recordedAt": "2026-08-06T09:15:00Z" }, "timestamp": "2026-08-06T09:15:01Z" }
```

CONNECT 시 `Authorization: Bearer <accessToken>` STOMP 헤더로 REST와 동일한 JWT를 검증한다(Security_Guide.md §7.5.1). 검증 실패 시 연결 자체를 거부한다.

CONNECT 단계에서 인증, SUBSCRIBE 단계에서 리소스 소유권을 검증한다(상세: `docs/security/Security_Guide.md`). 공용 Topic보다 사용자별 개인화 큐(`convertAndSendToUser`)를 우선 사용한다.

---

## 6. 내부 API (시스템 전용)

**Base Path**: `/internal` · 사용자가 직접 호출하지 않는 서버 간 통신 전용 API. `denyAll` + 네트워크 레벨 차단을 함께 적용한다(`docs/security/Security_Guide.md`). API_Response_Rule.md의 클라이언트 처리 규칙(§4, §7)은 적용 대상이 아니다.

| Method | URI | 설명 | 호출 주체 |
|---|---|---|---|
| POST | `/internal/geofence/check` | GeoFence 진입/이탈 판단 | Spring Boot 내부 배치/트리거 |
| POST | `/internal/fcm/send` | FCM Push 발송 | Spring Boot → Firebase |
| POST | `/internal/ai/predict` | 머신러닝 예측 요청 | Spring Boot → FastAPI |
| POST | `/internal/llm/chat` | LLM 질의 | Spring Boot → LLM API |
| POST | `/internal/notification/send` | 알림 생성(NotificationHistory 적재) | Spring Boot 내부 |
| POST | `/internal/sms/send` | SMS 발송 | Spring Boot → SMS 연동사 |

---

## 7. 결정 기록 (Decision Log)

> 이전 리뷰에서 열어뒀던 이슈 2건을 확정했다. "확인 필요 항목"이 아니라 **결정 근거를 남기는 절**로 전환한다 — 나중에 "왜 이렇게 했더라?"를 다시 물어보지 않기 위함이다.

### 7.1 AI 리포트 URI 분리 (확정)

기획서 원문은 `GET /api/guardian/ai/report`(AI 예측 리포트)와 `POST /api/guardian/ai/report`(LLM 주간 리포트)가 같은 URI를 Method로만 구분하고 있었다. 성격이 다른 두 시스템(FastAPI 머신러닝 / LLM)이 같은 경로를 쓰면 로그·모니터링·API 문서에서 계속 혼동을 유발하므로, 아직 Backend 구현 전인 지금 시점에 아래처럼 분리했다(§3.5, §3.6에 반영 완료).

| 기존(기획서 원문) | 변경 | 소속 |
|---|---|---|
| `GET /api/guardian/ai/report` | `GET /api/guardian/ai/predict/report` | AI 방문 예측(머신러닝) 그룹 |
| `POST /api/guardian/ai/report` | `POST /api/guardian/ai/report/weekly` | AI 케어 비서(LLM) 그룹 — "주간 리포트"라는 원래 설명을 URI에 반영 |

구현 전 단계라 변경 비용이 문서 수정뿐이므로 지금 확정하는 것이 개발 후반(Controller·Flutter 호출부까지 만든 뒤 변경) 대비 비용이 훨씬 낮다.

### 7.2 Admin API — 이번 MVP 범위 제외 (확정)

`User.role` CHECK 제약에는 `ADMIN`이 포함돼 있고 기획서에도 "관리자 Role" 언급이 있지만, REST API 명세서에는 Admin 전용 엔드포인트가 정의된 적이 없었다. 핵심 가치가 "보호자-보호대상자 케어"인 이 프로젝트의 MVP 범위에 관리자 콘솔은 포함하지 않기로 확정한다.

- 이번 범위에 **포함하지 않음**: 회원 목록/정지, 신고 처리, 시스템 모니터링 대시보드 등 Admin 전용 API 일체
- DB의 `role IN ('ADMIN', ...)` 값 자체는 유지한다(향후 확장 여지를 막지 않기 위함이며, 지금 당장 이 값을 쓰는 API는 없다)
- 향후 필요해지면 이어서 설계할 후보만 남겨둔다: 회원 정지/탈퇴 처리, 신고·이상행동 리뷰, 시스템 상태 모니터링
- 향후 확장 시에는 이 문서에 `## 8. 관리자(Admin) API` 절을 신설해서 추가하고, 이 절(7.2)은 "제외 결정의 근거"로 남겨둔다

### 7.3 `/explain` — LLM 미사용 고정 질문 템플릿으로 전환, URI 변경 (확정, 2026-09-20)

기획서 원문의 `POST /api/guardian/ai/explain`(이상행동 설명)은 "AI 케어 비서(LLM 연동)" 그룹(§3.6)에 속해 LLM이 이상행동을 자연어로 설명하는 것을 전제로 했으나, 아래처럼 변경을 확정했다.

| 기존 | 변경 |
|---|---|
| `POST /api/guardian/ai/explain` — LLM(Gemini) 호출 전제 | **폐기** |
| — | `GET /api/guardian/anomalies/{anomalyEventId}/explain?question={questionKey}`(§3.9) — LLM을 호출하지 않고, 서버가 카탈로그로 제공하는 고정 질문 템플릿(`ARRIVAL_DELAY` 7개, `UNREGISTERED_STAY` 8개) 중 사용자가 선택한 질문에 대응하는 저장 데이터로 답변 문장을 조립해 `answer` 하나로 반환 |

- `/explain`은 LLM을 호출하지 않으므로 LLM 계열 코드(`AI_001` 성공, `AI_002`/`AI_004` 실패)를 사용하지 않고 `ANOMALY_*` 도메인을 쓴다. `AI_002`/`AI_004`는 삭제되지 않는다 — `/chat`, `/summary`, `/search`, `/report/weekly`(§3.6)가 그대로 사용한다.
- `/explain` 자체는 구현된 적이 없어(Backend Controller/Service 없음) 폐기로 인한 코드 변경은 없다.
- **함께 확정된 세부 사항**(상세는 §3.9, DATABASE_DESIGN_GUIDE.md §15.7):
  - 질문은 `question={questionKey}` 쿼리 파라미터로 지정하고, 질문 카탈로그(키+표시 문구)는 서버가 `GET /api/guardian/anomalies/questions?type=`으로 제공한다(Frontend 하드코딩 아님).
  - 응답은 질문 종류와 무관하게 `answer: string` 하나로 통일한다.
  - 성공 코드는 `ANOMALY_001`(목록)/`ANOMALY_002`(설명)/`ANOMALY_003`(카탈로그), 에러 코드는 `ANOMALY_001`(이벤트 없음, 404)/`ANOMALY_002`(잘못된 질문 키, 400)로 성공/에러가 독립된 번호 공간을 쓰며(API_Response_Rule.md §5.1), 파라미터 누락은 `COMMON_002`, 소유권 불일치는 `TARGET_002`를 재사용한다.
  - 예정 시각/체류 시작 시각은 역산하지 않고 `AnomalyEvent` 스냅샷 컬럼(`scheduled_at`, `stay_started_at`)으로 저장한다.
  - "과거 비슷한 위치" 질문의 조회 원본은 과거 `UNREGISTERED_STAY` 이벤트뿐이다.
  - Audit Log는 이번 범위에 포함하지 않는다(위치 노출 API 전체 공통 도입 로드맵).

### 7.4 알림 설정 API — 별도 리소스 + PUT + 오버레이 (확정, 2026-09-21)

§4.4 로드맵의 알림 설정 API(§3.10)를 아래처럼 확정했다.

| 결정 | 내용 | 근거 |
|---|---|---|
| 리소스 분리 | 기존 `PUT /api/guardian/care-targets/{id}`(관계 relation/alias, `TARGET_008`)에 얹지 않고 `.../notification-settings`로 분리 | relation/alias는 부작용 없는 표시 라벨, 알림 설정은 안전 관련 동작을 바꾼다. `CareTargetResponse`(목록/상세/수정 공용)를 오염시키지 않는다 |
| 조회 | 상세 응답에 넣지 않고 별도 GET | 설정 화면이 독립적으로 진입하고 목록·상세 응답에 필드를 늘리지 않는다 |
| 메서드 | PATCH가 아니라 **PUT 전체 교체** | 프로젝트의 갱신 API는 전부 PUT. `null`("승격 안 함")과 "필드 없음"을 구분해야 하는 부분 수정(PATCH)을 피한다 |
| 정지 표현 | `PAUSED`를 모드 값으로 노출하지 않고 `paused`/`pausedUntil` **오버레이** | 정지 중 분 슬라이더만 고쳐 저장했을 때 정지가 조용히 해제되는 모호함을 없앤다. DB는 §15.3 그대로 저장 |
| 재정지 | `min( max(기존 pausedUntil, now) + durationMinutes, now + 1440분 )` **누적 + 총합 상한** | 요청 1회당 1~1440분, 남은 시간은 항상 "지금부터 24시간" 이하. 더블탭 방지는 Frontend 책임(§3.10) |
| 무기한 정지 | 미지원 | 안전 서비스에서 "잊고 계속 꺼둔" 상태 방지(눌러야만 이어지고 안 누르면 24시간 안에 자동 복귀), `paused_until`이 NULL이면 자동 복귀도 안 된다 |
| 승격 분 | 1~1440, 키 누락은 `COMMON_002`, 명시적 `null`만 "승격 안 함" | 클라이언트 실수로 승격이 조용히 꺼지는 것 방지 |
| 동시성 | 행 단위 비관적 락(사용자 쓰기 + 스케줄러 자동 복귀) | DATABASE_DESIGN_GUIDE.md §15.8 |
| 적용 범위 | **이상행동 알림에만**. GeoFence 도착 알림·EMERGENCY는 영향 없음 | 화면 문구는 "이상행동 알림 멈추기"(§3.10) |
| 에러 코드 | 신규 에러 코드 없음(`TARGET_001`/`TARGET_002`/`COMMON_002` 재사용), 성공 `TARGET_011`~`TARGET_014` | API_Response_Rule.md |
| DDL 변경 | 이번 범위에서 보류(분 양수 CHECK, `@Version`) | DATABASE_DESIGN_GUIDE.md §15.5 |
