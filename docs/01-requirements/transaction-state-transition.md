# FinGuardOps 거래 상태 전이

## 1. 문서 목적

이 문서는 FinGuardOps가 거래를 접수하고 검증·분석한 뒤 위험 대응 결과를 정하는 과정에서 사용하는 거래 처리 상태와 허용 전이를 요구사항 수준에서 정의한다.

이 문서는 이후 ERD, Entity, REST API, 트랜잭션 경계, 멱등성, 동시성 제어와 감사 로그 설계의 기준으로 사용한다.

## 2. 문서 범위와 경계

이 문서의 범위는 다음 흐름이다.

```text
요청 검증
→ 거래 접수
→ 이상거래 분석
→ 위험 대응 결정
→ 최종 처리 상태
```

이 문서는 요구사항 단계의 상태와 전이를 정의한다. 거래 접수의 DB
컬럼·제약조건·멱등성 키와 낙관적 잠금은
[`../04-database/transaction-intake-schema.md`](../04-database/transaction-intake-schema.md),
Rule v1 분석의 실행 시점·트랜잭션·결과 채택은
[Spring Boot Rule v1 분석 오케스트레이션·결과 채택 계약](./spring-rule-analysis-orchestration-contract.md)에서
구체화한다. 최종 거래 성공 상태와 멱등 Snapshot·완료 간극 복구는
[`ADR-006`](../07-decisions/ADR-006-final-transaction-success-and-idempotency-recovery.md)을
따른다.

FinGuardOps는 실제 금융기관 거래를 승인·인증·보류·차단하지 않는다. 이 문서의 승인, 추가 인증과 보류는 프로젝트 내부의 Mock 처리 결과를 뜻한다.

Kafka는 핵심 거래·탐지·사건 기능이 안정화된 이후 도입할 범위이다. 현재 거래 처리 흐름이 Kafka를 필수 구성요소로 사용한다고 가정하지 않는다.

## 3. 용어 정의

### 3.1 거래 처리 상태

모든 요청 Validation을 통과해 영속화된 거래가 FinGuardOps 내부 처리 흐름에서 어느 단계에 있는지를 나타낸다. 접수, 분석 진행·완료와 최종 처리 결과를 표현한다.

요청 형식과 거래 유형별 도메인 Validation 거절은 영속 거래 상태가 아니다. 이 거절은 오류 응답, `traceId`, 로그와 운영 메트릭으로 관측한다.

### 3.2 위험 등급

Rule·ML 탐지 결과를 승인된 점수 통합 정책으로 평가한 위험 수준이다.

- `LOW`
- `MEDIUM`
- `HIGH`
- `CRITICAL`

`LOW`, `HIGH`와 같은 위험 등급은 거래 처리 상태가 아니다.

### 3.3 위험 대응 결과

확정된 위험 등급에 따라 해당 거래에 적용하기로 한 Mock 업무 대응이다.

- `LOW`: 승인
- `MEDIUM`: 승인 후 모니터링
- `HIGH`: 추가 인증 요청 및 사건 생성
- `CRITICAL`: 거래 보류, 긴급 사건 생성 및 알림

위험 대응 결과는 거래 처리 상태와 별도로 관리한다. 예를 들어 MEDIUM 거래의
목표 거래 처리 상태는 승인 완료이고, 위험 대응 결과는 승인 후 모니터링이다.

현재 순수 위험 대응 결정 정책은 `RiskLevel` 하나를 입력받아 다음 네 값을
immutable decision으로 반환한다.

- 원본 `RiskLevel`
- 목표 `TransactionProcessingStatus`
- `RiskResponseOutcome`
- 사건 필수 여부

이 정책은 LOW를 `APPROVED`/`APPROVED`/사건 불필요, MEDIUM을
`APPROVED`/`APPROVED_WITH_MONITORING`/사건 불필요, HIGH를
`ADDITIONAL_AUTH_REQUIRED`/`ADDITIONAL_AUTH_REQUIRED`/사건 필수,
CRITICAL을 `HELD`/`HELD`/사건 필수로 결정한다. 정책 실행만으로 거래 상태나
영속 결과를 변경하거나 사건을 생성·연결하지 않는다.

### 3.4 사건 생성 여부

거래 또는 연관 거래 흐름이 사건 생성 조건을 충족해 사건이 생성되거나 기존 사건에 연결되었는지를 나타낸다. 사건 생성 여부는 거래 상태나 위험 등급을 대신하지 않는다.

HIGH와 CRITICAL은 사건 생성 대상이지만, 재처리·연관 거래·사건 병합 정책에 따라 새 사건 생성 대신 기존 사건 연결이 될 수 있다.

## 4. 현재 영속 처리 상태

초기 MVP에서 `financial_transaction.processing_status`에 저장하는 상태는 다음과 같다.

- `RECEIVED`
- `ANALYZING`
- `ANALYZED`
- `APPROVED`
- `ADDITIONAL_AUTH_REQUIRED`
- `HELD`
- `FAILED`

기존 요구사항에 있던 `MONITORING`은 거래 처리 상태에서 제외하고 위험 대응 결과로 분리한다. 이를 통해 승인된 거래가 `APPROVED`인지 `MONITORING`인지 중복 표현되는 문제를 피한다.

기존 후보인 `BLOCKED`도 현재 영속 상태에서 제외한다. 현재 요구사항의 CRITICAL 대응은 Mock 거래 보류이며 실제 거래 차단은 범위 밖이므로 `HELD`와 별도의 차단 상태를 확정할 근거가 부족하다.

## 5. 상태별 의미

### `RECEIVED`

요청 형식과 거래 유형별 도메인 Validation을 통과한 거래가 최초 저장되어 분석 시작을 기다리는 상태이다. 접수 성공이 거래 승인이나 분석 성공을 의미하지 않는다.

### `ANALYZING`

검증을 통과한 거래에 대해 Rule·ML 및 승인된 외부 위험정보 활용을 포함한 이상거래 분석이 진행 중인 상태이다.

### `ANALYZED`

사용 가능한 탐지 결과가 저장되고 위험 등급이 결정되어 위험 대응을 적용할 준비가 된 상태이다. 위험 대응까지 완료되었다는 뜻이 아니며 성공 HTTP 응답이나 성공 멱등 Snapshot을 확정할 수 없는 중간 상태이다.

### `APPROVED`

Mock 승인 처리가 완료된 상태이다. LOW의 단순 승인과 MEDIUM의 승인 후 모니터링은 모두 이 상태를 사용할 수 있으며 위험 대응 결과에서 구분한다.

### `ADDITIONAL_AUTH_REQUIRED`

HIGH 위험 대응으로 Mock 추가 인증이 필요한 상태이다. 추가 인증의 성공·실패·만료 후 전이는 기존 요구사항에 확정 근거가 없어 `TBD`이다.

### `HELD`

CRITICAL 위험 대응으로 Mock 거래가 보류된 상태이다. 실제 금융거래 차단이나 고객 자금 제어를 의미하지 않는다.

### `FAILED`

검증 이후 내부 저장, 분석 조정 또는 위험 대응 반영 과정에서 시스템 처리에 실패해 정상적인 다음 상태로 진행하지 못한 상태이다. 실패 단계와 마지막으로 확정된 결과를 함께 식별할 수 있어야 한다.

AI 리포트 생성 실패는 `FAILED`의 사유가 아니다.

## 6. 텍스트 상태 전이도

```text
요청 형식 또는 도메인 Validation 실패
→ Transaction 미생성
→ 400 또는 422 오류 응답
→ 로그·traceId·운영 메트릭으로 관측

요청 Validation 성공
→ RECEIVED
→ ANALYZING
   ├─ 분석 결과 확정 → ANALYZED
   └─ 시스템 처리 실패 → FAILED

ANALYZED
├─ LOW 대응      → APPROVED
├─ MEDIUM 대응   → APPROVED
├─ HIGH 대응     → ADDITIONAL_AUTH_REQUIRED
├─ CRITICAL 대응 → HELD
└─ 대응 반영 실패 → FAILED
```

MEDIUM의 `승인 후 모니터링`은 다음과 같이 표현한다.

```text
거래 처리 상태 = APPROVED
위험 등급 = MEDIUM
위험 대응 결과 = 승인 후 모니터링
사건 생성 여부 = 생성하지 않음
```

HIGH와 CRITICAL의 사건 생성은 거래 상태와 같은 필드에 포함하지 않는다.

## 7. 허용 전이

### Validation 성공 → `RECEIVED`

- 전이 조건: JSON·헤더 형식, 요청 DTO와 거래 유형별 도메인 Validation을 모두 통과한다.
- 변경 주체: 시스템인 Spring Boot
- 생성되는 결과: UUID v4 `transactionId`로 식별되는 최초 Transaction과 거래 접수 멱등 기록
- 사건 생성 여부: 생성하지 않음
- 실패 시 처리: Transaction과 멱등 기록의 저장 결과가 불명확하면 성공으로 임의 확정하지 않는다.
- 재시도 가능 여부: `Idempotency-Key`와 요청 지문 계약을 따른다.
- 감사·관측 여부: 유효 거래의 접수는 감사 가능해야 하며 Validation 거절은 Transaction이나 AuditLog 행을 만들지 않고 오류 응답·로그·traceId·운영 메트릭으로 관측한다.

### `RECEIVED` → `ANALYZING`

- 전이 조건: 요청 검증이 성공하고 거래·행동 이벤트·활성 RuleVersion
  Snapshot과 실행 식별자가 고정되어 분석을 시작할 수 있다.
- 변경 주체: 시스템인 Spring Boot
- 생성되는 결과: 거래 잠금 아래 할당한 `PENDING` DetectionResult를 같은 쓰기
  트랜잭션에서 `IN_PROGRESS`로 전이한 분석 요청과 추적 가능한 처리 컨텍스트
- 사건 생성 여부: 생성하지 않음
- 실패 시 처리: commit되지 않으면 두 상태 전이를 모두 적용하지 않는다.
- 재시도 가능 여부: 이 계약에서 자동 재시도하지 않는다. 실패 후 재분석·수동
  복구는 후속 계약으로 분리한다.
- 감사 로그 여부: 필요

FastAPI 호출은 이 전이의 commit 이후 DB 트랜잭션과 거래 잠금을 모두 해제한
상태에서 수행한다.

### `ANALYZING` → `ANALYZED`

- 전이 조건: FastAPI 성공 응답이 Client 계약을 모두 통과하고 Evidence와
  `DetectionResult COMPLETED`가 저장되며 해당 결과를 거래가 채택한다.
- 변경 주체: 상태와 업무 정합성의 최종 소유자인 Spring Boot
- 생성되는 결과: 채택된 탐지 결과, 위험 점수·등급, Reason Code와 사용한 외부 정보 상태
- 사건 생성 여부: 아직 새 사건을 생성하지 않으며 다음 위험 대응 단계에서 결정
- 실패 시 처리: Evidence 저장, `DetectionResult COMPLETED`, 결과 채택과 이
  전이를 하나의 쓰기 트랜잭션으로 rollback한다. 응답이 없다는 이유로
  정상이나 무위험으로 판단하지 않는다.
- 재시도 가능 여부: 이 계약에서 자동 재시도하지 않는다. 동일 분석 결과의
  중복 저장과 Timeout 후 늦은 응답을 거부한다.
- 감사 로그 여부: 필요

### `ANALYZED` → `APPROVED`

- 전이 조건: 위험 등급이 LOW 또는 MEDIUM이고 승인된 대응 정책을 적용한다.
- 변경 주체: 시스템인 Spring Boot
- 생성되는 결과: Mock 승인 결과. MEDIUM이면 별도 위험 대응 결과에 승인 후 모니터링을 기록한다.
- 사건 생성 여부: LOW와 MEDIUM의 기본 정책에서는 생성하지 않음
- 실패 시 처리: 대응 결과 저장 여부가 불명확하면 승인 완료로 임의 응답하지 않는다.
- 재시도 가능 여부: 가능 후보. 이미 반영된 승인 결과를 중복 생성하지 않는다.
- 감사 로그 여부: 필요

LOW는 `riskResponseOutcome = APPROVED`, MEDIUM은
`riskResponseOutcome = APPROVED_WITH_MONITORING`을 사용한다. 두 경우 모두
`caseId`는 null이다. 대응 결과와 `APPROVED` 전이가 commit된 뒤에만 최종 성공
조건을 충족한다.

### `ANALYZED` → `ADDITIONAL_AUTH_REQUIRED`

- 전이 조건: 위험 등급이 HIGH이고 추가 인증 및 사건 생성 정책을 적용한다.
- 변경 주체: 시스템인 Spring Boot
- 생성되는 결과: Mock 추가 인증 필요 결과와 사건 생성 또는 기존 사건 연결 결과
- 사건 생성 여부: 필요. 동일 거래에 대한 중복 사건은 생성하지 않는다. 사건
  생성 또는 기존 사건 연결과 `caseId`가 확정되지 않으면 성공 조건을 충족하지
  않는다.
- 실패 시 처리: 거래 상태 변경과 사건 생성의 일부만 성공한 경우 정합성 확인 및 복구가 필요하다. 구체적인 보상 방식은 후속 설계에서 확정한다.
- 재시도 가능 여부: 가능 후보. 상태와 사건 생성 모두 멱등성을 보장해야 한다.
- 감사 로그 여부: 필요

### `ANALYZED` → `HELD`

- 전이 조건: 위험 등급이 CRITICAL이고 거래 보류, 긴급 사건 및 알림 정책을 적용한다.
- 변경 주체: 시스템인 Spring Boot
- 생성되는 결과: Mock 보류 결과, 긴급 사건 생성 또는 기존 사건 연결, 담당자 알림 후보
- 사건 생성 여부: 필요. 중복 긴급 사건 생성을 방지한다. 사건 생성 또는 기존
  사건 연결과 `caseId`가 확정되지 않으면 성공 조건을 충족하지 않는다.
- 실패 시 처리: 보류·사건·알림의 각 결과를 구분해 기록한다. 알림 실패가 거래 위험 판단 결과를 변경하지 않는다.
- 재시도 가능 여부: 가능 후보. 각 결과의 중복 생성을 방지해야 한다.
- 감사 로그 여부: 필요

### `ANALYZING` 또는 `ANALYZED` → `FAILED`

- 전이 조건: 거래 처리에 필요한 내부 단계가 실패했고 승인된 fallback 또는 재시도로 계속할 수 없다.
- 변경 주체: 시스템인 Spring Boot
- 생성되는 결과: 실패 단계, 원인 분류, 마지막 확정 상태와 재시도 가능 여부
- 사건 생성 여부: 실패만을 이유로 자동 생성하지 않음. 이미 생성된 사건을 삭제하거나 중복 생성하지 않는다.
- 실패 시 처리: 저장 성공 여부가 불명확하면 성공으로 간주하지 않고 정합성을 확인한다.
- 재시도 가능 여부: 오류 유형별 `TBD`
- 감사 로그 여부: 필요

Rule v1 분석 실패에서는 `DetectionResult PENDING|IN_PROGRESS → FAILED`와 거래
`ANALYZING → FAILED`를 함께 기록하고 결과를 채택하지 않는다. Client 자동
retry는 0회이며 실패를 0점·`LOW`·빈 Evidence 또는 fallback 성공으로 바꾸지
않는다. `ANALYZED → FAILED`의 위험 대응 실패 의미는 이 분석 실패 경계와
구분한다.

## 8. 금지 전이

- 위험 등급 값을 거래 처리 상태로 저장하거나 전이 대상으로 사용하지 않는다.
- `RECEIVED`에서 검증과 분석 없이 `APPROVED`, `ADDITIONAL_AUTH_REQUIRED` 또는 `HELD`로 전이하지 않는다.
- Validation 실패 요청을 `VALIDATION_FAILED` Transaction으로 생성하거나 기존 Transaction의 상태로 연결하지 않는다.
- `ANALYZING`에서 위험 등급과 탐지 결과가 확정되지 않은 채 최종 처리 상태로 전이하지 않는다.
- `APPROVED`, `ADDITIONAL_AUTH_REQUIRED`, `HELD`를 위험 등급으로 사용하지 않는다.
- MEDIUM의 모니터링을 표현하기 위해 `APPROVED`와 `MONITORING`을 서로 경쟁하는 거래 상태로 중복 관리하지 않는다.
- LLM 실패나 AI 리포트 실패를 이유로 거래를 `FAILED`로 전이하지 않는다.
- 플랫폼·클라우드 운영자 또는 생성형 AI가 거래 위험 등급이나 최종 상태를 임의로 변경하지 않는다.
- 중복 요청이나 중복 탐지 결과가 이미 확정된 거래 상태를 역행시키거나 새 사건을 중복 생성하게 하지 않는다.

최종 상태에서 재분석 또는 정정이 필요한 경우 기존 상태를 되돌릴지 새 처리 이력·새 분석 버전을 만들지는 `TBD`이다.

## 9. 전이 조건

- 상태 전이는 현재 상태, 요청된 다음 상태와 승인된 위험 대응 정책을 함께 검증해야 한다.
- 위험 등급은 Rule·ML과 승인된 점수 통합 정책의 결과여야 하며 생성형 AI가 계산하지 않는다.
- Rule v1의 Evidence 저장, `DetectionResult COMPLETED`, 결과 채택과
  `ANALYZING → ANALYZED`는 하나의 쓰기 트랜잭션으로 처리한다. 다른 분석과
  위험 대응의 구체 경계는 후속 설계에서 확정한다.
- HIGH·CRITICAL의 새 사건 생성 전에 동일 거래 또는 동일 의심 흐름과 연결된 사건 존재 여부를 확인해야 한다.
- 외부 연동 결과가 불명확하면 성공, 정상 또는 위험정보 없음으로 임의 해석하지 않는다.
- 전이 요청에는 변경 원인과 관련 `transactionId`, 필요 시 `caseId` 및 `traceId` 후보를 연결할 수 있어야 한다.

## 10. 변경 주체

### 시스템

Spring Boot는 거래 상태와 업무 정합성의 최종 소유자이다. 검증 결과, 분석 결과와 승인된 위험 대응 정책을 근거로 상태를 변경한다.

FastAPI는 Feature 계산, Rule 실행과 ML 추론 결과를 제공할 수 있지만 거래 상태를 최종 확정하지 않는다.

### FDS 분석 담당자

거래와 탐지 근거를 조회하고 사건을 조사한다. 거래 모니터링 화면에서 거래 상태를 직접 변경하지 않는다. 사후 정정 권한과 절차는 `TBD`이다.

### 플랫폼·클라우드 운영자

장애와 처리 상태를 관찰하고 승인된 운영 절차를 수행한다. 위험 점수·위험 등급·거래 대응 결과와 거래 상태를 업무 판단으로 확정하지 않는다.

### 생성형 AI

거래 상태, 위험 점수·등급, 거래 대응 결과와 사건 생성 여부를 결정하거나 변경하지 않는다.

## 11. 멱등성 고려사항

### 동일 멱등성 키를 가진 거래 요청 재전송

- 최초 요청의 처리 결과를 식별해 동일한 업무 결과를 유지해야 한다.
- 같은 키와 같은 지문의 최초 처리가 완료되었으면 저장 형식과 HTTP 상태를
  재생한다. strict legacy는 `200 OK`, v1 envelope와 최종 v2 envelope는 저장된
  `201 Created`를 반환한다.
- 같은 키와 같은 지문의 최초 처리가 진행 중이면 `409 Conflict`와 `IDEMPOTENCY_REQUEST_IN_PROGRESS`를 반환한다.
- 같은 키에 다른 지문이 도착하면 `409 Conflict`와 `IDEMPOTENCY_KEY_CONFLICT`로 거부한다.
- `Idempotency-Key`는 8~128자의 승인된 ASCII 문자만 사용하며 `(operationScope, idempotencyKey)`를 Unique로 관리한다.
- 정규화 요청 지문은 SHA-256으로 계산하고 멱등 기록은 최초 선점부터 24시간 보존한다.
- 정확한 필드, 정규화, 저장과 만료 정책은 [`../04-database/transaction-intake-schema.md`](../04-database/transaction-intake-schema.md)를 따른다.

Rule 결과 채택과 `ANALYZING → ANALYZED` commit만으로는 최종 동기 성공 응답을
확정하지 않는다. 위험 대응 결과와 최종 거래 상태가 확정되고,
HIGH·CRITICAL이면 사건 생성 또는 기존 사건 연결까지 commit된 뒤에만 v2 성공
Snapshot을 확정한다. 현재 구현은 거래 접수 commit에서 `RECEIVED`/탐지 null
v1 Snapshot을 먼저 완료한다. 위험 등급별 목표 상태·대응 결과·사건 필수 여부를
반환하는 순수 정책과 HIGH·CRITICAL `ANALYZED` 거래의 사건·첫 연결 내부 영속
경계는 구현되었다. 하지만 이를 `FinancialTransaction`에 적용하는 전이,
`RiskResponseOutcome` 영속화, AuditLog와 사건 경계를 포함한 최종 원자적
commit은 아직 구현되지 않았다.

최종 업무 commit 뒤 Snapshot 완료가 실패하면 멱등 레코드는 `FAILED`로 전이하지
않고 `IN_PROGRESS`로 유지한다. 최초 요청은 `500 INTERNAL_ERROR`, 같은 요청은
`409 IDEMPOTENCY_REQUEST_IN_PROGRESS`이며 외부 호출과 업무 처리를 반복하지
않는다. 운영 복구만 확정된 상태를 검증해 동일 v2 Snapshot을 완료할 수 있다.

### 같은 요청의 동시 도착

- 하나의 요청만 최초 처리를 획득하고 나머지는 동일 거래의 기존 처리 결과를 참조해야 한다.
- 두 요청이 각각 별도 거래와 사건을 생성해서는 안 된다.

### 탐지 요청 재시도

- Rule v1 Client 자동 retry는 0회다.
- Timeout 실패와 늦은 성공 응답은 같은 거래·DetectionResult 잠금과 terminal
  상태 검증으로 하나만 확정한다.
- 실패 후 재분석이나 승인된 새 분석 버전은 후속 계약 없이는 시작하지 않는다.

### 동일 탐지 결과의 중복 저장

- 동일 거래와 동일 탐지 결과 버전의 결과가 여러 번 도착해도 하나의 유효
  결과로 유지한다.
- 거래 잠금 아래 다음 버전을 할당하고 거래별 버전 unique 제약을 사용한다.
  응답은 자신이 시작한 `detectionResultId`와 버전에만 적용한다.

### 동일 거래에 대한 중복 사건 생성 방지

- HIGH·CRITICAL 처리의 재시도, 중복 이벤트와 동시 실행으로 새 사건이 중복 생성되지 않아야 한다.
- Issue #154의 내부 사건 영속 경계는 거래 행을 먼저 비관적으로 잠그고 거래당
  활성 사건을 최대 하나로 검증한다. 동일 거래 재호출은 기존 활성 연결을 멱등
  반환하며 둘 이상이면 임의 선택하지 않고 정합성 오류로 거부한다.
- 기존 사건에 다른 거래를 추가하는 선정 정책과 사건 병합·분리는 후속
  사용자 결정이 필요한 `TBD`이다.

## 12. 동시성 고려사항

- 같은 거래를 처리하는 두 실행이 서로 다른 다음 상태를 동시에 확정하지 못하도록 해야 한다.
- 상태 변경 시 읽었던 현재 상태가 여전히 유효한지 검증해야 한다.
- 분석 완료와 Timeout 처리가 경합할 때 같은 거래·DetectionResult 잠금과
  terminal 상태 검증으로 하나의 일관된 결과만 유효해야 한다.
- 위험 대응 적용과 사건 생성이 동시에 또는 재시도로 수행되어도 상태와 사건 연결의 정합성을 유지해야 한다.
- 현재 거래 접수 물리 스키마는 `financial_transaction.version`을 사용한 낙관적 잠금을 적용한다.
- version 불일치는 기존 값을 덮어쓰지 않고 `409 Conflict`와 `CONCURRENT_MODIFICATION`으로 처리한다.
- 충돌 시 재조회, 재시도 또는 사용자·운영자 확인 중 어떤 정책을 적용할지는 후속 설계에서 결정한다.

## 13. 실패·재시도 원칙

- LLM 실패는 거래 위험 판단 결과를 변경하지 않는다.
- AI 리포트 실패는 거래 상태 실패가 아니다.
- FastAPI Timeout 시 Spring Boot는 대상 DetectionResult와 거래를 `FAILED`로
  기록하고 결과를 채택하지 않는다.
- FastAPI 없이 Rule v1 성공으로 처리하지 않는다. 응답이 없다는 이유로 정상
  거래로 승인하지 않으며 Client 자동 retry와 fallback은 없다.
- External Risk timeout·unavailable·invalid response는 위험정보 없음 또는
  `UNMATCHED`로 해석하지 않고 typed failure로 전파하며 현재 분석을 계속하지 않는다.
  cache, stale data와 fallback은 현재 승인 계약에 없다.
- 후속 거래 접수 연결에서는 External Risk 실패 시 거래와 분석 결과를 `FAILED`로
  확정하고 기존 외부 의존성 오류 매핑을 사용한다. 이 연결과 공개 오류 매핑은 아직
  구현되지 않았다.
- DB 저장 결과가 불명확하면 성공으로 임의 처리하지 않는다.
- 실패 후 재분석·수동 복구는 별도 후속 계약으로 정한다.
- Rule v1 Client와 External Risk의 자동 retry는 0회다. External Risk cache,
  Circuit Breaker 또는 fallback은 별도 Issue와 계약 승인 없이 도입하지 않는다.
- Kafka는 현재 필수 처리 흐름이 아니다. 향후 도입 시 중복 이벤트에도 같은 거래·사건 결과가 유지되어야 한다.

## 14. 감사 로그 요구사항

다음 변경과 결과는 감사 가능하게 기록해야 한다.

- Validation을 통과한 거래 접수와 이후 처리 결과
- 거래 처리 상태 변경
- 위험 점수·위험 등급 확정과 사용한 탐지 결과 버전
- 위험 대응 결과 적용
- 사건 생성 또는 기존 사건 연결 결과
- 실패, 재시도, 중복 요청과 멱등 처리 결과
- 외부 위험정보 조회 성공 상태·Provider 기준 시각 또는 실패 category
- 운영자에 의한 승인된 수동 조치

감사 기록에는 다음 정보가 필요하다.

- 변경 사용자 또는 시스템
- 변경 시각
- 변경 대상
- 이전 값
- 변경 후 값
- 변경 사유
- 관련 `transactionId`
- 관련 `caseId` 후보
- `traceId` 후보
- 재시도·중복 처리 식별 정보 후보

민감 정보 원문은 감사 로그에 기록하지 않는다. Issue #156에서 append-only
AuditLog Entity, Flyway V7과 INSERT 전용 Persistence 경계는 구현되었다. 기존
거래·사건 Service의 실제 AuditLog 통합과 보존 기간은 아직 확정하지 않았다.

Validation 거절은 Transaction이나 AuditLog 행을 만들지 않는다. 오류 응답, `traceId`, 민감정보를 제외한 로그와 승인된 저카디널리티 운영 메트릭으로만 관측한다.

## 15. 사용자 결정 필요 항목

- 추가 인증 성공·실패·만료 후 허용할 거래 상태 전이
- 최종 상태에서 재분석·정정이 필요할 때 기존 상태 변경 또는 새 이력 생성 여부
- `FAILED` 멱등 요청의 새 키 재처리와 재분석 정책
- External Risk 실패 후 재분석·수동 복구 정책
- 탐지·상태 전이 재시도 가능 오류, 횟수와 간격
- 동일 거래 또는 연관 거래의 사건 중복 방지와 병합·분리 기준
- 위험 대응과 사건 연결 중 일부만 성공했을 때의 복구·보상 구현 방식
- 동시 상태 변경 충돌 후 재조회·자동 재시도·운영 확인 방식
- 감사 로그 보존 기간과 접근 범위

## 16. 후속 ERD·API 설계 항목

후속 설계에서는 다음 항목을 사용자 승인으로 구체화해야 한다.

- 거래 처리 상태, 위험 등급, 위험 대응 결과와 사건 연결의 분리 표현
- 상태 및 탐지 결과의 변경 이력과 버전 표현
- `FAILED` 멱등 기록의 재시도와 만료 정리 방식
- 허용 전이를 보장할 데이터 정합성 제약과 트랜잭션 경계
- 동시성 충돌 탐지와 응답 계약
- 분석 요청·결과의 식별 및 재시도 계약
- 사건 생성 또는 기존 사건 연결의 원자성·복구 경계
- 감사 로그 조회에 필요한 연결 정보
- FastAPI와 External Risk 장애를 표현할 오류·처리 결과 계약

거래 접수의 구체적인 DB 컬럼, 제약조건, 인덱스, 멱등 상태 코드와 낙관적 잠금은 [`../04-database/transaction-intake-schema.md`](../04-database/transaction-intake-schema.md)에서 확정한다. Java 구현, 탐지·사건 물리 스키마와 그 밖의 상태 코드는 후속 승인 범위이다.
