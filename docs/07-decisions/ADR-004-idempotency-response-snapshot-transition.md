# ADR-004: 멱등 응답 Snapshot과 최종 동기 탐지 응답 전환

- 상태: Accepted
- 결정일: 2026-07-29
- 결정자: Project Owner
- 작업 목적: `[Docs] 멱등 응답 Snapshot과 동기 탐지 응답 전환 정책 결정`
- 관련 문서:
  - [`ADR-003-transaction-processing-boundary.md`](./ADR-003-transaction-processing-boundary.md)
  - [`../03-api/api-conventions.md`](../03-api/api-conventions.md)
  - [`../03-api/transaction-detection-api.md`](../03-api/transaction-detection-api.md)
  - [`../04-database/transaction-intake-schema.md`](../04-database/transaction-intake-schema.md)
  - [`../02-architecture/domain-erd.md`](../02-architecture/domain-erd.md)

## 배경과 문제

ADR-003은 최종 `POST /api/v1/transactions`가 거래 접수부터 External Risk 조회, FastAPI Rule·ML 분석, 탐지 결과 저장·채택, 위험 대응과 사건 연결까지 하나의 동기 요청에서 완료되는 경계를 유지한다고 결정했다.

현재 구현은 그중 입력 검증, 멱등 선점과 거래 영속화까지만 수행한다. 최초 성공 시 `processingStatus = RECEIVED`이고 `riskLevel`, `riskResponseOutcome`, `adoptedDetectionResultId`, `caseId`가 null인 응답을 반환하며, 이 일곱 개 업무 필드를 `idempotency_record.response_snapshot`에 저장한다. 따라서 최종 동기 탐지 응답으로 전환할 때 기존 Snapshot을 새 탐지 완료 결과로 바꿀지, 서로 다른 구조를 어떻게 식별·복원할지, 재요청에 어떤 HTTP 상태와 `traceId`를 사용할지 결정해야 한다.

이 ADR은 다음 세 범위를 구분한다.

- **현재 구현**: 저장소의 Java 코드와 V1 Flyway Migration이 지금 수행하는 동작
- **목표 계약**: 최종 동기 탐지 응답 전환 시 지켜야 할 멱등 재생 의미
- **후속 구현 필요**: 이 ADR을 실제 코드·DB·운영 흐름에 반영하기 위해 별도 승인과 구현이 필요한 항목

## 현재 단계적 구현

### 요청 선점과 동일성 비교

현재 Spring Boot는 헤더와 거래 요청을 검증한 뒤 다음 열 개 필드로 고정 순서 JSON을 만든다.

```text
transactionId
transactionType
amount
currencyCode
occurredAt
externalCustomerRef
senderAccountRef
recipientAccountRef
channel
deviceRef
```

UUID는 canonical 문자열, 금액은 10진 정수 문자열, 시각은 UTC ISO-8601 Instant로 정규화하고 nullable 필드는 JSON null로 표현한다. 이 JSON의 UTF-8 byte sequence를 SHA-256으로 계산해 소문자 16진수 64자 `request_fingerprint`로 저장한다. `traceId`, `Idempotency-Key`, 내부 PK, 서버 생성 시각과 처리 상태는 지문에서 제외한다.

거래 생성 작업 범위는 `POST:/api/v1/transactions`이며 `(operation_scope, idempotency_key)`가 Unique이다. 최초 요청은 별도 짧은 트랜잭션에서 `IN_PROGRESS` 레코드를 선점한다. Unique 경합의 패자는 기존 레코드를 조회해 지문과 상태를 비교한다.

### 상태와 저장 필드

실제 `idempotency_record`는 다음 정보를 저장한다.

- 내부 ID
- `operation_scope`
- `idempotency_key`
- `request_fingerprint`
- `processing_status`: `IN_PROGRESS`, `COMPLETED`, `FAILED`
- nullable 거래 FK `financial_transaction_id`
- nullable JSONB `response_snapshot`
- nullable `failure_code`
- `expires_at`
- `created_at`, `updated_at`, nullable `finished_at`

`IN_PROGRESS`는 Snapshot·실패 코드·종료 시각이 없고, `COMPLETED`는 거래 FK·JSON object Snapshot·종료 시각을 가지며, `FAILED`는 Snapshot 없이 실패 코드·종료 시각을 가진다.

### Snapshot 저장 형식과 저장 시점

현재 codec은 다음 일곱 필드만 허용하는 무버전 JSON object를 저장한다.

```json
{
  "transactionId": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
  "processingStatus": "RECEIVED",
  "riskLevel": null,
  "riskResponseOutcome": null,
  "adoptedDetectionResultId": null,
  "caseId": null,
  "createdAt": "2026-07-23T01:15:31Z"
}
```

`traceId`는 저장하지 않는다. 거래 저장과 멱등 레코드의 거래 연결 후 Snapshot을 만들고, 같은 짧은 업무 트랜잭션 안에서 `COMPLETED`와 `finished_at`을 확정한다. 어느 단계든 실패하면 거래 저장과 완료 전이를 롤백하고, 별도 트랜잭션에서 멱등 레코드를 `FAILED`로 전이한다.

현재 Entity는 terminal 상태에서 다시 완료·실패로 전이하지 못하게 하고 Snapshot을 defensive copy한다. 다만 이것만으로 DB 행에 대한 모든 직접 갱신 경로가 물리적으로 금지된 것은 아니다.

### 현재 재요청 처리

| 기존 레코드 | 같은 지문 | 다른 지문 |
| --- | --- | --- |
| `IN_PROGRESS` | `409 Conflict`, `IDEMPOTENCY_REQUEST_IN_PROGRESS` | `409 Conflict`, `IDEMPOTENCY_KEY_CONFLICT` |
| `COMPLETED` | strict codec으로 Snapshot을 복원해 `200 OK` 반환 | `409 Conflict`, `IDEMPOTENCY_KEY_CONFLICT` |
| `FAILED` | 자동 재처리하지 않고 저장된 실패 분류를 공개 whitelist로 매핑 | `409 Conflict`, `IDEMPOTENCY_KEY_CONFLICT` |

최초 성공은 현재 `201 Created`, 완료 재요청은 `200 OK`이다. 업무 필드는 저장된 Snapshot 값이며 `traceId`만 재요청의 현재 값을 결합한다. Snapshot이 손상되었거나 정확한 일곱 필드·타입·`RECEIVED` 상태를 만족하지 않으면 `500 Internal Server Error`와 `INTERNAL_ERROR`로 처리하고 신규 거래 처리로 우회하지 않는다.

현재 `FAILED` 재요청의 공개 whitelist는 다음과 같다.

| 저장된 `failureCode` | 공개 HTTP 상태 | 공개 code | 공개 message |
| --- | --- | --- | --- |
| `DUPLICATE_TRANSACTION` | `409 Conflict` | `DUPLICATE_TRANSACTION` | `이미 존재하는 transactionId입니다.` |
| `DEPENDENCY_TIMEOUT` | `503 Service Unavailable` | `DEPENDENCY_TIMEOUT` | `탐지 서비스를 사용할 수 없습니다.` |
| null·빈 값·알 수 없는 값·내부 전용 값 | `500 Internal Server Error` | `INTERNAL_ERROR` | `요청을 처리하는 중 오류가 발생했습니다.` |

현재 DB와 Entity는 `expires_at = created_at + 24 hours`를 저장한다. 그러나 Service는 claim 시 `expires_at`을 판정하지 않고 만료 레코드 정리 작업도 구현하지 않았다. 따라서 24시간은 현재 저장된 시각과 DB 제약일 뿐, 만료 후 키 재사용까지 시행하는 실질적인 만료 정책이 아니다.

## 최종 동기 탐지 응답 목표

최종 동기 흐름은 ADR-003을 유지한다. 최초 요청은 탐지 결과 검증·저장·채택, 위험 대응과 필요한 사건 연결이 업무적으로 확정된 뒤에만 성공 Snapshot을 확정한다. 최종 응답은 `RECEIVED` placeholder가 아니라 그 요청에서 확정된 거래·탐지·위험 대응·사건 연결 결과를 반영한다.

생성형 AI 리포트는 이 동기 경로의 필수 결과가 아니며 위험 점수, 거래 대응과 사건 상태를 결정하지 않는다.

## 고려한 대안

### 대안 A: 기존 Snapshot을 최신 거래 상태로 갱신

기존 키 재요청에서 최신 탐지·거래 상태를 보여주기 쉽지만 최초 명령 결과를 재현할 수 없고, 재분석이나 사건 상태 변경 시 같은 키의 응답 의미가 달라진다. 멱등 재생과 최신 상태 조회 책임도 섞인다.

### 대안 B: 기존 `RECEIVED` Snapshot을 최종 탐지 응답으로 일괄 변환

한 가지 응답 구조만 유지할 수 있지만 기존 요청 당시 존재하지 않았던 결과를 소급 생성해야 한다. 최초 응답의 사실성과 감사 가능성을 훼손하고, 어떤 시점의 최신 값을 변환에 사용했는지 재현하기 어렵다.

### 대안 C: 무버전 JSON을 새 구조로 암묵 해석

Migration 없이 구현하기 쉽지만 필드 추가·삭제가 codec 변경인지 응답 계약 변경인지 구분할 수 없다. 알 수 없는 구조를 잘못 해석해 다른 응답을 반환할 위험이 있다.

### 대안 D: Legacy Snapshot은 그대로 두고 신규 요청부터 버전 envelope 저장

기존 최초 결과를 보존하면서 전환 이후 요청은 명시적 메타데이터로 복원할 수 있다. 대신 legacy와 신규 codec을 함께 운영해야 하고 전환 기간의 응답 HTTP 상태가 서로 다를 수 있다.

## 최종 결정

대안 D를 채택한다.

- 동일한 `Idempotency-Key`와 동일한 요청은 해당 요청에서 최초 확정된 업무 결과를 재생한다.
- 같은 키에 다른 요청이 오면 기존 상태와 관계없이 `IDEMPOTENCY_KEY_CONFLICT`로 거부한다.
- 멱등 응답 재생은 최초 명령 결과를 재현하는 기능이며 최신 거래·탐지·사건 상태 조회 기능이 아니다.
- 확정된 Snapshot은 후속 탐지 결과, 재분석, 거래 상태 또는 사건 상태가 바뀌어도 수정하지 않는다.
- 기존 무버전 `RECEIVED`/null Snapshot은 신규 최종 응답으로 소급 교체하지 않는다.
- 전환 이후 새로 선점된 요청부터 최종 동기 탐지 응답을 신규 envelope로 저장한다.

## 요청 동일성 기준

현재 구현의 열 개 검증 필드, 정규화 방식과 SHA-256 `request_fingerprint`를 유지한다. 요청 동일성은 원본 JSON 문자열의 바이트 동일성이 아니라 검증·정규화된 업무 입력의 fingerprint 동일성이다.

fingerprint 입력 필드, 정규화 또는 해시 알고리즘을 바꾸려면 기존 레코드와의 비교 가능성을 검토하고 별도 버전·전환 결정을 해야 한다. 이 ADR은 fingerprint 버전을 새로 도입하거나 현재 알고리즘을 변경하지 않는다.

## Snapshot 저장 및 재생 정책

### 신규 Snapshot envelope

신규 envelope는 `response_snapshot` JSON object 안에서 최소 다음 항목을 식별한다.

```json
{
  "responseBody": {
    "transactionId": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
    "processingStatus": "HELD",
    "riskLevel": "CRITICAL",
    "riskResponseOutcome": "HELD",
    "adoptedDetectionResultId": "det_example",
    "caseId": "case_example",
    "createdAt": "2026-07-29T01:15:31Z"
  },
  "httpStatus": 201,
  "responseSchemaVersion": "<registered-response-schema-version>",
  "codecVersion": "<registered-codec-version>",
  "finalizedAt": "2026-07-29T01:15:33Z"
}
```

예시의 업무 값과 버전 placeholder는 구조 설명용이며 실제 응답 값이나 버전 식별자를 확정하지 않는다.

- `responseBody`: 최초 확정된 업무 응답. 요청별 관측 문맥인 `traceId`는 제외한다.
- `httpStatus`: 최초 확정 응답에 실제 사용한 HTTP 상태.
- `responseSchemaVersion`: `responseBody`의 필드·타입·의미 계약 버전.
- `codecVersion`: envelope 직렬화·역직렬화 규칙 버전.
- `finalizedAt`: Snapshot이 terminal 성공 결과로 확정된 시각.

신규 envelope 재요청은 기록된 `httpStatus`를 사용한다. 따라서 현재의 “최초 `201`, 완료 재요청 `200`” 동작은 legacy/current 구현으로 한정되며, 신규 envelope 전환 뒤에는 저장된 최초 확정 상태를 재생하는 목표 계약으로 바뀐다. 최초 확정 HTTP 상태 자체는 전환 시점의 승인된 API 공통 규칙을 따르며 이 ADR이 새로운 상태 코드를 추가하지 않는다.

### `traceId` 재생 범위

“동일 응답 재생”은 업무 결과와 기록된 HTTP 상태를 대상으로 하며 HTTP 응답 전체의 바이트 단위 동일성을 뜻하지 않는다.

- 거래·탐지·위험 대응·사건 연결을 포함한 `responseBody`의 업무 값은 최초 확정 값을 유지한다.
- `traceId`는 Snapshot에 넣지 않고 각 재요청의 현재 `traceId`를 응답에 결합한다.
- 응답 추적 헤더도 현재 재요청의 추적 문맥을 사용한다.
- JSON 직렬화의 공백, 필드 출력 순서와 전송 헤더처럼 업무 계약 밖의 바이트 표현은 Snapshot 불변성 대상이 아니다.

이 예외는 재요청 자체를 로그·트레이스에서 추적하면서 최초 업무 결과를 보존하기 위한 것이다. 저장된 과거 `traceId`나 최초 요청의 네트워크 문맥을 현재 요청의 추적값처럼 재사용하지 않는다.

## Snapshot 불변성

Snapshot이 terminal 성공으로 확정되면 다음 값을 같은 멱등 레코드에서 수정하지 않는다.

- `responseBody`
- `httpStatus`
- `responseSchemaVersion`
- `codecVersion`
- `finalizedAt`

최신 거래 상태, 새 DetectionResult, 재분석 결과, 사건 진행 상태 또는 정정 이력은 해당 도메인 데이터와 조회 API에서 표현한다. Snapshot 오류를 발견해도 같은 레코드를 최신 값으로 보정하지 않으며, 데이터 복구가 필요하면 감사 가능한 별도 운영 절차를 결정한다.

## 스키마·codec 버전 정책

- `responseSchemaVersion`과 `codecVersion`은 서로 다른 책임을 가진다. 응답 필드 의미가 바뀌면 전자를, envelope 인코딩 규칙이 바뀌면 후자를 변경한다.
- 각 식별자는 이미 다른 의미로 사용한 값을 재사용하지 않는다.
- decoder는 `codecVersion`으로 envelope를 해석한 뒤 `responseSchemaVersion`에 대응하는 typed 응답을 복원한다.
- 지원 중인 구버전 decoder는 해당 Snapshot 보존·재생 범위 동안 제거하지 않는다.
- 버전이 달라도 기존 Snapshot을 새 버전으로 제자리 갱신하지 않는다.
- 실제 최초 버전 식별자 값과 지원 버전 registry 구성은 후속 구현에서 확정한다.

## 기존 `RECEIVED`/null Snapshot 전환 정책

기존 무버전 Snapshot은 다음 조건을 모두 만족할 때만 legacy Snapshot으로 인정한다.

- 정확히 기존 일곱 필드를 가진 JSON object
- 기존 codec이 요구하는 필드 타입과 UUID·시각 형식
- `processingStatus = RECEIVED`
- 현재 데이터 기준 네 탐지 관련 필드는 null
- `traceId`나 추가 필드가 없음

legacy Snapshot은 엄격한 legacy codec으로만 복원하고 신규 envelope로 소급 갱신하지 않는다. 최초 HTTP 상태, `responseSchemaVersion`, `codecVersion`과 확정 시각이 Snapshot 내부에 없으므로 재요청은 현재 동작인 `200 OK`를 유지하고 현재 요청의 `traceId`를 결합한다.

필드가 비슷하다는 이유로 legacy 또는 신규 버전을 추측하지 않는다. legacy strict shape도 아니고 지원하는 envelope도 아니면 내부 재생 실패로 처리하며 신규 거래 처리로 우회하지 않는다.

## 처리 중·성공·실패 요청 정책

### 처리 중

같은 키·같은 fingerprint가 `IN_PROGRESS`이면 새 거래·탐지·외부 호출을 시작하지 않고 기존 `409 Conflict`, `IDEMPOTENCY_REQUEST_IN_PROGRESS`를 반환한다. 기다림, polling 또는 자동 재시도 계약은 이 ADR에서 추가하지 않는다.

### 성공

`COMPLETED`이면 저장된 legacy 또는 신규 Snapshot만 복원한다. legacy는 현재 `200` 규칙, 신규 envelope는 저장된 최초 확정 HTTP 상태를 사용한다. 두 경우 모두 업무 결과는 최초 확정 값이며 `traceId`만 현재 요청 값이다.

### 실패

`FAILED`인 같은 키·같은 fingerprint는 자동 재처리하지 않는다. 이는 실패 원인이 사라졌는지와 무관하게 같은 키로 새로운 거래·탐지 실행을 만들지 않는다는 결정이다.

현재 공개 whitelist의 `DUPLICATE_TRANSACTION`, `DEPENDENCY_TIMEOUT`만 기존 HTTP 상태·공개 code·고정 message로 재현하고, null·빈 값·알 수 없는 값과 내부 전용 값은 `500 Internal Server Error`, `INTERNAL_ERROR`로 축약한다. 저장된 내부 실패 문자열을 그대로 공개하지 않는다.

실패 응답 전체를 성공 Snapshot envelope에 저장하거나 신규 오류 code를 추가하지 않는다. 향후 실패 응답 Snapshot이 필요하면 민감정보 제외, `traceId` 처리, 상태·스키마 버전과 기존 `failure_code` 관계를 별도 승인한다.

## 역직렬화 실패와 알 수 없는 버전 처리

- JSON 파싱 실패, 필수 metadata 누락, 잘못된 타입, 지원하지 않는 `codecVersion` 또는 `responseSchemaVersion`은 재생 실패로 처리한다.
- 알 수 없는 버전을 최신 응답 DTO나 가장 가까운 codec으로 추측해 해석하지 않는다.
- DB의 현재 거래·탐지 상태로 응답을 재구성하지 않는다.
- 멱등 레코드를 삭제하거나 `IN_PROGRESS`로 되돌리지 않는다.
- 신규 거래·탐지·외부 서비스 호출로 우회하지 않는다.
- 공개 오류는 기존 공통 `500 Internal Server Error`, `INTERNAL_ERROR` 규칙을 우선한다. 새 오류 code는 별도 API 승인 전 확정하지 않는다.
- 원본 Snapshot, fingerprint, 멱등 키와 민감 참조값을 오류 응답이나 고카디널리티 메트릭 레이블에 노출하지 않는다.

## 최신 상태 조회와 멱등 응답 재생의 책임 구분

| 목적 | 책임 |
| --- | --- |
| 최초 명령에서 확정한 업무 결과 재현 | `POST /api/v1/transactions`의 멱등 Snapshot 재생 |
| 거래의 현재 처리 상태 조회 | 거래 조회 API |
| 채택 결과와 새 분석 이력 조회 | 탐지 결과 조회 API |
| 사건의 현재 진행 상태와 최종 판정 조회 | 사건 조회 API |

재요청 시 최신 테이블을 조회해 Snapshot 값을 덮어쓰거나 혼합하지 않는다. 클라이언트가 최신 상태를 원하면 별도 조회 API를 호출한다.

## 장점과 단점

### 장점

- 최초 명령 결과의 감사 가능성과 재현성을 보존한다.
- 기존 `RECEIVED` 응답에 미래 탐지 결과를 소급 귀속하지 않는다.
- 응답 계약 버전과 저장 codec 버전을 독립적으로 진화시킬 수 있다.
- 알 수 없는 데이터에서 중복 거래·탐지·외부 호출이 발생하는 fail-open을 막는다.
- 현재 요청의 `traceId`를 사용해 재요청 자체를 관측할 수 있다.

### 단점

- legacy와 신규 decoder를 함께 유지해야 한다.
- 전환 기간에는 legacy 재요청의 `200`과 신규 envelope의 기록된 HTTP 상태가 공존한다.
- 최신 상태를 보려는 클라이언트는 별도 조회 API를 사용해야 한다.
- 버전 지원 목록, 손상 데이터 대응과 만료 정리를 운영해야 한다.

## 구현 영향

다음은 **후속 구현 필요**이며 현재 구현된 것으로 간주하지 않는다.

- 신규 envelope DTO와 encoder·decoder
- legacy strict decoder와 신규 version dispatch
- 신규 envelope 재요청에서 저장된 HTTP 상태를 사용하는 Controller 매핑
- 최종 동기 탐지 결과가 모두 확정된 뒤 Snapshot을 완료하는 트랜잭션 경계
- Snapshot 불변성 단위·통합 테스트
- unknown version, 역직렬화 실패와 fail-closed 재생 테스트
- legacy `200`과 신규 기록 상태 재생의 호환 테스트
- `traceId`가 Snapshot에 저장되지 않고 현재 요청 값으로 결합되는 테스트

Java·Python·테스트·설정은 이 ADR 문서 작업에서 변경하지 않는다.

## Migration 영향

현재 V1 Migration의 `response_snapshot JSONB`와 JSON object 제약은 legacy Snapshot을 저장하며, 신규 envelope에 필요한 별도 metadata 컬럼이나 DB 수준 버전 제약은 없다. 논리 envelope를 같은 JSONB에 저장할 수 있는지는 가능하지만, DB 제약·조회·운영 요구까지 포함한 최종 물리 설계를 의미하지 않는다.

- 기존 V1 Migration을 수정하지 않는다.
- 기존 Snapshot을 신규 envelope로 backfill하지 않는다.
- metadata를 JSONB 내부에만 둘지 별도 컬럼으로 둘지, 추가 Check Constraint나 인덱스가 필요한지는 후속 구현 설계에서 결정한다.
- 스키마 변경이 필요하면 새 Flyway Migration으로만 수행한다.
- 필요한 Migration 버전과 정확한 DDL은 이 ADR에서 확정하지 않는다.

## 운영 및 관측 고려사항

최소 다음 결과를 저카디널리티 분류로 관측할 수 있어야 한다.

- 최초 완료
- legacy 완료 재생
- 신규 envelope 완료 재생
- key conflict
- request in progress
- previous failure replay
- legacy decode failure
- unknown codec version
- unknown response schema version
- envelope decode failure

원본 `Idempotency-Key`, fingerprint, 거래·고객·계좌 참조값과 Snapshot 원문은 메트릭 레이블에 사용하지 않는다. 로그는 `traceId`로 현재 재요청을 연결하되 민감정보와 Snapshot 본문을 출력하지 않는다.

현재 `expires_at`에는 최초 선점 시각의 24시간 후가 저장되지만 Service 판정과 정리 작업이 없으므로 실질적인 만료 시행으로 보지 않는다. 다음은 후속 결정이다.

- 만료 후 같은 `(operation_scope, idempotency_key)` 재사용 허용 여부
- 만료 레코드의 정리 방식, 주기와 batch 범위
- 정리 전후 claim·재생·삭제의 동시성 처리
- 실제 보존 기간과 감사·규제·운영 요구

외부 서비스 호출량과 AI 비용은 이 문서 변경으로 증가하지 않는다. 후속 구현에서도 완료 재생, 처리 중 거절, 충돌과 실패 재생은 새 External Risk·FastAPI·LLM 호출을 만들지 않아야 한다.

## 후속 작업

1. 최종 동기 거래 응답의 승인된 필드·Enum과 최초 HTTP 상태를 확정한다.
2. `responseSchemaVersion`과 `codecVersion`의 실제 최초 식별자 및 지원 registry를 결정한다.
3. legacy/new codec과 저장된 HTTP 상태 재생을 구현하고 호환 테스트를 추가한다.
4. 최종 동기 탐지·위험 대응·사건 연결 완료 트랜잭션과 실패 경계를 구현한다.
5. metadata의 JSONB 내부 저장 또는 별도 컬럼 여부와 필요한 새 Flyway Migration을 결정한다.
6. 만료 후 키 재사용, 실제 보존 기간, 정리 작업과 동시성 정책을 별도 승인한다.
7. 추가 공개 오류 code 또는 실패 whitelist가 필요하면 API 공통 규칙 변경으로 승인한다.
