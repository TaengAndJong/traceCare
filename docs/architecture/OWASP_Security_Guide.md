# OWASP Top 10 Security Analysis

프로젝트: 아이·노인 케어 위치추적 알림 시스템 (GIS)
문서 위치: `docs/backend/OWASP_Security_Guide.md`
기준: OWASP Top 10:2025 (2026년 1월 정식 발표본, https://owasp.org/Top10/2025/)
용도: 구현 및 코드 리뷰 시 참고하는 보안 점검 기준 문서
버전: v1.0 (작성일 2026-08-06)

> 이 문서는 **OWASP Top 10 항목별 취약점 진단·대응·점검 체크리스트**를 담당한다.
> 인증/인가 아키텍처의 상세 설계(Filter Chain, JWT 정책, RBAC 구조)는 **Security_Guide.md**, 예외 처리 구현 상세(GlobalExceptionHandler, Custom Exception 계층)는 **Exception Handling Rule** 문서를 따른다. 본 문서는 두 문서를 전제로 "OWASP 관점에서 무엇을 점검해야 하는가"에 집중하며, 이미 정의된 설계를 다시 상세히 설명하지 않고 참조로 연결한다.

### 2021 → 2025 개정 사항 요약

OWASP Top 10은 2026년 1월 `2025` 버전으로 개정되었다. 기존 2021 버전 자료·교육을 참고해온 팀원을 위해 매핑 관계를 아래에 정리한다. 가장 큰 변화는 SSRF가 별도 항목에서 Broken Access Control로 흡수됐고, Vulnerable Components가 Software Supply Chain Failures로 확장됐으며, Mishandling of Exceptional Conditions가 신설되어 예외 처리 부실 자체가 독립 항목이 됐다는 점이다.

| 2025 순위 | 2025 항목 | 2021 대응 항목 | 비고 |
|---|---|---|---|
| A01:2025 | Broken Access Control | A01:2021 Broken Access Control | SSRF(A10:2021) 흡수 |
| A02:2025 | Security Misconfiguration | A05:2021 Security Misconfiguration | 순위 5→2 상승 |
| A03:2025 | Software Supply Chain Failures | A06:2021 Vulnerable and Outdated Components | 범위 확장(빌드/배포 파이프라인 포함), 신규 성격 |
| A04:2025 | Cryptographic Failures | A02:2021 Cryptographic Failures | 순위 2→4 하락 |
| A05:2025 | Injection | A03:2021 Injection | 순위 3→5 하락 |
| A06:2025 | Insecure Design | A04:2021 Insecure Design | 순위 4→6 하락 |
| A07:2025 | Authentication Failures | A07:2021 Identification and Authentication Failures | 명칭 일부 변경 |
| A08:2025 | Software or Data Integrity Failures | A08:2021 Software and Data Integrity Failures | 유지 |
| A09:2025 | Security Logging & Alerting Failures | A09:2021 Security Logging and Monitoring Failures | Alerting 강조 |
| A10:2025 | Mishandling of Exceptional Conditions | (신설) | Exception Handling Rule 문서와 직접 연계 |

---

## 목차

1. A01:2025 Broken Access Control
2. A02:2025 Security Misconfiguration
3. A03:2025 Software Supply Chain Failures
4. A04:2025 Cryptographic Failures
5. A05:2025 Injection
6. A06:2025 Insecure Design
7. A07:2025 Authentication Failures
8. A08:2025 Software or Data Integrity Failures
9. A09:2025 Security Logging & Alerting Failures
10. A10:2025 Mishandling of Exceptional Conditions
11. 개인정보 보호 정책
12. API Security Checklist
13. Code Review Security Checklist

---

## 1. A01:2025 Broken Access Control

### 1.1 취약점 설명

- **발생 원인**: 인증(누구인지)과 인가(무엇을 할 수 있는지)를 혼동하거나, 인가 검증을 클라이언트단(UI 메뉴 숨김)에만 의존하고 서버가 실제로 권한을 검증하지 않을 때 발생한다. 2025년 개정판부터는 SSRF(서버가 신뢰 없이 사용자 입력 URL을 그대로 요청하는 결함)도 "서버가 접근해서는 안 될 리소스에 접근했다"는 동일한 근본 원인으로 묶여 이 카테고리에 포함된다.
- **공격자가 악용하는 방식**: URL/경로의 리소스 ID(`/api/guardian/care-targets/{id}`)를 다른 값으로 바꿔 접근(IDOR), Role 검사가 없는 엔드포인트를 직접 호출, 관리자 전용 API를 URL 추측으로 접근, 서버가 대신 요청하는 기능(장소 검색, AI 예측 요청 등)에 악성 URL/내부 주소를 주입해 내부망을 탐색(SSRF).
- **프로젝트에서 발생 가능한 상황**: CareTarget이 Guardian 전용 API를 직접 호출, 로그인한 보호자가 자신과 연동되지 않은 다른 보호대상자의 위치를 ID만 바꿔 조회, `/internal/**` 엔드포인트가 외부에 그대로 노출되는 경우, Google Places API 프록시 기능에 임의 URL을 주입해 Spring Boot가 내부망(Redis, PostgreSQL, FastAPI 등)으로 요청을 보내도록 유도하는 경우.

### 1.2 프로젝트 위험 요소 분석

| 위험 요소 | 설명 |
|---|---|
| 보호자 API 접근 권한 우회 | CareTarget 계정으로 `/api/guardian/**` 호출 시 Role 검증이 누락되면 다른 사용자의 관리 기능에 접근 가능 |
| 보호 대상자 위치 데이터 무단 조회 | `/api/guardian/location/current`, `/location/history` 등에서 Role만 확인하고 "이 Guardian이 이 CareTarget과 실제로 연동되어 있는가"를 확인하지 않으면 IDOR 발생 |
| 관리자 API 접근 | `ROLE_ADMIN` 검증이 URL 패턴 누락이나 오탈자로 뚫릴 경우 전체 사용자/서비스 관리 기능 노출 |
| 내부 전용 API 노출 | `/internal/geofence/check`, `/internal/ai/predict` 등이 인증 없이 외부 인터넷에서 직접 호출 가능한 경우 |
| SSRF (서버 대행 요청 오남용) | Google Maps/Places API 프록시, AI 서버 호출 URL 등에 사용자 입력이 검증 없이 반영되는 경우 |

### 1.3 대응 방안

**Spring Security 기준**
- URL 권한 관리: `authorizeHttpRequests`로 `/api/guardian/**` → `ROLE_GUARDIAN`, `/api/care-target/**` → `ROLE_CARE_TARGET`, `/api/admin/**` → `ROLE_ADMIN`, `/internal/**` → `denyAll` 명시 (상세 매핑표는 Security_Guide.md 4.2 참고)
- Method Security: `@EnableMethodSecurity` 활성화, 세밀한 조건은 `@PreAuthorize`로 표현 (Security_Guide.md 4.3~4.4 참고)
- Role 검증: URL 패턴만으로 표현 불가능한 리소스 소유권 검증(보호자-보호대상자 관계)은 Service 계층에서 별도 수행 (Security_Guide.md 4.5)

**Database 기준**
- 데이터 접근 권한 검증: 모든 조회/수정 쿼리에 `WHERE guardian_id = :currentUserId` 또는 관계 테이블 JOIN 조건을 반드시 포함, 클라이언트가 보낸 `id`만으로 조건 없이 조회하지 않는다
- 사용자 ID 기반 데이터 필터링: Service 계층에서 SecurityContext의 `userId`를 조회 파라미터에 강제로 주입 (Controller가 임의로 전달한 `userId` 파라미터를 신뢰하지 않는다)

**API 기준**
- 인증 필수 API 분리: `/api/auth/**`(로그인/토큰 재발급)를 제외한 모든 API는 기본적으로 인증을 요구하는 화이트리스트 방식(`permitAll` 최소화, 명시적으로 열거)으로 구성
- Public API 최소화: 공개 API 목록을 별도 문서화하고 신규 API 추가 시 기본값이 "인증 필요"가 되도록 설계
- SSRF 방어: 외부 URL을 서버가 대신 호출하는 기능(장소 검색 등)은 허용 도메인 화이트리스트(Google API 도메인만) 적용, 사용자 입력 URL을 그대로 `fetch`하지 않는다

### 1.4 개발 체크리스트

□ 모든 민감 API 인증 적용
□ Role 권한 검증 (`ROLE_GUARDIAN`/`ROLE_CARE_TARGET`/`ROLE_ADMIN` URL 매핑 누락 여부)
□ 사용자 소유 데이터 검증 (조회/수정 쿼리에 소유자 조건 포함 여부)
□ 관리자 API 별도 보호 (`/api/admin/**` 접근 시 감사 로그 기록, Security_Guide.md 10.1 연계)
□ `/internal/**` 외부 노출 차단 (Spring Security + 네트워크 레벨 이중 확인)
□ 서버 대행 요청(SSRF 가능 지점)에 URL 화이트리스트 적용 여부

---

## 2. A02:2025 Security Misconfiguration

### 2.1 취약점 설명

- **발생 원인**: 프레임워크/서버/클라우드의 기본 설정을 그대로 사용하거나, 운영 환경에 개발용 설정(디버그 모드, 상세 에러 페이지, 불필요한 엔드포인트 노출)이 남아있을 때 발생한다. 설정 기반으로 동작하는 애플리케이션 비중이 늘면서 2025년 개정판에서 5위→2위로 상승했다.
- **공격자가 악용하는 방식**: Actuator 등 관리 엔드포인트로 내부 구조 파악, 상세 에러 페이지의 스택 트레이스로 기술 스택/버전 특정 후 알려진 취약점 공격, 기본 계정/기본 비밀번호 시도, 불필요하게 열린 포트(DB, Redis 등)로 직접 접근.
- **프로젝트에서 발생 가능한 상황**: AWS 보안 그룹에서 PostgreSQL(5432)/Redis(6379) 포트가 외부에 열려 있는 경우, Spring Boot Actuator가 인증 없이 `/actuator/env`, `/actuator/heapdump`를 노출하는 경우, 운영 환경에서 예외 발생 시 스택 트레이스가 그대로 Flutter 앱에 노출되는 경우.

### 2.2 프로젝트 위험 요소 분석

| 위험 요소 | 설명 |
|---|---|
| DB/캐시 포트 노출 | Docker Compose로 PostgreSQL/Redis를 구동할 때 `ports` 설정을 통해 실수로 컨테이너 포트가 EC2 외부 IP에 그대로 바인딩되는 경우 |
| Actuator 노출 | 헬스체크 목적으로 켜둔 Actuator가 민감 엔드포인트까지 인증 없이 공개되는 경우 |
| 에러 메시지 정보노출 | 운영 환경에서 `server.error.include-stacktrace=always` 등으로 방치되는 경우 |
| CORS 과다 허용 | 개발 편의를 위해 설정한 `allowedOrigins("*")`가 운영 배포 시 그대로 남는 경우 |
| Nginx/Reverse Proxy 기본 설정 | 서버 버전 헤더(`Server: nginx/1.x`) 노출, 기본 에러 페이지 노출 |

### 2.3 대응 방안

**Spring Security 기준**
- 운영 프로파일(`application-prod.yml`)에서 `management.endpoints.web.exposure.include`를 `health` 등 최소 항목으로 제한하고 나머지는 인증 필요 또는 비활성화
- `server.error.include-stacktrace=never`, `server.error.include-message=never`(운영), Custom Error 응답은 GlobalExceptionHandler로 일원화 (Exception Handling Rule 참고)

**CORS 정책**
- 허용 Origin을 프로덕션 도메인으로 명시 등록, `*` 금지 (Security_Guide.md 7.3과 동일 정책 유지, 환경별 profile로 dev/prod 분리)

**DEBUG 비활성화**
- `spring.jpa.show-sql=false`, 운영 로그 레벨을 `INFO` 이상으로 설정, `DEBUG` 레벨은 개발 환경 전용

**에러 메시지 노출 방지**
- 클라이언트 응답에는 ErrorCode + 안내 메시지만 포함(API Response Rule), 상세 원인은 서버 로그 전용 (Exception Handling Rule 12장)

**AWS 보안 설정**
- 보안 그룹(Security Group)에서 PostgreSQL/Redis 포트는 애플리케이션 서버(EC2 내부 IP/동일 VPC)에서만 접근 가능하도록 제한, 외부(0.0.0.0/0) 인바운드 금지
- SSH(22) 포트도 특정 관리자 IP 대역으로 제한
- Docker Compose에서 DB/Redis 컨테이너는 `ports` 대신 내부 네트워크(`expose`)만 사용해 호스트 포트 바인딩 자체를 하지 않는 것을 권장

### 2.4 개발 체크리스트

□ 운영 환경 Actuator 민감 엔드포인트 비활성화
□ DEBUG 로그/SQL 로그 운영 환경 비활성화
□ 에러 응답에 스택 트레이스 미포함 확인
□ CORS 허용 Origin이 `*`가 아닌지 확인
□ AWS 보안 그룹에서 DB/Redis 포트 외부 노출 여부 점검
□ Docker Compose 포트 바인딩이 필요한 범위로 최소화되어 있는지 확인

---

## 3. A03:2025 Software Supply Chain Failures

### 3.1 취약점 설명

- **발생 원인**: 애플리케이션이 직접 작성한 코드뿐 아니라 의존하는 라이브러리, 빌드 도구, 컨테이너 베이스 이미지, CI/CD 파이프라인, 모델 배포 경로(Hugging Face 등)까지 공급망 전체에 신뢰 검증 없이 의존할 때 발생한다. 2021년의 "Vulnerable and Outdated Components"보다 범위가 넓어져, 취약점이 알려진 구버전 라이브러리뿐 아니라 빌드/배포 과정 자체의 변조 위험까지 포함한다.
- **공격자가 악용하는 방식**: 알려진 CVE가 있는 라이브러리 버전을 그대로 사용하는 애플리케이션 공격, 악성 코드가 삽입된 오픈소스 패키지(타이포스쿼팅) 설치 유도, 검증되지 않은 Docker Base Image/Hugging Face 모델을 통한 공급망 침투.
- **프로젝트에서 발생 가능한 상황**: Spring Boot/Gradle 의존성 중 오래된 버전에 알려진 취약점이 존재, Python AI 서버의 `requirements.txt` 의존성 버전 미고정, Hugging Face에서 받아오는 모델/데이터셋의 출처 미검증, Docker Base Image(`openjdk`, `python` 등)의 오래된 태그 사용.

### 3.2 프로젝트 위험 요소 분석

| 위험 요소 | 설명 |
|---|---|
| Gradle 의존성 취약점 | Spring Boot, JWT 라이브러리(`jjwt` 등), DB 드라이버의 알려진 CVE 미패치 |
| Python 의존성 취약점 | FastAPI, Pandas, XGBoost/LightGBM, Scikit-learn 등 버전 미고정으로 인한 예기치 않은 업데이트/취약점 유입 |
| Docker Base Image | 오래된 `openjdk`/`python` 베이스 이미지 사용 시 OS 레벨 취약점 누적 |
| Hugging Face 모델/데이터 출처 | 검증되지 않은 제3자 모델을 그대로 로드할 경우 악성 코드(pickle 역직렬화 취약점 등) 포함 가능성 |
| CI/CD 파이프라인 무결성 | Jenkins + GitHub Webhook 기반 자동 배포 과정에서 빌드 서버 자체가 침해되면 배포되는 모든 산출물이 오염될 수 있음 |

### 3.3 대응 방안

**Spring Boot Dependency 관리**
- Gradle Dependency Check(OWASP Dependency-Check Gradle Plugin) 또는 GitHub Dependabot을 CI 파이프라인에 통합해 신규 커밋/PR마다 자동 스캔
- `build.gradle`에서 버전을 명시적으로 고정(dynamic version, `+` 표기 금지)하고 정기적으로 최신 안정 버전으로 계획된 업그레이드 수행

**라이브러리 취약점 점검**
- 최소 월 1회 `./gradlew dependencyCheckAnalyze` 및 Python `pip-audit`/`safety` 실행을 정기 점검 항목으로 등록
- FastAPI 쪽 `requirements.txt`는 버전을 고정(`==`)하고, 업데이트 시 별도 테스트 환경에서 검증 후 반영

**Docker Image 취약점 관리**
- 베이스 이미지는 공식 이미지의 특정 버전 태그를 고정 사용(`latest` 태그 금지), `docker scan` 또는 Trivy 등으로 이미지 스캔을 CI에 포함
- Multi-stage build로 최종 이미지에는 빌드 도구/불필요한 패키지를 포함하지 않는다

**Hugging Face 모델 관리**
- 신뢰 가능한 조직/작성자가 배포한 모델만 사용하고, 커밋 해시를 고정해 참조(모델이 예고 없이 교체되는 것 방지)
- 가능하면 `safetensors` 형식을 사용해 pickle 기반 역직렬화 취약점을 회피

**CI/CD 무결성**
- Jenkins 접근을 내부망/특정 IP로 제한하고, GitHub Webhook Secret 검증을 필수화(3.3 A08과도 연계)

### 3.4 개발 체크리스트

□ Gradle Dependency Check(또는 Dependabot) CI 연동 여부
□ Python 의존성 `pip-audit`/`safety` 정기 점검 여부
□ Docker Base Image 태그 고정 및 취약점 스캔 여부
□ Hugging Face 모델 출처/커밋 해시 고정 여부
□ CI/CD(Jenkins) 접근 제한 및 Webhook Secret 검증 여부

---

## 4. A04:2025 Cryptographic Failures

### 4.1 취약점 설명

- **발생 원인**: 민감 데이터를 평문으로 저장/전송하거나, 취약한 암호화 알고리즘(MD5, SHA1, DES 등)을 사용하거나, 키 관리가 부실할 때 발생한다.
- **공격자가 악용하는 방식**: 네트워크 스니핑으로 평문 전송 데이터 탈취, DB 유출 시 평문/취약 해시로 저장된 자격 증명 역산, 하드코딩된 Secret Key로 JWT 위조.
- **프로젝트에서 발생 가능한 상황**: Flutter↔Spring Boot 통신이 HTTP로 이루어지는 경우 GPS 좌표/보호자-대상자 관계가 그대로 노출, JWT Secret이 소스코드에 하드코딩된 경우, 위치 이력(LocationHistory) 테이블이 암호화 없이 저장되어 DB 유출 시 그대로 노출.

### 4.2 프로젝트 위험 요소 분석

| 위험 요소 | 설명 |
|---|---|
| 평문 통신 | HTTPS 미적용 시 위치 데이터, JWT가 네트워크 구간에서 노출 |
| Secret 하드코딩 | JWT Secret, Google Client Secret, LLM API Key가 코드/설정 파일에 커밋되는 경우 |
| 민감 데이터 저장 | 위치 이력, 얼굴 인증 데이터가 평문 그대로 PostgreSQL에 저장 |
| 취약한 해시 알고리즘 | 향후 로컬 계정 도입 시 MD5/SHA1로 비밀번호를 저장하는 실수 |

### 4.3 대응 방안

**Spring Security 기준 / PasswordEncoder**
- 로컬 계정 도입 시 `BCryptPasswordEncoder`(work factor 10 이상) 사용, 평문/양방향 암호화 저장 금지 (본 프로젝트는 Google OAuth2 전용이므로 현재는 비밀번호 자체를 저장하지 않는 것이 원칙 — Security_Guide.md 9장 A02 참고)

**JWT Secret Key 관리**
- 환경 변수(`JWT_SECRET`) 또는 AWS Secrets Manager/Parameter Store(SecureString)로 관리, 최소 256bit 이상 (Security_Guide.md 5.1과 동일 정책)

**HTTPS 적용**
- Nginx Reverse Proxy에서 TLS Termination, 모든 HTTP 요청을 HTTPS로 리다이렉트, HSTS 헤더 적용 검토

**환경 변수 기반 Secret 관리**
- `.env`/`application-prod.yml`의 Secret 값은 Git 저장소에 커밋하지 않고 `../../.gitignore` 처리, 배포 시점에 AWS 환경변수 또는 Secrets Manager에서 주입

**개인정보 암호화 필요 영역**
- 위치 이력(LocationHistory)의 좌표 원문은 저장 자체는 서비스 목적상 불가피하므로, 접근 권한 통제(2단계 인가, Security_Guide.md 4.5)와 함께 DB 레벨 암호화(컬럼 암호화 또는 저장소 암호화, AWS RDS 암호화 옵션) 적용 검토
- 얼굴 인증 관련 이미지/특징 데이터는 원본 이미지를 영구 저장하지 않고 인증 목적 처리 후 즉시 폐기하는 것을 원칙으로 하며, 불가피하게 저장해야 한다면 별도 암호화된 스토리지에 분리 보관

### 4.4 개발 체크리스트

□ 전 구간 HTTPS 적용 확인
□ JWT Secret 등 민감 설정값 하드코딩 여부 점검
□ PasswordEncoder 적용 여부 (로컬 계정 도입 시)
□ 위치 이력/얼굴 인증 데이터 저장 및 암호화 정책 확인
□ `.env`/Secret 파일 Git 커밋 여부 점검

---

## 5. A05:2025 Injection

### 5.1 취약점 설명

- **발생 원인**: 사용자 입력을 검증/이스케이프 없이 쿼리, 명령어, 프롬프트 등에 그대로 결합할 때 발생한다.
- **공격자가 악용하는 방식**: SQL Injection으로 DB 데이터 탈취/변조, LLM 서비스에 Prompt Injection으로 시스템 프롬프트 유출 또는 의도하지 않은 행동 유도.
- **프로젝트에서 발생 가능한 상황**: MyBatis에서 `${}` 문자열 치환을 파라미터 바인딩 대신 사용, JPA `@Query`에 문자열을 concat, AI Care Chat에 사용자가 시스템 프롬프트를 무시하도록 유도하는 입력을 그대로 전달.

### 5.2 프로젝트 위험 요소 분석

| 위험 요소 | 설명 |
|---|---|
| MyBatis 동적 쿼리 | 정렬 컬럼명 등 일부 구간에서 `${}` 사용이 필요한 경우 검증 없이 사용하면 SQL Injection 발생 |
| JPA Native Query | `@Query(nativeQuery = true)`에서 문자열 결합 시 동일 위험 |
| FastAPI 입력 검증 누락 | Pydantic 스키마 없이 raw dict를 받아 처리할 경우 타입/범위 검증 누락 |
| LLM Prompt Injection | AI Care Chat 사용자 입력이 시스템 프롬프트 영역과 분리되지 않은 경우 |

### 5.3 대응 방안

**SQL Injection 대응 / MyBatis Parameter Binding / Prepared Statement**
- MyBatis는 `#{}` 바인딩만 사용, `${}`가 불가피한 경우(정렬 컬럼 등) 화이트리스트 Enum으로 값 제한 후 사용
- JPA는 기본적으로 Parameter Binding(PreparedStatement)이 적용되는 `@Query` 사용, Native Query에서도 파라미터는 `:param` 바인딩만 사용

**입력값 검증**
- 모든 Controller 진입점 DTO에 Bean Validation 적용(`@Valid`), GIS 좌표/반경 등은 Custom Validator (Exception Handling Rule 6장과 동일 원칙 공유)

**FastAPI 요청 데이터 Validation**
- 모든 엔드포인트에 Pydantic 모델을 명시적으로 선언해 타입/범위/필수값을 강제, `Any` 타입이나 raw JSON 그대로 받는 엔드포인트 지양

**LLM Prompt Injection 방어**
- 시스템 프롬프트와 사용자 입력을 명확히 분리된 메시지 role로 구성(System/User 분리), 사용자 입력 길이 제한, 시스템 프롬프트 노출 요청 패턴에 대한 응답 필터링 적용 (Security_Guide.md 11.5와 연계)

### 5.4 개발 체크리스트

□ MyBatis `${}` 사용 구간 화이트리스트 검증 여부
□ JPA Native Query 파라미터 바인딩 확인
□ Controller DTO Validation(`@Valid`) 적용 여부
□ FastAPI 엔드포인트 Pydantic 스키마 적용 여부
□ LLM 프롬프트 System/User 분리 및 입력 길이 제한 적용 여부

---

## 6. A06:2025 Insecure Design

### 6.1 취약점 설명

- **발생 원인**: 구현 단계의 버그가 아니라, 애초에 위협 모델링 없이 설계된 구조적 결함이다. 아무리 코드를 잘 짜도 설계 자체에 구멍이 있으면 막을 수 없다.
- **공격자가 악용하는 방식**: 설계 단계에서 고려되지 않은 흐름(예: Role 없는 사용자의 API 호출 시 기본 동작, 부분 실패 시 상태 불일치)을 악용.
- **프로젝트에서 발생 가능한 상황**: OAuth2 최초 로그인 후 Role이 아직 배정되지 않은 사용자가 특정 API에 접근했을 때 "기본적으로 허용"되는 설계, 긴급 상황(A안심구역 이탈) 알림 로직에서 예외 발생 시 알림을 조용히 누락하는 fail-open 설계.

### 6.2 프로젝트 위험 요소 분석

| 위험 요소 | 설명 |
|---|---|
| 인증/인가 분리 미흡 | "로그인됨"과 "이 기능을 쓸 자격이 있음"을 같은 것으로 취급하는 설계 |
| 최소 권한 원칙 미적용 | 신규 API 추가 시 기본값이 "모두 허용"으로 시작하는 설계 |
| 민감 데이터 접근 정책 부재 | 위치 데이터 접근에 대한 명시적 정책 문서 없이 개발자 재량으로 구현 |
| Fail-Open 설계 | GeoFence 이탈 감지, 긴급 알림 등 안전에 직결되는 기능에서 오류 발생 시 "일단 통과"로 처리하는 설계 |

### 6.3 대응 방안

**보안 설계 단계 적용**
- 신규 기능 설계 시(특히 위치/알림/AI 관련) "이 기능을 오남용하면 어떤 일이 벌어지는가"를 설계 리뷰 항목에 포함(위협 모델링 간이 체크리스트 운영)

**인증/인가 분리**
- Authentication(신원 확인)과 Authorization(권한 확인)을 코드 레벨에서도 명확히 분리된 계층(Filter vs Service)으로 유지 (Security_Guide.md 1.2, 4.5 참고)

**최소 권한 원칙**
- 신규 API는 기본적으로 인증 필요 + 명시적 Role 지정을 원칙으로 하고, `permitAll`은 예외적으로만 허용하며 PR 리뷰에서 반드시 근거를 명시

**민감 데이터 접근 정책**
- 위치 데이터, 얼굴 인증 데이터 등은 "누가, 언제, 왜 접근했는가"를 원칙적으로 감사 가능하게 설계(9장 로깅과 연계)

**Fail-Safe 설계 원칙**
- 안전 관련 기능(GeoFence 이탈, 긴급 알림)은 예외 발생 시 "알림을 보내지 않고 조용히 넘어가기"(fail-open)가 아니라 "재시도 후에도 실패하면 별도 경보/모니터링으로 전환"(fail-safe)하는 방향으로 설계 (Exception Handling Rule 9장 External Service Exception 원칙과 연계)

### 6.4 개발 체크리스트

□ 신규 기능 설계 시 오남용 시나리오 검토 여부
□ 신규 API 기본값이 "인증 필요"인지 확인
□ 안전 관련 기능(GeoFence, 긴급 알림)의 예외 처리 방향이 fail-safe인지 확인
□ 민감 데이터 접근에 대한 감사 가능성 확보 여부

---

## 7. A07:2025 Authentication Failures

### 7.1 취약점 설명

- **발생 원인**: 인증 메커니즘 자체가 약하거나(토큰 무기한 유효, 무차별 대입 미방어), 인증 상태 관리가 부실할 때 발생한다.
- **공격자가 악용하는 방식**: 탈취한 토큰의 장기 재사용, 계정 탈취 후 OAuth 재연동 악용, 로그인 시도 제한이 없는 엔드포인트에 대한 무차별 대입.
- **프로젝트에서 발생 가능한 상황**: Access Token 만료 시간이 지나치게 길게 설정되는 경우, 로그아웃해도 기존 토큰이 계속 유효한 경우(Blacklist 미적용), Google 이메일 미검증 상태로 계정이 생성되는 경우.

### 7.2 프로젝트 위험 요소 분석

| 위험 요소 | 설명 |
|---|---|
| JWT 정책 미흡 | Access Token 만료 시간이 과도하게 길거나, Refresh Token Rotation이 없어 탈취 시 장기간 악용 가능 |
| 로그아웃 무력화 | JWT Blacklist(Redis) 미적용 시 로그아웃 후에도 기존 토큰으로 API 호출 가능 |
| OAuth2 인증 취약 | 이메일 미검증 계정 허용, oauth_id 대신 이메일만으로 계정 매핑 시 계정 탈취 가능 |
| Brute Force 미방어 | 로그인/OAuth 콜백 엔드포인트에 요청 빈도 제한이 없는 경우 |

### 7.3 대응 방안

**JWT 인증 정책 / Token 만료 정책 / Refresh Token 관리**
- Access Token 15~30분, Refresh Token 14일, Rotation 전략 적용 (Security_Guide.md 5장 상세 정책 그대로 따름 — 본 문서에서 재정의하지 않음)

**OAuth2 인증 보안**
- `email_verified` 검증, `oauth_provider`+`oauth_id` 기준 계정 매핑 (Security_Guide.md 6.6~6.7 참고)

**로그인 실패 처리 / Brute Force 대응**
- OAuth 로그인 엔드포인트에 IP/기기 기준 요청 빈도 제한(Rate Limit, Security_Guide.md 7.2와 연계)을 적용해 짧은 시간 내 반복 요청을 차단
- 짧은 시간 내 동일 계정에 대한 비정상적으로 잦은 토큰 재발급 요청은 이상 패턴으로 탐지해 일시적으로 제한(추가 확장 고려)

### 7.4 개발 체크리스트

□ Access/Refresh Token 만료 시간이 정책값과 일치하는지 확인
□ JWT Blacklist(Redis)가 로그아웃 시 정상 등록되는지 확인
□ Google OAuth2 이메일 검증 로직 적용 여부
□ 로그인/OAuth 엔드포인트 Rate Limit 적용 여부
□ Refresh Token Rotation 및 재사용 감지 로직 적용 여부

---

## 8. A08:2025 Software or Data Integrity Failures

### 8.1 취약점 설명

- **발생 원인**: 소프트웨어 업데이트, 중요 데이터, CI/CD 파이프라인의 무결성을 검증하지 않고 신뢰할 때 발생한다. A03(Software Supply Chain Failures)이 "공급망 전체의 신뢰 여부"를 다룬다면, 이 항목은 "실제로 배포/저장되는 산출물이 변조되지 않았는가"라는 더 낮은 레벨의 무결성 검증에 집중한다.
- **공격자가 악용하는 방식**: CI/CD 파이프라인 침투를 통한 악성 코드 삽입 후 배포, 서명되지 않은 업데이트 적용, 클라이언트로부터 받은 데이터의 무결성 검증 없이 그대로 신뢰.
- **프로젝트에서 발생 가능한 상황**: Jenkins + GitHub Webhook 배포 과정에서 Webhook Secret 검증이 없어 임의의 배포 트리거가 가능한 경우, Docker 이미지 빌드/배포 과정에서 이미지 태그 변조, Flutter 앱이 전송하는 위치 데이터의 무결성(타임스탬프 조작 등)을 서버가 검증하지 않는 경우.

### 8.2 프로젝트 위험 요소 분석

| 위험 요소 | 설명 |
|---|---|
| CI/CD 파이프라인 침투 | GitHub Webhook Secret 미검증 시 제3자가 임의로 배포 트리거 가능 |
| Docker Image 변조 | 빌드된 이미지가 레지스트리~배포 사이에서 검증 없이 그대로 실행 |
| 클라이언트 데이터 무결성 | Flutter가 보낸 위치 데이터의 타임스탬프/좌표를 서버가 그대로 신뢰하고 이상값 검증 없이 저장 |
| Dependency 무결성 | 3장(Supply Chain)과 연계, 설치되는 패키지 자체의 체크섬 미검증 |

### 8.3 대응 방안

**CI/CD 보안**
- GitHub Webhook Secret을 Jenkins에 설정해 서명 검증된 요청만 빌드 트리거로 인정
- Jenkins 접근은 내부망/특정 IP로 제한, 빌드 로그에 Secret 값이 남지 않도록 마스킹

**Docker Image 검증**
- 빌드된 이미지에 다이제스트(SHA256) 기준으로 배포하고, 태그(mutable)만으로 배포 대상을 특정하지 않는다
- 가능하면 이미지 서명(예: cosign) 도입을 검토해 배포 파이프라인 무결성 강화(현재 프로젝트 규모에서는 선택적 확장 항목으로 우선순위는 낮게 설정)

**Dependency 무결성 검사**
- 3장의 Dependency Check 결과와 연계해, lock 파일(`build.gradle.lockfile`, Python `requirements.txt` 버전 고정)을 통해 설치되는 버전이 매 빌드마다 달라지지 않도록 고정

**API 데이터 검증**
- 클라이언트가 전송하는 위치 데이터의 타임스탬프가 서버 시각과 비정상적으로 차이나는 경우, 좌표가 물리적으로 이동 불가능한 값인 경우(속도 이상치) 등을 서버 측에서 검증해 이상 데이터로 별도 플래그 처리(AI 예측 정확도 보호 목적과도 연결)

### 8.4 개발 체크리스트

□ GitHub Webhook Secret 검증 적용 여부
□ Jenkins 접근 제한 및 Secret 마스킹 여부
□ Docker Image 배포 시 다이제스트 기준 검증 여부
□ 의존성 버전 고정(lock 파일) 여부
□ 클라이언트 전송 위치 데이터 이상값 검증 로직 존재 여부

---

## 9. A09:2025 Security Logging & Alerting Failures

### 9.1 취약점 설명

- **발생 원인**: 보안 이벤트가 기록되지 않거나, 기록되더라도 실시간으로 알림(Alerting)되지 않아 침해가 오랜 기간 발견되지 않을 때 발생한다. 2025년 개정판에서는 로깅뿐 아니라 "적절한 조치를 유도하는 알림"까지 강조한다.
- **공격자가 악용하는 방식**: 로그가 없거나 남지 않는 경로를 이용한 흔적 없는 공격, 탐지되더라도 담당자에게 알림이 가지 않아 장시간 방치되는 침해.
- **프로젝트에서 발생 가능한 상황**: 반복적인 로그인 실패, JWT 위변조 시도, 짧은 시간 내 다수의 403(권한 없음) 발생이 로그로만 쌓이고 아무도 확인하지 않는 경우.

### 9.2 프로젝트 위험 요소 분석

| 위험 요소 | 설명 |
|---|---|
| 로깅 누락 | 인증/인가 실패 이벤트가 일반 애플리케이션 로그에 묻혀 보안 관점에서 식별 불가 |
| 알림 부재 | 이상 징후(짧은 시간 내 다수 실패)가 발생해도 담당자에게 알림이 가지 않음 |
| 민감정보 로그 유출 | 로그 자체가 개인정보 유출 경로가 되는 경우 |

### 9.3 대응 방안

**로그 대상**
- 로그인 성공/실패, JWT 검증 실패, 권한 접근 실패(403), 관리자 기능 실행을 구조화된 형태(JSON 등)로 기록 (Security_Guide.md 10.1과 동일 정책, 본 문서는 OWASP 관점의 점검 항목으로만 재확인)

**로그 제외**
- Password, JWT Token 원문, 개인정보(위치 좌표 원문, 얼굴 인증 데이터)는 절대 기록하지 않는다 (Security_Guide.md 10.2 / Exception Handling Rule 12.2와 동일 원칙 공유, 중복 정의하지 않음)

**로그 분석 기준**
- 동일 IP/계정에서 짧은 시간(예: 5분) 내 로그인 실패 N회 이상, 403 응답 N회 이상 발생 시 이상 패턴으로 분류하는 기준을 마련
- 관리자 API 호출은 빈도와 무관하게 전건 별도 채널(감사 로그)로 분리 기록

**장애 추적 방법**
- 요청 단위 traceId(MDC)를 발급해 인증 실패~이후 흐름을 하나의 흐름으로 추적 가능하게 구성 (Exception Handling Rule 12.3 연계)
- 운영 환경에서는 로그를 단순 파일 적재에 그치지 않고, 이상 패턴 발생 시 Slack/이메일 등으로 알림이 가도록 연동(CloudWatch Alarm, 또는 로그 수집 도구의 Alert 규칙)하는 것을 목표로 하며, 최소 단계에서는 관리자 API 접근/반복된 인증 실패에 대해서만 알림을 우선 적용

### 9.4 개발 체크리스트

□ 인증/인가 관련 이벤트가 별도 식별 가능한 형태로 로깅되는지 확인
□ 민감정보가 로그에 남지 않는지 확인
□ 반복 실패 패턴에 대한 탐지 기준 존재 여부
□ 관리자 API 호출이 감사 로그로 별도 기록되는지 확인
□ 이상 징후 발생 시 알림 채널(최소 관리자 알림) 존재 여부

---

## 10. A10:2025 Mishandling of Exceptional Conditions

### 10.1 취약점 설명

- **발생 원인**: 2025년 신설 항목으로, 예외 상황(오류, 경계값, 비정상 입력, 외부 서비스 실패 등)을 부적절하게 처리해 발생하는 보안 결함을 다룬다. 정보노출형 에러 메시지, 예외를 삼키고 넘어가는 로직, 부분 실패 시 상태 불일치, "실패 시 기본적으로 허용"(fail-open) 패턴이 모두 이 범주에 속한다.
- **공격자가 악용하는 방식**: 의도적으로 예외를 유발해 서버 내부 정보(스택 트레이스, DB 구조)를 획득, 예외 처리 로직의 허점(fail-open)을 이용해 인증/인가를 우회, 부분 실패 상태를 반복 유발해 데이터 정합성을 깨뜨림.
- **프로젝트에서 발생 가능한 상황**: JWT 검증 중 예외가 발생했을 때 인증 실패로 처리되지 않고 예외가 삼켜져 인증된 것처럼 동작, 외부 AI 서버 호출 실패 시 예외 처리 미흡으로 요청 스레드가 무한 대기, DB 저장 성공 후 알림 발송 실패를 처리하는 로직에서 예외가 전체 트랜잭션을 롤백시켜 정상 데이터까지 유실.

### 10.2 프로젝트 위험 요소 분석

| 위험 요소 | 설명 |
|---|---|
| 인증 예외 오처리 | Filter 단계에서 예외를 잘못 처리해 인증 실패가 인증 성공처럼 흘러가는 경우 |
| 정보노출형 에러 | 예외 메시지에 스택 트레이스/SQL/내부 경로가 그대로 포함되는 경우 |
| 부적절한 예외 흡수 | 빈 catch 블록으로 예외를 삼켜 오류 상황이 은폐되는 경우 |
| 외부 서비스 실패 시 서비스 전체 장애 전파 | FastAPI/FCM/Google API 실패가 핵심 기능(인증, 위치 추적)까지 전파되는 경우 |

### 10.3 대응 방안

본 항목은 **Exception Handling Rule 문서 전체가 담당 범위**다. 아래는 OWASP A10 관점에서의 핵심 대응을 요약한 것이며, 상세 구현 기준(GlobalExceptionHandler 구조, Custom Exception 계층, Transaction 처리 기준)은 Exception Handling Rule 문서를 따른다.

| 항목 | 대응 (상세는 Exception Handling Rule 참고) |
|---|---|
| 중앙 집중 예외 처리 | GlobalExceptionHandler로 모든 예외를 한 곳에서 처리, 컨트롤러별 임시방편 처리 금지 (Exception Handling Rule 3장) |
| 구체적 예외 처리 | `catch (Exception e)` 광범위 처리 지양, 예외 타입별 세분화 (Exception Handling Rule 2장) |
| 인증 예외의 명확한 실패 처리 | JWT 검증 실패는 반드시 인증 미등록 상태로 이어지며, 예외 발생을 "인증 성공"으로 오판하지 않도록 설계 (Security_Guide.md 2.3 JwtAuthenticationFilter 예시 참고) |
| 정보노출 방지 | 클라이언트 응답에는 ErrorCode만, 상세 원인은 서버 로그 전용 (Exception Handling Rule 12장) |
| 외부 서비스 장애 격리 | Timeout/재시도/Fallback으로 핵심 기능과 부가 기능(AI/알림) 장애를 분리 (Exception Handling Rule 9장) |
| Fail-Safe 트랜잭션 | 부분 실패 시 데이터 정합성이 깨지지 않도록 트랜잭션 경계 설계 (Exception Handling Rule 11장) |

### 10.4 개발 체크리스트

□ 빈 catch 블록 존재 여부 코드 검색(정적 분석 또는 리뷰 시 확인)
□ 인증 관련 예외가 fail-open으로 흐르지 않는지 확인
□ 모든 예외 경로가 GlobalExceptionHandler로 수렴하는지 확인 (Exception Handling Rule 14장 체크리스트와 연계, 중복 점검 없이 그 문서의 체크리스트를 그대로 활용)
□ 외부 서비스(FastAPI/FCM/Google API) 실패가 핵심 기능 장애로 전파되지 않는지 확인

---

## 11. 개인정보 보호 정책

이 프로젝트는 위치 데이터(GPS 좌표, 이동 이력)와 개인 식별 정보(이메일, 얼굴 인증 데이터)를 다루므로, OWASP Top 10 각 항목의 대응과는 별도로 개인정보 보호 관점의 정책을 명시한다.

### 11.1 개인정보 저장 정책

| 데이터 | 저장 위치 | 정책 |
|---|---|---|
| 최신 위치 | Redis | 실시간 조회용, 일정 TTL 후 자동 만료(영구 저장 아님) |
| 위치 이력(LocationHistory) | PostgreSQL | 서비스 목적(방문 이력, AI 예측)에 필요한 최소 기간만 보관, 보관 기간 초과 시 자동 삭제/익명화(집계 데이터로만 전환) 검토 |
| 얼굴 인증 데이터 | 원칙적으로 미저장 | 인증 처리 후 즉시 폐기, 불가피하게 저장 시 별도 암호화 스토리지 분리 |
| 사용자 계정 정보 | PostgreSQL | Google OAuth 기반이므로 비밀번호 미보관, 이메일/이름 등 최소 항목만 저장 |

### 11.2 민감 데이터 접근 제한

- 위치 데이터, 얼굴 인증 데이터 접근은 반드시 Role + 리소스 소유권 검증(Security_Guide.md 4장 3단계 인가 구조)을 거친다.
- 관리자(Admin)라 하더라도 위치 원본 데이터에 대한 접근은 서비스 운영 목적(장애 대응, 사용자 문의 처리 등)으로 한정하고, 해당 접근 자체를 감사 로그로 남긴다.

### 11.3 로그 마스킹

- 로그에는 위치 좌표 원문, 이메일 전체, 얼굴 인증 데이터를 남기지 않는다(Exception Handling Rule 12.2, Security_Guide.md 10.2와 동일 원칙).
- 부득이하게 추적을 위해 사용자 식별이 필요한 경우 `userId`(내부 고유 ID)만 사용하고, 이메일/이름 등 직접 식별 정보는 로그에 포함하지 않는다.

### 11.4 데이터 보관 기간

- 위치 이력: 서비스 정책에 따른 보관 기간(예: 최근 1년)을 정하고, 초과분은 배치 작업으로 삭제하거나 통계 목적의 익명화된 형태로만 유지한다.
- 탈퇴 회원 데이터: 관련 법령(개인정보 보호법) 기준에 따라 즉시 삭제 또는 법정 보관 기간 동안 분리 보관 후 파기한다.
- Refresh Token/JWT Blacklist: Redis TTL로 자동 만료(Security_Guide.md 5.5~5.7).

---

## 12. API Security Checklist

□ 인증 없는 민감 API 존재 여부
□ 권한 우회 가능 여부 (IDOR, Role 검증 누락)
□ 입력값 검증 여부 (`@Valid`, Pydantic 스키마)
□ SQL Injection 방어 여부 (MyBatis `#{}`, JPA Parameter Binding)
□ 민감정보 응답 노출 여부 (위치 원문, 토큰, 스택 트레이스가 응답 바디에 포함되지 않는지)
□ Rate Limit 적용 여부 (로그인, 위치 전송, AI 호출 등 민감/비용 발생 API)
□ CORS 설정이 프로덕션 도메인으로 제한되어 있는지 여부
□ `/internal/**` API의 외부 노출 차단 여부

---

## 13. Code Review Security Checklist

개발자가 PR 리뷰 시 아래 항목을 확인한다.

□ Controller 권한 검증 확인 (`@PreAuthorize` 또는 URL 패턴 권한이 누락되지 않았는지)
□ Service Layer 데이터 권한 검증 확인 (조회/수정 쿼리에 소유자 조건이 포함되었는지, IDOR 가능성 점검)
□ Exception 정보 노출 확인 (예외 메시지에 스택 트레이스/SQL/내부 경로가 포함되지 않는지, Exception Handling Rule 기준 준수 여부)
□ Secret 하드코딩 확인 (JWT Secret, API Key, DB 접속 정보 등이 코드/설정 파일에 직접 포함되지 않았는지)
□ 신규 의존성 추가 시 취약점 스캔 결과 확인 (3장 Supply Chain 연계)
□ 신규 API의 기본 인증/인가 상태가 명시적으로 설계되었는지 (permitAll을 습관적으로 추가하지 않았는지)
□ 로그 출력문에 개인정보/토큰 원문이 포함되지 않았는지
□ 외부 서비스 호출부에 timeout/예외 변환 처리가 포함되었는지 (Exception Handling Rule 9장 연계)
□ 트랜잭션 경계 안에 불필요한 외부 I/O가 포함되지 않았는지 (Exception Handling Rule 11장 연계)
