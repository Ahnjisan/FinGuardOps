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
- `Idempotency-Key`, 요청 fingerprint, 정확 일치 조건과 동시 요청을 확인한다.
- 외부 요청 `AiReportRequest`의 `aiRequestId`와 실제 실행 `AiReportExecution`의 `executionId`를 구분해 관리한다.
- 요청이 신규 실행, 진행 중 실행 공유 또는 완료 리포트 캐시 재사용 중 어느 경로로 처리되었는지 기록한다.
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
| `aiRequestId` | 외부 AI 리포트 요청인 `AiReportRequest`의 업무 식별자 |
| `executionId` | 실제 논리 실행인 `AiReportExecution`의 업무 식별자. 캐시 적중 요청에는 새 실행이 없으므로 null |
| `executionShared` | 외부 요청이 기존 진행 실행을 공유했는지 나타내는 boolean 응답값 |
| `initiatingAiRequestId` | 실행을 최초 생성한 외부 요청 식별자. `executionId`가 있을 때 `initiatingRequestRef`에서 파생하며 캐시 적중이면 null |
| `sourceAiRequestId` | 캐시 적중 요청이 재사용한 리포트의 최초 생성 요청 식별자. 결과 계보에서 파생하며 캐시가 아니면 null |
| `reportId` | 검증을 통과해 저장된 `AiReport` 결과 업무 식별자. 처리 중·실패이면 null |
| `traceId` | Spring Boot, FastAPI와 LLM Provider 호출 흐름 추적 식별자 |
| `promptVersion` | 리포트 생성 지침의 버전 |
| `modelVersion` | 정확 일치 조건에 사용한 모델 버전 |

`aiRequestId`는 Provider 호출이나 실행 식별자가 아니다. 새로운 `Idempotency-Key`의 유효한 외부 요청마다 새 `AiReportRequest`와 `aiRequestId`를 발급한다. 같은 키와 같은 요청의 재전송은 `FAILED`를 포함한 기존 요청과 기존 `aiRequestId`를 반환한다.

`executionId`는 실제 모델 호출 또는 템플릿 fallback 처리를 수행하는 논리 실행을 식별한다. 신규 실행을 만든 요청과 그 실행을 공유하는 요청은 같은 `executionId`를 반환한다. 완료된 `AiReport`를 캐시로 재사용하는 요청은 `executionId = null`이다.

`executionShared`는 외부 요청과 실행의 연결 방식을 나타내는 boolean 응답값이다.

| 요청 처리 방식 | `executionId` | `executionShared` | `initiatingAiRequestId` |
| --- | --- | --- | --- |
| 새 실행을 최초 생성 | 새 실행 ID | `false` | 현재 `aiRequestId` |
| 기존 진행 중 실행 공유 | 기존 실행 ID | `true` | 실행을 최초 생성한 요청 ID. 현재 `aiRequestId`와 다를 수 있음 |
| 완료 리포트 캐시 재사용 | `null` | `false` | `null` |

`executionShared`는 현재 HTTP 호출이 같은 멱등 요청의 재전송인지 여부가 아니라, 저장된 `AiReportRequest`가 기존 진행 실행에 연결되었는지를 나타낸다. 따라서 같은 `Idempotency-Key` 재전송에는 기존 요청에 기록된 값이 그대로 반환된다.

`sourceAiRequestId`는 영속 저장 필드가 아니며 `cacheHit = true`인 요청에서만 다음 관계로 파생한다.

```text
AiReportRequest.resolvedReportRef
→ AiReport.executionRef
→ AiReportExecution.initiatingRequestRef
→ AiReportRequest.aiRequestId
```

신규 실행과 진행 중 실행 공유 요청의 `sourceAiRequestId`는 null이다. 요청 간 부모·자식 관계는 계약에 두지 않으며 실행 공유는 `executionId`, `executionShared`, `initiatingAiRequestId`로 표현한다.

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
- 가격표 버전, 환율과 확정 정산은 제외 범위이다.

단건 실행의 토큰·비용 합계는 다음 원칙을 사용한다.

- attempts가 없으면 `inputTokens`, `outputTokens`, `totalTokens`는 0이다.
- attempts가 하나 이상이면 각 토큰 필드별로 모든 attempt에서 값이 확인된 경우에만 합계를 반환한다.
- 하나라도 해당 토큰 필드가 null이면 실행 합계의 해당 필드도 null이다. 확인되지 않은 토큰을 0으로 간주하지 않는다.
- 모든 실제 attempt의 `estimatedCost`와 `costCurrency`가 확인되고 통화가 하나이면 전체 `estimatedCost`, `costCurrency`와 단일 항목 `costBreakdown`을 반환한다.
- 모든 실제 attempt의 비용과 통화가 확인되고 통화가 여러 개이면 `estimatedCost = null`, `costCurrency = null`로 두고 `costBreakdown`에 통화별 전체 합계를 반환한다.
- attempt가 하나 이상인데 일부 attempt의 비용 또는 통화가 null이면 전체 비용이 불완전하므로 `estimatedCost = null`, `costCurrency = null`, `costBreakdown = null`로 반환한다. 확인된 일부 비용만 완전한 총비용처럼 반환하지 않는다.
- attempts가 없으면 `estimatedCost = null`, `costCurrency = null`, `costBreakdown = []`이다. 빈 배열은 비용이 미측정이라는 뜻이 아니라 실제 Provider 호출이 없다는 뜻이다.
- 캐시 적중 요청과 Provider 호출 전 대기 상태에는 가상 attempt, 0 토큰 사용량 행 또는 0 비용 행을 만들지 않는다.

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

| 상태 | 요청 응답에서의 의미 | 실행과 결과의 의미 | 종료 상태 | 본문 |
| --- | --- | --- | --- | --- |
| `PENDING` | 외부 요청이 실행 대기 중 | 연결 실행이 아직 시작되지 않음 | 아니요 | `null` |
| `GENERATING` | 외부 요청이 연결 실행의 생성 결과를 기다리는 중 | 모델 라우팅, LLM 호출, 검증, 승인된 재시도 또는 fallback 처리 중 | 아니요 | `null` |
| `COMPLETED` | 요청이 검증된 LLM 결과를 받음 | LLM 실행과 `AiReport` 생성 완료 또는 기존 LLM 결과 캐시 재사용 | 예 | 필수 |
| `FALLBACK_COMPLETED` | 요청이 템플릿 결과를 받음 | `TEMPLATE_FALLBACK` 결과 생성 완료 또는 기존 fallback 결과 캐시 재사용 | 예 | 필수 |
| `FAILED` | 연결 실행이 최종 실패해 요청이 결과를 받지 못함 | LLM과 fallback 모두 사용 가능한 리포트를 만들지 못함 | 예 | `null` |

`AiReportRequest.reportStatus`는 외부 요청이 받을 결과의 상태이고 `AiReportExecution.executionStatus`는 실제 실행 단계이며, 같은 값 집합을 사용해도 소유자와 의미가 다르다. 캐시 적중 요청에는 실행 상태가 없고 기존 `AiReport.reportStatus`가 요청의 종료 상태로 투영된다.

`RETRYING`, `CANCELLED`, 캐시 적중과 실행 공유를 새 상태로 추가하지 않는다. 재시도는 `GENERATING` 실행의 `attemptNumber` 순서로, 캐시는 `cacheHit`과 결과 관계로, 실행 공유는 `executionId`, `executionShared`, `initiatingAiRequestId`로 표현한다.

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

| 처리 결과 | `reportStatus` | `reportSource` | `cacheHit` | `executionShared` | `sourceAiRequestId` |
| --- | --- | --- | --- | --- | --- |
| LLM 신규 생성 | `COMPLETED` | `LLM` | `false` | `false` | `null` |
| 템플릿 fallback 신규 생성 | `FALLBACK_COMPLETED` | `TEMPLATE_FALLBACK` | `false` | `false` | `null` |
| LLM 원본 캐시 재사용 | `COMPLETED` | `LLM` | `true` | `false` | 원본 생성 요청 ID |
| fallback 원본 캐시 재사용 | `FALLBACK_COMPLETED` | `TEMPLATE_FALLBACK` | `true` | `false` | 원본 생성 요청 ID |
| 진행 중 동일 실행 공유 | `PENDING` 또는 `GENERATING`, 종료 후 실행 결과를 따름 | 처리 중 null, 완료 후 공유 실행 결과의 최초 생성 출처 | `false` | `true` | `null` |

`PENDING`, `GENERATING`, `FAILED`에서는 사용 가능한 본문 출처가 없으므로 `reportSource = null`이다. 캐시가 아니면 `sourceAiRequestId = null`이다. 캐시를 `reportSource` Enum 값으로 사용하지 않는다. 캐시 적중 요청에는 새 Provider 실행과 `ProviderCallAttempt`가 없다. 진행 중 실행 공유 요청의 attempts는 플랫폼·클라우드 운영자 전용 단건 운영 상세 API에서만 조회할 수 있으며, 요청별 호출을 새로 만든 것은 아니다.

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
- 생성 가능한 구체적인 `caseStatus`는 사용자 결정 사항이다. 승인된 상태가 아닌 사건이면 `409 Conflict`와 기존 사건 계약의 `CASE_STATUS_CONFLICT`를 반환한다.
- Prompt와 모델 버전은 승인된 서버 정책으로 선택하고 요청의 정확 일치 조건에 고정한다.
- 정확 일치 기준은 `caseId + detectionResultVersion + promptVersion + modelVersion` 네 요소를 모두 사용한다.
- Reason Code만 같거나 시맨틱하게 유사하다는 이유로 다른 사건의 리포트를 재사용하지 않는다.
- 리포트 생성은 거래·사건 상태를 변경하지 않는다.

기본 Validation을 통과한 리포트 생성 요청은 다음 순서로 처리한다.

1. `Idempotency-Key`와 정규화된 요청 fingerprint를 확인한다.
2. 같은 키와 같은 fingerprint의 요청이 존재하면 `FAILED`를 포함한 기존 `AiReportRequest`와 기존 `aiRequestId`를 반환한다. 새 요청·실행·attempt·결과를 만들지 않는다.
3. 같은 키에 다른 요청 내용이 들어오면 기존 요청을 변경하지 않고 `409 Conflict`와 `IDEMPOTENCY_KEY_CONFLICT`를 반환한다.
4. 새 키의 유효한 요청에는 새 `AiReportRequest`와 `aiRequestId`를 발급한다. 완료된 정확 일치 `AiReport`가 있으면 `cacheHit = true`, `executionId = null`, `executionShared = false`로 기록하고 기존 리포트를 반환한다.
5. 재사용 가능한 완료 결과가 없고 `PENDING` 또는 `GENERATING`인 정확 일치 실행이 있으면 새 요청을 기존 `executionId`에 연결하고 `executionShared = true`로 기록한다.
6. 재사용 가능한 리포트와 활성 실행이 모두 없으면 새 `AiReportExecution`을 생성한다. 현재 요청이 실행의 `initiatingRequestRef`가 되므로 `executionShared = false`, `initiatingAiRequestId = aiRequestId`이다.
7. 과거 정확 일치 실행이 `FAILED`인 이력만 있고 재사용 가능한 결과와 활성 실행이 없으면 새로운 `Idempotency-Key`의 요청은 새 실행을 만들 수 있다. 새 실행에도 기존 자동 재시도 정책을 동일하게 적용한다.

완료된 정확 일치 결과에 대해 `regenerationReason`으로 강제 재생성 의사를 보였지만 네 요소가 바뀌지 않은 요청은 4단계의 새 `AiReportRequest`를 만들기 전에 `409 Conflict`와 기존 공통 코드 `STATE_TRANSITION_NOT_ALLOWED`로 거부한다. 일반 조회·재사용 의도의 정확 일치 요청은 4단계의 캐시 재사용으로 처리한다.

### 5.7 성공 응답

새 실행을 생성하거나 진행 중 실행을 공유한 요청은 `202 Accepted`를 반환한다. 완료 리포트를 캐시로 즉시 재사용한 요청과 종료된 동일 멱등 요청의 재전송은 `200 OK`로 기존 결과를 반환한다.

생성 요청 응답은 다음 공통 필드를 사용한다.

| 필드 | 설명 |
| --- | --- |
| `aiRequestId` | 외부 `AiReportRequest` 식별자 |
| `executionId` | 연결된 실제 실행 식별자. 캐시 적중이면 null |
| `executionShared` | 새 실행 최초 요청 false, 진행 실행 공유 true, 캐시 적중 false |
| `initiatingAiRequestId` | 실행 최초 요청 식별자. 실행이 있으면 `initiatingRequestRef`에서 파생하고 캐시 적중이면 null |
| `reportId` | 요청이 최종 제공하는 `AiReport` 식별자. 처리 중·실패이면 null |
| `sourceAiRequestId` | 캐시 원본 요청의 파생 식별자. `cacheHit = true`일 때만 값이 있음 |
| `cacheHit` | 완료된 기존 `AiReport`를 재사용했는지 |
| `reportStatus` | 외부 요청 관점 상태 |
| `reportSource` | 결과 최초 생성 출처. 처리 중·실패이면 null |
| `requestedAt` | 외부 요청 최초 접수 시각 |
| `resultLocation` | 단건 운영 상세 조회 경로 |
| `traceId` | 현재 HTTP 응답의 추적 식별자 |

생성 API는 FDS 분석 담당자용 업무 API이므로 연결 실행의 Provider, 모델, attempts, 토큰과 비용 상세를 반환하지 않는다. 같은 `Idempotency-Key`의 재전송으로 기존 요청 상태를 반환할 때도 이 운영 정보를 노출하지 않는다.

#### 5.7.1 새 실행을 생성한 요청

```http
HTTP/1.1 202 Accepted
Location: /api/v1/ai-report-requests/air_demo_20260726_0001
Content-Type: application/json
```

```json
{
  "aiRequestId": "air_demo_20260726_0001",
  "executionId": "aiexec_demo_20260726_0001",
  "executionShared": false,
  "initiatingAiRequestId": "air_demo_20260726_0001",
  "reportId": null,
  "caseId": "case_demo_20260724_0031",
  "detectionResultVersion": 3,
  "reportStatus": "PENDING",
  "reportSource": null,
  "sourceAiRequestId": null,
  "cacheHit": false,
  "requestedAt": "2026-07-26T02:10:00Z",
  "resultLocation": "/api/v1/ai-report-requests/air_demo_20260726_0001",
  "traceId": "trace_demo_ai_request_01"
}
```

생성 응답은 HTTP `Location` 헤더와 본문의 `resultLocation`을 함께 반환한다.

#### 5.7.2 같은 `Idempotency-Key` 재전송

다음 예시는 5.7.1의 요청이 `PENDING`인 동안 같은 키와 같은 fingerprint로 재전송된 경우이다. 기존 요청 자체를 반환하므로 `aiRequestId`, `executionId`, `requestedAt`, `executionShared`가 바뀌지 않고 새 attempt도 없다.

```http
HTTP/1.1 202 Accepted
Location: /api/v1/ai-report-requests/air_demo_20260726_0001
Content-Type: application/json
```

```json
{
  "aiRequestId": "air_demo_20260726_0001",
  "executionId": "aiexec_demo_20260726_0001",
  "executionShared": false,
  "initiatingAiRequestId": "air_demo_20260726_0001",
  "reportId": null,
  "caseId": "case_demo_20260724_0031",
  "detectionResultVersion": 3,
  "reportStatus": "PENDING",
  "reportSource": null,
  "sourceAiRequestId": null,
  "cacheHit": false,
  "requestedAt": "2026-07-26T02:10:00Z",
  "resultLocation": "/api/v1/ai-report-requests/air_demo_20260726_0001",
  "traceId": "trace_demo_ai_request_replay_01"
}
```

응답의 `traceId`는 현재 재전송 HTTP 흐름을 나타낸다. 저장된 최초 요청 흐름은 단건 운영 상세의 별도 과거 `traceId`로 조회한다.

#### 5.7.3 기존 진행 중 실행을 공유한 요청

```http
HTTP/1.1 202 Accepted
Location: /api/v1/ai-report-requests/air_demo_20260726_0002
Content-Type: application/json
```

```json
{
  "aiRequestId": "air_demo_20260726_0002",
  "executionId": "aiexec_demo_20260726_0001",
  "executionShared": true,
  "initiatingAiRequestId": "air_demo_20260726_0001",
  "reportId": null,
  "caseId": "case_demo_20260724_0031",
  "detectionResultVersion": 3,
  "reportStatus": "GENERATING",
  "reportSource": null,
  "sourceAiRequestId": null,
  "cacheHit": false,
  "requestedAt": "2026-07-26T02:10:02Z",
  "resultLocation": "/api/v1/ai-report-requests/air_demo_20260726_0002",
  "traceId": "trace_demo_ai_request_shared_01"
}
```

같은 `executionId`를 공유하더라도 이 생성 응답에는 Provider 호출 상세를 포함하지 않는다. 플랫폼·클라우드 운영자는 단건 운영 상세 API에서 연결 실행의 attempts를 조회할 수 있으며, 동일 attempts가 보이더라도 요청별 Provider 호출이 발생했다는 뜻은 아니다.

#### 5.7.4 완료된 기존 리포트를 캐시로 재사용한 요청

```http
HTTP/1.1 200 OK
Location: /api/v1/ai-report-requests/air_demo_20260726_0003
Content-Type: application/json
```

```json
{
  "aiRequestId": "air_demo_20260726_0003",
  "executionId": null,
  "executionShared": false,
  "initiatingAiRequestId": null,
  "reportId": "aireport_demo_20260726_0001",
  "caseId": "case_demo_20260724_0031",
  "detectionResultVersion": 3,
  "reportStatus": "COMPLETED",
  "reportSource": "LLM",
  "sourceAiRequestId": "air_demo_20260726_0001",
  "cacheHit": true,
  "requestedAt": "2026-07-26T02:30:00Z",
  "resultLocation": "/api/v1/ai-report-requests/air_demo_20260726_0003",
  "traceId": "trace_demo_ai_request_cache_01"
}
```

캐시 요청의 `sourceAiRequestId`는 `resolvedReportRef → executionRef → initiatingRequestRef` 계보에서 파생한다. 이 경로에는 새 실행, Provider attempt, 토큰과 비용이 생성되지 않는다.

### 5.8 멱등성

- 같은 키와 같은 정규화 요청이 `PENDING` 또는 `GENERATING`이면 `202 Accepted`로 기존 요청과 현재 상태를 반환한다.
- 같은 키와 같은 정규화 요청이 `COMPLETED`, `FALLBACK_COMPLETED` 또는 `FAILED`이면 `200 OK`로 기존 요청 결과를 반환한다.
- 같은 키 재전송은 `FAILED` 요청도 다시 실행하지 않는다.
- 같은 키에 다른 `caseId`, 요청 본문 또는 fingerprint 비교 필드가 오면 `409 Conflict`와 `IDEMPOTENCY_KEY_CONFLICT`를 반환한다.
- 서로 다른 키로 같은 정확 일치 조건이 도착했고 기존 실행이 `PENDING` 또는 `GENERATING`이면 새 외부 요청 이력과 새 `aiRequestId`를 생성하고 같은 `executionId`에 연결한다. `executionShared = true`이며 새 Provider 실행을 생성하지 않는다.
- 서로 다른 키로 완료된 정확 일치 결과를 요청하면 새 외부 요청 이력과 새 `aiRequestId`를 발급하고 `executionId = null`, `executionShared = false`, `cacheHit = true`로 기록한다.
- `aiRequestId` 신규 발급 여부와 별개로 같은 정확 일치 리포트 본문과 사용량을 중복 생성하지 않는다.
- 이전 정확 일치 실행이 `FAILED`이고 재사용 가능한 결과와 활성 실행이 없으면 새 키로 새 요청과 실행을 만들 수 있다. 새 요청에도 최초 호출 포함 최대 2회, 자동 재시도 최대 1회 정책을 적용한다.
- 정확 일치 `AiReport`가 존재하면 새 실행을 만들지 않고 캐시 재사용하며, 완료된 동일 결과의 강제 재생성은 허용하지 않는다.

### 5.9 주요 오류

| 상태 | 코드 | 상황 |
| --- | --- | --- |
| `400 Bad Request` | `VALIDATION_ERROR` | JSON, 식별자, 필수 헤더 또는 필드 형식 오류 |
| `404 Not Found` | `RESOURCE_NOT_FOUND` | 사건 또는 탐지 결과를 찾을 수 없음 |
| `409 Conflict` | `IDEMPOTENCY_KEY_CONFLICT` | 같은 키에 다른 요청 내용 |
| `409 Conflict` | `CASE_STATUS_CONFLICT` | 현재 사건 상태에서 AI 리포트 생성을 허용하지 않음 |
| `409 Conflict` | `STATE_TRANSITION_NOT_ALLOWED` | 완료된 동일 정확 일치 결과의 강제 재생성 요청 |
| `422 Unprocessable Entity` | `VALIDATION_ERROR` | HIGH·CRITICAL이 아닌 사건 |
| `403 Forbidden` | `FORBIDDEN` 후보 | 생성 권한 부족 |
| `500 Internal Server Error` | `INTERNAL_ERROR` | 요청 접수·식별자 발급·상태 저장 중 예기치 않은 오류 |

동일 조건의 처리 중 요청은 오류가 아니다. 같은 `Idempotency-Key`이면 기존 `aiRequestId`를 반환하고, 다른 키이면 새 `aiRequestId`를 같은 `executionId`에 연결해 `executionShared = true`로 반환한다.

```json
{
  "code": "VALIDATION_ERROR",
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
- 비노출: Provider, 모델, `ProviderCallAttempt`, attempts, 토큰과 비용 상세. 이 정보는 `GET /api/v1/ai-report-requests/{aiRequestId}` 운영 상세 API에서만 제공

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
| `reportId` | string | 선택된 `AiReport` 결과 식별자 |
| `executionId` | string | 이 결과를 최초 생성한 `AiReportExecution` 식별자 |
| `initiatingAiRequestId` | string | 결과 실행을 최초 생성한 외부 요청 식별자 |
| `caseId` | string | 사건 식별자 |
| `detectionResultVersion` | integer | 리포트 근거 탐지 버전 |
| `reportStatus` | string | `COMPLETED` 또는 `FALLBACK_COMPLETED` |
| `reportSource` | string | 본문 최초 생성 출처인 `LLM` 또는 `TEMPLATE_FALLBACK` |
| `summary` | string | 사건 요약 |
| `keyReasons` | array | Rule·ML 탐지 근거 요약 목록 |
| `timelineSummary` | string | 행동 타임라인 요약 |
| `investigationChecklist` | array | 담당자 확인 항목 |
| `promptVersion` | string | 사용 Prompt 버전 |
| `modelVersion` | string | 정확 일치 조건의 모델 버전 |
| `generatedAt` | string | 리포트가 실제로 최초 사용 가능해진 UTC 시각. 현재 리포트 우선순위의 첫 번째 기준은 아님 |
| `failureCode` | string 또는 null | fallback의 원인이 된 실패 분류. 정상 LLM 결과는 null |
| `traceId` | string | 해당 리포트를 만든 실행의 과거 추적 식별자 |

`latestRequest` 필드:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `aiRequestId` | string | 최신 요청 식별자 |
| `executionId` | string 또는 null | 최신 요청이 생성하거나 공유한 실행. 캐시 적중이면 null |
| `executionShared` | boolean | 최신 요청이 기존 진행 실행을 공유했는지 |
| `initiatingAiRequestId` | string 또는 null | 실행 최초 요청 식별자. 캐시 적중이면 null |
| `reportId` | string 또는 null | `resolvedReportRef`가 가리키는 결과 식별자 |
| `detectionResultVersion` | integer | 최신 요청의 탐지 버전 |
| `reportStatus` | string | 전체 상태 Enum |
| `reportSource` | string 또는 null | 처리 중·실패이면 null |
| `cacheHit` | boolean | 최신 요청의 캐시 재사용 여부 |
| `sourceAiRequestId` | string 또는 null | 캐시 적중일 때 결과 계보에서 파생한 원본 요청 ID |
| `requestedAt` | string | 접수 시각 |
| `generatedAt` | string 또는 null | 요청이 참조하는 리포트가 실제 사용 가능해진 시각. 현재 리포트 우선순위는 실행 최초 요청의 `requestedAt`으로 결정 |
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
- `currentReport`는 결과 자체와 그 생성 계보를 나타내므로 요청 처리 방식인 `cacheHit`, `executionShared`, `sourceAiRequestId`를 포함하지 않는다. 이 값들은 `latestRequest`에서 확인한다.

현재 유효 리포트는 `COMPLETED` 또는 `FALLBACK_COMPLETED` 결과만 후보로 삼고 다음 순서로 선택한다.

1. 결과를 만든 실행의 `initiatingRequestRef`가 가리키는 `AiReportRequest.requestedAt DESC`
2. 실행 최초 요청의 `aiRequestId DESC`

`generatedAt`은 리포트가 실제 사용 가능해진 시각으로 유지하지만 선택 우선순위의 첫 번째 기준으로 사용하지 않는다. 캐시 요청은 기존 `AiReport`를 재사용하므로 동일 결과의 순서를 올리지 않는다. 더 오래된 initiating request의 실행이 늦게 완료되어도 더 최근 initiating request가 만든 성공 결과를 덮어쓰지 않는다.

### 6.5 성공 응답 예시

```json
{
  "caseId": "case_demo_20260724_0031",
  "currentReport": {
    "reportId": "aireport_demo_20260726_0001",
    "executionId": "aiexec_demo_20260726_0001",
    "initiatingAiRequestId": "air_demo_20260726_0001",
    "caseId": "case_demo_20260724_0031",
    "detectionResultVersion": 3,
    "reportStatus": "COMPLETED",
    "reportSource": "LLM",
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
    "executionId": "aiexec_demo_20260726_0002",
    "executionShared": false,
    "initiatingAiRequestId": "air_demo_20260726_0002",
    "reportId": null,
    "detectionResultVersion": 4,
    "reportStatus": "GENERATING",
    "reportSource": null,
    "cacheHit": false,
    "sourceAiRequestId": null,
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
- 노출 범위: 사건 리포트 상태, 안전한 `failureCode`, 리포트 본문과 `reportSource`, 최신 요청의 `cacheHit`·실행 공유·원본 요청 관계
- 비노출: Provider, 모델, `ProviderCallAttempt`, attempts, 토큰과 비용 상세. 생성 API와 사건별 현재 리포트 API에서는 제공하지 않고 플랫폼·클라우드 운영자 전용 단건 운영 상세 API에서만 제공
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
| `aiRequestId` | string | 외부 `AiReportRequest` 식별자 |
| `executionId` | string 또는 null | 연결된 `AiReportExecution` 식별자. 캐시 적중이면 null |
| `executionShared` | boolean | 이 요청이 기존 진행 실행에 연결되었는지 |
| `initiatingAiRequestId` | string 또는 null | 실행의 `initiatingRequestRef`에서 파생한 최초 요청 ID. 캐시 적중이면 null |
| `reportId` | string 또는 null | 요청의 `resolvedReportRef`가 가리키는 결과 ID |
| `caseId` | string | 대상 사건 |
| `detectionResultVersion` | integer | 요청에 고정된 탐지 버전 |
| `reportStatus` | string | 리포트 상태 |
| `reportSource` | string 또는 null | 본문 최초 생성 출처. `LLM`, `TEMPLATE_FALLBACK` 또는 null |
| `sourceAiRequestId` | string 또는 null | 캐시 적중 시 결과 계보에서 파생한 원본 생성 요청 ID |
| `lastProvider` | string 또는 null | 연결 실행의 마지막 실제 Provider 호출. 실행·attempt가 없으면 null |
| `lastModel` | string 또는 null | 연결 실행의 마지막 실제 Provider 모델명. 실행·attempt가 없으면 null |
| `promptVersion` | string | Prompt 버전 |
| `modelVersion` | string | 정확 일치 기준 모델 버전 |
| `inputTokens` | integer 또는 null | attempts가 없으면 0. 모든 attempt에서 입력 토큰이 확인될 때만 합계, 하나라도 null이면 null |
| `outputTokens` | integer 또는 null | attempts가 없으면 0. 모든 attempt에서 출력 토큰이 확인될 때만 합계, 하나라도 null이면 null |
| `totalTokens` | integer 또는 null | attempts가 없으면 0. 모든 attempt에서 전체 토큰이 확인될 때만 합계, 하나라도 null이면 null |
| `estimatedCost` | string 또는 null | 모든 실제 attempt의 비용·통화가 확인된 단일 통화 실행의 전체 추정 비용. 무호출·다중 통화·불완전 비용이면 null |
| `costCurrency` | string 또는 null | `estimatedCost`의 통화 |
| `costBreakdown` | array 또는 null | 비용이 완전하면 통화별 전체 합계, attempts가 없으면 빈 배열, 일부 비용·통화가 미측정이면 null |
| `latencyMs` | integer 또는 null | 외부 요청 접수부터 요청 종료까지의 시간. 미종료이면 null |
| `cacheHit` | boolean | 이번 요청이 정확 일치 기존 리포트를 재사용했는지 |
| `fallbackUsed` | boolean | 연결 실행의 결과가 템플릿 fallback인지. 캐시 적중은 원본 출처와 무관하게 false |
| `usageFinalized` | boolean | 실행이 종료되어 더 이상 attempt가 추가되지 않는지. true여도 Provider 측정 실패로 토큰·비용 합계가 null일 수 있음 |
| `requestedByRef` | string | 서버 사용자 문맥에서 얻은 제한된 요청자 참조값 |
| `requestedAt` | string | 요청 접수 시각 |
| `completedAt` | string 또는 null | 종료 상태 확정 시각 |
| `failureCode` | string 또는 null | 실패 또는 fallback 원인 분류 |
| `attempts` | array | 연결된 `AiReportExecution`의 실제 Provider 호출 이력. 캐시·호출 전 대기는 빈 배열 |
| `traceId` | string | 과거 AI 생성 흐름 추적 식별자 |
| `queryTraceId` | string | 현재 조회 HTTP 요청 추적 식별자 |

`attempts`는 `AiReportRequest`가 아니라 `AiReportExecution`에 귀속된다. 같은 `executionId`를 공유하는 요청은 조회 시 같은 attempts를 볼 수 있지만 이는 요청별 Provider 호출이 발생했다는 뜻이 아니다. 공유 요청별로 attempt, 토큰 또는 비용 원본을 복제하지 않는다.

`attempts` 항목은 다음 필드를 유지한다.

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| `attemptNumber` | integer | 실행 안에서 1부터 시작하는 실제 호출 순서 |
| `provider` | string | 실제 호출 Provider |
| `model` | string | 실제 호출 모델 |
| `outcome` | string | `SUCCEEDED`, `FAILED`, `OUTPUT_REJECTED` 등 호출 결과 |
| `inputTokens` | integer 또는 null | Provider가 확인한 실제 입력 토큰 |
| `outputTokens` | integer 또는 null | Provider가 확인한 실제 출력 토큰 |
| `totalTokens` | integer 또는 null | Provider가 확인한 실제 총 토큰 |
| `estimatedCost` | string 또는 null | 실제 확인 사용량에 근거한 추정 비용 |
| `costCurrency` | string 또는 null | 추정 비용의 Provider 원통화 |
| `latencyMs` | integer 또는 null | 실제 호출 지연시간 |
| `failureCode` | string 또는 null | 안전하게 분류한 실패 원인 |
| `requestedAt` | string | Provider 호출 시작 시각 |
| `completedAt` | string 또는 null | Provider 호출 종료 시각 |

Prompt 원문, Provider 응답 원문, 고객 개인정보와 내부 예외 원문은 attempts에 포함하지 않는다.

### 7.4 상태별 운영 필드

- `PENDING`: Provider 호출 전이면 `attempts = []`, 토큰은 0, `costBreakdown = []`, `latencyMs`·`completedAt`은 null, `usageFinalized = false`.
- `GENERATING`: 현재까지 기록된 attempts를 보여주되 3.5절의 완전성 규칙으로 합계를 계산하며 `usageFinalized = false`.
- `COMPLETED`: 실행 종료를 `usageFinalized = true`로 표시한다. 종료됐어도 attempt 측정값이 불완전하면 해당 토큰·비용 합계는 null이다.
- `FALLBACK_COMPLETED`: 템플릿 자체에는 Provider 비용을 만들지 않는다. fallback 전에 실제 Provider 호출이 있었고 사용량이 확인되면 해당 attempt 사용량을 누락하지 않으며 `fallbackUsed = true`.
- `FAILED`: 사용 가능한 리포트는 없어도 실제 Provider 호출을 기록하고 `usageFinalized = true`로 표시한다. 측정하지 못한 토큰·비용을 0으로 바꾸지 않는다.
- 캐시 적중: `executionId = null`, `attempts = []`, 토큰 세 필드는 0, `estimatedCost = null`, `costCurrency = null`, `costBreakdown = []`, `lastProvider = null`, `lastModel = null`, `cacheHit = true`, `fallbackUsed = false`. 재사용한 원본이 fallback 결과이면 `reportSource = TEMPLATE_FALLBACK`으로 최초 생성 출처만 표시한다.
- 진행 실행 공유 요청: `executionShared = true`로 반환하고 연결된 실행의 동일 attempts를 보여준다. 아직 호출이 없으면 빈 배열이다. 요청별 Provider 사용량 원본을 복제하지 않으며 집계 API는 같은 attempts를 다시 합산하지 않는다.

자동 재시도는 Timeout과 연결 실패처럼 일시적인 오류에만 적용한다. 최초 호출을 포함해 최대 2회 시도하므로 자동 재시도는 최대 1회이다. 출력 검증 실패와 비일시적 Provider 오류는 자동 재시도하지 않고 템플릿 fallback으로 전환한다. 자동 재시도와 모델 라우팅의 실제 호출은 같은 `executionId` 아래 `attemptNumber` 순서로 기록한다.

### 7.5 성공 응답 예시

LLM 생성 완료:

```json
{
  "aiRequestId": "air_demo_20260726_0001",
  "executionId": "aiexec_demo_20260726_0001",
  "executionShared": false,
  "initiatingAiRequestId": "air_demo_20260726_0001",
  "reportId": "aireport_demo_20260726_0001",
  "caseId": "case_demo_20260724_0031",
  "detectionResultVersion": 3,
  "reportStatus": "COMPLETED",
  "reportSource": "LLM",
  "sourceAiRequestId": null,
  "lastProvider": "provider-demo",
  "lastModel": "report-model-demo",
  "promptVersion": "ai-report-prompt-3",
  "modelVersion": "report-model-lite-2",
  "inputTokens": 920,
  "outputTokens": 310,
  "totalTokens": 1230,
  "estimatedCost": "0.014200",
  "costCurrency": "USD",
  "costBreakdown": [
    {
      "costCurrency": "USD",
      "estimatedCost": "0.014200"
    }
  ],
  "latencyMs": 8100,
  "cacheHit": false,
  "fallbackUsed": false,
  "usageFinalized": true,
  "requestedByRef": "analyst_ref_demo_07",
  "requestedAt": "2026-07-26T02:10:00Z",
  "completedAt": "2026-07-26T02:10:08.100Z",
  "failureCode": null,
  "attempts": [
    {
      "attemptNumber": 1,
      "provider": "provider-demo",
      "model": "report-model-demo",
      "outcome": "SUCCEEDED",
      "inputTokens": 920,
      "outputTokens": 310,
      "totalTokens": 1230,
      "estimatedCost": "0.014200",
      "costCurrency": "USD",
      "latencyMs": 7900,
      "failureCode": null,
      "requestedAt": "2026-07-26T02:10:00.100Z",
      "completedAt": "2026-07-26T02:10:08Z"
    }
  ],
  "traceId": "trace_demo_ai_request_01",
  "queryTraceId": "trace_demo_ai_request_query_01"
}
```

`TEMPLATE_FALLBACK` 생성 완료:

```json
{
  "aiRequestId": "air_demo_20260726_0004",
  "executionId": "aiexec_demo_20260726_0004",
  "executionShared": false,
  "initiatingAiRequestId": "air_demo_20260726_0004",
  "reportId": "aireport_demo_20260726_0004",
  "caseId": "case_demo_20260724_0044",
  "detectionResultVersion": 2,
  "reportStatus": "FALLBACK_COMPLETED",
  "reportSource": "TEMPLATE_FALLBACK",
  "sourceAiRequestId": null,
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
      "requestedAt": "2026-07-26T03:00:00.100Z",
      "completedAt": "2026-07-26T03:00:04.100Z"
    }
  ],
  "traceId": "trace_demo_ai_request_04",
  "queryTraceId": "trace_demo_ai_request_query_04"
}
```

템플릿 fallback 자체에는 Provider attempt나 비용을 생성하지 않는다. 위 사용량은 fallback 전에 실제로 발생하고 Provider가 확인한 실패 호출의 값이다.

최종 실행 실패:

```json
{
  "aiRequestId": "air_demo_20260726_0005",
  "executionId": "aiexec_demo_20260726_0005",
  "executionShared": false,
  "initiatingAiRequestId": "air_demo_20260726_0005",
  "reportId": null,
  "caseId": "case_demo_20260724_0055",
  "detectionResultVersion": 1,
  "reportStatus": "FAILED",
  "reportSource": null,
  "sourceAiRequestId": null,
  "lastProvider": "provider-demo",
  "lastModel": "report-model-demo",
  "promptVersion": "ai-report-prompt-3",
  "modelVersion": "report-model-lite-2",
  "inputTokens": null,
  "outputTokens": null,
  "totalTokens": null,
  "estimatedCost": null,
  "costCurrency": null,
  "costBreakdown": null,
  "latencyMs": 8200,
  "cacheHit": false,
  "fallbackUsed": false,
  "usageFinalized": true,
  "requestedByRef": "analyst_ref_demo_07",
  "requestedAt": "2026-07-26T04:00:00Z",
  "completedAt": "2026-07-26T04:00:08.200Z",
  "failureCode": "TEMPLATE_FALLBACK_FAILED",
  "attempts": [
    {
      "attemptNumber": 1,
      "provider": "provider-demo",
      "model": "report-model-demo",
      "outcome": "FAILED",
      "inputTokens": null,
      "outputTokens": null,
      "totalTokens": null,
      "estimatedCost": null,
      "costCurrency": null,
      "latencyMs": 4000,
      "failureCode": "LLM_TIMEOUT",
      "requestedAt": "2026-07-26T04:00:00.100Z",
      "completedAt": "2026-07-26T04:00:04.100Z"
    },
    {
      "attemptNumber": 2,
      "provider": "provider-demo",
      "model": "report-model-demo",
      "outcome": "FAILED",
      "inputTokens": null,
      "outputTokens": null,
      "totalTokens": null,
      "estimatedCost": null,
      "costCurrency": null,
      "latencyMs": 4000,
      "failureCode": "LLM_TIMEOUT",
      "requestedAt": "2026-07-26T04:00:04.150Z",
      "completedAt": "2026-07-26T04:00:08.150Z"
    }
  ],
  "traceId": "trace_demo_ai_request_05",
  "queryTraceId": "trace_demo_ai_request_query_05"
}
```

실패 attempt의 토큰과 비용을 Provider가 확인하지 못한 경우 null로 유지한다. 확인된 실제 사용량이 있으면 실패 호출도 누락하지 않는다.

캐시 적중 요청의 핵심 운영 필드는 다음과 같다. 원본의 `reportSource`는 유지하지만 이번 요청의 Provider 실행과 사용량은 생성하지 않는다.

```json
{
  "aiRequestId": "air_demo_20260726_0003",
  "executionId": null,
  "executionShared": false,
  "initiatingAiRequestId": null,
  "reportId": "aireport_demo_20260726_0001",
  "reportStatus": "COMPLETED",
  "reportSource": "LLM",
  "sourceAiRequestId": "air_demo_20260726_0001",
  "lastProvider": null,
  "lastModel": null,
  "inputTokens": 0,
  "outputTokens": 0,
  "totalTokens": 0,
  "estimatedCost": null,
  "costCurrency": null,
  "costBreakdown": [],
  "cacheHit": true,
  "fallbackUsed": false,
  "usageFinalized": true,
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
| `provider` | string | 선택 | 연결 실행의 `ProviderCallAttempt` 중 하나라도 해당 Provider와 일치하는 요청. 캐시 요청은 매칭되지 않음 |
| `model` | string | 선택 | 연결 실행의 `ProviderCallAttempt` 중 하나라도 해당 모델과 일치하는 요청. 캐시 요청은 매칭되지 않음 |
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
- `executionId`
- `executionShared`
- `initiatingAiRequestId`
- `reportId`
- `caseId`
- `detectionResultVersion`
- `reportStatus`
- `reportSource`
- `sourceAiRequestId`
- `lastProvider`
- `lastModel`
- `promptVersion`
- `modelVersion`
- `inputTokens`
- `outputTokens`
- `totalTokens`
- `estimatedCost`
- `costCurrency`
- `costBreakdown`
- `latencyMs`
- `cacheHit`
- `fallbackUsed`
- `requestedAt`
- `completedAt`
- `failureCode`
- `traceId`

여러 Provider 시도가 있으면 `lastProvider`와 `lastModel`은 연결 실행의 마지막 실제 호출을 표시하고 토큰·비용은 그 실행의 distinct attempts 합계 projection이다. `inputTokens`, `outputTokens`, `totalTokens`의 타입은 각각 integer 또는 null이며, `estimatedCost`, `costCurrency`, `costBreakdown`은 3.5절의 단건 실행 완전성 규칙을 그대로 적용한다. 일부 attempt가 미측정이면 확인된 값만 완전한 실행 합계처럼 반환하지 않는다.

전체 시도는 단건 운영 상세 API에서 조회한다. 같은 실행을 공유하는 요청 행에는 같은 projection이 보일 수 있으므로 목록 행의 토큰·비용을 직접 합산하지 않는다. 집계는 9절의 distinct 실행·attempt 기준을 사용한다. `provider`와 `model` 필터는 마지막 호출만이 아니라 연결 실행의 attempts 중 하나라도 일치하면 해당 요청을 포함한다.

`fallbackUsed`는 연결 실행이 이번 요청에 제공한 결과로 템플릿 fallback을 실제 채택했는지를 나타낸다. 캐시 요청은 원본 `reportSource`가 `TEMPLATE_FALLBACK`이어도 새 fallback을 수행하지 않았으므로 `fallbackUsed = false`이다.

### 8.5 성공 응답 예시

```json
{
  "content": [
    {
      "aiRequestId": "air_demo_20260726_0001",
      "executionId": "aiexec_demo_20260726_0001",
      "executionShared": false,
      "initiatingAiRequestId": "air_demo_20260726_0001",
      "reportId": "aireport_demo_20260726_0001",
      "caseId": "case_demo_20260724_0031",
      "detectionResultVersion": 3,
      "reportStatus": "COMPLETED",
      "reportSource": "LLM",
      "sourceAiRequestId": null,
      "lastProvider": "provider-demo",
      "lastModel": "report-model-demo",
      "promptVersion": "ai-report-prompt-3",
      "modelVersion": "report-model-lite-2",
      "inputTokens": 920,
      "outputTokens": 310,
      "totalTokens": 1230,
      "estimatedCost": "0.014200",
      "costCurrency": "USD",
      "costBreakdown": [
        {
          "costCurrency": "USD",
          "estimatedCost": "0.014200"
        }
      ],
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

상세 목록과 같은 필터를 사용해 외부 요청 수, distinct 실행 수, distinct Provider 호출 수, 성공·실패, fallback, 캐시, 토큰, 비용과 평균 요청 지연시간을 집계한다.

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
| `requestCount` | 기간·필터에 포함된 `AiReportRequest` 수. 캐시·실행 공유 요청 포함 |
| `executionCount` | 필터에 포함된 요청들이 참조하는 distinct `AiReportExecution` 수. 캐시 요청은 실행이 없어 기여하지 않음 |
| `providerCallCount` | 포함된 distinct 실행에 속한 distinct `ProviderCallAttempt` 수 |
| `successCount` | 사용 가능한 리포트로 종료된 요청 수. `COMPLETED`와 `FALLBACK_COMPLETED` 포함 |
| `failureCount` | `FAILED` 요청 수 |
| `inProgressCount` | `PENDING`과 `GENERATING` 요청 수 |
| `fallbackCount` | 연결 실행에서 `TEMPLATE_FALLBACK` 결과를 받은 비캐시 요청 수. 캐시 재사용은 원본 출처가 fallback이어도 포함하지 않음 |
| `cacheHitCount` | `cacheHit = true`인 `AiReportRequest` 수 |
| `inputTokens` | integer 또는 null. 모든 포함 attempt에서 입력 토큰이 확인될 때만 합계 |
| `outputTokens` | integer 또는 null. 모든 포함 attempt에서 출력 토큰이 확인될 때만 합계 |
| `totalTokens` | integer 또는 null. 모든 포함 attempt에서 전체 토큰이 확인될 때만 합계 |
| `estimatedCost` | string 또는 null. 모든 포함 attempt의 비용·통화가 확인된 단일 통화 집계의 전체 추정 비용 |
| `costCurrency` | string 또는 null. 완전한 단일 통화 집계일 때의 통화이며 무호출·다중 통화·불완전 비용이면 null |
| `costBreakdown` | array 또는 null. 완전한 통화별 전체 합계, 무호출이면 빈 배열, 일부 미측정이면 null |
| `averageLatencyMs` | 종료된 `AiReportRequest`의 접수부터 종료까지 평균 지연시간. Provider 호출 지연 집계가 아님 |

`successCount + failureCount + inProgressCount = requestCount`가 되어야 한다. `fallbackCount`와 `cacheHitCount`는 성공 요청의 처리 방식 부분집합이며 성공·실패 합계에 다시 더하지 않는다.

집계는 요청별 상세 응답에 투영된 attempts를 합산하지 않고 `executionId`와 attempt 식별자로 중복 제거한 영속 `ProviderCallAttempt`를 직접 집계한다. 같은 실행을 공유하는 요청 수가 늘어도 `executionCount`, `providerCallCount`, 토큰과 비용이 증가하지 않는다.

기간 토큰 집계는 다음 완전성 규칙을 적용한다.

- 필터에 포함된 distinct `ProviderCallAttempt`가 없으면 `inputTokens`, `outputTokens`, `totalTokens`는 0이다.
- attempt가 하나 이상이면 각 토큰 필드별로 모든 포함 attempt에서 값이 확인될 때만 합계를 반환한다.
- 하나라도 해당 토큰 필드가 null이면 집계 응답의 해당 합계도 null이다.
- 확인하지 못한 토큰을 0으로 바꾸거나, 확인된 값만 합산해 완전한 총사용량처럼 반환하지 않는다.

기간 비용 집계는 다음 완전성 규칙을 적용한다.

- 모든 포함 attempt의 비용과 통화가 확인되고 통화가 하나이면 `estimatedCost`, `costCurrency`와 단일 항목 `costBreakdown`을 반환한다.
- 모든 비용과 통화가 확인되고 통화가 여러 개이면 `estimatedCost = null`, `costCurrency = null`이고 `costBreakdown`에 통화별 전체 합계를 반환한다.
- attempt가 하나 이상인데 일부 비용 또는 통화가 null이면 `estimatedCost = null`, `costCurrency = null`, `costBreakdown = null`로 반환한다. 확인된 일부 비용만 전체 비용처럼 합산하지 않는다.
- 포함 attempt가 없으면 `estimatedCost = null`, `costCurrency = null`, `costBreakdown = []`이다.

캐시 적중과 Provider 호출 전 대기 상태는 attempt가 없으므로 미측정 attempt로 계산하지 않는다. 따라서 “실제 사용량 없음”은 0과 빈 배열로, “실제 호출은 있으나 사용량 측정 불완전”은 null로 구분한다. 캐시 요청을 위한 가상 호출·토큰·비용 행은 만들지 않는다.

`TEMPLATE_FALLBACK` 자체도 Provider 비용을 생성하지 않는다. fallback 전에 실제 Provider 호출이 있었다면 그 distinct attempt를 집계 대상으로 포함하되, 확인된 사용량만 완전성 규칙에 따라 반환한다. `fallbackCount`는 실제 fallback 결과를 받은 비캐시 요청만 집계하며, 원본 출처가 `TEMPLATE_FALLBACK`인 캐시 요청은 포함하지 않는다.

### 9.5 성공 응답 예시

```json
{
  "from": "2026-07-26T00:00:00Z",
  "to": "2026-07-27T00:00:00Z",
  "requestCount": 125,
  "executionCount": 91,
  "providerCallCount": 96,
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

- 같은 정확 일치 조건의 완료 리포트가 있으면 새 요청을 캐시 적중으로 종결하고 기존 리포트를 재사용한다.
- 완료 결과가 없고 같은 조건의 `PENDING` 또는 `GENERATING` 실행이 있으면 새 요청을 기존 `executionId`에 연결하고 새 Provider 실행을 만들지 않는다.
- 동시에 도착한 요청 중 하나만 생성 실행을 획득한다.
- 같은 키와 같은 요청은 `FAILED`를 포함해 같은 `aiRequestId`와 기존 상태 또는 결과를 반환한다.
- 서로 다른 `Idempotency-Key`로 도착했어도 정확 일치 조건의 실행·리포트·사용량을 중복 생성하지 않는다.
- 이전 실행이 `FAILED`이고 재사용 가능한 결과와 활성 실행이 없으면 새 키의 요청은 새 실행을 만들 수 있다.

### 10.2 재시도와 재생성 구분

- 자동 재시도는 같은 `executionId` 아래에서 일시 오류 후 Provider를 다시 호출하는 처리이다.
- 자동 재시도는 Timeout과 연결 실패에만 적용하며 최초 호출을 포함해 최대 2회, 즉 자동 재시도 최대 1회이다.
- 출력 검증 실패와 비일시적 Provider 오류는 자동 재시도하지 않고 템플릿 fallback으로 전환한다.
- 각 실제 호출은 같은 `AiReportExecution`의 `ProviderCallAttempt`로 `attemptNumber` 순서에 따라 기록한다.
- 같은 키로 `FAILED` 요청을 재전송하면 기존 실패 요청을 반환하며 새 attempt를 추가하지 않는다.
- 새 키의 실패 이후 재요청으로 새 실행이 생성되면 해당 새 실행에도 같은 자동 재시도 정책을 적용한다.
- 재생성은 `detectionResultVersion`, `promptVersion` 또는 `modelVersion`이 변경되어 새로운 정확 일치 조건이 된 요청이다.
- 초기 버전에서는 동일 정확 일치 조건의 강제 재생성을 허용하지 않는다.

### 10.3 현재 유효한 리포트

확정 정책은 다음과 같다.

1. 사용 가능한 `COMPLETED` 또는 `FALLBACK_COMPLETED` 결과만 현재 리포트 후보로 삼는다.
2. 새 요청이 `PENDING`, `GENERATING` 또는 `FAILED`이면 기존 현재 리포트를 유지한다.
3. 서로 다른 성공 결과 중 해당 실행을 최초 생성한 `AiReportRequest.requestedAt`이 가장 최근인 결과를 우선한다.
4. 실행 최초 요청의 `requestedAt`이 같으면 실행 최초 요청의 `aiRequestId`를 안정적인 보조 순서로 사용한다.
5. `generatedAt`은 리포트가 실제 사용 가능해진 시각으로 유지하지만 현재 결과의 첫 번째 우선순위 기준으로 사용하지 않는다.
6. 캐시 요청은 기존 `AiReport`를 재사용하므로 동일 리포트의 현재 우선순위를 변경하지 않는다.
7. 오래된 initiating request의 실행이 나중에 완료되어도 더 최근 initiating request가 만든 성공 결과를 덮어쓰지 않는다.
8. 모든 과거 요청, 리포트와 실제 Provider 사용량 이력은 보존 정책에 따라 추적 가능해야 한다.

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
- 캐시 요청은 `executionId = null`, `executionShared = false`, `initiatingAiRequestId = null`, `cacheHit = true`로 표현한다.
- 캐시 요청은 기존 `AiReport`를 `resolvedReportRef`로 참조하고, 응답의 `sourceAiRequestId`는 결과→실행→최초 요청 계보에서 파생한다.
- 별도 AI 요청 이력에 요청자, 요청 시각, `traceId`, `resolvedReportRef`와 재사용 관계를 보존한다.
- 캐시 요청은 Provider 호출 이력, 가상 토큰과 가상 비용을 생성하지 않는다.
- AuditLog만으로 AI 요청 이력을 대체하지 않는다.
- Redis를 사용하더라도 PostgreSQL의 영속 결과가 업무 정합성 기준이다.
- Redis 장애 시 정확 일치 조건을 완화하지 않으며 원본 흐름 또는 실패 정책을 적용한다.

진행 중 실행 공유와 완료 결과 캐시 재사용은 서로 다른 처리이다. 공유 요청은 `executionId`가 있고 `executionShared = true`, `cacheHit = false`이며 연결 실행의 결과를 기다린다. 캐시 요청은 `executionId = null`, `executionShared = false`, `cacheHit = true`이고 요청 시점에 기존 완료 결과를 받는다.

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

### 13.3 사건 계약에서 재사용하는 코드와 권한 후보

새 AI 전용 코드를 추가하지 않고 기존 공통·사건 계약 코드를 우선 사용한다.

| 코드 | HTTP 상태 | 의미 |
| --- | --- | --- |
| `CASE_STATUS_CONFLICT` | `409` | 현재 사건 상태가 AI 리포트 생성 대상이 아님 |
| `STATE_TRANSITION_NOT_ALLOWED` | `409` | 완료된 동일 정확 일치 결과의 강제 재생성을 허용하지 않음 |
| `FORBIDDEN` 후보 | `403` | 역할 또는 리소스 접근 권한 부족. 인증·인가 계약 확정 전 후보 |

FastAPI Timeout, FastAPI 연결 실패, LLM 호출 실패와 출력 형식 검증 실패는 접수 이후의 AI 처리 결과이면 HTTP 오류 코드가 아니라 `failureCode`로 기록한다.

### 13.4 오류 상황 매핑

| 상황 | 계약 |
| --- | --- |
| `Idempotency-Key` 누락 또는 형식 오류 | `400 VALIDATION_ERROR` |
| 요청 형식·필드 검증 실패 | `400/422 VALIDATION_ERROR` |
| 사건 없음 | `404 RESOURCE_NOT_FOUND` |
| 탐지 결과·버전 없음 또는 사건과 불일치 | `404 RESOURCE_NOT_FOUND` 또는 관계 검증용 `422 VALIDATION_ERROR` |
| HIGH·CRITICAL이 아닌 사건 | `422 VALIDATION_ERROR` |
| 생성 대상이 아닌 사건 상태 | `409 CASE_STATUS_CONFLICT` |
| 같은 키로 이미 처리 중인 동일 요청 | `202`와 기존 `aiRequestId` 반환 |
| 같은 키의 종료된 동일 요청 | `FAILED` 포함 `200`과 기존 요청 반환. 새 실행 없음 |
| 다른 키지만 처리 중인 정확 일치 요청 | `202`와 새 `aiRequestId`, 기존 `executionId`, `executionShared = true` 반환. 새 실행 없음 |
| Idempotency-Key 충돌 | `409 IDEMPOTENCY_KEY_CONFLICT` |
| 완료된 동일 결과 강제 재생성 | `409 STATE_TRANSITION_NOT_ALLOWED` |
| FAILED 실행 뒤 새 키 재요청 | 결과·활성 실행이 없으면 새 요청·실행 생성. 오류 아님 |
| FastAPI Timeout·연결 실패 | fallback 성공 시 정상 종료, 모두 실패 시 `FAILED` |
| LLM 호출 실패 | fallback 성공 시 정상 종료, 모두 실패 시 `FAILED` |
| LLM 출력 형식 검증 실패 | 원문 미노출, fallback 성공 시 정상 종료, 모두 실패 시 `FAILED` |
| 비용 집계 기간·페이지 오류 | `400/422 VALIDATION_ERROR`; 기간은 `INVALID_TIME_RANGE` 필드 코드 |
| 권한 부족 | `403 FORBIDDEN` 후보 |
| 내부 오류 | `500 INTERNAL_ERROR` |

## 14. 보안·개인정보·감사

- FDS 분석 담당자는 생성 API와 `GET /api/v1/cases/{caseId}/ai-reports/current`에서 리포트 상태, 안전한 `failureCode`, 본문과 `reportSource`, 요청의 `cacheHit`·실행 공유·원본 요청 관계를 조회한다. Provider, 모델, `ProviderCallAttempt`, attempts, 토큰과 비용 상세는 조회하지 못한다.
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

요청과 실행 분리, 기존 부모 요청 식별자 필드 제거, 정확 일치 네 요소, 진행 중 실행 공유, 캐시 요청의 무실행 정책, `FAILED` 실행 이후 새 키 재요청과 실제 attempt 기준 비용 집계는 확정 정책이므로 다시 결정 대상으로 올리지 않는다.

| 결정 항목 | 선택 가능한 안 | 권장안과 이유 | API·구현 영향 | 현재 작업 차단 여부 |
| --- | --- | --- | --- | --- |
| AI 리포트 생성 가능 `caseStatus` | A. 모든 상태 허용 / B. 활성 조사 상태만 허용 / C. `IN_REVIEW`만 허용 | **B: `OPEN`, `IN_REVIEW`, `ADDITIONAL_INFORMATION_REQUIRED`만 허용.** 조사 지원 목적과 `CLOSED` 사건의 읽기 전용 원칙을 함께 지키기 쉬움 | POST Validation, `CASE_STATUS_CONFLICT` 조건과 테스트 케이스에 영향 | API 문서 정렬은 차단하지 않음. 생성 API 구현 전 결정 필요 |
| `failureCode`와 attempt `outcome` 최종 목록 | A. Provider별 자유 문자열 / B. 제한된 공통 Enum / C. 공통 Enum과 내부 원본 코드 별도 보관 | **C.** 외부 계약을 안정화하면서 운영 진단용 내부 정보를 분리할 수 있음 | DTO Enum, OpenAPI, 저장 필드와 Provider 매핑에 영향. 외부에는 원문을 노출하지 않음 | 현재 작업은 차단하지 않음. Provider 연동 구현 전 결정 필요 |
| 변경된 정확 일치 조건의 재생성 횟수 제한 | A. 제한 없음 / B. 사건별 UTC 일일 고정 횟수 / C. 역할·위험도별 정책 한도 | **C.** 서비스 범위의 비용 통제 의도를 유지하면서 CRITICAL 사건 조사 필요를 단일 고정값이 막지 않도록 할 수 있음 | `429` 사용 여부, 오류 코드, 한도 조회·감사와 운영 설정에 영향 | 현재 작업은 차단하지 않음. 제한 도입 전 별도 API 승인 필요 |
| AI 요청·실행·attempt·리포트 보존 기간 | A. 동일 기간 / B. 엔티티별 차등 기간 / C. 상세 단기 보존 후 비식별 집계만 장기 보존 | **C.** 감사·비용 검증과 개인정보 최소 보존의 균형이 좋음 | 목록 조회 가능 기간, 삭제·비식별화와 감사 참조에 영향 | 후속 결정 |
| `caseAnalysisSnapshotVersion` 도입 시점 | A. 초기부터 네 요소에 추가 / B. 대표 DetectionResult 계약으로 시작 후 복수 거래 사건 입력이 확정될 때 도입 | **B.** 현재 확정된 네 요소를 유지하면서 실제 복수 거래 입력 모델을 먼저 검증할 수 있음 | 향후 요청 필드, 정확 일치 키와 캐시 무효화 버전 변경에 영향 | 후속 ADR·ERD 결정 |
| Provider 가격표 버전 관리 | A. 가격표 버전 미보존 / B. Provider 가격표 참조·적용 시각 보존 / C. 가격 스냅샷 전체 보존 | **B.** 전체 가격표를 복제하지 않고도 `estimatedCost` 재현성을 확보하기 쉬움 | attempt 저장 속성, 비용 재계산·감사와 운영 화면에 영향 | 초기 계약은 차단하지 않음. 비용 검증 구현 전 결정 필요 |
| 인증·인가와 Mock Actor 전달 | A. 임의 요청 헤더 / B. 서버 인증 Principal과 local/test 전용 Mock Actor / C. 요청 본문 actor 필드 | **B.** 요청 본문 위조를 피하고 운영·테스트 경계를 분리할 수 있음 | 역할별 엔드포인트 접근, `requestedByRef`, AuditLog와 테스트 설정에 영향 | 실제 API 구현 전 결정 필요 |
| PostgreSQL 활성 실행 선점과 격리 | A. 애플리케이션 조회만 / B. 활성 정확 일치 부분 Unique와 충돌 후 재조회 / C. 명시적 잠금 중심 | **B.** 동시 요청의 중복 실행을 DB 제약으로 보조하면서 잠금 범위를 제한할 수 있음 | 마이그레이션, 트랜잭션 경계와 동시성 테스트에 영향 | 현재 API 계약은 차단하지 않음. DB 구현 전 결정 필요 |
| `FraudCase.currentAiReportRef` 도입 | A. 초기부터 물리 참조 / B. 조회 시 계산 후 성능 측정 시 도입 | **B.** 중복 현재값의 정합성 비용을 실제 성능 근거 없이 먼저 만들지 않음 | 사건 조회 쿼리와 향후 스키마 최적화에 영향 | 후속 성능 결정 |

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
- 같은 상태 값이라도 `AiReportRequest.reportStatus`, `AiReportExecution.executionStatus`, `AiReport.reportStatus`의 소유자와 의미를 구분한다.
- `riskLevel`은 `LOW/MEDIUM/HIGH/CRITICAL`을 사용하고 생성 대상은 HIGH·CRITICAL이다.
- `caseStatus`와 `finalDisposition`은 AI 리포트 상태와 분리한다.
- `detectionResultVersion`은 integer 버전으로 기존 탐지 API와 일치시킨다.
- `aiRequestId`는 외부 요청, `executionId`는 실제 실행, `reportId`는 사용 가능한 결과를 식별한다.
- 요청자는 사건 API와 같이 신뢰할 수 있는 서버 사용자 문맥에서 결정한다.
- 비용은 공통 금액 원칙에 따라 소수점 문자열로 표현한다.
- 목록은 공통 페이지 응답을 사용한다.
- 오류는 공통 `code/message/traceId/fieldErrors` 구조를 사용한다.
- 실제 사용량 원본은 `ProviderCallAttempt`이며 캐시·공유 요청별로 복제하지 않는다.

대조 중 확인한 문서 간 표현 차이와 충돌은 다음과 같다. 이번 작업은 이 파일만 수정하므로 다른 문서의 표현은 변경하지 않는다.

| 문서 | 확인한 차이·충돌 | 이 계약의 적용 |
| --- | --- | --- |
| `domain-erd.md` 13.5 | 본문 단계는 활성 실행 확인 후 완료 결과 확인 순서이나 이번 승인 정책은 완료 결과를 먼저 확인하도록 요구함 | 5.6에서 승인된 순서인 완료 결과 → 활성 실행 → 새 실행을 사용. 두 상태가 동시에 존재하지 않아야 한다는 ERD 불변식은 유지 |
| `domain-erd.md` 7.14·10.3 | 현재 유효 리포트를 `AiReport.generatedAt DESC`로 먼저 선택한 뒤 initiating request의 `requestedAt`, `aiRequestId`를 보조 순서로 사용함 | 이번 승인 정책에 따라 initiating request의 `requestedAt DESC`, `aiRequestId DESC`를 우선하고 `generatedAt`은 실제 사용 가능 시각으로만 사용. 오래된 요청의 늦은 완료가 더 최근 요청의 성공 결과를 덮지 않도록 하며 ERD 문구 정합화는 후속 문서 작업으로 남김 |
| `ai-report-state-transition.md` | 캐시 적중 표현과 완료 리포트 재생성 일부가 `TBD`로 남아 있음 | 후속 확정 ERD와 이번 승인 정책을 따라 캐시는 무실행 결과 재사용, 동일 완료 결과 강제 재생성 금지로 계약 |
| `fds-service-scope.md` 11.3 | 출력 JSON 검증 실패 후 상위 모델로 한 번 재시도하는 예시가 있음 | 현재 API 계약의 승인된 자동 재시도 정책인 Timeout·연결 실패만 최대 1회 재시도를 유지. 출력 검증 실패는 fallback으로 전환 |
| `fds-service-scope.md` 11.6 | 사건별 일일 재생성 제한 예시가 있으나 기간·횟수·오류 계약이 확정되지 않음 | 이번 계약에는 임의 수치를 넣지 않고 사용자 결정 사항으로 유지 |
| `system-architecture.md` 15절 | `aiRequestId`가 모델 라우팅·호출·토큰·비용을 연결한다고 표현되어 요청과 실행이 혼재함 | 확정 ERD에 따라 실행·attempt는 `executionId`, 외부 요청은 `aiRequestId`로 분리 |
| `api-conventions.md` | 페이지 기본·최대 크기는 사용자 결정 사항이지만 기존 AI API 문서는 20/100을 사용함 | 이 파일의 기존 20/100 계약은 유지. 공통 규칙과의 전역 정합화는 별도 승인 작업 |
| `project-summary.md`와 서비스 범위 문서 | 프로젝트 개요의 사용자 명칭은 일반 운영자·리스크 담당자이고 상세 범위는 FDS 분석 담당자·플랫폼 운영자로 구체화됨 | 이 API는 상세 서비스 범위와 ADR-001의 두 사용자 역할을 사용 |
| `domain-erd.md`와 기존 이 파일 | ERD는 요청·실행·attempt·결과 관계를 확정했으나 기존 API에는 관계 미확정 문구와 부모 요청 식별자 필드가 남아 있었음 | 미확정 문구와 부모 요청 식별자 필드를 제거하고 확정 관계를 반영 |

다음은 충돌이 아니라 후속 설계 범위이다.

- 초기에는 대표 DetectionResult 버전을 사용하며 복수 거래 사건 전체 입력을 표현하지 못한다. `caseAnalysisSnapshotVersion`은 후속 ADR·ERD 후보이다.
- Provider 가격표 버전 관리, 비용 데이터 보존 기간과 확정 정산은 미확정이다. 초기 계약은 Provider 원통화만 기록하고 환율 환산하지 않는다.
- `docs/04-database/` 경로는 현재 저장소에 없고 논리 ERD는 `docs/02-architecture/domain-erd.md`에 존재한다.

## 18. 구현 전 검증 체크리스트

- [ ] 요청·실행·attempt·결과의 확정 관계와 구현 전 필수 결정인 인증·인가 방식이 반영되었는가
- [ ] API 공통 시간·금액·페이지네이션·오류 구조와 일치하는가
- [ ] `caseId`, `detectionResultVersion`, `aiRequestId`, `executionId`, `reportId`, `traceId` 의미가 구현 전체에서 일치하는가
- [ ] 사건의 대표 또는 채택 DetectionResult 관계를 검증하는가
- [ ] Spring Boot와 FastAPI 책임 경계를 지키는가
- [ ] 외부 생성 API가 `202 Accepted`와 상태 조회 흐름을 유지하는가
- [ ] FDS 분석 담당자용 생성 API와 현재 리포트 API에서 Provider·모델·`ProviderCallAttempt`·`attempts`·토큰·비용 상세를 반환하지 않는가
- [ ] `attempts`는 플랫폼·클라우드 운영자 전용 단건 운영 상세 API에서만 제공하는가
- [ ] HIGH·CRITICAL 대상 정책을 검증하는가
- [ ] 캐시 키 네 요소를 모두 사용하고 다른 사건 결과를 재사용하지 않는가
- [ ] `reportSource`가 `LLM`, `TEMPLATE_FALLBACK`만 사용하고 캐시는 `cacheHit`과 `sourceAiRequestId`로 구분되는가
- [ ] 새 실행·진행 실행 공유·캐시 재사용이 `executionId`, `executionShared`, `initiatingAiRequestId`로 구분되는가
- [ ] `sourceAiRequestId`가 캐시 결과 계보에서 파생되고 캐시 요청에만 값이 있는가
- [ ] 같은 키 재전송이 `FAILED`를 포함해 기존 요청을 반환하는가
- [ ] `FAILED` 실행 뒤 새 키 요청은 결과·활성 실행이 없을 때 새 실행을 만드는가
- [ ] Timeout·연결 실패만 최대 1회 자동 재시도하고 각 시도를 같은 `executionId`의 `attempts`에 기록하는가
- [ ] 진행 중 동일 요청이 Provider 실행을 중복 생성하지 않는가
- [ ] 늦은 LLM 응답이 fallback·실패 결과를 이력 없이 덮어쓰지 않는가
- [ ] 현재 유효 리포트를 initiating request의 `requestedAt DESC`, `aiRequestId DESC`로 선택하고 `generatedAt`만으로 우선순위를 정하지 않는가
- [ ] 오래된 initiating request의 늦은 완료와 캐시 요청이 더 최근 성공 결과의 현재 우선순위를 바꾸지 않는가
- [ ] 실패한 Provider 호출의 토큰과 추정 비용도 누락하지 않는가
- [ ] 실제 attempt가 하나 이상일 때 모든 attempt에서 확인되지 않은 토큰 합계는 0이 아니라 null로 반환하는가
- [ ] 일부 attempt의 비용 또는 통화가 미측정이면 `estimatedCost`, `costCurrency`, `costBreakdown`을 null로 반환하고 부분 합계를 전체 비용처럼 노출하지 않는가
- [ ] 캐시 요청에 Provider 호출 이력, 가상 토큰과 가상 비용을 만들지 않는가
- [ ] 공유 요청의 동일 attempts를 집계에서 요청별로 다시 합산하지 않는가
- [ ] `executionCount`와 `providerCallCount`가 distinct 실행·attempt 기준인가
- [ ] 서로 다른 통화를 환산 없이 합산하지 않는가
- [ ] `fallbackUsed`는 실제 연결 실행의 fallback 채택만 나타내고, 캐시된 fallback 결과는 `fallbackUsed = false`, `reportSource = TEMPLATE_FALLBACK`으로 구분하는가
- [ ] 비용을 추정값으로 표시하고 실제 청구액으로 단정하지 않는가
- [ ] Prompt·Provider 응답 원문과 개인정보를 반환하지 않는가
- [ ] 요청자, 캐시, fallback, 재생성과 실패가 감사 가능한가
- [ ] 문서·코드·테스트에서 구현되지 않은 기능을 완료로 표현하지 않는가
