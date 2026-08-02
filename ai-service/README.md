# FinGuardOps AI Service

FinGuardOps의 Feature·Rule·ML 및 AI 리포트 계산 책임을 담당할 FastAPI 서비스이다. 현재 구현 범위는 애플리케이션 설정, Health API, R001~R004 Rule v1 개별 순수 evaluator, 불변 RuleEvaluatorRegistry와 ordered raw evaluator 실행을 담당하는 RuleExecutionOrchestrator이다. RuleVersion 연결·점수 계산·Spring Boot 연동·외부 시스템 연결은 포함하지 않는다.

## 개발 환경

- CPython 3.12
- `uv` 기반 의존성 및 가상환경 관리
- FastAPI와 Pydantic v2
- pytest
- Ruff

Python 지원 범위는 `>=3.12,<3.13`이며 `.python-version`과 `pyproject.toml`에 명시한다. `uv.lock`은 런타임 및 개발 의존성의 정확한 버전을 고정한다.

## 설치

`uv`를 설치한 뒤 이 디렉터리에서 잠금 파일 기준으로 환경을 동기화한다.

```shell
cd ai-service
uv sync --locked
```

## 로컬 실행

```shell
uv run --locked uvicorn finguardops_ai.main:app --reload
```

서비스가 시작되면 다음 요청으로 상태를 확인할 수 있다.

```http
GET http://127.0.0.1:8000/api/health
```

정상 응답은 `200 OK`와 다음 JSON이다.

```json
{
  "status": "UP",
  "service": "ai-service"
}
```

## 테스트와 품질 검사

```shell
uv run --locked ruff check .
uv run --locked ruff format --check .
uv run --locked pytest
```

필요한 경우 차단 기준 없이 coverage를 확인할 수 있다.

```shell
uv run --locked pytest --cov=finguardops_ai --cov-report=term-missing
```

## 설정 원칙

환경 설정은 `finguardops_ai.core.config`에서 관리하며 환경 변수 접두사는 `FINGUARDOPS_AI_`이다. 현재 외부 시스템 접속 설정은 없으며 애플리케이션 import 시 네트워크나 저장소에 연결하지 않는다.

시각을 추가하는 후속 구현은 timezone-aware UTC를 사용한다. API Key, Token, Password, DB 접속 정보, 개인정보 및 금융 식별자 원문은 코드·문서·로그에 기록하지 않는다.

## Rule v1 evaluator

`finguardops_ai.rules.v1`은 공식 Rule v1 계약의 R001~R004를 외부 시스템과 애플리케이션 전역 상태에 의존하지 않는 개별 evaluator로 제공한다.

RuleEvaluatorRegistry의 RuleId는 AI Service가 구현한 evaluator capability를 식별하는 내부 ID이다. DB의 `ruleCode`와 같은 개념이 아니며 활성 Rule 목록이나 적용 기간을 나타내지 않는다.

- 금액은 `Decimal`을 사용한다.
- 거래와 행동 시각은 timezone-aware UTC만 허용한다.
- 행동 시간창은 거래 `occurredAt` 기준 `[T-86400초, T]` 양끝을 포함한다.
- 입력 모델과 행동 이벤트 모음은 불변이다.
- 결과에는 Rule ID, 적중 여부와 판정에 사용한 최소 내부 사실만 포함한다.

내부 Rule 실행 오케스트레이션의 책임, 입력·출력, validation, 순차 실행과
fail-fast 정책은
[Rule 실행 오케스트레이션 내부 계약](../docs/01-requirements/rule-execution-orchestration-contract.md)에
문서로 정의되어 있다.

- Rule 실행 오케스트레이션 계약: 문서 정의 완료
- `RuleExecutionOrchestrator`: 구현 완료
- 점수 합산·위험 등급·Evidence 변환: 후속 범위

raw evaluator orchestration은 구현되었지만 RuleVersion 연결, 점수 산정, 위험
등급, Evidence·Reason Code 변환, 외부 API와 Spring Boot 연동은 후속 범위이다.
계약 문서가 존재한다는 사실만으로 후속 기능까지 구현된 것으로 간주하지 않는다.
