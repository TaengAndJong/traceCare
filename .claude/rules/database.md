# Database 작업 규칙
>해당파일 경로 .claude/rules/database.md
> DB 관련 작업(엔티티/쿼리/마이그레이션) 시 반드시 준수해야 하는 실행 규칙이다.
> 스키마 설계 근거, 인덱스 전략, 파티셔닝 등 상세 설계는
> `docs/db/DATABASE_DESIGN_GUIDE.md`를 따른다.

## PK / 식별자
- 신규 테이블의 PK는 `SERIAL` 대신 `BIGSERIAL` 또는
  `GENERATED ALWAYS AS IDENTITY`를 사용한다 (INT 범위 고갈 방지).
- 외부 노출용 식별자가 필요한 경우 PK를 그대로 노출하지 않고 `public_id`(UUID 등)를
  병행한다 (IDOR 방지, 상세: `docs/security/Security_Guide.md` §4.5).

## FK (외래키)
- Master Data 간 관계(예: `GuardianTarget → User`, `Place → User`)는 물리적 FK를 유지한다.
- FK 대상 컬럼에는 반드시 별도 인덱스를 생성한다
  (PostgreSQL은 FK 생성 시 인덱스를 자동으로 만들지 않는다).
- 삭제는 Repository 표준 메서드만 사용하고, Native Query로 우회하는 삭제를 하지 않는다.
- 대용량 Time-Series 테이블(`LocationHistory` 등)에 CASCADE 삭제를 걸지 않는다 —
  동기 트랜잭션으로 대량 자식 행을 삭제하면 장시간 락이 발생한다. `User` 탈퇴 시에는
  즉시 CASCADE 삭제 대신 비동기 배치로 처리한다.

## 트랜잭션
- 기본 Isolation Level은 `READ COMMITTED`를 사용한다. `SERIALIZABLE`은 원칙적으로
  사용하지 않는다 (예외가 필요하면 해당 트랜잭션에 한해 국소적으로만 검토).
- 여러 테이블을 한 트랜잭션에서 갱신할 때는 `User → GuardianTarget → Place` 순서를
  고정한다 (Deadlock 방지).
- 트랜잭션은 짧게 유지하고, 트랜잭션 내부에서 외부 API 호출(FCM 발송, AI 서버 요청 등)을
  수행하지 않는다.
- 위치 저장(`LocationHistory` INSERT)과 GeoFence 판정/알림 발송은 같은 트랜잭션으로
  묶지 않는다 — 별도 단계(비동기 또는 후속 짧은 트랜잭션)로 분리한다.
- 하나의 트랜잭션은 하나의 Aggregate에 대한 변경으로 범위를 제한한다.

## 동시성 제어
- Optimistic Lock(`@Version`)은 저빈도 쓰기(예: `Place` 수정처럼 사용자가 직접 수정하는
  화면)에만 적용한다. 쓰기가 매우 잦은 구간(위치 적재 등)에는 사용하지 않는다.
- Pessimistic Lock(`SELECT ... FOR UPDATE`)은 원칙적으로 사용하지 않는다. 결제/구독 등
  금전적 요소가 추가되기 전까지는 도입하지 않는다.
- 중복 등록 방지는 `SELECT` 후 `INSERT` 패턴 대신 `INSERT ... ON CONFLICT DO NOTHING`
  같은 원자적 처리를 우선한다.

## 삭제 정책
- Master Data(`User`, `Place` 등)는 Soft Delete를 우선한다.
- Time-Series Data(`LocationHistory` 등)는 보관 기간 경과 시 Hard Delete 또는 익명화로
  전환한다. 보관 기간 정책은 `docs/db/DATABASE_DESIGN_GUIDE.md` §7.2를 따른다.

## 캐시 (Redis)
- 캐시 대상 데이터, 키 네이밍 규칙, TTL 정책, 무효화 전략은 전부
  `docs/backend/Cache_Strategy_Guide.md`가 원본이다. 이 파일에서 표를 다시 만들거나
  임의로 새 네이밍 패턴을 추가하지 않는다.
- Redis 관련 보안 정책(Refresh Token, JWT Blacklist)은
  `docs/security/Security_Guide.md`를 따른다 (중복 정의하지 않음).
- 새 캐시 항목이 필요하면 코드에 바로 추가하지 않고
  `Cache_Strategy_Guide.md` §3.2 표에 먼저 등록한 뒤 구현한다.

## 배치 / 대량 처리
- 대량 데이터 처리는 하나의 대형 트랜잭션으로 묶지 않고, 청크 단위(예: 1만 건)로
  나누어 커밋한다.
- 배치는 트래픽이 낮은 시간대(새벽)에 실행하는 것을 기본으로 한다.

## 정규화 / 스키마 변경
- 기본은 정규화(3NF)를 유지한다. 비정규화는 실제로 측정된 성능 문제가 확인된 조회
  경로에 한해서만 적용하고, 처음부터 예측만으로 비정규화하지 않는다.
- 이력성 데이터(`VisitHistory` 등)의 스냅샷 컬럼(예: `place_name`)은 비정규화가 아니라
  "이력 불변성 보장" 목적이므로 임의로 정규화(참조 방식)로 되돌리지 않는다.
- 스키마 변경(테이블/컬럼 추가·수정) 시 `docs/db/tracecare_schema_ddl_<날짜>_<버전>.sql`과
  `docs/db/DATABASE_DESIGN_GUIDE.md`를 함께 갱신한다. DB에 적용한 DDL과 문서가
  달라지면 어느 쪽이 최신 기준인지 사용자에게 확인한다.

## 로컬 개발 환경
- DB(PostgreSQL + pgvector), Redis는 `docker-compose.yml`을 통해 로컬에서 실행한다.
- 컨테이너 기동: `docker compose up -d`
- 접속 포트: 호스트 `5433` → 컨테이너 `5432` (호스트의 기본 PostgreSQL 포트 5432와
  충돌 방지를 위해 변경됨. 로컬 클라이언트로 접속 시 `5433` 사용)
- 준비 상태 확인: `docker exec tracecare-db pg_isready -U tracecare`

### 알려진 함정 (재발 방지)
- **볼륨 마운트 경로**: 사용 중인 `postgres:18` 계열 이미지는 데이터 디렉터리로
  `/var/lib/postgresql/data`가 아니라 `/var/lib/postgresql`을 사용한다. 경로를
  잘못 지정하면 컨테이너가 재시작 루프에 빠진다. `docker-compose.yml`의 볼륨 경로를
  임의로 되돌리지 않는다.
- **DDL 적용 시 Windows(PowerShell) 인코딩 문제**: `Get-Content "*.sql" -Raw | docker exec -i
  tracecare-db psql ...` 처럼 PowerShell에서 SQL 파일을 파이프로 넘기면 인코딩이 깨져
  중간에 구문 오류가 발생할 수 있다 (부분 적용된 상태로 실패).
  **DDL은 반드시 아래 절차로 적용한다:**
    1. `docker cp docs/db/<DDL 파일명> tracecare-db:/tmp/schema.sql`
    2. (재적용인 경우, 기존 스키마 초기화)
       `docker exec tracecare-db psql -U tracecare -d tracecare -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"`
    3. `docker exec tracecare-db psql -U tracecare -d tracecare -f /tmp/schema.sql`
    4. 검증: `docker exec tracecare-db psql -U tracecare -d tracecare -c "\dt" -c "\dx"`
- 스키마 변경이 필요할 때 `docker-compose.yml`이나 실행 중인 컨테이너 설정을 임의로
  변경하지 않고, 변경이 필요하면 먼저 사용자에게 확인한다.
- DDL 파일은 `docs/db/tracecare_schema_ddl_<날짜>_<버전>.sql` 형식으로 버전을 남기고,
  적용 후 `docs/db/DATABASE_DESIGN_GUIDE.md`와 내용이 일치하는지 확인한다.
