# CLAUDE.md (Frontend — Flutter)

> 이 파일에는 Frontend 서비스에서 항상 알아야 하는 요약 정보와, 상세 내용을 확인해야 할 docs/rules 참조 경로를 작성한다.

## Project overview
- 역할: Guardian(보호자)/CareTarget(보호대상자) 앱, 실시간 지도 표시, WebSocket 기반 위치 갱신
- 전체 프로젝트 맥락은 Root `CLAUDE.md` 참조

## Directory map
- (실제 구조 미확정) [추가 필요] — Guardian용/CareTarget용 화면 분리 여부, 상태관리 패턴 확정 후 작성

## Commands

| 구분 | 명령어 |
|---|---|
| 실행 | TBD (`flutter run` 추정) |
| 빌드 | TBD |
| 테스트 | TBD |

## Conventions
- Backend API 연동 시 공통 응답 형식은 `../../docs/api/API_Response_Rule.md` [예정]을 따른다
  (Frontend에서 재정의하지 않음)
- 지도: Google Maps SDK, 실시간 위치는 WebSocket으로 수신

## Quirks
- (아직 특이사항 없음, 개발 진행하며 추가)
