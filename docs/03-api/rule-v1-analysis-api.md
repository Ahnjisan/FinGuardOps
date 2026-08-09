# Rule v1 내부 분석 API

## 1. 문서 목적과 구현 상태

이 문서는 Spring Boot가 FastAPI AI Service에 Rule v1 분석을 동기 요청하고,
현재 구현된 `RuleAnalysisResult`를 응답받기 위한 내부 HTTP API 계약을
정의한다.

```text
Spring Boot
→ 거래·행동 이벤트·활성 RuleVersion Snapshot 고정
→ FastAPI Rule v1 분석 요청
→ FastAPI 실행 계획·Rule·scoring·Evidence 계산
→ Spring Boot 결과 검증·채택·영속화
```

현재 AI Service에는 RuleVersion Snapshot, ExecutionPlan Builder,
Orchestrator, Runner, R001~R004 evaluator, `RuleScoringCalculator`,
`RuleEvidenceTransformer`와 `RuleAnalysisResult`까지의 순수 내부 경로가
구현되어 있다. 이 문서에서 정의하는 FastAPI Endpoint, Pydantic 요청·응답
DTO, 공통 예외 Handler와 Spring Boot Client는 아직 구현되지 않았다. 문서
확정은 서비스 연동 구현 완료를 의미하지 않는다.

이 API는 외부 사용자 API가 아니라 Private Network 안에서 사용하는
Spring Boot → FastAPI 내부 서비스 API다. 인증·인가는 아직 구현되지 않았으며
이번 계약과 구현 범위에서 제외한다.

## 2. Endpoint

```http
POST /api/v1/rule-analysis
Content-Type: application/json
X-Trace-Id: <traceId>
```

- 분석은 요청 안에서 완료되는 동기 처리다.
- 성공하면 `200 OK`를 반환한다.
- FastAPI는 분석 결과를 영속화하지 않는다.
- `GET /api/health`의 기존 경로와 응답 계약은 변경하지 않는다.
- 요청과 응답 본문은 UTF-8 JSON을 사용한다.
- 요청 본문 최대 크기는 1 MiB, 즉 1,048,576 bytes다. 후속 FastAPI Middleware가
  이 제한을 반드시 적용하며 Endpoint와 Middleware는 아직 구현되지 않았다.

## 3. 추적 계약

Spring Boot는 요청마다 `X-Trace-Id`를 정확히 하나 전달해야 한다. 헤더 값이
유효하면 FastAPI는 다음 네 위치에서 같은 원문 값을 사용한다.

```text
요청 X-Trace-Id
= 응답 X-Trace-Id
= 성공 또는 오류 응답 body.traceId
= 서버 로그 traceId
```

`X-Trace-Id`의 허용 형식과 유효하지 않은 외부 값의 미노출 원칙은
[API 공통 규칙](./api-conventions.md#8-traceid-원칙)을 따른다. FastAPI는 유효한
값을 변경·trim·대소문자 변환하지 않는다.

이 Endpoint에서는 누락·오류가 있어도 요청을 계속하는 공통 처리 방식의 예외로
다음 fail-closed 정책이 우선한다.

- 헤더가 누락되거나 형식이 잘못됐거나 복수 헤더 값이면 Rule 분석을 실행하지
  않고 `400 INVALID_REQUEST`로 종료한다.
- 오류 자체를 추적하기 위해 canonical lowercase UUID v4 형식의 로컬
  `traceId`를 요청 범위에서 한 번만 생성한다.
- 생성한 동일 값을 응답 `X-Trace-Id`, 오류 응답 body `traceId`와 서버 로그에
  사용한다.
- 누락되거나 잘못된 헤더 원문은 응답과 로그에 노출하지 않는다.
- 잘못된 값을 로컬 값으로 조용히 교체한 뒤 정상 분석을 계속하지 않는다.

`traceId`는 HTTP envelope와 추적 헤더의 필드다. 현재 Python
`RuleAnalysisResult`의 내부 도메인 필드로 추가하지 않는다.

## 4. 책임 경계

### 4.1 Spring Boot

- 거래 접수와 요청 멱등성 관리
- 현재 거래의 `occurredAt`을 `evaluationCutoffAt`으로 확정
- 하나의 일관된 읽기 경계에서 거래·행동 이벤트 Snapshot 고정
- 평가에 사용할 전체 활성 RuleVersion Snapshot 고정
- FastAPI 동기 호출과 Timeout 처리
- 응답의 transaction·cutoff·RuleVersion·점수·Evidence 완전성 검증
- DetectionResult·DetectionEvidence 생성, 채택과 영속화
- 거래 상태, 위험 대응과 사건 상태 결정
- `detectionResultId`, `analysisStatus`, 분석 시각, `evidenceId`,
  `displayDescription`과 영속 `sortOrder` 생성

### 4.2 FastAPI

- HTTP DTO를 내부 불변 Python 타입으로 변환
- RuleVersion Snapshot 상태·기간·중복·의존성과 설정 재검증
- canonical `RuleExecutionPlan`과 `ruleSetVersion` 생성
- R001~R004 실행
- Rule contribution, 그룹 상한, 점수와 위험 등급 계산
- Reason Code와 Evidence 변환
- 현재 `RuleAnalysisResult`를 HTTP 응답 DTO로 변환

FastAPI는 Spring Boot 업무 DB를 직접 조회하거나 저장하지 않는다. 거래 상태,
위험 대응, 사건 상태와 최종 판정을 확정하지 않는다. 생성형 AI와 LLM은 이
실행 경로에 참여하지 않는다.

## 5. 요청 DTO

### 5.1 최상위 구조

```text
RuleAnalysisRequest
├─ evaluationCutoffAt: UTC datetime
├─ transaction: RuleTransactionSnapshotRequest
├─ behaviorEvents: array<RuleBehaviorEventSnapshotRequest>
└─ ruleVersions: array<RuleVersionSnapshotRequest>
```

| JSON 필드 | 타입 | 필수 | 계약 |
| --- | --- | --- | --- |
| `evaluationCutoffAt` | string | 필수 | Spring Boot가 확정한 UTC 평가 cutoff |
| `transaction` | object | 필수 | 현재 거래의 불변 Snapshot |
| `behaviorEvents` | array | 필수 | 0~1,000개 행동 이벤트 Snapshot |
| `ruleVersions` | array | 필수 | 1~32개 전체 RuleVersion Snapshot |

`behaviorEvents` 빈 배열은 허용한다. `ruleVersions` 빈 배열은 정상적인 미적중이
아니라 실행 계획 구성 오류이므로 `422 RULE_CONTRACT_ERROR`로 거부한다.

### 5.2 거래 Snapshot

| JSON 필드 | 타입 | 필수 | 계약 |
| --- | --- | --- | --- |
| `transactionId` | string | 필수 | canonical lowercase RFC 4122 UUID v4 |
| `transactionType` | string | 필수 | `ACCOUNT_TRANSFER`, `OPEN_BANKING_TRANSFER` 중 하나 |
| `amount` | string | 필수 | 0보다 큰 canonical 10진 정수 문자열, 최대 15자리 |
| `currencyCode` | string | 필수 | 3자 uppercase ASCII 통화 코드 |
| `occurredAt` | string | 필수 | UTC `Z`, `evaluationCutoffAt`과 정확히 같음 |
| `externalCustomerRef` | string | 필수 | 1~128자, 앞뒤 공백 없음 |
| `senderAccountRef` | string | 필수 | 1~128자, 앞뒤 공백 없음 |
| `recipientAccountRef` | string | 필수 | 이체 거래이므로 null 금지, 1~128자 |
| `deviceRef` | string 또는 null | 필수 | 제공 시 1~128자, 앞뒤 공백 없음 |

Rule v1 분석 대상이 아닌 `ATM_WITHDRAWAL`, `LOAN_DISBURSED` 요청은 임의의
0점 결과로 처리하지 않고 `422 RULE_CONTRACT_ERROR`로 거부한다. R001~R003은
`KRW` 고액 조건을 평가하고 R004 자체에는 통화·고액 적중 조건이 없다.

### 5.3 행동 이벤트 Snapshot

각 항목은 Rule 평가에 필요한 아래 일곱 필드를 정확히 가진다. REST 행동
이벤트 접수의 nullable `transactionId`는 Rule 평가 입력에 포함하지 않는다.

| JSON 필드 | 타입 | 필수 | 계약 |
| --- | --- | --- | --- |
| `eventId` | string | 필수 | canonical lowercase RFC 4122 UUID v4 |
| `eventType` | string | 필수 | Rule v1이 사용하는 네 Event Type 중 하나 |
| `occurredAt` | string | 필수 | UTC `Z` 발생 시각 |
| `externalCustomerRef` | string | 필수 | 1~128자, 앞뒤 공백 없음 |
| `accountRef` | string 또는 null | 필수 | Event Type별 조건 적용 |
| `deviceRef` | string 또는 null | 필수 | Event Type별 조건 적용 |
| `beneficiaryRef` | string 또는 null | 필수 | Event Type별 조건 적용 |

Event Type별 fail-closed 조합은 다음과 같다. `선택` 필드는 null일 수 있고,
`금지` 필드는 반드시 명시적인 null이어야 한다.

| `eventType` | `accountRef` | `deviceRef` | `beneficiaryRef` |
| --- | --- | --- | --- |
| `DEVICE_REGISTERED` | 선택 | 필수 | 금지 |
| `PASSWORD_CHANGED` | 선택 | 선택 | 금지 |
| `TRANSFER_LIMIT_CHANGED` | 필수 | 선택 | 금지 |
| `BENEFICIARY_REGISTERED` | 필수 | 선택 | 필수 |

하나의 요청에 같은 `eventId`가 두 번 이상 있으면 내용이 같더라도
`422 RULE_CONTRACT_ERROR`로 거부한다. FastAPI는 중복 행동 이벤트를 자동으로
제거하거나 하나를 선택해 입력을 보정하지 않는다.

### 5.4 RuleVersion Snapshot

Spring Boot는 평가에 사용할 전체 RuleVersion 업무 Snapshot을 요청마다
전달한다. FastAPI는 별도 Rule 동기화 저장소나 Spring Boot DB 조회를 사용하지
않는다.

| JSON 필드 | 타입 | 필수 | 계약 |
| --- | --- | --- | --- |
| `fraudRuleId` | string | 필수 | FraudRule canonical lowercase UUID v4 업무 ID |
| `ruleCode` | string | 필수 | exact Rule v1 논리 코드 |
| `lifecycleStatus` | string | 필수 | 실행 요청에서는 `ACTIVE` |
| `ruleVersionId` | string | 필수 | RuleVersion canonical lowercase UUID v4 업무 ID |
| `versionNumber` | integer | 필수 | bool이 아닌 1 이상 정수 |
| `status` | string | 필수 | 실행 요청에서는 `PUBLISHED` |
| `reasonCode` | string | 필수 | Rule별 허용 Reason Code |
| `weight` | integer | 필수 | bool이 아닌 1~100 정수와 canonical weight 일치 |
| `conditionDefinition` | object | 필수 | Rule별 exact typed 설정 |
| `effectiveFrom` | string | 필수 | UTC `Z`, cutoff 포함 시작 |
| `effectiveTo` | string 또는 null | 필수 | UTC `Z`, cutoff 제외 종료 또는 무기한 null |

R001~R004의 canonical metadata는 다음과 같다.

| 내부 `RuleId` | exact `ruleCode` | 허용 `reasonCode` | canonical weight |
| --- | --- | --- | ---: |
| `R001` | `TRANSFER_ABSOLUTE_HIGH_AMOUNT` | `TRANSFER_ABSOLUTE_HIGH_AMOUNT` | 15 |
| `R002` | `RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT` | `RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT` | 20 |
| `R003` | `RECENT_SECURITY_CHANGE_HIGH_AMOUNT` | `RECENT_SECURITY_CHANGE_HIGH_AMOUNT` | 40 |
| `R004` | `RECENT_BENEFICIARY_TRANSFER` | `RECENT_BENEFICIARY_TRANSFER` | 10 |

`RuleId`는 FastAPI evaluator capability를 식별하는 내부 `StrEnum` 값이며 요청
필드가 아니다. FastAPI가 exact `ruleCode → RuleId` bridge로 결정한다.
`fraudRuleId`와 `ruleVersionId`는 Spring Boot가 전달하는 업무 UUID이며 Rule별
고정 UUID가 아니다.

FastAPI는 다음 조건을 전체 plan 생성 전에 재검증한다.

- `lifecycleStatus = ACTIVE`
- `status = PUBLISHED`
- `effectiveFrom <= evaluationCutoffAt`
- `effectiveTo IS NULL OR evaluationCutoffAt < effectiveTo`
- `fraudRuleId`, `ruleVersionId`, `ruleCode`와 mapping `RuleId` 중복 금지
- 위 표의 exact `ruleCode → RuleId`, 허용 `reasonCode`와 canonical weight
- R002·R003의 R001 dependency
- Rule별 conditionDefinition 구조와 현재 evaluator 지원값
- Registry evaluator capability

하나라도 실패하면 일부 Rule만 실행하지 않는다. `ruleSetVersion`은 요청에서
받지 않고 검증·정렬한 canonical execution plan으로 FastAPI가 계산한다.

이 사전 검증은 DTO 변환 후, Evaluator 또는 Runner 실행과 scoring·Evidence
변환 전에 수행한다. 알려진 R001~R004 Snapshot의 canonical metadata,
dependency 또는 conditionDefinition 업무 계약 위반은
`422 RULE_CONTRACT_ERROR`다. 승인된 Rule capability를 현재 FastAPI 배포본의
Registry가 제공하지 못하는 배포 불일치만 `500 UNSUPPORTED_RULE_CAPABILITY`다.

현재 `RuleExecutionPlanBuilder`는 `reasonCode`를 비어 있지 않은 문자열로,
`weight`를 1~100 범위로 검증하고 exact bridge·dependency·conditionDefinition과
Registry capability를 검증한다. canonical weight는 downstream scoring이,
허용 `reasonCode`는 Evidence 변환이 다시 검증한다. 따라서 후속 HTTP 계층은 위
사전 검증을 추가해야 하며 아직 구현되지 않았다. 사전 검증을 통과한 뒤
scoring 또는 Evidence에서 canonical 불일치가 다시 발생하면 입력 오류로
재분류하지 않고 `500 INTERNAL_ERROR`인 서버 내부 불변식 위반으로 처리한다.

## 6. conditionDefinition exact 계약

### 6.1 R001

```json
{
  "transactionTypes": ["ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"],
  "currencyCode": "KRW",
  "amountThreshold": "10000000"
}
```

- 배열 순서는 의미 비교 기준이 아니지만 값 중복은 금지한다.
- `amountThreshold`는 canonical Decimal 문자열이다.

### 6.2 R002

```json
{
  "prerequisiteRuleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
  "eventType": "DEVICE_REGISTERED",
  "windowSeconds": 86400,
  "matchPolicy": "SAME_CUSTOMER_AND_DEVICE",
  "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
}
```

### 6.3 R003

```json
{
  "prerequisiteRuleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
  "passwordEventType": "PASSWORD_CHANGED",
  "transferLimitEventType": "TRANSFER_LIMIT_CHANGED",
  "windowSeconds": 86400,
  "matchPolicy": "SAME_CUSTOMER_AND_SENDER_ACCOUNT",
  "sequencePolicy": "PASSWORD_CHANGED_AT_OR_BEFORE_TRANSFER_LIMIT_CHANGED",
  "selectionPolicy": "LATEST_TRANSFER_LIMIT_THEN_EVENT_ID_ASC_LATEST_PASSWORD_THEN_EVENT_ID_ASC"
}
```

### 6.4 R004

```json
{
  "eventType": "BENEFICIARY_REGISTERED",
  "windowSeconds": 86400,
  "matchPolicy": "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY",
  "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
}
```

모든 definition은 표시된 필드를 정확히 가져야 한다. 필수 필드 누락, 알 수
없는 필드, null, 중첩 구조 또는 값 차이는 `422 RULE_CONTRACT_ERROR`다.

## 7. JSON·Pydantic 직렬화 정책

- Python 모델 필드는 `snake_case`를 사용한다.
- JSON 필드는 `camelCase`를 사용한다.
- 요청 DTO는 JSON alias만 허용하고 Python `snake_case` 이름을 wire 입력으로
  허용하지 않는다.
- 응답 DTO는 항상 JSON alias로 직렬화한다.
- 모든 최상위·중첩 Pydantic DTO는 `extra="forbid"`, `frozen=True`를
  사용한다.
- 문자열·정수·boolean·배열 사이의 자동 scalar 강제 변환을 금지한다.
- Enum은 trim·대소문자 변환·alias 없이 exact case-sensitive 값만 허용한다.
- UUID는 [API 공통 규칙](./api-conventions.md#33-내부-식별자와-업무-식별자)과
  같이 hyphen을 포함한 canonical lowercase UUID v4와 RFC 4122 variant를
  검증한다.
- 시간은 ISO-8601 UTC `Z` 형식만 허용한다. 같은 순간의 `+00:00` 표기도
  거부한다.
- 소수 초는 생략하거나 1~6자리만 허용한다. FastAPI는 더 높은 정밀도를
  반올림·절삭하지 않는다.
- 응답 시각도 `Z` 접미사와 최대 6자리 소수 초로 직렬화한다.
- Decimal은 JSON number로 변환하지 않고 지수·소수부·leading zero가 없는
  canonical 10진 정수 문자열로 직렬화한다.
- tuple 기반 내부 collection은 JSON array로 직렬화한다.
- 중복 JSON key를 검출하기 위한 별도 Parser는 이번 범위에서 도입하지 않는다.
  Pydantic 검증 전에 JSON Parser가 처리한 중복 key를 이 계약이 검출한다고
  표현하지 않는다.

## 8. 평가와 시간창 정책

```text
evaluationCutoffAt == transaction.occurredAt
```

두 값은 UTC microsecond까지 정확히 같아야 한다. 서버 현재 시각, FastAPI 호출
시각과 분석 완료 시각으로 cutoff를 대체하지 않는다.

행동 시간창은 양 끝을 포함한다.

```text
evaluationCutoffAt - 86400초 <= event.occurredAt <= evaluationCutoffAt
```

시간창 밖 이벤트는 해당 Rule의 적격 이벤트에서 제외한다. R002와 R004는
가장 늦은 적격 이벤트를 선택하고 같은 시각이면 `eventId` 오름차순 첫 값을
선택한다. R003은 가장 늦은 적격 이체 한도 변경과 그 이전 또는 같은 시각의
가장 늦은 비밀번호 변경을 선택하며 같은 시각이면 각각 `eventId` 오름차순을
적용한다.

같은 Rule의 적격 이벤트가 여러 개여도 contribution과 Evidence는 한 번만
생성한다.

## 9. 성공 응답

### 9.1 Envelope

```text
RuleAnalysisResponse
├─ transactionId: UUID
├─ traceId: str
└─ analysis: RuleAnalysisResultResponse
```

성공 응답은 `200 OK`와 다음 envelope를 사용한다.

```http
HTTP/1.1 200 OK
Content-Type: application/json
X-Trace-Id: trace_demo_rule_0001
```

```json
{
  "transactionId": "10000000-0000-4000-8000-000000000001",
  "traceId": "trace_demo_rule_0001",
  "analysis": {
    "evaluationCutoffAt": "2026-07-23T12:00:00Z",
    "ruleSetVersion": "085edb92debd4e80d8472f77fab507d846810c668268ee34d8ee97ec2c917b26",
    "scoringResult": {
      "scoringPolicyVersion": "scoring-policy-v1",
      "riskScore": 65,
      "riskLevel": "HIGH",
      "ruleContributions": [
        {
          "ruleId": "R001",
          "executionOrder": 1,
          "matched": true,
          "originalContribution": 15
        },
        {
          "ruleId": "R003",
          "executionOrder": 2,
          "matched": true,
          "originalContribution": 40
        },
        {
          "ruleId": "R004",
          "executionOrder": 3,
          "matched": true,
          "originalContribution": 10
        }
      ],
      "groupSummaries": [
        {
          "groupId": "amount",
          "rawScore": 15,
          "cap": 15,
          "appliedScore": 15,
          "reduction": 0
        },
        {
          "groupId": "security",
          "rawScore": 50,
          "cap": 60,
          "appliedScore": 50,
          "reduction": 0
        }
      ]
    },
    "evidence": [
      {
        "ruleId": "R001",
        "ruleVersionId": "20000000-0000-4000-8000-000000000001",
        "ruleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
        "ruleVersion": "1",
        "reasonCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
        "executionOrder": 1,
        "scoreContribution": 15,
        "observationSummary": {
          "observedAmount": "12000000",
          "amountThreshold": "10000000"
        },
        "evidenceOccurredAt": "2026-07-23T12:00:00Z"
      },
      {
        "ruleId": "R003",
        "ruleVersionId": "20000000-0000-4000-8000-000000000003",
        "ruleCode": "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
        "ruleVersion": "1",
        "reasonCode": "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
        "executionOrder": 2,
        "scoreContribution": 40,
        "observationSummary": {
          "observedAmount": "12000000",
          "amountThreshold": "10000000",
          "passwordChangedEventId": "30000000-0000-4000-8000-000000000031",
          "passwordChangedAt": "2026-07-23T11:56:00Z",
          "transferLimitChangedEventId": "30000000-0000-4000-8000-000000000032",
          "transferLimitChangedAt": "2026-07-23T11:57:00Z",
          "elapsedSeconds": 180,
          "windowSeconds": 86400
        },
        "evidenceOccurredAt": "2026-07-23T11:57:00Z"
      },
      {
        "ruleId": "R004",
        "ruleVersionId": "20000000-0000-4000-8000-000000000004",
        "ruleCode": "RECENT_BENEFICIARY_TRANSFER",
        "ruleVersion": "1",
        "reasonCode": "RECENT_BENEFICIARY_TRANSFER",
        "executionOrder": 3,
        "scoreContribution": 10,
        "observationSummary": {
          "observedAmount": "12000000",
          "eventId": "30000000-0000-4000-8000-000000000004",
          "beneficiaryRegisteredAt": "2026-07-23T11:59:00Z",
          "elapsedSeconds": 60,
          "windowSeconds": 86400
        },
        "evidenceOccurredAt": "2026-07-23T11:59:00Z"
      }
    ]
  }
}
```

위 예시는 적중한 R001·R003·R004의 Evidence를 plan 순서로 모두 반환한다.

### 9.2 RuleAnalysisResult 필드

`analysis`는 현재 Python `RuleAnalysisResult`를 변경 없이 JSON으로 표현한다.

```text
RuleAnalysisResultResponse
├─ evaluationCutoffAt: UTC datetime
├─ ruleSetVersion: 64자 lowercase SHA-256
├─ scoringResult: RuleScoringResultResponse
└─ evidence: array<RuleEvidenceResponse>
```

`RuleScoringResultResponse`는 다음 필드를 가진다.

- `scoringPolicyVersion: string`
- `riskScore: integer`
- `riskLevel: LOW | MEDIUM | HIGH | CRITICAL`
- `ruleContributions: array`
  - `ruleId`
  - `executionOrder`
  - `matched`
  - `originalContribution`
- `groupSummaries: array`
  - `groupId: amount | security`
  - `rawScore`
  - `cap`
  - `appliedScore`
  - `reduction`

`RuleEvidenceResponse`는 다음 공통 필드를 가진다.

- `ruleId`
- `ruleVersionId`
- `ruleCode`
- `ruleVersion`: leading zero 없는 양의 canonical decimal 문자열
- `reasonCode`
- `executionOrder`
- `scoreContribution`
- `observationSummary`
- `evidenceOccurredAt`

Rule별 `observationSummary` exact allowlist는 다음과 같다.

| Rule | 정확한 필드 |
| --- | --- |
| R001 | `observedAmount`, `amountThreshold` |
| R002 | `observedAmount`, `amountThreshold`, `eventId`, `deviceRegisteredAt`, `elapsedSeconds`, `windowSeconds` |
| R003 | `observedAmount`, `amountThreshold`, `passwordChangedEventId`, `passwordChangedAt`, `transferLimitChangedEventId`, `transferLimitChangedAt`, `elapsedSeconds`, `windowSeconds` |
| R004 | `observedAmount`, `eventId`, `beneficiaryRegisteredAt`, `elapsedSeconds`, `windowSeconds` |

모든 Rule이 정상 실행되었지만 미적중이면 다음 결과는 유효하다.

```text
riskScore = 0
riskLevel = LOW
evidence = []
```

실행·검증 실패를 위 정상 미적중 결과로 변환하지 않는다.

### 9.3 FastAPI 응답에서 제외하는 필드

다음 값은 Spring Boot가 결과를 검증·채택·영속화할 때 소유하므로 FastAPI
응답에 추가하지 않는다.

- `detectionResultId`
- `analysisStatus`
- `analyzedAt`
- `evidenceId`
- `displayDescription`
- `sortOrder`
- `featureVersion`
- `modelVersion`

## 10. 오류 응답

### 10.1 공통 구조

모든 오류는 다음 구조를 사용한다.

```json
{
  "code": "RULE_CONTRACT_ERROR",
  "message": "Rule 분석 요청 계약을 확인해 주세요.",
  "traceId": "trace_demo_rule_0001",
  "fieldErrors": [
    {
      "field": "ruleVersions",
      "code": "MISSING_RULE_DEPENDENCY",
      "reason": "RuleVersion 실행 계약을 만족하지 않습니다."
    }
  ]
}
```

- 응답의 `X-Trace-Id`와 body `traceId`는 같다.
- `fieldErrors`가 없으면 빈 배열을 반환한다.
- `fieldErrors.field`는 camelCase JSON 경로를 사용한다.
- `reason`은 안전한 고정 설명이며 입력값과 내부 예외 메시지를 포함하지 않는다.
- 부분 분석 결과를 오류 응답에 포함하지 않는다.

### 10.2 상태와 오류 코드

| HTTP 상태 | `code` | 적용 범위 |
| --- | --- | --- |
| `400 Bad Request` | `INVALID_REQUEST` | malformed JSON, 필드 누락, 알 수 없는 필드, JSON 타입 또는 UUID·UTC·Decimal·Enum wire 형식 오류 |
| `413 Payload Too Large` | `PAYLOAD_TOO_LARGE` | 실제 수신한 HTTP 요청 본문이 1,048,576 bytes를 초과함 |
| `422 Unprocessable Entity` | `RULE_CONTRACT_ERROR` | cutoff 불일치, 중복 eventId, 거래·이벤트 조합, RuleVersion·dependency·conditionDefinition 계약 위반 |
| `500 Internal Server Error` | `UNSUPPORTED_RULE_CAPABILITY` | 승인 Rule을 현재 FastAPI 배포본이 실행하지 못하는 배포 불일치 |
| `500 Internal Server Error` | `INTERNAL_ERROR` | Runner 후조건, scoring·Evidence 불변식 또는 예상하지 못한 내부 오류 |

FastAPI/Pydantic의 기본 `RequestValidationError` 응답을 그대로 사용하지 않는다.
malformed JSON과 요청 DTO의 필드 누락, JSON alias 오류, 알 수 없는 필드,
JSON scalar 타입과 UUID·UTC·Decimal·Enum wire 형식 오류는 별도 Exception
Handler가 위 공통 오류 envelope의 `400 INVALID_REQUEST`로 변환해야 한다.
DTO 변환 이후의 Rule 업무 계약 위반은 별도 Rule 계약 예외로 발생시켜
`422 RULE_CONTRACT_ERROR`로 반환한다. FastAPI 기본 `422`와 업무 계약 `422`를
혼합하지 않는다. 이 Exception Handler는 아직 구현되지 않은 후속 구현
대상이다.

다음 실행 계획 semantic category는 `UNSUPPORTED_RULE_CAPABILITY`를 제외하고
`422 RULE_CONTRACT_ERROR`의 세부 `fieldErrors.code`로 사용할 수 있다.

- `NO_EXECUTABLE_RULE_VERSION`
- `MULTIPLE_EXECUTABLE_RULE_VERSIONS`
- `UNKNOWN_RULE_CODE`
- `DUPLICATE_RULE_VERSION_ID`
- `DUPLICATE_RULE_CODE`
- `DUPLICATE_RULE_ID`
- `MISSING_RULE_DEPENDENCY`
- `UNSUPPORTED_RULE_CONFIGURATION`
- `INVALID_RULE_EXECUTION_PLAN`

`UNSUPPORTED_RULE_CAPABILITY`는 요청을 일부 실행하거나 지원하지 않는 Rule을
제외하지 않고 배포 불일치 `500`으로 반환한다. Runner, scoring과 Evidence의
내부 category는 외부 `code`로 노출하지 않고 `INTERNAL_ERROR`로 축약한다.
다만 RuleVersion canonical metadata는 실행 전 검증하므로 그 입력 위반은
`422`이며, 사전 검증 이후 동일한 불일치가 downstream에서 다시 발견된 경우만
내부 불변식 위반 `500`이다.

### 10.3 정보 노출 금지

오류 응답, 로그와 메트릭 레이블에 다음 정보를 포함하지 않는다.

- 내부 예외 메시지와 stack trace
- 요청 JSON 원문
- 고객·계좌·기기·수취인 참조값
- RuleVersion conditionDefinition 원문
- 내부 DB PK, SQL과 저장소 상세

FastAPI는 실패를 `LOW`, 0점, 빈 Evidence 또는 일부 Rule 성공으로 변환하지
않는다. 자동 retry와 fallback도 이 Endpoint 책임이 아니다.

## 11. 요청 제한과 적용 경계

| 제한 | 계약값 | 현재 적용 상태 |
| --- | ---: | --- |
| `behaviorEvents` | 최대 1,000개 | Pydantic DTO 후속 구현 대상 |
| `ruleVersions` | 1~32개 | Pydantic DTO 후속 구현 대상 |
| HTTP 요청 본문 | 최대 1 MiB = 1,048,576 bytes | FastAPI Middleware 후속 구현 필수 |

FastAPI Middleware는 `Content-Length`만 신뢰하지 않고 실제 수신 byte 상한을
적용하며, 제한 초과 요청을 Rule 분석과 DTO 역직렬화 전에
`413 PAYLOAD_TOO_LARGE`로 거부해야 한다. Gateway가 도입되면 동일하거나 더 작은
제한으로 조기 차단할 수 있지만 이는 추가 방어이며 FastAPI Middleware 제한을
대체하지 않는다. Endpoint, Middleware와 Exception Handler는 아직 구현되지
않았다.

처리 순서는 `X-Trace-Id` 검증과 요청 범위 `traceId` 확정, 본문 크기 검사, DTO
역직렬화 순이다. 유효한 요청 Trace가 있는 413 응답은 그 값을 응답 헤더·본문과
서버 로그에 그대로 사용한다. Trace 헤더가 누락·오류·복수 값이면 3절의 우선
정책에 따라 로컬 UUID v4를 한 번 생성하고 `400 INVALID_REQUEST`로 종료하므로
본문 검사를 계속하지 않는다. `PAYLOAD_TOO_LARGE` 오류의 `fieldErrors`는 빈
배열이다.

## 12. 보안과 접근 통제

- 이 Endpoint는 Private Network 내부 서비스 통신만 전제로 한다.
- 외부 사용자와 브라우저가 직접 호출하는 공개 API가 아니다.
- 인증·인가, 서비스 자격 증명, mTLS, NetworkPolicy와 Gateway 정책은 아직
  구현되지 않았다.
- 인증·인가와 CORS 변경은 이번 범위에서 제외하며 별도 보안 설계와 사용자
  승인 후 구현한다.
- Private Network 전제만으로 호출자 신뢰와 인증이 구현되었다고 표현하지
  않는다.

## 13. 제외 범위

- FastAPI Router, Service, Pydantic DTO와 Exception Handler 구현
- Spring Boot HTTP Client와 Timeout·재호출 구현
- DetectionResult·DetectionEvidence 생성·채택·영속화
- 거래 상태, 위험 대응과 사건 상태 변경
- RuleVersion 별도 동기화·캐시·배포 산출물
- 인증·인가, CORS와 네트워크 보안 구현
- 요청 본문 1 MiB 차단 FastAPI Middleware와 선택적 Gateway 조기 차단 구현
- External Risk, ML과 생성형 AI 연동
- DB 스키마와 Migration 변경
