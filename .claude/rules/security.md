# Security Rules (Claude Code 자동 참조용)

프로젝트: 아이·노인 케어 위치추적 알림 시스템 (trace_care)
버전: v1.0 (작성일 2026-08-14)

> 해당파일 경로 .claude/rules/security.md
> 이 파일은 **코드를 생성/수정할 때 항상 지켜야 할 실행 규칙**만 담는다. 설계 배경과 상세 근거는 재설명하지 않고 아래 문서를 참조한다.
> - 인증/인가/WebSocket 상세 설계 → `docs/security/Security_Guide.md`
> - OWASP Top 10 항목별 점검 기준 → `docs/security/OWASP_Security_Guide.md` (경로 확인 필요 — 아래 참고)
> - 예외 처리 구조 → Exception Handling Rule 문서
> - API 응답 포맷 → API Response Rule 문서
>
> 이 문서와 위 문서의 내용이 충돌하면 위 문서(설계 원본)가 우선한다. 이 파일은 그 요약본이다.

---

## 1. 인증(Authentication) — 항상 지킬 것

- 모든 인증은 Spring Boot가 자체 발급한 JWT로만 판단한다. Google Access/ID Token을 API 인증에 재사용하지 않는다.
- JWT는 `Authorization: Bearer <token>` 헤더로만 받는다. **쿼리 파라미터, 쿠키, 요청 바디에 토큰을 담지 않는다** (URL/로그 노출 위험).
- JWT Payload에 이메일·이름·전화번호 등 개인정보를 넣지 않는다. `sub`(userId), `role`, `iat`, `exp`, `jti`, `typ`(access/refresh 구분)만 포함한다.
- Secret Key, Google Client Secret, LLM API Key는 코드/설정 파일에 하드코딩하지 않는다. 환경 변수 또는 Secret Manager로만 주입하고, 값이 포함된 파일은 반드시 `.gitignore`에 있는지 확인한다.
- 새 인증 로직을 추가할 때 `UserDetailsService`로 매 요청 DB 조회를 하지 않는다. JWT 서명 검증만으로 `Authentication`을 구성한다(DB round-trip은 로그인 시점에만).

## 2. 인가(Authorization) — 3단계를 절대 생략하지 말 것

새 API 엔드포인트를 만들 때마다 아래 3단계를 순서대로 확인한다. 하나라도 생략하면 안 된다.

1. **인증**: 로그인된 사용자인가 → Filter 단계에서 이미 처리됨, 임의로 우회하지 않는다.
2. **역할 인가**: 이 Role이 이 URL을 호출할 자격이 있는가 → `authorizeHttpRequests` 또는 `@PreAuthorize`.
3. **리소스 소유권 검증**: 요청 대상(CareTarget, Place, LocationHistory 등)이 실제로 이 요청자와 관계가 있는가 → **반드시 Service 계층에서 DB로 확인**. Role만 맞으면 통과시키지 않는다.

- 신규 API는 기본값이 "인증 필요"다. `permitAll`을 추가할 때는 반드시 이유를 코드 주석 또는 PR 설명에 남긴다.
- Controller가 파라미터로 받은 `userId`를 그대로 신뢰해 조회 조건으로 쓰지 않는다. 항상 `SecurityContext`에서 꺼낸 인증된 사용자 ID를 기준으로 조회한다.
- `/internal/**`은 `denyAll` + 네트워크 레벨 차단을 함께 건다. 둘 중 하나만 하지 않는다.
- WebSocket을 다룰 때도 REST와 동일한 3단계를 적용한다: CONNECT에서 인증, SUBSCRIBE에서 리소스 소유권 검증. 가능하면 공용 Topic보다 사용자별 개인화 큐(`convertAndSendToUser`)를 우선 사용한다.

## 3. 입력 검증 / Injection 방어

- SQL: JPA는 `@Query`에 문자열 concat 금지, MyBatis는 `#{}` 바인딩만 사용한다. `${}`가 불가피하면(정렬 컬럼 등) 화이트리스트 Enum으로 제한 후 사용한다.
- 모든 Controller의 `@RequestBody`/`@ModelAttribute`에 `@Valid`를 적용하고, GPS 좌표·반경 등 도메인 규칙은 Custom `@ConstraintValidator`로 표현한다.
- FastAPI 쪽 엔드포인트는 `Any`/raw dict로 받지 않고 Pydantic 모델을 명시한다.
- LLM(AI Care Chat)에 전달하는 사용자 입력은 시스템 프롬프트와 분리된 메시지 role로 넣는다. 문자열 결합으로 프롬프트를 조립하지 않는다. 길이 제한을 둔다.
- 파일 업로드 엔드포인트를 추가할 때는 확장자 화이트리스트, 매직바이트 검증, 크기 제한, 서버 측 파일명 재생성(UUID), 실행 권한 없는 저장 경로를 모두 적용한다. 하나라도 빠뜨리지 않는다.

## 4. 에러/예외 처리

- `catch (Exception e)`로 광범위하게 잡고 조용히 넘어가지 않는다(빈 catch 블록 금지). 예외 타입별로 구체적으로 처리한다.
- 클라이언트 응답에 스택 트레이스, SQL 원문, 내부 경로를 포함하지 않는다. 상세 원인은 서버 로그에만 남긴다.
- 예외 처리는 `GlobalExceptionHandler`(Controller/Service 계층)와 `AuthenticationEntryPoint`/`AccessDeniedHandler`(Filter 계층) 중 맞는 곳으로 위임한다. 둘을 임의로 뒤섞지 않는다.
- 안전 관련 로직(GeoFence 이탈 감지, 긴급 알림)에서 예외가 나면 "조용히 넘어가기"(fail-open)로 처리하지 않는다. 재시도 후에도 실패하면 별도 경보로 전환한다(fail-safe).

## 5. 로깅

절대 로그에 남기지 않는다:
- 비밀번호(원문/해시 모두), JWT/Refresh Token 원문, Google ID/Access Token 원문
- GPS 좌표 원문, 얼굴 인증 이미지/특징 데이터
- 이메일·이름 등 직접 식별 정보(로그에는 내부 `userId`만 사용)

반드시 남긴다: 로그인 성공/실패, JWT 검증 실패 사유, 403 발생 시 userId/role/요청 URI, `/api/admin/**` 전체 호출(별도 감사 로그).

## 6. 설정/배포

- 운영 프로파일(`application-prod.yml`)에서 `spring.jpa.show-sql=false`, `server.error.include-stacktrace=never`.
- CORS 허용 Origin은 프로덕션 도메인만 명시한다. `*`는 사용하지 않는다(`allowCredentials(true)`와 함께 쓰면 애초에 동작하지 않는다).
- JWT Stateless 구조이므로 CSRF는 비활성화하지만, 만약 쿠키 기반 인증 경로(웹 관리자 콘솔 등)가 추가되면 그 경로에 한해 CSRF를 다시 켠다.
- Actuator는 운영 환경에서 `health` 등 최소 항목만 노출한다.
- DB/Redis 포트를 애플리케이션 서버 외부에 열지 않는다(보안 그룹/Docker `expose` 사용, `ports` 직접 바인딩 지양).

## 7. 코드 리뷰 시 자가 점검 (PR 작성/리뷰 전 확인)

□ 새 Controller 메서드에 권한 검증이 있는가 (`@PreAuthorize` 또는 URL 패턴)
□ Service 계층에서 소유권 검증(WHERE 조건에 요청자 ID 포함)을 했는가
□ 응답에 예외 메시지·토큰·개인정보 원문이 섞여 나가지 않는가
□ 새로 추가한 의존성에 알려진 취약점이 없는가
□ `permitAll`을 습관적으로 붙이지 않았는가
□ 로그 출력문에 5장에서 금지한 항목이 없는가
□ 외부 서비스(FastAPI/FCM/Google API) 호출에 timeout이 설정돼 있는가
