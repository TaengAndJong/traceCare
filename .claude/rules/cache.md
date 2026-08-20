# 캐시(Redis) 작업 규칙
>해당파일 경로 .claude/rules/cache.md
> Redis 캐시(키 설계, TTL, 무효화) 관련 작업 시 반드시 준수해야 하는 실행 규칙이다.
> 설계 배경과 상세 근거, 전체 캐시 대상 표, TTL 정책 근거는
> `docs/backend/Cache_Strategy_Guide.md`를 따른다.
> - Refresh Token/JWT Blacklist 보안 설계 → `docs/security/Security_Guide.md`
> - Redis 장애 시 예외 처리 → `docs/backend/Exception_Handling_Rule.md` §10.4
> - 테이블/컬럼 설계, DB-캐시 정합성 근거 → `docs/db/DATABASE_DESIGN_GUIDE.md`
>
> 이 문서와 위 문서의 내용이 충돌하면 위 문서(설계 원본)가 우선한다. 이 파일은 그 요약본이다.

## 기본 패턴

- 새 캐시 항목은 별도 이유가 없는 한 **Cache Aside**(조회 시 캐시 확인 → 없으면 DB 조회 → 캐시 적재)로 구현한다.
- Write-Through(쓰기 시 캐시도 즉시 갱신/삭제)는 Place/GeoFence처럼 Cache_Strategy_Guide.md가 명시적으로 지정한 항목에만 적용한다. 임의로 다른 항목에 확대 적용하지 않는다.
- 캐시 키 문자열을 Service 코드에서 직접 조립하지 않는다. `CacheKeyGenerator` 같은 공용 유틸을 거친다.

## 새 캐시 항목 추가 절차

1. `Cache_Strategy_Guide.md` §3.2 표에 먼저 등록한다: 캐시 키 패턴, TTL, 무효화 전략, Source of Truth(Redis인지 DB인지).
2. 표 등록 없이 코드에만 캐시 로직을 추가하지 않는다 — `database.md`의 "새 에러 코드는 문서에 먼저" 원칙과 동일하다.
3. 키 네이밍은 `{도메인}:{용도}:{식별자}` 형식을 따른다. 식별자는 가능하면 내부 PK가 아닌 `public_id`(UUID)를 쓴다(API Response Rule §1.5 식별자 노출 정책과 통일).

## TTL

- TTL 값은 근거 없이 정하지 않는다. Cache_Strategy_Guide.md §4의 3가지 기준(실시간성 요구, 원본 변경 빈도, 재계산/재조회 비용) 중 어디에 해당하는지 설명할 수 있어야 한다.
- 보안 성격 데이터(Refresh Token, JWT Blacklist)의 TTL은 이 기준이 아니라 토큰 만료 정책(Security_Guide.md §5.4)을 그대로 따른다. 임의의 값을 넣지 않는다.

## Source of Truth 구분 — 장애 처리 갈림길

- **Redis가 Source of Truth인 데이터**(최신 위치, FCM Token, Refresh Token, JWT Blacklist): Redis 장애는 단순 캐시 미스가 아니라 실제 데이터 유실이다. 특히 Refresh Token/JWT Blacklist는 폴백 없이 명시적으로 503 실패 처리한다 — 인증 상태를 임의로 "성공"으로 간주하지 않는다.
- **PostgreSQL이 Source of Truth인 데이터**(Place, CareTarget 정보 등): Redis 장애 시 예외를 던지지 않고 DB 원본 조회로 자동 폴백한다.
- 새 캐시 항목을 추가할 때 이 둘 중 어느 쪽인지 반드시 판단하고 Cache_Strategy_Guide.md §6 표에 기록한다.

## 무효화

- Place/GeoFence 변경 시 관련 캐시(`place:list:{guardianId}`, `geofence:{careTargetId}`)를 같은 트랜잭션 흐름 안에서 즉시 삭제한다. 다음 요청에서 Cache Aside로 다시 채워지게 한다.
- AI 예측/LLM 응답/외부 API 결과처럼 실시간성이 덜 중요한 항목은 명시적 무효화 로직을 만들지 않고 TTL 자연 만료에 맡긴다. 불필요하게 무효화 코드를 추가하지 않는다.
- 최신 위치/FCM Token은 "무효화"가 아니라 매번 최신 값으로 덮어쓴다.

## PR 작성/리뷰 전 자가 점검

□ 새 캐시 항목이 Cache_Strategy_Guide.md §3.2 표에 먼저 등록됐는가
□ 캐시 키가 `CacheKeyGenerator`를 통해 만들어지는가 (문자열 직접 조립 금지)
□ TTL 값의 근거를 설명할 수 있는가
□ Redis 장애 시 이 데이터가 DB 폴백 대상인지, 명시적 실패 처리 대상인지 구분했는가
□ Refresh Token/JWT Blacklist 관련 캐시 로직을 Security_Guide.md 기준으로 구현했는가 (이 문서에서 재정의하지 않음)
□ Write-Through가 필요 없는 항목에 불필요하게 즉시 무효화 로직을 추가하지 않았는가
