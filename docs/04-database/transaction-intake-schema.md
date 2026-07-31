# 거래 접수 PostgreSQL 물리 DB 계약

## 1. 문서 목적

이 문서는 FinGuardOps 거래 접수 구현의 기준이 되는 PostgreSQL 물리 스키마와 멱등 처리 계약을 정의한다.

적용 범위는 다음 두 테이블이다.

- `financial_transaction`
- `idempotency_record`

이 문서는 다음 기준 문서와 함께 사용한다.

- [`../03-api/api-conventions.md`](../03-api/api-conventions.md)
- [`../03-api/transaction-detection-api.md`](../03-api/transaction-detection-api.md)
- [`../02-architecture/domain-erd.md`](../02-architecture/domain-erd.md)
- [`../01-requirements/transaction-state-transition.md`](../01-requirements/transaction-state-transition.md)
- [`../07-decisions/ADR-003-transaction-processing-boundary.md`](../07-decisions/ADR-003-transaction-processing-boundary.md)

논리 모델보다 이 문서가 더 구체적으로 확정한 거래 접수의 PostgreSQL 컬럼, 제약조건, 인덱스와 멱등 정책은 후속 Flyway Migration, JPA 매핑과 Testcontainers 통합 테스트의 구현 기준이다.

## 2. 범위와 제외 범위

### 2.1 이번 계약의 범위

- 유효한 거래 요청의 최초 영속화
- 거래 업무 식별자와 내부 식별자의 분리
- 거래 유형별 수취 계좌와 접수 채널 검증
- 거래 금액, 통화와 발생 시각 검증
- 거래 상태 변경을 위한 낙관적 잠금
- 거래 생성 요청의 멱등 선점, 충돌 판별과 완료 결과 재사용
- 멱등 기록의 24시간 후 `expires_at` 시각 저장
- Flyway와 실제 PostgreSQL 기반 검증 원칙

### 2.2 이번 계약의 제외 범위

- 실제 Flyway SQL 파일 작성
- JPA Entity, Repository, Service와 Controller 구현
- 사건과 감사 로그의 물리 테이블
- 사건 연결 FK
- Redis 기반 멱등성
- Kafka, Outbox, Worker와 Scheduler 구현
- 인증·인가와 CORS

탐지 결과와 채택 관계는 후속 승인된
[`detection-result-schema.md`](./detection-result-schema.md)와 V3
Migration에서 추가되었다. 사건 관계는 사건 물리 스키마 승인 후 별도
Migration으로 추가한다.

## 3. 확정된 거래 접수 경계

### 3.1 처리 순서

거래 요청은 다음 순서로 처리한다.

```text
JSON·헤더 형식 검증
→ 요청 DTO 필드·도메인 Validation
→ 정규화 요청 지문 계산
→ 멱등 요청 선점
→ financial_transaction 최초 저장
→ 후속 분석·위험 대응 처리
→ 멱등 완료 결과 확정
```

- JSON 파싱, `Idempotency-Key` 검증, 필수 필드·UUID·Enum·금액·통화·시각 형식 검증 실패는 `400 Bad Request`와 `VALIDATION_ERROR`로 처리한다.
- 형식은 올바르지만 거래 유형별 `recipientAccountRef`·`channel` 또는 그 밖의 도메인 규칙을 위반하면 `422 Unprocessable Entity`와 `VALIDATION_ERROR`로 처리한다.
- 요청 형식 또는 도메인 Validation에 실패하면 `financial_transaction`과 `idempotency_record`를 생성하지 않는다.
- Validation 거절은 오류 응답, `traceId`, 민감정보를 제외한 로그와 운영 메트릭으로만 관측한다.
- `VALIDATION_FAILED`는 현재 거래 접수의 영속 `processing_status` 값으로 사용하지 않는다.
- 검증을 통과해 최초 저장되는 거래의 초기 상태는 `RECEIVED`이다.

잘못된 요청을 보존하기 위해 임의의 거래 ID나 거래 행을 만들지 않는다. Validation 거절 건수와 원인은 DB 거래 행 수가 아니라 승인된 저카디널리티 오류 코드 기반 메트릭으로 집계한다.

### 3.2 업무 식별자

- 내부 관계와 PK에는 `BIGINT GENERATED ALWAYS AS IDENTITY`를 사용한다.
- 외부 요청·응답·로그와 업무 조회에는 클라이언트가 요청 DTO로 전달한 UUID v4 `transactionId`를 사용한다.
- PostgreSQL 컬럼 타입은 `UUID`이다.
- UUID version nibble이 4이고 RFC 4122 variant 비트가 `10`인지 DB `CHECK`로 보조 검증한다.
- 내부 `id`는 API에 노출하지 않고 `transactionId`와 서로 대체하지 않는다.

## 4. 거래 요청 Validation 계약

### 4.1 요청 필드

요청 지문과 `financial_transaction` 저장의 기준이 되는 요청 DTO 필드는 다음 열 개이다.

| 순서 | 요청 필드 | 필수 | 저장 컬럼 | 확정 규칙 |
| --- | --- | --- | --- | --- |
| 1 | `transactionId` | 필수 | `transaction_id` | UUID v4 |
| 2 | `transactionType` | 필수 | `transaction_type` | 승인된 네 거래 유형 중 하나 |
| 3 | `amount` | 필수 | `amount` | 0보다 큰 10진 정수 문자열 |
| 4 | `currencyCode` | 필수 | `currency_code` | 초기에는 `KRW`만 허용 |
| 5 | `occurredAt` | 필수 | `occurred_at` | UTC ISO-8601, 서버 시각보다 최대 5분 미래까지 허용 |
| 6 | `externalCustomerRef` | 필수 | `external_customer_ref` | 1~128자, 앞뒤 공백과 빈 문자열 금지 |
| 7 | `senderAccountRef` | 필수 | `sender_account_ref` | 1~128자, 거래의 기준 계좌 참조값 |
| 8 | `recipientAccountRef` | 조건부 | `recipient_account_ref` | 거래 유형별 필수 또는 금지 |
| 9 | `channel` | 필수 | `channel` | 거래 유형별 단일 허용값 |
| 10 | `deviceRef` | 선택 | `device_ref` | null 또는 1~128자, 앞뒤 공백과 빈 문자열 금지 |

`senderAccountRef`는 네 거래 유형 모두에서 FinGuardOps가 추적하는 기준 계좌를 나타낸다. `LOAN_DISBURSED`에서는 대출금 입금 대상 계좌를 이 필드로 표현하며 별도 `accountRef` 필드를 추가하지 않는다.

### 4.2 거래 유형별 `recipientAccountRef`와 `channel`

`channel`은 거래가 FinGuardOps에 유입된 접수 경로이다.

| `transactionType` | `recipientAccountRef` | 허용 `channel` | 의미 |
| --- | --- | --- | --- |
| `ACCOUNT_TRANSFER` | 필수 | `MOBILE_BANKING` | 모바일뱅킹을 통한 계좌이체 |
| `OPEN_BANKING_TRANSFER` | 필수 | `OPEN_BANKING` | 오픈뱅킹 연계 거래 |
| `ATM_WITHDRAWAL` | 금지, 반드시 null | `ATM` | ATM 인출 |
| `LOAN_DISBURSED` | 금지, 반드시 null | `CORE_BANKING` | 코어뱅킹 Mock에서 전달된 대출 실행 이벤트 |

`ACCOUNT_TRANSFER`의 인터넷뱅킹과 창구 등 추가 채널은 현재 MVP 범위에서 제외한다. 채널을 추가하려면 API 계약, 이 문서의 `CHECK` 제약과 Flyway Migration을 함께 변경해야 한다.

### 4.3 금액과 통화

- API의 `amount`는 정밀도 손실을 피하기 위해 문자열로 전달한다.
- 문자열을 `BigDecimal`로 정확히 파싱한 값이 0보다 크고 소수부가 없는 정수여야 한다.
- 지수 표기, 반올림과 자동 절삭은 허용하지 않는다.
- PostgreSQL 타입은 `NUMERIC(19,4)`이다.
- DB는 `amount > 0`과 `amount = trunc(amount)`를 `CHECK`로 검증한다.
- `NUMERIC(19,4)`에 따라 저장 가능한 최대 정수 금액은 `999999999999999`이다.
- 저장값은 소수점 네 자리 scale로 표현될 수 있지만 업무 값은 정수이다.
- 초기 지원 통화는 `KRW` 하나이며 다른 통화는 거부한다.

### 4.4 발생 시각

- API는 `Z` 접미사를 가진 UTC ISO-8601 시각만 허용한다.
- `occurredAt`은 서버 시각보다 과거일 수 있다.
- 미래 시각은 Validation 시점의 서버 시각보다 최대 5분까지 허용한다.
- 애플리케이션 Validation은 주입 가능한 서버 Clock을 기준으로 수행한다.
- PostgreSQL은 시간에 따라 결과가 달라지는 `CURRENT_TIMESTAMP`를 `CHECK`에 직접 사용하지 않고 `occurred_at <= created_at + INTERVAL '5 minutes'`로 방어적 검증한다.
- `created_at`은 PostgreSQL DB clock이 설정하는 최초 저장 시각이며 클라이언트 입력으로 받지 않는다.
- Testcontainers에서는 애플리케이션 Clock의 5분 경계와 DB `created_at` 기준의 방어 제약을 각각 검증한다.

## 5. 요청 지문 계약

### 5.1 포함 필드

거래 생성 요청 지문에는 다음 열 개의 정규화된 요청 DTO 필드를 정확히 포함한다.

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

한 필드라도 값이 다르면 다른 요청 지문이다.

### 5.2 제외 필드

다음 값은 요청 지문에 포함하지 않는다.

- HTTP `Idempotency-Key`
- `traceId`와 추적 헤더
- 내부 Identity PK
- 서버가 생성하는 `createdAt`, `updatedAt`, `version`
- 서버가 결정하는 `processingStatus`
- 탐지 결과, 위험 등급, 위험 대응과 사건 식별자
- 완료 응답의 서버 생성 필드

### 5.3 정규화

요청 DTO를 검증한 뒤 다음 규칙으로 정규화한다.

1. 알 수 없는 JSON 필드는 검증 단계에서 거부하며 지문 입력에 포함하지 않는다.
2. JSON의 원래 필드 순서, 공백과 숫자 토큰 표현을 사용하지 않고 위 5.1절의 고정 필드 순서를 사용한다.
3. `transactionId`는 `UUID.toString()`에 해당하는 소문자 하이픈 표기로 직렬화한다.
4. `transactionType`, `currencyCode`와 `channel`은 검증된 계약 값 그대로 사용하며 대소문자를 자동 교정하지 않는다.
5. `amount`는 DTO 형식 검증에서 지수 표기와 소수점을 거부한다. 검증된 `"1250000"` 같은 값을 `BigDecimal.toBigIntegerExact().toString()`에 해당하는 부호 없는 10진 정수 문자열로 직렬화한다. `"1250000.00"`은 값이 수학적으로 정수여도 이번 요청 형식에서는 거부한다.
6. `occurredAt`은 파싱한 시각을 UTC Instant의 ISO-8601 `Z` 표기로 직렬화한다.
7. 참조값은 앞뒤 공백을 제거해 교정하지 않는다. 앞뒤 공백이 있으면 Validation에서 거부하고 검증된 원문 UTF-8 값을 사용한다.
8. 선택 필드인 `recipientAccountRef`와 `deviceRef`는 누락과 명시적 null을 모두 JSON `null`로 정규화한다.
9. 고정 순서의 키와 정규화된 값을 공백 없는 UTF-8 JSON object로 직렬화한다.

정규화 예시는 다음과 같다.

```json
{"transactionId":"2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001","transactionType":"ACCOUNT_TRANSFER","amount":"1250000","currencyCode":"KRW","occurredAt":"2026-07-23T01:15:30Z","externalCustomerRef":"cust_ref_demo_a7f2","senderAccountRef":"acct_ref_demo_s91c","recipientAccountRef":"acct_ref_demo_r44d","channel":"MOBILE_BANKING","deviceRef":"device_ref_demo_18b3"}
```

### 5.4 해시

- 정규화 JSON의 UTF-8 byte sequence를 SHA-256으로 계산한다.
- DB에는 32-byte digest의 소문자 16진수 64자를 `request_fingerprint VARCHAR(64)`로 저장한다.
- 해시 비교는 64자 전체가 일치해야 한다.
- 로그와 메트릭에는 요청 DTO 원문이나 요청 지문 전체를 고카디널리티 레이블로 기록하지 않는다.

## 6. `Idempotency-Key` 계약

### 6.1 형식

- 필수 HTTP 헤더이다.
- 길이는 8~128자이다.
- 허용 문자는 영문 대문자·소문자, 숫자, 마침표(`.`), 밑줄(`_`), 콜론(`:`), 하이픈(`-`)뿐이다.
- 정규식은 `^[A-Za-z0-9._:-]{8,128}$`이다.
- 공백, 한글, 슬래시, 역슬래시와 그 밖의 문자는 허용하지 않는다.
- 서버는 키를 trim하거나 대소문자 변환하지 않는다.

### 6.2 작업 범위

현재 거래 생성 작업의 `operationScope` 값은 다음 문자열로 고정한다.

```text
POST:/api/v1/transactions
```

멱등성 Unique 범위는 다음 조합이다.

```text
operation_scope + idempotency_key
```

같은 키라도 다른 작업 범위에서는 별도 요청으로 취급할 수 있다. 새 작업 범위는 해당 API 계약을 승인한 뒤 추가한다.

### 6.3 상태와 응답

`idempotency_record.processing_status`는 다음 값만 사용한다.

```text
IN_PROGRESS
COMPLETED
FAILED
```

거래 생성의 확정 동작은 다음과 같다.

| 상황 | HTTP·오류 | 처리 |
| --- | --- | --- |
| 새 키의 최초 성공 | `201 Created` | 거래와 완료 결과를 한 번만 생성 |
| 같은 키 + 같은 지문 + `COMPLETED` | legacy `200 OK`, 신규 envelope의 저장된 `201 Created` | 새 처리를 시작하지 않고 기존 완료 결과 반환 |
| 같은 키 + 다른 지문 | `409 Conflict`, `IDEMPOTENCY_KEY_CONFLICT` | 기존 기록과 거래를 변경하지 않음 |
| 같은 키 + 같은 지문 + `IN_PROGRESS` | `409 Conflict`, `IDEMPOTENCY_REQUEST_IN_PROGRESS` | 새 처리·거래·탐지·사건을 시작하지 않음 |
| 같은 키 + 같은 지문 + `FAILED` | 고정 whitelist 또는 `500 INTERNAL_ERROR` | 같은 키의 업무 처리를 자동 재실행하지 않음 |
| 다른 키 + 같은 `transactionId` | `409 Conflict`, `DUPLICATE_TRANSACTION` | 기존 거래를 덮어쓰지 않음 |

충돌 판별은 기존 레코드의 상태를 해석하기 전에 지문 일치 여부를 먼저 확인한다. 따라서 같은 키의 지문이 다르면 기존 처리가 진행 중이어도 `IDEMPOTENCY_KEY_CONFLICT`이다.

전환 이후 신규 최초 성공은 `response_snapshot`에 다음 envelope를 보존한다.

```json
{
  "responseBody": {
    "transactionId": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
    "processingStatus": "RECEIVED",
    "riskLevel": null,
    "riskResponseOutcome": null,
    "adoptedDetectionResultId": null,
    "caseId": null,
    "createdAt": "2026-07-23T01:15:31Z"
  },
  "httpStatus": 201,
  "responseSchemaVersion": "transaction-create-response-v1",
  "codecVersion": "transaction-intake-snapshot-envelope-v1",
  "finalizedAt": "2026-07-23T01:15:33Z"
}
```

`traceId`, `idempotencyRecordId`, 요청 지문, 내부 PK, 요청 본문, 고객·계좌 참조값은 snapshot에 저장하지 않는다. 네 nullable 업무 필드는 JSON에서 생략하지 않고 명시적 null로 저장한다. 신규 envelope 완료 재전송은 검증된 `responseBody`와 저장된 `201`을 복원하고 현재 재전송 요청의 새 `traceId`를 결합한다.

기존 무버전 Snapshot은 `responseBody`에 표시한 일곱 필드만 최상위에 정확히 가지며 `processingStatus=RECEIVED`, 네 탐지 관련 필드가 모두 JSON null일 때만 strict legacy로 복원한다. legacy 재전송은 `200 OK`를 유지하며 신규 envelope로 갱신하지 않는다. 손상되었거나 두 계약과 다른 snapshot, 알 수 없는 version은 보정하지 않고 `500 INTERNAL_ERROR`로 처리하며 snapshot 원문을 로그나 오류 응답에 노출하지 않는다.

`finalizedAt`과 `finished_at`은 완료 경로에서 `Clock`을 한 번 읽은 같은 확정 시각이다. V1의 무정밀도 지정 `TIMESTAMPTZ`가 PostgreSQL 기본 마이크로초 정밀도를 사용하므로 애플리케이션은 이 값을 마이크로초로 정규화한 뒤 두 위치에 동일하게 저장한다.

`FAILED`인 같은 키·같은 지문의 요청은 자동 재실행하지 않는다. `DUPLICATE_TRANSACTION`은 `409`, `DEPENDENCY_TIMEOUT`은 `503`으로만 고정 재현한다. null, 빈 값, 알 수 없는 값과 내부 전용 `TRANSACTION_INTAKE_FAILED`는 원문을 노출하지 않고 `500 INTERNAL_ERROR`로 축약한다.

### 6.4 24시간 시각 저장과 미구현 만료 정책

- `expires_at = created_at + INTERVAL '24 hours'`로 저장한다.
- Service는 `expires_at`을 판정하지 않으며 만료 레코드 정리 작업도 구현하지 않았다.
- 따라서 24시간은 현재 DB에 저장되는 시각과 Check Constraint일 뿐, 만료 후 키 재사용을 허용하는 시행 중인 유효기간이 아니다.
- 만료 시각이 지난 행도 Unique 제약에 남고 현재 요청은 기존 상태에 따라 처리된다.
- 실제 보존 기간, 키 재사용, 정리 주기·batch와 경합 정책은 후속 승인 사항이다.
- `financial_transaction`은 멱등 기록과 함께 삭제하지 않는다.

## 7. `financial_transaction` 테이블

### 7.1 컬럼

| 컬럼 | PostgreSQL 타입 | Null | Default | 제약·의미 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` Identity | NOT NULL | Identity | 내부 PK |
| `transaction_id` | `UUID` | NOT NULL | 없음 | UUID v4 업무 ID, Unique |
| `transaction_type` | `VARCHAR(32)` | NOT NULL | 없음 | 네 거래 유형 |
| `amount` | `NUMERIC(19,4)` | NOT NULL | 없음 | 0보다 큰 정수 |
| `currency_code` | `VARCHAR(3)` | NOT NULL | 없음 | `KRW`만 허용 |
| `occurred_at` | `TIMESTAMPTZ` | NOT NULL | 없음 | 거래 발생 UTC 시각 |
| `external_customer_ref` | `VARCHAR(128)` | NOT NULL | 없음 | 외부 고객 참조값 |
| `sender_account_ref` | `VARCHAR(128)` | NOT NULL | 없음 | 기준 계좌 참조값 |
| `recipient_account_ref` | `VARCHAR(128)` | nullable | 없음 | 거래 유형별 필수 또는 금지 |
| `channel` | `VARCHAR(32)` | NOT NULL | 없음 | 거래 유형별 접수 경로 |
| `device_ref` | `VARCHAR(128)` | nullable | 없음 | 외부 기기 참조값 |
| `processing_status` | `VARCHAR(32)` | NOT NULL | `'RECEIVED'` | 거래 처리 상태 |
| `version` | `BIGINT` | NOT NULL | `0` | 낙관적 잠금 버전 |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | 최초 저장 시각 |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | 마지막 변경 시각 |

현재 허용하는 영속 거래 상태는 다음과 같다.

```text
RECEIVED
ANALYZING
ANALYZED
APPROVED
ADDITIONAL_AUTH_REQUIRED
HELD
FAILED
```

`VALIDATION_FAILED`는 포함하지 않는다. V3는 nullable
`adopted_detection_result_id`, `risk_level`, `risk_response_outcome`을
추가했지만 현재 거래 접수는 이 값을 설정하지 않고 기존
`RECEIVED`/null 응답을 유지한다. 구체적인 제약은
[`detection-result-schema.md`](./detection-result-schema.md)를 따른다.

### 7.2 감사 시각 clock과 정밀도

`financial_transaction.created_at`과 `updated_at`의 authoritative clock은
PostgreSQL이다. JPA 저장 경로는 Hibernate의 DB-source timestamp
generation을 사용하고, 직접 SQL INSERT는 컬럼의 `CURRENT_TIMESTAMP`
default를 사용한다. 애플리케이션 JVM clock은 두 감사 시각을 생성하거나
갱신하지 않는다.

timestamp별 생성 정책은 다음과 같다.

| 감사 timestamp | 생성·갱신 정책 |
| --- | --- |
| `created_at` | 최초 INSERT 트랜잭션의 PostgreSQL transaction timestamp, 이후 불변 |
| `updated_at` | INSERT와 이후 UPDATE 트랜잭션의 PostgreSQL transaction timestamp |

PostgreSQL의 `CURRENT_TIMESTAMP`는 같은 트랜잭션에서 동일한 transaction
timestamp를 반환한다. 따라서 신규 거래의 `created_at`과 `updated_at`은
같고, 이후 수정 트랜잭션에서는 `created_at`을 유지하면서 `updated_at`만
해당 수정 트랜잭션의 DB timestamp로 갱신한다.

V1의 무정밀도 지정 `TIMESTAMPTZ`는 PostgreSQL 기본 마이크로초 정밀도
(`datetime_precision = 6`)를 사용한다. JPA의 `Instant`도 DB에서 생성해
다시 읽은 이 정밀도 값을 사용한다.

`occurred_at`은 외부에서 전달된 거래 발생 업무 시각이므로 감사 시각 생성
정책의 대상이 아니다. 애플리케이션 Clock 기준 미래 5분 Validation과
`occurred_at <= created_at + INTERVAL '5 minutes'` DB 방어 제약도 유지한다.

### 7.3 제약조건

| 제약 이름 | 종류 | 조건 |
| --- | --- | --- |
| `pk_financial_transaction` | PK | `id` |
| `uq_financial_transaction_transaction_id` | Unique | `transaction_id` |
| `ck_financial_transaction_uuid_v4` | Check | UUID version 4와 RFC 4122 variant |
| `ck_financial_transaction_type` | Check | 승인된 네 거래 유형 |
| `ck_financial_transaction_amount` | Check | `amount > 0 AND amount = trunc(amount)` |
| `ck_financial_transaction_currency` | Check | `currency_code = 'KRW'` |
| `ck_financial_transaction_occurred_at` | Check | `occurred_at <= created_at + 5 minutes` |
| `ck_financial_transaction_refs` | Check | 필수 참조값 1~128자, 앞뒤 공백 금지 |
| `ck_financial_transaction_type_contract` | Check | 거래 유형별 수취 계좌와 채널 조합 |
| `ck_financial_transaction_device_ref` | Check | null 또는 1~128자, 앞뒤 공백 금지 |
| `ck_financial_transaction_processing_status` | Check | 영속 상태 허용 목록 |
| `ck_financial_transaction_version` | Check | `version >= 0` |
| `ck_financial_transaction_timestamps` | Check | `updated_at >= created_at` |

## 8. `idempotency_record` 테이블

### 8.1 컬럼

| 컬럼 | PostgreSQL 타입 | Null | Default | 제약·의미 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` Identity | NOT NULL | Identity | 내부 PK |
| `operation_scope` | `VARCHAR(64)` | NOT NULL | 없음 | 멱등 작업 범위 |
| `idempotency_key` | `VARCHAR(128)` | NOT NULL | 없음 | 원본 `Idempotency-Key` |
| `request_fingerprint` | `VARCHAR(64)` | NOT NULL | 없음 | SHA-256 소문자 16진수 |
| `processing_status` | `VARCHAR(16)` | NOT NULL | 없음 | `IN_PROGRESS`, `COMPLETED`, `FAILED` |
| `financial_transaction_id` | `BIGINT` | nullable | 없음 | 처리 중에는 null 가능, 거래 결과 FK |
| `response_snapshot` | `JSONB` | nullable | 없음 | strict legacy 일곱 필드 또는 신규 version envelope JSON object, `traceId`와 내부·요청 정보 제외 |
| `failure_code` | `VARCHAR(64)` | nullable | 없음 | `FAILED`의 안전한 내부 실패 분류. 공개 응답은 whitelist로만 고정 매핑 |
| `expires_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP + INTERVAL '24 hours'` | 멱등 만료 시각 |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | 최초 선점 시각 |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | 마지막 상태 변경 시각 |
| `finished_at` | `TIMESTAMPTZ` | nullable | 없음 | 완료 또는 실패 확정 시각 |

### 8.2 제약조건

| 제약 이름 | 종류 | 조건 |
| --- | --- | --- |
| `pk_idempotency_record` | PK | `id` |
| `uq_idempotency_record_scope_key` | Unique | `operation_scope, idempotency_key` |
| `uq_idempotency_record_transaction` | Unique | `financial_transaction_id` |
| `fk_idempotency_record_transaction` | FK | `financial_transaction_id → financial_transaction.id`, `ON DELETE RESTRICT` |
| `ck_idempotency_record_scope` | Check | 작업 범위 1~64자, 앞뒤 공백 금지 |
| `ck_idempotency_record_key_length` | Check | 키 길이 8~128자 |
| `ck_idempotency_record_key_characters` | Check | `[A-Za-z0-9._:-]`만 허용 |
| `ck_idempotency_record_fingerprint` | Check | 소문자 16진수 64자 |
| `ck_idempotency_record_status` | Check | 세 처리 상태 허용 |
| `ck_idempotency_record_state_fields` | Check | 상태별 FK·응답·실패·종료 시각 조합 |
| `ck_idempotency_record_expiration` | Check | `expires_at = created_at + 24 hours` |
| `ck_idempotency_record_timestamps` | Check | 변경·종료 시각이 생성 시각보다 빠르지 않음 |

`financial_transaction_id` Unique는 하나의 거래 결과가 서로 다른 멱등 기록의 완료 결과로 중복 연결되는 것을 막는다. 다른 키로 같은 `transactionId`가 요청되면 `financial_transaction.transaction_id` Unique가 최종 방어선이며 애플리케이션은 `DUPLICATE_TRANSACTION`으로 매핑한다.

## 9. PostgreSQL 기준 DDL

다음 DDL은 승인된 물리 계약을 정확히 표현하는 기준이다. 실제 실행은 이 내용을 반영한 Flyway Migration으로만 수행한다.

```sql
CREATE TABLE financial_transaction (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    transaction_id UUID NOT NULL,
    transaction_type VARCHAR(32) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    external_customer_ref VARCHAR(128) NOT NULL,
    sender_account_ref VARCHAR(128) NOT NULL,
    recipient_account_ref VARCHAR(128),
    channel VARCHAR(32) NOT NULL,
    device_ref VARCHAR(128),
    processing_status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_financial_transaction
        PRIMARY KEY (id),
    CONSTRAINT uq_financial_transaction_transaction_id
        UNIQUE (transaction_id),
    CONSTRAINT ck_financial_transaction_uuid_v4
        CHECK (
            get_byte(uuid_send(transaction_id), 6) BETWEEN 64 AND 79
            AND get_byte(uuid_send(transaction_id), 8) BETWEEN 128 AND 191
        ),
    CONSTRAINT ck_financial_transaction_type
        CHECK (
            transaction_type IN (
                'ACCOUNT_TRANSFER',
                'OPEN_BANKING_TRANSFER',
                'ATM_WITHDRAWAL',
                'LOAN_DISBURSED'
            )
        ),
    CONSTRAINT ck_financial_transaction_amount
        CHECK (amount > 0 AND amount = trunc(amount)),
    CONSTRAINT ck_financial_transaction_currency
        CHECK (currency_code = 'KRW'),
    CONSTRAINT ck_financial_transaction_occurred_at
        CHECK (occurred_at <= created_at + INTERVAL '5 minutes'),
    CONSTRAINT ck_financial_transaction_refs
        CHECK (
            char_length(external_customer_ref) BETWEEN 1 AND 128
            AND external_customer_ref = btrim(external_customer_ref)
            AND char_length(sender_account_ref) BETWEEN 1 AND 128
            AND sender_account_ref = btrim(sender_account_ref)
        ),
    CONSTRAINT ck_financial_transaction_type_contract
        CHECK (
            (
                transaction_type = 'ACCOUNT_TRANSFER'
                AND recipient_account_ref IS NOT NULL
                AND char_length(recipient_account_ref) BETWEEN 1 AND 128
                AND recipient_account_ref = btrim(recipient_account_ref)
                AND channel = 'MOBILE_BANKING'
            )
            OR (
                transaction_type = 'OPEN_BANKING_TRANSFER'
                AND recipient_account_ref IS NOT NULL
                AND char_length(recipient_account_ref) BETWEEN 1 AND 128
                AND recipient_account_ref = btrim(recipient_account_ref)
                AND channel = 'OPEN_BANKING'
            )
            OR (
                transaction_type = 'ATM_WITHDRAWAL'
                AND recipient_account_ref IS NULL
                AND channel = 'ATM'
            )
            OR (
                transaction_type = 'LOAN_DISBURSED'
                AND recipient_account_ref IS NULL
                AND channel = 'CORE_BANKING'
            )
        ),
    CONSTRAINT ck_financial_transaction_device_ref
        CHECK (
            device_ref IS NULL
            OR (
                char_length(device_ref) BETWEEN 1 AND 128
                AND device_ref = btrim(device_ref)
            )
        ),
    CONSTRAINT ck_financial_transaction_processing_status
        CHECK (
            processing_status IN (
                'RECEIVED',
                'ANALYZING',
                'ANALYZED',
                'APPROVED',
                'ADDITIONAL_AUTH_REQUIRED',
                'HELD',
                'FAILED'
            )
        ),
    CONSTRAINT ck_financial_transaction_version
        CHECK (version >= 0),
    CONSTRAINT ck_financial_transaction_timestamps
        CHECK (updated_at >= created_at)
);

CREATE TABLE idempotency_record (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    operation_scope VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    processing_status VARCHAR(16) NOT NULL,
    financial_transaction_id BIGINT,
    response_snapshot JSONB,
    failure_code VARCHAR(64),
    expires_at TIMESTAMPTZ NOT NULL
        DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,

    CONSTRAINT pk_idempotency_record
        PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_record_scope_key
        UNIQUE (operation_scope, idempotency_key),
    CONSTRAINT uq_idempotency_record_transaction
        UNIQUE (financial_transaction_id),
    CONSTRAINT fk_idempotency_record_transaction
        FOREIGN KEY (financial_transaction_id)
        REFERENCES financial_transaction (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_idempotency_record_scope
        CHECK (
            char_length(operation_scope) BETWEEN 1 AND 64
            AND operation_scope = btrim(operation_scope)
        ),
    CONSTRAINT ck_idempotency_record_key_length
        CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    CONSTRAINT ck_idempotency_record_key_characters
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]+$'),
    CONSTRAINT ck_idempotency_record_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_record_status
        CHECK (
            processing_status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')
        ),
    CONSTRAINT ck_idempotency_record_state_fields
        CHECK (
            (
                processing_status = 'IN_PROGRESS'
                AND response_snapshot IS NULL
                AND failure_code IS NULL
                AND finished_at IS NULL
            )
            OR (
                processing_status = 'COMPLETED'
                AND financial_transaction_id IS NOT NULL
                AND response_snapshot IS NOT NULL
                AND jsonb_typeof(response_snapshot) = 'object'
                AND failure_code IS NULL
                AND finished_at IS NOT NULL
            )
            OR (
                processing_status = 'FAILED'
                AND response_snapshot IS NULL
                AND failure_code IS NOT NULL
                AND char_length(failure_code) BETWEEN 1 AND 64
                AND failure_code = btrim(failure_code)
                AND finished_at IS NOT NULL
            )
        ),
    CONSTRAINT ck_idempotency_record_expiration
        CHECK (expires_at = created_at + INTERVAL '24 hours'),
    CONSTRAINT ck_idempotency_record_timestamps
        CHECK (
            updated_at >= created_at
            AND (finished_at IS NULL OR finished_at >= created_at)
        )
);
```

## 10. 인덱스

PK와 Unique Constraint가 만드는 인덱스를 중복 생성하지 않는다.

### 10.1 Constraint가 제공하는 인덱스

| 테이블 | 인덱스 역할 | 제공 제약 |
| --- | --- | --- |
| `financial_transaction` | 내부 PK 조회 | `pk_financial_transaction` |
| `financial_transaction` | `transactionId` 단건 조회·중복 방지 | `uq_financial_transaction_transaction_id` |
| `idempotency_record` | `operationScope + idempotencyKey` 선점·조회 | `uq_idempotency_record_scope_key` |
| `idempotency_record` | 거래 결과의 멱등 기록 중복 연결 방지 | `uq_idempotency_record_transaction` |

### 10.2 명시적 보조 인덱스

```sql
CREATE INDEX ix_financial_transaction_occurred_at
    ON financial_transaction (occurred_at DESC, id DESC);

CREATE INDEX ix_financial_transaction_type_occurred_at
    ON financial_transaction (transaction_type, occurred_at DESC, id DESC);

CREATE INDEX ix_financial_transaction_status_occurred_at
    ON financial_transaction (processing_status, occurred_at DESC, id DESC);

CREATE INDEX ix_financial_transaction_customer_occurred_at
    ON financial_transaction (
        external_customer_ref,
        occurred_at DESC,
        id DESC
    );

CREATE INDEX ix_financial_transaction_sender_occurred_at
    ON financial_transaction (
        sender_account_ref,
        occurred_at DESC,
        id DESC
    );

CREATE INDEX ix_financial_transaction_recipient_occurred_at
    ON financial_transaction (
        recipient_account_ref,
        occurred_at DESC,
        id DESC
    )
    WHERE recipient_account_ref IS NOT NULL;

CREATE INDEX ix_idempotency_record_expires_at
    ON idempotency_record (expires_at);

CREATE INDEX ix_idempotency_record_status_updated_at
    ON idempotency_record (processing_status, updated_at);
```

거래 인덱스는 거래 API의 발생 시각 정렬, 유형·상태 필터, 고객·발신·수신 계좌별 최근 거래 조회를 지원한다. `recipient_account_ref`는 두 거래 유형에서만 존재하므로 Partial Index를 사용한다.

위험 등급 인덱스는 이번 테이블에 위험 등급 컬럼을 선행 추가하지 않으므로 탐지 스키마 Migration에서 함께 정의한다. 실제 데이터 분포와 `EXPLAIN (ANALYZE, BUFFERS)` 결과 없이 추가 복합 인덱스를 늘리지 않는다.

## 11. 동시성과 트랜잭션 경계

### 11.1 거래 낙관적 잠금

- `financial_transaction.version`을 JPA `@Version`에 대응하는 낙관적 잠금 컬럼으로 사용한다.
- 최초 값은 0이다.
- 상태 또는 그 밖의 가변 업무값을 갱신할 때 이전 version 일치를 `UPDATE` 조건으로 확인하고 성공한 변경에서 version을 증가시킨다.
- version 불일치는 기존 값을 덮어쓰지 않고 `409 Conflict`와 `CONCURRENT_MODIFICATION`으로 매핑한다.
- `version`은 탐지 결과 버전이나 업무 이력 버전이 아니다.
- `created_at`은 변경하지 않고 모든 성공 갱신에서 `updated_at`을 PostgreSQL 수정 트랜잭션 시각으로 갱신한다.

### 11.2 멱등 요청 선점

- `idempotency_record`에는 낙관적 잠금용 version을 두지 않는다.
- 최초 선점은 `(operation_scope, idempotency_key)` Unique Insert로 원자화한다.
- Unique 충돌이 발생하면 기존 행을 조회해 지문, 만료 시각과 처리 상태를 판별한다.
- 상태 확정은 현재 `processing_status = 'IN_PROGRESS'`를 `UPDATE` 조건에 포함해 하나의 실행만 `COMPLETED` 또는 `FAILED`로 변경하게 한다.
- `IN_PROGRESS` 선점 DB 트랜잭션을 External Risk나 FastAPI 네트워크 호출 동안 열어 두지 않는다.
- `financial_transaction` 생성, 해당 멱등 기록의 `financial_transaction_id` 연결, typed snapshot 저장과 `COMPLETED` 전이는 하나의 짧은 업무 트랜잭션 경계에서 처리한다. 어느 단계든 실패하면 거래 저장과 `COMPLETED` 전이를 함께 롤백한 뒤 별도 짧은 트랜잭션으로 선점 기록을 `FAILED`로 확정한다.
- 후속 분석과 최종 완료 응답 확정은 ADR-003의 단계적 구현 순서를 따르며, 전체 흐름 준비 전 불완전한 외부 Controller를 공개하지 않는다.

## 12. JPA 매핑 기준

현재 Java 구현은 다음 매핑을 기준으로 한다.

| PostgreSQL | Java/JPA 후보 | 주의사항 |
| --- | --- | --- |
| Identity `BIGINT` | `Long`, `GenerationType.IDENTITY` | API 비노출 |
| `UUID` | `java.util.UUID` | 문자열 ID로 중복 보관하지 않음 |
| `NUMERIC(19,4)` | `BigDecimal` | `double`을 거치지 않음 |
| `TIMESTAMPTZ` | `Instant` 우선 | API에서는 UTC `Z` 표기 |
| 상태·유형·채널 문자열 | API 계약 값의 `STRING` 매핑 | Java Enum 사용 여부는 후속 구현 승인에서 결정하고 DB 허용값과 정확히 일치 |
| `version BIGINT` | `Long` 또는 `long` + `@Version` | 최초 0, 업무 버전과 분리 |
| `JSONB` | 승인된 구조화 응답 snapshot | 자유 형식 업무 입력 저장소로 사용하지 않음 |

Java 매핑 변경은 이 물리 DB 계약과 함께 검증한다.

## 13. Flyway 변경 원칙

- 운영·개발·테스트 스키마 변경은 Flyway Migration으로만 수행한다.
- Hibernate `ddl-auto=create`, `update` 또는 수동 운영 DB DDL로 스키마를 변경하지 않는다.
- Migration은 `financial_transaction`을 먼저 만들고 FK 참조자인 `idempotency_record`를 다음에 만든다.
- 테이블, 제약조건과 인덱스 이름은 이 문서의 이름을 사용한다.
- 이미 적용된 Migration 파일을 수정하지 않고 변경이 필요하면 새 버전 Migration을 추가한다.
- 로컬 H2나 다른 호환 DB 결과를 PostgreSQL 동작의 증거로 사용하지 않는다.
- 실제 Migration 경로와 버전 번호는 Flyway 의존성 도입·구성 작업에서 현재 백엔드 구조와 선행 버전을 확인한 뒤 확정한다.

## 14. Testcontainers 검증 계약

실제 Flyway Migration과 Java 구현 작업에서는 실제 PostgreSQL Testcontainers로 다음을 검증해야 한다.

### 14.1 Migration

- 빈 PostgreSQL 컨테이너에 모든 Flyway Migration이 순서대로 성공한다.
- 같은 Migration 집합을 검증할 때 checksum 오류나 미적용 변경이 없다.
- Hibernate 자동 DDL 없이 애플리케이션이 시작된다.

### 14.2 `financial_transaction`

- Identity PK가 생성되고 외부 응답에는 노출되지 않는다.
- 정상 UUID v4는 저장되고 version 1·3·5 UUID 및 잘못된 variant는 거부된다.
- 같은 `transaction_id` 두 건은 Unique 위반이다.
- 네 거래 유형 각각의 승인된 recipient/channel 조합은 저장된다.
- 필수 recipient 누락, 금지 recipient 입력과 잘못된 channel 조합은 Check 위반이다.
- 0, 음수와 소수 금액은 거부되고 양의 정수는 `NUMERIC(19,4)`로 정확히 저장된다.
- `KRW`는 저장되고 다른 통화는 거부된다.
- 애플리케이션 Clock 기준 4분 미래는 허용되고 6분 미래는 Validation에서 거부된다.
- DB에서는 `created_at`보다 4분 뒤인 `occurred_at`은 허용되고 6분 뒤인 값은 Check 위반이다.
- `created_at`, `updated_at`의 `datetime_precision`은 6이다.
- JPA 신규 저장의 두 감사 시각은 같은 트랜잭션의 PostgreSQL `CURRENT_TIMESTAMP`와 정확히 일치한다.
- 별도 JPA 수정 트랜잭션에서 `created_at`은 불변이고 `updated_at`은 해당 트랜잭션의 PostgreSQL `CURRENT_TIMESTAMP`와 정확히 일치한다.
- DB default로 직접 생성한 행을 JPA로 수정해도 `updated_at >= created_at`을 유지한다.
- 빈 값과 앞뒤 공백이 있는 필수 참조값은 거부된다.
- `VALIDATION_FAILED` 저장은 거부된다.
- `version` 기본값은 0이고 음수는 거부된다.
- 동시 상태 변경에서 한 version의 갱신만 성공하고 다른 갱신은 충돌로 처리된다.

### 14.3 `idempotency_record`

- 키 길이 8자와 128자는 허용되고 7자와 129자는 거부된다.
- 영문, 숫자, `.`, `_`, `:`, `-`는 허용되고 공백·한글·슬래시는 거부된다.
- 같은 `(operation_scope, idempotency_key)` 동시 Insert 중 하나만 성공한다.
- 같은 키가 다른 `operation_scope`에 사용될 수 있다.
- SHA-256 소문자 16진수 64자만 저장된다.
- 존재하지 않는 `financial_transaction_id`는 FK 위반이다.
- 한 거래를 두 멱등 완료 기록에 연결하면 Unique 위반이다.
- 상태별 FK, 응답 snapshot, 실패 코드와 종료 시각 조합이 Check로 검증된다.
- `expires_at`은 `created_at`의 정확히 24시간 후여야 한다.
- `expires_at` 인덱스로 만료 정리 대상 조회가 가능하다.

테스트는 예외 타입만 확인하지 않고 위반한 PostgreSQL constraint 이름 또는 SQLState를 함께 확인해 의도한 제약이 실제로 동작했는지 검증한다.

## 15. 관측과 비용 영향

- Validation 거절은 거래 테이블이나 멱등 테이블 행으로 집계하지 않는다.
- 요청 결과, 오류 코드와 처리 단계는 로그·트레이스에서 `traceId`로 연결한다.
- 멱등 결과 메트릭은 최소 `first_success`, `completed_replay`, `key_conflict`, `request_in_progress`, `duplicate_transaction`과 실패 후보를 저카디널리티 결과 레이블로 구분한다.
- 원본 `Idempotency-Key`, `transactionId`, 고객·계좌 참조값과 요청 지문은 메트릭 레이블로 사용하지 않는다.
- 이 스키마는 External Risk, FastAPI 또는 LLM 호출을 추가하지 않는다.
- 완료 재전송과 처리 중 충돌은 새 탐지·사건·AI 호출을 만들지 않으므로 중복 외부 호출과 비용 발생을 막아야 한다.

## 16. 남은 미확정 사항

이번 물리 계약 이후에도 다음 항목은 별도 승인과 구현 설계가 필요하다.

- 멱등 만료 레코드 정리 주기, batch 크기, 잠금과 장애 재시도 방식
- 탐지 실행·결과 검증·채택·위험 대응을 기존 거래 접수 흐름에 통합하는 방식
- 상태 변경 충돌 후 자동 재시도 여부
- 최종 상태 거래의 재분석·정정 이력 모델
- External Risk와 FastAPI Timeout 이후 재개·복구 정책
- 참조값 생성 주체, 추가 문자 제한, 암호화·마스킹과 보존 기간
- 실제 Flyway 의존성, Migration 버전과 Testcontainers 의존성 도입 승인

이번 계약에서 확정한 UUID v4, 거래 유형별 recipient/channel, 양의 정수 금액, KRW, 미래 5분, Validation 미저장, 낙관적 잠금, 멱등 키 형식·지문·상태 코드와 `expires_at = created_at + 24 hours` 저장 제약은 위 TBD에 포함하지 않는다. 실제 만료 시행과 보존 기간은 여전히 후속 결정이다.
