# FinGuardOps API 공통 규칙

## 1. 문서 목적

이 문서는 FinGuardOps의 Spring Boot REST API가 공통으로 따를 표현 형식, 식별자, 금액, 페이지네이션, 멱등성, 오류 응답과 추적 원칙을 정의한다.

이 문서는 이후 Controller, 요청·응답 DTO, Validation, Service, 테스트와 OpenAPI 계약의 기준이다. Java 타입, DB 컬럼과 OpenTelemetry 전파 헤더는 이 문서에서 확정하지 않는다. 인증·인가의 목표 API 계약은 [`security-architecture.md`](../02-architecture/security-architecture.md)와 [`ADR-008`](../07-decisions/ADR-008-oauth2-resource-server-rbac-user-audit-actor.md)을 따른다. 현재 Spring Security·JWT·RBAC는 구현되지 않았다.

## 2. 기본 경로

신규 업무 API의 기본 경로는 다음과 같다.

```text
/api/v1
```

예:

```text
POST /api/v1/transactions
GET  /api/v1/behavior-events
```

기존 Health Check API인 `/api/health`는 현재 계약을 유지한다. 이 문서는 기존 경로를 소급 변경하지 않는다.

## 3. 표현 형식

### 3.1 미디어 타입과 문자 인코딩

- 요청과 응답 본문은 JSON을 사용한다.
- 문자 인코딩은 UTF-8을 사용한다.
- JSON 본문을 보내는 요청은 `Content-Type: application/json`을 사용한다.
- 클라이언트는 JSON 응답을 받을 수 있도록 `Accept: application/json`을 사용할 수 있다.
- JSON 필드명은 `camelCase`를 사용한다.
- 정의되지 않은 필드를 자동으로 저장하거나 외부 서비스에 그대로 전달하지 않는다.

### 3.2 시간

- 시간은 ISO-8601 형식으로 표현한다.
- 저장과 서비스 간 전달은 UTC를 원칙으로 한다.
- UTC 시각은 `Z` 접미사를 포함해 표현한다.
- 발생 시각과 저장 시각을 구분한다.
- 거래 생성 요청의 `occurredAt`은 Validation 시점의 서버 시각보다 최대 5분 미래까지 허용한다.
- 행동 이벤트 생성 요청의 `occurredAt`도 Validation 시점의 서버 시각보다 최대 5분 미래까지 허용한다. 정확히 5분은 허용하고 이를 초과하면 `422 Unprocessable Entity`와 `VALIDATION_ERROR`를 반환한다.

예:

```json
{
  "occurredAt": "2026-07-23T01:15:30Z",
  "createdAt": "2026-07-23T01:15:31Z"
}
```

클라이언트의 로컬 시간대 표시는 화면 책임이며 API의 원본 시각을 덮어쓰지 않는다. 거래 발생 시각의 5분 미래 허용 범위는 [`../04-database/transaction-intake-schema.md`](../04-database/transaction-intake-schema.md)를, 행동 이벤트 발생 시각은 [`../04-database/behavior-event-intake-schema.md`](../04-database/behavior-event-intake-schema.md)를 따른다. offset 표기가 같은 순간을 나타내더라도 `Z` 접미사가 아니면 거부한다.

### 3.3 내부 식별자와 업무 식별자

DB 관계와 저장에 사용하는 내부 식별자와 API·로그·업무 조회에 사용하는 업무 식별자를 구분한다.

| 식별자 | 의미 |
| --- | --- |
| 내부 DB 식별자 | Entity 관계와 DB 연결에 사용하는 내부 값. 기본적으로 API에 노출하지 않는다. |
| `transactionId` | 거래 접수·조회와 관련 탐지·사건 연결에 사용하는 거래 업무 식별자 |
| `eventId` | REST 행동 이벤트의 중복 수신과 조회에 사용하는 호출자 생성 UUID v4 업무 식별자 |
| `detectionResultId` | 저장·검증된 개별 탐지 결과를 외부 계약에서 식별하는 업무 식별자 |
| `caseId` | 생성되었거나 연결된 사건을 식별하는 업무 식별자 |
| `traceId` | Spring Boot와 의존 서비스 호출 흐름을 연결하는 추적 식별자 |

각 식별자는 서로 대체할 수 없다. 특히 `traceId`는 거래나 탐지 결과의 업무 식별자가 아니며, `eventId`는 이 문서 범위에서 행동 이벤트 식별자를 뜻한다.

`transactionId`는 거래 생성 요청에서 클라이언트가 전달하는 UUID v4이며 PostgreSQL 내부 Identity PK와 구분한다. REST `BehaviorEvent.eventId`도 호출자가 생성한 canonical UUID v4이고 내부 Identity PK와 구분한다. 두 UUID는 version 4와 RFC 4122 variant를 검증한다. 향후 도메인 이벤트 Envelope도 `eventId`라는 이름을 사용할 수 있지만, Envelope 식별자는 전달·재처리되는 논리 도메인 이벤트를 식별하고 REST `BehaviorEvent.eventId`는 수집된 사용자 행동 Aggregate를 식별하므로 서로 다른 경계의 식별자이다. 식별자 자체에 실제 고객번호, 계좌번호, 인증정보와 같은 민감정보를 포함하지 않는다.

### 3.4 민감정보

- 실제 고객번호와 실제 계좌번호 원문을 요청·응답·오류·로그에 노출하지 않는다.
- 고객, 계좌와 기기는 의미가 제한된 외부 참조값을 사용한다.
- 원문 IP 대신 국가·지역·해외 접속 여부·위험 여부 등 필요한 최소 요약을 우선 사용한다.
- 비밀번호, OTP, 인증 토큰, API Key와 Provider 응답 원문을 받거나 반환하지 않는다.
- Feature 전체 벡터, External Risk Provider 원문 응답과 불필요한 행동 상세를 반환하지 않는다.
- 오류의 `message`, `fieldErrors.reason`과 `traceId`에 민감정보를 포함하지 않는다.

참조값도 접근 범위에 따라 민감할 수 있으므로 로그·메트릭 레이블에 무분별하게 기록하지 않는다. 구체적인 마스킹, 해시, 암호화와 접근 제어는 후속 보안 설계에서 확정한다.

## 4. 금액 표현

거래 금액은 통화와 함께 전달한다.

```json
{
  "amount": "1250000",
  "currencyCode": "KRW"
}
```

금액 표현 후보는 다음 두 가지이다.

| 비교 기준 | JSON number | 10진 문자열 |
| --- | --- | --- |
| 예 | `1250000` | `"1250000"` |
| Java `BigDecimal` | JSON 숫자 토큰을 `BigDecimal`로 직접 역직렬화하면 정밀도를 유지할 수 있다. 중간에 `double`을 거치면 정밀도 손실 가능성이 있다. | 문자열을 승인된 형식으로 검증한 뒤 `BigDecimal`로 변환해 10진수 값을 명시적으로 보존할 수 있다. |
| JavaScript | 일반 `number`는 IEEE 754 배정밀도를 사용하므로 큰 정수와 일부 소수에서 정확한 금융 금액 표현을 보장하지 못한다. | 문자열 상태로 정밀도를 보존할 수 있다. 계산 시 decimal 라이브러리 또는 별도 변환 정책이 필요하다. |
| API 사용 편의성 | 숫자 필드이므로 단순 클라이언트에서 계산·정렬하기 편리하다. | 형식 검증과 변환이 필요해 사용 편의성이 낮아질 수 있다. |
| 계약 명확성 | 클라이언트 언어와 JSON 파서에 따라 정밀도 처리 차이가 생길 수 있다. | 허용 자릿수와 소수점 형식을 계약으로 고정하기 쉽다. |

이번 API 계약에서 거래 금액은 **10진 정수 문자열**로 표현한다. JavaScript 클라이언트 경계에서 금융 금액 정밀도를 잃지 않고 Java `BigDecimal`로 명시적으로 변환하기 위한 기준이다.

- 값은 0보다 큰 정수여야 한다.
- 지수 표기, 소수부, 반올림과 자동 절삭을 허용하지 않는다.
- 초기 지원 통화는 `KRW` 하나이다.
- PostgreSQL 저장 타입과 최대값은 [`../04-database/transaction-intake-schema.md`](../04-database/transaction-intake-schema.md)의 `NUMERIC(19,4)` 계약을 따른다.

모든 거래 요청·응답 예시는 정수 문자열을 사용한다.

## 5. 페이지네이션

### 5.1 기본 방식

초기 목록 API는 다음 쿼리 파라미터를 우선 사용한다.

```text
page
size
sort
```

요청 예:

```http
GET /api/v1/transactions?page=0&size=20&sort=occurredAt,desc
```

공통 계약:

- `page`: 0부터 시작하는 페이지 번호이며 기본값은 `0`이다.
- `size`: 한 페이지 항목 수이며 기본값은 `20`, 최댓값은 `100`이다.
- `sort`: `field,direction` 형식
- 정렬 방향은 `asc` 또는 `desc`를 사용한다.
- 정렬 필드, 기본 정렬과 단일·복수 정렬 허용 여부는 각 API 계약의 허용 목록으로 제한한다.
- API 계약이 복수 정렬을 명시하지 않으면 반복된 `sort` 또는 다중 정렬을 허용하지 않는다.
- 같은 정렬값을 가진 항목의 순서를 안정적으로 유지하기 위해 업무 식별자 등의 보조 정렬키를 적용한다.

- `page`가 정수가 아니거나 `size`의 숫자 형식이 잘못되면 `400 Bad Request`와 `VALIDATION_ERROR`를 반환한다.
- `page`가 음수이거나 `size`가 1보다 작거나 100보다 크면 `422 Unprocessable Entity`와 `VALIDATION_ERROR`를 반환한다.
- 클라이언트가 요청할 수 있는 정렬 필드는 전 API 공통 JPA 필드가 아니며 각 API의 외부 계약 이름으로 정의한다.

페이지 응답의 공통 구조는 다음과 같다.

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
  "traceId": "trace_demo_01"
}
```

### 5.2 Cursor 방식 전환 조건

다음 조건이 실제 조회·부하 테스트에서 확인되면 Cursor 방식 전환을 검토한다.

- 데이터가 빠르게 추가되어 페이지 이동 중 중복 또는 누락이 빈번하다.
- 깊은 페이지의 Offset 조회 비용이 허용 범위를 넘는다.
- 무한 스크롤이나 연속 수집처럼 다음 묶음 조회가 중심이다.
- 전체 건수 계산 비용이 크고 정확한 `totalElements`가 필수 요구가 아니다.
- 발생 시각과 고유 식별자처럼 안정적이고 불변인 정렬키를 보장할 수 있다.

Cursor는 클라이언트가 내부 정렬키를 조작하지 않도록 불투명한 값으로 제공하는 방향을 권장한다. Cursor 전환은 응답 구조와 탐색 방식이 달라지므로 별도 API 계약 승인 후 진행한다.

## 6. 거래 생성 멱등성

### 6.1 요청 헤더

거래 생성 요청은 다음 헤더를 사용한다.

```http
Idempotency-Key: <key>
```

`Idempotency-Key`는 필수 헤더이다. 누락하거나 형식이 올바르지 않으면 Transaction과 멱등 기록을 생성하지 않고 `400 Bad Request`와 `VALIDATION_ERROR`를 반환한다.

- 길이는 8~128자이다.
- 허용 문자는 영문 대문자·소문자, 숫자, 마침표(`.`), 밑줄(`_`), 콜론(`:`), 하이픈(`-`)이다.
- 정규식은 `^[A-Za-z0-9._:-]{8,128}$`이다.
- 서버는 키를 trim하거나 대소문자 변환하지 않는다.
- 거래 생성의 작업 범위는 `POST:/api/v1/transactions`이다.
- `(operationScope, Idempotency-Key)` 조합을 Unique로 관리한다.
- 현재 DB는 `expiresAt`에 최초 선점 시각의 24시간 후를 저장한다. 그러나 Service의 만료 판정과 정리 작업은 구현되지 않았으므로 이를 시행 중인 멱등 유효기간으로 해석하지 않는다.

멱등 응답의 현재 구현, 신규 version envelope, legacy 재생과 최종 동기 응답 전환
정책은 [`ADR-004`](../07-decisions/ADR-004-idempotency-response-snapshot-transition.md)를
따른다. External Risk 선행 실패의 terminal 저장·재생은
[`ADR-007`](../07-decisions/ADR-007-external-risk-idempotent-failure-replay-contract.md)을
따른다.

### 6.2 처리 규칙

Spring Boot가 멱등성 확인과 업무 결과의 최종 소유자이다.

| 상황 | 처리 규칙 |
| --- | --- |
| 같은 키 + 같은 요청, legacy 처리 완료 | 새 거래·탐지·사건을 만들지 않고 `200 OK`로 기존 완료 결과를 반환한다. |
| 같은 키 + 같은 요청, 신규 envelope 처리 완료 | 새 거래·탐지·사건을 만들지 않고 저장된 최초 확정 업무 결과와 검증된 `201 Created`를 재생한다. `traceId`는 현재 재요청 값을 사용한다. |
| 같은 키 + 같은 요청, 최초 처리 중 | 새 처리를 시작하지 않고 `409 Conflict`와 `IDEMPOTENCY_REQUEST_IN_PROGRESS`를 반환한다. |
| 같은 키 + 같은 요청, External Risk 실패 확정 | Provider·FastAPI·위험 대응 최종화를 호출하지 않고 저장된 안전한 실패 응답을 재생한다. `traceId`는 현재 재요청 값을 사용한다. |
| 같은 키 + 다른 요청 | 키 재사용 충돌로 거부하고 `409 Conflict`와 `IDEMPOTENCY_KEY_CONFLICT`를 반환한다. |
| 다른 키 + 같은 `transactionId` | 새 거래로 처리하지 않고 `409 Conflict`와 `DUPLICATE_TRANSACTION`을 반환한다. |
| 같은 `transactionId` + 다른 요청 내용 | 기존 거래를 덮어쓰거나 재분석으로 해석하지 않고 `409 Conflict`와 `DUPLICATE_TRANSACTION`을 반환한다. |
| 같은 요청의 동시 도착 | 하나의 요청만 최초 처리를 획득한다. 나머지 요청은 새 업무 결과를 만들지 않고 기존 완료 결과를 반환하거나 처리 중 충돌을 반환한다. |

요청 형식과 거래 유형별 도메인 Validation은 멱등 선점 전에 수행한다. Validation에 실패한 요청은 Transaction과 멱등 기록을 생성하지 않고 오류 응답, `traceId`, 로그와 운영 메트릭으로만 관측한다.

처리 중인 동일 요청의 오류 응답은 다음 공통 구조를 사용한다.

응답 예:

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

### 6.3 요청 지문

정규화 요청 지문은 다음 열 개 필드를 정확히 포함한다.

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

`traceId`, `Idempotency-Key`, 내부 Identity PK, 생성·수정 시각, version, 처리 상태와 그 밖의 서버 생성 필드는 제외한다. 정규화 JSON의 UTF-8 byte sequence를 SHA-256으로 계산하고 소문자 16진수 64자로 저장한다. 필드 순서, UUID·금액·시각 정규화와 null 처리의 정확한 기준은 [`../04-database/transaction-intake-schema.md`](../04-database/transaction-intake-schema.md)를 따른다.

### 6.4 멱등성 상태 코드

- 최초 생성 완료 응답은 `201 Created`를 사용한다.
- 현재 무버전 legacy Snapshot의 완료 재전송은 `200 OK`로 기존 업무 결과를 반환한다.
- 신규 envelope 완료 재전송은 envelope에 기록되고 v1 codec이 검증한 `201 Created`를 사용한다.
- 처리 중인 동일 멱등 요청의 재전송은 `409 Conflict`와 `IDEMPOTENCY_REQUEST_IN_PROGRESS`를 반환한다.
- 어떤 응답이든 새 거래·탐지·사건을 중복 생성하지 않는다.

완료 재전송에서 거래·탐지·위험 대응·사건 연결 업무 값은 최초 확정 값을 유지한다. `traceId`와 추적 헤더는 Snapshot 재생 대상에서 제외하고 현재 재전송 요청 값을 사용하므로, 멱등 재생은 HTTP 응답 전체의 바이트 단위 복제를 의미하지 않는다.

### 6.5 저장 계약과 후속 항목

거래 접수의 요청 지문, 처리 상태, 완료 응답 snapshot, Unique 범위와 만료 저장 구조는 [`../04-database/transaction-intake-schema.md`](../04-database/transaction-intake-schema.md)를 따른다.

전환 이후 신규 완료 Snapshot은 `responseBody`, 최초 확정 `httpStatus=201`, `responseSchemaVersion=transaction-create-response-v1`, `codecVersion=transaction-intake-snapshot-envelope-v1`, `finalizedAt`을 정확히 가진 envelope로 저장한다. 현재 `responseBody`는 실제 구현된 `RECEIVED`와 네 탐지 관련 JSON null을 가진 일곱 업무 필드이다. 기존 무버전 Snapshot은 이 정확한 일곱 필드와 값 계약을 만족할 때만 strict legacy codec으로 복원하고 신규 envelope로 소급 갱신하지 않는다. 알 수 없는 구조·버전과 역직렬화 실패는 `500 INTERNAL_ERROR`로 fail-closed 처리하며 최신 상태 조회나 신규 거래 처리로 우회하지 않는다.

거래 접수에서 `FAILED`인 같은 키·같은 지문의 요청은 자동 재실행하지 않는다. 저장된 `failureCode`는 외부 code, message 또는 HTTP 상태로 동적으로 사용하지 않고 다음 공개 whitelist만 고정 매핑한다.

| 저장된 `failureCode` | HTTP 상태 | 공개 code | 공개 message |
| --- | --- | --- | --- |
| `DUPLICATE_TRANSACTION` | `409 Conflict` | `DUPLICATE_TRANSACTION` | `이미 존재하는 transactionId입니다.` |
| `DEPENDENCY_TIMEOUT` | `503 Service Unavailable` | `DEPENDENCY_TIMEOUT` | `탐지 서비스를 사용할 수 없습니다.` |

null, 빈 값, 알 수 없는 값과 `TRANSACTION_INTAKE_FAILED` 같은 내부 전용 값은 `500 Internal Server Error`, `INTERNAL_ERROR`, `요청을 처리하는 중 오류가 발생했습니다.`로 축약한다. 내부 `failureCode`는 오류 응답이나 로그에 노출하지 않는다.

ADR-007이 확정한 여섯 External Risk typed category는 정상적으로 저장된 경우 같은
operation scope·key에서 모두 terminal이다. 신규 typed 실패는 성공 Snapshot
legacy·v1·v2를 재사용하지 않고 별도 strict Failure Snapshot에 공개 응답을 저장하는
목표다.
같은 fingerprint 재요청은 Provider를 호출하지 않고 저장 HTTP status·공개 code·
안전 message·빈 `fieldErrors`를 의미적으로 재생한다. category별 authoritative 공개
매핑과 현재 구현 여부는
[`transaction-detection-api.md`](./transaction-detection-api.md)를 따른다.

신규 Failure Snapshot과 기존 code-only `FAILED`의 물리 호환성은
[`transaction-intake-schema.md`](../04-database/transaction-intake-schema.md)를 따른다.
현재 DB·Entity는 신규 Failure Snapshot을 저장할 수 없고 전용 codec·mapper와 신규
Migration은 아직 구현되지 않았다.

다음 항목은 후속 구현 전에 추가 결정한다.

- 실제 멱등 보존 기간과 만료 후 같은 키 재사용 허용 여부
- 만료 기록 정리 방식·주기와 정리 전후 경합 처리
- 재시도와 늦은 FastAPI 응답의 경합 처리
- 향후 응답 계약 또는 envelope 규칙 변경 시 새 version 식별자와 필요한 Flyway Migration

### 6.6 행동 이벤트 생성의 자연 멱등성

`POST /api/v1/behavior-events`는 별도 `Idempotency-Key`를 사용하지 않는다. 호출자가 생성한 REST 행동 이벤트의 `eventId`와 승인된 8개 요청 필드의 정규화 SHA-256 fingerprint를 사용한다.

- 같은 `eventId`와 같은 fingerprint는 새 행을 만들지 않고 `200 OK`로 기존 저장 결과를 반환한다.
- 같은 `eventId`와 다른 fingerprint는 `409 Conflict`와 `DUPLICATE_EVENT`를 반환한다.
- 최초 저장은 `201 Created`를 반환한다.
- `traceId`, 내부 PK와 `createdAt`은 fingerprint에 포함하지 않는다.
- 완료 응답 snapshot은 만들지 않고 불변 행동 이벤트 행에서 응답을 재구성한다.
- 재전송 응답에는 저장 당시 값이 아니라 현재 HTTP 요청의 `traceId`를 사용한다.

구체적인 필드 순서, null 직렬화와 DB 제약은 [`../04-database/behavior-event-intake-schema.md`](../04-database/behavior-event-intake-schema.md)를 따른다.

## 7. 오류 응답

### 7.1 공통 구조

오류 응답은 다음 구조를 기준으로 한다.

```json
{
  "code": "ERROR_CODE",
  "message": "오류 설명",
  "traceId": "추적 식별자",
  "fieldErrors": [
    {
      "field": "fieldName",
      "code": "FIELD_ERROR_CODE",
      "reason": "검증 실패 이유"
    }
  ]
}
```

- `code`는 클라이언트가 분기할 안정적인 오류 코드이다.
- `message`는 민감정보를 제외한 사용자·개발자용 요약이다.
- `traceId`는 서버 로그와 의존 서비스 호출 흐름을 찾기 위한 식별자이다.
- `fieldErrors`는 필드 검증 오류에 사용한다.
- 각 `fieldErrors.code`는 클라이언트가 분기할 수 있는 안정적인 필드 오류 코드 후보이다.
- 필드 오류가 없어도 `fieldErrors`는 빈 배열로 반환한다.
- 내부 예외 메시지, SQL, 스택 트레이스와 외부 Provider 원문을 반환하지 않는다.

등록되지 않은 일반 API 경로와 정적 리소스 경로 요청은 `404 Not Found`,
`RESOURCE_NOT_FOUND`, `요청한 리소스를 찾을 수 없습니다.`로 응답한다. 이 응답은
공통 오류 구조와 현재 요청의 `traceId`를 유지하고 `fieldErrors`는 빈 배열이다. 요청
path·query, 내부 예외 class·message·cause·stack trace는 응답에 포함하지 않는다.

이 프레임워크 미등록 경로 계약은 식별자로 조회한 거래·탐지 결과 등 명시적인 업무
리소스가 존재하지 않는 경우와 구분한다. 두 경우 모두 `RESOURCE_NOT_FOUND`를
사용할 수 있지만, 업무 not-found 응답은 각 업무 API가 승인한 공개 message를
유지하며 미등록 경로의 고정 message와 혼합하지 않는다.

요청 처리 중 영속 리소스가 생성된 뒤 오류가 발생하면 해당 리소스를 다시 조회할 수 있도록 `resource` 문맥을 추가하는 방안을 사용한다. 거래 Timeout 오류의 후보 문맥은 `transactionId`와 현재 `processingStatus`이다. JSON 파싱·필수 헤더·기본 필드 형식 오류처럼 리소스를 생성하지 않은 오류에는 `resource`를 반환하지 않는다. `resource`의 최종 이름과 API 전반의 범용 구조는 사용자 결정 사항이다.

검증 오류 예:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "요청 필드를 확인해 주세요.",
  "traceId": "trace_demo_validation_01",
  "fieldErrors": [
    {
      "field": "occurredAt",
      "code": "INVALID_DATETIME_FORMAT",
      "reason": "UTC ISO-8601 형식이어야 합니다."
    }
  ]
}
```

### 7.2 오류 코드

| 오류 코드 | 의미 | HTTP 상태 |
| --- | --- | --- |
| `VALIDATION_ERROR` | JSON 파싱, 필수 헤더, 필드 형식 또는 도메인 입력 검증 실패 | 형식 오류는 `400 Bad Request`, 형식이 맞는 도메인 규칙 위반은 `422 Unprocessable Entity` |
| `RESOURCE_NOT_FOUND` | 요청한 업무 리소스가 없거나 일반 API·정적 리소스 경로가 등록되지 않음 | `404 Not Found` |
| `DUPLICATE_TRANSACTION` | 이미 존재하는 `transactionId`로 새 거래 생성을 시도함 | `409 Conflict` |
| `DUPLICATE_EVENT` | 같은 `eventId`에 다른 내용이 도착하거나 중복 정책과 충돌함 | `409 Conflict` |
| `IDEMPOTENCY_KEY_CONFLICT` | 같은 멱등성 키가 다른 요청 내용에 재사용됨 | `409 Conflict` |
| `IDEMPOTENCY_REQUEST_IN_PROGRESS` | 같은 멱등성 키와 같은 요청의 최초 처리가 아직 진행 중임 | `409 Conflict` |
| `STATE_TRANSITION_NOT_ALLOWED` | 현재 상태에서 요청한 상태나 처리를 허용할 수 없음 | `409 Conflict` |
| `CONCURRENT_MODIFICATION` | 더 최신 변경과 충돌해 요청을 적용할 수 없음 | `409 Conflict` |
| `DEPENDENCY_TIMEOUT` | 필수 의존 서비스가 제한 시간 안에 결과를 반환하지 않음 | `503 Service Unavailable` |
| `DEPENDENCY_UNAVAILABLE` | 필수 의존 서비스나 저장소의 연결 실패 또는 명확한 일시적 가용성 장애 | `503 Service Unavailable` |
| `INTERNAL_ERROR` | 공개할 수 없는 예기치 않은 서버 오류 | `500 Internal Server Error` |

JSON·필수 헤더·필드 형식 오류와 도메인 규칙 위반을 구분하되 모두 `VALIDATION_ERROR`를 사용할 수 있다. 더 세분화된 최상위 오류 코드가 필요한지는 후속 Validation·OpenAPI 설계에서 결정한다.

`DEPENDENCY_TIMEOUT`은 제한 시간 초과로 명확히 분류된 실패에만 사용한다. `DEPENDENCY_UNAVAILABLE`은 DB 연결 실패처럼 연결 또는 일시적 가용성 장애로 명확히 분류된 실패에 사용한다. 그 밖의 원인을 확정할 수 없는 `DataAccessException`과 예상하지 못한 서버 오류는 `500 Internal Server Error`와 `INTERNAL_ERROR`로 축약한다. 내부 예외 메시지, SQL, 테이블명, 컬럼명과 요청 원문은 공개 오류 응답에 포함하지 않는다.

### 7.3 HTTP 상태 코드 기준

| 상태 코드 | 공통 사용 기준 |
| --- | --- |
| `200 OK` | 조회 성공, 기존 결과 반환 또는 승인된 중복 수집 응답 |
| `201 Created` | 거래 또는 행동 이벤트가 처음 생성됨 |
| `202 Accepted` | 명시적으로 비동기 접수 계약을 가진 API가 요청 또는 진행 중 리소스 상태를 반환함. 거래 생성의 동일 멱등 요청 처리 중 응답에는 사용하지 않음 |
| `400 Bad Request` | 잘못된 JSON, 필수 헤더 누락, 필드·쿼리 형식 오류 등 요청을 해석·기본 검증할 수 없음 |
| `401 Unauthorized` | credential이 없거나 Bearer JWT·필수 claim 검증에 실패함 |
| `403 Forbidden` | 인증·claim 검증은 성공했지만 endpoint authority가 부족함 |
| `404 Not Found` | 식별자로 요청한 업무 리소스가 없거나 요청 경로가 등록되지 않음 |
| `409 Conflict` | 멱등성 키, 업무 식별자, 상태 또는 동시성 충돌 |
| `422 Unprocessable Entity` | 형식은 올바르지만 거래 유형별 도메인 규칙 등 업무 의미상 처리할 수 없는 입력 |
| `503 Service Unavailable` | 필수 의존성 Timeout 또는 일시적인 서비스 처리 불가 |

서버의 예기치 않은 오류에는 `500 Internal Server Error`가 필요할 수 있다. 사용자가 지정한 주요 상태 코드 외 상태를 추가할 때는 API 계약 검토를 거친다.

### 7.4 목표 401·403 계약

현재는 security layer가 없어 아래 응답이 구현되지 않았다. 목표 구현에서는
`TraceIdFilter`가 Spring Security보다 먼저 요청 traceId를 확정하고, filter 단계의
`AuthenticationEntryPoint`와 `AccessDeniedHandler`가 `GlobalExceptionHandler`에 의존하지
않고 공통 body를 작성한다.

| 상태 | code | message | `WWW-Authenticate` |
| --- | --- | --- | --- |
| credential 없음 | `UNAUTHORIZED` | `인증이 필요하거나 인증 정보가 유효하지 않습니다.` | `Bearer realm="finguardops-backend"` |
| invalid token·claim | `UNAUTHORIZED` | `인증이 필요하거나 인증 정보가 유효하지 않습니다.` | `Bearer realm="finguardops-backend", error="invalid_token"` |
| authority 부족 | `ACCESS_DENIED` | `요청한 작업을 수행할 권한이 없습니다.` | `Bearer realm="finguardops-backend", error="insufficient_scope"` |

세 응답 모두 `fieldErrors`는 빈 배열이며 body `traceId`와 `X-Trace-Id`는 같은 현재 요청
값이다. token·claim·Authorization Server Provider 예외·내부 security class·stack trace를
응답이나 일반 로그에 포함하지 않는다. 401·403은 업무 AuditLog를 생성하지 않는다.
malformed header/token, 서명·시간·issuer·audience·필수 claim·role 검증 실패는 401이다.
실제 upstream JWK 가용성 장애의 안전한 503 code는 Spring Security 예외 분류를 검증하는
후속 구현에서 확정하며 invalid token 401과 혼합하지 않는다.

## 8. `traceId` 원칙

Spring Boot는 클라이언트 요청부터 External Risk Mock과 FastAPI 호출까지 하나의 업무 흐름을 추적할 수 있는 `traceId`를 관리한다.

```text
Client
→ Spring Boot
→ External Risk Mock
→ FastAPI
```

### 8.1 HTTP 헤더

Spring Boot가 처리하는 모든 HTTP 요청은 다음 요청·응답 헤더를 사용한다. 기존 Health Check API인 `/api/health`에도 같은 응답 헤더를 적용하되 기존 JSON 본문 구조는 변경하지 않는다.

```http
X-Trace-Id: <traceId>
```

- 요청의 `X-Trace-Id`는 선택 사항이다.
- 응답의 `X-Trace-Id`는 항상 현재 HTTP 요청의 `traceId`이다.
- 오류 응답의 `X-Trace-Id`와 본문 `traceId`는 반드시 같은 값이다.
- 거래·행동·탐지 등 업무 API의 성공·오류 응답 본문은 각 API 계약에 정의된 `traceId`를 반환한다.
- `/api/health` 성공 응답 본문은 기존 `status`, `service` 필드만 유지하고 `traceId` 필드를 추가하지 않는다.

### 8.2 외부 `traceId` 수용 규칙

Spring Boot는 `X-Trace-Id`가 정확히 하나의 헤더 값으로 전달되고 다음 정규식 전체와 일치할 때만 원문 그대로 수용한다.

```text
^[A-Za-z0-9][A-Za-z0-9._:-]{7,63}$
```

- 전체 길이는 8~64자이다.
- 첫 문자는 영문 대문자·소문자 또는 숫자이다.
- 이후 문자는 영문 대문자·소문자, 숫자, 마침표(`.`), 밑줄(`_`), 콜론(`:`), 하이픈(`-`)만 허용한다.
- 서버는 값을 trim하거나 대소문자를 변환하거나 일부를 잘라내지 않는다.
- `X-Trace-Id` 헤더가 여러 줄로 전달되면 유효하지 않다.
- 하나의 헤더 값에 쉼표가 포함되어 여러 값이 결합된 경우에도 유효하지 않다.
- 빈 값, 길이 위반, 공백·비ASCII·제어 문자·그 밖의 허용되지 않은 문자를 포함한 값은 유효하지 않다.
- 외부 `traceId`는 요청 추적용 불투명 값일 뿐 신뢰된 사용자 식별자, 인증·인가 정보 또는 보안 증명으로 사용하지 않는다.
- 유효하지 않은 외부 원문을 로그, 오류 메시지, 응답 헤더 또는 응답 본문에 포함하지 않는다.

### 8.3 서버 생성과 요청 범위

- `X-Trace-Id`가 없거나 8.2의 검증을 통과하지 못하면 요청을 거절하지 않고 Spring Boot가 새 `traceId`를 생성한다.
- 서버 생성값은 `UUID.randomUUID().toString()`으로 만든 canonical lowercase UUID v4 문자열이며 길이는 36자이다.
- 하나의 HTTP 요청에서는 `traceId`를 한 번만 결정하고 정상·오류 처리와 로그에서 같은 값을 사용한다.
- 오류 처리 과정에서 다른 `traceId`를 생성하지 않는다.
- 요청 처리가 끝나면 실행 스레드의 MDC에서 `traceId`를 제거한다.
- `traceId`는 `transactionId`, `eventId`, `detectionResultId`를 대체하지 않는다.
- `traceId`는 거래 요청 fingerprint, 멱등 성공 Snapshot과 External Risk Failure
  Snapshot에서 제외한다. 완료·실패 재전송에는 저장된 업무 결과와 현재 HTTP 요청의
  `traceId`를 결합한다. exact replay는 저장 HTTP status·공개 code·안전 message·
  `fieldErrors`의 의미적 동일성이며 trace, HTTP 헤더와 JSON byte ordering은 포함하지
  않는다. 공개 `replayed` 필드는 추가하지 않는다.
- 로그·메트릭·트레이스에 고객·계좌·IP 원문을 `traceId`와 함께 기록하지 않으며 `traceId`를 메트릭 레이블로 추가하지 않는다.

OpenTelemetry, W3C Trace Context의 `traceparent`, 외부 HTTP 호출, Kafka와 비동기 작업으로의 전파, 샘플링과 보존 기간은 이 문서의 현재 구현 범위에서 제외한다.

## 9. 사용자 결정 필요 항목

- 멱등 만료 기록 정리 방식과 만료 후 키 재사용 정책
- Validation 오류의 최상위 오류 코드를 더 세분화할지
- `fieldErrors.code`의 코드 목록과 버전 관리 방식
- 오류 응답의 `resource` 최종 이름과 범용 구조
- OpenTelemetry 추적 헤더와 외부 서비스·Kafka·비동기 경계의 추적 문맥 전파 정책

## 10. 제외 범위

- 승인된 인증·인가·CORS 계약의 production 구현
- Java Controller, DTO, Service와 Exception Handler
- JPA Entity와 PostgreSQL DDL
- OpenAPI YAML
- 구체적인 OpenTelemetry Header 구현
- Kafka 이벤트 계약
- 사건 상태 변경, 조사 메모와 AI 리포트 API
- AI 사용량과 플랫폼 운영 API
