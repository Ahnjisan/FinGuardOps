# FinGuardOps API 공통 규칙

## 1. 문서 목적

이 문서는 FinGuardOps의 Spring Boot REST API가 공통으로 따를 표현 형식, 식별자, 금액, 페이지네이션, 멱등성, 오류 응답과 추적 원칙을 정의한다.

이 문서는 이후 Controller, 요청·응답 DTO, Validation, Service, 테스트와 OpenAPI 계약의 기준이다. Java 타입, DB 컬럼, OpenTelemetry 전파 헤더와 인증·인가 구현은 이 문서에서 확정하지 않는다.

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

예:

```json
{
  "occurredAt": "2026-07-23T01:15:30Z",
  "createdAt": "2026-07-23T01:15:31Z"
}
```

클라이언트의 로컬 시간대 표시는 화면 책임이며 API의 원본 시각을 덮어쓰지 않는다. 소수 초 정밀도와 허용할 시각 범위는 후속 Validation 설계에서 확정한다.

### 3.3 내부 식별자와 업무 식별자

DB 관계와 저장에 사용하는 내부 식별자와 API·로그·업무 조회에 사용하는 업무 식별자를 구분한다.

| 식별자 | 의미 |
| --- | --- |
| 내부 DB 식별자 | Entity 관계와 DB 연결에 사용하는 내부 값. 기본적으로 API에 노출하지 않는다. |
| `transactionId` | 거래 접수·조회와 관련 탐지·사건 연결에 사용하는 거래 업무 식별자 |
| `eventId` | 행동 이벤트의 중복 수신과 조회에 사용하는 행동 이벤트 업무 식별자 |
| `detectionResultId` | 저장·검증된 개별 탐지 결과를 외부 계약에서 식별하는 업무 식별자 |
| `caseId` | 생성되었거나 연결된 사건을 식별하는 업무 식별자 |
| `traceId` | Spring Boot와 의존 서비스 호출 흐름을 연결하는 추적 식별자 |

각 식별자는 서로 대체할 수 없다. 특히 `traceId`는 거래나 탐지 결과의 업무 식별자가 아니며, `eventId`는 이 문서 범위에서 행동 이벤트 식별자를 뜻한다.

식별자의 구체적인 형식, 길이, 생성 주체와 보존 기간은 후속 구현 설계에서 확정한다. 식별자 자체에 실제 고객번호, 계좌번호, 인증정보와 같은 민감정보를 포함하지 않는다.

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
  "amount": "1250000.00",
  "currencyCode": "KRW"
}
```

금액 표현 후보는 다음 두 가지이다.

| 비교 기준 | JSON number | 소수점 문자열 |
| --- | --- | --- |
| 예 | `1250000.00` | `"1250000.00"` |
| Java `BigDecimal` | JSON 숫자 토큰을 `BigDecimal`로 직접 역직렬화하면 정밀도를 유지할 수 있다. 중간에 `double`을 거치면 정밀도 손실 가능성이 있다. | 문자열을 승인된 형식으로 검증한 뒤 `BigDecimal`로 변환해 10진수 값을 명시적으로 보존할 수 있다. |
| JavaScript | 일반 `number`는 IEEE 754 배정밀도를 사용하므로 큰 정수와 일부 소수에서 정확한 금융 금액 표현을 보장하지 못한다. | 문자열 상태로 정밀도를 보존할 수 있다. 계산 시 decimal 라이브러리 또는 별도 변환 정책이 필요하다. |
| API 사용 편의성 | 숫자 필드이므로 단순 클라이언트에서 계산·정렬하기 편리하다. | 형식 검증과 변환이 필요해 사용 편의성이 낮아질 수 있다. |
| 계약 명확성 | 클라이언트 언어와 JSON 파서에 따라 정밀도 처리 차이가 생길 수 있다. | 허용 자릿수와 소수점 형식을 계약으로 고정하기 쉽다. |

이번 API 계약에서 거래 금액은 **소수점 문자열**로 표현한다. JavaScript 클라이언트 경계에서 금융 금액 정밀도를 잃지 않고 Java `BigDecimal`로 명시적으로 변환하기 위한 기준이다.

다음 세부 사항은 후속 사용자 승인이 필요하다.

- 통화별 허용 소수 자릿수
- 최대 정수부·소수부 자릿수
- 반올림 허용 여부와 반올림 방식
- 0원·음수 거래 허용 여부

모든 거래 요청·응답 예시는 확정된 계약인 소수점 문자열을 사용한다.

## 5. 페이지네이션

### 5.1 초기 권장 방식

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

권장 계약 후보:

- `page`: 0부터 시작하는 페이지 번호
- `size`: 한 페이지 항목 수
- `sort`: `field,direction` 형식
- 여러 정렬 조건이 필요하면 `sort`를 반복한다.
- 정렬 방향은 `asc` 또는 `desc`를 사용한다.
- 정렬 필드는 API가 허용 목록으로 제한한다.
- 같은 정렬값을 가진 항목의 순서를 안정적으로 유지하기 위해 업무 식별자 등의 보조 정렬키를 적용한다.

`page` 시작값, 기본 `size`, 최대 `size`와 API별 허용 정렬 필드는 구현 전에 사용자 승인이 필요하다.

페이지 응답의 공통 후보는 다음과 같다.

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

`Idempotency-Key`는 필수 헤더이다. 누락하거나 형식이 올바르지 않으면 Transaction을 생성하지 않고 `400 Bad Request`와 `VALIDATION_ERROR`를 반환한다.

### 6.2 처리 규칙

Spring Boot가 멱등성 확인과 업무 결과의 최종 소유자이다.

| 상황 | 처리 규칙 |
| --- | --- |
| 같은 키 + 같은 요청, 최초 처리 완료 | 새 거래·탐지·사건을 만들지 않고 `200 OK`로 기존 완료 결과를 반환한다. |
| 같은 키 + 같은 요청, 최초 처리 중 | 새 처리를 시작하지 않고 `202 Accepted`로 현재 처리 상태를 반환한다. |
| 같은 키 + 다른 요청 | 키 재사용 충돌로 거부하고 `409 Conflict`와 `IDEMPOTENCY_KEY_CONFLICT`를 반환한다. |
| 다른 키 + 같은 `transactionId` | 새 거래로 처리하지 않고 `409 Conflict`와 `DUPLICATE_TRANSACTION`을 반환한다. |
| 같은 `transactionId` + 다른 요청 내용 | 기존 거래를 덮어쓰거나 재분석으로 해석하지 않고 `409 Conflict`와 `DUPLICATE_TRANSACTION`을 반환한다. |
| 같은 요청의 동시 도착 | 하나의 요청만 최초 처리를 획득한다. 나머지 요청은 새 업무 결과를 만들지 않고 기존 처리 상태나 완료 결과를 참조한다. |

처리 중인 동일 요청의 응답에는 최소한 다음 정보를 포함한다.

- `transactionId`
- 현재 `processingStatus`
- 결과 조회 경로 후보
- `traceId`

응답 예:

```http
HTTP/1.1 202 Accepted
Content-Type: application/json
```

```json
{
  "transactionId": "tx_demo_20260723_0001",
  "processingStatus": "ANALYZING",
  "resultLocation": "/api/v1/transactions/tx_demo_20260723_0001",
  "traceId": "trace_demo_processing_01"
}
```

`resultLocation`의 최종 필드명과 HTTP `Location` 헤더 병행 여부는 사용자 결정 사항이다.

### 6.3 멱등성 상태 코드

- 최초 생성 완료 응답은 `201 Created`를 사용한다.
- 완료된 동일 멱등 요청의 재전송은 `200 OK`로 기존 결과를 반환한다.
- 처리 중인 동일 멱등 요청의 재전송은 `202 Accepted`로 현재 상태를 반환한다.
- 어떤 응답이든 새 거래·탐지·사건을 중복 생성하지 않는다.

### 6.4 후속 구현 항목

다음은 API 계약에 필요한 후속 구현 항목이며 이번 문서에서 저장 구조를 확정하지 않는다.

- 정규화된 요청 내용의 요청 지문 저장
- 처리 중·완료·실패를 구분하는 멱등 처리 상태
- 완료 결과 식별자 또는 완료 응답 저장
- 동시에 도착한 요청의 최초 처리 선점
- 멱등성 기록 보존·만료 정책
- 재시도와 늦은 FastAPI 응답의 경합 처리

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

### 7.2 오류 코드 후보

| 오류 코드 | 의미 | HTTP 상태 후보 |
| --- | --- | --- |
| `VALIDATION_ERROR` | JSON 파싱, 필수 헤더, 필드 형식 또는 도메인 입력 검증 실패 | 형식 오류는 `400 Bad Request`, 형식이 맞는 도메인 규칙 위반은 `422 Unprocessable Entity` |
| `RESOURCE_NOT_FOUND` | 요청한 거래 또는 탐지 결과가 없음 | `404 Not Found` |
| `DUPLICATE_TRANSACTION` | 이미 존재하는 `transactionId`로 새 거래 생성을 시도함 | `409 Conflict` |
| `DUPLICATE_EVENT` | 같은 `eventId`에 다른 내용이 도착하거나 중복 정책과 충돌함 | `409 Conflict` |
| `IDEMPOTENCY_KEY_CONFLICT` | 같은 멱등성 키가 다른 요청 내용에 재사용됨 | `409 Conflict` |
| `STATE_TRANSITION_NOT_ALLOWED` | 현재 상태에서 요청한 상태나 처리를 허용할 수 없음 | `409 Conflict` |
| `CONCURRENT_MODIFICATION` | 더 최신 변경과 충돌해 요청을 적용할 수 없음 | `409 Conflict` |
| `DEPENDENCY_TIMEOUT` | 필수 의존 서비스가 제한 시간 안에 결과를 반환하지 않음 | `503 Service Unavailable` |
| `INTERNAL_ERROR` | 공개할 수 없는 예기치 않은 서버 오류 | `500 Internal Server Error` |

JSON·필수 헤더·필드 형식 오류와 도메인 규칙 위반을 구분하되 모두 `VALIDATION_ERROR`를 사용할 수 있다. 더 세분화된 최상위 오류 코드가 필요한지는 후속 Validation·OpenAPI 설계에서 결정한다.

### 7.3 HTTP 상태 코드 기준

| 상태 코드 | 공통 사용 기준 |
| --- | --- |
| `200 OK` | 조회 성공, 기존 결과 반환 또는 승인된 중복 수집 응답 |
| `201 Created` | 거래 또는 행동 이벤트가 처음 생성됨 |
| `202 Accepted` | 처리 중인 동일 멱등 거래 요청에 현재 처리 상태를 반환함 |
| `400 Bad Request` | 잘못된 JSON, 필수 헤더 누락, 필드·쿼리 형식 오류 등 요청을 해석·기본 검증할 수 없음 |
| `404 Not Found` | 식별자로 요청한 리소스가 없음 |
| `409 Conflict` | 멱등성 키, 업무 식별자, 상태 또는 동시성 충돌 |
| `422 Unprocessable Entity` | 형식은 올바르지만 거래 유형별 도메인 규칙 등 업무 의미상 처리할 수 없는 입력 |
| `503 Service Unavailable` | 필수 의존성 Timeout 또는 일시적인 서비스 처리 불가 |

서버의 예기치 않은 오류에는 `500 Internal Server Error`가 필요할 수 있다. 사용자가 지정한 주요 상태 코드 외 상태를 추가할 때는 API 계약 검토를 거친다.

## 8. `traceId` 원칙

Spring Boot는 클라이언트 요청부터 External Risk Mock과 FastAPI 호출까지 하나의 업무 흐름을 추적할 수 있는 `traceId`를 관리한다.

```text
Client
→ Spring Boot
→ External Risk Mock
→ FastAPI
```

원칙은 다음과 같다.

- Spring Boot는 유효한 추적 문맥이 없으면 새 `traceId`를 생성한다.
- 승인된 외부 입력 추적 문맥을 수용할 경우에도 형식과 신뢰 경계를 검증한다.
- Spring Boot는 External Risk Mock과 FastAPI 요청에 같은 추적 흐름을 연결할 수 있는 값을 전달한다.
- FastAPI와 External Risk Mock은 로그와 응답에서 해당 흐름을 연결할 수 있어야 한다.
- Spring Boot는 성공·오류 응답 본문에 클라이언트가 확인할 수 있는 `traceId`를 반환한다.
- `traceId`는 `transactionId`, `eventId`, `detectionResultId`를 대체하지 않는다.
- 로그·메트릭·트레이스에 고객·계좌·IP 원문을 `traceId`와 함께 기록하지 않는다.

구체적인 OpenTelemetry Header, W3C Trace Context 적용 방식, 응답 헤더명, 샘플링과 보존 기간은 이 문서에서 확정하지 않는다.

## 9. 사용자 결정 필요 항목

- 통화별 금액 자릿수, 최대값, 0·음수와 반올림 정책
- 페이지 번호 시작값, 기본·최대 `size`와 허용 정렬 필드
- `Idempotency-Key` 형식, 범위와 보존 기간
- 처리 중 응답의 `resultLocation` 최종 필드명과 `Location` 헤더 병행 여부
- 요청 지문과 완료 응답의 저장 범위
- Validation 오류의 최상위 오류 코드를 더 세분화할지
- `fieldErrors.code`의 코드 목록과 버전 관리 방식
- 오류 응답의 `resource` 최종 이름과 범용 구조
- OpenTelemetry 추적 헤더와 외부 추적 문맥 수용 정책

## 10. 제외 범위

- 인증·인가와 CORS 구현
- Java Controller, DTO, Service와 Exception Handler
- JPA Entity와 PostgreSQL DDL
- OpenAPI YAML
- 구체적인 OpenTelemetry Header 구현
- Kafka 이벤트 계약
- 사건 상태 변경, 조사 메모와 AI 리포트 API
- AI 사용량과 플랫폼 운영 API
