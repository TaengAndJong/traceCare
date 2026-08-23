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
| POST | `/api/guardian/ai/explain` | 이상행동 설명 |
| POST | `/api/guardian/ai/search` | 자연어 이동기록 검색 |

성공 코드: `AI_001` · 주요 실패 코드: `AI_002`(500, LLM API 호출 실패), `AI_004`(429, LLM 호출 한도 초과)

### 3.7 알림

| Method | URI | 설명 |
|---|---|---|
| GET | `/api/guardian/notifications` | 알림 목록 |
| PUT | `/api/guardian/notifications/{id}/read` | 읽음 처리 |
| GET | `/api/guardian/notifications/history` | 알림 이력 전체 |

`{id}` = NotificationHistory 내부 PK(시계열 데이터, `public_id` 정책 미적용).

성공 코드: `NOTI_001`(조회) / `NOTI_002`(읽음 처리) · 주요 실패 코드: `NOTI_001`(404)

### 3.8 내 정보

| Method | URI | 설명 |
|---|---|---|
| GET | `/api/guardian/profile` | 내 정보 조회 |
| PUT | `/api/guardian/profile` | 내 정보 수정 |
| PUT | `/api/guardian/profile/image` | 프로필 이미지 변경 |

성공 코드: `USER_001`(조회) / `USER_002`(수정)

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

성공 코드: `NOTI_001` · 주요 실패 코드: `EMERGENCY_001`(403, Guardian 호출), `EMERGENCY_002`(400, 등록된 보호자 연락처 없음), `EMERGENCY_003`(500, 연동 자체 실패)

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
| GET | `/api/care-target/notifications` | 알림 조회 |
| PUT | `/api/care-target/notifications/{id}/read` | 읽음 처리 |
| GET | `/api/care-target/profile` | 내 정보 조회 |
| PUT | `/api/care-target/profile` | 내 정보 수정 |

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
