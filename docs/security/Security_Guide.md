# Security Guide
>해당파일 경로 docs/security/Security_Guide.md

프로젝트: 아이·노인 케어 위치추적 알림 시스템 (GIS)
문서 위치: `docs/security/Security_Guide.md`
담당 서버: Spring Boot Backend (인증/인가), FastAPI AI Server(연동 대상)
기준: OWASP Top 10 (2025), Spring Security 공식 권장 아키텍처, JWT/OAuth2 표준
버전: v1.0 (작성일 2026-08-06)

> 이 문서는 **Authentication / Authorization 설계**를 담당한다.
> 예외가 실제로 어떻게 잡히고 로깅되는지의 구현 상세는 **Exception Handling Rule** 문서, API 실패 응답의 JSON 구조·Error Code 값 체계는 **API Response Rule** 문서를 따른다. 본 문서에서는 "어떤 상황에서 401/403이 발생하는가"까지만 정의하고, 그 응답이 어떤 필드로 직렬화되는지는 다루지 않는다.

---

## 목차

1. Security Architecture
2. Spring Security Filter Chain 설계
3. Authentication 설계
4. Authorization 설계
5. JWT Security Policy
6. OAuth2 Google Login Security
7. API Security 정책
8. Exception Handling Security
9. OWASP Top 10 대응 정책
10. Logging Security Policy
11. AI Server Communication Security
12. 개발 체크리스트

---

## 1. Security Architecture

### 1.1 설계 배경

이 프로젝트는 보호자(Guardian)와 보호대상자(CareTarget)의 실시간 위치·이동 데이터를 다룬다. 위치 정보는 개인정보 보호법상 민감정보에 준하는 데이터이므로, "누가 로그인했는가(Authentication)"와 "그 사람이 이 데이터에 접근할 자격이 있는가(Authorization)"를 API 레벨에서 강제하는 것이 이 시스템의 보안 설계의 핵심 목표다. 세션 기반이 아닌 JWT 기반 Stateless 인증을 택한 이유는, Flutter 모바일 클라이언트와 REST API + WebSocket 조합에서 서버 확장(다중 인스턴스) 시 세션 동기화 부담이 없고, FastAPI AI 서버와의 내부 통신에도 동일한 토큰 검증 방식을 재사용할 수 있기 때문이다.

### 1.2 전체 인증/인가 흐름

```
Client (Flutter App)
   ↓  HTTP Request + Authorization: Bearer <JWT>
Spring Security Filter Chain
   ↓
JwtAuthenticationFilter (OncePerRequestFilter)
   ↓  JWT 파싱/검증 성공 시
Authentication 객체 생성
   ↓
SecurityContextHolder 저장
   ↓
AuthorizationFilter (URL / Method 기반 권한 검사)
   ↓  권한 통과
Controller
   ↓
Service (비즈니스 로직, 리소스 소유권 검증)
   ↓
Repository (PostgreSQL / Redis)
```

### 1.3 JWT 인증 흐름 (로그인 ~ API 호출)

```
[로그인]
Flutter App → POST /api/auth/oauth/login (Google ID Token)
   ↓
Spring Boot: Google OAuth2 토큰 검증
   ↓
기존 회원 매핑 or 신규 회원 생성 (User/Guardian/CareTarget)
   ↓
Spring Boot: JWT Access Token + Refresh Token 발급
   ↓
Refresh Token → Redis 저장 (TTL 설정)
   ↓
Flutter App: 토큰 저장 (Access Token은 메모리/Secure Storage)

[API 호출]
Flutter App → Authorization: Bearer <Access Token>
   ↓
JwtAuthenticationFilter: 서명 검증 + 만료 검증 + Blacklist 확인(Redis)
   ↓
SecurityContext에 Authentication(userId, role) 등록
   ↓
Role 기반 인가 (Guardian / CareTarget / Admin)
   ↓
리소스 소유권 검증 (보호자-보호대상자 매핑 관계 확인, PostgreSQL)
   ↓
API 응답
```

### 1.4 OAuth2 인증 흐름

```
Flutter App → Google Sign-In SDK 실행 → Google ID Token 획득
   ↓
Flutter App → Spring Boot: POST /api/auth/oauth/login (Google ID Token 전달)
   ↓
Spring Boot: Google Public Key로 ID Token 서명 검증 (Google Tink/JWK)
   ↓
이메일/Google Subject(sub) 추출
   ↓
기존 계정 존재 여부 조회 (PostgreSQL User 테이블, oauth_provider + oauth_id 기준)
   ↓
존재 O → 로그인 처리 / 존재 X → 최초 로그인 시 회원 자동 생성(Role 미배정 상태로 시작 가능)
   ↓
Spring Boot 자체 JWT(Access/Refresh) 발급
   ↓
Flutter App에 JWT 반환 (Google Access Token은 프론트에 재전달하지 않음)
```

**설계 이유**: Google OAuth2는 "이 사용자가 실제로 본인인가"를 확인하는 신원 확인(Authentication) 단계에만 사용한다. Google Access Token은 수명이 짧고 서비스 내부 Role(Guardian/CareTarget/Admin) 개념을 갖고 있지 않으므로, API 인가에는 사용하지 않는다. 로그인 성공 이후의 모든 API 인증/인가는 Spring Boot가 자체 발급한 JWT로 일원화한다.

### 1.5 AI 서버 통신 보안 흐름

```
Spring Boot (요청자, 신뢰 주체)
   ↓  POST /internal/ai/predict, /internal/llm/chat 등
   ↓  Header: X-Internal-Api-Key: <Server-to-Server Key>
FastAPI AI Server
   ↓  API Key 검증 (사용자 JWT 아님)
   ↓  요청 바디 검증
ML/LLM 처리
   ↓
Spring Boot로 결과 반환
   ↓
Spring Boot → Flutter App (사용자 JWT 기준 응답)
```

**설계 이유**: FastAPI의 `/internal/*` 엔드포인트는 사용자가 직접 호출하지 않고 Spring Boot만 호출하는 서버 간(Server-to-Server) 통신이다. 사용자 JWT를 그대로 AI 서버까지 전달하지 않고 별도의 내부 인증 수단(API Key, 11장)을 사용해 신뢰 경계를 분리한다. 이렇게 하면 AI 서버가 탈취당하더라도 사용자 JWT Secret이나 Refresh Token 저장소에 직접 접근할 수 없다.

---

## 2. Spring Security Filter Chain 설계

### 2.1 SecurityFilterChain 설정 목적

Stateless JWT 인증 구조에서는 Spring Security의 기본값(Session 기반, CSRF 활성화, Form Login) 대부분이 불필요하거나 오히려 구멍이 된다. `SecurityFilterChain` Bean을 명시적으로 구성하는 목적은 다음과 같다.

- 세션을 생성하지 않는 `STATELESS` 정책을 명시해 서버 확장성을 확보하고 세션 고정(Session Fixation) 공격 자체를 무의미하게 만든다.
- 기본 Form Login, HTTP Basic, CSRF Filter 등 이 프로젝트에서 쓰지 않는 인증 방식을 비활성화해 공격 표면을 줄인다.
- `JwtAuthenticationFilter`를 표준 Filter Chain에 명시적으로 끼워 넣어 모든 요청이 JWT 검증을 거치도록 강제한다.
- URL 패턴별 권한 요구사항(`/api/guardian/**` 등)을 한 곳에서 선언적으로 관리한다.

### 2.2 Filter 실행 순서

```
1. SecurityContextHolderFilter          - SecurityContext 초기화
2. CorsFilter                            - CORS Preflight 처리
3. JwtAuthenticationFilter (커스텀)       - JWT 파싱/검증, Authentication 등록
4. ExceptionTranslationFilter            - AuthenticationException/AccessDeniedException 포착
5. AuthorizationFilter                   - URL/Method 기반 인가 판단
6. FilterSecurityInterceptor 이후        - DispatcherServlet → Controller
```

### 2.3 JwtAuthenticationFilter 위치

`JwtAuthenticationFilter`는 `UsernamePasswordAuthenticationFilter` **이전** 위치에 등록한다(`addFilterBefore`). 이유는 다음과 같다.

- 이 프로젝트는 Form Login(ID/PW + Session)을 사용하지 않으므로 `UsernamePasswordAuthenticationFilter` 자체가 사실상 비활성 상태에 가깝지만, Spring Security 기본 Filter Chain 순서상 `UsernamePasswordAuthenticationFilter`가 인증 담당 Filter의 표준 위치이기 때문에, 커스텀 JWT 인증 로직을 그 앞에 배치해 "이 요청은 이미 JWT로 인증되었다"는 상태를 먼저 확정짓는다.
- `OncePerRequestFilter`를 상속해 하나의 요청 안에서 필터가 중복 실행되지 않도록 한다(포워드/에러 디스패치 시 중복 검증 방지).

```java
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            if (jwtBlacklistService.isBlacklisted(token)) {
                // 검증은 유효하지만 로그아웃된 토큰 → 인증 미등록, 이후 AuthenticationEntryPoint에서 401 처리
                filterChain.doFilter(request, response);
                return;
            }
            Authentication authentication = jwtTokenProvider.getAuthentication(token);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        // 토큰이 없거나 유효하지 않아도 여기서 예외를 던지지 않는다.
        // 인증되지 않은 상태로 다음 Filter로 넘기고, 실제 차단은 AuthorizationFilter + AuthenticationEntryPoint가 담당한다.
        filterChain.doFilter(request, response);
    }
}
```

**설계 이유**: Filter 내부에서 즉시 401을 응답하지 않고 "인증되지 않은 상태로 통과"시키는 이유는, 인증이 필요 없는 공개 엔드포인트(`/api/auth/oauth/login` 등)까지 이 Filter를 거치기 때문이다. 실제 차단 여부 판단은 `AuthorizationFilter`와 `AuthenticationEntryPoint`에 위임해 Filter 하나가 모든 책임을 지지 않도록 역할을 분리한다.

### 2.4 ExceptionTranslationFilter 처리 방식

`ExceptionTranslationFilter`는 하위 Filter 체인(주로 `AuthorizationFilter`)에서 던져진 `AuthenticationException`과 `AccessDeniedException`을 가로채 각각 `AuthenticationEntryPoint`, `AccessDeniedHandler`로 위임한다. 이 프로젝트에서는 이 Filter를 별도로 커스터마이징하지 않고, 아래 두 핸들러만 직접 구현한다.

### 2.5 AuthenticationEntryPoint 처리 방식

`AuthenticationEntryPoint`는 **인증 자체가 안 된 상태**(토큰 없음, 형식 오류, 서명 검증 실패, 만료, Blacklist 등록됨)에서 보호된 리소스에 접근했을 때 호출된다.

```java
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // 401 응답. 실제 JSON 바디 포맷/ErrorCode 값은 API Response Rule을 따른다.
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(securityResponseWriter.unauthorized(request));
    }
}
```

### 2.6 AccessDeniedHandler 처리 방식

`AccessDeniedHandler`는 **인증은 됐지만 권한이 부족한 상태**(Role 불일치, 리소스 소유권 불일치)에서 호출된다.

```java
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(securityResponseWriter.forbidden(request));
    }
}
```

두 핸들러 모두 **Filter 레벨**에서 직접 응답을 작성한다. `@RestControllerAdvice` 기반 GlobalExceptionHandler는 DispatcherServlet 이후(Controller/Service 계층)에서만 동작하므로 Filter 단계 예외를 잡을 수 없다는 점이 8장에서 다시 다뤄진다.

### 2.7 예외 상황별 처리 매트릭스

| 상황 | 발생 Filter | 처리 주체 | HTTP Status |
|---|---|---|---|
| 인증되지 않은 사용자의 보호된 API 접근 (토큰 없음) | AuthorizationFilter | AuthenticationEntryPoint | 401 |
| 만료된 JWT | JwtAuthenticationFilter (검증 실패) → AuthorizationFilter | AuthenticationEntryPoint | 401 |
| 변조된 JWT (서명 불일치) | JwtAuthenticationFilter (검증 실패) | AuthenticationEntryPoint | 401 |
| Blacklist 등록된 JWT (로그아웃된 토큰) | JwtAuthenticationFilter | AuthenticationEntryPoint | 401 |
| 인증은 됐으나 Role 불일치 (CareTarget이 `/api/guardian/**` 접근) | AuthorizationFilter | AccessDeniedHandler | 403 |
| 인증/Role은 맞으나 리소스 소유권 불일치 (타인의 CareTarget 접근) | Service 계층 (Filter 단계 아님) | Custom Exception → GlobalExceptionHandler (Exception Handling Rule 참고) | 403 |

---

## 3. Authentication 설계

### 3.1 JWT Authentication

#### JWT 구성 (Header.Payload.Signature)

| 구성 | 포함 정보 |
|---|---|
| Header | `alg`(서명 알고리즘, HS256 또는 RS256), `typ`(JWT) |
| Payload | `sub`(userId), `role`(GUARDIAN/CARE_TARGET/ADMIN), `iat`, `exp`, `jti`(토큰 고유 ID, Blacklist 조회용) |
| Signature | Header+Payload를 Secret Key(또는 RSA Private Key)로 서명 |

**Payload 설계 원칙**: 이메일, 이름, 전화번호 등 개인정보는 Payload에 담지 않는다. JWT Payload는 서명만 되어 있을 뿐 암호화되어 있지 않으므로(Base64 인코딩에 불과) 디코딩 시 누구나 읽을 수 있기 때문이다. 필요한 사용자 정보는 `userId`로 매 요청마다 DB/Redis에서 조회한다.

#### Access Token / Refresh Token 관리 방식

| 항목 | Access Token | Refresh Token |
|---|---|---|
| 용도 | API 요청 인증 | Access Token 재발급 |
| 저장 위치(서버) | 저장하지 않음 (Stateless) | Redis (userId 또는 jti 키) |
| 저장 위치(클라이언트) | 앱 메모리 또는 Flutter Secure Storage | Flutter Secure Storage (암호화 저장) |
| 만료 시간 | 짧게 (15~30분 권장) | 길게 (14일 권장, "자동 로그인" 요구사항 반영) |
| 노출 시 위험도 | 짧은 시간 내에서만 악용 가능 | 장기간 악용 가능 → Redis 서버 측 관리 필수 |

#### Token 만료 정책

- Access Token 만료 시 `AUTH_002` 코드로 응답한다(일반 인증 필요 `AUTH_001`과 구분, API Response Rule §5.2·Exception Handling Rule 8.2 참고). Flutter는 이 코드를 받으면 자동으로 `/api/auth/refresh`를 호출한다.
- Refresh Token도 만료되었거나 Redis에 존재하지 않으면(로그아웃/탈취 대응으로 무효화된 경우 포함) 재로그인을 요구한다.
- Refresh Token은 1회 사용 후 재발급하는 **Rotation 전략**을 권장한다(재사용 감지 시 해당 사용자의 모든 세션을 강제 만료).

#### Token 저장 위치 (클라이언트)

Flutter 앱에서는 `flutter_secure_storage`(iOS Keychain / Android Keystore 기반)를 사용해 Access/Refresh Token을 저장한다. 일반 `SharedPreferences`에 평문 저장하지 않는다.

#### Token 탈취 대응 방법

| 대응 | 설명 |
|---|---|
| 짧은 Access Token 수명 | 탈취되어도 피해 시간을 최소화 |
| Refresh Token Rotation | 재사용 감지 시 전체 세션 무효화 |
| JWT Blacklist (Redis, 5장) | 로그아웃/탈취 신고 시 즉시 Access Token 무효화 |
| `jti` 클레임 | 토큰 단위 식별로 특정 토큰만 선택적 무효화 가능 |
| 위치 기반 이상 탐지(향후 확장) | 짧은 시간 내 지리적으로 불가능한 위치에서의 요청 등 이상 패턴 탐지 |

### 3.2 UserDetails 구조

```
CustomUserDetails implements UserDetails
   - userId, role(GrantedAuthority로 변환), (email 등은 최소한만 보관)

UserDetailsService (JwtAuthenticationFilter에서는 실제로는 매 요청 DB 조회를 피하기 위해
JWT Payload의 userId/role로 CustomUserDetails를 즉석 구성하는 경량 방식을 사용하고,
로그인 시점의 자격 증명 검증에서만 UserDetailsService를 통한 DB 조회를 수행)

AuthenticationProvider (OAuth2 로그인 처리 시 사용, JWT 재검증 시에는 미사용)
   - Google ID Token 검증 결과를 바탕으로 Authentication 객체 생성

AuthenticationManager
   - AuthenticationProvider들을 관리, 로그인(OAuth2) 시점에만 개입
```

**설계 이유**: 이 프로젝트는 모든 API 요청마다 `UserDetailsService`로 DB를 조회하지 않는다. JWT 자체에 `sub`(userId)와 `role`이 담겨 있으므로, `JwtAuthenticationFilter`는 토큰 서명 검증만으로 `Authentication` 객체를 구성한다(DB round-trip 없음). `UserDetailsService`와 `AuthenticationManager`는 로그인(OAuth2 인증) 시점에만 관여하고, 이후 API 호출 단계에서는 관여하지 않는다. 이는 Stateless JWT 구조에서 흔히 쓰이는 방식으로, 매 요청 DB 조회로 인한 성능 저하를 방지한다.

---

## 4. Authorization 설계

### 4.1 프로젝트 Role 정의

| Role | 설명 |
|---|---|
| `ROLE_GUARDIAN` | 보호자. 보호대상자 관리, 실시간 위치 조회, 장소(안심구역) 관리, 방문 이력 조회, AI 방문 예측/케어 비서 사용 |
| `ROLE_CARE_TARGET` | 보호대상자. 실시간 위치 전송, 도착 확인, 긴급 연락, AI 도우미 사용 |
| `ROLE_ADMIN` | 시스템 관리자. 사용자 및 서비스 관리 |

### 4.2 URL 권한 관리

```java
http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/auth/**").permitAll()
    .requestMatchers("/internal/**").denyAll()          // 외부 노출 차단, Gateway/Network 레벨에서도 재차단(11장)
    .requestMatchers("/api/guardian/**").hasRole("GUARDIAN")
    .requestMatchers("/api/care-target/**").hasRole("CARE_TARGET")
    .requestMatchers("/api/admin/**").hasRole("ADMIN")
    .anyRequest().authenticated()
);
```

| 패턴 | 필요 권한 |
|---|---|
| `/api/auth/oauth/login`, `/api/auth/refresh` | 인증 불필요 (permitAll) |
| `/api/auth/logout`, `/api/auth/me` | 인증 필요 (모든 Role) |
| `/api/guardian/**` | `ROLE_GUARDIAN` |
| `/api/care-target/**` | `ROLE_CARE_TARGET` |
| `/api/admin/**` | `ROLE_ADMIN` |
| `/internal/**` | 외부 요청 자체를 차단, Server-to-Server 인증만 허용 (11장) |

### 4.3 Method Security 사용 여부

URL 패턴 기반 권한 검사(`authorizeHttpRequests`)만으로는 **"같은 Role 안에서 특정 리소스에 대한 소유권"**까지 검증할 수 없다(예: Guardian A가 Guardian B의 CareTarget에 접근하는 경우). 이 계층은 URL 패턴으로 표현이 안 되므로 Method Security(`@PreAuthorize`)와 Service 계층 검증을 함께 사용한다.

`@EnableMethodSecurity`를 활성화하고, Controller 또는 Service 메서드에 `@PreAuthorize`를 선언적으로 적용한다.

```java
@PreAuthorize("hasRole('GUARDIAN')")
@GetMapping("/api/guardian/care-targets/{id}")
public ApiResponse<CareTargetResponse> getCareTarget(@PathVariable Long id) {
    // Role 검증은 @PreAuthorize에서 선언적으로 처리
    // 리소스 소유권(요청자 Guardian ↔ id의 CareTarget 매핑 관계) 검증은 Service 계층에서 수행
    return careTargetService.getCareTarget(SecurityUtils.getCurrentUserId(), id);
}
```

### 4.4 @PreAuthorize 사용 기준

| 사용 기준 | 예시 |
|---|---|
| Role 단위의 단순 접근 제어는 URL 패턴(`authorizeHttpRequests`)으로 처리하고, `@PreAuthorize`는 URL만으로 표현하기 어려운 세밀한 조건에만 사용 | `@PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.userId")` |
| 파라미터 기반 조건이 필요한 경우 SpEL로 표현 | 본인 데이터만 조회 허용 |
| 복잡한 소유권 검증(다단계 관계 확인)은 `@PreAuthorize`로 무리하게 표현하지 않고 Service 계층 로직으로 분리 | 보호자-보호대상자 매핑 관계 검증(4.5) |

### 4.5 권한 검증 위치 (3단계)

이 프로젝트의 인가는 URL Role 검사 한 단계로 끝나지 않고, 아래 3단계로 구성된다.

```
1단계: 인증 (Authentication) — 이 사용자가 누구인가          → Filter 단계 (2장)
2단계: 역할 인가 (Role Authorization) — 무엇을 할 수 있는가   → authorizeHttpRequests / @PreAuthorize
3단계: 리소스 접근 제어 (Resource-level Authorization)
        — 실제로 요청 대상과 관계가 맺어져 있는가             → Service 계층
        예: 보호자-보호대상자 매핑 관계 검증 (PostgreSQL 관계 테이블 조회)
        예: 요청한 Place/LocationHistory가 본인(또는 본인이 관리하는 CareTarget) 소유인지 확인
```

3단계 위반 시 Custom Exception(`AccessDeniedCustomException` 등, Exception Handling Rule 참고)을 던져 403으로 응답한다. 이 검증은 Spring Security의 선언적 기능만으로 처리하지 않고 반드시 Service 계층 코드로 명시한다 — DB 관계 조회가 필요한 도메인 로직이기 때문이다.

---

## 5. JWT Security Policy

### 5.1 Secret Key / 환경 변수 관리

| 항목 | 정책 |
|---|---|
| Secret Key 저장 | 소스코드/설정 파일(`application.yml`)에 하드코딩 금지. 환경 변수(`JWT_SECRET`) 또는 운영 환경의 Secret Manager로 주입 |
| Git 관리 | `.env`, 실제 Secret이 담긴 `application-prod.yml`은 `../../.gitignore` 처리, 저장소에 커밋 금지 |
| Key 강도 | HS256 사용 시 최소 256bit 이상의 무작위 문자열 사용 (짧은 문자열은 Brute-force 위험) |
| 알고리즘 선택 | 단일 서버(Spring Boot)만 발급/검증하는 현재 구조에서는 HS256(대칭키)으로 충분. AI 서버 등 제3자가 서명 없이 "검증만" 해야 하는 시나리오가 생기면 RS256(비대칭키) 전환을 고려 |
| Key 회전(Rotation) | 운영 중 Secret 유출 의심 시 즉시 교체 가능하도록 Key 버전 관리(`kid` 클레임) 여지를 설계에 남겨둔다 |

### 5.2 Token 생성 정책

- Payload에는 `sub`, `role`, `iat`, `exp`, `jti`만 포함한다(3.1 참고).
- Access Token과 Refresh Token은 서로 다른 용도 클레임(`typ: access` / `typ: refresh`)을 구분해, Refresh Token을 Access Token 대신 API 인증에 사용하는 실수를 서버가 감지·거부할 수 있게 한다.

### 5.3 Token 검증 정책

검증 순서(하나라도 실패하면 인증 실패로 처리):

1. 서명(Signature) 검증
2. 만료 시간(`exp`) 검증
3. 토큰 타입(`typ`) 검증 — Access Token 필요 위치에 Refresh Token이 오면 거부
4. Redis Blacklist 조회(`jti` 기준) — 로그아웃/강제 만료된 토큰인지 확인

### 5.4 Expiration Time 기준

| 토큰 | 권장 만료 시간 | 근거 |
|---|---|---|
| Access Token | 15~30분 | 탈취 시 피해 시간 최소화, 위치 데이터 민감도 고려 |
| Refresh Token | 14일 | 보호자/보호대상자의 재로그인 빈도를 낮춰 사용성 확보, 대신 Redis 서버 측에서 통제 가능 |

### 5.5 Refresh Token 전략

- Redis에 `refresh:{userId}` 또는 `refresh:{jti}` 키로 저장, TTL을 만료 시간과 동일하게 설정해 자동 만료되도록 한다.
- Refresh 요청 시 기존 Refresh Token을 무효화하고 새 Refresh Token을 발급하는 Rotation을 적용한다. 이미 사용된(무효화된) Refresh Token이 재사용되면 탈취로 간주하고 해당 사용자의 모든 Refresh Token을 즉시 삭제(강제 로그아웃)한다.

### 5.6 Logout 처리 방식

1. 클라이언트가 `/api/auth/logout` 호출 시 현재 Access Token의 `jti`를 Redis Blacklist에 등록(TTL = 남은 만료 시간)한다.
2. 해당 사용자의 Refresh Token을 Redis에서 삭제한다.
3. 클라이언트는 로컬에 저장된 토큰을 즉시 폐기한다.

### 5.7 Token Blacklist 필요 여부 및 Redis 활용

JWT는 발급 즉시 서버가 개입 없이 자체 검증되는 구조이므로, "로그아웃"이라는 개념을 서버가 강제하려면 Blacklist가 **필수**다. 이 프로젝트는 다음과 같이 Redis를 활용한다.

| Redis 키 | 용도 | TTL |
|---|---|---|
| `refresh:{userId}` | Refresh Token 저장 | Refresh Token 만료 시간과 동일 |
| `blacklist:{jti}` | 로그아웃/강제 만료된 Access Token의 jti | 해당 Access Token의 남은 만료 시간 |

Blacklist 조회가 매 요청마다 Redis에 접근한다는 점에서 약간의 지연이 발생하지만, Access Token의 만료 시간이 짧아 Blacklist 항목의 수와 TTL이 크지 않으므로 성능 영향은 제한적이다.

---

## 6. OAuth2 Google Login Security

### 6.1 OAuth2 인증 흐름 (Authorization Code Flow 기반)

이 프로젝트는 Flutter 클라이언트에서 Google Sign-In SDK를 사용해 Google과 직접 인증하고, 그 결과로 얻은 ID Token만 Spring Boot로 전달하는 방식(모바일 앱에 적합한 방식)을 사용한다. 순수한 서버 사이드 Authorization Code Flow(Redirect 기반)는 웹 관리자 콘솔 등 별도 채널이 필요할 경우에 한해 적용을 검토한다.

```
1. Flutter: Google Sign-In SDK 실행 → 사용자 동의 → Google이 ID Token 발급
2. Flutter → Spring Boot: POST /api/auth/oauth/login { idToken }
3. Spring Boot: Google 공개키(JWK)로 ID Token 서명 검증, aud(Client ID) 검증, exp 검증
4. Spring Boot: email, sub, email_verified 추출
5. Spring Boot: 자체 JWT(Access/Refresh) 발급 → Flutter 반환
```

### 6.2 Client Secret 관리

- Google Client Secret은 서버(Spring Boot) 환경 변수로만 관리하며, Flutter 클라이언트 코드나 APK/IPA 내부에 절대 포함하지 않는다.
- Flutter는 Google Client ID(공개 값)만 사용하며, Client Secret이 필요한 흐름(서버 사이드 Authorization Code 교환)은 Spring Boot에서만 수행한다.

### 6.3 Redirect URI 관리

- 서버 사이드 Redirect 방식을 사용할 경우, Google Cloud Console에 등록된 Redirect URI와 서버 설정값이 정확히 일치하는지 배포 환경별(dev/prod)로 별도 관리한다.
- 와일드카드 Redirect URI 등록은 금지한다(오픈 리다이렉트를 통한 Authorization Code 탈취 방지).

### 6.4 OAuth2 SuccessHandler 처리

Spring Security의 OAuth2 Login 표준 흐름(`OAuth2LoginAuthenticationFilter` + `AuthenticationSuccessHandler`)을 그대로 사용하는 대신, 이 프로젝트는 Flutter가 Google ID Token을 직접 서버에 제출하는 API(`/api/auth/oauth/login`) 방식을 취하므로, "SuccessHandler"에 해당하는 로직은 별도 Custom Filter가 아닌 일반 Service 로직(`OAuthLoginService`)으로 구현한다. 처리 순서는 다음과 같다.

1. ID Token 검증
2. 이메일 검증(6.6)
3. 기존 회원 매핑(6.7) 또는 신규 가입 처리
4. JWT 발급 및 Refresh Token Redis 저장
5. 응답 반환 (JSON 포맷은 API Response Rule 참고)

### 6.5 JWT 발급 과정

Google 인증 성공 직후, Spring Boot는 자체 `sub`(내부 userId), `role`을 담은 JWT를 즉시 발급한다. Google이 발급한 ID Token/Access Token은 이 시점 이후 폐기하고 재사용하지 않는다(6.6~6.7 절차에만 사용).

### 6.6 이메일 검증 (계정 탈취 방지)

- Google ID Token의 `email_verified` 클레임이 `true`인 경우에만 로그인/가입을 허용한다. 미검증 이메일로는 가입을 차단한다.
- 이메일 자체를 계정 식별 기본 키로 사용하지 않는다. 대신 `oauth_provider`(google) + `oauth_id`(Google `sub`)를 고유 식별자로 사용한다. 이메일은 사용자가 Google 계정 설정에서 변경할 수 있는 값이므로, 이메일만으로 계정을 매핑하면 다른 사람이 동일 이메일로 재가입 시 계정을 탈취할 위험이 있다.

### 6.7 기존 회원 매핑 정책

| 상황 | 처리 |
|---|---|
| `oauth_provider` + `oauth_id`가 기존 회원과 일치 | 정상 로그인 처리 |
| `oauth_id`는 새로우나 이메일이 기존 회원과 동일 | 자동 병합하지 않는다. 잠재적 계정 탈취 시나리오이므로, 별도 본인 확인 절차 없이는 기존 계정과 자동으로 연결하지 않는다. **(현재 구현 기준)** `tracecare_schema_ddl_1.0.sql`의 `uq_user_email_active`(활성 사용자당 이메일 유일) 제약으로 인해 "신규 계정으로 처리"(동일 이메일 두 번째 계정 생성)는 DB 제약 위반이 되어 불가능하다 — 대신 `USER_002`(409, 이미 가입된 사용자)로 로그인을 거부한다. 별도 본인 확인을 거친 명시적 계정 연동 절차는 아직 구현하지 않았으며, 필요해지면 별도 기능으로 설계한다 |
| 최초 로그인 | User 엔티티 생성. 이 시점에는 Guardian/CareTarget Role이 미정 상태일 수 있으며, 최초 온보딩 단계에서 Role을 선택/배정하는 후속 API를 별도로 둔다 |

---

## 7. API Security 정책

### 7.1 입력 검증

| 수단 | 적용 위치 |
|---|---|
| `@Valid` / `@Validated` | Controller의 `@RequestBody`, `@ModelAttribute` 파라미터 |
| DTO 단위 Bean Validation | `@NotNull`, `@Email`, `@Pattern`, GPS 좌표 범위 등은 Custom `@ConstraintValidator` |
| SQL Injection 방어 | JPA는 Parameter Binding(PreparedStatement)이 기본이므로 `@Query`에 문자열 concat 금지. MyBatis 사용 구간은 `#{}` 파라미터 바인딩만 사용하고 `${}` 치환은 원칙적으로 금지(불가피한 경우 화이트리스트 검증 후 사용) |

상세 Validation Exception의 처리/응답 흐름은 Exception Handling Rule 문서를 참고한다. 본 절은 "무엇을 검증해야 하는가"까지만 규정한다.

### 7.2 요청 제한

| 항목 | 정책 |
|---|---|
| Rate Limit | 로그인/OAuth 엔드포인트, 위치 전송 API 등 악용 시 피해가 큰 API에 IP 또는 userId 기준 요청 빈도 제한 적용(예: Bucket4j + Redis 또는 Nginx `limit_req`) |
| IP 제한 | `/internal/**` 및 관리자 API는 내부망/특정 IP 대역에서만 접근 가능하도록 Nginx 레벨에서도 제한(이중 방어) |
| API 호출 제한 | AI 예측/LLM 호출은 비용과 직결되므로 사용자당 호출 횟수 제한을 Redis 카운터로 관리 |

### 7.3 CORS 정책

```java
CorsConfiguration config = new CorsConfiguration();
config.setAllowedOrigins(List.of("https://<프로덕션 관리자 웹 도메인>")); // 모바일 앱은 Origin 헤더 자체가 없는 경우가 많음
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
```

- 허용 Origin은 명시적 도메인만 등록하고 `*`(전체 허용)는 사용하지 않는다. 특히 `allowCredentials(true)`와 `*`는 함께 사용할 수 없다(브라우저 정책상 무효).
- Flutter 모바일 앱은 브라우저 CORS 정책의 적용을 받지 않으므로, CORS 설정은 주로 향후 추가될 수 있는 웹 관리자 콘솔을 대비한 것이다.

> **TODO(미구현)**: 실제 프로덕션 관리자 웹 도메인 값이 확정되면 위 `CorsConfigurationSource` 예시대로 `common.security`에 CORS 설정을 구현한다. 현재 웹 관리자 콘솔이 없어 보류 중이다(Flutter 모바일 클라이언트는 CORS 대상이 아니므로 이 보류가 모바일 API 동작에는 영향 없음).

### 7.4 CSRF 정책

- JWT 기반 Stateless 인증에서는 CSRF Protection을 **비활성화**한다(`http.csrf(csrf -> csrf.disable())`).
- **비활성화 이유**: CSRF 공격은 브라우저가 쿠키를 요청에 자동으로 실어 보내는 특성을 악용한다. 이 프로젝트는 인증 정보를 쿠키가 아닌 `Authorization: Bearer` 헤더로 클라이언트(Flutter)가 명시적으로 담아 보내므로, 공격자가 피해자 브라우저를 통해 임의로 이 헤더를 실어 보낼 수 없다. 즉 CSRF 공격의 전제 조건 자체가 성립하지 않는다.
- **Cookie 기반 인증과의 차이점**: 만약 향후 웹 관리자 콘솔에서 Refresh Token을 HttpOnly Cookie로 저장하는 방식을 도입한다면, 그 경로에 한해서는 CSRF Protection을 다시 활성화하거나 `SameSite=Strict/Lax` 쿠키 옵션과 CSRF Token 검증을 함께 적용해야 한다. 즉 "JWT를 헤더로 전달하는 API"와 "인증 정보를 쿠키로 전달하는 API"는 CSRF 위협 모델이 다르므로 동일한 정책을 적용하면 안 된다.

### 7.5 WebSocket 연결/구독 보안

REST API의 3단계 인가 구조(인증 → Role → 리소스 소유권, §4.5)를 WebSocket에도 동일하게 적용한다. WebSocket은 REST와 취약점의 성격이 다르므로 별도로 다룬다 — REST의 IDOR은 요청 1건이 뚫리는 것으로 끝나지만, WebSocket 구독(SUBSCRIBE)이 한 번 잘못 뚫리면 **연결이 유지되는 동안 데이터가 계속 유출**된다(OWASP_Security_Guide.md §1.1 A01:2025 참고).

#### 7.5.1 CONNECT 단계: 인증

- STOMP CONNECT 프레임의 헤더로 JWT를 전달받아 검증한다(`Authorization` 헤더와 동일한 Access Token 사용, 별도 WebSocket 전용 토큰 체계를 만들지 않는다).
- CONNECT 시점 인증 실패는 REST의 401과 동일하게 연결 자체를 거부한다.

```java
@Override
public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
        String token = accessor.getFirstNativeHeader("Authorization");
        Authentication auth = jwtTokenProvider.getAuthentication(token); // 검증 실패 시 예외
        accessor.setUser(auth);
    }
    return message;
}
```

#### 7.5.2 SUBSCRIBE 단계: 리소스 소유권 재검증 (핵심)

**CONNECT 인증과 SUBSCRIBE 인가를 같은 것으로 취급하지 않는다.** "로그인은 했다"와 "이 Topic을 구독해도 된다"는 별개의 검증이다 — REST에서 인증(1단계)과 리소스 소유권(3단계)을 분리하는 것과 동일한 이유다.

- `/topic/location/{careTargetId}` 처럼 클라이언트가 대상 id를 지정하는 공용 Topic 구조는 **가능하면 쓰지 않는다.** SUBSCRIBE 시점에 매번 소유권 검증을 빠뜨리지 않아야 하는 부담이 생기고, 검증 로직이 하나라도 누락되면 즉시 취약점이 된다.
- 대신 서버가 인증된 사용자 기준으로만 발행하는 **개인화 큐**(`convertAndSendToUser(userId, "/queue/location", payload)`)를 우선 사용한다. 이 구조에서는 "잘못된 id를 구독"하는 공격 자체가 성립하지 않는다 — 애초에 클라이언트가 다른 사용자의 큐를 지정할 방법이 없기 때문이다.
- 부득이하게 id 기반 공용 Topic을 써야 하는 경우, SUBSCRIBE 인터셉터에서 §4.5와 동일하게 Service 계층 조회(GuardianTarget 관계 확인)를 거쳐야 하며, 이 검증 없이 SUBSCRIBE를 허용하지 않는다.

```java
@Override
public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
        String destination = accessor.getDestination(); // 예: /topic/location/{careTargetId}
        String careTargetId = extractCareTargetId(destination);
        String guardianId = ((Authentication) accessor.getUser()).getName();
        if (!careTargetAuthService.isOwner(guardianId, careTargetId)) { // §4.5와 동일한 소유권 조회
            throw new AccessDeniedCustomException(ErrorCode.TARGET_002); // 3단계 인가 실패, Exception Handling Rule 8.1 3단계 처리
        }
    }
    return message;
}
```

#### 7.5.3 개발 체크리스트

□ CONNECT 시점에 JWT 검증이 이루어지는가 (REST와 동일한 토큰 체계 사용)
□ 공용 Topic(`/topic/location/{id}`) 대신 개인화 큐(`convertAndSendToUser`)를 우선 검토했는가
□ 부득이하게 공용 Topic을 쓴다면 SUBSCRIBE 시점에 리소스 소유권 검증이 빠짐없이 적용되는가
□ SUBSCRIBE 인가 실패가 REST의 3단계 인가 실패(403)와 동일한 원칙으로 처리되는가

---

## 8. Exception Handling Security

### 8.1 AuthenticationException 처리 (401 Unauthorized)

| 상황 | 예시 |
|---|---|
| 로그인 필요 | 인증 헤더 자체가 없는 상태로 보호된 API 접근 |
| JWT 없음 | `Authorization` 헤더 누락 |
| JWT 만료 | Access Token `exp` 초과 (별도 `AUTH_002` 코드로 구분, 5.3·3.1 참고) |
| JWT 변조 | 서명 검증 실패 |
| Blacklist 등록된 토큰 | 로그아웃/강제 만료된 토큰 재사용 시도 |

### 8.2 AccessDeniedException 처리 (403 Forbidden)

| 상황 | 예시 |
|---|---|
| 로그인은 성공했지만 권한 부족 | CareTarget이 `/api/guardian/**` 접근 |
| 리소스 소유권 불일치 | 타인의 CareTarget/Place 데이터 접근 시도 |

### 8.3 GlobalExceptionHandler와 Security Exception Handler 역할 구분

이 구분은 두 계층이 서로 다른 시점에 동작하기 때문에 반드시 필요하다.

| 구분 | 동작 시점 | 처리 대상 | 구현 위치 |
|---|---|---|---|
| Security Exception Handler (`AuthenticationEntryPoint`, `AccessDeniedHandler`) | `DispatcherServlet` **이전** (Filter 단계) | JWT 자체의 인증/인가 실패 (2.5~2.6) | Spring Security 설정(`SecurityConfig`) |
| GlobalExceptionHandler (`@RestControllerAdvice`) | `DispatcherServlet` **이후** (Controller/Service 단계) | 리소스 소유권 검증 실패 등 비즈니스 로직 중 발생하는 인가 예외, 그 외 모든 도메인/시스템 예외 | Exception Handling Rule 문서 참고 |

두 계층 모두 최종적으로는 동일한 응답 포맷(API Response Rule)을 사용해야 클라이언트가 오류 형식을 단일하게 처리할 수 있다. 따라서 `SecurityResponseWriter`(2.5~2.6에서 사용)와 GlobalExceptionHandler는 동일한 응답 직렬화 로직(공용 유틸)을 공유하도록 구현한다. 구체적인 응답 바디 구조와 Error Code 값은 API Response Rule 문서를 따른다.

---

## 9. OWASP Top 10 대응 정책

### A01:2025 Broken Access Control

| 위험 | 대응 |
|---|---|
| Role 없이 API 접근, 타인 리소스 접근 | RBAC(`ROLE_GUARDIAN`/`ROLE_CARE_TARGET`/`ROLE_ADMIN`) + URL 패턴 권한(4.2) + `@PreAuthorize`(4.4) + Service 계층 리소스 소유권 검증(4.5) 3중 방어 |
| Insecure Direct Object Reference (IDOR) | `/api/guardian/care-targets/{id}` 같은 경로 파라미터 접근 시 매번 "요청자-리소스 소유 관계"를 DB로 검증, id 값만으로 접근 허용하지 않음 |
| 내부 전용 API 외부 노출 | `/internal/**`은 Spring Security에서 `denyAll` + Nginx/네트워크 레벨 접근 제한 이중 적용(11장) |

### A02:2025 Security Misconfiguration

| 위험 | 대응 |
|---|---|
| 기본 설정 노출 | Spring Boot Actuator 엔드포인트는 운영 환경에서 인증 없이 노출하지 않음(`management.endpoints.web.exposure` 최소화) |
| Debug Mode | 운영 프로파일(`application-prod.yml`)에서 `spring.jpa.show-sql`, 상세 에러 페이지(`server.error.include-stacktrace=never`) 비활성화 |
| 불필요한 정보 노출 | 에러 응답에 스택 트레이스 미포함(Exception Handling Rule 12장과 연계) |
| CORS 과다 허용 | `*` Origin 금지(7.3) |

### A04:2025 Cryptographic Failures

| 위험 | 대응 |
|---|---|
| 통신 구간 평문 노출 | 전 구간 HTTPS 강제(Nginx Reverse Proxy에서 TLS Termination, HTTP→HTTPS 리다이렉트) |
| 민감 설정값 노출 | JWT Secret, Google Client Secret, LLM API Key 등은 환경 변수/Secret Manager로 관리(5.1) |
| 비밀번호 저장 | 이 프로젝트는 Google OAuth2 전용 로그인이므로 자체 비밀번호를 저장하지 않는 것이 원칙. 향후 로컬 계정 방식을 추가할 경우 `BCryptPasswordEncoder`(work factor 10 이상)로 해시 저장, 평문/양방향 암호화 저장 금지 |
| 저장 데이터 암호화 | 위치 이력 등 민감 데이터는 DB 접근 통제(권한 분리)와 함께, 필요 시 컬럼 단위 암호화 적용을 검토 |

### A05:2025 Injection

| 위험 | 대응 |
|---|---|
| SQL Injection | JPA Parameter Binding 기본 사용, MyBatis `#{}` 바인딩만 허용(7.1) |
| 입력값 미검증으로 인한 오동작 | DTO Validation(`@Valid`)으로 형식·범위 사전 차단(7.1, Validation 상세 흐름은 Exception Handling Rule 참고) |
| LLM Prompt Injection | AI Care Chat에 전달되는 사용자 입력을 Prompt에 그대로 삽입하지 않고, 시스템 프롬프트와 사용자 입력 영역을 명확히 분리, 길이 제한 적용(11장) |

### A07:2025 Authentication Failures

| 위험 | 대응 |
|---|---|
| 토큰 무한 유효 | 짧은 Access Token 만료(5.4) + Blacklist(5.7) |
| 계정 탈취 | OAuth2 이메일 검증(6.6), oauth_id 기반 매핑(6.6~6.7) |
| 무차별 대입(향후 로컬 계정 도입 시) | 로그인 시도 횟수 제한, CAPTCHA 등 도입 검토 |

### A09:2025 Security Logging and Alerting Failures

| 위험 | 대응 |
|---|---|
| 보안 이벤트 미기록 | 로그인 성공/실패, JWT 검증 실패, 권한 접근 실패, 관리자(Admin) 접근을 별도 기록(10장) |
| 로그를 통한 개인정보 유출 | 위치 좌표 원문, 토큰 원문, 얼굴 인증 데이터는 로그에서 마스킹/제외(10장) |

> 본 프로젝트에서 우선순위가 높은 A01/A02/A04/A05/A07/A09(2025 기준)를 위 표로 정리했다. 나머지 카테고리는 아키텍처/의존성 관리 영역이거나 이번 개정(2025)에서 신설된 항목이라 별도 검토가 필요하다(본 문서에서 중복 작성하지 않음).
> - A03:2025 Software Supply Chain Failures(신규), A06:2025 Insecure Design, A08:2025 Software or Data Integrity Failures: Architecture 문서 및 운영 체크리스트에서 별도 관리
> - **A10:2025 Mishandling of Exceptional Conditions(신규)**: 이번 개정에서 새로 생긴 카테고리로, 내용상 `Exception Handling Rule` 문서가 이미 다루는 영역(예외 삼킴, 부적절한 Fallback, 미분류 예외)과 직접 겹친다. Exception Handling Rule 쪽에서 이 카테고리 대응表를 추가하는 걸 검토할 필요가 있다 — 이 문서에서 임의로 추가하지 않는다(문서 책임 경계, Exception Handling Rule 담당)

---

## 10. Logging Security Policy

### 10.1 기록 대상 (보안 이벤트)

| 이벤트 | 기록 항목 |
|---|---|
| 로그인 성공 | userId, role, 로그인 시각, (필요 시) 대략적 지역 정보 |
| 로그인 실패 | 시도 시각, 실패 사유(이메일 미검증 등), 요청 IP |
| JWT 검증 실패 | 실패 사유(만료/서명불일치/Blacklist), 요청 URI, 요청 IP |
| 권한 접근 실패 (403) | userId, role, 요청 URI, 요구된 권한 |
| 관리자(Admin) 접근 | 모든 `/api/admin/**` 호출을 별도 감사 로그로 기록(누가, 언제, 무엇을) |

### 10.2 절대 기록하면 안 되는 정보

- Password (원문/해시 모두 로그에 남기지 않음)
- JWT Token 전체 문자열 (필요 시 `jti` 또는 토큰 앞 8자리 정도의 축약 해시만 기록)
- Refresh Token 원문
- 개인정보 전체 (이름, 전화번호, 정확한 GPS 좌표 원문 — 위치 데이터는 마스킹하거나 별도 감사 전용 스토리지로 분리)
- Google ID Token / OAuth Access Token 원문
- 얼굴 인증에 사용되는 이미지/특징 데이터

### 10.3 Security 로그와 일반 Exception 로그의 관계

본 절은 "보안 관점에서 무엇을 남겨야 하는가"를 규정하며, 로그 레벨 체계·traceId 연계 등 로깅 구현 전반의 상세 기준은 Exception Handling Rule의 Logging 보안 기준(12장)을 따른다. 두 문서의 로깅 원칙(민감정보 미기록, 로그와 응답 분리)은 동일한 원칙을 공유한다.

---

## 11. AI Server Communication Security

Python FastAPI AI 서버는 사용자가 직접 호출하지 않고 Spring Boot를 경유해서만 호출되는 신뢰 경계 안쪽의 서비스다. 이 절은 Spring Boot ↔ FastAPI 간 Server-to-Server 통신의 보안 기준을 정의한다.

### 11.1 API Key 인증

- Spring Boot가 FastAPI `/internal/*` 엔드포인트 호출 시 `X-Internal-Api-Key` 헤더에 사전 공유된 Server-to-Server API Key를 담아 전송한다.
- 사용자 JWT를 그대로 AI 서버에 전달하지 않는다(1.5 참고) — AI 서버는 사용자 신원이 아니라 "Spring Boot로부터의 요청인가"만 검증하면 되므로 책임을 분리한다.
- API Key는 환경 변수로 관리하며 정기적으로 교체(Rotation)한다.

### 11.2 Internal Network 통신

- `/internal/**` 엔드포인트는 Spring Security에서 `denyAll`로 외부 직접 접근을 차단하는 것과 별개로, 인프라 레벨(Docker Compose 내부 네트워크, 또는 배포 환경의 보안 그룹/방화벽)에서도 FastAPI 서버가 Spring Boot가 위치한 네트워크 대역 외부로 노출되지 않도록 구성한다(외부 인터넷에서 FastAPI 포트에 직접 접근 불가).

### 11.3 Request Signature 검증 (선택적 강화)

API Key 유출 시의 재사용 공격(Replay Attack)까지 방어하려면, 요청 바디 + 타임스탬프를 HMAC으로 서명해 `X-Signature` 헤더로 함께 전달하고 FastAPI에서 서명과 타임스탬프 유효 시간(예: 5분 이내)을 함께 검증하는 방식을 추가로 검토한다. 프로젝트 초기 단계에서는 API Key + Internal Network 격리만으로도 충분한 방어 수준을 확보할 수 있으므로, Request Signature는 운영 단계에서 위협 수준에 따라 도입 여부를 결정하는 확장 옵션으로 둔다.

### 11.4 Timeout 설정

- Spring Boot → FastAPI 호출 시 connect/read timeout을 반드시 설정한다(예: connect 2초, read 5~10초). AI 예측/LLM 응답 생성은 지연이 발생할 수 있는 작업이므로, timeout 미설정 시 Spring Boot의 요청 처리 스레드가 고갈될 위험이 있다.
- Timeout 발생 시의 예외 처리(재시도, 사용자 응답 방식)는 Exception Handling Rule의 External Service Exception 처리 기준을 따른다.

### 11.5 입력 데이터 검증

- Spring Boot는 FastAPI로 전달하기 전에 요청 데이터(CareTarget ID, 좌표 범위, 채팅 메시지 길이 등)를 1차 검증한다(7.1 기준과 동일).
- FastAPI 측에서도 Spring Boot로부터 온 요청이라는 이유로 무조건 신뢰하지 않고, 자체적으로 입력 스키마(Pydantic 모델) 검증을 수행한다(신뢰 경계 안이라도 방어적 코딩 원칙 적용).
- LLM API에 전달되는 사용자 발화는 시스템 프롬프트와 명확히 분리하고, 길이 제한 및 금칙어 필터링을 적용해 Prompt Injection으로 인한 시스템 프롬프트 노출·오작동을 방지한다(9장 A05 참고).

---

## 12. 개발 체크리스트

□ 모든 API 인증 여부 확인 (`permitAll` 대상이 의도된 공개 API인지 재검토)
□ Role 권한 분리 적용 (`ROLE_GUARDIAN` / `ROLE_CARE_TARGET` / `ROLE_ADMIN` URL 패턴 매핑 확인)
□ 리소스 소유권 검증 로직 존재 여부 확인 (Role만으로 판단하지 않았는지 — IDOR 점검)
□ JWT Secret 등 민감 설정값 환경 변수 처리 (`../../.gitignore` 확인)
□ Access/Refresh Token 만료 시간이 정책값과 일치하는지 확인
□ Refresh Token Rotation 및 재사용 감지 로직 적용
□ JWT Blacklist(Redis) 로그아웃 시 정상 등록되는지 확인
□ Google OAuth2 이메일 검증(`email_verified`) 로직 적용
□ oauth_provider + oauth_id 기반 계정 매핑(이메일 단독 매핑 금지) 확인
□ PasswordEncoder 적용 여부 (로컬 계정 도입 시)
□ CORS 허용 Origin이 `*`가 아닌 명시적 도메인인지 확인
□ CSRF 비활성화가 JWT Stateless 구조에서만 적용되고 있는지 확인 (쿠키 기반 경로 추가 시 재검토)
□ `/internal/**` 외부 노출 차단(Spring Security + 네트워크 레벨) 이중 확인
□ AI 서버 호출부 timeout 설정 확인
□ Exception Response 통일 (Security Exception Handler와 GlobalExceptionHandler가 동일 응답 포맷 사용, 8.3 참고)
□ 민감정보 로그 제거 (Password, Token 원문, 위치 좌표 원문, 얼굴 인증 데이터)
□ Actuator 등 운영 환경 불필요 엔드포인트 노출 차단
□ 운영 환경 스택 트레이스/디버그 정보 비활성화
□ OWASP Top 10 대응 표(9장) 항목별 실제 구현 여부 재점검
