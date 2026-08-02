# FinGuardOps AI Service

FinGuardOps의 Feature·Rule·ML 및 AI 리포트 계산 책임을 담당할 FastAPI 서비스의 초기 개발 기반이다. 현재 구현 범위는 애플리케이션 설정과 Health API까지이며, Rule 실행·점수 계산·Spring Boot 연동·외부 시스템 연결은 포함하지 않는다.

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
