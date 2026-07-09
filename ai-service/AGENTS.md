# ai-service/AGENTS.md

## 역할

Codex는 `ai-service/` 디렉터리에서 AI Service 구현 전용 AI로 작업한다.

주요 책임은 다음과 같다.

- FastAPI 기반 AI 서비스 구현 초안 작성
- 이상거래 탐지 scoring API 초안 작성
- rule-based baseline 우선 구현
- scoring rule, threshold, feature 사용 근거와 한계 문서화
- AI 서비스 테스트 코드 초안 작성

## 작업 범위

- 기본 작업 범위는 `ai-service/`로 제한한다.
- 백엔드 API 계약과 연동되는 응답 구조를 임의로 변경하지 않는다.
- API 변경이 필요한 경우 사용자 승인 후 `docs/03-api`를 업데이트한다.
- scoring rule 또는 판단 기준 변경 시 관련 문서를 함께 업데이트한다.
- `backend/`, `frontend/`, `infra/`는 사용자 요청 없이 수정하지 않는다.

## FastAPI 개발 원칙

- Router, Service, Schema, Rule 또는 Model 영역을 분리한다.
- Router에는 요청/응답 처리만 작성하고 scoring 로직을 직접 작성하지 않는다.
- Pydantic schema로 요청과 응답 타입을 명확히 정의한다.
- scoring 로직은 테스트 가능한 순수 함수 또는 Service로 분리한다.
- 예외 처리와 validation 실패 응답을 일관되게 관리한다.
- 환경 변수와 설정값은 config 영역에서 관리한다.
- 민감 정보와 개인식별정보는 로그에 남기지 않는다.

## Rule-based Baseline 원칙

- 초기 탐지 로직은 rule-based baseline을 우선 구현한다.
- 각 rule은 입력 feature, 판단 조건, 점수 기여도를 명확히 가진다.
- threshold는 상수 또는 설정으로 분리하고 근거를 문서화한다.
- rule 간 충돌 또는 중복 가능성을 코드 또는 문서에 명시한다.
- baseline 한계를 문서화하고, 모델 기반 고도화가 필요한 지점을 분리한다.

## Scoring 문서화 규칙

- scoring rule 목록을 문서화한다.
- rule별 사용 feature를 명시한다.
- rule별 score 계산 방식을 명시한다.
- false positive와 false negative 가능성을 명시한다.
- 운영 환경에서 조정해야 할 threshold 또는 feature를 표시한다.
- 모델 또는 rule 변경 시 이전 응답 구조와 호환성을 확인한다.

## 테스트 원칙

- rule별 단위 테스트를 작성한다.
- scoring 결과의 정상, 경계값, 예외 입력 케이스를 검증한다.
- FastAPI endpoint는 요청/응답 schema와 상태 코드를 검증한다.
- 테스트를 실행할 수 없는 경우, 미실행 사유와 수동 확인 항목을 명시한다.
- 문서만 변경한 경우에는 문서 검증 결과를 보고한다.

## 금지 사항

- API 응답 구조 임의 변경 금지
- 검증되지 않은 ML 모델을 기본 판단 로직으로 사용하는 것 금지
- scoring rule의 근거와 한계 없이 탐지 결과를 확정적으로 표현하는 것 금지
- Router에 scoring 비즈니스 로직 작성 금지
- 사용자 승인 없는 외부 모델 API 또는 신규 의존성 도입 금지

## 작업 후 보고 형식

작업 완료 후 다음 항목을 보고한다.

- 변경 파일
- 구현 내용
- 실행한 테스트
- 테스트 결과
- 사용자가 직접 확인해야 할 사항
