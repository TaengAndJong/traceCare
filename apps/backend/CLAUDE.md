# CLAUDE.md (Backend — Spring Boot)

> 이 파일에는 Backend 서비스에서 항상 알아야 하는 요약 정보와, 상세 내용을 확인해야 할 docs/rules 참조 경로를 작성한다.

## Project overview
- 역할: REST API, WebSocket(실시간 위치), 인증/인가, GeoFence 판정, 알림 발송, AI 서버 연동
- 현재 단계: API 설계 착수 전 — Security_Guide.md, API_Response_Rule.md 골격 우선 확정 필요
  (Root CLAUDE.md 및 아래 Conventions 참조)
- 전체 프로젝트 맥락은 Root `CLAUDE.md` 참조

## Directory map
- (실제 패키지 구조 미확정) [추가 필요]
  예: `com.tracecare.{auth,location,geofence,notification,chat}` 도메인별 분리 제안 — 확정되면 갱신

## Commands

| 구분 | 명령어 |
|---|---|
| 빌드 | TBD (Gradle/Maven 확인 필요) |
| 실행 | TBD |
| 테스트 | TBD |

## Conventions
- 인증/인가 상세: `../../docs/security/Security_Guide.md` [예정] 담당
  (OAuth2(Google) 인증 → 자체 JWT Access/Refresh 발급, Guardian/CareTarget Role 기반 권한)
- API 응답 형식: `../../docs/api/API_Response_Rule.md` [예정] 담당
- 예외 처리 구조: `../../docs/backend/Exception_Handling_Rule.md` [예정] 담당
- DB 스키마·인덱스·트랜잭션·캐시 전략: `../../docs/db/DATABASE_DESIGN_GUIDE.md` 담당 (완료됨,
  아래는 그중 API 설계 시 반드시 지켜야 할 핵심만 요약 발췌)
  - 위치 저장(LocationHistory INSERT)과 GeoFence 판정/알림 발송은 같은 트랜잭션으로 묶지 않는다
  - 기본 격리 수준 `READ COMMITTED`, `SERIALIZABLE` 원칙적으로 미사용
  - 여러 테이블 갱신 시 `User → GuardianTarget → Place` 순서 고정 (Deadlock 방지)
  - 캐시 키: `location:latest:{userId}`, `place:list:{guardianId}`,
    `prediction:{userId}:{date}`, `chat:cache:{questionHash}`
- SQL Injection 방어: MyBatis `#{}` 바인딩만 사용(`${}` 금지), JPQL 파라미터 바인딩 필수
- 삭제는 Repository 표준 메서드만 사용, Native Query 우회 삭제 금지

## Quirks
- Hugging Face AI 예측 서버가 유휴 시 sleep 상태가 될 수 있어, 예측 요청 전 Redis 캐시를
  먼저 조회하고 없을 때만 AI 서버를 호출한다 — 단순 성능 캐시가 아니라 외부 서버 가용성 대응 목적.
- `NotificationHistory.status`는 `EMERGENCY`/`GEOFENCE_EXIT` 알림의 경우 FCM 발송 실패 시에도
  감사 목적상 행이 반드시 남아야 한다 — 실패를 삼키지 말고 `status='FAILED'`로 명시 기록.
- FK 대상 컬럼(특히 `LocationHistory.user_id`)에는 반드시 별도 인덱스가 있다
  (`../../docs/db/DATABASE_DESIGN_GUIDE.md` §5 기준, 이미 반영됨 — 재설계 시 누락 여부만 확인)
