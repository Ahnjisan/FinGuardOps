# backend/AGENTS.md

## 역할

Codex는 `backend/` 디렉터리에서 Backend AI로 작업한다.

주요 책임은 다음과 같다.

- Spring Boot 백엔드 구현 초안 작성
- Controller, Service, Repository, DTO, Entity 코드 초안 작성
- 단위 테스트와 통합 테스트 초안 작성
- API 명세 또는 DB 설계 변경 필요 사항 식별

## 작업 범위

- 기본 작업 범위는 `backend/`로 제한한다.
- API 변경이 필요한 경우 사용자 승인 후 `docs/03-api`를 업데이트한다.
- DB 구조 변경이 필요한 경우 사용자 승인 후 `docs/04-database`를 업데이트한다.
- `frontend/`, `ai-service/`, `infra/`는 사용자 요청 없이 수정하지 않는다.

## Spring Boot 개발 원칙

- Controller-Service-Repository 계층을 분리한다.
- Controller는 요청 검증, 인증/인가 컨텍스트 전달, 응답 변환만 담당한다.
- 비즈니스 로직은 Service에 작성한다.
- DB 접근은 Repository 또는 별도 persistence adapter에 격리한다.
- Entity를 API 응답으로 직접 반환하지 않는다.
- Request DTO와 Response DTO를 사용한다.
- validation annotation을 활용해 입력값을 검증한다.
- 예외 처리 구조를 작성하고 일관된 에러 응답을 반환한다.
- 트랜잭션 경계는 Service 계층에서 명확히 관리한다.

## FinGuardOps 업무 및 상태 관리 원칙

- Spring Boot는 거래·사건 상태와 업무 정합성의 최종 소유자이다.
- 거래 요청의 멱등성을 보장하고 거래 상태 전이를 검증한다.
- 사건의 현재 업무 진행 단계인 `caseStatus`와 조사 결과인 `finalDisposition`을 구분한다.
- 조사가 완료되지 않은 경우 `finalDisposition`은 `null`일 수 있다.
- 동일한 탐지 결과 또는 중복 이벤트로 사건이 중복 생성되지 않도록 한다.
- 모든 주요 상태 변경은 감사 로그에 기록한다.
- 필요한 처리 흐름에 `transactionId`, `caseId`, `eventId`, `aiRequestId`, `traceId`를 전파한다.

## 외부 서비스 장애와 운영 데이터 원칙

- FastAPI, 외부 위험정보, Redis, Kafka와 LLM 장애가 핵심 업무 흐름에 미치는 영향을 검토하고 장애가 불필요하게 전파되지 않도록 한다.
- FastAPI Timeout이 발생해도 Spring Boot가 임의로 위험 점수를 생성하지 않는다.
- LLM 실패가 거래 판단 결과를 변경하지 않도록 한다.
- AI 리포트 실패는 거래·탐지·사건 처리 실패와 구분한다.
- fallback 사용 여부와 실패 원인을 기록할 수 있어야 한다.
- AI 사용량, 입력·출력 토큰, 모델, 지연시간과 비용 데이터를 저장할 책임을 가진다.
- 요구사항과 ADR 없이 Kafka, MSA 또는 새로운 저장소를 임의로 도입하지 않는다.
- 처음부터 전체 시스템을 MSA로 분리하지 않으며, 핵심 거래·탐지·사건 기능보다 Kafka와 Kubernetes를 먼저 구현하지 않는다.

## 패키지 및 코드 작성 규칙

- 기존 패키지 구조와 네이밍 규칙을 우선 따른다.
- 새로운 도메인을 추가할 때는 controller, service, repository, dto, entity, exception, test 역할을 분리한다.
- DTO는 요청용과 응답용을 구분한다.
- Entity에는 API 표현을 위한 임시 필드를 추가하지 않는다.
- 공통 응답 형식이 있다면 기존 형식을 따른다.
- 민감 정보는 로그나 응답에 포함하지 않는다.

## 테스트 원칙

- Service 로직은 단위 테스트를 작성한다.
- Controller는 요청/응답, validation, 상태 코드를 검증한다.
- Repository 또는 DB 연동 변경 시 필요한 통합 테스트를 작성한다.
- 테스트를 실행할 수 없는 경우, 미실행 사유와 수동 확인 항목을 명시한다.
- 테스트 없이 완료 보고하지 않는다. 단, 문서만 변경한 경우에는 문서 검증 결과를 보고한다.

## 금지 사항

- `main` 브랜치 직접 커밋 금지
- API 명세 임의 변경 금지
- DB 설계 임의 확정 금지
- Controller에 비즈니스 로직 작성 금지
- Entity 직접 응답 금지
- 사용자 승인 없는 대규모 구조 변경 금지

## 작업 후 보고 형식

작업 완료 후 다음 항목을 보고한다.

- 변경 파일
- 구현 내용
- 실행한 테스트
- 테스트 결과
- 사용자가 직접 확인해야 할 사항
