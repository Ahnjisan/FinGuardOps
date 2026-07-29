# 거래·행동·탐지 API

## 1. 문서 목적

이 문서는 FinGuardOps의 거래 접수·조회, 행동 이벤트 수집·조회와 탐지 결과 조회 REST API 계약을 정의한다.

이 계약은 이후 Spring Boot Controller, 요청·응답 DTO, Validation, Service, 테스트와 OpenAPI 구현의 기준이다. API 공통 표현, 금액, 페이지네이션, 멱등성, 오류 응답과 추적 원칙은 [`api-conventions.md`](./api-conventions.md)를 따른다.

## 2. 범위와 책임 경계

### 2.1 처리 흐름

```text
Client
→ Spring Boot 거래 접수
→ 입력 검증·멱등성 확인
→ External Risk 조회
→ FastAPI Rule·ML 분석
→ Spring Boot 결과 검증·저장·채택
→ Spring Boot 위험 대응 결정
→ 필요 시 사건 생성 또는 기존 사건 연결
→ Client 응답
```

### 2.2 Spring Boot 책임

- 거래와 행동 이벤트 요청을 검증한다.
- 거래 생성 요청의 멱등성과 `transactionId` 중복을 관리한다.
- 행동 이벤트의 `eventId` 중복을 관리한다.
- External Risk Mock을 조회하고 조회 상태를 관리한다.
- FastAPI 분석 호출을 오케스트레이션한다.
- FastAPI가 반환한 결과의 요청 연결, 완전성, 버전과 처리 가능 여부를 검증한다.
- DetectionResult와 DetectionEvidence를 저장한다.
- 사용할 DetectionResult를 채택하고 `adoptedDetectionResultId`로 식별한다.
- 채택 결과를 Transaction의 현재 `riskLevel`에 반영한다.
- 승인된 정책에 따라 `riskResponseOutcome`과 `processingStatus`를 결정한다.
- HIGH·CRITICAL 처리에서 사건 생성 또는 기존 사건 연결을 결정한다.
- 업무 결과와 감사·추적 식별자의 정합성을 관리한다.

### 2.3 FastAPI 책임

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
| `detectionResultId` | 개별 탐지 결과 업무 식별자 |
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

`riskResponseOutcome`은 위험 등급에 Spring Boot가 승인된 Mock 대응 정책을 적용한 결과이다. 값 후보는 다음과 같다.

```text
APPROVED
APPROVED_WITH_MONITORING
ADDITIONAL_AUTH_REQUIRED
HELD
```

값 이름은 API Enum 후보이며 최종 승인이 필요하다. 거래 상태, 위험 등급과 위험 대응 결과는 다음처럼 분리한다.

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

`analysisStatus`는 탐지 분석 요청·진행·완료·실패를 구분한다. 예시에서는 `COMPLETED`를 사용한다. 전체 값과 실패한 분석 시도의 버전 처리 방식은 후속 Spring Boot·FastAPI 계약에서 확정한다.

### 4.7 위험 점수 의미

- API는 승인된 통합 정책이 산출한 최종 `riskScore`를 반환한다.
- 각 DetectionEvidence는 해당 근거의 `scoreContribution`을 반환할 수 있다.
- 통합 정책이 확정되기 전까지 `ruleScore`와 `mlScore`를 필수 API 계약으로 확정하지 않는다.
- Rule·ML·External Risk·행동 근거의 점수 통합 공식은 후속 탐지 정책 설계에서 결정한다.
- `riskScore`가 개별 `scoreContribution`의 단순 합이라고 가정하지 않는다.
- `riskScore` 범위, 정밀도, 상한과 정규화 방식은 사용자 결정 사항이다.

## 5. 거래 생성

### 5.1 요청

```http
POST /api/v1/transactions
Content-Type: application/json
Idempotency-Key: <required>
```

`Idempotency-Key`는 필수이다. 길이는 8~128자이고 영문, 숫자, 마침표(`.`), 밑줄(`_`), 콜론(`:`), 하이픈(`-`)만 허용한다. 누락하거나 형식이 올바르지 않으면 Transaction과 멱등 기록을 생성하지 않고 `400 Bad Request`와 `VALIDATION_ERROR`를 반환한다. 작업 범위는 `POST:/api/v1/transactions`이고 멱등 기록은 최초 선점부터 24시간 보존한다.

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

거래 접수 성공 응답은 다음 여덟 필드를 정확히 포함한다.

| 필드 | 타입 | 현재 거래 접수 값 |
| --- | --- | --- |
| `transactionId` | string | 저장된 거래의 UUID v4 업무 식별자 |
| `processingStatus` | string | `RECEIVED` |
| `riskLevel` | string 또는 null | 탐지 미구현이므로 명시적 null |
| `riskResponseOutcome` | string 또는 null | 위험 대응 미구현이므로 명시적 null |
| `adoptedDetectionResultId` | string 또는 null | 채택 탐지 결과 미구현이므로 명시적 null |
| `caseId` | string 또는 null | 사건 처리 미구현이므로 명시적 null |
| `createdAt` | string | DB/JPA 저장 결과의 실제 생성 시각, UTC ISO-8601 |
| `traceId` | string | 현재 HTTP 요청의 추적 식별자 |

현재 접수 단계에서는 네 nullable 필드를 JSON에서 생략하지 않는다. `riskLevel`과 `riskResponseOutcome`의 비-null Enum은 후속 위험 탐지·대응 계약에서 확정하며, 이번 접수 응답에서는 nullable string으로 취급한다.

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
  "processingStatus": "RECEIVED",
  "riskLevel": null,
  "riskResponseOutcome": null,
  "adoptedDetectionResultId": null,
  "caseId": null,
  "createdAt": "2026-07-23T01:15:31Z",
  "traceId": "trace_demo_tx_0001"
}
```

이번 거래 접수 API의 `201 Created` 응답에는 `Location` 헤더를 적용하지 않는다. `X-Trace-Id`는 공통 Trace Filter가 설정하며 응답 body의 `traceId`와 같다.

### 5.7 멱등성과 중복

- 같은 `Idempotency-Key`와 같은 요청의 최초 처리가 완료되었으면 새 거래·탐지·사건을 생성하지 않고 `200 OK`로 기존 업무 결과를 반환한다. `transactionId`, `processingStatus`, 네 nullable 필드와 `createdAt`은 최초 응답과 같고, `traceId`만 현재 재전송 요청의 값이다.
- 같은 `Idempotency-Key`와 같은 요청의 최초 처리가 진행 중이면 새 처리를 시작하지 않고 `409 Conflict`와 `IDEMPOTENCY_REQUEST_IN_PROGRESS`를 반환한다.
- 같은 키에 다른 요청 내용이 오면 `409 Conflict`와 `IDEMPOTENCY_KEY_CONFLICT`를 반환한다.
- 다른 키로 같은 `transactionId`가 오면 `409 Conflict`와 `DUPLICATE_TRANSACTION`을 반환한다.
- 같은 `transactionId`에 다른 요청 내용이 오면 기존 거래를 덮어쓰거나 재분석으로 해석하지 않는다.
- 요청 지문은 정규화한 `transactionId`, `transactionType`, `amount`, `currencyCode`, `occurredAt`, `externalCustomerRef`, `senderAccountRef`, `recipientAccountRef`, `channel`, `deviceRef`를 고정 순서 JSON으로 직렬화한 뒤 SHA-256으로 계산한다.
- `traceId`, `Idempotency-Key`, 내부 PK, 생성·수정 시각, version, 처리 상태와 그 밖의 서버 생성 필드는 지문에서 제외한다.
- 요청 지문, `IN_PROGRESS`·`COMPLETED`·`FAILED` 상태, 완료 응답 snapshot과 24시간 만료의 물리 저장 기준은 [`../04-database/transaction-intake-schema.md`](../04-database/transaction-intake-schema.md)를 따른다.
- `eventId` 중복과 `DUPLICATE_EVENT`는 행동 이벤트 API의 책임이며 이 거래 접수 API 범위에 포함하지 않는다.

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

`FAILED`인 같은 키·같은 요청은 자동 재실행하지 않는다. 거래 접수 API가 기존 실패를 공개 응답으로 재현하는 whitelist는 다음과 같다.

| 저장된 `failureCode` | HTTP 상태 | 공개 오류 코드 | 고정 message |
| --- | --- | --- | --- |
| `DUPLICATE_TRANSACTION` | `409 Conflict` | `DUPLICATE_TRANSACTION` | `이미 존재하는 transactionId입니다.` |
| `DEPENDENCY_TIMEOUT` | `503 Service Unavailable` | `DEPENDENCY_TIMEOUT` | `탐지 서비스를 사용할 수 없습니다.` |

저장된 `failureCode`가 null, 빈 값, 알 수 없는 값 또는 내부 전용 값이면 `500 Internal Server Error`, `INTERNAL_ERROR`, `요청을 처리하는 중 오류가 발생했습니다.`로 축약한다. 원래 `failureCode` 문자열을 공개 code나 message로 전달하지 않는다. 현재 거래 저장 또는 멱등 완료의 예기치 않은 실패를 기록하는 내부 코드 `TRANSACTION_INTAKE_FAILED`도 공개 whitelist가 아니므로 `INTERNAL_ERROR`로 처리한다.

### 5.8 의존 서비스 Timeout

#### 5.8.1 External Risk Timeout

- 사용할 수 있는 유효 캐시가 있으면 캐시의 기준 시각과 유효성을 검증한 뒤 분석을 계속할 수 있다.
- 캐시 사용 여부, 캐시 기준 시각과 조회 Timeout은 ExternalRiskSnapshot에 기록한다.
- 유효 캐시가 없으면 조회 실패를 위험정보 없음 또는 안전으로 해석하지 않는다.
- 유효 캐시가 없는 초기 권장 정책은 Transaction을 `FAILED`로 기록하고 사건을 생성하지 않는 것이다.
- 클라이언트에는 `503 Service Unavailable`과 `DEPENDENCY_TIMEOUT`을 반환한다.

#### 5.8.2 FastAPI Timeout

- Spring Boot는 임의 위험 점수나 `LOW` 결과를 생성하지 않는다.
- 완료·검증된 DetectionResult가 없으므로 `adoptedDetectionResultId`를 설정하지 않는다.
- 초기 권장 정책은 Transaction을 `FAILED`로 기록하고 사건을 생성하지 않는 것이다.
- 클라이언트에는 `503 Service Unavailable`과 `DEPENDENCY_TIMEOUT`을 반환한다.

실패 Transaction 저장과 오류 응답은 일부만 성공한 것처럼 보이지 않도록 정합성 경계를 가져야 한다. 오류 응답에서 이미 저장된 실패 Transaction을 식별할 수 있도록 다음 공통 오류 문맥을 사용한다.

```http
HTTP/1.1 503 Service Unavailable
Content-Type: application/json
```

```json
{
  "code": "DEPENDENCY_TIMEOUT",
  "message": "탐지 서비스를 사용할 수 없습니다.",
  "traceId": "trace_demo_timeout_01",
  "fieldErrors": [],
  "resource": {
    "transactionId": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
    "processingStatus": "FAILED"
  }
}
```

`resource`는 오류와 함께 영속 리소스 문맥을 반환하기 위한 후보이다. 최종 필드명, 범용 구조와 다른 오류에 적용할 범위는 사용자 결정 사항이다. JSON 파싱이나 기본 형식 검증처럼 Transaction을 생성하지 않은 오류에는 이 문맥을 반환하지 않는다.

### 5.9 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `201 Created` | 거래가 처음 생성되고 승인된 동기 처리 결과를 반환함 |
| `200 OK` | 완료된 동일 멱등 요청에 기존 결과 반환 |
| `400 Bad Request` | 잘못된 JSON, 필수 헤더 누락 또는 필드 형식 오류 |
| `409 Conflict` | 멱등성 키 지문 충돌, 동일 멱등 요청 처리 중, `transactionId` 중복 또는 동시성 충돌 |
| `422 Unprocessable Entity` | 형식은 맞지만 거래 유형별 업무 규칙을 만족하지 못함 |
| `503 Service Unavailable` | 유효 캐시 없는 External Risk Timeout 또는 FastAPI Timeout의 초기 정책 |

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
| `analysisStartedAt` | 분석 시작 시각 |
| `analysisCompletedAt` | 분석 완료 시각. 미완료 시 null 가능 |

### 10.3 성공 응답 예시

```json
{
  "transactionId": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
  "content": [
    {
      "detectionResultId": "det_demo_20260723_0101",
      "detectionResultVersion": 1,
      "riskScore": 72.5,
      "riskLevel": "HIGH",
      "analysisStatus": "COMPLETED",
      "adopted": true,
      "modelVersion": "fraud-baseline-1.0",
      "featureVersion": "feature-set-1.0",
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

동일 `transactionId + detectionResultVersion`은 하나의 결과만 유지한다. Timeout 후 늦은 응답과 재시도 응답이 같은 버전을 각각 확정하지 않으며, 실제 새 분석은 새 버전을 사용한다.

### 10.4 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 탐지 결과 조회 성공. 결과가 없으면 빈 `content` 반환 |
| `400 Bad Request` | 식별자, 페이지 또는 정렬 형식 오류 |
| `404 Not Found` | 해당 `transactionId`의 거래가 없음 |
| `422 Unprocessable Entity` | 의미상 처리할 수 없는 페이지·정렬 조건 |
| `503 Service Unavailable` | 필수 저장소 등 조회 의존성이 일시적으로 사용 불가 |

## 11. 탐지 결과 상세 조회

### 11.1 요청

```http
GET /api/v1/detection-results/{detectionResultId}
```

요청 예:

```http
GET /api/v1/detection-results/det_demo_20260723_0101
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
    "detectionResultId": "det_demo_20260723_0101",
    "transactionId": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
    "detectionResultVersion": 1,
    "riskScore": 72.5,
    "riskLevel": "HIGH",
    "analysisStatus": "COMPLETED",
    "adopted": true,
    "modelVersion": "fraud-baseline-1.0",
    "featureVersion": "feature-set-1.0",
    "analysisStartedAt": "2026-07-23T01:15:30Z",
    "analysisCompletedAt": "2026-07-23T01:15:32Z"
  },
  "evidence": [
    {
      "evidenceId": "evidence_demo_rule_01",
      "evidenceType": "RULE",
      "reasonCode": "NEW_DEVICE_HIGH_AMOUNT",
      "displayDescription": "신규 기기 등록 후 고객 기준선보다 큰 금액의 이체가 요청되었습니다.",
      "scoreContribution": 30.0,
      "rule": {
        "ruleCode": "NEW_DEVICE_HIGH_AMOUNT",
        "ruleVersion": "1.0"
      },
      "observationSummary": {
        "newDevice": true,
        "amountRatioBand": "TEN_OR_MORE"
      },
      "evidenceOccurredAt": "2026-07-23T01:15:30Z"
    },
    {
      "evidenceId": "evidence_demo_external_01",
      "evidenceType": "EXTERNAL_RISK",
      "reasonCode": "EXTERNAL_RISK_MATCH",
      "displayDescription": "비식별 대상 참조값에 외부 위험 신호가 확인되었습니다.",
      "scoreContribution": 17.5,
      "externalRiskSnapshot": {
        "targetType": "RECIPIENT_ACCOUNT_REFERENCE",
        "matched": true,
        "riskType": "SUSPECTED_ACCOUNT",
        "providerAsOf": "2026-07-23T01:14:00Z",
        "lookupStatus": "SUCCESS",
        "cacheUsed": false,
        "fallbackUsed": false
      },
      "evidenceOccurredAt": "2026-07-23T01:15:31Z"
    },
    {
      "evidenceId": "evidence_demo_behavior_01",
      "evidenceType": "BEHAVIOR_PATTERN",
      "reasonCode": "RECENT_SECURITY_SEQUENCE",
      "displayDescription": "거래 직전에 기기 등록과 신규 수취인 등록이 연속으로 발생했습니다.",
      "scoreContribution": 10.0,
      "behaviorPatternSummary": {
        "eventTypes": [
          "DEVICE_REGISTERED",
          "BENEFICIARY_REGISTERED",
          "TRANSFER_REQUESTED"
        ],
        "eventCount": 3,
        "windowSeconds": 330
      },
      "evidenceOccurredAt": "2026-07-23T01:15:30Z"
    }
  ],
  "traceId": "trace_demo_detection_detail_01"
}
```

`observationSummary`, `externalRiskSnapshot`과 `behaviorPatternSummary`는 설명과 감사에 필요한 최소 요약이다. 자유 형식 원문 저장·반환을 허용하지 않고 유형별 허용 필드를 후속 DTO 계약에서 확정한다.

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
| `POST /transactions` | 완료된 동일 멱등 요청의 기존 결과 | 최초 생성 | JSON·필수 헤더·필드 형식 오류 | 사용하지 않음 | 멱등 키 지문 충돌·처리 중 동일 요청·거래·상태·동시성 충돌 | 거래 유형별 도메인 규칙 위반 | 유효 캐시 없는 External Risk Timeout 또는 FastAPI Timeout 초기 정책 | 예기치 않은 서버 오류 |
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
- 거래 생성 흐름의 `traceId`는 Spring Boot, External Risk Mock과 FastAPI 호출을 연결한다.
- 조회 API의 `traceId`는 해당 조회 요청을 추적하며 저장된 과거 분석의 `traceId`와 같을 필요가 없다.
- 탐지 결과에 저장된 분석 당시 추적값과 현재 조회 요청의 `traceId`를 함께 제공할 필요가 있으면 서로 다른 필드명과 의미로 구분한다.
- OpenTelemetry 전파 헤더의 구체적인 이름과 구현은 이 문서에서 확정하지 않는다.

## 15. 민감정보 처리

- 예시와 계약에는 실제 고객번호·계좌번호·IP 원문을 사용하지 않는다.
- `externalCustomerRef`, 계좌 참조값과 `deviceRef`는 실제 원문이 아닌 제한된 참조값이다.
- 행동 이벤트는 국가·지역·해외 여부와 위험 신호 같은 최소 요약을 사용한다.
- 거래 목록·상세에서 고객·계좌 원문을 반환하지 않는다.
- 탐지 근거에는 Feature 전체 벡터와 원문 행동 로그를 반환하지 않는다.
- ExternalRiskSnapshot에는 조회 상태, 일치 여부, Reason Code, 기준 시각, 캐시·fallback 여부만 최소 제공한다.
- Provider 응답 원문, 인증정보와 내부 예외 원문을 응답에 포함하지 않는다.

## 16. 사용자 결정 필요 항목

### 16.1 공통 계약

- `fieldErrors.code`의 코드 목록과 버전 관리 방식

### 16.2 거래

- `riskResponseOutcome` Enum 이름
- FastAPI·External Risk Timeout 이후 재시도·복구 정책
- 오류 응답의 `resource` 최종 이름과 범용 구조

### 16.3 행동 이벤트

- 후속 행동 이벤트 조회 API의 필터·응답·인덱스 계약
- 후속 Rule 입력에서 필요한 위치·관측 신호·유형별 상세의 제한된 필드 계약

### 16.4 탐지 결과

- `analysisStatus` 전체 값과 실패한 분석 시도 표현
- DetectionResult 버전 생성 규칙
- `riskScore` 범위·정밀도·상한·정규화 방식과 null 규칙
- Rule·ML·External Risk·행동 근거의 점수 통합 정책
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
