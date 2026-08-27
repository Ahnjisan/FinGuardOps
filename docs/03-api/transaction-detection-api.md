# 거래·행동·탐지 API

## 1. 문서 목적

이 문서는 FinGuardOps의 거래 접수·조회, 행동 이벤트 수집·조회와 탐지 결과 조회 REST API 계약을 정의한다.

이 계약은 이후 Spring Boot Controller, 요청·응답 DTO, Validation, Service, 테스트와 OpenAPI 구현의 기준이다. API 공통 표현, 금액, 페이지네이션, 멱등성, 오류 응답과 추적 원칙은 [`api-conventions.md`](./api-conventions.md)를 따른다. 기존 멱등 Snapshot 전환은 [`ADR-004`](../07-decisions/ADR-004-idempotency-response-snapshot-transition.md), 최종 성공과 Snapshot v2·완료 간극 복구는 [`ADR-006`](../07-decisions/ADR-006-final-transaction-success-and-idempotency-recovery.md), External Risk 선행 실패 저장·재생 결정은 [`ADR-007`](../07-decisions/ADR-007-external-risk-idempotent-failure-replay-contract.md)을 따른다.

## 2. 범위와 책임 경계

### 2.1 처리 흐름

다음은 ADR-003에 따라 구현된 최종 동기 분석 흐름이다.

```text
Client
→ Spring Boot 거래 접수
→ 헤더·요청 Validation
→ fingerprint 계산
→ Idempotency IN_PROGRESS 단일 승자 선점 commit
→ RECEIVED 거래 저장·Idempotency record 연결 commit
→ DB 트랜잭션 밖 External Risk 조회
→ 성공이면 FastAPI /api/v2/rule-analysis와 위험 대응 최종화
→ 최종 성공 Snapshot v2 또는 External Risk Failure Snapshot으로 terminal 전이 commit
→ Client 응답 또는 같은 key·fingerprint의 저장 응답 재생
```

public `POST /api/v1/transactions`는 위 최종 동기 흐름으로 구현되어 있다. 입력 검증,
fingerprint와 Provider 가용성 확인 뒤 `IN_PROGRESS` 단일 승자를 선점하고 거래
`RECEIVED`·Idempotency 연결을 commit한 다음 DB transaction 밖에서 External Risk와
Rule v2를 호출한다.
DetectionResult·DetectionEvidence의 물리 영속 모델과 FastAPI
`POST /api/v1/rule-analysis` HTTP 경계, Spring Boot `RuleAnalysisHttpClient`,
Timeout·Trace 전달과 응답 검증·오류 분류, 거래 분석 Snapshot 조합·HTTP
오케스트레이션과 탐지 실행 결과 자동 생성·채택은 구현되었다. 실제 External Risk
HTTP Provider와 local/dev/test Mock, per-invocation Policy→Rule v2 coordinator,
위험 대응 최종화, External Risk Failure Snapshot·공개 typed 오류 재생과 성공
Snapshot v2 writer가 public 거래 접수에 연결되었다. Snapshot 완료 간극·장기
`IN_PROGRESS` 운영 복구와 RuleVersion 운영 publish는 아직 구현되지 않았다.
Spring Boot 분석 처리의 기준은
[Spring Boot Rule v1 분석 오케스트레이션·결과 채택 계약](../01-requirements/spring-rule-analysis-orchestration-contract.md)이다.
위험 등급별 거래 상태·`RiskResponseOutcome`·사건 필수 여부를 반환하는 순수
decision 정책과 이를 거래 Entity에 적용하는 내부 경계가 구현되었다.
`FraudCase`·`CaseTransaction`과 Flyway V6, append-only AuditLog V7을 재사용하며,
HIGH·CRITICAL은 새 사건·첫 연결 또는 기존 활성 연결을 최종 상태와 함께 확정한다.
이 최종화 경계는 public 거래 접수에서 호출되지만 별도 공개 최종화 API를 추가하지 않는다.

`POST /api/v2/rule-analysis`는 External Risk를 필수 입력으로 받지만 계속
Rule v1 R001~R004를 실행한다. 현재 v1 Endpoint는 당장 제거하지 않으며 v2
Java·Python DTO·Client, 내부 오케스트레이션과 Mock 성공 Snapshot 전달 coordinator는
구현됐다. coordinator 직접 재호출·멱등 경계 밖 동시 호출은 Provider를 다시 호출할 수
있지만 public 거래 접수의 Idempotency claim은 동일 요청의 단일 Provider 승자를
보장하고 terminal 재생에서 downstream을 호출하지 않는다. 상세 계약은
[External Risk·Rule 분석 입력 계약](../01-requirements/external-risk-rule-analysis-input-contract.md)을
따른다.

### 2.2 Spring Boot 책임

다음 항목은 Spring Boot가 소유하는 현재 거래 처리 책임이다.

- 거래와 행동 이벤트 요청을 검증한다.
- 거래 생성 요청의 멱등성과 `transactionId` 중복을 관리한다.
- 행동 이벤트의 `eventId` 중복을 관리한다.
- 실제 External Risk HTTP Provider 또는 local/dev/test Mock을 조회하고 조회 상태를
  관리한다.
- External Risk Provider 호출 전에 멱등 단일 승자를 확정하고 호출 중 DB
  트랜잭션과 거래 잠금을 유지하지 않는다.
- FastAPI 분석 호출을 오케스트레이션한다.
- FastAPI가 반환한 결과의 요청 연결, 완전성, 버전과 처리 가능 여부를 검증한다.
- DetectionResult와 DetectionEvidence를 저장한다.
- 사용할 DetectionResult를 채택하고 `adoptedDetectionResultId`로 식별한다.
- 채택 결과를 Transaction의 현재 `riskLevel`에 반영한다.
- 승인된 정책에 따라 `riskResponseOutcome`과 `processingStatus`를 결정한다.
- HIGH·CRITICAL 처리에서 사건 생성 또는 기존 사건 연결을 결정한다.
- 업무 결과와 감사·추적 식별자의 정합성을 관리한다.

### 2.3 FastAPI 책임

다음 항목은 FastAPI 책임 범위이다. 현재 `ai-service/`에는 R001~R004 실행,
scoring, Evidence 변환과 Rule 분석 결과 조합의 내부 경로에 더해 Pydantic
요청·응답 DTO와 FastAPI `POST /api/v1/rule-analysis` HTTP 경계가 구현되어
있다. Spring Boot Client와 v1·v2 내부 DetectionResult 생성·채택·영속화도
  구현되어 있고 실제 Provider·거래 접수 v2 연결도 완료되었다. ML은 아직 구현되지 않았다.
상세 Client 계약은
[Rule v1 내부 분석 API](./rule-v1-analysis-api.md#13-spring-boot-client-연동-계약)를
따른다.

- Feature를 계산한다.
- 승인된 Rule을 실행한다.
- ML 추론을 수행한다.
- 위험 점수, 위험 등급, Reason Code와 탐지 근거를 계산해 반환한다.
- 사용한 모델과 Feature 버전 및 분석 실행 정보를 반환한다.

FastAPI는 다음 작업을 수행하지 않는다.

- 거래 상태 직접 변경
- Transaction 현재 위험값 직접 변경
- 사건 직접 생성 또는 사건 상태 변경
- 거래 승인·추가 인증·보류 결정
- Spring Boot 업무 데이터 직접 저장
- 최종 이상거래 판정

## 3. 공통 경로와 식별자

기본 경로는 다음과 같다.

```text
/api/v1
```

| 식별자 | 이 문서에서의 의미 |
| --- | --- |
| `transactionId` | 클라이언트가 거래 생성 요청으로 전달하는 UUID v4 거래 업무 식별자 |
| `eventId` | 행동 이벤트 업무 식별자 |
| `detectionResultId` | Spring Boot가 생성하는 UUID v4 탐지 결과 업무 식별자 |
| `caseId` | 생성되었거나 연결된 사건 업무 식별자 |
| `traceId` | Spring Boot, External Risk Mock과 FastAPI 호출 흐름 추적 식별자 |

이 식별자들은 내부 DB 식별자가 아니며 서로 대체할 수 없다.

## 4. 공통 값

### 4.1 거래 유형

지원 거래 유형은 다음과 같다.

```text
ACCOUNT_TRANSFER
OPEN_BANKING_TRANSFER
ATM_WITHDRAWAL
LOAN_DISBURSED
```

`LOAN_DISBURSED`는 대출 실행을 표현하는 Mock 금융거래 유형이며 행동 이벤트가 아니다. 실제 대출 원장, 상품·심사 또는 실행 기능을 FinGuardOps가 소유한다는 의미는 아니다.

### 4.2 거래 처리 상태

`processingStatus`는 거래가 현재 어느 처리 단계에 있는지를 나타낸다.

```text
RECEIVED
ANALYZING
ANALYZED
APPROVED
ADDITIONAL_AUTH_REQUIRED
HELD
FAILED
```

### 4.3 위험 등급

`riskLevel`은 채택된 Rule·ML 탐지 결과의 위험 수준이다.

```text
LOW
MEDIUM
HIGH
CRITICAL
```

### 4.4 위험 대응 결과

`riskResponseOutcome`은 위험 등급에 Spring Boot의 승인된 Mock 대응 정책을 적용한
결과이다. 확정된 Enum 값은 다음과 같다.

```text
APPROVED
APPROVED_WITH_MONITORING
ADDITIONAL_AUTH_REQUIRED
HELD
```

값 이름과 위험 등급별 매핑은 확정 계약이다. 현재 순수 decision 정책은 LOW를
`APPROVED`, MEDIUM을 `APPROVED_WITH_MONITORING`, HIGH를
`ADDITIONAL_AUTH_REQUIRED`, CRITICAL을 `HELD`로 결정한다. 거래 상태, 위험
등급과 위험 대응 결과는 다음처럼 분리한다.

```json
{
  "processingStatus": "APPROVED",
  "riskLevel": "MEDIUM",
  "riskResponseOutcome": "APPROVED_WITH_MONITORING"
}
```

`MONITORING`을 `processingStatus`로 사용하지 않고 `BLOCKED`를 실제 거래 차단 상태로 확정하지 않는다.

### 4.5 행동 이벤트 유형

지원 행동 이벤트 유형은 다음과 같다.

```text
LOGIN
LOGIN_FAILED
DEVICE_REGISTERED
PASSWORD_CHANGED
OTP_REISSUED
BENEFICIARY_REGISTERED
TRANSFER_LIMIT_CHANGED
TRANSFER_REQUESTED
ATM_WITHDRAWAL_REQUESTED
```

`LOAN_DISBURSED`는 행동 이벤트에 포함하지 않는다.

### 4.6 탐지 분석 상태

`analysisStatus`는 `PENDING`, `IN_PROGRESS`, `COMPLETED`, `FAILED`를
사용한다. 실패한 분석과 동일 분석의 재시도도 새
`detectionResultVersion`을 소비하며 기존 결과를 수정하지 않는다.

### 4.7 위험 점수 의미

- API는 승인된 통합 정책이 산출한 최종 `riskScore`를 반환한다.
- 각 DetectionEvidence는 해당 근거의 `scoreContribution`을 반환할 수 있다.
- Rule v1은 amount·security 그룹 상한을 적용해 `0`~`100` 범위의 정수 점수를 계산하며, 상세 공식과 등급 경계는 [`../01-requirements/rule-v1-detection-contract.md`](../01-requirements/rule-v1-detection-contract.md)를 따른다.
- Rule v1의 `scoreContribution`은 그룹 상한 적용 전 개별 Rule 가중치이므로 Evidence 기여도의 단순 합이 최종 `riskScore`와 다를 수 있다.
- Rule v1 이후 ML·External Risk·자금흐름 점수 통합과 점수 정밀도는 후속 계약에서 결정한다.
- `ruleScore`와 `mlScore`는 현재 필수 외부 API 필드로 확정하지 않는다.

## 5. 거래 생성

### 5.1 요청

```http
POST /api/v1/transactions
Content-Type: application/json
Idempotency-Key: <required>
```

`Idempotency-Key`는 필수이다. 길이는 8~128자이고 영문, 숫자, 마침표(`.`), 밑줄(`_`), 콜론(`:`), 하이픈(`-`)만 허용한다. 누락하거나 형식이 올바르지 않으면 Transaction과 멱등 기록을 생성하지 않고 `400 Bad Request`와 `VALIDATION_ERROR`를 반환한다. 작업 범위는 `POST:/api/v1/transactions`이다. 현재 DB는 `expiresAt`에 최초 선점의 24시간 후를 저장하지만 Service가 이를 판정하지 않고 정리 작업도 없으므로 실질적인 만료 정책은 시행되지 않는다.

### 5.2 요청 필드

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `transactionId` | string | 필수 | UUID v4 거래 업무 식별자 |
| `transactionType` | string | 필수 | 지원 거래 유형 |
| `amount` | string | 필수 | 0보다 큰 10진 정수 문자열 |
| `currencyCode` | string | 필수 | 초기에는 `KRW`만 허용 |
| `occurredAt` | string | 필수 | 거래 발생 시각, UTC ISO-8601 |
| `externalCustomerRef` | string | 필수 | 실제 고객번호가 아닌 외부 고객 참조값 |
| `senderAccountRef` | string | 필수 | 실제 계좌번호가 아닌 거래 기준 계좌 참조값 |
| `recipientAccountRef` | string 또는 null | 거래 유형별 조건부 | 실제 계좌번호가 아닌 외부 수취 계좌 참조값 |
| `channel` | string | 필수 | 거래가 FinGuardOps에 유입된 접수 경로 |
| `deviceRef` | string 또는 null | 선택 | 실제 기기 식별자 원문 대신 사용하는 참조값 |

`senderAccountRef`는 네 거래 유형 모두에서 FinGuardOps가 추적하는 기준 계좌를 나타낸다. `LOAN_DISBURSED`에서는 대출금 입금 대상 계좌를 이 필드로 표현하며 별도 `accountRef` 필드를 추가하지 않는다.

거래 유형별 수취 계좌와 채널 계약은 다음과 같다.

| `transactionType` | `recipientAccountRef` | 허용 `channel` | 채널 의미 |
| --- | --- | --- | --- |
| `ACCOUNT_TRANSFER` | 필수 | `MOBILE_BANKING` | 모바일뱅킹을 통한 계좌이체 |
| `OPEN_BANKING_TRANSFER` | 필수 | `OPEN_BANKING` | 오픈뱅킹 연계 거래 |
| `ATM_WITHDRAWAL` | 금지, 반드시 null | `ATM` | ATM 인출 |
| `LOAN_DISBURSED` | 금지, 반드시 null | `CORE_BANKING` | 코어뱅킹 Mock에서 전달된 대출 실행 이벤트 |

`ACCOUNT_TRANSFER`의 인터넷뱅킹과 창구 등 추가 채널은 MVP 범위에서 제외하며 후속 계약 변경으로만 확장한다.

### 5.3 요청 예시

```json
{
  "transactionId": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
  "transactionType": "ACCOUNT_TRANSFER",
  "amount": "1250000",
  "currencyCode": "KRW",
  "occurredAt": "2026-07-23T01:15:30Z",
  "externalCustomerRef": "cust_ref_demo_a7f2",
  "senderAccountRef": "acct_ref_demo_s91c",
  "recipientAccountRef": "acct_ref_demo_r44d",
  "channel": "MOBILE_BANKING",
  "deviceRef": "device_ref_demo_18b3"
}
```

### 5.4 요청 검증

- `transactionId`는 UUID v4여야 하며 기존 거래와 중복되지 않아야 한다.
- `transactionType`은 지원 목록에 있어야 한다.
- `amount`는 지수 표기나 소수부가 없는 0보다 큰 10진 정수 문자열이어야 한다.
- `currencyCode`는 `KRW`여야 한다.
- `occurredAt`은 UTC ISO-8601 `Z` 형식이고 Validation 시점의 서버 시각보다 최대 5분 미래까지 허용한다.
- `senderAccountRef`는 모든 거래 유형에서 필수이다.
- 거래 유형별 `recipientAccountRef` 필수·금지와 `channel` 조합을 검증한다.
- 참조값에 실제 고객번호·계좌번호 원문을 사용하지 않는다.
- 알 수 없는 상세 필드를 업무 데이터나 FastAPI 입력으로 자동 전달하지 않는다.

참조값의 길이와 공백, PostgreSQL 타입 및 DB `CHECK`는 [`../04-database/transaction-intake-schema.md`](../04-database/transaction-intake-schema.md)를 따른다.

#### 5.4.1 API 검증 실패와 Transaction 상태 경계

- JSON 파싱, 필수 `Idempotency-Key`, 기본 필드 형식 또는 거래 유형별 도메인 검증에 실패한 요청은 Transaction과 멱등 기록을 생성하지 않는다.
- 잘못된 요청을 저장하기 위해 임의의 `transactionId`나 Transaction을 생성하지 않는다.
- 형식은 올바르지만 거래 유형별 도메인 규칙을 위반한 요청은 `422 Unprocessable Entity`로 처리한다.
- Validation 거절은 오류 응답, `traceId`, 민감정보를 제외한 로그와 운영 메트릭으로만 관측한다.
- `VALIDATION_FAILED`는 현재 거래 접수의 영속 `processingStatus`에서 제외한다.
- 모든 Validation을 통과해 최초 저장되는 Transaction의 초기 상태는 `RECEIVED`이다.

### 5.5 성공 응답 필드

현재 단계적 거래 접수 성공 응답은 다음 여덟 필드를 정확히 포함한다.

| 필드 | 타입 | 현재 거래 접수 값 |
| --- | --- | --- |
| `transactionId` | string | 저장된 거래의 UUID v4 업무 식별자 |
| `processingStatus` | string | `APPROVED`, `ADDITIONAL_AUTH_REQUIRED`, `HELD` 중 최종 상태 |
| `riskLevel` | string | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` 중 채택 결과 |
| `riskResponseOutcome` | string | 위험 등급별 확정 대응 결과 |
| `adoptedDetectionResultId` | string | 채택된 DetectionResult UUID v4 |
| `caseId` | string 또는 null | LOW·MEDIUM은 null, HIGH·CRITICAL은 사건 UUID v4 |
| `createdAt` | string | DB/JPA 저장 결과의 실제 생성 시각, UTC ISO-8601 |
| `traceId` | string | 현재 HTTP 요청의 추적 식별자 |

`caseId`는 JSON에서 생략하지 않고 nullable string으로 유지한다. 나머지 탐지·대응
필드는 신규 성공에서 non-null이며 아래 조합과 ADR-006을 따른다.

Issue #178 이전 신규 요청은 `RECEIVED`와 네 null 필드를 Snapshot v1로 저장했다.
현재 신규 성공은 DetectionResult 채택과 거래 `ANALYZED`만으로 확정하지 않고 위험
대응·사건·AuditLog 최종화 commit 뒤 Snapshot v2를 별도 completion transaction으로
저장한다. 기존 legacy·v1 Snapshot은 소급 변환하지 않고 저장 status 그대로 재생한다.

#### 5.5.1 최종 동기 성공 응답

최종 동기 성공은 Rule 결과·Evidence 저장과 채택, 위험 등급·대응, 최종 거래
상태가 모두 확정되고 HIGH·CRITICAL이면 사건 생성 또는 기존 사건 연결까지
commit된 뒤에만 허용한다.

| `riskLevel` | `processingStatus` | `riskResponseOutcome` | `caseId` |
| --- | --- | --- | --- |
| `LOW` | `APPROVED` | `APPROVED` | null |
| `MEDIUM` | `APPROVED` | `APPROVED_WITH_MONITORING` | null |
| `HIGH` | `ADDITIONAL_AUTH_REQUIRED` | `ADDITIONAL_AUTH_REQUIRED` | 필수 |
| `CRITICAL` | `HELD` | `HELD` | 필수 |

최종 v2 response body도 현재와 같은 일곱 업무 필드를 사용하며 HTTP 응답에서만
현재 요청의 `traceId`를 여덟 번째 필드로 결합한다. `RECEIVED`, `ANALYZING`,
`ANALYZED`, `FAILED`는 v2 성공 body에 사용할 수 없다.

저장 envelope는 별도 `snapshotType` 없이
`responseSchemaVersion=transaction-create-response-v2`와
`codecVersion=transaction-intake-snapshot-envelope-v2` tuple로 식별한다. 최상위는
`responseBody`, `httpStatus`, 두 version 필드와 `finalizedAt`만, body는 위 일곱
업무 필드만 정확히 허용한다. 성공 v2에는 오류 응답 전용 `fieldErrors`가 없다.
고정 순서 compact JSON의 canonical UTF-8 크기는 최대 4096 byte이며 이 제한은 v2
encode·decode에만 적용한다.

### 5.6 성공 응답 예시

최초 생성:

```http
HTTP/1.1 201 Created
Content-Type: application/json
X-Trace-Id: trace_demo_tx_0001
```

```json
{
  "transactionId": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
  "processingStatus": "APPROVED",
  "riskLevel": "LOW",
  "riskResponseOutcome": "APPROVED",
  "adoptedDetectionResultId": "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101",
  "caseId": null,
  "createdAt": "2026-07-23T01:15:31Z",
  "traceId": "trace_demo_tx_0001"
}
```

이번 거래 접수 API의 `201 Created` 응답에는 `Location` 헤더를 적용하지 않는다. `X-Trace-Id`는 공통 Trace Filter가 설정하며 응답 body의 `traceId`와 같다.

### 5.7 멱등성과 중복

- 같은 `Idempotency-Key`와 같은 요청의 최초 처리가 완료되었으면 새 거래·탐지·사건을 생성하지 않고 기존 업무 결과를 반환한다. 무버전 legacy Snapshot은 `200 OK`, v1과 v2 envelope는 저장되고 검증된 `201 Created`를 사용한다.
- 모든 Snapshot 형식은 최초 확정 업무 값을 유지하고 `traceId`만 현재 재전송 요청의 값을 사용한다. External Risk 확정 실패의 exact replay 범위는 HTTP 상태, 공개 `code`, 안전한 `message`, 빈 `fieldErrors`의 의미적 동일성이다. `traceId`, HTTP 헤더, JSON byte ordering은 exact replay 범위가 아니며 공개 `replayed` 필드를 추가하지 않는다.
- 같은 `Idempotency-Key`와 같은 요청의 최초 처리가 진행 중이면 새 처리를 시작하지 않고 `409 Conflict`와 `IDEMPOTENCY_REQUEST_IN_PROGRESS`를 반환한다.
- 같은 키에 다른 요청 내용이 오면 `409 Conflict`와 `IDEMPOTENCY_KEY_CONFLICT`를 반환한다.
- 다른 키로 같은 `transactionId`가 오면 `409 Conflict`와 `DUPLICATE_TRANSACTION`을 반환한다.
- 같은 `transactionId`에 다른 요청 내용이 오면 기존 거래를 덮어쓰거나 재분석으로 해석하지 않는다.
- 요청 지문은 정규화한 `transactionId`, `transactionType`, `amount`, `currencyCode`, `occurredAt`, `externalCustomerRef`, `senderAccountRef`, `recipientAccountRef`, `channel`, `deviceRef`를 고정 순서 JSON으로 직렬화한 뒤 SHA-256으로 계산한다.
- `traceId`, `Idempotency-Key`, 내부 PK, 생성·수정 시각, version, 처리 상태와 그 밖의 서버 생성 필드는 지문에서 제외한다.
- 요청 지문, `IN_PROGRESS`·`COMPLETED`·`FAILED` 상태와 현재 완료 응답 snapshot의 물리 저장 기준은 [`../04-database/transaction-intake-schema.md`](../04-database/transaction-intake-schema.md)를 따른다. `expires_at`의 24시간 값은 현재 저장 제약이며 만료 판정·키 재사용·정리 정책은 구현되지 않았다.
- `eventId` 중복과 `DUPLICATE_EVENT`는 행동 이벤트 API의 책임이며 이 거래 접수 API 범위에 포함하지 않는다.

**legacy·v1 호환**: Issue #178 이전 v1 완료 요청은 `RECEIVED`와 네 탐지 관련 JSON
null을 가진 일곱 업무 필드다. 기존 무버전 Snapshot은 정확한 일곱 필드,
`RECEIVED`, 네 JSON null일 때만 strict legacy codec으로 복원한다. legacy는 `200`,
v1은 저장된 `201`을 유지하며 신규 envelope나 최신 탐지 결과로 소급 갱신하지 않는다.

**현재 public v2 경계**: 신규 최종 성공 요청은
`responseSchemaVersion=transaction-create-response-v2`,
`codecVersion=transaction-intake-snapshot-envelope-v2`, `httpStatus=201`로
저장한다. typed 모델·codec·dispatcher, PostgreSQL JSONB 저장·조회, public intake·
최종화 호출과 Idempotency 완료 writer가 연결되었다. legacy와 v1을 수정·backfill하거나
최신 DB 상태로 보정하지 않으며 기존 version의 의미를 확장하지 않는다.

알 수 없는 구조·버전 또는 역직렬화 실패를 최신 거래 상태로 보정하거나 신규 거래·탐지 처리로 우회하지 않는다. 멱등 재생은 최초 명령 결과의 책임이고 최신 거래·탐지 상태는 별도 조회 API의 책임이다.

처리 중인 동일 요청의 응답 예시는 다음과 같다.

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "code": "IDEMPOTENCY_REQUEST_IN_PROGRESS",
  "message": "같은 멱등 요청이 처리 중입니다.",
  "traceId": "trace_demo_processing_01",
  "fieldErrors": []
}
```

`FAILED`인 같은 operation scope·키·fingerprint 요청은 자동 재실행하지 않는다.
현재 code-only legacy `FAILED`를 공개 응답으로 축약하는 whitelist는 다음과 같다.

| 저장된 `failureCode` | HTTP 상태 | 공개 오류 코드 | 고정 message |
| --- | --- | --- | --- |
| `DUPLICATE_TRANSACTION` | `409 Conflict` | `DUPLICATE_TRANSACTION` | `이미 존재하는 transactionId입니다.` |
| `DEPENDENCY_TIMEOUT` | `503 Service Unavailable` | `DEPENDENCY_TIMEOUT` | `탐지 서비스를 사용할 수 없습니다.` |

저장된 `failureCode`가 null, 빈 값, 알 수 없는 값 또는 내부 전용 값이면 `500 Internal Server Error`, `INTERNAL_ERROR`, `요청을 처리하는 중 오류가 발생했습니다.`로 축약한다. 원래 `failureCode` 문자열을 공개 code나 message로 전달하지 않는다. 현재 거래 저장 또는 멱등 완료의 예기치 않은 실패를 기록하는 내부 코드 `TRANSACTION_INTAKE_FAILED`도 공개 whitelist가 아니므로 `INTERNAL_ERROR`로 처리한다.

ADR-007이 확정했고 현재 내부 Failure Snapshot 모델·codec이 사용하는 External Risk
전용 매핑은 다음과 같다. 내부
`failureCategory`나 Provider 상세는 공개 body에 포함하지 않는다.

| 내부 category | HTTP 상태 | 공개 오류 code | 공개 안전 message |
| --- | --- | --- | --- |
| `TIMEOUT` | `503 Service Unavailable` | `DEPENDENCY_TIMEOUT` | `탐지 서비스를 사용할 수 없습니다.` |
| `UNAVAILABLE` | `503 Service Unavailable` | `DEPENDENCY_UNAVAILABLE` | `탐지 서비스를 사용할 수 없습니다.` |
| `INVALID_REQUEST` | `500 Internal Server Error` | `INTERNAL_ERROR` | `요청을 처리하는 중 오류가 발생했습니다.` |
| `UNSUPPORTED_CAPABILITY` | `500 Internal Server Error` | `INTERNAL_ERROR` | `요청을 처리하는 중 오류가 발생했습니다.` |
| `INVALID_RESPONSE` | `500 Internal Server Error` | `INTERNAL_ERROR` | `요청을 처리하는 중 오류가 발생했습니다.` |
| `TRANSFORMATION_ERROR` | `500 Internal Server Error` | `INTERNAL_ERROR` | `요청을 처리하는 중 오류가 발생했습니다.` |

현재 구현된 내부 저장 형식은 성공 Snapshot v1·v2와 구분되는
`snapshotType=external-risk-failure`,
`responseSchemaVersion=transaction-create-error-v1`,
`codecVersion=external-risk-failure-snapshot-envelope-v1`이다. strict envelope의
정확한 필드·4 KiB 제한·fail-closed decoder 계약은 ADR-007을, V8 적용 물리
제약은 [거래 접수 스키마](../04-database/transaction-intake-schema.md)를 따른다.
`responseBody.code`는 `idempotency_record.failure_code`와 같아야 하고
`fieldErrors`는 빈 배열이다.

최초 확정 실패와 같은 키·fingerprint 재생은 위 표의 HTTP 상태·공개 code·안전
message·빈 `fieldErrors`를 의미적으로 동일하게 반환한다. 재생 시 External Risk
Provider, FastAPI와 위험 대응 최종화는 모두 `0회` 호출한다. 새
`Idempotency-Key`를 사용한 같은 `transactionId` 접수는 unique constraint와 충돌하므로
공식 재처리 수단이 아니다. 재처리는 후속 Issue에서 별도 operation scope의 승인된
복구·재분석 명령으로 설계한다.

category mapper, Failure Snapshot strict codec·decoder, V8 Migration과
`REQUIRES_NEW` 기반 내부 저장·조회 경계는 구현되었다. Snapshot이 null인 legacy
`FAILED`와 non-null typed `FAILED`를 멱등 계층에서 구분하고, typed 데이터는 External
Risk 계층에서 strict decode한다. public intake 연결·공개 mapper·HTTP 재생 경로도
구현되었다. 전용 대상은 `ExternalRiskLookupException`의 위 여섯 category뿐이며
예상하지 못한 일반 `RuntimeException`을 category로 변환하거나 전용 Snapshot으로
저장하지 않는다.

External Risk failure writer 실패·저장 직전 crash 또는 최종 성공 완료 간극은
durable terminal 결과가 아니다. 멱등 레코드는 `IN_PROGRESS`로 남을 수 있고 최초
요청의 공개 응답은 `500 INTERNAL_ERROR`, 같은 키·fingerprint 재요청은
`409 IDEMPOTENCY_REQUEST_IN_PROGRESS`이다. DB만으로 Provider 호출 여부를 확정할 수
없으므로 External Risk를 자동 재호출하지 않는다. 원본 typed exception을 유지하고
writer 오류를 suppressed로 보존하며 실제 운영 복구는 후속 Issue에서 정한다.

### 5.8 의존 서비스 Timeout

#### 5.8.1 External Risk 선행 실패

- `TIMEOUT`, `UNAVAILABLE`, `INVALID_REQUEST`, `UNSUPPORTED_CAPABILITY`,
  `INVALID_RESPONSE`, `TRANSFORMATION_ERROR`는 typed failure로 전파하고 현재 분석을
  계속하지 않는다.
- 실패를 cache, stale data, fallback, `UNMATCHED`, 위험정보 없음 또는 안전으로
  변환하지 않는다. 현재 `ExternalRiskSnapshot`은 성공 결과만 표현한다.
- public 거래 접수에서는 Transaction을 `RECEIVED`로 유지하고 DetectionResult를
  생성하지 않으며 FastAPI·위험 대응 최종화를 호출하지 않는다. 성공 Snapshot v2와
  사건·AuditLog도 만들지 않는다.
- Failure Snapshot과 `FAILED`가 정상 commit된 경우 여섯 category 모두 같은
  operation scope·key에서 terminal이다. 같은 fingerprint 재생은 Provider를 다시
  호출하지 않고 5.7의 저장된 안전 응답을 반환한다.
- 자동 retry, fallback, cache, Circuit Breaker 또는 stale data는 이 계약에 포함하지
  않는다.

#### 5.8.2 FastAPI Timeout

- Spring Boot는 임의 위험 점수나 `LOW` 결과를 생성하지 않는다.
- 완료·검증된 DetectionResult가 없으므로 `adoptedDetectionResultId`를 설정하지 않는다.
- Rule v1 Spring Boot Client의 자동 retry는 `0회`이며 timeout 응답을 반복
  호출하거나 fallback 결과로 바꾸지 않는다.
- 대상 DetectionResult와 Transaction을 `FAILED`로 기록하는 commit이 확인되면
  결과를 채택하거나 사건을 생성하지 않고 멱등 실패를 확정한다. 거래가
  `ANALYZING`이거나 결과 상태가 불확실하면 멱등 레코드는 `IN_PROGRESS`로
  유지한다.
- connect·response timeout은 `503 Service Unavailable`과
  `DEPENDENCY_TIMEOUT`으로 반환한다.
- 실패 후 재분석 정책과 운영 복구 실행 경로는 후속 구현으로 분리한다. 불확실한
  상태에서 자동 재실행하지 않는 복구 원칙은 ADR-006을 따른다.

connect·response timeout의 상세 분류와 설정은
[Rule v1 내부 분석 API](./rule-v1-analysis-api.md#13-spring-boot-client-연동-계약)를
따른다. Client 내부 오류 category를 외부 거래 API code로 직접 노출하지 않으며
다음 현재 매핑을 적용한다.

| Client 내부 category | 외부 HTTP·공통 code |
| --- | --- |
| `AI_SERVICE_CONNECT_TIMEOUT`, `AI_SERVICE_RESPONSE_TIMEOUT` | `503 DEPENDENCY_TIMEOUT` |
| `AI_SERVICE_UNAVAILABLE` | `503 DEPENDENCY_UNAVAILABLE` |
| 요청·payload·Rule 계약, capability, FastAPI 내부 오류, invalid response category | `500 INTERNAL_ERROR` |

FastAPI 원문 오류와 Client 내부 category는 외부 응답에 포함하지 않는다.
DetectionResult에는 Client category 이름과 승인된 오케스트레이션 로컬
`failureCode`를 원자적으로 기록한다. public 거래 접수는 안전 상태가 확인된 Rule
실패를 code-only `DEPENDENCY_UNAVAILABLE`로 축약한다.

실패 Transaction 저장과 오류 응답은 일부만 성공한 것처럼 보이지 않도록 정합성 경계를 가져야 한다. 현재 오류 응답은 다음 공통 envelope를 사용한다.

```http
HTTP/1.1 503 Service Unavailable
Content-Type: application/json
```

```json
{
  "code": "DEPENDENCY_TIMEOUT",
  "message": "탐지 서비스를 사용할 수 없습니다.",
  "traceId": "trace_demo_timeout_01",
  "fieldErrors": []
}
```

`resource`는 오류와 함께 영속 리소스 문맥을 반환하기 위한 후보일 뿐 현재 계약에
포함하지 않는다. 별도 승인 전 오류 응답에 추가하지 않는다.

### 5.9 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `201 Created` | 신규 최종 v2 최초 성공과 저장 status가 `201`인 v1·v2 완료 재요청 |
| `200 OK` | 무버전 strict legacy Snapshot의 완료된 동일 멱등 요청에 기존 결과 반환 |
| `400 Bad Request` | 잘못된 JSON, 필수 헤더 누락 또는 필드 형식 오류 |
| `409 Conflict` | 멱등성 키 지문 충돌, 동일 멱등 요청 처리 중, `transactionId` 중복 또는 동시성 충돌 |
| `422 Unprocessable Entity` | 형식은 맞지만 거래 유형별 업무 규칙을 만족하지 못함 |
| `503 Service Unavailable` | External Risk Timeout·Unavailable, FastAPI connect·response timeout 또는 가용성 장애 |
| `500 Internal Server Error` | Spring이 만든 AI 요청·Rule·배포 capability 문제, FastAPI 내부 오류, 신뢰할 수 없는 응답 또는 그 밖의 서버 오류 |

## 6. 거래 목록 조회

### 6.1 요청

```http
GET /api/v1/transactions
```

### 6.2 필터와 페이지네이션

| 쿼리 파라미터 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `occurredAtFrom` | 선택 | 없음 | 발생 시각 시작, UTC ISO-8601 `Z`, 포함 경계 |
| `occurredAtTo` | 선택 | 없음 | 발생 시각 종료, UTC ISO-8601 `Z`, 미포함 경계 |
| `transactionType` | 선택 | 없음 | 정확한 대문자 거래 유형 단일 값 |
| `processingStatus` | 선택 | 없음 | 정확한 대문자 거래 처리 상태 단일 값 |
| `externalCustomerRef` | 선택 | 없음 | 외부 고객 참조값 exact, case-sensitive 검색 |
| `accountRef` | 선택 | 없음 | 발신 또는 수신 계좌 외부 참조값 exact, case-sensitive 검색 |
| `page` | 선택 | `0` | 0부터 시작하는 페이지 번호 |
| `size` | 선택 | `20` | 1~100의 페이지 크기 |
| `sort` | 선택 | `occurredAt,desc` | 단일 발생 시각 정렬 |

요청 예:

```http
GET /api/v1/transactions?occurredAtFrom=2026-07-23T00:00:00Z&occurredAtTo=2026-07-24T00:00:00Z&transactionType=ACCOUNT_TRANSFER&page=0&size=20&sort=occurredAt,desc
```

- 시각 범위는 `[occurredAtFrom, occurredAtTo)`이다. 시작값은 포함하고 종료값은 포함하지 않는다.
- 시작값이나 종료값 중 하나만 전달할 수 있다.
- 시작값이 종료값보다 늦으면 `422 Unprocessable Entity`를 반환한다. 두 값이 같으면 정상적인 빈 범위이다.
- 시각 값은 `Z` 접미사가 있는 UTC ISO-8601이어야 하며 서버가 로컬 시간대나 UTC로 보정하지 않는다.
- Enum 필터는 단일 값만 허용한다. 쉼표 구분 값이나 반복된 값은 허용하지 않는다.
- `externalCustomerRef`와 `accountRef`는 값을 trim하거나 대소문자를 변경하지 않는다. 빈 문자열이나 공백만 있는 값은 허용하지 않는다.
- `accountRef`는 `senderAccountRef = accountRef OR recipientAccountRef = accountRef` 조건이다.
- 부분 일치, 대소문자 무시와 wildcard 검색은 지원하지 않는다.
- `sort`는 `occurredAt,asc` 또는 `occurredAt,desc`만 허용한다. 반복된 `sort`, 다중 정렬, 다른 필드와 다른 방향 표기는 허용하지 않는다.
- 같은 `occurredAt`을 가진 거래는 내부 `id`를 같은 방향의 보조 정렬키로 사용한다. 내부 `id`는 요청 정렬 필드나 응답 필드로 노출하지 않는다.

현재 `financial_transaction` 저장 구조에 조회 원천이 없는 `riskLevel`, `activeCaseLinked`, `hasCaseHistory`는 요청 파라미터가 아니다. 관련 저장 구조와 후속 API 계약이 승인되기 전까지 지원하지 않는다.

### 6.3 성공 응답 예시

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "content": [
    {
      "transactionId": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
      "transactionType": "ACCOUNT_TRANSFER",
      "amount": "1250000",
      "currencyCode": "KRW",
      "occurredAt": "2026-07-23T01:15:30Z",
      "externalCustomerRef": "cust_ref_demo_a7f2",
      "senderAccountRef": "acct_ref_demo_s91c",
      "recipientAccountRef": "acct_ref_demo_r44d",
      "processingStatus": "ADDITIONAL_AUTH_REQUIRED",
      "createdAt": "2026-07-23T01:15:31Z"
    }
  ],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  },
  "traceId": "trace_demo_tx_list_01"
}
```

최상위 응답은 `content`, `page`, `traceId`만 포함한다. 각 `content` 항목은 예시에 표시된 거래 기본 정보 10개 필드만 포함한다. `recipientAccountRef`가 없으면 필드를 생략하지 않고 명시적인 null을 반환한다. 금액은 후행 0을 제거하되 실제 소수 자릿수는 보존한 10진 문자열로 반환하고 지수 표기를 사용하지 않는다.

위험 등급, 위험 대응, 채택 탐지 결과, 사건 연결·이력과 행동 이벤트 정보는 null, false 또는 0 placeholder로 반환하지 않고 필드 자체를 제외한다. `TransactionIntakeSnapshot`은 거래 접수 멱등 재전송용이며 조회 원천으로 사용하지 않는다.

조회 결과가 없으면 다음처럼 정상 응답한다.

```json
{
  "content": [],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 0,
    "totalPages": 0,
    "first": true,
    "last": true
  },
  "traceId": "trace_demo_tx_empty_01"
}
```

### 6.4 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 조회 성공. 결과가 없으면 빈 `content` 반환 |
| `400 Bad Request` | 시각·Enum·페이지·크기·정렬 형식 또는 참조값 오류 |
| `422 Unprocessable Entity` | 시작 시각이 종료 시각보다 늦거나 페이지·크기가 허용 범위를 벗어남 |
| `503 Service Unavailable` | 조회 Timeout은 `DEPENDENCY_TIMEOUT`, 명확한 저장소 가용성 장애는 `DEPENDENCY_UNAVAILABLE` |
| `500 Internal Server Error` | 그 밖의 DataAccess 오류 또는 예상하지 못한 서버 오류 |

`400`과 `422`는 모두 `VALIDATION_ERROR`를 사용한다. 공개 오류 메시지에는 쿼리 파라미터 원문이나 거래 참조값을 포함하지 않는다.

## 7. 거래 상세 조회

### 7.1 요청

```http
GET /api/v1/transactions/{transactionId}
```

요청 예:

```http
GET /api/v1/transactions/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001
```

`transactionId`는 거래 접수와 같은 canonical UUID v4 및 RFC 4122 variant 규칙을 사용한다.

### 7.2 응답 범위

최상위 응답은 `transaction`, `traceId`만 포함한다. `transaction`은 거래 기본 정보, 채널, 기기 참조, 처리 상태와 생성·변경 시각만 포함한다. 내부 DB `id`, 낙관적 잠금 `version`과 멱등 처리 정보는 포함하지 않는다.

### 7.3 성공 응답 예시

```json
{
  "transaction": {
    "transactionId": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
    "transactionType": "ACCOUNT_TRANSFER",
    "amount": "1250000",
    "currencyCode": "KRW",
    "occurredAt": "2026-07-23T01:15:30Z",
    "externalCustomerRef": "cust_ref_demo_a7f2",
    "senderAccountRef": "acct_ref_demo_s91c",
    "recipientAccountRef": "acct_ref_demo_r44d",
    "channel": "MOBILE_BANKING",
    "deviceRef": "device_ref_demo_18b3",
    "processingStatus": "ADDITIONAL_AUTH_REQUIRED",
    "createdAt": "2026-07-23T01:15:31Z",
    "updatedAt": "2026-07-23T01:15:32Z"
  },
  "traceId": "trace_demo_tx_detail_01"
}
```

`recipientAccountRef`와 `deviceRef`가 없으면 필드를 생략하지 않고 명시적인 null을 반환한다. 금액 문자열 규칙은 목록 응답과 같다. 탐지·위험 대응·사건·행동 이벤트 정보는 placeholder 없이 필드 자체를 제외한다.

### 7.4 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 거래 상세 조회 성공 |
| `400 Bad Request` | `transactionId` 형식 오류 |
| `404 Not Found` | 해당 `transactionId`의 거래가 없음 |
| `503 Service Unavailable` | 조회 Timeout은 `DEPENDENCY_TIMEOUT`, 명확한 저장소 가용성 장애는 `DEPENDENCY_UNAVAILABLE` |
| `500 Internal Server Error` | 그 밖의 DataAccess 오류 또는 예상하지 못한 서버 오류 |

`400`은 `VALIDATION_ERROR`, `404`는 `RESOURCE_NOT_FOUND`를 사용한다. DB 예외 메시지, SQL, 테이블명, 컬럼명, 거래 식별자와 참조값은 공개 오류 메시지에 포함하지 않는다.

## 8. 행동 이벤트 생성

### 8.1 요청

```http
POST /api/v1/behavior-events
Content-Type: application/json
```

별도 `Idempotency-Key`는 사용하지 않는다. 호출자가 생성한 canonical UUID v4 `eventId`와 정규화 요청 fingerprint를 자연 멱등 기준으로 사용한다.

#### 8.1.1 호출 주체와 신뢰 경계

- 이 엔드포인트는 일반 사용자가 임의의 위험 판단을 제출하는 API가 아니다.
- 신뢰된 Mock 금융·인증 시스템 또는 승인된 수집 어댑터가 관측 이벤트를 전달하는 수집 API이다.
- 이번 접수 범위는 행동 이벤트 영속화와 선택적 거래 연결까지이다.
- `locationRiskSummary`, `observedSignals`, 자유 형식 `eventDetails`, Rule·ML·위험·탐지·사건 필드는 받거나 반환하지 않는다.

### 8.2 요청 필드

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `eventId` | string | 공통 필수 | 호출자 생성 canonical UUID v4 행동 이벤트 업무 식별자 |
| `eventType` | string | 공통 필수 | 지원하는 9개 행동 이벤트 유형 |
| `occurredAt` | string | 공통 필수 | `Z` 접미사의 UTC ISO-8601 발생 시각 |
| `externalCustomerRef` | string | 공통 필수 | 외부 고객 참조값 |
| `accountRef` | string 또는 null | 조건부 | 행동의 기준이 되는 고객 측 계좌 참조값 |
| `deviceRef` | string 또는 null | 조건부 | 기기 외부 참조값 |
| `transactionId` | string 또는 null | 조건부 | 관련 거래의 UUID v4 업무 식별자 |
| `beneficiaryRef` | string 또는 null | 조건부 | 새로 등록된 수취인 참조값 |

요청 JSON은 위 8개 필드만 허용한다. 알 수 없는 필드, 중복 JSON 키, string 또는 null 외 타입과 스칼라 강제 변환은 `400 Bad Request`와 `VALIDATION_ERROR`로 거부한다.

`externalCustomerRef`, `accountRef`, `deviceRef`, `beneficiaryRef`는 제공될 때 1~128자이며 빈 값, 공백만 있는 값과 앞뒤 공백을 허용하지 않는다. 서버는 trim하거나 대소문자를 바꾸지 않고 exact·case-sensitive 값으로 취급한다. 선택 필드의 누락과 명시적 null은 동일하게 정규화한다.

`accountRef`는 행동의 기준이 되는 고객 측 계좌이다. `BENEFICIARY_REGISTERED`에서는 수취인을 등록한 고객 측 출금 계좌이고, `beneficiaryRef`는 새로 등록된 수취인이다. 두 필드의 의미를 혼합하지 않는다.

### 8.3 이벤트별 필드 조건

| `eventType` | `accountRef` | `deviceRef` | `transactionId` | `beneficiaryRef` |
| --- | --- | --- | --- | --- |
| `LOGIN` | 선택 | 필수 | 선택 | 금지 |
| `LOGIN_FAILED` | 선택 | 선택 | 선택 | 금지 |
| `DEVICE_REGISTERED` | 선택 | 필수 | 선택 | 금지 |
| `PASSWORD_CHANGED` | 선택 | 선택 | 선택 | 금지 |
| `OTP_REISSUED` | 선택 | 선택 | 선택 | 금지 |
| `BENEFICIARY_REGISTERED` | 필수 | 선택 | 선택 | 필수 |
| `TRANSFER_LIMIT_CHANGED` | 필수 | 선택 | 선택 | 금지 |
| `TRANSFER_REQUESTED` | 필수 | 선택 | 필수 | 금지 |
| `ATM_WITHDRAWAL_REQUESTED` | 필수 | 선택 | 필수 | 금지 |

조건부 필수값 누락과 금지된 `beneficiaryRef`는 형식상 파싱 가능한 요청의 업무 규칙 위반이므로 `422 Unprocessable Entity`와 `VALIDATION_ERROR`로 처리한다.

### 8.4 UUID와 발생 시각

- `eventId`와 제공된 `transactionId`는 canonical UUID 문자열, version 4와 RFC 4122 variant를 검증한다.
- `eventId`는 REST BehaviorEvent Aggregate 식별자이며 내부 DB Identity PK를 받거나 반환하지 않는다.
- 향후 도메인 이벤트 Envelope의 `eventId`는 논리 이벤트 전달 식별자이고 REST `BehaviorEvent.eventId`와 서로 다른 경계이다.
- `occurredAt`은 `Z` 접미사를 가진 UTC ISO-8601만 허용한다.
- 같은 순간을 표현하는 offset 시각도 거부한다.
- 정확히 서버 시각보다 5분 미래는 허용하고 이를 초과하면 `422 Unprocessable Entity`와 `VALIDATION_ERROR`를 반환한다.
- 서버 시각 검증은 테스트 가능한 주입식 `Clock`을 사용한다.

### 8.5 관련 거래 검증

`transactionId`가 제공되면 Spring Boot가 `financial_transaction`을 조회한다.

- 거래가 없으면 `404 Not Found`와 `RESOURCE_NOT_FOUND`를 반환한다.
- `externalCustomerRef`는 거래의 고객 참조와 정확히 일치해야 한다.
- `accountRef`가 제공된 일반 이벤트는 거래의 `senderAccountRef` 또는 nullable `recipientAccountRef` 중 하나와 일치해야 한다.
- `TRANSFER_REQUESTED`는 `ACCOUNT_TRANSFER` 또는 `OPEN_BANKING_TRANSFER` 거래만 참조하고 `accountRef`가 `senderAccountRef`와 일치해야 한다.
- `ATM_WITHDRAWAL_REQUESTED`는 `ATM_WITHDRAWAL` 거래만 참조하고 `accountRef`가 출금 계좌인 `senderAccountRef`와 일치해야 한다.
- 거래가 존재하지만 고객·계좌·유형 정합성이 맞지 않으면 `422 Unprocessable Entity`와 `VALIDATION_ERROR`를 반환한다.

오류 응답에는 실제 참조값, 내부 PK, SQL과 DB 상세를 포함하지 않는다.

### 8.6 요청 예시

```json
{
  "eventId": "e54cbf7e-d857-4ca0-bff3-8d4321b7722a",
  "eventType": "BENEFICIARY_REGISTERED",
  "occurredAt": "2026-07-29T04:10:00Z",
  "externalCustomerRef": "cust_ref_demo_a7f2",
  "accountRef": "acct_ref_demo_s91c",
  "deviceRef": "device_ref_demo_18b3",
  "transactionId": null,
  "beneficiaryRef": "acct_ref_demo_r82a"
}
```

### 8.7 성공 응답

최초 저장:

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "eventId": "e54cbf7e-d857-4ca0-bff3-8d4321b7722a",
  "eventType": "BENEFICIARY_REGISTERED",
  "transactionId": null,
  "occurredAt": "2026-07-29T04:10:00Z",
  "createdAt": "2026-07-29T04:10:01Z",
  "traceId": "6cb3a9e2-7f91-4b99-98df-bcad0f8bf21b"
}
```

응답은 위 6개 필드를 정확히 반환하고 nullable `transactionId`도 생략하지 않는다. 내부 PK·FK, fingerprint, 고객·계좌·기기·수취인 참조, Entity 상태와 Rule·ML·위험·탐지·사건 placeholder는 반환하지 않는다.

### 8.8 `eventId` 자연 멱등성과 fingerprint

정규화 fingerprint는 다음 필드를 고정 순서로 포함한다.

```text
eventId
eventType
occurredAt
externalCustomerRef
accountRef
deviceRef
transactionId
beneficiaryRef
```

검증된 UUID와 시각은 canonical 문자열로 직렬화하고 nullable 필드는 명시적 JSON null로 표현한다. 고정 순서·고정 필드의 공백 없는 UTF-8 JSON object를 SHA-256으로 계산해 소문자 16진수 64자로 저장한다. 이 표현은 필드 경계와 null을 모호하지 않게 구분한다. `traceId`, 내부 PK·FK와 `createdAt`은 제외한다.

| 상황 | 처리 규칙 |
| --- | --- |
| 같은 `eventId` + 같은 정규화 요청 | 새 이벤트를 저장하지 않고 기존 결과를 `200 OK`로 반환한다. |
| 같은 `eventId` + 다른 요청 내용 | 기존 이벤트를 덮어쓰지 않고 `409 Conflict`와 `DUPLICATE_EVENT`를 반환한다. |
| 같은 유형·비슷한 시각 + 다른 `eventId` | 식별자가 다르다는 이유만으로 자동 중복 처리하지 않는다. 별도 이상 패턴으로 분석할 수 있다. |
| 같은 `eventId`의 동시 도착 | 하나만 최초 저장하고 나머지는 기존 결과를 참조한다. |

저장 시도에서 Unique 위반이 발생한 트랜잭션은 rollback한 뒤 분리된 트랜잭션에서 기존 행을 재조회한다. 패자는 fingerprint가 같으면 `200`, 다르면 `409`를 반환한다. 별도 response snapshot은 만들지 않으며 기존 불변 행에서 결과를 재구성하고 현재 HTTP 요청의 `traceId`를 결합한다.

### 8.9 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `201 Created` | 행동 이벤트가 처음 저장됨 |
| `200 OK` | 같은 `eventId`와 같은 요청의 기존 결과 반환 |
| `400 Bad Request` | malformed JSON, 공통 필수 필드·UUID·Enum·시각·참조 형식, 알 수 없는 필드, 중복 키와 타입 오류 |
| `404 Not Found` | 제공되었거나 필수인 `transactionId`의 거래가 없음 |
| `409 Conflict` | 같은 `eventId`에 다른 요청 내용이 도착함 |
| `422 Unprocessable Entity` | 조건부 필드, 미래 5분 초과 또는 거래 고객·계좌·유형 정합성 위반 |
| `503 Service Unavailable` | 명확한 PostgreSQL timeout 또는 일시적 저장소 가용성 장애 |
| `500 Internal Server Error` | 그 밖의 DataAccess 오류, 모순된 내부 데이터 또는 예상하지 못한 오류 |

`DEPENDENCY_TIMEOUT`은 명확한 statement/query timeout에만, `DEPENDENCY_UNAVAILABLE`은 연결 실패 또는 명확한 일시적 가용성 장애에만 사용한다. 그 밖의 `DataAccessException`을 일괄적으로 `503`으로 변환하지 않고 `500 INTERNAL_ERROR`로 축약한다. 모든 오류는 공통 오류 구조와 현재 요청의 `traceId`를 사용하고 SQL·DB·드라이버·제약 이름·스택 트레이스·참조값·내부 PK·fingerprint를 노출하지 않는다.

## 9. 행동 이벤트 목록 조회

이 절은 후속 조회 API 후보이며 Issue #68 구현 범위에 포함하지 않는다. 현재 구현되는 행동 이벤트 API는 8장의 생성 API뿐이다.

### 9.1 요청

```http
GET /api/v1/behavior-events
```

### 9.2 필터 후보

| 쿼리 파라미터 | 설명 |
| --- | --- |
| `externalCustomerRef` | 외부 고객 참조값 |
| `transactionId` | 관련 거래 식별자 |
| `eventType` | 행동 이벤트 유형 |
| `occurredAtFrom` | 발생 시각 시작 |
| `occurredAtTo` | 발생 시각 종료 |
| `deviceRef` | 기기 외부 참조값 |
| `page` | 페이지 번호 |
| `size` | 페이지 크기 |
| `sort` | 정렬 조건 |

요청 예:

```http
GET /api/v1/behavior-events?externalCustomerRef=cust_ref_demo_a7f2&occurredAtFrom=2026-07-23T00:00:00Z&occurredAtTo=2026-07-24T00:00:00Z&page=0&size=20&sort=occurredAt,asc
```

### 9.3 성공 응답 예시

```json
{
  "content": [
    {
      "eventId": "e54cbf7e-d857-4ca0-bff3-8d4321b7722a",
      "externalCustomerRef": "cust_ref_demo_a7f2",
      "transactionId": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
      "eventType": "DEVICE_REGISTERED",
      "occurredAt": "2026-07-23T01:10:00Z",
      "deviceRef": "device_ref_demo_18b3",
      "accountRef": null,
      "beneficiaryRef": null
    }
  ],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  },
  "traceId": "trace_demo_event_list_01"
}
```

목록 응답은 허용된 요약만 반환하며 이벤트 상세 원문을 무조건 포함하지 않는다.

### 9.4 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 조회 성공. 결과가 없으면 빈 `content` 반환 |
| `400 Bad Request` | 시각, Enum, 페이지 또는 정렬 형식 오류 |
| `422 Unprocessable Entity` | 의미상 처리할 수 없는 시각 범위나 필터 조합 |
| `503 Service Unavailable` | 필수 저장소 등 조회 의존성이 일시적으로 사용 불가 |

## 10. 거래별 탐지 결과 조회

이 절은 후속 API 후보이다. DetectionResult·Evidence 물리 영속 모델은
구현되었지만 탐지 실행에 의한 결과 생성과 조회 API는 아직 구현되지
않았다.

### 10.1 요청

```http
GET /api/v1/transactions/{transactionId}/detection-results
```

요청 예:

```http
GET /api/v1/transactions/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001/detection-results?page=0&size=20&sort=detectionResultVersion,desc
```

한 거래에 여러 재분석 버전이 존재할 수 있으므로 페이지네이션을 적용하는 방향을 권장한다.

### 10.2 응답 필드 후보

| 필드 | 설명 |
| --- | --- |
| `detectionResultId` | 탐지 결과 업무 식별자 |
| `detectionResultVersion` | 같은 거래의 분석 버전 |
| `riskScore` | 승인된 점수 통합 정책의 결과 |
| `riskLevel` | 해당 분석 버전의 위험 등급 |
| `analysisStatus` | 분석 상태 |
| `adopted` | Spring Boot가 Transaction 현재값의 기준으로 채택했는지 여부 |
| `modelVersion` | 사용 모델 버전 |
| `featureVersion` | Feature 정의·계산 버전 |
| `ruleSetVersion` | 평가에 사용한 전체 Rule 집합 버전 |
| `scoringPolicyVersion` | 점수 합산·등급 정책 버전 |
| `analysisStartedAt` | 분석 시작 시각 |
| `analysisCompletedAt` | 분석 완료 시각. 미완료 시 null 가능 |

### 10.3 성공 응답 예시

```json
{
  "transactionId": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
  "content": [
    {
      "detectionResultId": "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101",
      "detectionResultVersion": 1,
      "riskScore": 55,
      "riskLevel": "HIGH",
      "analysisStatus": "COMPLETED",
      "adopted": true,
      "modelVersion": null,
      "featureVersion": "rule-v1",
      "ruleSetVersion": "085edb92debd4e80d8472f77fab507d846810c668268ee34d8ee97ec2c917b26",
      "scoringPolicyVersion": "scoring-policy-v1",
      "analysisStartedAt": "2026-07-23T01:15:30Z",
      "analysisCompletedAt": "2026-07-23T01:15:32Z"
    }
  ],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  },
  "traceId": "trace_demo_detection_list_01"
}
```

동일 `transactionId + detectionResultVersion`은 하나의 결과만 유지한다. 거래
잠금 아래 다음 버전을 할당하고 DB unique 제약을 최종 방어선으로 사용한다.
Timeout 실패와 늦은 성공 응답은 같은 거래·DetectionResult 잠금 및 terminal
상태 검증으로 하나만 확정한다. Client 자동 retry는 없으며 실제 새 분석은
후속 재분석 계약에 따라 새 버전을 사용해야 한다.

### 10.4 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 탐지 결과 조회 성공. 결과가 없으면 빈 `content` 반환 |
| `400 Bad Request` | 식별자, 페이지 또는 정렬 형식 오류 |
| `404 Not Found` | 해당 `transactionId`의 거래가 없음 |
| `422 Unprocessable Entity` | 의미상 처리할 수 없는 페이지·정렬 조건 |
| `503 Service Unavailable` | 필수 저장소 등 조회 의존성이 일시적으로 사용 불가 |

## 11. 탐지 결과 상세 조회

이 절은 후속 API 후보이다. DetectionResult·DetectionEvidence 물리
영속 모델은 구현되었지만 상세 조회 API는 아직 구현되지 않았다.

### 11.1 요청

```http
GET /api/v1/detection-results/{detectionResultId}
```

요청 예:

```http
GET /api/v1/detection-results/7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101
```

### 11.2 응답 범위

응답 후보는 다음을 포함한다.

- 탐지 결과 기본 정보
- DetectionEvidence
- 사용한 Rule 버전
- Reason Code
- 점수 기여도
- ExternalRiskSnapshot 최소 요약
- 행동 패턴 요약
- `traceId`

다음 정보는 반환하지 않는다.

- Feature 전체 벡터
- 실제 고객번호와 실제 계좌번호
- 원문 IP
- 원문 기기 식별정보
- External Risk Provider 원문 응답
- 원문 행동 로그 전체
- LLM 입력과 AI 리포트

### 11.3 성공 응답 예시

```json
{
  "detectionResult": {
    "detectionResultId": "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101",
    "transactionId": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
    "detectionResultVersion": 1,
    "riskScore": 55,
    "riskLevel": "HIGH",
    "analysisStatus": "COMPLETED",
    "adopted": true,
    "modelVersion": null,
    "featureVersion": "rule-v1",
    "ruleSetVersion": "085edb92debd4e80d8472f77fab507d846810c668268ee34d8ee97ec2c917b26",
    "scoringPolicyVersion": "scoring-policy-v1",
    "analysisStartedAt": "2026-07-23T01:15:30Z",
    "analysisCompletedAt": "2026-07-23T01:15:32Z"
  },
  "evidence": [
    {
      "evidenceId": "6a4c0a4e-8a9d-4c2f-9a1b-7d6e5f430201",
      "evidenceType": "RULE",
      "reasonCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
      "displayDescription": "절대 고액 이체",
      "scoreContribution": 15,
      "rule": {
        "ruleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
        "ruleVersion": "1"
      },
      "observationSummary": {
        "observedAmount": "10000000",
        "amountThreshold": "10000000"
      },
      "evidenceOccurredAt": "2026-07-23T01:15:30Z"
    },
    {
      "evidenceId": "6a4c0a4e-8a9d-4c2f-9a1b-7d6e5f430202",
      "evidenceType": "RULE",
      "reasonCode": "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
      "displayDescription": "최근 보안정보 변경 시퀀스가 있는 고액 이체",
      "scoreContribution": 40,
      "rule": {
        "ruleCode": "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
        "ruleVersion": "1"
      },
      "observationSummary": {
        "observedAmount": "10000000",
        "amountThreshold": "10000000",
        "passwordChangedEventId": "8be68132-507c-42c9-926e-aeb63c471d23",
        "passwordChangedAt": "2026-07-23T00:15:00Z",
        "transferLimitChangedEventId": "cc7ec17c-064f-4c90-841d-b7e75c6e82f8",
        "transferLimitChangedAt": "2026-07-23T00:45:00Z",
        "elapsedSeconds": 1830,
        "windowSeconds": 86400
      },
      "evidenceOccurredAt": "2026-07-23T00:45:00Z"
    }
  ],
  "traceId": "trace_demo_detection_detail_01"
}
```

위 예시는 미구현 Rule v1 조회 응답의 후보이며 조회 API 구현 완료를
의미하지 않는다. Rule 조건과 Evidence 의미는
[Rule v1 탐지 계약](../01-requirements/rule-v1-detection-contract.md)을
따른다. `observationSummary`의 Reason Code별 정확한 allowlist와 물리
제약은
[`../04-database/detection-result-schema.md`](../04-database/detection-result-schema.md)를
따르며 자유 형식 원문 저장·반환은 허용하지 않는다.

### 11.4 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 탐지 결과 상세 조회 성공 |
| `400 Bad Request` | `detectionResultId` 형식 오류 |
| `404 Not Found` | 해당 `detectionResultId`의 탐지 결과가 없음 |
| `503 Service Unavailable` | 필수 저장소 등 조회 의존성이 일시적으로 사용 불가 |

## 12. HTTP 상태 코드 요약

| API | `200` | `201` | `400` | `404` | `409` | `422` | `503` | `500` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `POST /transactions` | strict legacy 완료 재요청의 기존 결과 | 신규 최종 v2 최초 성공과 저장 status가 `201`인 v1·v2 완료 재요청 | JSON·필수 헤더·필드 형식 오류 | 사용하지 않음 | 멱등 키 지문 충돌·처리 중 동일 요청·거래·상태·동시성 충돌 | 거래 유형별 도메인 규칙 위반 | External Risk Timeout·Unavailable, FastAPI Timeout 또는 가용성 장애 | 계약·응답·내부 오류와 Snapshot 완료 실패 |
| `GET /transactions` | 조회 성공 | 사용하지 않음 | 필터·페이지 형식 오류 | 사용하지 않음 | 사용하지 않음 | 의미상 잘못된 필터·페이지 범위 | 조회 Timeout 또는 명확한 저장소 가용성 장애 | 그 밖의 DataAccess 오류·예기치 않은 서버 오류 |
| `GET /transactions/{transactionId}` | 조회 성공 | 사용하지 않음 | 식별자 형식 오류 | 거래 없음 | 사용하지 않음 | 사용하지 않음 | 조회 Timeout 또는 명확한 저장소 가용성 장애 | 그 밖의 DataAccess 오류·예기치 않은 서버 오류 |
| `POST /behavior-events` | 동일 이벤트 기존 결과 | 최초 생성 | JSON·알 수 없는 필드·필드 형식 오류 | 관련 거래 없음 | 다른 내용의 `eventId` 중복 | 이벤트 유형별 도메인 규칙·거래 정합성 위반 | 명확한 DB Timeout 또는 저장소 가용성 장애 | 그 밖의 DataAccess 오류·예기치 않은 서버 오류 |
| `GET /behavior-events` | 조회 성공 | 사용하지 않음 | 필터·페이지 형식 오류 | 사용하지 않음 | 사용하지 않음 | 의미상 잘못된 필터 | 조회 의존성 장애 | 예기치 않은 서버 오류 |
| `GET /transactions/{transactionId}/detection-results` | 조회 성공 | 사용하지 않음 | 식별자·페이지 형식 오류 | 거래 없음 | 사용하지 않음 | 의미상 잘못된 조건 | 조회 의존성 장애 | 예기치 않은 서버 오류 |
| `GET /detection-results/{detectionResultId}` | 조회 성공 | 사용하지 않음 | 식별자 형식 오류 | 탐지 결과 없음 | 사용하지 않음 | 사용하지 않음 | 조회 의존성 장애 | 예기치 않은 서버 오류 |

## 13. 공통 오류 예시

### 13.1 멱등성 키 충돌

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "code": "IDEMPOTENCY_KEY_CONFLICT",
  "message": "같은 멱등성 키가 다른 거래 요청에 사용되었습니다.",
  "traceId": "trace_demo_idempotency_conflict_01",
  "fieldErrors": []
}
```

### 13.2 동일 멱등 요청 처리 중

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "code": "IDEMPOTENCY_REQUEST_IN_PROGRESS",
  "message": "같은 멱등 요청이 처리 중입니다.",
  "traceId": "trace_demo_idempotency_in_progress_01",
  "fieldErrors": []
}
```

### 13.3 행동 이벤트 중복 충돌

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "code": "DUPLICATE_EVENT",
  "message": "같은 eventId에 다른 이벤트 내용이 요청되었습니다.",
  "traceId": "trace_demo_event_conflict_01",
  "fieldErrors": [
    {
      "field": "eventId",
      "code": "EVENT_ID_ALREADY_USED",
      "reason": "이미 다른 내용으로 저장된 이벤트 식별자입니다."
    }
  ]
}
```

### 13.4 리소스 없음

```http
HTTP/1.1 404 Not Found
Content-Type: application/json
```

```json
{
  "code": "RESOURCE_NOT_FOUND",
  "message": "요청한 탐지 결과를 찾을 수 없습니다.",
  "traceId": "trace_demo_not_found_01",
  "fieldErrors": []
}
```

## 14. `traceId` 전파

- Spring Boot는 거래·행동·탐지 API의 성공과 오류 응답에 `traceId`를 반환한다.
- 최초 거래 생성 HTTP 요청의 `traceId`를 별도 생성 없이 분석
  `analysisTraceId`로 그대로 전달해 Spring Boot, External Risk Mock과 FastAPI
  호출을 연결한다.
- 성공 Snapshot과 External Risk Failure Snapshot에는 `traceId`를 저장하지 않는다.
  완료·실패 재생 응답은 현재 재요청의 `traceId`를 결합하며 최초 분석 trace를
  재요청 trace로 재사용하지 않는다. External Risk 실패 exact replay는 HTTP 상태,
  공개 code, 안전 message와 `fieldErrors`의 의미적 동일성만 보장한다.
- 조회 API의 `traceId`는 해당 조회 요청을 추적하며 저장된 과거 분석의 `traceId`와 같을 필요가 없다.
- 탐지 결과에 저장된 분석 당시 추적값과 현재 조회 요청의 `traceId`를 함께 제공할 필요가 있으면 서로 다른 필드명과 의미로 구분한다.
- OpenTelemetry 전파 헤더의 구체적인 이름과 구현은 이 문서에서 확정하지 않는다.

## 15. 민감정보 처리

- 예시와 계약에는 실제 고객번호·계좌번호·IP 원문을 사용하지 않는다.
- `externalCustomerRef`, 계좌 참조값과 `deviceRef`는 실제 원문이 아닌 제한된 참조값이다.
- 행동 이벤트는 국가·지역·해외 여부와 위험 신호 같은 최소 요약을 사용한다.
- 거래 목록·상세에서 고객·계좌 원문을 반환하지 않는다.
- 탐지 근거에는 Feature 전체 벡터와 원문 행동 로그를 반환하지 않는다.
- 현재 `ExternalRiskSnapshot`은 성공 조회 전용 immutable 인메모리 값으로
  `transactionId`, `evaluationCutoffAt`, `lookedUpAt`, `providerCode`,
  `providerAsOf`, `SUCCEEDED` 조회 상태, `MATCHED` 또는 `UNMATCHED` 정책 결과와
  최대 3개의 제한된 match만 제공한다.
- 성공 업무용 `ExternalRiskSnapshot`에는 cache·fallback·stale data·retry·실패
  상태를 포함하지 않는다. 별도 External Risk Failure Snapshot은 Provider 업무 응답
  원문이 아니라 안전한 공개 실패를 재생하기 위한 envelope이다.
- Failure Snapshot에는 `traceId`, `transactionId`, `evaluationCutoffAt`, 원본
  `Idempotency-Key`, fingerprint, Provider request·response body·원문 code·URL,
  고객·계좌·기기 reference, IP·행동 원문, low-level exception message, stack trace,
  인증정보, Provider 구현 클래스와 내부 설정을 저장하지 않는다. `transactionId`는
  idempotency record의 거래 FK로 확인한다.
- v2 `externalRisk`에는 Provider·정책·시각과 제한된 match만 포함하며 기존
  거래·행동의 비식별 reference를 중복하지 않는다. 성공 `ExternalRiskSnapshot`과
  v2 요청은 DB, DetectionEvidence 또는 AuditLog에 저장하지 않는다.
- Provider 응답 원문, 인증정보와 내부 예외 원문을 응답에 포함하지 않는다.

## 16. 사용자 결정 필요 항목

### 16.1 공통 계약

- `fieldErrors.code`의 코드 목록과 버전 관리 방식

### 16.2 거래

확정되어 구현된 계약은 다음과 같다.

현재 거래 오케스트레이션은 여섯 External Risk typed failure 발생 시 분석을
시작하지 않고 거래 `RECEIVED`를 유지하며 DetectionResult를 생성하지 않는다.
FastAPI·위험 대응 최종화·성공 Snapshot v2를 호출하거나 만들지 않는다. 정상 확정된
Failure Snapshot의 내부 저장·조회와 strict decode 경계는 구현되었고 같은
key·fingerprint에서 terminal `FAILED`로 판별한다. 공개 mapper와 public intake
end-to-end 연결도 구현되었다.

다음은 아직 사용자 결정이 필요하다.

- 별도 operation scope의 External Risk 복구·재분석 명령, 운영자 권한과 감사 방식
- External Risk 불확실 `IN_PROGRESS`와 완료 간극의 실제 수동·운영 복구 절차
- Rule v1 분석 실패 후 재분석·수동 복구 정책. 최초 시도는 DetectionResult와
  거래를 `FAILED`로 기록하고 Client 자동 retry는 `0회`
- 오류 응답의 `resource` 최종 이름과 범용 구조
- v2 이후 응답 계약 또는 envelope codec 변경 시 새 version 식별자와 지원 registry
- 만료 후 같은 키 재사용, 실제 보존 기간, 정리 방식과 정리 전후 동시성 정책

### 16.3 행동 이벤트

- 후속 행동 이벤트 조회 API의 필터·응답·인덱스 계약
- 후속 Rule 입력에서 필요한 위치·관측 신호·유형별 상세의 제한된 필드 계약

### 16.4 탐지 결과

- Rule v1 이후 ML·External Risk·자금흐름 점수 통합과 점수 정밀도
- Rule·External Risk·행동 패턴 요약의 유형별 허용 필드
- 분석 당시 `traceId`와 조회 요청 `traceId`의 응답 구분

## 17. 제외 범위

- Spring Boot 코드
- Controller, DTO, Validation과 Service 구현
- JPA Entity
- PostgreSQL DDL과 마이그레이션
- OpenAPI YAML
- 사건 생성 전용·상태 변경 API
- 조사 메모 API
- AI 사건 리포트 API
- AI 사용량·비용 API
- 플랫폼 운영 API
- Kafka 이벤트 API
- 인증·인가와 CORS 구현
- 구체적인 OpenTelemetry Header 구현
- 실제 금융거래 승인·인증·차단과 고객 제재

## 18. 후속 API 문서 항목

이번 범위와 분리해 후속 승인 작업에서 다음 계약을 정의한다.

- 사건 목록·상세 조회
- 사건 생성 또는 기존 사건 연결 결과 조회
- 사건 상태와 최종 판정 변경
- 사건 동시 수정 충돌
- 조사 메모 생성·조회
- 감사 이력 조회
- AI 리포트 요청·상태·상세·재생성
- AI 리포트 정확 일치 결과 반환
- AI 사용량·토큰·지연시간·비용 조회
- 서비스 상태와 플랫폼 운영 조회

## 19. Issue #178 거래 접수 구현 상태 (2026-08-27)

`POST /api/v1/transactions`는 최종 동기 처리로 연결되었다. 신규 성공은 네 승인된
위험 조합을 Snapshot v2로 저장하고 `201 Created`를 반환한다. legacy 완료 재생은
`200`, v1·v2 완료 재생은 저장된 HTTP status를 사용하며 현재 요청 `traceId`를
결합한다.

| 상황 | HTTP | 공개 code |
| --- | ---: | --- |
| Provider/coordinator 미설정 | 503 | `DEPENDENCY_UNAVAILABLE` |
| Rule 확정 실패 최초·code-only 재생 | 503 | `DEPENDENCY_UNAVAILABLE` |
| External Risk `TIMEOUT` 최초·재생 | 저장된 503 | `DEPENDENCY_TIMEOUT` |
| External Risk `UNAVAILABLE` 최초·재생 | 저장된 503 | `DEPENDENCY_UNAVAILABLE` |
| 나머지 External Risk typed 실패 최초·재생 | 저장된 500 | `INTERNAL_ERROR` |

External Risk 응답은 저장된 공개 status·code·고정 안전 message와 현재 trace만
사용한다. category, Provider 원문, exception 상세, credential, reference와 replay
내부 flag는 공개하지 않는다. 기존 conflict·in-progress·duplicate와 legacy
code-only `DEPENDENCY_TIMEOUT` 매핑은 유지한다.

External Risk lookup과 Rule 단계를 분리해 command read·Provider 단계의 일반 예외는
Rule 실패 reader 대상이 아니며 원본 객체 그대로 `500 INTERNAL_ERROR` 경계로
전파된다. Idempotency는 `IN_PROGRESS`를 유지한다.
