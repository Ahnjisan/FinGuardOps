# ADR-003: 거래 처리 경계와 단계적 구현 순서

- 상태: Accepted
- 결정일: 2026-07-26
- 결정자: Project Owner
- 관련 Issue:
  - [#44: 구현 전 문서 정합성 및 거래 처리 경계 정리](https://github.com/Ahnjisan/FinGuardOps/issues/44)
- 관련 문서:
  - `docs/01-requirements/transaction-state-transition.md`
  - `docs/01-requirements/rule-v1-detection-contract.md`
  - `docs/02-architecture/system-architecture.md`
  - `docs/02-architecture/domain-erd.md`
  - `docs/03-api/transaction-detection-api.md`
  - `docs/03-api/domain-event-contracts.md`
  - `docs/04-database/transaction-intake-schema.md`
  - `docs/07-decisions/ADR-004-idempotency-response-snapshot-transition.md`
  - `docs/07-decisions/ADR-006-final-transaction-success-and-idempotency-recovery.md`

## Context

최종 `POST /api/v1/transactions` 계약은 거래 접수부터 분석, 위험 대응과 사건 연결 결과까지 하나의 동기 요청에서 처리한다.

```text
거래 접수
→ 입력 검증·멱등성 확인
→ 거래 영속화
→ External Risk 조회
→ FastAPI Rule·ML 분석
→ 탐지 결과 검증·저장·채택
→ 위험 대응 결정
→ 필요 시 사건 생성 또는 기존 사건 연결
→ 최종 응답
```

그러나 백엔드 구현을 이 전체 흐름의 외부 Controller부터 한 번에 시작하면 아직 준비되지 않은 분석·위험 대응·사건 연결을 임시 응답이나 불완전한 상태로 공개할 위험이 있다. 반대로 거래 영속화와 멱등성부터 내부 구성요소 단위로 구현하는 것은 최종 API 경계를 비동기로 바꾸거나 기능을 축소하는 결정이 아니다.

외부 계약과 내부 구현 순서를 구분해, 최종 API 의미를 유지하면서도 각 정합성 단위를 독립적으로 검증할 기준이 필요하다.

## Decision

- 최종 `POST /api/v1/transactions`는 거래 접수부터 분석, 위험 대응, 사건 생성 또는 기존 사건 연결까지의 동기 계약을 유지한다.
- `RECEIVED`, `ANALYZING`, `ANALYZED`는 최종 성공 상태가 아니며 이 상태에서
  성공 응답이나 성공 멱등 Snapshot을 확정하지 않는다.
- 구현은 거래 영속화와 멱등성부터 내부 구성요소 단위로 진행한다.
- 전체 흐름이 준비되기 전에는 불완전한 외부 Controller를 공개하지 않는다.
- 이번 결정은 API 처리 경계 변경이 아니라 구현 순서에 관한 결정이다.

최종 Controller가 공개되기 전에도 내부 Service, Repository, 도메인 정책과 테스트는 단계적으로 구현할 수 있다. 이 내부 단계는 최종 API의 일부 응답만 임시로 제공하거나 문서에 없는 성공 결과를 외부에 노출하지 않는다.

논리적 도메인 이벤트를 내부 애플리케이션 흐름에서 사용할 수 있지만, 이것이 `POST /api/v1/transactions`를 비동기 접수 계약으로 변경하거나 Kafka를 선행 도입한다는 뜻은 아니다.

### 현재 구현 상태

현재 저장소에는 거래·멱등·행동 이벤트,
DetectionResult·DetectionEvidence와 ADR-005의
FraudRule·RuleVersion PostgreSQL 물리 영속 모델 및 거래 접수 Controller가
구현되어 있다. 거래 접수 성공 응답은
`processingStatus = RECEIVED`이며 `riskLevel`,
`riskResponseOutcome`, `adoptedDetectionResultId`, `caseId`는 null이다.
행동 이벤트는 내부 Rule 평가용 제한 조회와 행동 기반
`DetectionEvidence.observationSummary`의 외부 Event ID 검증까지 구현되어
있다. RuleVersion 적용 기간·불변성·Evidence FK 정합성은 구현되었지만
공개 행동 이벤트 조회 API는 구현되지 않았다. FastAPI Rule v1 실행과 Spring
Boot의 Snapshot 고정·HTTP 호출·탐지 결과 생성·검증·채택 및 실패 기록은
구현되었다. 독립 External Risk Port·정책 Service, local/dev/test 결정적 Mock과
immutable 인메모리 성공 Snapshot도 구현되었다. 실제 Provider, 거래 접수와
Rule 분석 입력 연결,
위험 등급별 목표 거래 상태·`RiskResponseOutcome`·사건 필수 여부를 반환하는
순수 decision 정책도 구현되었다. 최종 멱등 Snapshot v2, 이 정책의 거래 적용과
최종 상태 전이, 대응 결과 영속화, 사건 연결, Snapshot 완료 간극 복구와 일반
RuleVersion 운영 관리는 아직 수행하지 않는다.

이 단계적 응답은 현재 구현 사실을 기록한 것이며, `POST /api/v1/transactions`를 비동기 접수 API로 바꾸거나 최종 동기 분석 결정을 뒤집는 새로운 결정이 아니다. 현행 단계 Controller는 이 ADR이 정한 중간 외부 노출 제한과 아직 정합화되지 않은 구현 차이로 기록한다. 후속 구현에서는 이 ADR의 최종 경계로 전환하거나, 결정 변경이 필요하면 별도 사용자 승인과 ADR 검토를 거쳐야 한다.

현재 멱등 완료 응답 snapshot은 `RECEIVED`/null 구조를 저장·재생한다. [`ADR-004`](./ADR-004-idempotency-response-snapshot-transition.md)는 이 legacy Snapshot을 엄격하게 그대로 재생하고 소급 갱신하지 않으며, 최종 동기 응답 전환 이후 신규 요청부터 version envelope와 최초 확정 HTTP 상태를 저장하도록 결정한다. 이는 ADR-003의 최종 동기 처리 결정을 유지한 호환 정책이다. ADR-004 결정 당시에는 envelope·codec, 관련 Migration과 만료 처리가 아직 구현되지 않은 상태였다.

후속 구현 상태(2026-07-30): 신규 요청의 version envelope encoder·decoder와
version dispatch는 구현되었다. 기존 `response_snapshot JSONB` 안에 저장하므로
별도 Migration과 legacy backfill은 필요하지 않았고, 만료 판정·정리 작업은
여전히 구현되지 않았다. 이 후속 상태는 ADR-003의 결정 자체를 변경하지
않는다.

후속 구현 상태(2026-08-14): 거래·행동 이벤트·RuleVersion Snapshot과 분석
시작 commit, DB 트랜잭션 밖 FastAPI 정확히 1회 호출, 응답 Evidence 변환,
DetectionResult 완료·채택 또는 실패 기록을 연결하는 내부 Rule v1
오케스트레이터가 구현되었다. 거래 접수와 최종 동기 응답 연결은 아직 수행하지
않으며 ADR-003의 단계적 구현 결정은 유지한다.

후속 결정(2026-08-14): [`ADR-006`](./ADR-006-final-transaction-success-and-idempotency-recovery.md)은
위험 대응, 최종 거래 상태와 HIGH·CRITICAL 사건 연결을 포함한 모든 업무 commit
뒤에만 최종 성공을 허용한다. 신규 최종 성공은 Snapshot v2로 저장하고, 업무
commit 뒤 멱등 완료 실패는 업무를 되돌리거나 멱등 `FAILED`로 바꾸지 않고
`IN_PROGRESS`에서 운영 복구를 기다린다. 이 계약과 복구 실행 경로는 아직
구현되지 않았다.

## 구현 순서

초기 구현은 다음 순서를 따른다.

1. 거래 식별자, 요청 지문과 멱등성 선점 규칙을 정의하고 거래 접수 영속화를 구현한다.
2. 요청 형식·도메인 Validation을 거래 저장 전에 수행하고, 검증을 통과한 거래의 `RECEIVED` 영속 경계를 검증한다. Validation 실패는 거래로 저장하지 않는다.
3. [Rule v1 탐지 계약](../01-requirements/rule-v1-detection-contract.md)에 따라 평가 Snapshot, 활성 Rule 집합, FastAPI 분석 호출 경계와 DetectionResult 저장·채택을 구현한다.
4. 구현된 위험 대응 decision을 거래에 적용해 대응 결과와 최종 상태를 확정하고 HIGH·CRITICAL 사건 생성 또는 기존 사건 연결을 구현한다.
5. 전체 성공·실패·멱등·동시성 흐름이 준비되면 현재 단계 응답을 최종 동기 Controller 계약으로 전환한다.

각 단계는 내부 단위·통합 테스트로 검증한다. 최종 동기 응답 전환 전에는 내부 구현 완료 범위와 외부 API 제공 상태를 구분해 보고한다.

## 유지되는 계약

- Spring Boot는 거래 상태, 위험 대응과 사건 연결의 업무 정합성을 최종 소유한다.
- `POST /api/v1/transactions`의 최종 성공 응답은 채택된 탐지 결과와 위험 대응 및 사건 연결 결과를 반영한다.
- 동일 멱등 요청은 새 거래·탐지 결과·사건을 중복 생성하지 않는다.
- FastAPI Timeout이나 응답 부재를 이유로 Spring Boot가 임의 위험 점수를 만들거나 정상 거래로 승인하지 않는다.
- HIGH·CRITICAL 처리에서 거래 상태와 사건 연결이 일부만 성공한 결과를 정상 완료로 공개하지 않는다.
- AI 사건 리포트는 거래 동기 처리의 필수 경로가 아니며 거래 위험 판단을 변경하지 않는다.
- Kafka는 핵심 거래·탐지·사건 기능 안정화 이후의 도입 후보이다.

## Consequences

### 긍정적 결과

- 최종 API 계약을 유지하면서 거래 영속화, 멱등성, 분석과 사건 정합성을 작은 단위로 검증할 수 있다.
- 아직 구현되지 않은 분석·위험 대응 결과를 임시 성공 응답으로 공개하지 않는다.
- 내부 구현 순서를 이유로 API 문서와 Controller 의미가 달라지는 것을 방지한다.
- Kafka나 비동기 계약을 핵심 거래 기능보다 먼저 도입하지 않는다.

### Trade-off

- 현재 단계적 거래 접수 API로 영속화·멱등성은 시연할 수 있지만 탐지·위험 대응·사건 연결이 포함된 최종 동기 처리로 시연해서는 안 된다.
- 내부 구성요소 테스트와 최종 Controller 통합 테스트를 구분해 관리해야 한다.
- 최종 성공과 멱등 완료 간극의 불변 원칙은 ADR-006으로 확정되었다. 사건·위험
  대응의 구체 트랜잭션과 운영 복구 명령·권한은 후속 구현에서 구체화해야 한다.

## 제외 범위

- `POST /api/v1/transactions`를 `202 Accepted` 중심의 비동기 API로 변경
- 거래 접수 전용 임시 Controller 공개
- 일부 분석 결과를 임의 기본값으로 채운 임시 성공 응답
- Java Controller, Service, Repository, Entity와 설정 구현
- DB DDL과 마이그레이션
- Kafka Topic, Producer, Consumer와 Outbox 구현
- FastAPI, External Risk Mock과 실제 금융거래 처리 구현

