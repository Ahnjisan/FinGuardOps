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
