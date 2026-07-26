# AI 리포트·AI 사용량 API

## 1. 문서 목적

이 문서는 FinGuardOps에서 다음 두 사용자에게 제공하는 Spring Boot REST API 계약을 정의한다.

- FDS 분석 담당자: HIGH·CRITICAL 사건의 AI 리포트 생성을 요청하고 생성 상태와 조사 지원 결과를 조회한다.
- 플랫폼·클라우드 운영자: AI 요청별 Provider, 모델, 토큰, 지연시간, 추정 비용, 캐시와 fallback 결과를 조회하고 기간별 사용량·비용을 집계한다.

이 계약은 이후 Spring Boot Controller, 요청·응답 DTO, Validation, Service, FastAPI 연동, 테스트와 OpenAPI 구현의 기준이다.

API 공통 표현, 시간, 금액 정밀도, 페이지네이션, 오류 응답과 추적 원칙은 [`api-conventions.md`](./api-conventions.md)를 따른다. 거래·탐지 식별자와 사건 계약은 각각 [`transaction-detection-api.md`](./transaction-detection-api.md)와 [`case-audit-api.md`](./case-audit-api.md)를 따른다.

이 문서는 **API 계약 문서이며 구현 완료 내역이 아니다.** Java·Python 구현, PostgreSQL 스키마, Redis, Kafka, Worker와 실제 LLM Provider 연동이 완료되었다는 의미가 아니다.

## 2. 범위와 책임 경계

### 2.1 외부 처리 흐름

```text
FDS 분석 담당자
→ Spring Boot에 사건 AI 리포트 생성 요청
→ 202 Accepted와 aiRequestId 수신
→ 현재 리포트·생성 상태 조회
→ 조사 지원 정보 검토
```

```text
플랫폼·클라우드 운영자
→ AI 요청 단건 운영 상세 조회
→ 기간별 AI 사용량·비용 상세 목록 조회
→ 같은 조건의 집계 조회
```

외부 API는 비동기 생성·상태 조회 계약을 유지한다. 초기 내부 구현이 Spring Boot와 FastAPI 사이의 동기 호출을 사용하더라도 생성 요청 HTTP 연결에서 완성된 리포트를 기다려 반환하지 않는다.

### 2.2 Spring Boot 책임

Spring Boot는 다음 책임을 가진다.

- API 요청 형식, 권한과 서버 사용자 문맥을 검증한다.
- 사건과 탐지 결과의 존재 및 연결 관계를 확인한다.
- 사건의 대표 또는 채택 탐지 결과와 `detectionResultVersion`을 검증한다.
- HIGH·CRITICAL 대상 조건과 재생성 가능 조건을 검증한다.
- `Idempotency-Key`, 정확 일치 조건과 동시 요청을 확인한다.
- `aiRequestId`를 발급하고 생성 요청·상태·감사 이력을 관리한다.
- FastAPI를 호출하고 응답 구조와 버전을 검증한다.
- 현재 유효한 리포트를 선택한다.
- 리포트, AI 사용량, 토큰, 지연시간, 추정 비용과 실패 정보를 영속화한다.
- 업무 API 응답, 접근 제어와 감사 가능한 변경 기록을 제공한다.

Spring Boot는 FastAPI 또는 LLM 응답을 그대로 업무 원본으로 확정하지 않는다.

### 2.3 FastAPI AI Service 책임

FastAPI AI Service는 다음 책임을 가진다.

- 승인된 최소 리포트 입력 데이터를 처리한다.
- 모델을 라우팅하고 Prompt를 구성한다.
- LLM Provider를 호출한다.
- 출력 구조를 검증한다.
- LLM 호출 또는 출력 검증 실패 시 Rule·ML 기반 템플릿 fallback을 생성한다.
- Provider, 모델, 토큰, 지연시간, 오류와 fallback 결과를 Spring Boot에 반환한다.

FastAPI는 Spring Boot 업무 DB를 직접 수정하지 않는다. 사건 상태, 최종 판정, 거래 상태와 리포트의 최종 업무 상태를 확정하지 않는다.

### 2.4 Rule·ML과 생성형 AI의 역할

- Rule·ML은 위험 점수, 위험 등급, Reason Code와 탐지 근거를 계산한다.
- 생성형 AI는 사건 요약, 탐지 근거 요약, 행동 타임라인 요약과 담당자 확인 항목을 생성한다.
- 생성형 AI는 위험 점수, 위험 등급, 최종 판정과 사건 상태를 결정하지 않는다.
- 생성형 AI는 거래 승인·보류·차단과 고객 제재를 수행하지 않는다.
- AI 리포트는 담당자의 조사를 지원하며 최종 판정을 대체하지 않는다.
- AI 리포트 실패는 거래·탐지·사건 처리 실패와 구분한다.

## 3. 공통 정책

### 3.1 기본 경로와 표현

```text
/api/v1
```

- 요청과 응답은 UTF-8 JSON을 사용한다.
- JSON 필드명은 lowerCamelCase를 사용한다.
- 시간은 UTC ISO-8601 형식과 `Z` 접미사를 사용한다.
- 성공과 오류 응답에는 현재 HTTP 요청의 `traceId`를 반환한다.
- 내부 DB 식별자는 API에 노출하지 않는다.
- 예시 식별자와 데이터는 모두 가상 값이다.

### 3.2 주요 식별자

| 필드 | 의미 |
| --- | --- |
| `caseId` | 사건 업무 식별자 |
| `detectionResultId` | 저장·검증된 개별 탐지 결과 업무 식별자 |
| `detectionResultVersion` | 같은 거래의 탐지 분석 버전. 현재 정확 일치 기준에 사용 |
| `aiRequestId` | 하나의 외부 AI 리포트 생성 요청과 그 운영 결과를 연결하는 식별자 |
| `sourceAiRequestId` | 캐시 적중 시 재사용한 원본 리포트를 최초 생성한 요청 식별자 |
| `parentAiRequestId` | 서로 다른 멱등성 키의 동일 정확 일치 요청이 진행 중일 때 공유하는 기존 실행의 요청 식별자 |
| `traceId` | Spring Boot, FastAPI와 LLM Provider 호출 흐름 추적 식별자 |
| `promptVersion` | 리포트 생성 지침의 버전 |
| `modelVersion` | 정확 일치 조건에 사용한 모델 버전 |

`aiRequestId`는 개별 Provider 호출 식별자가 아니다. 한 요청에서 승인된 재시도 또는 모델 라우팅이 발생하면 여러 Provider 호출이 하나의 `aiRequestId`에 연결될 수 있다.

초기 계약에서는 사건에 채택된 대표 DetectionResult의 `detectionResultVersion`을 사용한다. 이 방식은 복수 거래 사건에 연결된 전체 DetectionResult 집합의 변경을 표현하지 못하는 초기 제약이 있다. 불변 `caseAnalysisSnapshotVersion`은 후속 ADR·ERD 설계 후보이며, 현재 확정된 네 요소의 정확 일치 키를 변경하지 않는다.

### 3.3 요청 사용자와 호출 주체

- FDS 분석 담당자나 운영자의 식별은 요청 본문의 임의 `requestedBy`, `authorRef` 또는 `actorRef`를 신뢰하지 않고 서버 사용자 문맥에서 결정한다.
- 인증·인가가 구현되지 않은 local/test 환경의 Mock Actor 공급 방식과 헤더명은 이 계약에서 확정하지 않는다.
- 감사 기록에는 제한된 요청자 참조값, `aiRequestId`, `caseId`, 요청 시각과 `traceId`를 연결한다.

### 3.4 시간 범위

`from`과 `to`는 UTC ISO-8601 시각이며 다음 반개구간을 사용한다.

```text
from <= requestedAt < to
```

`from`은 `to`보다 앞서야 하며 최대 조회 기간은 31일이다. 31일을 초과하면 `422 Unprocessable Entity`, `VALIDATION_ERROR`와 필드 오류 코드 `INVALID_TIME_RANGE`를 반환한다.

### 3.5 비용 표현

- `estimatedCost`는 JSON number가 아닌 소수점 문자열이다.
- `costCurrency`는 비용 통화를 명시하는 문자열이다.
- Provider가 반환한 토큰 사용량을 우선 기록한다.
- 비용은 Provider가 반환한 토큰 사용량과 호출 시점에 적용할 가격 정책을 바탕으로 계산하는 **추정 비용**이다.
- `estimatedCost`를 실제 청구 금액이나 확정 정산 금액으로 표현하지 않는다.
- 초기에는 Provider 원통화를 그대로 기록하고 환율 환산을 하지 않는다.
- 서로 다른 통화를 환산 없이 합산하지 않는다.
- 여러 통화가 포함된 집계는 `estimatedCost`와 `costCurrency`를 모두 `null`로 두고 `costBreakdown`에 통화별 금액을 반환한다.
- 캐시 적중 요청은 실제 Provider 호출이 없으므로 `inputTokens`, `outputTokens`, `totalTokens`를 0으로 반환하고 `estimatedCost`와 `costCurrency`를 모두 `null`로 반환한다.
- 가격표 버전, 환율과 확정 정산은 제외 범위이다.

단일 통화 예:

```json
{
  "estimatedCost": "0.014200",
  "costCurrency": "USD"
}
```

복수 통화 집계 예:

```json
{
  "estimatedCost": null,
  "costCurrency": null,
  "costBreakdown": [
    {
      "costCurrency": "KRW",
      "estimatedCost": "1250.00"
    },
    {
      "costCurrency": "USD",
      "estimatedCost": "1.420000"
    }
  ]
}
```

### 3.6 페이지네이션

상세 목록은 공통 규칙의 `page`, `size`, `sort`를 사용한다.

- `page`: 0부터 시작하며 기본값은 0
- `size`: 한 페이지의 항목 수. 기본값은 20, 최대값은 100
- `sort`: `field,direction` 형식이며 반복 가능
- 기본 정렬: `requestedAt,desc`와 안정적인 보조 정렬 `aiRequestId,desc`
- 허용 정렬 필드: `requestedAt`, `completedAt`, `latencyMs`, `totalTokens`, `aiRequestId`

통화를 환산하지 않으므로 `estimatedCost` 정렬은 제공하지 않는다. 향후 단일 `costCurrency` 필터를 필수 적용하는 별도 계약이 승인될 때만 비용 정렬을 확장할 수 있다. 보존 기간은 DB·운영 정책의 후속 결정 사항이다.

### 3.7 API 목록

```text
POST /api/v1/cases/{caseId}/ai-reports
GET  /api/v1/cases/{caseId}/ai-reports/current

GET  /api/v1/ai-report-requests/{aiRequestId}
GET  /api/v1/ai-report-usage
GET  /api/v1/ai-report-usage/summary
```

상세 목록과 집계를 별도 엔드포인트로 분리한다. 페이지 탐색용 상세 데이터와 전체 기간 집계는 응답 크기, 계산 비용과 변경 주기가 다르며, 단일 책임과 기존 목록 API 스타일을 유지하기 위해서다. 두 API는 같은 필터 의미를 사용한다.

## 4. 상태와 열거형

### 4.1 `reportStatus`

[`ai-report-state-transition.md`](../01-requirements/ai-report-state-transition.md), [`domain-erd.md`](../02-architecture/domain-erd.md)와 [`system-architecture.md`](../02-architecture/system-architecture.md)에 확정된 다음 값을 사용한다.

```text
PENDING
GENERATING
COMPLETED
FALLBACK_COMPLETED
FAILED
```

| 상태 | 의미 | 종료 상태 | 본문 |
| --- | --- | --- | --- |
| `PENDING` | 요청이 접수되었으나 실행을 시작하지 않음 | 아니요 | `null` |
| `GENERATING` | 모델 라우팅, LLM 호출, 검증, 승인된 재시도 또는 fallback 처리 중 | 아니요 | `null` |
| `COMPLETED` | 검증된 LLM 리포트가 준비됨 | 예 | 필수 |
| `FALLBACK_COMPLETED` | Rule·ML 기반 템플릿 리포트가 준비됨 | 예 | 필수 |
| `FAILED` | LLM과 fallback 모두 사용 가능한 리포트를 만들지 못함 | 예 | `null` |

`RETRYING`, `CANCELLED`와 캐시 적중 상태를 추가하지 않는다. 재시도는 `GENERATING` 내부 시도 이력으로, 캐시 적중은 `reportSource`와 `cacheHit`로 표현한다.

### 4.2 `reportSource`, `cacheHit`와 원본 요청

`reportSource`는 리포트 본문의 **최초 생성 출처**를 나타낸다.

```text
LLM
TEMPLATE_FALLBACK
```

| 값 | 의미 |
| --- | --- |
| `LLM` | LLM 결과가 검증을 통과해 리포트 본문을 최초 생성함 |
| `TEMPLATE_FALLBACK` | LLM 호출 또는 출력 검증 실패 후 템플릿이 리포트 본문을 최초 생성함 |

캐시 재사용은 `reportSource` 값이 아니라 `cacheHit`으로 구분한다.

| 처리 결과 | `reportStatus` | `reportSource` | `cacheHit` | `sourceAiRequestId` |
| --- | --- | --- | --- | --- |
| LLM 신규 생성 | `COMPLETED` | `LLM` | `false` | `null` |
| 템플릿 fallback 신규 생성 | `FALLBACK_COMPLETED` | `TEMPLATE_FALLBACK` | `false` | `null` |
| LLM 원본 캐시 재사용 | `COMPLETED` | `LLM` | `true` | 원본 생성 요청 ID |
| fallback 원본 캐시 재사용 | `FALLBACK_COMPLETED` | `TEMPLATE_FALLBACK` | `true` | 원본 생성 요청 ID |
| 진행 중 동일 실행 공유 | 부모 실행 결과를 따름 | 완료 후 부모 실행의 최초 생성 출처 | `false` | `null` |

`PENDING`, `GENERATING`, `FAILED`에서는 아직 사용 가능한 본문 출처가 없으므로 `reportSource = null`이다. 캐시가 아니면 `sourceAiRequestId = null`이다. 캐시는 `reportSource` Enum 값으로 사용하지 않는다. 진행 중 실행 공유 요청은 `parentAiRequestId`로 부모를 가리킨다.

### 4.3 실패 원인 코드 후보

다음은 HTTP 오류 코드가 아니라 AI 요청의 `failureCode` 후보이다. 최종 목록은 사용자 승인이 필요하다.

```text
FASTAPI_TIMEOUT
FASTAPI_CONNECTION_FAILED
LLM_TIMEOUT
LLM_PROVIDER_ERROR
LLM_OUTPUT_VALIDATION_FAILED
TEMPLATE_FALLBACK_FAILED
RESULT_PERSISTENCE_FAILED
```

Provider 원본 오류 메시지, Prompt와 응답 원문을 `failureCode`나 외부 응답에 포함하지 않는다.

## 5. 사건 AI 리포트 생성 요청 API

### 5.1 목적과 요청

HIGH 또는 CRITICAL 사건을 대상으로 비동기 AI 리포트 생성을 요청한다.

```http
POST /api/v1/cases/{caseId}/ai-reports
Content-Type: application/json
Idempotency-Key: <required>
```

### 5.2 Path Parameter

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `caseId` | string | 필수 | 리포트 대상 사건 업무 식별자 |

### 5.3 Query Parameter

없음.

### 5.4 Header

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `Idempotency-Key` | 필수 | 동일 생성 요청의 중복 실행을 방지한다. 누락 또는 형식 오류는 `400 Bad Request`와 `VALIDATION_ERROR` |
| `Content-Type` | 필수 | `application/json` |
| 추적 헤더 | 미확정 | 구체적인 OpenTelemetry/W3C 헤더명은 공통 후속 설계 대상 |

### 5.5 Request Body

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `detectionResultVersion` | integer | 필수 | 사건의 대표 또는 채택 탐지 결과 버전 |
| `regenerationReason` | string 또는 null | 조건부 | 탐지·Prompt·모델 버전 변경으로 새 정확 일치 조건을 요청하는 감사 사유. 일반 최초 요청은 null 가능 |

요청자를 나타내는 필드는 본문에서 받지 않는다. Spring Boot가 신뢰할 수 있는 서버 사용자 문맥에서 요청 주체를 결정한다.

```json
{
  "detectionResultVersion": 3,
  "regenerationReason": "새 탐지 결과 버전을 반영한 조사 지원 리포트 요청"
}
```

### 5.6 Validation과 처리 규칙

- `caseId` 형식과 사건 존재 여부를 확인한다.
- `detectionResultVersion`은 1 이상의 정수이며 대상 사건의 대표 또는 채택 탐지 결과와 일치해야 한다.
- 사건의 대표 위험 등급은 `HIGH` 또는 `CRITICAL`이어야 한다.
- LOW·MEDIUM 사건의 수동 생성은 초기 계약에서 허용하지 않는다.
- Prompt와 모델 버전은 승인된 서버 정책으로 선택하고 요청의 정확 일치 조건에 고정한다.
- 동일 정확 일치 조건의 `PENDING` 또는 `GENERATING` 요청이 있으면 새 실행을 만들지 않는다.
- 동일 정확 일치 조건의 완료 리포트가 있으면 새 Provider 호출 없이 캐시 재사용 요청으로 처리한다.
- `detectionResultVersion`, `promptVersion` 또는 `modelVersion`이 바뀐 경우에만 새로운 정확 일치 조건의 요청이 될 수 있다.
- 초기 버전에서는 동일 정확 일치 조건의 강제 재생성을 허용하지 않는다.
- 향후 관리자 강제 재생성은 후속 확장 범위이며 이전 리포트와 요청 이력을 덮어쓰거나 삭제해서는 안 된다.
- 리포트 생성은 거래·사건 상태를 변경하지 않는다.

### 5.7 성공 응답

최초 유효 요청은 `202 Accepted`를 반환한다. 응답에서 완성된 리포트를 반환하지 않는다.

```http
HTTP/1.1 202 Accepted
Location: /api/v1/ai-report-requests/air_demo_20260726_0001
Content-Type: application/json
```

```json
{
  "aiRequestId": "air_demo_20260726_0001",
  "caseId": "case_demo_20260724_0031",
  "detectionResultVersion": 3,
  "reportStatus": "PENDING",
  "sourceAiRequestId": null,
  "parentAiRequestId": null,
  "requestedAt": "2026-07-26T02:10:00Z",
  "resultLocation": "/api/v1/ai-report-requests/air_demo_20260726_0001",
  "traceId": "trace_demo_ai_request_01"
}
```

| 필드 | 설명 |
| --- | --- |
| `aiRequestId` | 생성 요청 식별자 |
| `caseId` | 대상 사건 식별자 |
| `detectionResultVersion` | 요청에 고정된 탐지 결과 버전 |
| `reportStatus` | 접수 시 `PENDING` |
| `sourceAiRequestId` | 캐시 적중 전에는 null. 적중 완료 후 원본 생성 요청 ID |
| `parentAiRequestId` | 동일 정확 일치 진행 실행을 공유하면 해당 기존 요청 ID, 아니면 null |
| `requestedAt` | 요청 접수 시각 |
| `resultLocation` | 단건 운영 상태 조회 경로 |
| `traceId` | 현재 생성 요청 HTTP 흐름 추적 식별자 |

생성 응답은 HTTP `Location` 헤더와 본문의 `resultLocation`을 함께 반환한다.

### 5.8 멱등성

- 같은 키와 같은 정규화 요청이 처리 중이면 `202 Accepted`로 기존 `aiRequestId`와 현재 상태를 반환한다.
- 같은 키와 같은 정규화 요청이 완료되었으면 `200 OK`로 기존 요청 결과를 반환한다.
- 같은 키에 다른 `caseId`, `detectionResultVersion` 또는 재생성 사유가 오면 `409 Conflict`와 `IDEMPOTENCY_KEY_CONFLICT`를 반환한다.
- 서로 다른 키로 같은 정확 일치 조건이 도착했고 기존 요청이 `PENDING` 또는 `GENERATING`이면 새 외부 요청 이력과 새 `aiRequestId`를 생성해 반환한다. 새 요청의 `parentAiRequestId`는 기존 진행 요청을 가리키며 같은 실행을 공유한다.
- 위 공유 요청은 새 Provider 실행을 생성하지 않는다. 공유 실행의 결과를 자신의 최종 상태와 원본 관계에 반영한다.
- 서로 다른 키로 완료된 정확 일치 결과를 요청하면 새 외부 요청 이력과 새 `aiRequestId`를 발급하고 `cacheHit = true`, `sourceAiRequestId = 원본 생성 요청 ID`로 기록한다.
- `aiRequestId` 신규 발급 여부와 별개로 같은 정확 일치 리포트 본문과 사용량을 중복 생성하지 않는다.

현재 ERD는 하나의 `aiRequestId`가 외부 요청과 Provider 실행을 동시에 의미하는 후보여서 새 요청과 공유 실행 관계를 직접 표현하기 어렵다. API는 새로운 외부 요청에 새 `aiRequestId`를 반환하는 방식으로 확정하지만, 구현 전 ERD에서 요청 이력과 실행 이력의 관계를 확정해야 한다. 이 문서는 임의의 DB 테이블 구조를 확정하지 않는다.

### 5.9 주요 오류

| 상태 | 코드 | 상황 |
| --- | --- | --- |
| `400 Bad Request` | `VALIDATION_ERROR` | JSON, 식별자, 필수 헤더 또는 필드 형식 오류 |
| `404 Not Found` | `RESOURCE_NOT_FOUND` | 사건 또는 탐지 결과를 찾을 수 없음 |
| `409 Conflict` | `IDEMPOTENCY_KEY_CONFLICT` | 같은 키에 다른 요청 내용 |
| `409 Conflict` | `AI_REPORT_REGENERATION_NOT_ALLOWED` 후보 | 동일 정확 일치 조건의 강제 재생성 요청 |
| `422 Unprocessable Entity` | `AI_REPORT_CASE_NOT_ELIGIBLE` 후보 | HIGH·CRITICAL이 아닌 사건 |
| `403 Forbidden` | `FORBIDDEN` 후보 | 생성 권한 부족 |
| `500 Internal Server Error` | `INTERNAL_ERROR` | 요청 접수·식별자 발급·상태 저장 중 예기치 않은 오류 |

동일 조건의 처리 중 요청은 오류가 아니다. 같은 `Idempotency-Key`이면 `202 Accepted`로 기존 `aiRequestId`를 반환하고, 다른 키이면 새 `aiRequestId`와 기존 실행의 `parentAiRequestId`를 반환한다.

```json
{
  "code": "AI_REPORT_CASE_NOT_ELIGIBLE",
  "message": "AI 리포트 생성 대상 위험 등급이 아닙니다.",
  "traceId": "trace_demo_ai_not_eligible_01",
  "fieldErrors": [
    {
      "field": "caseId",
      "code": "UNSUPPORTED_CASE_RISK_LEVEL",
      "reason": "HIGH 또는 CRITICAL 사건만 생성 요청할 수 있습니다."
    }
  ]
}
```

FastAPI와 LLM은 요청 접수 이후 호출되므로 그 Timeout이나 Provider 오류를 최초 `202 Accepted`의 HTTP 실패로 소급 변경하지 않는다. 이후 상태와 fallback 결과로 표현한다.

### 5.10 사용 주체와 추적

- 사용 주체: FDS 분석 담당자
- 멱등성: 필수 `Idempotency-Key`
- 추적: `aiRequestId`, `caseId`, `detectionResultVersion`, 서버 요청자 참조값과 `traceId`

## 6. 사건 AI 리포트 조회 API

### 6.1 목적과 요청

사건별 현재 유효한 AI 리포트와 최신 생성 요청 상태를 함께 조회한다.

```http
GET /api/v1/cases/{caseId}/ai-reports/current
```

### 6.2 Parameter와 Header

| 구분 | 이름 | 필수 | 설명 |
| --- | --- | --- | --- |
| Path | `caseId` | 필수 | 사건 업무 식별자 |
| Query | 없음 | - | - |
| Header | 추적 헤더 | 미확정 | 공통 추적 정책 적용 |
| Body | 없음 | - | GET 요청 본문을 사용하지 않음 |

### 6.3 Response Body

응답은 현재 유효한 리포트와 최신 요청을 분리한다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `caseId` | string | 대상 사건 |
| `currentReport` | object 또는 null | 현재 조사에 사용할 수 있는 `COMPLETED` 또는 `FALLBACK_COMPLETED` 리포트 |
| `latestRequest` | object 또는 null | 해당 사건의 가장 최근 생성 요청 상태 |
| `traceId` | string | 현재 조회 HTTP 요청 추적 식별자 |

`currentReport` 필드:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `aiRequestId` | string | 선택된 리포트를 만든 또는 재사용한 요청 |
| `caseId` | string | 사건 식별자 |
| `detectionResultVersion` | integer | 리포트 근거 탐지 버전 |
| `reportStatus` | string | `COMPLETED` 또는 `FALLBACK_COMPLETED` |
| `reportSource` | string | 본문 최초 생성 출처인 `LLM` 또는 `TEMPLATE_FALLBACK` |
| `cacheHit` | boolean | 현재 요청이 정확 일치 원본을 재사용했는지 |
| `sourceAiRequestId` | string 또는 null | 캐시 적중 시 원본 생성 요청 ID, 아니면 null |
| `summary` | string | 사건 요약 |
| `keyReasons` | array | Rule·ML 탐지 근거 요약 목록 |
| `timelineSummary` | string | 행동 타임라인 요약 |
| `investigationChecklist` | array | 담당자 확인 항목 |
| `promptVersion` | string | 사용 Prompt 버전 |
| `modelVersion` | string | 정확 일치 조건의 모델 버전 |
| `generatedAt` | string | 리포트가 최초 사용 가능해진 UTC 시각 |
| `failureCode` | string 또는 null | fallback의 원인이 된 실패 분류. 정상 LLM·캐시는 null 가능 |
| `traceId` | string | 해당 리포트 생성 또는 캐시 재사용 흐름의 과거 추적 식별자 |

`latestRequest` 필드:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `aiRequestId` | string | 최신 요청 식별자 |
| `detectionResultVersion` | integer | 최신 요청의 탐지 버전 |
| `reportStatus` | string | 전체 상태 Enum |
| `reportSource` | string 또는 null | 처리 중·실패이면 null |
| `cacheHit` | boolean | 최신 요청의 캐시 재사용 여부 |
| `sourceAiRequestId` | string 또는 null | 캐시 적중 시 원본 생성 요청 ID |
| `parentAiRequestId` | string 또는 null | 진행 중 동일 실행을 공유한 부모 요청 ID |
| `requestedAt` | string | 접수 시각 |
| `generatedAt` | string 또는 null | 사용 가능한 결과가 준비된 시각 |
| `failureCode` | string 또는 null | 최종 또는 fallback 실패 분류 |
| `traceId` | string | 최신 요청의 처리 흐름 추적 식별자 |

### 6.4 null 정책과 현재 선택

- 요청 이력이 없으면 `currentReport`와 `latestRequest`는 모두 `null`이다.
- `PENDING` 또는 `GENERATING`이며 과거 유효 리포트가 없으면 `currentReport = null`이고 `latestRequest`만 반환한다.
- 새 요청이 `PENDING`, `GENERATING` 또는 `FAILED`여도 과거 유효 리포트를 숨기지 않고 `currentReport`로 유지한다.
- `FAILED` 요청은 리포트 본문 필드를 만들지 않고 `latestRequest`로 제공한다.
- `summary`, `keyReasons`, `timelineSummary`, `investigationChecklist`, `generatedAt`은 사용 가능한 리포트에 필수이다.
- `failureCode`는 `FAILED`와 `FALLBACK_COMPLETED`에서 값이 있을 수 있다.
- 빈 근거·체크리스트가 업무상 허용되는 경우 빈 배열을 사용하며 `null`과 혼합하지 않는다.

현재 유효 리포트는 가장 최근에 성공적으로 준비된 `COMPLETED` 또는 `FALLBACK_COMPLETED` 결과이다. `generatedAt`이 같으면 `requestedAt`, 그다음 `aiRequestId`를 안정적인 보조 순서로 사용한다.

### 6.5 성공 응답 예시

```json
{
  "caseId": "case_demo_20260724_0031",
  "currentReport": {
    "aiRequestId": "air_demo_20260726_0001",
    "caseId": "case_demo_20260724_0031",
    "detectionResultVersion": 3,
    "reportStatus": "COMPLETED",
    "reportSource": "LLM",
    "cacheHit": false,
    "sourceAiRequestId": null,
    "summary": "가상 사건에서 신규 기기 등록 직후 고액 이체가 발생했습니다.",
    "keyReasons": [
      {
        "reasonCode": "NEW_DEVICE_HIGH_AMOUNT",
        "description": "신규 기기와 고객 기준선 대비 높은 금액이 함께 탐지되었습니다."
      }
    ],
    "timelineSummary": "기기 등록, 신규 수취인 등록, 이체 요청이 짧은 시간 안에 이어졌습니다.",
    "investigationChecklist": [
      "기기 등록 경위 확인",
      "수취인과 고객의 관계 확인",
      "추가 인증 결과 확인"
    ],
    "promptVersion": "ai-report-prompt-3",
    "modelVersion": "report-model-lite-2",
    "generatedAt": "2026-07-26T02:10:08Z",
    "failureCode": null,
    "traceId": "trace_demo_ai_request_01"
  },
  "latestRequest": {
    "aiRequestId": "air_demo_20260726_0002",
    "detectionResultVersion": 4,
    "reportStatus": "GENERATING",
    "reportSource": null,
    "cacheHit": false,
    "sourceAiRequestId": null,
    "parentAiRequestId": null,
    "requestedAt": "2026-07-26T02:20:00Z",
    "generatedAt": null,
    "failureCode": null,
    "traceId": "trace_demo_ai_request_02"
  },
  "traceId": "trace_demo_ai_current_query_01"
}
```

응답 최상위 `traceId`는 현재 조회 요청을, 중첩 객체의 `traceId`는 과거 생성 처리를 추적한다.

### 6.6 Validation, 오류와 사용 주체

| 상태 | 코드 | 상황 |
| --- | --- | --- |
| `400 Bad Request` | `VALIDATION_ERROR` | `caseId` 형식 오류 |
| `404 Not Found` | `RESOURCE_NOT_FOUND` | 사건이 없음 |
| `403 Forbidden` | `FORBIDDEN` 후보 | 사건 리포트 조회 권한 부족 |
| `500 Internal Server Error` | `INTERNAL_ERROR` | 저장된 선택 상태를 일관되게 읽을 수 없음 |

- 사용 주체: FDS 분석 담당자
- 노출 범위: 사건 리포트 상태, 안전한 `failureCode`, 리포트 본문, `reportSource`, `cacheHit`과 원본 요청 관계
- 비노출: Provider, 모델 호출 시도, 토큰과 비용 상세. 이 정보는 플랫폼·클라우드 운영자 전용 API에서만 제공
- 멱등성: GET이므로 별도 키를 사용하지 않음
- 추적: 현재 조회 `traceId`, 리포트와 최신 요청의 과거 `traceId`

## 7. AI 요청 단건 운영 상세 조회 API

### 7.1 목적과 요청

`aiRequestId`를 기준으로 리포트 상태, Provider 호출 사용량과 장애 처리 결과를 조회한다.

```http
GET /api/v1/ai-report-requests/{aiRequestId}
```

### 7.2 Parameter와 Header

| 구분 | 이름 | 필수 | 설명 |
| --- | --- | --- | --- |
| Path | `aiRequestId` | 필수 | AI 리포트 생성 요청 식별자 |
| Query | 없음 | - | - |
| Header | 추적 헤더 | 미확정 | 공통 추적 정책 적용 |
| Body | 없음 | - | GET 요청 본문을 사용하지 않음 |

### 7.3 Response Body

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `aiRequestId` | string | AI 요청 식별자 |
| `caseId` | string | 대상 사건 |
| `detectionResultVersion` | integer | 요청에 고정된 탐지 버전 |
| `reportStatus` | string | 리포트 상태 |
| `reportSource` | string 또는 null | 본문 최초 생성 출처. `LLM`, `TEMPLATE_FALLBACK` 또는 null |
| `sourceAiRequestId` | string 또는 null | 캐시 적중 시 원본 생성 요청 ID |
| `parentAiRequestId` | string 또는 null | 진행 중 동일 실행을 공유한 부모 요청 ID |
| `lastProvider` | string 또는 null | 이번 요청의 마지막 실제 Provider 호출. 캐시로 호출이 없으면 null |
| `lastModel` | string 또는 null | 마지막 실제 Provider 모델명. 캐시로 호출이 없으면 null |
| `promptVersion` | string | Prompt 버전 |
| `modelVersion` | string | 정확 일치 기준 모델 버전 |
| `inputTokens` | integer | 이번 요청의 실제 Provider 호출 입력 토큰 합 |
| `outputTokens` | integer | 이번 요청의 실제 Provider 호출 출력 토큰 합 |
| `totalTokens` | integer | `inputTokens + outputTokens` |
| `estimatedCost` | string 또는 null | 단일 통화일 때 호출별 추정 비용 합 |
| `costCurrency` | string 또는 null | `estimatedCost`의 통화 |
| `costBreakdown` | array | 통화별 추정 비용. 서로 다른 통화를 분리 |
| `latencyMs` | integer 또는 null | 요청 접수부터 종료까지의 시간. 미종료이면 null |
| `cacheHit` | boolean | 이번 요청이 정확 일치 기존 리포트를 재사용했는지 |
| `fallbackUsed` | boolean | 이번 요청에서 템플릿 fallback을 실제 실행해 채택했는지 |
| `usageFinalized` | boolean | 토큰·비용·지연 합계가 종료 상태로 확정되었는지 |
| `requestedByRef` | string | 서버 사용자 문맥에서 얻은 제한된 요청자 참조값 |
| `requestedAt` | string | 요청 접수 시각 |
| `completedAt` | string 또는 null | 종료 상태 확정 시각 |
| `failureCode` | string 또는 null | 실패 또는 fallback 원인 분류 |
| `attempts` | array | 실제 Provider 호출별 운영 정보. 캐시는 빈 배열 |
| `traceId` | string | 과거 AI 생성 흐름 추적 식별자 |
| `queryTraceId` | string | 현재 조회 HTTP 요청 추적 식별자 |

`attempts` 항목은 최소한 `attemptNumber`, `provider`, `model`, `inputTokens`, `outputTokens`, `totalTokens`, `estimatedCost`, `costCurrency`, `latencyMs`, `outcome`, `failureCode`, `calledAt`을 포함한다. 이는 한 `aiRequestId`에 여러 Provider 호출이 가능한 ERD 관계를 손실 없이 표현한다.

### 7.4 상태별 운영 필드

- `PENDING`: `attempts`는 빈 배열, 토큰은 0, `latencyMs`·`completedAt`은 null, `usageFinalized = false`.
- `GENERATING`: 현재까지 확정된 호출만 `attempts`와 합계에 포함하며 `usageFinalized = false`.
- `COMPLETED`: 사용량·비용·지연을 확정하고 `usageFinalized = true`.
- `FALLBACK_COMPLETED`: 실패한 LLM 호출에 비용이 발생했으면 누락하지 않으며 `fallbackUsed = true`.
- `FAILED`: 사용 가능한 리포트는 없어도 실제 Provider 호출의 토큰과 비용은 기록하고 `usageFinalized = true`.
- 캐시 적중: `attempts = []`, 토큰 세 필드는 0, `estimatedCost = null`, `costCurrency = null`, `lastProvider = null`, `lastModel = null`, `cacheHit = true`, `fallbackUsed = false`.
- 진행 실행 공유 요청: `parentAiRequestId`를 반환하고 자신의 `attempts`는 빈 배열로 유지한다. 토큰 세 필드는 0, 비용과 `lastProvider`·`lastModel`은 null로 반환해 같은 Provider 사용량을 중복 집계하지 않는다.

자동 재시도는 Timeout과 연결 실패처럼 일시적인 오류에만 적용한다. 최초 호출을 포함해 최대 2회 시도하므로 자동 재시도는 최대 1회이다. 출력 검증 실패와 비일시적 Provider 오류는 자동 재시도하지 않고 템플릿 fallback으로 전환한다. 각 호출은 같은 `aiRequestId` 아래 `attempts`에 순서대로 기록한다.

### 7.5 성공 응답 예시

```json
{
  "aiRequestId": "air_demo_20260726_0003",
  "caseId": "case_demo_20260724_0031",
  "detectionResultVersion": 3,
  "reportStatus": "FALLBACK_COMPLETED",
  "reportSource": "TEMPLATE_FALLBACK",
  "sourceAiRequestId": null,
  "parentAiRequestId": null,
  "lastProvider": "provider-demo",
  "lastModel": "report-model-demo",
  "promptVersion": "ai-report-prompt-3",
  "modelVersion": "report-model-lite-2",
  "inputTokens": 920,
  "outputTokens": 0,
  "totalTokens": 920,
  "estimatedCost": "0.002300",
  "costCurrency": "USD",
  "costBreakdown": [
    {
      "costCurrency": "USD",
      "estimatedCost": "0.002300"
    }
  ],
  "latencyMs": 4200,
  "cacheHit": false,
  "fallbackUsed": true,
  "usageFinalized": true,
  "requestedByRef": "analyst_ref_demo_07",
  "requestedAt": "2026-07-26T03:00:00Z",
  "completedAt": "2026-07-26T03:00:04.200Z",
  "failureCode": "LLM_TIMEOUT",
  "attempts": [
    {
      "attemptNumber": 1,
      "provider": "provider-demo",
      "model": "report-model-demo",
      "inputTokens": 920,
      "outputTokens": 0,
      "totalTokens": 920,
      "estimatedCost": "0.002300",
      "costCurrency": "USD",
      "latencyMs": 4000,
      "outcome": "FAILED",
      "failureCode": "LLM_TIMEOUT",
      "calledAt": "2026-07-26T03:00:00.100Z"
    }
  ],
  "traceId": "trace_demo_ai_request_03",
  "queryTraceId": "trace_demo_ai_request_query_01"
}
```

캐시 적중 요청의 핵심 운영 필드는 다음과 같다. 원본의 `reportSource`는 유지하지만 이번 요청의 Provider 사용량은 생성하지 않는다.

```json
{
  "aiRequestId": "air_demo_20260726_0004",
  "reportStatus": "COMPLETED",
  "reportSource": "LLM",
  "sourceAiRequestId": "air_demo_20260726_0001",
  "parentAiRequestId": null,
  "lastProvider": null,
  "lastModel": null,
  "inputTokens": 0,
  "outputTokens": 0,
  "totalTokens": 0,
  "estimatedCost": null,
  "costCurrency": null,
  "cacheHit": true,
  "fallbackUsed": false,
  "attempts": []
}
```

### 7.6 Validation, 오류와 사용 주체

| 상태 | 코드 | 상황 |
| --- | --- | --- |
| `400 Bad Request` | `VALIDATION_ERROR` | `aiRequestId` 형식 오류 |
| `404 Not Found` | `RESOURCE_NOT_FOUND` | AI 요청을 찾을 수 없음 |
| `403 Forbidden` | `FORBIDDEN` 후보 | 운영 상세 조회 권한 부족 |
| `500 Internal Server Error` | `INTERNAL_ERROR` | 요청·호출 사용량 관계가 일관되지 않음 |

- 사용 주체: 플랫폼·클라우드 운영자 전용
- FDS 분석 담당자는 이 운영 상세 API로 Provider, 모델 호출 시도, 토큰과 비용을 조회하지 않는다.
- 멱등성: GET이므로 별도 키를 사용하지 않음
- 추적: 과거 `traceId`와 현재 `queryTraceId`를 구분
- 비노출: Prompt 원문, Provider 응답 원문, 고객 개인정보, 인증정보와 내부 예외 원문

## 8. AI 사용량·비용 상세 목록 조회 API

### 8.1 목적과 요청

플랫폼·클라우드 운영자가 기간과 운영 조건으로 AI 요청별 사용량·비용을 조회한다.

```http
GET /api/v1/ai-report-usage
```

### 8.2 Query Parameter

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `from` | datetime | 필수 | 요청 접수 시각 범위 시작 |
| `to` | datetime | 필수 | 요청 접수 시각 범위 끝 |
| `provider` | string | 선택 | `attempts` 중 하나라도 해당 Provider와 일치하는 요청. 캐시 요청은 매칭되지 않음 |
| `model` | string | 선택 | `attempts` 중 하나라도 해당 모델과 일치하는 요청. 캐시 요청은 매칭되지 않음 |
| `reportStatus` | string | 선택 | 상태 Enum |
| `reportSource` | string | 선택 | 본문 최초 생성 출처인 `LLM`, `TEMPLATE_FALLBACK` |
| `cacheHit` | boolean | 선택 | 정확 일치 캐시 재사용 여부 |
| `fallbackUsed` | boolean | 선택 | 이번 요청의 실제 fallback 채택 여부 |
| `page` | integer | 선택 | 0부터 시작, 기본값 0 |
| `size` | integer | 선택 | 기본값 20, 최대값 100 |
| `sort` | string | 선택·반복 | `field,direction`. 기본값 `requestedAt,desc`, 보조 정렬 `aiRequestId,desc` |

### 8.3 Header와 Body

- 요청 본문 없음
- 추적 헤더는 공통 정책 적용
- `Idempotency-Key`는 사용하지 않음

### 8.4 목록 항목

각 `content` 항목은 다음 필드를 포함한다.

- `aiRequestId`
- `caseId`
- `detectionResultVersion`
- `reportStatus`
- `reportSource`
- `sourceAiRequestId`
- `parentAiRequestId`
- `lastProvider`
- `lastModel`
- `promptVersion`
- `modelVersion`
- `inputTokens`
- `outputTokens`
- `totalTokens`
- `estimatedCost`
- `costCurrency`
- `latencyMs`
- `cacheHit`
- `fallbackUsed`
- `requestedAt`
- `completedAt`
- `failureCode`
- `traceId`

여러 Provider 시도가 있으면 `lastProvider`와 `lastModel`은 마지막 실제 호출을 표시하고 토큰·비용은 요청 전체 실제 호출의 합계이다. 전체 시도는 단건 운영 상세 API에서 조회한다. `provider`와 `model` 필터는 마지막 호출만이 아니라 `attempts` 중 하나라도 일치하면 해당 요청을 포함한다.

### 8.5 성공 응답 예시

```json
{
  "content": [
    {
      "aiRequestId": "air_demo_20260726_0001",
      "caseId": "case_demo_20260724_0031",
      "detectionResultVersion": 3,
      "reportStatus": "COMPLETED",
      "reportSource": "LLM",
      "sourceAiRequestId": null,
      "parentAiRequestId": null,
      "lastProvider": "provider-demo",
      "lastModel": "report-model-demo",
      "promptVersion": "ai-report-prompt-3",
      "modelVersion": "report-model-lite-2",
      "inputTokens": 920,
      "outputTokens": 310,
      "totalTokens": 1230,
      "estimatedCost": "0.014200",
      "costCurrency": "USD",
      "latencyMs": 8100,
      "cacheHit": false,
      "fallbackUsed": false,
      "requestedAt": "2026-07-26T02:10:00Z",
      "completedAt": "2026-07-26T02:10:08.100Z",
      "failureCode": null,
      "traceId": "trace_demo_ai_request_01"
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
  "traceId": "trace_demo_ai_usage_list_01"
}
```

목록 항목의 `traceId`는 과거 생성 요청을, 최상위 `traceId`는 현재 목록 조회를 추적한다.

### 8.6 Validation, 오류와 사용 주체

- `from`과 `to`는 필수이며 UTC ISO-8601 형식이어야 한다.
- `from < to`여야 한다.
- 조회 기간은 반개구간 `from <= requestedAt < to`이며 최대 31일이다. 초과하면 `422 VALIDATION_ERROR`와 `INVALID_TIME_RANGE`를 반환한다.
- Enum, boolean, 페이지와 정렬 필드를 검증한다.
- `page < 0` 또는 `size < 1`은 형식·범위 오류이며 `size > 100`은 `422 VALIDATION_ERROR`이다.
- `estimatedCost` 정렬은 제공하지 않는다.

| 상태 | 코드 | 상황 |
| --- | --- | --- |
| `400 Bad Request` | `VALIDATION_ERROR` | 시각, Enum, boolean, 페이지 또는 정렬 형식 오류 |
| `422 Unprocessable Entity` | `VALIDATION_ERROR` | `from >= to`, 과도한 기간 또는 허용하지 않은 정렬 |
| `403 Forbidden` | `FORBIDDEN` 후보 | 운영 목록 조회 권한 부족 |
| `500 Internal Server Error` | `INTERNAL_ERROR` | 예기치 않은 조회 오류 |

- 사용 주체: 플랫폼·클라우드 운영자
- 멱등성: GET이므로 별도 키를 사용하지 않음
- 추적: 현재 목록 조회와 각 과거 요청의 `traceId` 구분

## 9. AI 사용량·비용 집계 조회 API

### 9.1 목적과 요청

상세 목록과 같은 필터를 사용해 요청 수, 성공·실패, fallback, 캐시, 토큰, 비용과 평균 지연시간을 집계한다.

```http
GET /api/v1/ai-report-usage/summary
```

### 9.2 Query Parameter

`from`, `to`, `provider`, `model`, `reportStatus`, `reportSource`, `cacheHit`, `fallbackUsed`는 상세 목록과 같은 의미를 사용한다. 집계 API에는 `page`, `size`, `sort`를 사용하지 않는다.

### 9.3 Header와 Body

- 요청 본문 없음
- 추적 헤더는 공통 정책 적용
- `Idempotency-Key`는 사용하지 않음

### 9.4 집계 의미

| 필드 | 의미 |
| --- | --- |
| `requestCount` | 기간·필터에 포함된 전체 외부 AI 요청 수. 캐시 요청 포함 |
| `successCount` | 사용 가능한 리포트로 종료된 요청 수. `COMPLETED`와 `FALLBACK_COMPLETED` 포함 |
| `failureCount` | `FAILED` 요청 수 |
| `inProgressCount` | `PENDING`과 `GENERATING` 요청 수 |
| `fallbackCount` | 이번 요청에서 `TEMPLATE_FALLBACK`을 실제 사용한 요청 수 |
| `cacheHitCount` | `cacheHit = true`인 요청 수 |
| `inputTokens` | 실제 Provider 호출 입력 토큰 합 |
| `outputTokens` | 실제 Provider 호출 출력 토큰 합 |
| `totalTokens` | 실제 Provider 호출 전체 토큰 합 |
| `estimatedCost` | 단일 통화일 때 추정 비용 합 |
| `costCurrency` | 단일 집계 통화 |
| `costBreakdown` | 통화별 추정 비용 합 |
| `averageLatencyMs` | 종료된 요청의 접수부터 종료까지 평균 지연시간 |

`successCount + failureCount + inProgressCount = requestCount`가 되어야 한다. `fallbackCount`와 `cacheHitCount`는 성공 요청의 처리 방식 부분집합이며 성공·실패 합계에 다시 더하지 않는다.

캐시 적중은 Provider 호출이 아니므로 토큰 세 필드는 0이고 비용 합계에 0으로 기여한다. 캐시 요청 자체의 `estimatedCost`와 `costCurrency`는 null이며 Provider 호출 이력이나 가상 사용량을 생성하지 않는다. 실패한 Provider 호출도 실제 사용량과 비용이 있으면 합계에 포함한다.

### 9.5 성공 응답 예시

```json
{
  "from": "2026-07-26T00:00:00Z",
  "to": "2026-07-27T00:00:00Z",
  "requestCount": 125,
  "successCount": 119,
  "failureCount": 2,
  "inProgressCount": 4,
  "fallbackCount": 7,
  "cacheHitCount": 31,
  "inputTokens": 84200,
  "outputTokens": 25100,
  "totalTokens": 109300,
  "estimatedCost": "12.340000",
  "costCurrency": "USD",
  "costBreakdown": [
    {
      "costCurrency": "USD",
      "estimatedCost": "12.340000"
    }
  ],
  "averageLatencyMs": 7350,
  "traceId": "trace_demo_ai_usage_summary_01"
}
```

### 9.6 Validation, 오류와 사용 주체

상세 목록과 같은 기간·필터 Validation을 적용한다.

```json
{
  "code": "VALIDATION_ERROR",
  "message": "조회 기간을 확인해 주세요.",
  "traceId": "trace_demo_ai_usage_range_01",
  "fieldErrors": [
    {
      "field": "to",
      "code": "INVALID_TIME_RANGE",
      "reason": "from부터 to까지의 조회 기간은 최대 31일이어야 합니다."
    }
  ]
}
```

- `400 Bad Request`: 형식 오류
- `422 Unprocessable Entity`: 의미상 잘못된 기간 또는 필터 조합
- `403 Forbidden`: 운영 집계 권한 부족
- `500 Internal Server Error`: 예기치 않은 집계 오류
- 사용 주체: 플랫폼·클라우드 운영자
- 멱등성: GET이므로 별도 키를 사용하지 않음
- 추적: 응답 `traceId`로 현재 집계 조회 추적

## 10. 중복 요청·멱등성·재생성 정책

### 10.1 정확 일치 중복

- 같은 정확 일치 조건의 완료 리포트가 있으면 기존 리포트를 재사용할 수 있다.
- 같은 조건의 `PENDING` 또는 `GENERATING` 요청이 있으면 새 Provider 실행을 만들지 않는다.
- 동시에 도착한 요청 중 하나만 생성 실행을 획득한다.
- 동일 멱등 요청은 같은 `aiRequestId`와 상태 또는 완료 결과를 반환한다.
- 서로 다른 `Idempotency-Key`로 도착했어도 정확 일치 조건의 실행·리포트·사용량을 중복 생성하지 않는다.

### 10.2 재시도와 재생성 구분

- 재시도는 같은 `aiRequestId` 아래에서 일시 오류 후 Provider를 다시 호출하는 처리이다.
- 자동 재시도는 Timeout과 연결 실패에만 적용하며 최초 호출을 포함해 최대 2회, 즉 자동 재시도 최대 1회이다.
- 출력 검증 실패와 비일시적 Provider 오류는 자동 재시도하지 않고 템플릿 fallback으로 전환한다.
- 각 실제 호출은 같은 `aiRequestId`의 `attempts`에 `attemptNumber` 순서로 기록한다.
- 재생성은 `detectionResultVersion`, `promptVersion` 또는 `modelVersion`이 변경되어 새로운 정확 일치 조건이 된 요청이다.
- 초기 버전에서는 동일 정확 일치 조건의 강제 재생성을 허용하지 않는다.
- 향후 관리자 강제 재생성은 후속 확장 범위이며 이전 리포트, 요청·사용량과 감사 이력을 덮어쓰지 않아야 한다.

### 10.3 현재 유효한 리포트

확정 정책은 다음과 같다.

1. 사용 가능한 `COMPLETED` 또는 `FALLBACK_COMPLETED` 결과만 현재 리포트 후보로 삼는다.
2. 새 요청이 `PENDING`, `GENERATING` 또는 `FAILED`이면 기존 현재 리포트를 유지한다.
3. 새 성공 결과를 현재 리포트로 승격하는 기준은 `generatedAt`이다.
4. `generatedAt`이 같으면 `requestedAt`, 그다음 `aiRequestId`를 안정적인 보조 순서로 사용한다.
5. 모든 과거 요청, 리포트와 실제 Provider 사용량 이력은 보존 정책에 따라 추적 가능해야 한다.

보존 기간은 DB·운영 정책의 후속 결정 사항이다.

## 11. 정확 일치 캐시 정책

캐시 키는 다음 네 요소를 모두 포함한다.

```text
caseId
+ detectionResultVersion
+ promptVersion
+ modelVersion
```

- 네 값이 모두 같을 때만 기존 결과를 재사용한다.
- Reason Code가 같다는 이유로 다른 사건의 리포트를 재사용하지 않는다.
- 시맨틱 유사도를 기준으로 사건 간 리포트를 재사용하지 않는다.
- 캐시 적중 시 새로운 중복 리포트 본문과 가상 Provider 사용량을 만들지 않는다.
- 캐시 적중이어도 새 외부 요청에 새 `aiRequestId`를 발급한다.
- 별도 AI 요청 이력에 요청자, 요청 시각, `traceId`, `sourceAiRequestId`와 재사용 관계를 보존한다.
- 캐시 요청은 Provider 호출 이력, 가상 토큰과 가상 비용을 생성하지 않는다.
- AuditLog만으로 AI 요청 이력을 대체하지 않는다.
- Redis를 사용하더라도 PostgreSQL의 영속 결과가 업무 정합성 기준이다.
- Redis 장애 시 정확 일치 조건을 완화하지 않으며 원본 흐름 또는 실패 정책을 적용한다.

현재 ERD에는 외부 요청 이력과 공유 Provider 실행의 관계가 확정되어 있지 않다. 이 관계는 API 구현을 시작하기 전에 ERD 상세 설계에서 확정해야 하며, 이 문서는 임의의 DB 구조를 구현 완료 상태로 표현하지 않는다.

## 12. fallback 정책

- LLM Timeout, Provider 오류 또는 출력 형식 검증 실패 시 Rule·ML 기반 템플릿 fallback을 사용할 수 있다.
- 템플릿이 사용 가능한 리포트를 만들면 `FALLBACK_COMPLETED`와 `reportSource = TEMPLATE_FALLBACK`으로 정상 조회된다.
- fallback이 성공해도 원래 LLM 실패 원인과 실제 발생한 토큰·비용을 기록한다.
- fallback도 실패하면 `FAILED`로 종료한다.
- fallback은 거래 위험 점수, 위험 등급, 위험 대응, 사건 상태나 최종 판정을 변경하지 않는다.
- fallback 발생에도 거래 탐지와 사건 처리는 계속된다.
- 출력 형식 검증에 실패한 LLM 원문을 정상 리포트로 노출하지 않는다.

### 12.1 HTTP 실패와 상태 완료의 경계

| 상황 | 외부 API 결과 |
| --- | --- |
| 생성 요청을 영속화하고 `aiRequestId`를 발급함 | `202 Accepted` |
| 이후 FastAPI Timeout·연결 실패 후 템플릿 성공 | `FALLBACK_COMPLETED` 상태 조회 |
| 이후 LLM 호출·출력 검증 실패 후 템플릿 성공 | `FALLBACK_COMPLETED` 상태 조회 |
| LLM과 템플릿 모두 실패 | `FAILED` 상태 조회 |
| 생성 요청 자체를 영속화하지 못함 | `500 INTERNAL_ERROR`; 성공으로 응답하지 않음 |
| 현재 HTTP 조회에 필수인 저장소가 일시 불가 | `503 DEPENDENCY_TIMEOUT` 또는 승인된 의존성 오류 후보 |

FastAPI·LLM의 비동기 처리 실패를 생성 접수 API의 HTTP 오류로 소급하지 않는다. Provider 원본 오류 메시지는 외부 API에 노출하지 않는다.

## 13. 오류 응답과 오류 코드

### 13.1 공통 구조

[`api-conventions.md`](./api-conventions.md)의 구조를 그대로 사용한다.

```json
{
  "code": "ERROR_CODE",
  "message": "민감정보를 제외한 오류 설명",
  "traceId": "trace_demo_error_01",
  "fieldErrors": []
}
```

### 13.2 기존 코드

| 코드 | HTTP 상태 | 사용 |
| --- | --- | --- |
| `VALIDATION_ERROR` | `400` 또는 `422` | JSON·필드·쿼리 형식 또는 도메인 Validation |
| `RESOURCE_NOT_FOUND` | `404` | 사건, 탐지 결과 또는 AI 요청 없음 |
| `IDEMPOTENCY_KEY_CONFLICT` | `409` | 같은 키에 다른 요청 내용 |
| `DEPENDENCY_TIMEOUT` | `503` | 현재 HTTP 처리를 위한 필수 의존성 Timeout |
| `INTERNAL_ERROR` | `500` | 공개할 수 없는 예기치 않은 서버 오류 |

### 13.3 새 코드 후보

기존 공통 코드와 의미가 겹치지 않는 경우에만 다음 후보를 추가한다.

| 코드 후보 | HTTP 상태 | 의미 |
| --- | --- | --- |
| `AI_REPORT_CASE_NOT_ELIGIBLE` | `422` | HIGH·CRITICAL 대상 조건 불충족 |
| `AI_REPORT_REGENERATION_NOT_ALLOWED` | `409` | 동일 정확 일치 조건의 강제 재생성 요청 |
| `FORBIDDEN` | `403` | 역할 또는 리소스 접근 권한 부족 |

FastAPI Timeout, FastAPI 연결 실패, LLM 호출 실패와 출력 형식 검증 실패는 접수 이후의 AI 처리 결과이면 HTTP 오류 코드가 아니라 `failureCode`로 기록한다.

### 13.4 오류 상황 매핑

| 상황 | 계약 |
| --- | --- |
| 요청 형식·필드 검증 실패 | `400/422 VALIDATION_ERROR` |
| 사건 없음 | `404 RESOURCE_NOT_FOUND` |
| 탐지 결과·버전 없음 또는 사건과 불일치 | `404 RESOURCE_NOT_FOUND` 또는 관계 검증용 `422 VALIDATION_ERROR` |
| HIGH·CRITICAL이 아닌 사건 | `422 AI_REPORT_CASE_NOT_ELIGIBLE` 후보 |
| 같은 키로 이미 처리 중인 동일 요청 | `202`와 기존 `aiRequestId` 반환 |
| 다른 키지만 처리 중인 정확 일치 요청 | `202`와 새 `aiRequestId`, 기존 실행의 `parentAiRequestId` 반환. 새 Provider 실행 없음 |
| Idempotency-Key 충돌 | `409 IDEMPOTENCY_KEY_CONFLICT` |
| 허용되지 않은 재생성 | `409 AI_REPORT_REGENERATION_NOT_ALLOWED` 후보 |
| FastAPI Timeout·연결 실패 | fallback 성공 시 정상 종료, 모두 실패 시 `FAILED` |
| LLM 호출 실패 | fallback 성공 시 정상 종료, 모두 실패 시 `FAILED` |
| LLM 출력 형식 검증 실패 | 원문 미노출, fallback 성공 시 정상 종료, 모두 실패 시 `FAILED` |
| 비용 집계 기간 오류 | `400/422 VALIDATION_ERROR`와 `INVALID_TIME_RANGE` 필드 코드 |
| 권한 부족 | `403 FORBIDDEN` 후보 |
| 내부 오류 | `500 INTERNAL_ERROR` |

## 14. 보안·개인정보·감사

- FDS 분석 담당자는 `GET /api/v1/cases/{caseId}/ai-reports/current`에서 리포트 상태, 안전한 `failureCode`, 본문, `reportSource`, `cacheHit`과 원본 요청 관계만 조회한다.
- `GET /api/v1/ai-report-requests/{aiRequestId}`, `GET /api/v1/ai-report-usage`와 `GET /api/v1/ai-report-usage/summary`의 Provider, 모델 시도, 토큰과 비용 상세는 플랫폼·클라우드 운영자에게만 제공한다.
- 실제 고객번호, 계좌번호, 비밀번호, OTP, 인증 토큰은 요청·응답·오류와 예시에 사용하지 않는다.
- Prompt 원문과 Provider 응답 원문은 사건·운영 조회 API에서 반환하지 않는다.
- 개인정보는 마스킹하거나 AI 처리에 필요한 최소 정보만 전달한다.
- 전체 Feature 벡터, 불필요한 행동 원문과 External Risk Provider 원문을 LLM에 전달하지 않는다.
- `traceId`와 `aiRequestId`로 Spring Boot, FastAPI와 LLM 호출 흐름을 연결한다.
- 누가 리포트 생성을 요청했는지 서버 사용자 문맥과 감사 기록으로 확인할 수 있어야 한다.
- 운영자는 AI 사용량과 장애 정보를 조회하지만 금융 개인정보 원문을 불필요하게 조회하지 않는다.
- 오류 메시지, `fieldErrors.reason`과 `failureCode`에 Provider 원문이나 민감정보를 포함하지 않는다.
- 접근 거부, 중복 요청, 재생성 요청, 상태 변경, 캐시 적중, fallback과 최종 실패를 감사 가능하게 기록한다.
- 문서 예시는 모두 가상 식별자와 가상 데이터이다.

## 15. 사용자 결정 필요 사항

이번 검토에서 승인된 생성 대상, 멱등성, 재시도, 강제 재생성, 현재 리포트 선택, 비용 통화, 역할별 노출, 페이지네이션·기간, 대표 DetectionResult와 캐시 요청 이력 정책은 확정 정책으로 본문에 반영했다. 남은 항목은 다음 다섯 개뿐이다.

| 항목 | 구분 | 남은 결정과 API 영향 |
| --- | --- | --- |
| AI 요청·리포트·사용량 보존 기간 | 후속 상세 설계 | 목록에서 조회 가능한 기간, 만료·비식별화와 감사 참조 정책에 영향. 초기 API 구현 자체를 막지는 않음 |
| 외부 요청 이력과 공유 실행 이력의 ERD 구조 | **구현 전 필수 결정** | 새 `aiRequestId`, `parentAiRequestId`, `sourceAiRequestId`, Provider `attempts`의 관계와 중복 방지를 저장하려면 구현 전에 관계 확정 필요 |
| `caseAnalysisSnapshotVersion` 향후 도입 여부 | 후속 ADR·ERD 설계 | 초기 대표 DetectionResult 제약을 확장할 후보. 현재 네 요소 캐시 키 기반 초기 구현을 막지는 않음 |
| Provider 가격표 버전 관리 방식 | 후속 상세 설계 | `estimatedCost` 재현성과 가격 기준 시각에 영향. 초기에는 Provider 원통화의 추정 비용으로 시작 가능 |
| 인증·인가 실제 구현과 Mock Actor 전달 방식 | **구현 전 필수 결정** | FDS 분석 담당자와 플랫폼·클라우드 운영자의 권한 분리 및 요청자 감사 기록을 실제 엔드포인트에서 검증하려면 구현 전에 확정 필요 |

## 16. 제외 범위

- Spring Boot Controller, DTO, Service와 Repository
- FastAPI 엔드포인트와 LLM Provider 연동
- 실제 모델 선정
- 실제 Prompt 전문
- Provider API Key와 인증정보
- 실제 Provider 가격표
- 환율 계산과 청구 금액 정산
- Redis 구현
- Kafka Topic·Consumer·DLQ 구현
- Scheduler와 Worker 구현
- PostgreSQL 스키마 변경
- 프론트엔드 화면
- 시맨틱 캐시
- 범용 FDS 챗봇
- Investigation Copilot
- 실제 거래 차단과 고객 제재
- 측정되지 않은 성능·비용 개선 수치

## 17. 기존 문서와의 정합성 및 후속 문서 작업

- `reportStatus`는 기존 다섯 상태를 유지하며 요청에서 제시한 `REQUESTED/PROCESSING` 후보를 사용하지 않는다.
- `riskLevel`은 `LOW/MEDIUM/HIGH/CRITICAL`을 사용하고 생성 대상은 HIGH·CRITICAL이다.
- `caseStatus`와 `finalDisposition`은 AI 리포트 상태와 분리한다.
- `detectionResultVersion`은 integer 버전으로 기존 탐지 API와 일치시킨다.
- 요청자는 사건 API와 같이 신뢰할 수 있는 서버 사용자 문맥에서 결정한다.
- 비용은 공통 금액 원칙에 따라 소수점 문자열로 표현한다.
- 목록은 공통 페이지 응답을 사용한다.
- 오류는 공통 `code/message/traceId/fieldErrors` 구조를 사용한다.

다음은 이번 문서에서 기존 파일을 수정하지 않고 후속 작업으로 남긴다.

- ERD에는 새 외부 요청 이력, 공유 실행과 Provider 시도 이력의 관계가 확정되어 있지 않다. 이 관계는 구현 전 필수 후속 과제이다.
- ERD의 콘텐츠 출처 후보는 이 API의 `reportSource`와 같은 최초 생성 출처 의미로 매핑하고 캐시 여부는 `cacheHit`과 `sourceAiRequestId`로 분리해야 한다.
- 초기에는 대표 DetectionResult 버전을 사용하며 복수 거래 사건 전체 입력을 표현하지 못한다. `caseAnalysisSnapshotVersion`은 후속 ADR·ERD 후보이다.
- Provider 가격표 버전 관리, 비용 데이터 보존 기간과 확정 정산은 미확정이다. 초기 계약은 Provider 원통화만 기록하고 환율 환산하지 않는다.
- `docs/04-database/` 경로는 현재 저장소에 없고 논리 ERD는 `docs/02-architecture/domain-erd.md`에 존재한다.

## 18. 구현 전 검증 체크리스트

- [ ] 구현 전 필수 결정인 요청·실행 이력 관계와 인증·인가 방식이 승인되었는가
- [ ] API 공통 시간·금액·페이지네이션·오류 구조와 일치하는가
- [ ] `caseId`, `detectionResultVersion`, `aiRequestId`, `traceId` 의미가 구현 전체에서 일치하는가
- [ ] 사건의 대표 또는 채택 DetectionResult 관계를 검증하는가
- [ ] Spring Boot와 FastAPI 책임 경계를 지키는가
- [ ] 외부 생성 API가 `202 Accepted`와 상태 조회 흐름을 유지하는가
- [ ] HIGH·CRITICAL 대상 정책을 검증하는가
- [ ] 캐시 키 네 요소를 모두 사용하고 다른 사건 결과를 재사용하지 않는가
- [ ] `reportSource`가 `LLM`, `TEMPLATE_FALLBACK`만 사용하고 캐시는 `cacheHit`과 `sourceAiRequestId`로 구분되는가
- [ ] 서로 다른 키의 진행 중 동일 요청에 새 `aiRequestId`를 반환하고 `parentAiRequestId`로 공유 실행을 참조하는가
- [ ] Timeout·연결 실패만 최대 1회 자동 재시도하고 각 시도를 같은 `aiRequestId`의 `attempts`에 기록하는가
- [ ] 진행 중 동일 요청이 Provider 실행을 중복 생성하지 않는가
- [ ] 늦은 LLM 응답이 fallback·실패 결과를 이력 없이 덮어쓰지 않는가
- [ ] 실패한 Provider 호출의 토큰과 추정 비용도 누락하지 않는가
- [ ] 캐시 요청에 Provider 호출 이력, 가상 토큰과 가상 비용을 만들지 않는가
- [ ] 서로 다른 통화를 환산 없이 합산하지 않는가
- [ ] 비용을 추정값으로 표시하고 실제 청구액으로 단정하지 않는가
- [ ] Prompt·Provider 응답 원문과 개인정보를 반환하지 않는가
- [ ] 요청자, 캐시, fallback, 재생성과 실패가 감사 가능한가
- [ ] 문서·코드·테스트에서 구현되지 않은 기능을 완료로 표현하지 않는가
