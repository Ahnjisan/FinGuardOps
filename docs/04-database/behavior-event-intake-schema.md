# 행동 이벤트 접수 PostgreSQL 물리 DB 계약

## 1. 문서 목적

이 문서는 `POST /api/v1/behavior-events`가 사용하는 `behavior_event` 테이블, Validation, 거래 연결, 자연 멱등성, 동시성, Flyway와 PostgreSQL 통합 테스트 계약을 정의한다.

행동 이벤트 접수만 범위에 포함한다. 행동 이벤트 조회, Rule·ML, 탐지 결과, 위험 대응, 사건, AI 리포트, FastAPI, External Risk, Redis와 Kafka는 포함하지 않는다.

## 2. 처리 경계

```text
엄격한 JSON·공통 형식 검증
→ 이벤트 유형별 도메인 Validation
→ 선택적 관련 거래 조회와 업무 정합성 검증
→ 8개 요청 필드 정규화·SHA-256 fingerprint 계산
→ event_id Unique 선점·행동 이벤트 저장
→ 최초 201 또는 기존 결과 200
```

- Validation 실패는 `behavior_event` 행을 만들지 않는다.
- 최초 저장과 거래 FK 연결은 한 DB 트랜잭션에서 처리한다.
- 별도 `Idempotency-Key`와 `idempotency_record`를 사용하지 않는다.
- 저장 실패 트랜잭션에서 `DataIntegrityViolationException`을 잡아 같은 트랜잭션으로 재조회하지 않는다.
- `event_id` Unique 위반 후 기존 행 재조회는 rollback된 저장 시도와 분리된 새 트랜잭션에서 수행한다.

## 3. 식별자와 참조값

- 내부 관계와 PK는 `BIGINT GENERATED ALWAYS AS IDENTITY`를 사용한다.
- REST `BehaviorEvent.eventId`는 호출자가 생성한 canonical UUID v4이며 PostgreSQL `UUID`로 저장한다.
- `event_id`는 version 4와 RFC 4122 variant 비트를 DB `CHECK`로 보조 검증한다.
- API는 내부 `id`와 `financial_transaction_id`를 받거나 반환하지 않는다.
- 향후 도메인 이벤트 Envelope의 `eventId`는 논리 이벤트 전달 식별자이고 REST `BehaviorEvent.eventId`는 행동 Aggregate 식별자이므로 서로 다른 경계이다.
- `external_customer_ref`, `account_ref`, `device_ref`, `beneficiary_ref`는 실제 원문이 아닌 외부 참조값이다.
- `account_ref`는 행동의 기준이 되는 고객 측 계좌이고 `beneficiary_ref`는 `BENEFICIARY_REGISTERED`에서 새로 등록된 수취인이다.

## 4. 요청 Validation 계약

### 4.1 요청 필드

| 순서 | 요청 필드 | 필수 | 저장 컬럼 |
| --- | --- | --- | --- |
| 1 | `eventId` | 공통 필수 | `event_id` |
| 2 | `eventType` | 공통 필수 | `event_type` |
| 3 | `occurredAt` | 공통 필수 | `occurred_at` |
| 4 | `externalCustomerRef` | 공통 필수 | `external_customer_ref` |
| 5 | `accountRef` | 조건부 | `account_ref` |
| 6 | `deviceRef` | 조건부 | `device_ref` |
| 7 | `transactionId` | 조건부 | `financial_transaction_id` FK로 변환 |
| 8 | `beneficiaryRef` | 조건부 | `beneficiary_ref` |

요청은 위 8개 필드만 허용한다. 알 수 없는 필드, 중복 JSON 키, string 또는 null 외 타입과 스칼라 강제 변환은 거부한다.

### 4.2 UUID

- `eventId`와 제공된 `transactionId`는 canonical 하이픈 UUID 문자열이어야 한다.
- UUID version은 4이고 variant는 RFC 4122여야 한다.
- API 형식 위반은 `400 Bad Request`와 `VALIDATION_ERROR`이다.

### 4.3 발생 시각

- `occurredAt`은 `Z` 접미사의 UTC ISO-8601만 허용한다.
- 같은 Instant를 나타내는 offset 형식도 거부한다.
- 정확히 Validation Clock보다 5분 미래는 허용한다.
- 5분을 초과하면 `422 Unprocessable Entity`와 `VALIDATION_ERROR`이다.
- PostgreSQL은 `occurred_at <= created_at + INTERVAL '5 minutes'`로 방어 검증한다.

### 4.4 참조값

- 제공된 참조값은 1~128자이다.
- 빈 문자열, 공백만 있는 값과 앞뒤 공백을 거부한다.
- 서버는 trim하거나 대소문자를 변경하지 않는다.
- 비교와 저장은 exact·case-sensitive이다.
- 선택 필드 누락과 명시적 null은 동일한 null로 정규화한다.

### 4.5 이벤트별 null 계약

| 이벤트 | `account_ref` | `device_ref` | `financial_transaction_id` | `beneficiary_ref` |
| --- | --- | --- | --- | --- |
| `LOGIN` | nullable | NOT NULL | nullable | NULL |
| `LOGIN_FAILED` | nullable | nullable | nullable | NULL |
| `DEVICE_REGISTERED` | nullable | NOT NULL | nullable | NULL |
| `PASSWORD_CHANGED` | nullable | nullable | nullable | NULL |
| `OTP_REISSUED` | nullable | nullable | nullable | NULL |
| `BENEFICIARY_REGISTERED` | NOT NULL | nullable | nullable | NOT NULL |
| `TRANSFER_LIMIT_CHANGED` | NOT NULL | nullable | nullable | NULL |
| `TRANSFER_REQUESTED` | NOT NULL | nullable | NOT NULL | NULL |
| `ATM_WITHDRAWAL_REQUESTED` | NOT NULL | nullable | NOT NULL | NULL |

애플리케이션은 같은 조건을 저장 전에 `422`로 검증하고 DB `CHECK`는 우회 저장을 방어한다.

## 5. 관련 거래 정합성

`transactionId`가 제공되면 `financial_transaction.transaction_id`로 거래를 조회하고 내부 `id`를 `financial_transaction_id` FK에 저장한다.

- 거래가 없으면 `404 RESOURCE_NOT_FOUND`이다.
- `externalCustomerRef`가 거래의 `external_customer_ref`와 일치해야 한다.
- 일반 이벤트에 `accountRef`가 있으면 거래의 `sender_account_ref` 또는 nullable `recipient_account_ref`와 일치해야 한다.
- `TRANSFER_REQUESTED`는 거래 유형이 `ACCOUNT_TRANSFER` 또는 `OPEN_BANKING_TRANSFER`이고 `accountRef`가 `sender_account_ref`와 일치해야 한다.
- `ATM_WITHDRAWAL_REQUESTED`는 거래 유형이 `ATM_WITHDRAWAL`이고 `accountRef`가 `sender_account_ref`와 일치해야 한다.
- 거래는 존재하지만 정합성이 맞지 않으면 `422 VALIDATION_ERROR`이다.

고객·계좌·거래 유형의 교차 행 조건은 단일 `behavior_event` Check로 표현하지 않고 Spring Boot Service가 저장 트랜잭션 전에 검증한다. FK는 거래 존재와 삭제 제한을 보장한다.

## 6. fingerprint 계약

### 6.1 포함 필드와 순서

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

### 6.2 정규화

1. 검증된 필드를 위 순서의 JSON object로 직렬화한다.
2. `eventId`와 `transactionId`는 `UUID.toString()` canonical lowercase 형식을 사용한다.
3. `eventType`은 승인된 Enum 이름을 사용한다.
4. `occurredAt`은 UTC Instant ISO-8601 `Z` 형식을 사용한다.
5. 참조값은 검증된 원문을 그대로 사용한다.
6. nullable 필드는 누락과 명시적 null 모두 JSON null로 기록한다.
7. 고정 키와 JSON string/null 타입을 사용하므로 필드 경계와 null이 모호하지 않다.
8. 공백 없는 UTF-8 JSON byte sequence를 사용한다.

예:

```json
{"eventId":"e54cbf7e-d857-4ca0-bff3-8d4321b7722a","eventType":"BENEFICIARY_REGISTERED","occurredAt":"2026-07-29T04:10:00Z","externalCustomerRef":"cust_ref_demo_a7f2","accountRef":"acct_ref_demo_s91c","deviceRef":"device_ref_demo_18b3","transactionId":null,"beneficiaryRef":"acct_ref_demo_r82a"}
```

정규화 JSON의 SHA-256을 소문자 16진수 64자로 `request_fingerprint`에 저장한다. `traceId`, 내부 PK·FK와 `createdAt`은 제외한다.

## 7. 자연 멱등성과 동시성

| 상황 | 결과 |
| --- | --- |
| 새 `eventId` 최초 저장 | `201 Created`, 행 한 건 생성 |
| 같은 `eventId` + 같은 fingerprint | `200 OK`, 기존 행 반환 |
| 같은 `eventId` + 다른 fingerprint | `409 DUPLICATE_EVENT`, 기존 행 불변 |
| 다른 `eventId`의 유사 이벤트 | 별도 행 저장 |

순차 재전송은 저장 전에 기존 `event_id`를 조회할 수 있다. 동시 요청은 `uq_behavior_event_event_id`가 한 Insert만 허용한다. Unique 위반 패자는 저장 트랜잭션이 rollback된 뒤 별도 읽기 트랜잭션에서 기존 행을 재조회해 fingerprint를 비교한다. 기존 행을 찾을 수 없거나 저장 데이터가 모순되면 공개 상세 없이 `500 INTERNAL_ERROR`로 처리한다.

별도 response snapshot은 만들지 않는다. 응답은 불변 `behavior_event` 행에서 재구성하고 현재 요청의 `traceId`를 결합한다.

## 8. `behavior_event` 테이블

### 8.1 컬럼

| 컬럼 | PostgreSQL 타입 | Null | Default | 의미 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` Identity | NOT NULL | Identity | 내부 PK |
| `event_id` | `UUID` | NOT NULL | 없음 | UUID v4 업무 ID |
| `event_type` | `VARCHAR(32)` | NOT NULL | 없음 | 9개 행동 이벤트 |
| `occurred_at` | `TIMESTAMPTZ` | NOT NULL | 없음 | 실제 발생 시각 |
| `external_customer_ref` | `VARCHAR(128)` | NOT NULL | 없음 | 외부 고객 참조 |
| `account_ref` | `VARCHAR(128)` | nullable | 없음 | 고객 측 기준 계좌 |
| `device_ref` | `VARCHAR(128)` | nullable | 없음 | 외부 기기 참조 |
| `beneficiary_ref` | `VARCHAR(128)` | nullable | 없음 | 신규 수취인 참조 |
| `financial_transaction_id` | `BIGINT` | nullable | 없음 | 거래 내부 FK |
| `request_fingerprint` | `VARCHAR(64)` | NOT NULL | 없음 | SHA-256 |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | 저장 시각 |

### 8.2 제약조건

| 이름 | 종류 | 조건 |
| --- | --- | --- |
| `pk_behavior_event` | PK | `id` |
| `uq_behavior_event_event_id` | Unique | `event_id` |
| `fk_behavior_event_transaction` | FK | `financial_transaction_id → financial_transaction.id`, `ON DELETE RESTRICT` |
| `ck_behavior_event_uuid_v4` | Check | UUID v4와 RFC 4122 variant |
| `ck_behavior_event_type` | Check | 승인된 9개 Enum |
| `ck_behavior_event_external_customer_ref` | Check | 1~128자, 앞뒤 공백 금지 |
| `ck_behavior_event_account_ref` | Check | null 또는 1~128자, 앞뒤 공백 금지 |
| `ck_behavior_event_device_ref` | Check | null 또는 1~128자, 앞뒤 공백 금지 |
| `ck_behavior_event_beneficiary_ref` | Check | null 또는 1~128자, 앞뒤 공백 금지 |
| `ck_behavior_event_type_fields` | Check | 4.5절 유형별 null 계약 |
| `ck_behavior_event_occurred_at` | Check | `occurred_at <= created_at + 5 minutes` |
| `ck_behavior_event_fingerprint` | Check | 소문자 16진수 64자 |

## 9. 인덱스

- `uq_behavior_event_event_id`가 `event_id` Unique 인덱스를 제공한다.
- nullable FK 조회를 위해 `ix_behavior_event_transaction`을 `financial_transaction_id`에 부분 인덱스로 추가한다.
- 고객·계좌·기기·이벤트 유형과 시각의 추측성 복합 인덱스는 추가하지 않는다. 후속 Rule·조회 API의 실제 쿼리와 실행 계획을 근거로 결정한다.

## 10. Flyway와 JPA

- 기존 `V1__create_transaction_persistence_tables.sql`은 수정하지 않는다.
- `V2__create_behavior_event_table.sql`에서 `behavior_event`를 추가한다.
- Hibernate `ddl-auto=validate`가 Entity와 V1→V2 결과의 타입·길이·nullability를 검증해야 한다.
- `BehaviorEvent`는 거래를 nullable `ManyToOne`으로 참조하되 API에는 내부 관계를 노출하지 않는다.
- Entity를 Controller 응답으로 직접 반환하지 않고 전용 Response DTO와 Mapper를 사용한다.

## 11. 오류와 보안

- 명확한 PostgreSQL statement/query timeout만 `503 DEPENDENCY_TIMEOUT`으로 분류한다.
- 연결 실패 또는 명확한 일시적 저장소 장애만 `503 DEPENDENCY_UNAVAILABLE`로 분류한다.
- 그 밖의 `DataAccessException`, 내부 모순과 예상하지 못한 오류는 `500 INTERNAL_ERROR`이다.
- SQL, DB·드라이버 원문, 테이블·컬럼·제약 이름, 스택 트레이스, 참조값, 내부 PK와 fingerprint를 공개 응답에 포함하지 않는다.
- 정상·오류·재전송 응답은 기존 `TraceIdFilter`가 정한 현재 HTTP 요청의 `traceId`를 사용한다.

## 12. 통합 테스트 계약

- 빈 PostgreSQL에 V1→V2가 순서대로 적용된다.
- Hibernate 자동 DDL 없이 애플리케이션이 시작된다.
- 컬럼 타입·길이·nullability, named constraint와 최소 인덱스를 검증한다.
- UUID v4·variant, 9개 Enum, 참조값, 유형별 null, 미래 5분, fingerprint Check를 검증한다.
- 거래 FK와 `ON DELETE RESTRICT`, `event_id` Unique를 검증한다.
- 최초 접수, 동일 재전송, 다른 fingerprint 충돌, 다른 eventId, 거래 연결과 실제 동시 요청을 검증한다.
- Unique 위반 저장 트랜잭션과 중복 재조회 트랜잭션이 분리되어 rollback-only 상태를 재사용하지 않는지 검증한다.

## 13. 제외 범위

- 행동 이벤트 조회 API와 이를 위한 복합 인덱스
- Rule·ML·탐지·위험 대응·사건·AI 리포트
- FastAPI·External Risk·Redis·Kafka
- 인증·인가·CORS·OpenTelemetry
- `locationRiskSummary`, `observedSignals`, 자유 형식 `eventDetails`
- `idempotency_record` 변경과 response snapshot

