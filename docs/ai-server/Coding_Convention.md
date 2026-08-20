# AI Server Coding Convention
>해당파일 경로 docs/ai-server/Coding_Convention.md

프로젝트: TraceCare — 아이·노인 케어 위치추적 알림 시스템
문서 위치: `docs/ai-server/Coding_Convention.md`
담당 서버: FastAPI AI Server (`apps/ai-server/`) — 방문 예측(XGBoost/LightGBM), AI Care Chat(LLM 연동)
버전: v1.0 — **일반적인 Python/FastAPI 추천안**(팀 확정 규칙 아님, 실제 개발 착수 시 재검토)

> 이 문서는 **Python 코드를 어떤 스타일로 작성할 것인가**를 담당한다.
> Spring Boot ↔ FastAPI 통신 보안(API Key, Timeout)은 `docs/security/Security_Guide.md` 11장, 외부 연동 실패 시 예외 처리는 `docs/backend/Exception_Handling_Rule.md` 9장을 따르며, 이 문서는 그 결과를 실제 FastAPI 코드로 어떻게 쓸지만 다룬다.

---

## 목차

1. 프로젝트 구조
2. 네이밍 규칙
3. 타입 힌트 / Pydantic
4. 포맷터 / 린터
5. 예외 처리
6. 로깅
7. ML 모델 관리
8. 테스트
9. 개발 체크리스트

---

## 1. 프로젝트 구조 (추천안)

```
apps/ai-server
 ├─ app
 │   ├─ main.py                 # FastAPI 앱 진입점
 │   ├─ api
 │   │   └─ v1
 │   │       ├─ predict.py       # /internal/ai/predict
 │   │       └─ chat.py          # /internal/llm/chat
 │   ├─ core
 │   │   ├─ config.py            # 환경변수/설정 (Pydantic Settings)
 │   │   └─ security.py          # Server-to-Server API Key 검증 (Security_Guide.md 11.1)
 │   ├─ schemas                  # Pydantic 요청/응답 모델
 │   ├─ services                 # 비즈니스 로직(예측 실행, LLM 호출)
 │   ├─ models                   # 학습된 ML 모델 로딩/추론 래퍼 (model.pkl 등)
 │   └─ exceptions.py            # Custom Exception 정의
 ├─ tests
 └─ pyproject.toml
```

Spring Boot 쪽의 도메인 패키지 구조(`Coding_Convention.md`(backend) 1장)와 이름을 맞출 필요는 없다 — FastAPI는 "예측/LLM"이라는 기능 축으로만 나뉘므로 도메인이 아니라 API 축으로 구조화한다.

## 2. 네이밍 규칙

| 대상 | 규칙 | 예시 |
|---|---|---|
| 변수/함수 | snake_case | `get_prediction_result` |
| 클래스 | UpperCamelCase | `PredictionService` |
| 상수 | UPPER_SNAKE_CASE | `DEFAULT_TIMEOUT_SECONDS` |
| 모듈/파일명 | snake_case | `predict_service.py` |
| Pydantic 모델 | `{용도}Request`/`{용도}Response`(Spring Boot DTO 네이밍과 동일한 접미사 규칙으로 통일) | `PredictRequest`, `PredictResponse` |

## 3. 타입 힌트 / Pydantic

- 모든 함수 시그니처에 타입 힌트를 명시한다. `Any`/raw `dict`로 요청을 받지 않는다(Security_Guide.md §11.5 "FastAPI 측에서도 자체 Pydantic 모델 검증을 수행한다"와 연계).
- 요청/응답은 반드시 Pydantic 모델(`BaseModel`)로 정의한다.
- Python 3.10+ 기준 `Optional[X]` 대신 `X | None` 문법을 쓴다(버전이 낮으면 이 규칙 보류).

```python
from pydantic import BaseModel, Field

class PredictRequest(BaseModel):
    care_target_id: str = Field(..., description="CareTarget public_id (UUID)")
    target_date: str

class PredictResponse(BaseModel):
    predicted_place: str
    probability: float
```

## 4. 포맷터 / 린터

| 도구 | 용도 |
|---|---|
| `ruff` | 린팅 + import 정렬(isort 대체), 빠른 속도로 CI에 적합 |
| `black` | 코드 포맷팅(줄바꿈, 따옴표 등 자동 정리) |
| `mypy` | 정적 타입 검사(선택, 팀 여유가 되면 CI에 추가) |

세 도구를 pre-commit hook 또는 CI에 걸어 사람이 스타일을 리뷰에서 지적하지 않게 한다.

## 5. 예외 처리

- FastAPI의 `HTTPException`을 여기저기서 직접 던지지 않고, Custom Exception(`app/exceptions.py`)을 정의한 뒤 `@app.exception_handler()`로 중앙 처리한다 — Spring Boot의 `GlobalExceptionHandler` 패턴과 동일한 사상이다.
- Spring Boot로 반환하는 에러 응답은 API_Response_Rule.md 포맷을 억지로 따라갈 필요는 없다(`/internal/**`는 서버 간 통신이라 API_Response_Rule.md 적용 범위 밖, 문서 헤더 §"적용 범위" 참고) — 다만 최소한 `{"error_code": "...", "message": "..."}` 형태로 Spring Boot가 파싱하기 쉬운 일관된 구조는 유지한다.
- 외부 API(Hugging Face, LLM API) 호출 실패는 반드시 timeout을 설정하고(Security_Guide.md §11.4), 재시도 후에도 실패하면 명확한 에러 코드로 Spring Boot에 전달한다.

## 6. 로깅

- Python 표준 `logging` 모듈 + `structlog`(구조화 로그, JSON 포맷) 조합을 권장한다 — Logging_Guide.md §13의 JSON Log Format(`timestamp`/`level`/`service`/`traceId` 등)과 필드를 맞춘다.
- `service` 필드는 `"ai-server"`로 고정해 Spring Boot 로그와 구분한다(Logging_Guide.md §13.1).
- Spring Boot가 보낸 `X-Trace-Id` 헤더를 수신해 그대로 로그의 `traceId` 필드로 사용한다(Logging_Guide.md §14.3에서 이미 이 프로젝트의 역할 분담으로 정의됨).
- 절대 금지: 개인정보(이름/연락처), 얼굴 인증 이미지, 원본 GPS 좌표, LLM에 전달된 사용자 발화 원문(Security_Guide.md §11.5, Logging_Guide.md §11.2와 동일 원칙).

## 7. ML 모델 관리

- 학습된 모델(`model.pkl`)은 코드 저장소에 커밋하지 않는다. Hugging Face Spaces 또는 별도 아티팩트 저장소에서 배포 시점에 로드한다.
- 모델 버전과 학습 데이터 시점을 로그/응답 메타데이터에 남겨, 예측 결과가 어떤 모델 버전에서 나왔는지 추적 가능하게 한다.
- 모델 로딩은 애플리케이션 시작 시 1회만 수행하고(요청마다 다시 로드하지 않음), 싱글턴으로 관리한다.

## 8. 테스트

- `pytest` 사용. 테스트 파일명은 `test_{대상모듈}.py`.
- FastAPI `TestClient`로 엔드포인트 통합 테스트를 작성하고, ML 추론 로직은 실제 모델 대신 Mock으로 대체해 테스트 속도를 확보한다.
- Pydantic 모델 검증 실패 케이스(잘못된 타입/누락 필드)를 반드시 테스트에 포함한다.

## 9. 개발 체크리스트

- [ ] 모든 API가 Pydantic 모델로 요청/응답을 검증하는가 (raw dict 금지)
- [ ] `ruff`/`black` 포맷 검사를 통과하는가
- [ ] 외부 API 호출에 timeout이 설정돼 있는가
- [ ] 로그에 개인정보/원본 좌표/LLM 입력 원문이 없는가
- [ ] `X-Trace-Id`를 수신해 로그에 반영하는가
- [ ] 모델 파일이 저장소에 커밋되지 않았는가
