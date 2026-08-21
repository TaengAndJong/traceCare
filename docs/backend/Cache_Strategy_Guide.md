# Cache Strategy Guide
>해당파일 경로 docs/backend/Cache_Strategy_Guide.md

프로젝트: 아이·노인 케어 위치추적 알림 시스템 (GIS)
문서 위치: `docs/backend/Cache_Strategy_Guide.md`
담당 서버: Spring Boot Backend (Redis 연동)
버전: v1.0

> 이 문서는 **Redis를 무엇을, 어떤 키로, 얼마나 캐싱할 것인가**를 담당하는 유일한 원본이다.
> Refresh Token/JWT Blacklist의 보안 설계 이유는 **Security_Guide.md** 5장, Redis 장애 시 예외 처리는 **Exception Handling Rule** 10.4절, 테이블/컬럼 설계는 **DATABASE_DESIGN_GUIDE.md**를 따른다. 본 문서는 이 문서들이 이미 정의한 개별 캐시 항목들을 **하나의 표준 키 체계·TTL 정책·무효화 전략**으로 통합 관리하며, 각 문서에서 흩어져 있던 캐시 관련 서술은 이 문서로 일원화한다(중복 정의하지 않음).

---

## 목차

1. 문서 목적 및 범위
2. Cache Architecture Overview
3. Cache Key 설계 표준
4. TTL 정책
5. Cache Invalidation 전략
6. 데이터 정합성 관리 (Source of Truth 구분)
7. 장애 처리
8. Spring Boot 구현 기준
9. 모니터링
10. 개발 체크리스트

---

## 1. 문서 목적 및 범위

### 1.1 목적

이 프로젝트는 위치 조회, 장소 목록, AI 예측/LLM 응답, 외부 API 조회 결과, 인증 토큰까지 성격이 전혀 다른 데이터를 전부 Redis 하나로 캐싱한다. 캐시 항목이 늘어날수록 "이 데이터는 왜 캐싱하는지, 키는 어떻게 짓는지, TTL은 왜 이 값인지"가 코드 여기저기에 흩어지기 쉽다. 이 문서는 그걸 한 곳에 모아, 새 캐시 항목을 추가할 때마다 참고할 단일 기준을 제공한다.

### 1.2 작성 범위 / 제외 범위

**작성 범위**: Redis 캐시 대상 데이터 목록, 키 네이밍 규칙, TTL 정책, 무효화 전략, 캐시-DB 정합성 관리, Cache Aside 구현 패턴.

**작성 제외 (타 문서 담당)**

| 항목 | 담당 문서 |
|---|---|
| Refresh Token/JWT Blacklist의 보안 설계 이유, Token Rotation 전략 | Security_Guide.md 5장 |
| Redis 장애 시 예외 처리(캐시성 vs 보안성 데이터 분기, 503 처리) | Exception Handling Rule 10.4절 |
| PostgreSQL 테이블/컬럼 설계, Redis-DB 간 소스 오브 트루스 원칙의 근거 | DATABASE_DESIGN_GUIDE.md 10.1절 |
| 캐시 미스/외부 API 실패 시 Fallback 예외 처리 상세 | Exception Handling Rule 9.2절 |

---

## 2. Cache Architecture Overview

### 2.1 왜 Redis인가

메모리 기반 Key-Value 저장소인 Redis는 디스크 기반 PostgreSQL보다 훨씬 빠른 조회 성능을 제공한다. 이 프로젝트에서 Redis를 쓰는 이유는 두 가지로 나뉜다.

- **성능 가속**: 자주 조회되는 데이터(최신 위치, 장소 목록, AI 예측 결과)를 캐싱해 DB 부하를 줄이고 응답 속도를 높인다.
- **상태 저장(세션 성격)**: Refresh Token, JWT Blacklist, FCM Token처럼 Stateless JWT 구조에서 서버가 유일하게 상태를 들고 있어야 하는 데이터를 저장한다.

두 목적은 장애 시 대응 방식이 다르므로(6장 참고) 구분해서 관리한다.

### 2.2 기본 패턴: Cache Aside

```
조회 요청
   ↓
Redis에 캐시 존재? ──있음──▶ 캐시 값 반환
   ↓ 없음
PostgreSQL 조회
   ↓
Redis에 캐시 적재(TTL 설정)
   ↓
값 반환
```

이 프로젝트의 **기본 캐시 패턴은 Cache Aside**다. 신규 캐시 항목을 추가할 때도 별도 이유가 없다면 이 패턴을 따른다. Write-Through(쓰기 시점에 캐시도 함께 갱신)는 5장에서 정의한 특정 항목(Place)에 한해 예외적으로 적용한다.

### 2.3 전체 구성

```
Flutter App
   ↓
Spring Boot Backend
   ↓
┌──────────────┬──────────────┐
↓              ↓
Redis          PostgreSQL
(3장 캐시 대상)  (원본 데이터)
```

포트폴리오 단계에서는 Redis 단일 인스턴스(EC2 1대, Docker Compose)로 구성하고, 운영 확장 시 Redis Cluster로 전환하는 것을 목표로 한다(DATABASE_DESIGN_GUIDE.md 1.2절과 동일한 확장 방향).

---

## 3. Cache Key 설계 표준

### 3.1 네이밍 규칙

```
{도메인}:{용도}:{식별자}
```

- 도메인은 소문자 스네이크/단일 단어(`location`, `place`, `user`, `prediction`, `chat`, `places`, `refresh`, `blacklist`, `fcm`, `geofence`)
- 식별자는 `public_id`(UUID)를 우선 사용한다(API Response Rule §1.5의 식별자 노출 정책과 동일한 원칙 — 내부 PK를 캐시 키에 그대로 노출하지 않는다). 단, Redis 키는 클라이언트에 노출되지 않으므로 내부 식별자를 써도 보안상 문제는 없다 — 다만 코드베이스 전체에서 "외부 노출 식별자는 UUID"라는 원칙과 일관성을 맞추기 위해 가능하면 통일한다.
- 새 캐시 항목 추가 시 반드시 이 표(3.2절)에 먼저 등록한다.

### 3.2 전체 캐시 대상 표 (단일 원본)

| 데이터 | Cache Key | TTL | 무효화 전략 | Source of Truth |
|---|---|---|---|---|
| CareTarget 최신 위치 | `location:latest:{careTargetId}` | TTL 없음(계속 덮어씀) 또는 긴 TTL(1일) | 새 위치 수신 시 덮어쓰기 | **Redis** (DB는 이력 보관용) |
| Place/GeoFence 목록 | `place:list:{guardianId}` | 5~10분 | Write-Through(등록/수정/삭제 시 즉시 삭제) | PostgreSQL |
| CareTarget 기본 정보 | `user:info:{userId}` | 10~30분 | TTL 자연 만료 | PostgreSQL |
| AI 방문 예측 결과 | `prediction:{careTargetId}:{date}` | 24시간(하루 단위 예측) | TTL 자연 만료 | PostgreSQL(PredictionHistory) |
| AI 케어 비서 LLM 응답 | `chat:cache:{questionHash}` | 1~7일(개인화 아닌 일반 질의 한정) | TTL 자연 만료 | 캐시 전용(재생성 가능) |
| Google Places API 조회 결과 | `places:external:{placeQueryHash}` | 7일 이상 | TTL 자연 만료 | 캐시 전용(외부 데이터) |
| GeoFence 정보 | `geofence:{careTargetId}` | Place와 동일(5~10분) | Place 변경 시 함께 무효화 | PostgreSQL |
| FCM Token | `fcm:token:{userId}` | 없음(로그인/토큰 갱신 시 덮어씀) | 신규 발급 시 덮어쓰기 | **Redis** (최신 값만 필요) |
| Refresh Token | `refresh:{userId}` | Refresh Token 만료 시간과 동일 | Rotation/로그아웃 시 삭제 | **Redis** (상세: Security_Guide.md 5.5) |
| JWT Blacklist | `blacklist:{jti}` | 해당 Access Token의 남은 만료 시간 | TTL 자연 만료 | **Redis** (상세: Security_Guide.md 5.6~5.7) |
| Guardian 초대 토큰 | `invite:token:{token}` | 10분 | CareTarget 승인/거절 시 즉시 삭제, 입력 실패 5회 초과 시 즉시 삭제 | **Redis** (DB에 별도 테이블 없음, DATABASE_DESIGN_GUIDE.md §3.2) |
| Guardian 초대 대기 목록(역인덱스) | `invite:pending:{careTargetId}` (Hash, field=guardianId, value=token) | 없음(개별 field는 `invite:token:{token}` 만료 여부로 조회 시점에 지연 무효화) | 승인/거절 시 해당 field 삭제 | **Redis** (`invite:token:{token}`의 보조 색인, CareTarget이 대기 중인 요청 목록을 조회하기 위한 용도) |
| 초대 코드 생성 Rate Limit 카운터 | `invite:count:{careTargetId}` | 당일 자정까지 | TTL 자연 만료 | **Redis** |
| 초대 코드 입력 실패 카운터 | `invite:fail:{token}` | 10분(원본 토큰과 동일 수명) | 5회 도달 시 원본 토큰과 함께 즉시 삭제 | **Redis** |

> `location:latest:{careTargetId}`처럼 API_Response_Rule.md 예시에서 `careTargetId`를 `public_id`(UUID)로 쓰기로 한 정책과 캐시 키 표기를 일치시켰다.
> 초대 관련 4개 키는 `domain/guardian` Phase 1 구현 시 추가됐다. `invite:pending:{careTargetId}`는 DATABASE_DESIGN_GUIDE.md §3.2가 명시한 `invite:token:{token}` 단일 키만으로는 CareTarget이 "나에게 걸린 대기 요청 목록"을 조회할 방법이 없어(토큰 값을 모르므로 직접 조회 불가) 보조 색인으로 추가한 것이며, Source of Truth는 여전히 `invite:token:{token}`이다 — field별 TTL을 걸지 않고 조회/승인/거절 시점에 원본 토큰 존재 여부로 유효성을 재확인(지연 무효화)한다.

---

## 4. TTL 정책

TTL 값을 정할 때는 아래 3가지 기준으로 판단하고, 근거 없이 임의로 값을 정하지 않는다.

| 기준 | 설명 | 적용 예 |
|---|---|---|
| 데이터의 실시간성 요구 수준 | 실시간성이 중요할수록 TTL을 짧게 하거나 없앤다(대신 덮어쓰기로 최신화) | 최신 위치(TTL 없음), FCM Token(TTL 없음) |
| 원본 데이터 변경 빈도 | 자주 안 바뀌는 데이터는 TTL을 길게 잡아도 무방 | Google Places 결과(7일 이상), 장소 목록(5~10분) |
| 재계산/재조회 비용 | 비용이 큰 연산 결과는 TTL을 길게 잡아 반복 호출을 줄인다 | AI 예측(24시간), LLM 응답(1~7일) — AI 서버/LLM API 호출 비용 절감이 목적 |

보안 성격 데이터(Refresh Token, JWT Blacklist)의 TTL은 이 기준이 아니라 **토큰 자체의 만료 정책**을 그대로 따른다(Security_Guide.md 5.4절).

---

## 5. Cache Invalidation 전략

| 전략 | 적용 대상 | 설명 |
|---|---|---|
| Write-Through 성격 | Place/GeoFence | 등록/수정/삭제 시 DB 반영과 동시에 `place:list:{guardianId}`, `geofence:{careTargetId}` 캐시를 즉시 삭제한다. 다음 조회 시 Cache Aside 패턴으로 새로 채워진다. |
| TTL 기반 자연 만료 | AI 예측, LLM 응답, Google Places 결과 | 실시간성이 상대적으로 덜 중요하므로 명시적 무효화 로직을 만들지 않고 TTL 만료에 의존한다. |
| 덮어쓰기(Overwrite) | 최신 위치, FCM Token | "무효화"가 아니라 매번 최신 값으로 교체한다. 이전 값을 굳이 삭제할 필요가 없다. |
| 즉시 삭제(Rotation/Logout) | Refresh Token, JWT Blacklist | 사용 즉시 삭제 후 재발급(Refresh Token Rotation), 로그아웃 시 즉시 삭제(Security_Guide.md 5.5~5.6) |

새 캐시 항목을 추가할 때 이 4가지 중 어디에도 안 맞으면, 임의로 새 전략을 만들지 않고 이 표에 먼저 근거를 추가한다.

---

## 6. 데이터 정합성 관리 (Source of Truth 구분)

캐시 항목마다 "진실이 어디에 있는지"가 다르다. 이 구분을 헷갈리면 장애 대응 방식(7장)도 잘못 설계된다.

| 유형 | 설명 | 해당 데이터 | 장애 시 영향 |
|---|---|---|---|
| Redis가 Source of Truth | DB는 이력 보관용이고, "지금 값"은 Redis에만 있다 | 최신 위치, FCM Token, Refresh Token, JWT Blacklist | Redis 장애 시 이 데이터의 "현재 상태"를 잃는다 — 단순 캐시 미스가 아니라 실제 데이터 유실이므로 7장 원칙에 따라 다르게 처리한다 |
| PostgreSQL이 Source of Truth | Redis는 단순 가속용, 캐시가 사라져도 DB에서 다시 채우면 된다 | Place 목록, CareTarget 정보, GeoFence | Redis 장애 시 DB로 폴백하면 그만이며 데이터 유실이 아니다 |
| 캐시 전용(재생성 가능) | 원본이 없거나, 있어도 재계산 비용을 아끼는 목적뿐 | AI 예측, LLM 응답, Google Places 결과 | 캐시가 사라지면 다시 계산/호출하면 되므로 정합성 리스크가 가장 낮다 |

Eventual Consistency(캐시와 DB가 짧은 시간 다를 수 있음)를 감수할 수 있는 데이터(장소 목록 등)와, 감수할 수 없는 데이터(인증 관련 Blacklist)를 구분해 후자는 반드시 즉시 무효화 전략(5장)을 쓴다.

---

## 7. 장애 처리

Redis 장애 시의 구체적인 예외 처리 코드/HTTP Status는 **Exception Handling Rule 10.4절**을 그대로 따른다. 이 절에서는 6장의 Source of Truth 구분에 따라 "어떤 원칙으로 나뉘는지"만 요약한다.

| Redis 장애 시 | 처리 원칙 |
|---|---|
| PostgreSQL이 Source of Truth인 캐시(Place, CareTarget 정보 등) | 예외를 던지지 않고 DB 원본 조회로 자동 폴백한다 |
| 캐시 전용 데이터(AI 예측, LLM 응답, Google Places) | 폴백 대상이 재계산/재호출이므로 마찬가지로 예외 없이 원본 로직 재실행 |
| Redis가 Source of Truth인 세션/보안 데이터(Refresh Token, JWT Blacklist) | 폴백이 불가능하므로 명시적으로 실패 처리(503)한다. 인증 상태를 임의로 "성공"으로 간주하지 않는다(보안 원칙 우선) |

---

## 8. Spring Boot 구현 기준

### 8.1 패키지 구조 (제안)

```
com.tracecare.backend
 └─ common
     └─ cache
         ├─ CacheKeyGenerator.java     # 3.1절 네이밍 규칙을 코드로 강제
         ├─ CacheKeys.java             # 3.2절 표의 prefix 상수 모음 ("location:latest:", "place:list:" 등)
         └─ RedisConfig.java           # RedisTemplate, Serializer, TTL 기본값 설정
```

### 8.2 CacheKeyGenerator 예시

키 문자열을 각 Service에서 직접 조립하지 않고 공용 유틸을 거치게 해, 3.1절 네이밍 규칙에서 벗어난 키가 생기지 않도록 한다.

```java
@Component
public class CacheKeyGenerator {
    public String locationLatest(String careTargetId) {
        return "location:latest:" + careTargetId;
    }
    public String placeList(String guardianId) {
        return "place:list:" + guardianId;
    }
    // ... 3.2절 표의 각 키 패턴에 대응하는 메서드 추가
}
```

### 8.3 Cache Aside 구현 예시

```java
public LocationResponse getLatestLocation(String careTargetId) {
    String key = cacheKeyGenerator.locationLatest(careTargetId);
    LocationResponse cached = redisTemplate.opsForValue().get(key);
    if (cached != null) {
        return cached;
    }
    LocationResponse fromDb = locationRepository.findLatestByCareTargetId(careTargetId)
        .orElseThrow(() -> new LocationNotFoundException(ErrorCode.LOCATION_002));
    redisTemplate.opsForValue().set(key, fromDb, Duration.ofDays(1));
    return fromDb;
}
```

### 8.4 Write-Through 구현 예시 (Place)

```java
@Transactional
public void updatePlace(String placeId, PlaceUpdateRequest request) {
    Place place = placeRepository.findByPublicId(placeId)
        .orElseThrow(() -> new PlaceNotFoundException(ErrorCode.PLACE_001));
    place.update(request);
    redisTemplate.delete(cacheKeyGenerator.placeList(place.getGuardianId()));
    // GeoFence 캐시도 함께 무효화 (5장 표)
    redisTemplate.delete(cacheKeyGenerator.geofence(place.getCareTargetId()));
}
```

---

## 9. 모니터링

| 항목 | 기준 | 대응 |
|---|---|---|
| 메모리 사용률 | Redis `maxmemory`의 70% 지속 시 경고 | TTL 정책 재검토, `maxmemory-policy`(예: `allkeys-lru`) 확인 |
| Cache Hit Rate | 항목별(prefix 기준) 히트율이 지속적으로 낮으면 | TTL이 너무 짧거나, 애초에 캐싱 실익이 없는 데이터일 수 있으므로 재검토 |
| Blacklist/Refresh Token 키 개수 | 비정상적으로 급증 | 대량 로그아웃/토큰 탈취 대응(강제 무효화) 상황인지 확인, Security_Guide.md 10장 로깅과 연계 |
| Redis 연결 실패율 | 커넥션 풀 고갈, Timeout 급증 | 7장 장애 처리 원칙이 실제로 발동하는지 확인, Exception Handling Rule 10.4절 |

---

## 10. 개발 체크리스트

- [ ] 새 캐시 항목을 추가하기 전에 3.2절 표에 먼저 등록했는가 (Key 패턴, TTL, 무효화 전략, Source of Truth)
- [ ] 캐시 키를 Service 코드에서 직접 문자열로 조립하지 않고 `CacheKeyGenerator`를 거치는가
- [ ] Master Data 성격 캐시(Place 등)는 Cache Aside가 기본이고, Write-Through가 필요한 경우만 예외 적용했는가
- [ ] Redis가 Source of Truth인 데이터(최신 위치, Refresh Token 등)와 단순 가속용 캐시를 구분해서 장애 처리를 다르게 했는가(7장)
- [ ] TTL 값에 대한 근거(4장 3가지 기준 중 하나)를 설명할 수 있는가, 아니면 임의로 정한 값인가
- [ ] Refresh Token/JWT Blacklist 관련 캐시 로직을 이 문서가 아니라 Security_Guide.md 기준으로 구현했는가(중복 재정의 금지)
