# 사건·조사 메모·감사 API

## 1. 문서 목적

이 문서는 FinGuardOps의 FDS 분석 담당자가 사건을 조회하고, 담당자와 조사 상태를 관리하며, 최종 판정을 확정하고, 조사 메모와 감사 이력을 조회하기 위한 Spring Boot REST API 계약을 정의한다.

이 계약은 이후 Spring Boot Controller, 요청·응답 DTO, Validation, Service, 테스트와 OpenAPI 구현의 기준이다. API 공통 표현, 시간, 금액, 페이지네이션, 오류 응답과 추적 원칙은 [`api-conventions.md`](./api-conventions.md)를 따른다.

Issue #207에서 사건 목록·상세 조회를 구현했고 Issue #209에서 아래 두 mutation과
PostgreSQL 낙관적 동시성·감사 원자성 경계를 구현했다. Issue #211은 최종 판정·
종료 API와 V12 감사를 구현했고 Issue #213에서 조사 메모 생성·목록과 V13 감사를
구현했다. Issue #215는 사건 감사 로그 조회 API와 명시적 비노출 projection을
구현했다. Issue #221은 아래 실제 사건·메모·감사 endpoint RBAC와 네 high-risk write
method security를 구현했고 Issue #223은 네 write의 USER actor와 조사 메모 USER author를
구현했다. 연관 거래 목록은 구현되지 않았다. 사건 영속 계약은
[`../04-database/fraud-case-schema.md`](../04-database/fraud-case-schema.md)를 따른다.
구현 인증·인가와 USER Audit actor 계약은
[`security-architecture.md`](../02-architecture/security-architecture.md)와
[`ADR-008`](../07-decisions/ADR-008-oauth2-resource-server-rbac-user-audit-actor.md)을
따른다. Spring Security·RBAC와 USER writer가 구현되었다.
실제 `caseId`와 `auditId`는 UUID v4를 사용한다.
Issue #207 범위 밖의 후속 API 절에 남아 있는 `case_demo_...` 값은 읽기 쉬운
미구현 예시일 뿐 실제 식별자 형식이 아니다.

## 2. 범위와 책임 경계

### 2.1 처리 흐름

```text
FDS 분석 담당자
→ 사건 대기열과 사건 상세 조회
→ 연관 거래·조사 메모·감사 이력 조회
→ 담당자 또는 사건 상태 변경
→ 최종 판정과 사건 종료
→ Spring Boot의 전이·동시성 검증
→ PostgreSQL에 사건 현재값과 감사 기록 저장
```

### 2.2 Spring Boot 책임

- 사건의 현재 상태, 담당자와 최종 판정을 검증하고 변경한다.
- 허용 상태 전이와 종료 조건을 검증한다.
- `expectedVersion`과 현재 `concurrencyVersion`을 비교한다.
- 사건 종료와 최종 판정 설정을 하나의 업무 정합성 경계에서 처리한다.
- 사건, 조사 메모와 감사 기록을 PostgreSQL에 영속화한다.
- 작성자와 변경 주체는 신뢰할 수 있는 서버 사용자 문맥에서 결정한다.
- 주요 변경, 거부된 상태 전이와 동시성 충돌을 감사 가능하게 기록한다.

### 2.3 FastAPI와 LLM 책임 경계

- FastAPI와 LLM은 사건 상태, 담당자와 최종 판정을 변경하지 않는다.
- AI 사건 리포트는 FDS 분석 담당자의 조사를 돕는 참고 자료이며 최종 판정을 대신하지 않는다.
- AI 리포트의 생성 실패, fallback 또는 상태 변경은 사건 상태를 자동 변경하지 않는다.
- 이번 문서에서는 AI 리포트 API를 정의하지 않는다.

### 2.4 데이터 소유권

- Spring Boot는 사건 상태와 최종 판정을 포함한 업무 정합성의 최종 소유자이다.
- PostgreSQL은 사건, 연관 거래, 조사 메모와 감사 데이터의 영속 원본이다.
- 감사 로그는 변경 이력을 제공하지만 `FraudCase`에 저장된 현재 상태의 원본을 대신하지 않는다.
- Redis, FastAPI와 LLM Provider는 사건·메모·감사 데이터의 영속 원본이 아니다.

## 3. 공통 계약

### 3.1 기본 경로와 표현

```text
/api/v1
```

- 요청과 응답 본문은 UTF-8 JSON을 사용한다.
- JSON 필드명은 `camelCase`를 사용한다.
- 시간은 UTC ISO-8601 형식과 `Z` 접미사를 사용한다.
- 내부 DB 식별자는 기본적으로 API에 노출하지 않는다.
- 성공과 오류 응답에는 현재 API 요청을 추적하는 `traceId`를 반환한다.

### 3.2 사건 상태

`caseStatus`는 사건 업무의 현재 진행 단계이다.

```text
OPEN
IN_REVIEW
ADDITIONAL_INFORMATION_REQUIRED
CLOSED
```

### 3.3 최종 판정

`finalDisposition`은 조사 결과이며 `caseStatus`와 별도 필드로 관리한다.

```text
NORMAL
FALSE_POSITIVE
CONFIRMED_FRAUD
```

```text
caseStatus
≠ finalDisposition
```

조사 중에는 `finalDisposition`이 `null`일 수 있다. 사건 상태와 최종 판정을 하나의 필드나 Enum으로 합치지 않는다.

### 3.4 초기 사건 전이 정책

일반 상태 변경 API에서 허용하는 전이는 다음과 같다.

```text
OPEN
→ IN_REVIEW

IN_REVIEW
→ ADDITIONAL_INFORMATION_REQUIRED

ADDITIONAL_INFORMATION_REQUIRED
→ IN_REVIEW
```

`CLOSED`는 일반 상태 변경 API에서 직접 설정하지 않는다. 사건 종료는 `POST /api/v1/cases/{caseId}/resolution`에서만 수행한다.

초기 담당자 정책은 다음과 같다.

```text
IN_REVIEW 사건
→ 담당자 필수
```

- `OPEN` → `IN_REVIEW` 전이에서는 상태 변경 요청의 `assigneeRef`로 담당자를 함께 지정한다.
- `ADDITIONAL_INFORMATION_REQUIRED` → `IN_REVIEW` 전이에서는 기존 담당자가 있어야 한다.
- 담당자 지정, `caseStatus = IN_REVIEW`, 최초 `reviewStartedAt` 기록, `lastChangedAt` 변경, `concurrencyVersion` 증가와 AuditLog 기록은 일부만 저장되지 않도록 같은 업무 정합성 경계에서 처리한다.
- `reviewStartedAt`은 사건이 최초로 `IN_REVIEW`에 진입할 때만 기록하고 이후 `ADDITIONAL_INFORMATION_REQUIRED` → `IN_REVIEW` 전이에서는 기존 값을 유지한다.

초기 사건 종료 정책은 다음과 같다.

- `IN_REVIEW` 사건만 종료할 수 있다.
- `finalDisposition`은 필수이다.
- 최종 판정 설정과 `caseStatus = CLOSED`를 하나의 업무 트랜잭션으로 처리한다.
- `closedAt`을 기록한다.
- 변경 전후 값과 사유를 AuditLog에 기록한다.
- 종료된 사건의 재개와 최종 판정 변경은 초기 범위에서 제외한다.

### 3.5 페이지네이션

목록 API는 공통 규칙에 따라 다음 쿼리 파라미터를 사용한다.

```text
page
size
sort
```

페이지 응답은 다음 구조를 사용한다.

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
  "traceId": "trace_demo_case_page_01"
}
```

사건 목록은 0부터 시작하고 기본 `page=0`, `size=20`, 최대 `size=100`을 사용한다.
정렬은 `lastChangedAt,asc` 또는 `lastChangedAt,desc` 하나만 허용하며 기본값은
`lastChangedAt,desc`이다. 같은 변경 시각에는 내부 `id`를 같은 방향의 보조
정렬키로 사용하되 요청·응답에 내부 `id`를 노출하지 않는다.

### 3.6 사건 mutation 재요청 범위

사건 종료와 조사 메모 생성 API는 `Idempotency-Key`를 요구하거나 replay하지 않는다.
두 API 모두 필수 `expectedVersion`과 JPA optimistic version을 사용한다. 성공 후 같은
`expectedVersion`으로 메모 생성을 재요청하면 `409 CONCURRENT_MODIFICATION`이며 새
`InvestigationNote`와 `AuditLog`를 생성하지 않는다.

## 4. API 목록

```text
GET   /api/v1/cases
GET   /api/v1/cases/{caseId}
GET   /api/v1/cases/{caseId}/transactions

PATCH /api/v1/cases/{caseId}/status
PATCH /api/v1/cases/{caseId}/assignee
POST  /api/v1/cases/{caseId}/resolution

POST  /api/v1/cases/{caseId}/notes
GET   /api/v1/cases/{caseId}/notes

GET   /api/v1/cases/{caseId}/audit-logs
```

실제 구현 endpoint 중 사건 목록·상세는 `case:read`, 상태·담당자 변경은
`case:workflow:write`, 종결은 `case:resolution:write`, 메모 생성·조회는 각각
`case-note:write`·`case-note:read`, 감사 조회는 `case-audit:read`를 요구한다. 문서 후보인
`GET /api/v1/cases/{caseId}/transactions`에는 matcher가 없다. write 네 개는 URL matcher와
production Service proxy의 method security로 이중 보호한다.

## 5. 사건 목록 조회

### 5.1 요청

```http
GET /api/v1/cases
```

### 5.2 필터와 페이지네이션

| 쿼리 파라미터 | 기본값 | 설명 |
| --- | --- | --- |
| `caseStatus` | 없음 | 사건 상태 단일 Enum |
| `finalDisposition` | 없음 | 최종 판정 단일 Enum. null 전용 필터 없음 |
| `assigneeRef` | 없음 | opaque 운영자 참조값 exact 검색 |
| `createdAtFrom` | 없음 | 생성 시각 시작, UTC ISO-8601 `Z`, 포함 |
| `createdAtTo` | 없음 | 생성 시각 끝, UTC ISO-8601 `Z`, 미포함 |
| `lastChangedAtFrom` | 없음 | 변경 시각 시작, UTC ISO-8601 `Z`, 포함 |
| `lastChangedAtTo` | 없음 | 변경 시각 끝, UTC ISO-8601 `Z`, 미포함 |
| `transactionId` | 없음 | 연관 거래 canonical UUID v4 |
| `page` | `0` | 0부터 시작하는 페이지 번호 |
| `size` | `20` | 1~100의 페이지 크기 |
| `sort` | `lastChangedAt,desc` | 단일 변경 시각 정렬 |

요청 예:

```http
GET /api/v1/cases?caseStatus=IN_REVIEW&page=0&size=20&sort=lastChangedAt,asc
```

모든 쿼리 파라미터는 최대 한 번만 허용한다. Enum은 정확한 대문자 단일 값이며
`assigneeRef`는 trim하지 않는 exact·case-sensitive 값이다. 시간 범위는
`[from,to)`이고 같은 시작·끝은 빈 범위이며, 시작이 끝보다 늦으면 `422`이다.
형식·중복·미지원 값과 정렬 오류는 `400`이다.

### 5.3 목록 항목

목록 응답은 사건 대기열에 필요한 다음 요약만 포함한다.

| 필드 | 설명 |
| --- | --- |
| `caseId` | 사건 업무 식별자 |
| `caseStatus` | 현재 사건 상태 |
| `finalDisposition` | 최종 판정. 조사 중에는 null 가능 |
| `assigneeRef` | opaque 운영자 참조값. 미배정이면 null 가능 |
| `relatedTransactionCount` | 연관 거래 수 |
| `createdAt` | 사건 생성 시각 |
| `lastChangedAt` | 사건 마지막 변경 시각 |

대표 거래·위험등급·사유, 전체 연관 거래, 조사 메모, 감사 로그와 AI 리포트는
목록에 포함하지 않는다.

### 5.4 성공 응답 예시

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "content": [
    {
      "caseId": "20000000-0000-4000-9000-000000000003",
      "caseStatus": "IN_REVIEW",
      "finalDisposition": null,
      "assigneeRef": "analyst_ref_demo_07",
      "relatedTransactionCount": 3,
      "createdAt": "2026-07-24T01:15:33Z",
      "lastChangedAt": "2026-07-24T02:05:10Z"
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
  "traceId": "trace_demo_case_list_01"
}
```

### 5.5 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 조회 성공. 결과가 없으면 빈 `content` 반환 |
| `400 Bad Request` | Enum, 식별자, 시각, 페이지 또는 정렬 형식 오류 |
| `422 Unprocessable Entity` | 시작 시각이 종료 시각보다 늦는 등 의미상 처리할 수 없는 필터 |
| `503 Service Unavailable` | 명확한 조회 Timeout 또는 저장소 가용성 장애 |
| `500 Internal Server Error` | 공개할 수 없는 예기치 않은 서버 오류 |

## 6. 사건 상세 조회

### 6.1 요청

```http
GET /api/v1/cases/{caseId}
```

요청 예:

```http
GET /api/v1/cases/20000000-0000-4000-9000-000000000003
```

### 6.2 응답 범위

응답은 다음 저장값과 집계값만 포함한다.

- `caseId`
- `caseStatus`
- `finalDisposition`
- opaque 담당자 참조값
- 생성·검토 시작·종료·마지막 변경 시각
- `concurrencyVersion`
- 연관 거래 수
- `traceId`

대표 거래·위험등급·사유, 조사 메모 수, 감사 요약, 전체 연관 거래와 AI 리포트는
포함하지 않는다.

### 6.3 성공 응답 예시

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "case": {
    "caseId": "20000000-0000-4000-9000-000000000003",
    "caseStatus": "IN_REVIEW",
    "finalDisposition": null,
    "assigneeRef": "analyst_ref_demo_07",
    "relatedTransactionCount": 3,
    "createdAt": "2026-07-24T01:15:33Z",
    "reviewStartedAt": "2026-07-24T01:25:00Z",
    "closedAt": null,
    "lastChangedAt": "2026-07-24T02:05:10Z",
    "concurrencyVersion": 4
  },
  "traceId": "trace_demo_case_detail_01"
}
```

nullable 필드는 JSON에 명시적으로 `null`을 반환한다. 내부 PK·FK와 내부 예외,
고객·계좌·기기 원문, 내부 snapshot·Provider payload는 반환하지 않는다.

### 6.4 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 사건 상세 조회 성공 |
| `400 Bad Request` | `caseId` 형식 오류 |
| `404 Not Found` | 해당 사건이 없음 |
| `503 Service Unavailable` | 명확한 조회 Timeout 또는 저장소 가용성 장애 |
| `500 Internal Server Error` | 공개할 수 없는 예기치 않은 서버 오류 |

## 7. 사건 연관 거래 조회

### 7.1 요청

```http
GET /api/v1/cases/{caseId}/transactions
```

요청 예:

```http
GET /api/v1/cases/case_demo_20260724_0031/transactions?page=0&size=20&sort=occurredAt,desc
```

### 7.2 응답 항목

| 필드 | 설명 |
| --- | --- |
| `transactionId` | 거래 업무 식별자 |
| `transactionType` | 거래 유형 |
| `amount` | 소수점 문자열 형식의 거래 금액 |
| `currencyCode` | 통화 코드 |
| `occurredAt` | 거래 발생 시각 |
| `processingStatus` | 거래 처리 상태 |
| `riskLevel` | 현재 채택된 탐지 결과의 위험 등급 |
| `riskResponseOutcome` | Spring Boot가 적용한 Mock 위험 대응 결과 |
| `adoptedDetectionResultId` | 채택된 탐지 결과 업무 식별자 |
| `representative` | 사건의 대표 거래 여부 후보 |
| `linkReason` | 사건 연결 사유 또는 제한된 Reason Code 후보 |
| `linkedAt` | 사건 연결 시각 |

실제 고객번호, 실제 계좌번호, 원문 IP와 인증정보는 반환하지 않는다.

### 7.3 성공 응답 예시

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "caseId": "case_demo_20260724_0031",
  "content": [
    {
      "transactionId": "91a2b3c4-d5e6-47f8-9a0b-1c2d3e4f5003",
      "transactionType": "ACCOUNT_TRANSFER",
      "amount": "1250000",
      "currencyCode": "KRW",
      "occurredAt": "2026-07-24T01:15:30Z",
      "processingStatus": "ADDITIONAL_AUTH_REQUIRED",
      "riskLevel": "HIGH",
      "riskResponseOutcome": "ADDITIONAL_AUTH_REQUIRED",
      "adoptedDetectionResultId": "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101",
      "representative": true,
      "linkReason": "NEW_DEVICE_HIGH_AMOUNT",
      "linkedAt": "2026-07-24T01:15:33Z"
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
  "traceId": "trace_demo_case_transactions_01"
}
```

### 7.4 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 조회 성공. 연관 거래가 없으면 빈 `content` 반환 |
| `400 Bad Request` | 식별자, 페이지 또는 정렬 형식 오류 |
| `404 Not Found` | 해당 사건이 없음 |
| `422 Unprocessable Entity` | 의미상 처리할 수 없는 페이지 또는 정렬 조건 |
| `500 Internal Server Error` | 공개할 수 없는 예기치 않은 서버 오류 |

## 8. 사건 상태 변경

### 8.1 요청

```http
PATCH /api/v1/cases/{caseId}/status
Content-Type: application/json
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `targetStatus` | string | 필수 | 변경할 사건 상태 |
| `assigneeRef` | string 또는 null | 조건부 필수 | `OPEN` → `IN_REVIEW` 전이에서 지정할 담당자 참조값 |
| `reasonCode` | string | 필수 | 전이와 정확히 일치하는 승인된 구조화 사유 |
| `expectedVersion` | integer | 필수 | 클라이언트가 조회한 사건의 `concurrencyVersion` |

요청 예:

```json
{
  "targetStatus": "IN_REVIEW",
  "assigneeRef": "20000000-0000-4000-9000-000000000007",
  "reasonCode": "CASE_REVIEW_STARTED",
  "expectedVersion": 0
}
```

### 8.2 처리 규칙

- 현재 상태와 `targetStatus` 조합을 검증한다.
- 일반 상태 변경 API에서는 다음 세 전이만 허용한다.
  - `OPEN` → `IN_REVIEW`
  - `IN_REVIEW` → `ADDITIONAL_INFORMATION_REQUIRED`
  - `ADDITIONAL_INFORMATION_REQUIRED` → `IN_REVIEW`
- `OPEN` → `IN_REVIEW` 전이에서는 요청의 `assigneeRef`가 필수이다. 값이 없으면 사건을 변경하지 않고 `422 Unprocessable Entity`와 `ASSIGNEE_REQUIRED`를 반환한다.
- `OPEN` → `IN_REVIEW` 요청의 `assigneeRef` 형식 또는 승인된 허용 목록 검증에 실패하면 `422 Unprocessable Entity`와 `INVALID_ASSIGNEE_REF`를 반환한다.
- 신규 write `assigneeRef`는 정확히 36 ASCII 문자인 canonical lowercase UUID v4만
  허용하며 trim·소문자 변환 등 정규화를 수행하지 않는다.
- `IN_REVIEW` → `ADDITIONAL_INFORMATION_REQUIRED`에는
  `CASE_ADDITIONAL_INFORMATION_REQUESTED`, 복귀에는 `CASE_REVIEW_RESUMED`를
  사용한다. 이 두 전이에 `assigneeRef` 필드를 함께 보내면 `422` 의미 오류다.
- `ADDITIONAL_INFORMATION_REQUIRED` → `IN_REVIEW` 전이에서는 사건에 기존 담당자가 있어야 한다. 담당자가 없으면 사건을 변경하지 않고 `422 Unprocessable Entity`와 `ASSIGNEE_REQUIRED`를 반환한다.
- `reviewStartedAt`은 다음 조건에서만 현재 시각으로 설정한다.

```text
reviewStartedAt == null
+ 최초 OPEN → IN_REVIEW
→ 현재 시각 기록
```

- 이후 `ADDITIONAL_INFORMATION_REQUIRED` → `IN_REVIEW` 전이에서는 기존 `reviewStartedAt`을 변경하지 않는다.
- `OPEN` → `IN_REVIEW`의 담당자 지정, 상태 변경, 최초 `reviewStartedAt` 기록, `lastChangedAt` 변경, `concurrencyVersion` 증가와 AuditLog 기록은 하나의 업무 정합성 경계에서 처리한다.
- 현재 상태와 `targetStatus`가 같으면 무변경 성공으로 처리하지 않는다. `409 Conflict`와 `CASE_STATUS_CONFLICT`를 반환하고 사건 현재값과 `concurrencyVersion`을 변경하지 않는다.
- `targetStatus = CLOSED` 요청은 적용하지 않고 `409 Conflict`와 `CASE_STATUS_CONFLICT`를 반환한다.
- 이미 `CLOSED`인 사건에 대한 상태 변경은 `409 Conflict`와 `CASE_ALREADY_CLOSED`를 반환한다.
- 허용되지 않은 다른 전이는 `409 Conflict`와 `CASE_STATUS_CONFLICT`를 반환한다.
- 성공한 상태 변경과 AuditLog 기록은 일부만 성공하지 않도록 같은 업무 정합성 경계에서 처리한다.
- 조회 후 `expectedVersion`을 업무 충돌보다 먼저 비교한다. 명시적 flush에서 실제
  `@Version` 증가를 확정한 후 성공 응답을 만들며 row lock·자동 retry·`If-Match`는
  사용하지 않는다.

### 8.3 성공 응답 예시

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "caseId": "10000000-0000-4000-9000-000000000003",
  "caseStatus": "IN_REVIEW",
  "finalDisposition": null,
  "assigneeRef": "20000000-0000-4000-9000-000000000007",
  "reviewStartedAt": "2026-07-24T01:25:00Z",
  "closedAt": null,
  "lastChangedAt": "2026-07-24T01:25:00Z",
  "concurrencyVersion": 2,
  "traceId": "trace_demo_case_status_01"
}
```

### 8.4 허용되지 않은 전이 오류 예시

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "code": "CASE_STATUS_CONFLICT",
  "message": "현재 사건 상태에서는 요청한 상태로 변경할 수 없습니다.",
  "traceId": "trace_demo_case_status_conflict_01",
  "fieldErrors": []
}
```

실패·거부·stale 요청은 이번 구현에서 별도 감사 기록을 만들지 않는다.

### 8.5 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 허용된 상태 변경 성공 |
| `400 Bad Request` | JSON, 식별자, 필드 또는 버전 형식 오류 |
| `404 Not Found` | 해당 사건이 없음 |
| `409 Conflict` | 허용되지 않은 상태 전이, 종료 사건 변경 또는 동시성 충돌 |
| `422 Unprocessable Entity` | 담당자 없는 `IN_REVIEW` 전이, 잘못된 `assigneeRef` 또는 상태 변경 사유 등 업무 입력 조건을 충족하지 못함 |
| `503 Service Unavailable` | 저장소 timeout 또는 일시적 가용성 장애 |
| `500 Internal Server Error` | 공개할 수 없는 예기치 않은 서버 오류 |

## 9. 사건 담당자 변경

### 9.1 요청

```http
PATCH /api/v1/cases/{caseId}/assignee
Content-Type: application/json
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `assigneeRef` | string 또는 null | 필수 | 새 담당자. 명시적 null만 해제 명령 |
| `reasonCode` | string | 필수 | `CASE_ASSIGNEE_ASSIGNED`, `CASE_ASSIGNEE_CHANGED`, `CASE_ASSIGNEE_RELEASED` 중 현재/새 값과 일치하는 값 |
| `expectedVersion` | integer | 필수 | 클라이언트가 조회한 사건의 `concurrencyVersion` |

요청 예:

```json
{
  "assigneeRef": "20000000-0000-4000-9000-000000000012",
  "reasonCode": "CASE_ASSIGNEE_CHANGED",
  "expectedVersion": 5
}
```

### 9.2 처리 규칙

- `OPEN`에서는 별도 담당자 변경을 금지하고 최초 배정은 검토 시작과 함께 한다.
- `IN_REVIEW`에서는 변경만 허용하고 해제는 금지한다.
- `ADDITIONAL_INFORMATION_REQUIRED`에서는 배정·변경·해제를 허용한다. 담당자가
  null이면 다시 `IN_REVIEW`로 전이할 수 없다.
- 담당자 변경 API는 담당자만 변경하며 사건 상태를 암묵적으로 변경하지 않는다.
- `assigneeRef`는 실제 사용자 프로필이나 인증정보가 아닌 제한된 참조값을 사용한다.
- `assigneeRef` 누락은 `400 VALIDATION_ERROR`이며 명시적 null과 구분한다.
- `assigneeRef` 형식 또는 승인된 허용 목록 검증에 실패하면 `422 Unprocessable Entity`와 `INVALID_ASSIGNEE_REF`를 반환한다.
- 사용자·담당자 디렉터리와 인증 시스템이 아직 없으므로 잘못된 `assigneeRef`를 담당자 리소스 없음으로 해석해 `404 Not Found`로 확정하지 않는다.
- 현재 담당자, 새 담당자, 변경 사유와 변경 주체를 AuditLog에 기록한다.
- 같은 담당자 재요청은 `409 CASE_ASSIGNEE_CONFLICT`이며 무변경·무감사다.
- stale version이면서 같은 값이면 `409 CONCURRENT_MODIFICATION`을 우선한다.
- 담당자 변경, `lastChangedAt` 변경, `concurrencyVersion` 증가와 AuditLog 기록은 하나의 업무 정합성 경계에서 처리한다.
- 이미 `CLOSED`인 사건의 담당자 변경은 초기 범위에서 거부하고 `409 Conflict`와 `CASE_ALREADY_CLOSED`를 반환한다.

### 9.3 성공 응답 예시

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "caseId": "10000000-0000-4000-9000-000000000003",
  "caseStatus": "IN_REVIEW",
  "finalDisposition": null,
  "assigneeRef": "20000000-0000-4000-9000-000000000012",
  "reviewStartedAt": "2026-07-24T01:25:00Z",
  "closedAt": null,
  "lastChangedAt": "2026-07-24T02:25:40Z",
  "concurrencyVersion": 6,
  "traceId": "trace_demo_case_assignee_01"
}
```

### 9.4 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 담당자 변경 성공 |
| `400 Bad Request` | JSON, 식별자, 필드 또는 버전 형식 오류 |
| `404 Not Found` | 해당 사건이 없음 |
| `409 Conflict` | 종료 사건 변경, 담당자 업무 조건 또는 동시성 충돌 |
| `422 Unprocessable Entity` | 담당자가 없거나 `assigneeRef` 형식·허용 목록 또는 변경 사유 규칙을 충족하지 못함 |
| `503 Service Unavailable` | 저장소 timeout 또는 일시적 가용성 장애 |
| `500 Internal Server Error` | 공개할 수 없는 예기치 않은 서버 오류 |

## 10. 사건 종료와 최종 판정

### 10.1 요청

```http
POST /api/v1/cases/{caseId}/resolution
Content-Type: application/json
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `finalDisposition` | string | 필수 | `NORMAL`, `FALSE_POSITIVE`, `CONFIRMED_FRAUD` 중 하나 |
| `reasonCode` | string | 필수 | `CASE_RESOLUTION_COMPLETED`만 허용 |
| `expectedVersion` | integer | 필수 | 클라이언트가 조회한 사건의 `concurrencyVersion` |

요청 예:

```json
{
  "finalDisposition": "CONFIRMED_FRAUD",
  "reasonCode": "CASE_RESOLUTION_COMPLETED",
  "expectedVersion": 6
}
```

### 10.2 처리 규칙

- `Idempotency-Key`, `If-Match`, row lock, 자동 retry를 사용하지 않는다.
- 현재 `caseStatus = IN_REVIEW`이고 담당자와 최초 조사 시작 시각이 모두 있는 사건만 종료할 수 있다.
- `expectedVersion`을 현재 `concurrencyVersion`과 먼저 비교한다. stale 요청은 사건이 `CLOSED`인지와 판정이 같은지보다 먼저 `409 CONCURRENT_MODIFICATION`으로 거부한다.
- `finalDisposition`은 필수이며 사건 상태 값과 혼합하지 않는다.
- 자유 텍스트 `reason`은 허용하지 않으며 `reasonCode=CASE_RESOLUTION_COMPLETED`만 허용한다.
- 최종 판정 설정, `caseStatus = CLOSED`, 하나의 마이크로초 정밀도 시각을 사용한 `closedAt = lastChangedAt`, 실제 JPA `concurrencyVersion` 증가와 AuditLog 기록을 하나의 REQUIRED 트랜잭션으로 처리한다.
- 일부 값만 반영된 종료 결과를 허용하지 않는다.
- 성공 응답 전에 FraudCase와 AuditLog를 명시적으로 flush하며 optimistic conflict 또는 감사 실패 시 사건·판정·시각·version·감사를 모두 rollback한다.
- `OPEN` 또는 `ADDITIONAL_INFORMATION_REQUIRED`에서 직접 종료하려는 요청은 `409 Conflict`와 `CASE_STATUS_CONFLICT`를 반환한다.
- 이미 `CLOSED`인 사건은 같은 판정과 다른 판정 모두 `409 Conflict`와 `CASE_ALREADY_CLOSED`를 반환한다.
- `finalDisposition`이 누락되거나 null이면 `422 Unprocessable Entity`와 `FINAL_DISPOSITION_REQUIRED`를 반환한다.
- 종료 사건 재개와 종료 후 최종 판정 변경은 초기 범위에서 제외한다.
- 세 판정은 모두 사건만 종료하며 Transaction, RiskLevel, RiskResponseOutcome, CaseTransaction과 AI 처리를 변경하지 않는다.
- 성공 종료만 검증된 USER UUID v4 actor의 `CASE_RESOLVED/CASE_RESOLUTION_COMPLETED` AuditLog를 정확히 1건 생성한다.

오류 우선순위는 요청 구조·타입·path → 사건 없음 → stale version → 이미 종료 → 금지 상태 → 판정·사유 업무 오류 → flush optimistic conflict → DB timeout → DB unavailable → 기타 내부 오류 순이다.

### 10.3 성공 응답 예시

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "caseId": "1a000000-0000-4000-9000-000000000001",
  "caseStatus": "CLOSED",
  "finalDisposition": "CONFIRMED_FRAUD",
  "assigneeRef": "2a000000-0000-4000-9000-000000000002",
  "reviewStartedAt": "2026-07-24T02:10:00.123456Z",
  "closedAt": "2026-07-24T03:10:00.123456Z",
  "lastChangedAt": "2026-07-24T03:10:00.123456Z",
  "concurrencyVersion": 7,
  "traceId": "trace_demo_case_resolution_01"
}
```

응답 `concurrencyVersion`은 실제 commit될 DB version과 일치한다. 같은 요청을 재전송해도 기존 응답을 replay하지 않으며 현재 version과 상태에 따라 충돌한다.

### 10.4 최종 판정 누락 오류 예시

```http
HTTP/1.1 422 Unprocessable Entity
Content-Type: application/json
```

```json
{
  "code": "FINAL_DISPOSITION_REQUIRED",
  "message": "사건 종료에는 최종 판정이 필요합니다.",
  "traceId": "trace_demo_resolution_required_01",
  "fieldErrors": [
    {
      "field": "finalDisposition",
      "code": "FINAL_DISPOSITION_REQUIRED",
      "reason": "finalDisposition is required"
    }
  ]
}
```

### 10.5 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 사건 종료 성공 |
| `400 Bad Request` | JSON 구조·타입, 식별자, `reasonCode`·`expectedVersion` 필수값 또는 버전 형식 오류 |
| `404 Not Found` | 해당 사건이 없음 |
| `409 Conflict` | stale/flush 동시성 충돌, 종료할 수 없는 현재 상태 또는 이미 종료된 사건 |
| `422 Unprocessable Entity` | 최종 판정 누락·지원되지 않는 판정 또는 다른 감사 사유 코드 |
| `503 Service Unavailable` | DB timeout 또는 일시적 가용성 장애 |
| `500 Internal Server Error` | 공개할 수 없는 예기치 않은 서버 오류 |

## 11. 조사 메모 생성

### 11.1 요청

```http
POST /api/v1/cases/{caseId}/notes
Content-Type: application/json
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `content` | string | 필수 | 원문 그대로 저장하는 plain text, Unicode code point 1..4,000 |
| `expectedVersion` | integer | 필수 | 조회한 사건의 0 이상 `concurrencyVersion` |

요청 예:

```json
{
  "content": "조사 메모 원문",
  "expectedVersion": 6
}
```

### 11.2 처리 규칙

- `IN_REVIEW`, `ADDITIONAL_INFORMATION_REQUIRED`만 작성할 수 있다. `OPEN`, `CLOSED`는 `409 NOTE_NOT_ALLOWED`이다.
- 사건 조회 후 stale `expectedVersion`을 상태보다 먼저 검사한다.
- 서버가 검증된 USER JWT `sub`로 `authorType=USER`, `authorRef=<canonical lowercase UUID v4>`를 설정한다. 요청의 `authorRef`, `actorType`, `actorId`와 모든 unknown·duplicate field는 `400`이다.
- 앞뒤 공백, CR/LF, Unicode 조합과 HTML·Markdown·SQL·script 문자열은 정규화·trim·실행·렌더링하지 않고 plain text 원문으로 보존한다.
- NUL과 CR/LF 이외 ISO 제어문자, 공백-only, 4,000 code point 초과는 `422`이다.
- `content`는 신뢰할 수 없는 plain text다. 클라이언트는 화면 출력 시 HTML/text
  escaping을 적용해야 하며 `innerHTML`, `dangerouslySetInnerHTML` 등으로 원문을 HTML로
  렌더링하면 안 된다. 서버가 원문을 실행·해석하지 않는다는 사실만으로 클라이언트 출력의
  안전이 보장되지는 않는다. 인증·RBAC는 구현되었지만 Frontend OIDC·권한 UI는 미구현이다.
- 사건 조회 → version → 상태 → content → 단일 `activityTime` → 부모 flush → 메모 insert·flush → 감사 append·flush 순서로 같은 REQUIRED 트랜잭션에서 처리한다.
- `Clock` 시각이 기존 `lastChangedAt` 이하이면 정확히 1 microsecond 뒤를 사용한다. 성공 시 `createdAt == lastChangedAt`, version은 정확히 1 증가한다.
- `InvestigationNote`는 append-only이며 수정·삭제 API가 없다. `Idempotency-Key` replay와 `correctionOfNoteId`도 구현하지 않는다.
- 메모에 실제 고객번호, 실제 계좌번호, 비밀번호, OTP, 인증 토큰과 불필요한 개인정보를 입력하지 않는다.
- `InvestigationNote` 물리 DB 계약은
  [`fraud-case-schema.md`](../04-database/fraud-case-schema.md)에 통합되어 있다.

### 11.3 성공 응답 예시

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "noteId": "10000000-0000-4000-8000-000000000001",
  "caseId": "20000000-0000-4000-8000-000000000002",
  "authorType": "USER",
  "authorRef": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
  "content": "조사 메모 원문",
  "createdAt": "2026-09-02T00:00:00.123456Z",
  "concurrencyVersion": 7,
  "traceId": "trace_demo_case_note_create_01"
}
```

현재 개별 메모 조회 API가 없으므로 초기 성공 응답에 `Location` 헤더를 반환하지 않는다. 향후 `GET /api/v1/cases/{caseId}/notes/{noteId}`가 별도 승인으로 추가될 경우에만 최초 생성 응답에 개별 리소스 URI를 `Location`으로 제공하는 방안을 검토한다. 이번 문서에서는 개별 메모 조회 API를 추가하지 않는다.

### 11.4 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `201 Created` | 메모 생성 성공 |
| `400 Bad Request` | strict JSON, UUID, `expectedVersion` 또는 필드 형식 오류 |
| `404 Not Found` | 해당 사건이 없음 |
| `409 Conflict` | stale/flush 충돌 또는 작성 불가 상태 |
| `422 Unprocessable Entity` | content 업무 규칙 위반 |
| `503 Service Unavailable` | DB timeout 또는 unavailable |
| `500 Internal Server Error` | 공개할 수 없는 예기치 않은 서버 오류 |

## 12. 조사 메모 조회

### 12.1 요청

```http
GET /api/v1/cases/{caseId}/notes
```

요청 예:

```http
GET /api/v1/cases/20000000-0000-4000-8000-000000000002/notes?page=0&size=20&sort=createdAt,asc
```

### 12.2 성공 응답 예시

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "items": [
    {
      "noteId": "10000000-0000-4000-8000-000000000001",
      "caseId": "20000000-0000-4000-8000-000000000002",
      "authorType": "SYSTEM",
      "authorRef": "finguardops-backend",
      "content": "추가 확인 자료의 도착 여부를 검토했습니다.",
      "createdAt": "2026-07-24T02:20:00Z"
    }
  ],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 2,
    "totalPages": 1,
    "first": true,
    "last": true
  },
  "traceId": "trace_demo_case_notes_01"
}
```

기본값은 `page=0`, `size=20`, `sort=createdAt,asc`이고 최대 size는 100이다.
정렬은 `createdAt,asc|desc`만 허용하며 내부 `id`를 같은 방향 tie-breaker로 사용하되
응답에 노출하지 않는다. 사건 존재를 먼저 확인하므로 존재하는 사건의 빈 목록은 `200`이다.

### 12.3 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 조회 성공. 메모가 없으면 빈 `items` 반환 |
| `400 Bad Request` | 식별자, 페이지 또는 정렬 형식 오류 |
| `404 Not Found` | 해당 사건이 없음 |
| `422 Unprocessable Entity` | 음수 page 또는 허용 범위 밖 size |
| `503 Service Unavailable` | DB timeout 또는 unavailable |
| `500 Internal Server Error` | 공개할 수 없는 예기치 않은 서버 오류 |

## 13. 사건 감사 로그 조회

### 13.1 요청

```http
GET /api/v1/cases/{caseId}/audit-logs
```

`caseId`는 canonical lowercase UUID v4와 RFC 4122 variant만 허용한다. trim,
lowercase 변환이나 다른 형식의 coercion은 하지 않는다.

### 13.2 페이지네이션

| 쿼리 파라미터 | 기본값 | 설명 |
| --- | --- | --- |
| `page` | `0` | 0 이상의 페이지 번호 |
| `size` | `20` | 1~100의 페이지 크기 |
| `sort` | `changedAt,desc` | `changedAt,asc` 또는 `changedAt,desc` |

요청 예:

```http
GET /api/v1/cases/5c671624-8714-4bd7-871a-a9445e6f453e/audit-logs?page=0&size=20&sort=changedAt,desc
```

`page`, `size`, `sort` 이외의 query parameter와 모든 scalar 중복은 `400`이다.
페이지·크기 숫자 형식 오류는 `400`, 음수 page와 size 범위 위반은 `422`다.
동일 `changedAt`에서는 내부 `id`를 요청 방향과 같은 최종 정렬키로 사용하되
응답에는 노출하지 않는다. 범위 밖 page는 `200`과 빈 `content`다.

### 13.3 응답 항목

| 필드 | 설명 |
| --- | --- |
| `action` | 승인된 사건 감사 작업 |
| `reasonCode` | action과 일치하는 구조화 사유 코드 |
| `actorType` | `SYSTEM` 또는 `USER` |
| `changedAt` | 변경 시각 |
| `beforeSummary` | action별 승인된 변경 전 projection 또는 null |
| `afterSummary` | action별 승인된 변경 후 projection 또는 null |
| `metadata` | 빈 object 또는 `CASE_NOTE_CREATED`의 `{noteId}` |

action별 projection은 다음과 같다.

| action | beforeSummary | afterSummary | metadata |
| --- | --- | --- | --- |
| `CASE_CREATED` | null | `{caseStatus}` | `{}` |
| `CASE_TRANSACTION_LINKED` | null | `{linked}` | `{}` |
| `CASE_STATUS_CHANGED` | `{caseStatus, assigneeRef}` | `{caseStatus, assigneeRef}` | `{}` |
| `CASE_ASSIGNEE_CHANGED` | `{caseStatus, assigneeRef}` | `{caseStatus, assigneeRef}` | `{}` |
| `CASE_RESOLVED` | `{caseStatus, assigneeRef}` | `{caseStatus, assigneeRef, finalDisposition}` | `{}` |
| `CASE_NOTE_CREATED` | null | null | `{noteId}` |

workflow summary의 `assigneeRef`는 미배정이면 명시적 JSON null이다. 저장된 detection
metadata는 현재 DB 계약에 맞는지 검증하지만 응답에는 공개하지 않는다. 내부 `id`,
`auditId`, `actorId`, 저장 당시 `traceId`, target·context 식별자, JSONB 원문,
조사 메모·거래·Provider·AI payload는 반환하지 않는다.

Issue #223의 USER actor가 저장되어도 응답은 위와 같이 `actorType`만 공개하고
`actorId`는 공개하지 않는다. email·display name도 공개하지 않으며 SYSTEM actorId 공개
여부는 후속 API 변경에서 명시적으로 결정한다.

### 13.4 성공 응답 예시

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "caseId": "5c671624-8714-4bd7-871a-a9445e6f453e",
  "content": [
    {
      "action": "CASE_CREATED",
      "reasonCode": "CASE_REQUIRED_BY_RISK_POLICY",
      "actorType": "SYSTEM",
      "changedAt": "2026-07-24T02:05:10Z",
      "beforeSummary": null,
      "afterSummary": {
        "caseStatus": "OPEN"
      },
      "metadata": {}
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
  "traceId": "trace_demo_case_audit_list_01"
}
```

응답 최상위 `traceId`만 현재 조회 요청을 추적한다. 저장 당시 과거 `traceId`는
반환하지 않는다.

### 13.5 읽기 전용 원칙

- 감사 로그는 조회만 제공한다.
- 감사 로그 수정·삭제 API를 제공하지 않는다.
- 기존 감사 행을 덮어쓰지 않는다.
- 먼저 외부 `caseId`로 사건 존재를 확인하고, 존재할 때만
  `targetType=FRAUD_CASE AND targetId=:caseId` Page/count query를 실행한다.
- 조회 Service transaction은 read-only이며 Entity relationship이나 사건 collection을
  추가로 로딩하지 않는다.
- 지원하지 않는 action·reason·context·JSON이 한 행이라도 있으면 skip 또는 raw
  fallback 없이 해당 페이지 전체를 안전한 `500 INTERNAL_ERROR`로 처리한다.
- 정정이 필요한 경우 기존 행을 변경하기보다 별도 정정 기록을 추가하는 방향을 후속 감사 정책에서 검토한다.
- 감사 로그의 접근 범위와 보존 기간은 사용자 결정 사항이다.

### 13.6 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 조회 성공. 감사 이력이 없으면 빈 `content` 반환 |
| `400 Bad Request` | 식별자·페이지·크기·정렬 형식, unknown query 또는 scalar 중복 |
| `404 Not Found` | 해당 사건이 없음 |
| `422 Unprocessable Entity` | 음수 page 또는 허용 범위 밖 size |
| `503 Service Unavailable` | 명확한 DB timeout 또는 unavailable |
| `500 Internal Server Error` | 공개할 수 없는 예기치 않은 서버 오류 |

## 14. 동시성 처리

### 14.1 적용 API

다음 변경 요청은 `expectedVersion`을 필수로 사용한다.

```text
PATCH /api/v1/cases/{caseId}/status
PATCH /api/v1/cases/{caseId}/assignee
POST  /api/v1/cases/{caseId}/resolution
```

`expectedVersion`은 클라이언트가 사건을 조회했을 때 받은 `concurrencyVersion`이다. 업무 내용 버전, 탐지 결과 버전, Rule 버전과 혼합하지 않는다.

세 API는 body의 `expectedVersion`을 사용하며 `If-Match`는 도입하지 않는다.

### 14.2 충돌 처리

- 서버는 요청의 `expectedVersion`과 현재 사건의 `concurrencyVersion`을 비교한다.
- 값이 다르면 변경을 적용하지 않는다.
- `409 Conflict`와 `CONCURRENT_MODIFICATION`을 반환한다.
- 먼저 저장된 사건 상태, 담당자, 최종 판정과 감사 기록을 오래된 요청으로 덮어쓰지 않는다.
- 서버는 오래된 요청을 자동으로 덮어쓰거나 무조건 재시도하지 않는다.
- 클라이언트는 최신 사건을 다시 조회하고 사용자 입력을 보존한 뒤 재입력 또는 승인된 병합 절차를 수행해야 한다.
- 사건은 JPA `@Version`을 사용한다. 일반 `caseId` 조회와 version 비교 후 Entity 업무
  메서드를 적용하고 명시적 flush에서 실제 version 증가를 확정한다.
- row lock과 자동 retry를 추가하지 않는다. stale·충돌·거부·validation 실패 요청은
  사건을 변경하거나 AuditLog를 생성하지 않는다.

### 14.3 충돌 오류 예시

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "code": "CONCURRENT_MODIFICATION",
  "message": "사건이 다른 요청에 의해 변경되었습니다. 최신 정보를 다시 조회해 주세요.",
  "traceId": "trace_demo_case_concurrency_01",
  "fieldErrors": []
}
```

현재 `concurrencyVersion`을 충돌 응답에 직접 포함할지와 최신 사건 조회 경로를 추가할지는 사용자 결정 사항이다.

## 15. 감사 원칙

Issue #209, #211, #213에서 성공한 다음 명령은 AuditLog를 정확히 1건 생성한다.

- 사건 상태 변경
- 담당자 배정·변경·해제
- 사건 최종 판정·종료
- 사건 조사 메모 생성

read-only, 401, 403, validation 실패, stale version, 같은 상태·담당자, 금지 전이,
종료 사건 변경, 업무 상태 거부, DB 오류, optimistic conflict와 rollback loser는 업무
AuditLog를 생성하지 않는다. 거부 요청 별도 감사는 현재 구현하지 않는다.

감사 기록에는 다음 정보를 포함하는 방향을 사용한다.

```text
actorType
+ actorId
+ changedAt
+ targetType
+ targetId
+ action
+ beforeValueSummary
+ afterValueSummary
+ reasonCode
+ transactionId?
+ caseId
+ traceId?
```

V7은 성공한 네 action만 저장하며 자유 텍스트 사유와 거부 감사는 허용하지 않는다.
V11은 성공한 사건 상태·담당자 action과 승인 reasonCode 6개를 확장하고 V12는 성공 종료
action과 reasonCode를 확장한다. Issue #223의 네 사용자 write는 실제 USER actor를 쓴다. 각
action의 summary·metadata exact schema는
[`audit-log-schema.md`](../04-database/audit-log-schema.md)를 따른다.

사건 상태·담당자·종결·조사 메모 성공 write는 검증된 USER principal의
canonical lowercase UUID v4 `sub`를 USER `actorId`로 기록한다. 거래 처리·자동 위험 대응,
사건 자동 생성·거래 연결, RuleVersion·복구 one-shot과 기타 자동 처리는 SYSTEM을
유지한다. V14 CHECK는 USER와 기존 SYSTEM 조합을 모두 허용하고 교차 조합·NULL·비정규
UUID를 거부한다.

### 15.1 성공한 업무 변경의 트랜잭션 경계

- 상태·담당자 변경에서는 FraudCase 현재값, `lastChangedAt`과 해당하는
  `reviewStartedAt`, `concurrencyVersion` 증가와 AuditLog 1건을 같은 REQUIRED
  트랜잭션으로 처리한다.
- resolution에서는 최종 판정, `CLOSED`, `closedAt=lastChangedAt`, 실제 version과
  AuditLog 1건을 같은 REQUIRED 트랜잭션에서 처리한다.
- 적용 대상 중 일부만 저장되는 결과를 허용하지 않는다. AuditLog 저장에 실패한 성공 변경을 정상 완료로 확정하지 않는다.
- USER actor도 사건 변경과 같은 transaction·flush·rollback 경계를 사용한다.

### 15.2 거부된 요청의 감사 경계

다음 거부 요청은 FraudCase 현재값과 `concurrencyVersion`을 변경하지 않고 AuditLog도
생성하지 않는다.

- 허용되지 않은 상태 전이와 같은 상태 요청
- 동시성 충돌
- 종료 사건 변경 시도
- 잘못된 정정 메모
- 담당자 없는 `IN_REVIEW` 전이

거부 요청을 보존하는 별도 commit 감사 경계는 현재 구현하지 않으며 후속 승인 범위다.
감사 로그에는 실제 고객번호, 실제 계좌번호, 비밀번호, OTP, 인증 토큰, 원문 IP,
전체 프롬프트와 LLM 원문 입출력을 기록하지 않는다.

## 16. 오류 계약

### 16.1 공통 구조

오류 응답은 [`api-conventions.md`](./api-conventions.md)의 공통 구조를 따른다.

```json
{
  "code": "ERROR_CODE",
  "message": "민감정보를 제외한 오류 설명",
  "traceId": "trace_demo_error_01",
  "fieldErrors": []
}
```

내부 예외 메시지, SQL, 스택 트레이스, 인증정보와 외부 Provider 원문을 반환하지 않는다.

### 16.2 오류 코드

| 오류 코드 | 의미 | HTTP 상태 |
| --- | --- | --- |
| `RESOURCE_NOT_FOUND` | 요청한 사건 등 식별된 API 리소스를 찾을 수 없음 | `404 Not Found` |
| `IDEMPOTENCY_KEY_CONFLICT` | 같은 멱등성 키가 다른 정규화 요청에 재사용됨 | `409 Conflict` |
| `CASE_STATUS_CONFLICT` | 현재 사건 상태에서 요청한 상태 전이 또는 종료를 허용할 수 없음 | `409 Conflict` |
| `CASE_ASSIGNEE_CONFLICT` | 현재 사건 상태 또는 담당자 값에서 요청한 담당자 변경을 허용할 수 없음 | `409 Conflict` |
| `CASE_ALREADY_CLOSED` | 이미 종료된 사건에 허용되지 않은 변경을 요청함 | `409 Conflict` |
| `FINAL_DISPOSITION_REQUIRED` | 사건 종료에 필요한 최종 판정이 없음 | `422 Unprocessable Entity` |
| `ASSIGNEE_REQUIRED` | `IN_REVIEW`에 필요한 담당자가 없음 | `422 Unprocessable Entity` |
| `INVALID_ASSIGNEE_REF` | `assigneeRef` 형식 또는 승인된 허용 목록 검증에 실패함 | `422 Unprocessable Entity` |
| `CONCURRENT_MODIFICATION` | 요청 버전과 현재 사건 버전이 달라 변경할 수 없음 | `409 Conflict` |
| `DEPENDENCY_TIMEOUT` | 저장소 요청이 제한 시간 안에 완료되지 않음 | `503 Service Unavailable` |
| `DEPENDENCY_UNAVAILABLE` | 저장소를 일시적으로 사용할 수 없음 | `503 Service Unavailable` |
| `NOTE_NOT_ALLOWED` | 현재 사건 상태에서 새 메모를 추가할 수 없음 | `409 Conflict` |
| `INVALID_CORRECTION_NOTE` | 정정 대상 메모가 없거나 같은 사건에 속하지 않음 | `422 Unprocessable Entity` |
| `VALIDATION_ERROR` | JSON, 필드, Enum, 식별자, 시각, 페이지 또는 도메인 입력 검증 실패 | 형식 오류는 `400 Bad Request`, 의미상 오류는 `422 Unprocessable Entity` |
| `INTERNAL_ERROR` | 공개할 수 없는 예기치 않은 서버 오류 | `500 Internal Server Error` |

담당자 관련 오류는 다음처럼 구분한다.

```text
사건 없음
→ 404 RESOURCE_NOT_FOUND

assigneeRef 형식 또는 허용 목록 오류
→ 422 INVALID_ASSIGNEE_REF

IN_REVIEW에 필요한 담당자가 없음
→ 422 ASSIGNEE_REQUIRED
```

사용자·담당자 디렉터리와 인증 시스템이 아직 없으므로 잘못된 담당자 참조를 무조건 `404 Not Found`로 확정하지 않는다. 사건 상태 전이에는 공통 `STATE_TRANSITION_NOT_ALLOWED` 대신 사건 API의 안정적인 코드인 `CASE_STATUS_CONFLICT`를 사용한다.

### 16.3 리소스 없음 예시

```http
HTTP/1.1 404 Not Found
Content-Type: application/json
```

```json
{
  "code": "RESOURCE_NOT_FOUND",
  "message": "요청한 사건을 찾을 수 없습니다.",
  "traceId": "trace_demo_case_not_found_01",
  "fieldErrors": []
}
```

## 17. 민감정보 처리

- 실제 고객번호와 실제 계좌번호 원문을 요청·응답·오류 예시에 사용하지 않는다.
- 사건 목록과 상세에는 조사에 필요한 최소 요약만 반환한다.
- 연관 거래 응답에는 고객·계좌 원문을 반환하지 않는다.
- 담당자와 작성자는 제한된 `assigneeRef`, `authorRef`로 표현한다. 감사 `USER`
  `actorId`는 검증된 JWT `sub`인 canonical lowercase UUID v4다.
- email, display name, 내부 DB PK, 사용자명, 사번과 전화번호를 `actorId`로 저장하지
  않는다. 별도 `user_id` claim으로 다시 매핑하지 않는다.
- 참조값 자체에 개인정보, 인증정보 또는 업무상 불필요한 의미를 포함하지 않는다.
- 메모와 변경 사유에 불필요한 고객·계좌 원문이나 인증정보를 기록하지 않는다.
- 감사 로그의 변경 전후 요약은 허용된 필드와 마스킹·축약 값만 사용한다.
- 오류 메시지와 `fieldErrors.reason`에 내부 예외나 민감정보를 포함하지 않는다.
- 승인된 인증·인가·접근 제어 계약은 [`security-architecture.md`](../02-architecture/security-architecture.md)를 따른다. 마스킹, 암호화, 해시와 보존 기간의 구현은 후속 범위다.

## 18. 사용자 결정 필요 항목

### 18.1 사건 상태와 담당자

- 상태 변경 요청을 통한 초기 수동 배정 외에 담당자 자동 배정을 도입할지
- local/test 단계의 `assigneeRef` 형식과 승인된 허용 목록을 어디에서 관리할지
- 향후 사용자·담당자 디렉터리 도입 시 참조값 검증과 현재 계약을 연결하는 방식
- `IN_REVIEW`가 아닌 사건에서 기존 담당자 값을 유지하거나 해제할 수 있는 후속 정책

### 18.2 승인된 인증 actor와 현재 구현

인증 adapter는 검증된 JWT로 immutable authenticated principal을 만들고, Service의 단일
provider가 SecurityContext에서 USER subject를 명령당 한 번 읽는다. request body·query·임의 header의
`authorRef`, `actorType`, `actorId`는 신뢰하지 않는다. production Security chain을
profile로 끄지 않으며 test는 ephemeral asymmetric key 또는 test-only decoder와 실제 claim
validator를 사용한다.

조사 메모와 사건 write는 USER actor를 사용한다. 자동 사건 생성·거래·Rule/AI·복구 writer는
`SYSTEM/finguardops-backend`를 유지하고 local/test Mock Actor header를 도입하지 않는다.

### 18.3 조회와 표시

- 페이지 시작값, 기본·최대 크기와 API별 허용 정렬 필드
- 시각 범위 끝값 포함 여부와 복수 Enum 필터 방식
- 대표 위험 등급, 대표 탐지 사유와 대표 거래 선정 규칙
- 사건 상세에 최근 감사 요약을 포함할지와 포함 범위
- `caseId`, 담당자·작성자 참조값과 변경 사유의 형식·길이

### 18.4 조사 메모와 감사

- 향후 정정 메모를 도입할 경우 참조 깊이와 표시·감사 정책
- 향후 `GET /api/v1/cases/{caseId}/notes/{noteId}` 승인 시 최초 생성 응답에 개별 리소스 `Location`을 제공할지
- 감사 로그 접근 범위, 정정 절차와 보존 기간

### 18.5 동시성과 오류

- 충돌 응답에 현재 `concurrencyVersion` 또는 최신 사건 조회 경로를 포함할지
- 충돌 후 사용자 입력 보존·재입력·병합 UX
- `fieldErrors.code`의 최종 목록과 버전 관리 방식

## 19. 제외 범위

- OpenAPI YAML
- 사건 병합·분리 API
- 종료 사건 재개 API
- 조사 메모 개별 상세·수정·삭제 API
- 조사 메모 `correctionOfNoteId`와 `Idempotency-Key` replay
- 감사 로그 수정·삭제 API
- AI 리포트 API
- AI 사용량 API
- 플랫폼 운영 API
- 사건 write·조사 메모 `USER` actor 구현
- CORS와 외부 API 보안 구현
- Kafka 이벤트 API
- 실제 고객 제재, 거래 승인·인증·차단

## 20. 후속 AI 리포트 API 항목

AI 리포트 API는 이번 계약과 분리된 후속 승인 문서에서 정의한다. 후속 문서는 최소한 다음 계약을 다뤄야 한다.

- 사건별 AI 리포트 요청·상태·상세 조회
- 정상 완료, 템플릿 fallback 완료와 실패 상태의 구분
- 정확 일치 조건과 기존 결과 반환
- 재시도와 재생성 구분
- 재생성 권한·횟수·버전과 기존 결과 보존
- 탐지 결과·프롬프트·모델 버전 연결
- 모델, 토큰, 지연시간, 비용과 `aiRequestId` 추적
- AI 리포트 실패가 사건 상태와 최종 판정을 변경하지 않는 경계
- 실제 고객·계좌·인증정보와 불필요한 원문을 LLM에 전달하지 않는 원칙

이번 문서에서는 AI 리포트의 경로, 요청·응답 DTO, 상태 코드와 구현을 정의하지 않는다.
