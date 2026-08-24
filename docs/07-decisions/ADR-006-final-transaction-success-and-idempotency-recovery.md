# ADR-006: 최종 거래 성공과 멱등 Snapshot v2·완료 간극 복구

- 상태: Accepted
- 결정일: 2026-08-14
- 결정자: Project Owner
- 관련 문서:
  - [`ADR-003-transaction-processing-boundary.md`](./ADR-003-transaction-processing-boundary.md)
  - [`ADR-004-idempotency-response-snapshot-transition.md`](./ADR-004-idempotency-response-snapshot-transition.md)
  - [`../01-requirements/spring-rule-analysis-orchestration-contract.md`](../01-requirements/spring-rule-analysis-orchestration-contract.md)
  - [`../01-requirements/transaction-state-transition.md`](../01-requirements/transaction-state-transition.md)
  - [`../03-api/transaction-detection-api.md`](../03-api/transaction-detection-api.md)
  - [`../04-database/transaction-intake-schema.md`](../04-database/transaction-intake-schema.md)

## 1. 배경

ADR-003과 ADR-004는 `POST /api/v1/transactions`가 거래 접수부터 External Risk,
Rule 분석, 위험 대응과 필요한 사건 연결까지 완료한 뒤 최초 명령의 최종 업무
결과를 멱등 Snapshot으로 확정하도록 결정했다.

현재 구현은 거래 접수의 단계적 `RECEIVED` Snapshot과 내부 Rule v1 분석
오케스트레이터, 위험 등급별 목표 거래 상태·`RiskResponseOutcome`·사건 필수
여부를 반환하는 순수 decision 정책을 제공한다. Rule 분석 성공은 DetectionResult와
Evidence를 저장하고 결과를 채택해 거래를 `ANALYZED`로 만든다. 별도 내부
최종화 경계는 decision 적용, 필요한 사건 생성 또는 재사용, 거래 최종 상태와
AuditLog를 원자적으로 확정한다. 다만 거래 접수 전체 연결과 최종 업무 commit 뒤
멱등 Snapshot 완료가 실패하는
간극과 분석 실패 상태가 불확실한 경우의 멱등 전이 기준이 확정되지 않았다.

이 ADR은 기존 최종 동기 목표를 유지하면서 최종 성공 경계, Snapshot v2,
완료 간극 복구, 실패 공개 매핑과 선행 구현 순서를 확정한다. 이 문서 확정은
Java·Python·DB 또는 운영 복구 실행 경로의 구현 완료를 의미하지 않는다.

## 2. 최종 성공 경계

다음 거래 상태에서는 성공 HTTP 응답이나 성공 멱등 Snapshot을 확정하지 않는다.

- `RECEIVED`
- `ANALYZING`
- `ANALYZED`

`ANALYZED`는 Rule 분석 결과가 저장되고 DetectionResult가 `COMPLETED`이며,
Evidence 저장, 결과 채택과 위험 등급 확정이 끝난 중간 상태다. 위험 대응과
필요한 사건 연결까지 완료되었다는 뜻이 아니다.

최종 성공은 다음 항목이 모두 업무적으로 확정되고 필요한 commit이 끝난 뒤에만
허용한다.

1. Rule 분석 결과 저장
2. DetectionResult `COMPLETED`
3. Evidence 저장
4. 거래 결과 채택
5. 위험 등급 확정
6. 위험 대응 결과 확정
7. 최종 거래 상태 전이
8. HIGH·CRITICAL이면 사건 생성 또는 기존 사건 연결
9. 위 결과에 필요한 모든 업무 commit 완료

HIGH·CRITICAL 거래에서 사건 연결이 완료되지 않으면 일부 결과가 저장되었더라도
성공 Snapshot을 확정하지 않는다.

## 3. 위험 등급별 최종 결과

최종 성공 Snapshot은 다음 조합만 허용한다.

| `riskLevel` | `processingStatus` | `riskResponseOutcome` | `caseId` |
| --- | --- | --- | --- |
| `LOW` | `APPROVED` | `APPROVED` | null |
| `MEDIUM` | `APPROVED` | `APPROVED_WITH_MONITORING` | null |
| `HIGH` | `ADDITIONAL_AUTH_REQUIRED` | `ADDITIONAL_AUTH_REQUIRED` | 필수 |
| `CRITICAL` | `HELD` | `HELD` | 필수 |

위 표의 상태·등급·대응·사건 조합은 서로 독립적으로 조합하지 않는다. LOW·MEDIUM은
`caseId`가 null이어야 하고 HIGH·CRITICAL은 생성되었거나 연결된 사건의
`caseId`가 반드시 있어야 한다.

## 4. 최종 성공 Snapshot v2

최종 동기 처리로 새로 완료되는 요청은 다음 식별자를 사용한다.

- `responseSchemaVersion = transaction-create-response-v2`
- `codecVersion = transaction-intake-snapshot-envelope-v2`
- 최초 HTTP 상태: `201 Created`
- 완료 재생 HTTP 상태: 저장된 `201 Created`

v2 `responseBody`는 기존과 같은 일곱 업무 필드를 정확히 가진다.

```text
transactionId
processingStatus
riskLevel
riskResponseOutcome
adoptedDetectionResultId
caseId
createdAt
```

v2는 다음 조건을 모두 검증한다.

- `processingStatus`는 `APPROVED`, `ADDITIONAL_AUTH_REQUIRED`, `HELD` 중 하나다.
- `riskLevel`, `riskResponseOutcome`, `adoptedDetectionResultId`는 필수다.
- 위험 등급별 상태·대응·사건 조합은 3절과 정확히 일치한다.
- HIGH·CRITICAL의 `caseId`는 필수다.
- LOW·MEDIUM의 `caseId`는 JSON null이다.
- `RECEIVED`, `ANALYZING`, `ANALYZED`, `FAILED`는 v2에 저장할 수 없다.
- `traceId`는 envelope 어느 깊이에도 저장하지 않는다.
- 내부 PK, 요청 지문, 멱등 키와 민감 참조값을 저장하지 않는다.

예시는 다음과 같다.

```json
{
  "responseBody": {
    "transactionId": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
    "processingStatus": "HELD",
    "riskLevel": "CRITICAL",
    "riskResponseOutcome": "HELD",
    "adoptedDetectionResultId": "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101",
    "caseId": "case_example",
    "createdAt": "2026-08-14T01:15:31Z"
  },
  "httpStatus": 201,
  "responseSchemaVersion": "transaction-create-response-v2",
  "codecVersion": "transaction-intake-snapshot-envelope-v2",
  "finalizedAt": "2026-08-14T01:15:33Z"
}
```

## 5. 기존 Snapshot 호환성

다음 기존 Snapshot은 수정하거나 backfill하지 않는다.

- 무버전 legacy `RECEIVED` Snapshot
- `transaction-create-response-v1`
- `transaction-intake-snapshot-envelope-v1`

재생 규칙은 다음과 같다.

| 저장 형식 | 재생 HTTP 상태 | 재생 원칙 |
| --- | --- | --- |
| strict legacy `RECEIVED` | `200 OK` | 저장된 일곱 업무 값과 현재 요청 trace를 결합 |
| v1 envelope `RECEIVED` | 저장된 `201 Created` | 저장된 body·상태와 현재 요청 trace를 결합 |
| v2 envelope 최종 성공 | 저장된 `201 Created` | 저장된 최종 body·상태와 현재 요청 trace를 결합 |

기존 Snapshot을 최신 DB 상태로 보정하지 않는다. 기존 Snapshot 재생에서 Rule 분석,
External Risk, 위험 대응 또는 사건 생성을 시작하지 않는다. 기존 version 식별자의
의미를 확장하거나 v1 데이터를 v2로 해석하지 않는다.

## 6. 최종 업무 commit과 Snapshot 완료 사이 간극

최종 거래·탐지·위험 대응·사건 결과가 commit된 뒤 v2 Snapshot 생성 또는 멱등
`COMPLETED` commit이 실패할 수 있다. 이 경우 다음을 적용한다.

- 이미 확정된 거래·탐지·위험 대응·사건 결과를 되돌리지 않는다.
- 멱등 레코드를 `FAILED`로 전이하지 않는다.
- 멱등 레코드를 `IN_PROGRESS`로 유지한다.
- 최초 요청은 `500 Internal Server Error`, `INTERNAL_ERROR`를 반환한다.
- 같은 키·같은 지문 재요청은 `409 Conflict`,
  `IDEMPOTENCY_REQUEST_IN_PROGRESS`를 반환한다.
- 재요청은 FastAPI, External Risk, 위험 대응 또는 사건 생성을 반복하지 않는다.
- 자동 재실행이나 임시 성공 응답을 만들지 않는다.

운영 복구는 새로운 업무 실행이 아니다. 복구 경로는 이미 확정된 거래,
DetectionResult, Evidence, 위험 대응과 사건 연결의 식별자·상태·소유 관계를
검증한 뒤, 동일한 v2 Snapshot만 결정적으로 재구성해 기존 `IN_PROGRESS` 멱등
레코드를 `COMPLETED`로 전이한다. 복구 중 업무 상태를 변경하거나 새
DetectionResult·사건을 생성해서는 안 된다.

복구 작업은 대상 레코드 잠금, 검증 결과, 실행 주체, 실행 시각과 성공·실패를
감사 가능하게 남겨야 한다. 구체적인 운영 명령·권한·재시도 횟수와 배치 방식은
후속 구현에서 정하되 이 불변 원칙을 변경하지 않는다.

## 7. 분석 실패와 불확실성

멱등 `FAILED`는 업무 실패 상태가 확정된 경우에만 기록한다.

- 분석 실패와 거래·DetectionResult의 `FAILED` commit이 확인되면 공개 매핑에
  대응하는 안전한 `failureCode`로 멱등 레코드를 `FAILED`로 확정한다.
- 분석 시작 전 실패로 거래가 `RECEIVED`이고 DetectionResult가 생성되지 않은
  것이 확인되면 승인된 내부 코드로 멱등 레코드를 `FAILED`로 확정할 수 있다.
- 거래가 `ANALYZING`이거나 DetectionResult의 terminal 상태가 확인되지 않으면
  멱등 레코드를 `FAILED`로 확정하지 않고 `IN_PROGRESS`로 유지한다.
- 불확실한 상태에서 같은 요청을 자동 재실행하지 않는다.
- 운영 복구가 거래와 DetectionResult 상태를 확인해 실패 확정, 성공 Snapshot
  누락 복원 또는 별도 정합성 조치 중 승인된 후속 처리를 선택한다.

실패 기록 중 발생한 오류는 원래 오류를 덮어쓰지 않는다. 원래 예외를 보존하고
기록 오류는 동일 객체가 아닐 때만 suppressed exception으로 연결한다.

## 8. 실패 공개 매핑

Client 내부 category와 로컬 오케스트레이션 오류는 다음과 같이 멱등 실패와
최초·재생 응답으로 변환한다.

| 내부 원인 | 멱등 `failureCode` | 최초·재생 응답 |
| --- | --- | --- |
| connect·response timeout | `DEPENDENCY_TIMEOUT` | `503 DEPENDENCY_TIMEOUT` |
| AI Service unavailable | `DEPENDENCY_UNAVAILABLE` | `503 DEPENDENCY_UNAVAILABLE` |
| 계약·payload·capability·invalid response·내부 오류 | `INTERNAL_ERROR` | `500 INTERNAL_ERROR` |
| mapping·adoption·transaction boundary 오류 | `INTERNAL_ERROR` | `500 INTERNAL_ERROR` |

`DEPENDENCY_TIMEOUT`과 `DEPENDENCY_UNAVAILABLE`의 고정 공개 message는 모두
`탐지 서비스를 사용할 수 없습니다.`이다. Client 내부 category, FastAPI 원문
오류, 예외 상세와 민감정보를 공개 code나 message에 포함하지 않는다.

오류 응답의 `resource`는 후보이므로 이 ADR에서 추가하지 않는다.

## 9. Trace 정책

- 최초 HTTP 요청의 `traceId`를 분석 `analysisTraceId`로 그대로 전달한다.
- 별도 분석 trace를 생성하지 않는다.
- Snapshot에는 `traceId`를 저장하지 않는다.
- 완료·실패 재생 응답은 현재 재요청의 `traceId`를 사용한다.
- 최초 분석의 trace를 재요청 trace로 재사용하지 않는다.

## 10. External Risk와 RuleVersion 선행 조건

- ADR-003의 External Risk 단계를 최종 동기 목표에서 제거하지 않는다.
- 독립 External Risk 정책·Mock 경계는 구현되었지만 거래 접수·Rule 분석 입력과
  연결되기 전에는 최종 거래 접수 연결을 구현 완료로 표시하지 않는다.
- External Risk timeout·unavailable·invalid response는 현재 분석을 계속하지 않고
  typed failure로 전파한다. cache, stale data, fallback과 `UNMATCHED` 변환은 없다.
- 후속 거래 접수 연결에서는 거래와 분석 결과를 `FAILED`로 확정하고 기존 외부
  오류 매핑을 사용한다. cache·Circuit Breaker·fallback은 별도 Issue와 계약
  승인 없이는 도입하지 않는다.
- V5의 `DRAFT` RuleVersion을 자동 실행하거나 암묵적으로 publish하지 않는다.
- 기본 RuleVersion의 원자적 발행 경계와 제한된 local/dev/test one-shot 명령은
  구현되었지만 정상 시작 자동 발행과 production 실험값 발행은 없다.
- 이 결정은 RuleVersion seed나 Flyway Migration을 변경하지 않는다.

## 11. 현재 구현 상태

### 구현됨

- 신규 거래 접수의 단계적 `RECEIVED` v1 Snapshot 저장·재생
- strict legacy `RECEIVED` Snapshot 재생
- 내부 Rule v1 HTTP 오케스트레이터와 분석 결과 저장·채택·실패 기록
- 독립 External Risk Port·정책 Service, local/dev/test 결정적 Mock과 immutable
  인메모리 성공 Snapshot
- LOW·MEDIUM·HIGH·CRITICAL별 목표 거래 상태, `RiskResponseOutcome`과 사건 필수
  여부를 반환하는 순수 immutable 위험 대응 decision
- `FraudCase`·`CaseTransaction` Entity, Flyway V6와 HIGH·CRITICAL
  `ANALYZED` 거래의 새 `OPEN` 사건·첫 연결을 원자적으로 생성하거나 기존 활성
  연결을 멱등 반환하는 내부 persistence boundary
- append-only `AuditLog` Entity, Flyway V7, typed INSERT 전용 Persistence 경계와
  PostgreSQL UPDATE·DELETE 차단 trigger
- `ANALYZED` 거래를 잠그고 채택 결과를 검증한 뒤 decision, 필요한 사건,
  최종 거래 상태·`RiskResponseOutcome`, AuditLog를 함께 commit하거나 rollback하는
  내부 위험 대응 최종화 경계

### 구현되지 않음

- 거래 접수 Service와 Rule 분석 오케스트레이터 연결
- 실제 External Risk HTTP Provider, FastAPI 입력·거래 접수 연결, 공개 오류 매핑과
  Snapshot DB 영속화
- 최종 v2 Snapshot codec과 거래 접수 완료 연결
- Snapshot 완료 간극과 불확실 분석 상태의 운영 복구 실행 경로
- RuleVersion 운영 publish 준비

## 12. 후속 구현 순서

1. 구현된 위험 대응 decision을 거래에 적용하고 최종 상태 전이 구현 — 완료
2. AuditLog 계약과 물리 모델 및 내부 최종화 통합 — 완료
3. 거래 접수–External Risk–Rule 분석–위험 대응–사건–Snapshot v2 연결
4. Snapshot 완료 간극 운영 복구 구현

각 단계는 이전 단계의 계약과 상태를 임시 기본값으로 대체하지 않는다. 4단계가
완료되기 전까지 현행 `RECEIVED` v1 응답을 최종 동기 거래 처리로 표현하지 않는다.

## 13. 영향과 제외 범위

이 결정으로 기존 DB 컬럼, Flyway Migration, Java·Python 코드, seed 또는
의존성이 변경되지는 않는다. v2는 기존 `response_snapshot JSONB`에 저장할 수
있지만 codec과 실행 경로는 후속 구현 대상이다. 사건 영속 모델과 External Risk의
구체적인 물리 설계는 각 선행 Issue의 승인 범위다.

완료·진행 중·충돌·실패 재생과 완료 간극 복구는 새 External Risk·FastAPI·LLM
호출을 만들지 않아야 한다. 이 Rule v1 경로는 LLM을 호출하지 않는다.
