# 사건·조사 메모·감사 API

## 1. 문서 목적

이 문서는 FinGuardOps의 FDS 분석 담당자가 사건을 조회하고, 담당자와 조사 상태를 관리하며, 최종 판정을 확정하고, 조사 메모와 감사 이력을 조회하기 위한 Spring Boot REST API 계약을 정의한다.

이 계약은 이후 Spring Boot Controller, 요청·응답 DTO, Validation, Service, 테스트와 OpenAPI 구현의 기준이다. API 공통 표현, 시간, 금액, 페이지네이션, 오류 응답과 추적 원칙은 [`api-conventions.md`](./api-conventions.md)를 따른다.

이 문서는 API 계약이며 공개 사건 API 구현 완료 내역이 아니다. Issue #154에서
`FraudCase`·`CaseTransaction` JPA 영속 기반과 Flyway V6, 사건·첫 거래 연결 내부
Service가 구현되었다. Issue #156에서는 append-only `AuditLog` JPA 영속 기반과
Flyway V7만 구현되었다. Controller·API DTO, 조사 상태 전이, 실제 업무 변경과
AuditLog의 연결, 인증·인가는 구현되지 않았다. 문서의 `case_demo_...` 값은 읽기
쉬운 예시이며 실제 영속 `caseId`와 `auditId`는 UUID v4를 사용한다.

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

페이지 시작값, 기본·최대 크기와 API별 허용 정렬 필드는 사용자 결정 사항이다. 정렬 필드는 서버 허용 목록으로 제한하며 동일한 정렬값에는 업무 식별자 등 안정적인 보조 정렬키를 적용한다.

### 3.6 사건 종료·조사 메모 멱등성

다음 POST API는 필수 `Idempotency-Key` 헤더를 사용한다.

```text
POST /api/v1/cases/{caseId}/resolution
POST /api/v1/cases/{caseId}/notes
```

공통 처리 규칙은 다음과 같다.

- 최초 요청은 각 API의 정상 처리 규칙에 따라 처리한다.
- 같은 키와 같은 정규화 요청의 처리가 완료되었으면 새 상태 변경이나 CaseNote를 만들지 않고 `200 OK`로 기존 결과를 반환한다.
- 같은 키와 같은 정규화 요청이 처리 중이면 새 처리를 시작하지 않고 `409 Conflict`와 `IDEMPOTENCY_REQUEST_IN_PROGRESS`를 반환한다.
- 같은 키에 다른 요청 내용이 오면 변경을 적용하지 않고 `409 Conflict`와 `IDEMPOTENCY_KEY_CONFLICT`를 반환한다.
- 응답 유실 후 같은 요청을 재전송해도 사건 종료, `concurrencyVersion` 변경, CaseNote와 AuditLog가 중복 생성되지 않아야 한다.
- 멱등성 확인은 완료된 동일 요청의 기존 결과를 식별할 수 있어야 하며, 단순한 현재 사건 상태 검사만으로 대체하지 않는다.
- `Idempotency-Key`가 누락되거나 형식이 올바르지 않으면 업무 변경을 시작하지 않고 `400 Bad Request`와 `VALIDATION_ERROR`를 반환한다.
- 구체적인 `IdempotencyRecord`, 정규화 방식, 요청 지문과 완료 응답 저장 구조는 후속 JPA 설계에서 확정한다.

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

## 5. 사건 목록 조회

### 5.1 요청

```http
GET /api/v1/cases
```

### 5.2 필터와 페이지네이션 후보

| 쿼리 파라미터 | 설명 |
| --- | --- |
| `caseStatus` | 사건 상태 |
| `finalDisposition` | 최종 판정 |
| `representativeRiskLevel` | 대표 위험 등급 |
| `assigneeRef` | 담당자 참조값 |
| `createdAtFrom` | 사건 생성 시각 범위 시작, UTC ISO-8601 |
| `createdAtTo` | 사건 생성 시각 범위 끝, UTC ISO-8601 |
| `lastChangedAtFrom` | 마지막 변경 시각 범위 시작, UTC ISO-8601 |
| `lastChangedAtTo` | 마지막 변경 시각 범위 끝, UTC ISO-8601 |
| `transactionId` | 관련 거래 업무 식별자 |
| `page` | 페이지 번호 |
| `size` | 페이지 크기 |
| `sort` | 정렬 조건 |

요청 예:

```http
GET /api/v1/cases?caseStatus=IN_REVIEW&representativeRiskLevel=HIGH&page=0&size=20&sort=lastChangedAt,asc
```

시각 범위 끝값의 포함 여부, 복수 Enum 필터, 기본 정렬과 허용 정렬 필드는 사용자 결정 사항이다.

### 5.3 목록 항목

목록 응답은 사건 대기열에 필요한 다음 요약만 포함한다.

| 필드 | 설명 |
| --- | --- |
| `caseId` | 사건 업무 식별자 |
| `caseStatus` | 현재 사건 상태 |
| `finalDisposition` | 최종 판정. 조사 중에는 null 가능 |
| `representativeRiskLevel` | 대표 위험 등급 후보 |
| `representativeReasonCodes` | 대표 탐지 사유 코드의 제한된 목록 후보 |
| `assigneeRef` | 담당자 참조값. 미배정이면 null 가능 |
| `relatedTransactionCount` | 연관 거래 수 |
| `createdAt` | 사건 생성 시각 |
| `lastChangedAt` | 사건 마지막 변경 시각 |

전체 연관 거래, 조사 메모, 감사 로그와 AI 리포트 본문·상태는 목록에 포함하지 않는다.

### 5.4 성공 응답 예시

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "content": [
    {
      "caseId": "case_demo_20260724_0031",
      "caseStatus": "IN_REVIEW",
      "finalDisposition": null,
      "representativeRiskLevel": "HIGH",
      "representativeReasonCodes": [
        "NEW_DEVICE_HIGH_AMOUNT",
        "EXTERNAL_RISK_MATCH"
      ],
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
| `500 Internal Server Error` | 공개할 수 없는 예기치 않은 서버 오류 |

## 6. 사건 상세 조회

### 6.1 요청

```http
GET /api/v1/cases/{caseId}
```

요청 예:

```http
GET /api/v1/cases/case_demo_20260724_0031
```

### 6.2 응답 범위

응답 후보는 다음을 포함한다.

- `caseId`
- `caseStatus`
- `finalDisposition`
- 대표 위험 등급과 대표 탐지 사유
- 담당자 참조값
- 생성·검토 시작·종료·마지막 변경 시각
- `concurrencyVersion`
- 대표 거래 요약
- 연관 거래 수
- 조사 메모 수
- 최근 감사 이력 요약 후보
- `traceId`

전체 연관 거래, 전체 조사 메모, 전체 감사 로그와 AI 리포트는 포함하지 않는다. 각 전체 목록은 별도 API에서 조회한다.

### 6.3 성공 응답 예시

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "case": {
    "caseId": "case_demo_20260724_0031",
    "caseStatus": "IN_REVIEW",
    "finalDisposition": null,
    "representativeRiskLevel": "HIGH",
    "representativeReasons": [
      {
        "reasonCode": "NEW_DEVICE_HIGH_AMOUNT",
        "displayDescription": "신규 기기 등록 후 고객 기준선보다 큰 금액의 이체가 요청되었습니다."
      },
      {
        "reasonCode": "EXTERNAL_RISK_MATCH",
        "displayDescription": "비식별 대상 참조값에 외부 위험 신호가 확인되었습니다."
      }
    ],
    "assigneeRef": "analyst_ref_demo_07",
    "createdAt": "2026-07-24T01:15:33Z",
    "reviewStartedAt": "2026-07-24T01:25:00Z",
    "closedAt": null,
    "lastChangedAt": "2026-07-24T02:05:10Z",
    "concurrencyVersion": 4
  },
  "representativeTransaction": {
    "transactionId": "91a2b3c4-d5e6-47f8-9a0b-1c2d3e4f5003",
    "transactionType": "ACCOUNT_TRANSFER",
    "amount": "1250000",
    "currencyCode": "KRW",
    "occurredAt": "2026-07-24T01:15:30Z",
    "processingStatus": "ADDITIONAL_AUTH_REQUIRED",
    "riskLevel": "HIGH",
    "riskResponseOutcome": "ADDITIONAL_AUTH_REQUIRED",
    "adoptedDetectionResultId": "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101"
  },
  "relatedTransactionCount": 3,
  "noteCount": 2,
  "recentAuditSummary": {
    "auditLogId": "8bf3c9a2-7d0e-4a51-9b36-1c2d3e4f5a60",
    "action": "CASE_CREATED",
    "actorType": "SYSTEM",
    "actorId": "finguardops-backend",
    "changedAt": "2026-07-24T02:05:10Z",
    "reasonCode": "CASE_REQUIRED_BY_RISK_POLICY"
  },
  "traceId": "trace_demo_case_detail_01"
}
```

대표 거래와 최근 감사 요약이 없을 때의 null 규칙, 대표 거래·대표 위험 등급의 선정 규칙과 최근 감사 요약 포함 여부는 사용자 결정 사항이다.

### 6.4 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 사건 상세 조회 성공 |
| `400 Bad Request` | `caseId` 형식 오류 |
| `404 Not Found` | 해당 사건이 없음 |
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

| 필드 | 타입 후보 | 필수 후보 | 설명 |
| --- | --- | --- | --- |
| `targetStatus` | string | 필수 | 변경할 사건 상태 |
| `assigneeRef` | string 또는 null | 조건부 필수 | `OPEN` → `IN_REVIEW` 전이에서 지정할 담당자 참조값 |
| `reason` | string | 필수 | 상태 변경 사유 |
| `expectedVersion` | integer | 필수 | 클라이언트가 조회한 사건의 `concurrencyVersion` |

요청 예:

```json
{
  "targetStatus": "IN_REVIEW",
  "assigneeRef": "analyst_ref_demo_07",
  "reason": "담당자를 지정하고 사건 검토를 시작합니다.",
  "expectedVersion": 1
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
- `ADDITIONAL_INFORMATION_REQUIRED` → `IN_REVIEW` 전이에서는 사건에 기존 담당자가 있어야 한다. 담당자가 없으면 사건을 변경하지 않고 `422 Unprocessable Entity`와 `ASSIGNEE_REQUIRED`를 반환한다.
- `reviewStartedAt`은 다음 조건에서만 현재 시각으로 설정한다.

```text
reviewStartedAt == null
+ 최초 OPEN → IN_REVIEW
→ 현재 시각 기록
```

- 이후 `ADDITIONAL_INFORMATION_REQUIRED` → `IN_REVIEW` 전이에서는 기존 `reviewStartedAt`을 변경하지 않는다.
- `OPEN` → `IN_REVIEW`의 담당자 지정, 상태 변경, 최초 `reviewStartedAt` 기록, `lastChangedAt` 변경, `concurrencyVersion` 증가와 AuditLog 기록은 하나의 업무 정합성 경계에서 처리한다.
- 현재 상태와 `targetStatus`가 같으면 무변경 성공으로 처리하지 않는다. `409 Conflict`와 `CASE_STATUS_CONFLICT`를 반환하고 사건 현재값과 `concurrencyVersion`을 변경하지 않으며 거부 요청은 감사 기록 후보로 남긴다.
- `targetStatus = CLOSED` 요청은 적용하지 않고 `409 Conflict`와 `CASE_STATUS_CONFLICT`를 반환한다.
- 이미 `CLOSED`인 사건에 대한 상태 변경은 `409 Conflict`와 `CASE_ALREADY_CLOSED`를 반환한다.
- 허용되지 않은 다른 전이는 `409 Conflict`와 `CASE_STATUS_CONFLICT`를 반환한다.
- 성공한 상태 변경과 AuditLog 기록은 일부만 성공하지 않도록 같은 업무 정합성 경계에서 처리한다.

### 8.3 성공 응답 예시

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "caseId": "case_demo_20260724_0031",
  "caseStatus": "IN_REVIEW",
  "finalDisposition": null,
  "assigneeRef": "analyst_ref_demo_07",
  "reviewStartedAt": "2026-07-24T01:25:00Z",
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
  "fieldErrors": [
    {
      "field": "targetStatus",
      "code": "CASE_TRANSITION_NOT_ALLOWED",
      "reason": "사건 종료는 resolution API를 사용해야 합니다."
    }
  ]
}
```

거부된 전이 시도도 변경 주체, 현재 상태, 요청 상태, 사유, `caseId`와 `traceId`를 민감정보 없이 감사 기록 대상으로 남긴다.

### 8.5 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 허용된 상태 변경 성공 |
| `400 Bad Request` | JSON, 식별자, 필드 또는 버전 형식 오류 |
| `404 Not Found` | 해당 사건이 없음 |
| `409 Conflict` | 허용되지 않은 상태 전이, 종료 사건 변경 또는 동시성 충돌 |
| `422 Unprocessable Entity` | 담당자 없는 `IN_REVIEW` 전이, 잘못된 `assigneeRef` 또는 상태 변경 사유 등 업무 입력 조건을 충족하지 못함 |
| `500 Internal Server Error` | 공개할 수 없는 예기치 않은 서버 오류 |

## 9. 사건 담당자 변경

### 9.1 요청

```http
PATCH /api/v1/cases/{caseId}/assignee
Content-Type: application/json
```

| 필드 | 타입 후보 | 필수 후보 | 설명 |
| --- | --- | --- | --- |
| `assigneeRef` | string | 필수 | 새 담당자 참조값 |
| `reason` | string | 필수 | 담당자 변경 사유 |
| `expectedVersion` | integer | 필수 | 클라이언트가 조회한 사건의 `concurrencyVersion` |

요청 예:

```json
{
  "assigneeRef": "analyst_ref_demo_12",
  "reason": "관련 거래 유형 담당자에게 재배정합니다.",
  "expectedVersion": 5
}
```

### 9.2 처리 규칙

- 별도 담당자 변경 API는 이미 `IN_REVIEW`인 사건의 담당자 재배정에 사용한다.
- 담당자 변경 API는 담당자만 변경하며 사건 상태를 암묵적으로 변경하지 않는다.
- `assigneeRef`는 실제 사용자 프로필이나 인증정보가 아닌 제한된 참조값을 사용한다.
- `assigneeRef`가 없으면 `422 Unprocessable Entity`와 `ASSIGNEE_REQUIRED`를 반환한다.
- `assigneeRef` 형식 또는 승인된 허용 목록 검증에 실패하면 `422 Unprocessable Entity`와 `INVALID_ASSIGNEE_REF`를 반환한다.
- 사용자·담당자 디렉터리와 인증 시스템이 아직 없으므로 잘못된 `assigneeRef`를 담당자 리소스 없음으로 해석해 `404 Not Found`로 확정하지 않는다.
- 현재 담당자, 새 담당자, 변경 사유와 변경 주체를 AuditLog에 기록한다.
- 담당자 변경, `lastChangedAt` 변경, `concurrencyVersion` 증가와 AuditLog 기록은 하나의 업무 정합성 경계에서 처리한다.
- 이미 `CLOSED`인 사건의 담당자 변경은 초기 범위에서 거부하고 `409 Conflict`와 `CASE_ALREADY_CLOSED`를 반환한다.

### 9.3 성공 응답 예시

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "caseId": "case_demo_20260724_0031",
  "caseStatus": "IN_REVIEW",
  "assigneeRef": "analyst_ref_demo_12",
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
| `500 Internal Server Error` | 공개할 수 없는 예기치 않은 서버 오류 |

## 10. 사건 종료와 최종 판정

### 10.1 요청

```http
POST /api/v1/cases/{caseId}/resolution
Content-Type: application/json
Idempotency-Key: <required>
```

| 필드 | 타입 후보 | 필수 후보 | 설명 |
| --- | --- | --- | --- |
| `finalDisposition` | string | 필수 | `NORMAL`, `FALSE_POSITIVE`, `CONFIRMED_FRAUD` 중 하나 |
| `reason` | string | 필수 | 최종 판정과 종료의 조사 근거 |
| `expectedVersion` | integer | 필수 | 클라이언트가 조회한 사건의 `concurrencyVersion` |

요청 예:

```json
{
  "finalDisposition": "CONFIRMED_FRAUD",
  "reason": "거래 당사자가 요청을 부인했고 비식별 위험 신호와 연관 거래가 확인되었습니다.",
  "expectedVersion": 6
}
```

### 10.2 처리 규칙

- 필수 `Idempotency-Key`를 3.6절의 공통 규칙에 따라 확인한다.
- 최초 요청은 현재 `caseStatus = IN_REVIEW`인 사건만 종료할 수 있다.
- 같은 키와 같은 정규화 요청의 완료된 재전송은 현재 사건이 이미 `CLOSED`이고 요청의 `expectedVersion`이 과거 값이더라도 최초 종료 결과를 식별해 `200 OK`로 반환한다. 새 종료, `concurrencyVersion` 증가와 AuditLog를 만들지 않는다.
- 완료된 동일 멱등 요청의 판별은 일반적인 현재 상태·동시성 검증보다 먼저 수행해 단순 `CASE_ALREADY_CLOSED` 또는 `CONCURRENT_MODIFICATION`으로 처리하지 않는다.
- 같은 키와 같은 요청이 처리 중이면 새 종료를 시작하지 않고 `409 Conflict`와 `IDEMPOTENCY_REQUEST_IN_PROGRESS`를 반환한다.
- 같은 키에 다른 요청이 오면 `409 Conflict`와 `IDEMPOTENCY_KEY_CONFLICT`를 반환한다.
- 새로운 키의 요청이나 기존 완료 결과와 일치하지 않는 요청은 현재 상태와 `expectedVersion`을 정상 검증한다.
- `finalDisposition`은 필수이며 사건 상태 값과 혼합하지 않는다.
- 최종 판정 설정, `caseStatus = CLOSED`, `closedAt`과 `lastChangedAt` 기록, `concurrencyVersion` 증가와 AuditLog 기록을 하나의 업무 트랜잭션으로 처리한다.
- 일부 값만 반영된 종료 결과를 허용하지 않는다.
- `OPEN` 또는 `ADDITIONAL_INFORMATION_REQUIRED`에서 직접 종료하려는 요청은 `409 Conflict`와 `CASE_STATUS_CONFLICT`를 반환한다.
- 완료된 동일 멱등 요청이 아닌 상태에서 이미 종료된 사건에는 `409 Conflict`와 `CASE_ALREADY_CLOSED`를 반환한다.
- `finalDisposition`이 누락되거나 null이면 `422 Unprocessable Entity`와 `FINAL_DISPOSITION_REQUIRED`를 반환한다.
- 종료 사건 재개와 종료 후 최종 판정 변경은 초기 범위에서 제외한다.
- 최종 판정은 FDS 분석 담당자의 서버 사용자 문맥을 기준으로 확정하며 FastAPI나 LLM이 설정하지 않는다.

### 10.3 성공 응답 예시

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "caseId": "case_demo_20260724_0031",
  "caseStatus": "CLOSED",
  "finalDisposition": "CONFIRMED_FRAUD",
  "assigneeRef": "analyst_ref_demo_12",
  "closedAt": "2026-07-24T03:10:00Z",
  "lastChangedAt": "2026-07-24T03:10:00Z",
  "concurrencyVersion": 7,
  "traceId": "trace_demo_case_resolution_01"
}
```

최초 종료와 완료된 동일 멱등 요청 재전송은 같은 응답 구조를 사용한다. 재전송 응답은 기존 확정 결과이며 새 업무 변경이 아니다.

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
      "code": "REQUIRED_FOR_CASE_RESOLUTION",
      "reason": "지원되는 최종 판정을 입력해야 합니다."
    }
  ]
}
```

### 10.5 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 최초 종료 성공 또는 완료된 동일 멱등 요청에 기존 결과 반환 |
| `400 Bad Request` | JSON, 필수 `Idempotency-Key`, 식별자, Enum 또는 버전 형식 오류 |
| `404 Not Found` | 해당 사건이 없음 |
| `409 Conflict` | 멱등성 키 지문 충돌, 동일 멱등 요청 처리 중, 종료할 수 없는 현재 상태, 이미 종료된 사건 또는 동시성 충돌 |
| `422 Unprocessable Entity` | 최종 판정 또는 종료 사유 등 업무 종료 조건을 충족하지 못함 |
| `500 Internal Server Error` | 공개할 수 없는 예기치 않은 서버 오류 |

## 11. 조사 메모 생성

### 11.1 요청

```http
POST /api/v1/cases/{caseId}/notes
Content-Type: application/json
Idempotency-Key: <required>
```

| 필드 | 타입 후보 | 필수 후보 | 설명 |
| --- | --- | --- | --- |
| `content` | string | 필수 | 조사 메모 내용 |
| `correctionOfNoteId` | string 또는 null | 선택 | 정정 대상 원 메모 식별자 |

요청 예:

```json
{
  "content": "이전 메모의 확인 시각을 정정합니다. 추가 자료는 2026-07-24T02:40:00Z에 확인했습니다.",
  "correctionOfNoteId": "note_demo_20260724_0201"
}
```

### 11.2 처리 규칙

- 필수 `Idempotency-Key`를 3.6절의 공통 규칙에 따라 확인한다.
- CaseNote는 append-only로 관리한다.
- 최초 요청은 새 CaseNote를 생성하고 `201 Created`로 결과를 반환한다.
- 같은 키와 같은 정규화 요청의 완료된 재전송은 새 메모를 만들지 않고 `200 OK`로 최초 생성된 CaseNote 결과를 반환한다.
- 같은 키와 같은 요청이 처리 중이면 새 메모를 만들지 않고 `409 Conflict`와 `IDEMPOTENCY_REQUEST_IN_PROGRESS`를 반환한다.
- 같은 키에 다른 요청이 오면 `409 Conflict`와 `IDEMPOTENCY_KEY_CONFLICT`를 반환한다.
- 응답 유실 후 재전송되더라도 동일 메모, 정정 메모와 AuditLog를 중복 생성하지 않는다.
- 기존 메모를 직접 수정하거나 물리 삭제하지 않는다.
- 정정이 필요하면 새 메모를 추가하고 `correctionOfNoteId`로 원 메모를 참조한다.
- `correctionOfNoteId`가 가리키는 메모는 존재해야 하며 같은 사건에 속해야 한다.
- 존재하지 않거나 다른 사건의 메모를 참조하면 `422 Unprocessable Entity`와 `INVALID_CORRECTION_NOTE`를 반환하고 사건 현재값과 `concurrencyVersion`을 변경하지 않는다.
- 작성자 `authorRef`는 클라이언트 요청 본문에서 받거나 임의로 신뢰하지 않고 서버 사용자 문맥에서 결정한다.
- 인증·인가가 구현되지 않은 현재 단계에서는 작성자와 변경 주체를 local/test 환경의 서버 사용자 문맥에서 공급하며 구체 방식은 사용자 결정 사항이다.
- 초기 권장 정책은 `CLOSED` 사건을 읽기 전용으로 유지하는 것이다. 종료 사건에 새 메모를 추가하려는 요청은 `409 Conflict`와 `NOTE_NOT_ALLOWED`를 반환한다.
- 최초 메모 생성, `FraudCase.lastChangedAt` 변경, `concurrencyVersion` 증가와 AuditLog 기록은 하나의 업무 트랜잭션으로 처리하며 일부만 저장되는 결과를 허용하지 않는다.
- 메모 생성과 정정 메모 생성은 AuditLog 기록 대상이다.
- 메모에 실제 고객번호, 실제 계좌번호, 비밀번호, OTP, 인증 토큰과 불필요한 개인정보를 입력하지 않는다.

### 11.3 성공 응답 예시

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "noteId": "note_demo_20260724_0202",
  "caseId": "case_demo_20260724_0031",
  "authorRef": "analyst_ref_demo_12",
  "content": "이전 메모의 확인 시각을 정정합니다. 추가 자료는 2026-07-24T02:40:00Z에 확인했습니다.",
  "correctionOfNoteId": "note_demo_20260724_0201",
  "createdAt": "2026-07-24T02:45:00Z",
  "concurrencyVersion": 7,
  "traceId": "trace_demo_case_note_create_01"
}
```

현재 개별 메모 조회 API가 없으므로 초기 성공 응답에 `Location` 헤더를 반환하지 않는다. 향후 `GET /api/v1/cases/{caseId}/notes/{noteId}`가 별도 승인으로 추가될 경우에만 최초 생성 응답에 개별 리소스 URI를 `Location`으로 제공하는 방안을 검토한다. 이번 문서에서는 개별 메모 조회 API를 추가하지 않는다.

### 11.4 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `201 Created` | 일반 메모 또는 정정 메모 최초 생성 성공 |
| `200 OK` | 완료된 동일 멱등 요청에 최초 생성된 메모 결과 반환 |
| `400 Bad Request` | JSON, 필수 `Idempotency-Key`, 식별자 또는 필드 형식 오류 |
| `404 Not Found` | 해당 사건이 없음 |
| `409 Conflict` | 멱등성 키 지문 충돌, 동일 멱등 요청 처리 중 또는 종료 사건 등 메모를 추가할 수 없는 현재 사건 상태 |
| `422 Unprocessable Entity` | 빈 내용, 잘못된 정정 참조 등 메모 업무 규칙 위반 |
| `500 Internal Server Error` | 공개할 수 없는 예기치 않은 서버 오류 |

## 12. 조사 메모 조회

### 12.1 요청

```http
GET /api/v1/cases/{caseId}/notes
```

요청 예:

```http
GET /api/v1/cases/case_demo_20260724_0031/notes?page=0&size=20&sort=createdAt,asc
```

### 12.2 성공 응답 예시

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "caseId": "case_demo_20260724_0031",
  "content": [
    {
      "noteId": "note_demo_20260724_0201",
      "authorRef": "analyst_ref_demo_07",
      "content": "추가 확인 자료의 도착 여부를 검토했습니다.",
      "correctionOfNoteId": null,
      "createdAt": "2026-07-24T02:20:00Z"
    },
    {
      "noteId": "note_demo_20260724_0202",
      "authorRef": "analyst_ref_demo_12",
      "content": "이전 메모의 확인 시각을 정정합니다. 추가 자료는 2026-07-24T02:40:00Z에 확인했습니다.",
      "correctionOfNoteId": "note_demo_20260724_0201",
      "createdAt": "2026-07-24T02:45:00Z"
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

메모는 생성 순서를 안정적으로 확인할 수 있어야 하며 정정 메모를 원 메모 대신 덮어써서 반환하지 않는다.

### 12.3 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 조회 성공. 메모가 없으면 빈 `content` 반환 |
| `400 Bad Request` | 식별자, 페이지 또는 정렬 형식 오류 |
| `404 Not Found` | 해당 사건이 없음 |
| `422 Unprocessable Entity` | 의미상 처리할 수 없는 페이지 또는 정렬 조건 |
| `500 Internal Server Error` | 공개할 수 없는 예기치 않은 서버 오류 |

## 13. 사건 감사 로그 조회

### 13.1 요청

```http
GET /api/v1/cases/{caseId}/audit-logs
```

### 13.2 필터와 페이지네이션 후보

| 쿼리 파라미터 | 설명 |
| --- | --- |
| `action` | 변경 작업 |
| `actorType` | 변경 주체 유형 |
| `actorId` | `SYSTEM`은 `finguardops-backend`, `USER`는 내부 사용자 UUID v4 |
| `changedAtFrom` | 변경 시각 범위 시작, UTC ISO-8601 |
| `changedAtTo` | 변경 시각 범위 끝, UTC ISO-8601 |
| `transactionId` | 관련 거래 업무 식별자 |
| `traceId` | 관련 요청 추적 식별자 |
| `page` | 페이지 번호 |
| `size` | 페이지 크기 |
| `sort` | 정렬 조건 |

요청 예:

```http
GET /api/v1/cases/case_demo_20260724_0031/audit-logs?action=CASE_CREATED&page=0&size=20&sort=changedAt,desc
```

V7 물리 모델의 승인된 `action` 값은 다음 네 가지로 제한한다.

```text
CASE_CREATED
CASE_TRANSACTION_LINKED
TRANSACTION_RISK_RESPONSE_APPLIED
TRANSACTION_STATUS_CHANGED
```

사건 상태·담당자·판정·메모 변경과 거부 감사 action은 이 API 초안의 후속 후보일
뿐 V7 Enum에 포함되지 않는다. 도입하려면 별도 승인과 additive Migration이 필요하다.

### 13.3 응답 항목

| 필드 | 설명 |
| --- | --- |
| `auditLogId` | 감사 로그 업무 식별자 |
| `actorType` | `SYSTEM` 또는 `USER` |
| `actorId` | `SYSTEM`이면 `finguardops-backend`, `USER`이면 canonical lowercase 내부 사용자 업무 UUID v4 |
| `changedAt` | 변경 시각 |
| `targetType` | 변경 대상 유형 |
| `targetId` | 변경 대상 식별자 |
| `action` | 변경 작업 |
| `beforeValueSummary` | 변경 전 값의 제한된 요약 |
| `afterValueSummary` | 변경 후 값의 제한된 요약 |
| `reasonCode` | 승인된 구조화 사유 코드. 자유 텍스트 사유를 저장하지 않음 |
| `transactionId` | 관련 거래 식별자. 없으면 null 가능 |
| `caseId` | 관련 사건 식별자 |
| `traceId` | 관련 처리 흐름 추적 식별자 |

변경 전후 값은 감사에 필요한 필드만 포함하며 민감 원문 전체를 복제하지 않는다.

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
      "auditLogId": "8bf3c9a2-7d0e-4a51-9b36-1c2d3e4f5a60",
      "actorType": "SYSTEM",
      "actorId": "finguardops-backend",
      "changedAt": "2026-07-24T02:05:10Z",
      "targetType": "FRAUD_CASE",
      "targetId": "5c671624-8714-4bd7-871a-a9445e6f453e",
      "action": "CASE_CREATED",
      "beforeValueSummary": null,
      "afterValueSummary": {
        "caseStatus": "OPEN"
      },
      "reasonCode": "CASE_REQUIRED_BY_RISK_POLICY",
      "transactionId": "91a2b3c4-d5e6-47f8-9a0b-1c2d3e4f5003",
      "caseId": "5c671624-8714-4bd7-871a-a9445e6f453e",
      "traceId": "trace_demo_case_created_00"
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

응답 최상위 `traceId`는 현재 조회 요청을 추적하고, 각 감사 항목의 `traceId`는 과거 변경 요청을 추적한다.

### 13.5 읽기 전용 원칙

- 감사 로그는 조회만 제공한다.
- 감사 로그 수정·삭제 API를 제공하지 않는다.
- 기존 감사 행을 덮어쓰지 않는다.
- 정정이 필요한 경우 기존 행을 변경하기보다 별도 정정 기록을 추가하는 방향을 후속 감사 정책에서 검토한다.
- 감사 로그의 접근 범위와 보존 기간은 사용자 결정 사항이다.

### 13.6 상태 코드

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 조회 성공. 감사 이력이 없으면 빈 `content` 반환 |
| `400 Bad Request` | 식별자, 필터, 시각, 페이지 또는 정렬 형식 오류 |
| `404 Not Found` | 해당 사건이 없음 |
| `422 Unprocessable Entity` | 의미상 처리할 수 없는 시각 범위 또는 조회 조건 |
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

사건 종료 API의 완료된 동일 멱등 요청 재전송은 멱등성 결과를 먼저 확인한다. 최초 요청과 같은 `expectedVersion`이 현재 버전보다 오래되었더라도 기존 종료 결과를 반환하며, 새로운 키 또는 다른 요청에는 일반 동시성 검증을 적용한다.

### 14.2 충돌 처리

- 서버는 요청의 `expectedVersion`과 현재 사건의 `concurrencyVersion`을 비교한다.
- 값이 다르면 변경을 적용하지 않는다.
- `409 Conflict`와 `CONCURRENT_MODIFICATION`을 반환한다.
- 먼저 저장된 사건 상태, 담당자, 최종 판정과 감사 기록을 오래된 요청으로 덮어쓰지 않는다.
- 서버는 오래된 요청을 자동으로 덮어쓰거나 무조건 재시도하지 않는다.
- 클라이언트는 최신 사건을 다시 조회하고 사용자 입력을 보존한 뒤 재입력 또는 승인된 병합 절차를 수행해야 한다.
- 충돌한 요청도 변경 주체, 요청 작업, 요청 버전, 현재 버전, `caseId`와 `traceId`를 민감정보 없이 감사 기록 대상으로 남긴다.
- JPA `@Version`, 잠금 방식과 트랜잭션 격리 수준은 이 문서에서 확정하지 않는다.

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
  "fieldErrors": [
    {
      "field": "expectedVersion",
      "code": "VERSION_MISMATCH",
      "reason": "요청 버전이 현재 사건 버전과 일치하지 않습니다."
    }
  ]
}
```

현재 `concurrencyVersion`을 충돌 응답에 직접 포함할지와 최신 사건 조회 경로를 추가할지는 사용자 결정 사항이다.

## 15. 감사 원칙

다음 작업과 거부 결과는 반드시 감사 기록 대상이다.

- 사건 상태 변경
- 담당자 지정과 변경
- 최종 판정 설정과 사건 종료
- 일반 조사 메모 생성
- 정정 메모 생성
- 허용되지 않은 상태 전이와 같은 상태 요청
- 동시성 충돌
- 종료된 사건에 대한 거부된 상태·담당자·메모·판정 변경
- 잘못된 정정 메모 요청
- 담당자 없는 `IN_REVIEW` 전이 요청

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
각 action의 summary·metadata exact schema는
[`audit-log-schema.md`](../04-database/audit-log-schema.md)를 따른다.

### 15.1 성공한 업무 변경의 트랜잭션 경계

- 상태 변경, 담당자 변경과 사건 종료에서는 FraudCase 현재값 변경, `lastChangedAt`·`reviewStartedAt`·`closedAt` 중 해당 값의 변경, `concurrencyVersion` 증가와 AuditLog 기록을 같은 업무 트랜잭션으로 처리한다.
- 조사 메모 생성에서는 CaseNote 생성, FraudCase의 `lastChangedAt` 변경, `concurrencyVersion` 증가와 AuditLog 기록을 같은 업무 트랜잭션으로 처리한다.
- 적용 대상 중 일부만 저장되는 결과를 허용하지 않는다. AuditLog 저장에 실패한 성공 변경을 정상 완료로 확정하지 않는다.
- 완료된 동일 멱등 요청 재전송은 기존 결과를 반환하는 조회 성격의 처리이며 FraudCase, CaseNote, `concurrencyVersion`과 AuditLog를 다시 변경하거나 생성하지 않는다.

### 15.2 거부된 요청의 감사 경계

다음 거부 요청은 FraudCase 현재값, CaseNote와 `concurrencyVersion`을 변경하지 않는다.

- 허용되지 않은 상태 전이와 같은 상태 요청
- 동시성 충돌
- 종료 사건 변경 시도
- 잘못된 정정 메모
- 담당자 없는 `IN_REVIEW` 전이

거부 감사 기록은 실패한 업무 변경 트랜잭션과 함께 rollback되어 사라지지 않아야 한다. 따라서 업무 변경을 반영하지 않으면서 거부 결과를 보존할 수 있는 별도의 커밋 가능한 감사 경계가 필요하다.

구체적인 Spring Transaction 전파 방식, 예외 처리와 저장 구현은 이번 문서에서 확정하지 않는다. 감사 로그에는 실제 고객번호, 실제 계좌번호, 비밀번호, OTP, 인증 토큰, 원문 IP, 전체 프롬프트와 LLM 원문 입출력을 기록하지 않는다.

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
| `CASE_ALREADY_CLOSED` | 이미 종료된 사건에 허용되지 않은 변경을 요청함 | `409 Conflict` |
| `FINAL_DISPOSITION_REQUIRED` | 사건 종료에 필요한 최종 판정이 없음 | `422 Unprocessable Entity` |
| `ASSIGNEE_REQUIRED` | `IN_REVIEW`에 필요한 담당자가 없음 | `422 Unprocessable Entity` |
| `INVALID_ASSIGNEE_REF` | `assigneeRef` 형식 또는 승인된 허용 목록 검증에 실패함 | `422 Unprocessable Entity` |
| `CONCURRENT_MODIFICATION` | 요청 버전과 현재 사건 버전이 달라 변경할 수 없음 | `409 Conflict` |
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
- 담당자와 작성자는 제한된 `assigneeRef`, `authorRef`로 표현하고, 감사 `USER`
  `actorId`는 내부 사용자 업무 UUID v4로만 표현한다.
- 외부 인증 Provider subject, 사용자명, 이메일, 사번, 전화번호 원문은
  `actorId`로 저장하지 않는다. 향후 인증 계층이 외부 subject를 내부 UUID
  v4로 매핑해 전달하며, 실제 `USER` 감사 연결은 현재 미구현이다.
- 참조값 자체에 개인정보, 인증정보 또는 업무상 불필요한 의미를 포함하지 않는다.
- 메모와 변경 사유에 불필요한 고객·계좌 원문이나 인증정보를 기록하지 않는다.
- 감사 로그의 변경 전후 요약은 허용된 필드와 마스킹·축약 값만 사용한다.
- 오류 메시지와 `fieldErrors.reason`에 내부 예외나 민감정보를 포함하지 않는다.
- 실제 인증·인가, 접근 제어, 마스킹, 암호화, 해시와 보존 기간은 사용자 승인 후 별도 보안 설계에서 확정한다.

## 18. 사용자 결정 필요 항목

### 18.1 사건 상태와 담당자

- 상태 변경 요청을 통한 초기 수동 배정 외에 담당자 자동 배정을 도입할지
- local/test 단계의 `assigneeRef` 형식과 승인된 허용 목록을 어디에서 관리할지
- 향후 사용자·담당자 디렉터리 도입 시 참조값 검증과 현재 계약을 연결하는 방식
- `IN_REVIEW`가 아닌 사건에서 기존 담당자 값을 유지하거나 해제할 수 있는 후속 정책

### 18.2 현재 인증·인가 미구현 단계

- local/test 프로필에서만 허용되는 Mock Actor Provider
- production 프로필에서 Mock Actor 사용을 금지하는 검증 방식
- Mock Actor 참조값의 허용 목록과 테스트 격리 방식
- 실제 인증·인가 도입 시 서버 사용자 문맥으로 교체하는 경계

작성자와 변경 주체는 테스트·로컬 환경의 서버 사용자 문맥으로 공급한다. 요청 본문의
`authorRef`, `actorType`, `actorId`는 신뢰하지 않는다. 구체적인 요청 헤더명, Mock
Actor Provider 구현과 인증 코드는 이번 문서에서 확정하지 않는다.

### 18.3 조회와 표시

- 페이지 시작값, 기본·최대 크기와 API별 허용 정렬 필드
- 시각 범위 끝값 포함 여부와 복수 Enum 필터 방식
- 대표 위험 등급, 대표 탐지 사유와 대표 거래 선정 규칙
- 사건 상세에 최근 감사 요약을 포함할지와 포함 범위
- `caseId`, 담당자·작성자 참조값과 변경 사유의 형식·길이

### 18.4 조사 메모와 감사

- `CLOSED` 사건 읽기 전용 초기 권장안을 최종 정책으로 승인할지
- 메모 내용의 최대 길이와 허용 문자
- 정정 메모가 다른 정정 메모를 다시 참조할 수 있는지
- 향후 `GET /api/v1/cases/{caseId}/notes/{noteId}` 승인 시 최초 생성 응답에 개별 리소스 `Location`을 제공할지
- 감사 작업 `action` Enum과 거부 사유의 세분화 수준
- 감사 로그 접근 범위, 정정 절차와 보존 기간

### 18.5 멱등성·동시성과 오류

- `Idempotency-Key`의 형식, 작업별 범위와 보존 기간
- 정규화 요청 지문과 완료 응답의 저장 범위
- 처리 중 `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` 응답의 작업별 오류 문맥
- 충돌 응답에 현재 `concurrencyVersion` 또는 최신 사건 조회 경로를 포함할지
- 충돌 후 사용자 입력 보존·재입력·병합 UX
- `fieldErrors.code`의 최종 목록과 버전 관리 방식

## 19. 제외 범위

- Spring Boot 코드
- Controller, DTO, Validation과 Service 구현
- 공개 사건 API용 JPA 조회·변경 Service
- Issue #156 V7 이외의 사건 조사 PostgreSQL DDL
- V8 이후 사건 조사·감사 Flyway Migration과 action 확장
- OpenAPI YAML
- 구현된 사건 자동 생성 내부 경계의 거래 최종화·AuditLog 연결
- 사건 병합·분리 API
- 종료 사건 재개 API
- 조사 메모 수정·삭제 API
- 감사 로그 수정·삭제 API
- AI 리포트 API
- AI 사용량 API
- 플랫폼 운영 API
- 인증·인가 구현
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
