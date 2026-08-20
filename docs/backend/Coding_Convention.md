# Backend Coding Convention
>해당파일 경로 docs/backend/Coding_Convention.md

프로젝트: TraceCare — 아이·노인 케어 위치추적 알림 시스템
문서 위치: `docs/backend/Coding_Convention.md`
담당 서버: Spring Boot Backend (`../../apps/backend`)
버전: v1.0

> 이 문서는 **Java/Spring Boot 코드를 어떤 스타일로 작성할 것인가**(네이밍, 패키지 구조, 어노테이션 사용, 테스트 작성법)를 담당한다.
> "무엇을 왜 이렇게 설계했는가"는 이미 완료된 `API_Response_Rule.md`, `Exception_Handling_Rule.md`, `Logging_Guide.md`, `Cache_Strategy_Guide.md`, `Security_Guide.md`가 담당하며, 이 문서는 그 설계들을 코드로 옮길 때 팀 전체가 같은 스타일을 쓰도록 통일하는 역할만 한다. 설계 내용을 다시 설명하지 않는다.

---

## 목차

1. 패키지 구조
2. 네이밍 규칙
3. 클래스 작성 규칙
4. DTO / Entity 분리 원칙
5. Lombok 사용 기준
6. Controller 작성 규칙
7. Service 작성 규칙
8. 코드 포맷팅
9. 테스트 작성 규칙
10. 개발 체크리스트

---

## 1. 패키지 구조

### 1.1 루트 패키지

```
com.tracecare.backend
```

레포 루트명(`traceCare`)을 기준으로 하고, `../../apps/backend` 폴더와 1:1 대응시킨다(다른 서비스 코드와 섞이지 않게 하기 위함). 기존 문서들에 있던 `com.gis.care`/`com.gis.backend` 표기는 이 값으로 통일했다(2026-08 결정).

### 1.2 전체 구조 (도메인 + 공통 모듈)

이미 완료된 문서들이 각자 자기 영역의 패키지만 보여줬으므로(`common.response`, `common.exception`, `common.cache` 등), 여기서 전체 그림을 한 번에 정리한다.

```
com.tracecare.backend
 ├─ common                      # 도메인에 속하지 않는 공통 모듈
 │   ├─ response                # ApiResponse, PageResponse (API_Response_Rule.md §6.2)
 │   ├─ exception                # ErrorCode, BusinessException 계층 (Exception_Handling_Rule.md §3~4)
 │   ├─ security                 # JwtAuthenticationFilter, EntryPoint/AccessDeniedHandler (Security_Guide.md)
 │   └─ cache                    # CacheKeyGenerator, CacheKeys, RedisConfig (Cache_Strategy_Guide.md §8.1)
 │
 ├─ domain                      # 도메인별 패키지 (API_Specification.md 도메인과 1:1 대응)
 │   ├─ auth                     # 로그인, Role 확정, 토큰 재발급
 │   ├─ guardian                 # 보호자 프로필, 보호대상자 관리
 │   ├─ caretarget                # 보호대상자 프로필, 위치 전송, 도착 확인, 긴급 연락
 │   ├─ location                 # 위치 조회/전송, LocationHistory
 │   ├─ place                    # 장소(안심구역), GeoFence
 │   ├─ visit                    # 방문 히스토리(VisitHistory)
 │   ├─ notification              # 알림 조회/발송(NotificationHistory)
 │   └─ ai                       # AI 예측/케어 비서 연동(FastAPI 호출)
 │
 └─ TracecareBackendApplication.java
```

각 도메인 패키지 내부는 아래 하위 구조를 동일하게 반복한다.

```
domain/guardian
 ├─ controller     # GuardianController, CareTargetController
 ├─ service        # GuardianService
 ├─ repository     # GuardianRepository (Spring Data JPA)
 ├─ entity         # Guardian, GuardianTarget
 └─ dto
     ├─ request    # CareTargetRegisterRequest
     └─ response   # CareTargetResponse
```

### 1.3 패키지 배치 규칙

- 새 클래스를 어느 도메인에 둘지 애매하면, "이 클래스가 다루는 API_Specification.md 도메인이 뭔지"로 판단한다(예: `ARRIVAL` 도메인 코드는 `caretarget` 패키지 하위).
- 여러 도메인이 공유하는 로직(예: Guardian-CareTarget 관계 조회)은 관계의 주인 쪽(`guardian`)에 둔다. 양쪽에 중복 구현하지 않는다.
- `common` 패키지에는 특정 도메인 로직을 넣지 않는다. "도메인 이름을 몰라도 이해되는 코드"만 `common`에 둔다.

---

## 2. 네이밍 규칙

| 대상 | 규칙 | 예시 |
|---|---|---|
| 클래스(일반) | UpperCamelCase | `CareTargetService` |
| 메서드/변수 | lowerCamelCase | `getCareTargetById` |
| 상수 | UPPER_SNAKE_CASE | `DEFAULT_PAGE_SIZE` |
| 패키지 | 전부 소문자, 단어 구분 없이 붙여쓰기(Java 관례) | `caretarget`, 언더스코어/하이픈 금지 |
| Request DTO | `{동작}{도메인}Request` | `CareTargetRegisterRequest` |
| Response DTO | `{도메인}Response` (목록은 `PageResponse<T>`로 감쌈, API_Response_Rule.md §2.2) | `CareTargetResponse` |
| Entity | 테이블명과 동일(DATABASE_DESIGN_GUIDE.md 기준) | `GuardianTarget`, `LocationHistory` |
| Custom Exception | `{상황}Exception` (Exception_Handling_Rule.md §5.2 규칙 그대로) | `CareTargetNotFoundException` |
| Repository | `{Entity명}Repository` | `PlaceRepository` |
| Service 인터페이스를 따로 두지 않는 경우 | `{도메인}Service` (구현체 접미사 `Impl` 등을 붙이지 않음 — 인터페이스/구현 분리가 필요할 때만 예외) | `LocationService` |

---

## 3. 클래스 작성 규칙

- 클래스 하나는 책임 하나만 가진다. Controller가 비즈니스 로직을 갖거나, Service가 HTTP 관련 코드를 갖지 않는다.
- 필드 선언 순서: `static final` 상수 → 의존성 필드(`private final`) → 일반 필드. 그룹 사이에 빈 줄을 둔다.
- 생성자 주입만 사용한다. `@Autowired` 필드 주입을 쓰지 않는다(테스트 용이성, 불변성 확보 목적). Lombok `@RequiredArgsConstructor`로 대체한다(5장).
- 매직 넘버/문자열을 코드에 직접 쓰지 않는다. 상수로 추출하거나 설정값(`application.yml`)으로 뺀다.

---

## 4. DTO / Entity 분리 원칙

- Controller는 절대 Entity를 직접 반환하지 않는다(API_Response_Rule.md §6.2에서 이미 확정된 원칙). 응답은 항상 Response DTO로 변환한다.
- Entity → DTO 변환은 Entity 자신의 정적 팩토리 메서드(`CareTargetResponse.from(entity)`)로 통일한다. Controller나 Service 안에서 필드를 하나씩 꺼내 조립하지 않는다.
- Request DTO에는 Bean Validation 애노테이션(`@NotNull`, `@Valid` 대상)을 직접 붙인다(Security_Guide.md §7.1과 연계).
- Entity에는 Setter를 열어두지 않는다. 상태 변경은 의미 있는 이름의 메서드(`place.update(request)`)로 캡슐화한다.
- **테이블/컬럼명은 항상 명시한다(자동 변환에 의존하지 않음)**: 이 프로젝트는 `docs/db/tracecare_schema_ddl_1.0.sql`이 모든 테이블을 대소문자 혼용 Quoted 식별자(`"User"` 등)로 정의하므로, `spring.jpa.hibernate.naming.physical-strategy`를 Spring Boot 기본 전략이 아닌 `PhysicalNamingStrategyStandardImpl`로 설정했다(`User` 엔티티 매핑 시 기본 전략이 테이블명을 강제로 소문자 변환해 실제 DB 테이블을 찾지 못하는 문제가 있었음). 이 전략은 Spring Boot 기본 전략과 달리 **camelCase→snake_case 자동 변환을 하지 않으므로**, 모든 Entity 필드는 `@Column(name = "실제_컬럼명")`을 생략 없이 명시한다(PK 포함). 테이블명도 `@Table(name = "실제_테이블명")`으로 DDL과 정확히 일치시킨다. 하나라도 생략하면 Java 필드명이 그대로(변환 없이) 컬럼명으로 쓰여 조용히 매핑이 깨질 수 있다.

---

## 5. Lombok 사용 기준

지금까지 문서들의 코드 예시(`@Getter`, `@Builder`)와 일관되게, 아래 조합만 표준으로 허용한다.

| 대상 | 허용 어노테이션 | 금지 |
|---|---|---|
| DTO(Request/Response) | `@Getter`, `@Builder`, `@AllArgsConstructor`(Builder와 함께 쓸 때만) | `@Data`(Setter까지 열려서 불변성이 깨짐) |
| Entity | `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`(JPA 요구사항) | `@Setter`, `@Builder`를 Entity에 직접(대신 정적 팩토리 메서드 사용), `@Data` |
| Service/Controller | `@RequiredArgsConstructor`(생성자 주입) | 없음 |
| 예외 클래스 | 없음(Exception_Handling_Rule.md §5.1 기준대로 명시적 생성자 작성) | Lombok 생성자 어노테이션 |

`@Data`를 프로젝트 전체에서 금지하는 이유: `equals`/`hashCode`/`toString`을 한 번에 열어주는데, Entity의 연관관계 필드까지 포함되면 무한 루프나 N+1을 유발하기 쉽다.

---

## 6. Controller 작성 규칙

- 반환 타입은 항상 `ApiResponse<T>`(API_Response_Rule.md §6.2).
- URL 패턴은 API_Specification.md에 정의된 URI를 그대로 쓴다. Controller를 작성하면서 URI를 임의로 바꾸지 않는다 — 바꿔야 하면 API_Specification.md를 먼저 수정한다.
- 인증된 사용자 정보는 `@AuthenticationPrincipal CustomUserDetails user`로만 받는다. 클라이언트가 보낸 `userId` 파라미터를 그대로 신뢰해 조회 조건으로 쓰지 않는다(Security_Guide.md §4.5 IDOR 방지 원칙).
- 하나의 Controller 메서드는 하나의 API만 처리한다. 여러 URI를 한 메서드에서 분기하지 않는다.

```java
@RestController
@RequestMapping("/api/guardian/care-targets")
@RequiredArgsConstructor
public class CareTargetController {

    private final CareTargetService careTargetService;

    @GetMapping
    public ApiResponse<PageResponse<CareTargetResponse>> getCareTargets(
            @AuthenticationPrincipal CustomUserDetails user,
            Pageable pageable) {
        return ApiResponse.success(SuccessCode.TARGET_001,
                careTargetService.getCareTargets(user.getUserId(), pageable));
    }
}
```

---

## 7. Service 작성 규칙

- `@Transactional`은 Service 클래스 또는 메서드에만 선언한다(Exception_Handling_Rule.md §11.1).
- 조회 전용 메서드는 `@Transactional(readOnly = true)`를 명시한다.
- 외부 호출(FCM, FastAPI, Google API)과 DB 트랜잭션을 같은 메서드에서 묶지 않는다 — 트랜잭션 범위 최소화 원칙(Exception_Handling_Rule.md §11.1)을 지킨다.
- 리소스 소유권 검증(3단계 인가, Security_Guide.md §4.5)은 반드시 Service 계층에서 명시적으로 수행하고, `AccessDeniedCustomException`을 던진다. Controller나 Repository 조건절에 묻어서 처리하지 않는다.
- Repository/외부 클라이언트가 던진 저수준 예외는 Service에서 반드시 도메인 예외로 변환한다(Exception_Handling_Rule.md §2 계층별 책임 분리).

---

## 8. 코드 포맷팅

| 항목 | 기준 |
|---|---|
| 들여쓰기 | 스페이스 4칸(탭 금지) |
| 줄 길이 | 120자 권장 |
| Import 순서 | `java` → `javax/jakarta` → 외부 라이브러리 → `com.tracecare.backend` 순, 각 그룹 사이 빈 줄. Wildcard import(`import java.util.*`) 금지 |
| 포맷터 | Google Java Format 또는 Spotless(Gradle 플러그인)를 CI에 걸어 자동 검사. 스타일 논쟁을 사람이 리뷰에서 하지 않는다 |
| 빌드 도구 | Gradle(Kotlin DSL 또는 Groovy 중 택1, 프로젝트 전체 통일) |

---

## 9. 테스트 작성 규칙

- 프레임워크: JUnit 5 + Mockito + AssertJ.
- 테스트 클래스명: `{대상클래스}Test`(단위) / `{대상클래스}IntegrationTest`(통합).
- 테스트 메서드명은 `한글로 상황을 설명`하거나(`@DisplayName` 사용) `should_결과_when_조건` 형식 중 하나로 팀 전체 통일한다.
- Given-When-Then 3단 주석으로 구분해 작성한다.
- Service 단위 테스트는 Repository/외부 클라이언트를 Mock으로 대체하고, 리소스 소유권 검증(6장) 같은 분기 로직은 반드시 실패 케이스도 함께 작성한다.
- Exception 발생 케이스는 `assertThatThrownBy(...).isInstanceOf(CareTargetNotFoundException.class)` 형태로 어떤 Custom Exception이 던져지는지까지 검증한다(ErrorCode 하드코딩 문자열 비교 대신 예외 타입으로 검증).

```java
@Test
@DisplayName("존재하지 않는 CareTarget 조회 시 CareTargetNotFoundException을 던진다")
void getCareTarget_notFound_throwsException() {
    // given
    given(careTargetRepository.findByPublicId(anyString())).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> careTargetService.getCareTarget(guardianId, careTargetId))
        .isInstanceOf(CareTargetNotFoundException.class);
}
```

---

## 10. 개발 체크리스트

- [ ] 새 클래스가 올바른 도메인 패키지(1장)에 위치하는가
- [ ] DTO/Entity 네이밍이 2장 표를 따르는가
- [ ] Controller가 Entity를 직접 반환하지 않는가
- [ ] Entity에 `@Setter`/`@Data`를 쓰지 않았는가
- [ ] Service의 외부 호출과 DB 트랜잭션이 분리돼 있는가
- [ ] 리소스 소유권 검증이 Service 계층에 명시적으로 있는가
- [ ] 새 테스트에 실패 케이스(예외 발생)가 포함돼 있는가
- [ ] 포맷터(Spotless 등) 검사를 통과하는가
