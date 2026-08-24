# Rule v1 내부 분석 API

## 1. 문서 목적과 구현 상태

이 문서는 Spring Boot가 FastAPI AI Service에 Rule v1 분석을 동기 요청하고,
현재 구현된 `RuleAnalysisResult`를 응답받기 위한 내부 HTTP API 계약을
정의한다. 현재 FastAPI wire는 `POST /api/v1/rule-analysis`와 External Risk를
필수로 결합하는 `POST /api/v2/rule-analysis`다. v2의 단일 상세 기준은
[External Risk·Rule 분석 입력 계약](../01-requirements/external-risk-rule-analysis-input-contract.md)이다.

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
구현되어 있다. Pydantic 요청·응답 DTO와 명시적 매퍼에 이어 이 문서에서
정의하는 FastAPI Endpoint, Service, Trace·본문 크기 Middleware와 공통 예외
Handler도 구현되어 있다. Spring Boot `RuleAnalysisHttpClient`, Timeout·Trace
전달, 성공·오류 응답 검증과 transport·응답 오류 분류도 구현되어 있다.
거래·행동 이벤트·RuleVersion Snapshot, DetectionResult 시작 commit,
FastAPI 정확히 1회 호출, 응답의 Evidence 변환, 결과 완료·채택과 실패 기록을
연결하는 Spring Boot 오케스트레이션도 구현되어 있다. 거래 접수와 최종 멱등
응답 연결은 아직 구현되지 않았으므로 전체 서비스 연동 완료로 해석하지 않는다.
최종 거래 성공, Snapshot v2와 완료 간극 복구는
[`ADR-006`](../07-decisions/ADR-006-final-transaction-success-and-idempotency-recovery.md)을
따르며 이 내부 Rule 분석 API의 책임이 아니다.

이 API는 외부 사용자 API가 아니라 Private Network 안에서 사용하는
Spring Boot → FastAPI 내부 서비스 API다. 인증·인가는 아직 구현되지 않았으며
이번 계약과 구현 범위에서 제외한다.

## 2. Endpoint

현재 구현 Endpoint는 다음과 같다.

```http
POST /api/v1/rule-analysis
Content-Type: application/json
X-Trace-Id: <traceId>
```

```http
POST /api/v2/rule-analysis
Content-Type: application/json
X-Trace-Id: <traceId>
```

- 분석은 요청 안에서 완료되는 동기 처리다.
- 성공하면 `200 OK`를 반환한다.
- FastAPI는 분석 결과를 영속화하지 않는다.
- `GET /api/health`의 기존 경로와 응답 계약은 변경하지 않는다.
- 요청과 응답 본문은 UTF-8 JSON을 사용한다.
- 요청 본문 최대 크기는 1 MiB, 즉 1,048,576 bytes다. 구현된 FastAPI
  Middleware가 실제 수신 byte를 기준으로 이 제한을 적용한다.

구현된 `POST /api/v2/rule-analysis`도 Rule v1 엔진을 실행하지만 필수
`externalRisk` 때문에 v1과 호환되지 않는 새 wire 계약이다. v1은 당장 제거하지
않고, v1에 optional `externalRisk`나 기본 `UNMATCHED`를 추가하지 않는다.

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
- 한 실행에서 거래·행동 이벤트 Snapshot 고정
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

다음은 현재 v1의 exact 구조다.

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
허용 `reasonCode`는 Evidence 변환이 다시 검증한다. 구현된 HTTP DTO→도메인
매퍼는 위 canonical metadata와 요청 업무 계약을 plan 생성 전에 사전
검증한다. 사전 검증을 통과한 뒤 scoring 또는 Evidence에서 canonical
불일치가 다시 발생하면 입력 오류로 재분류하지 않고 `500 INTERNAL_ERROR`인
서버 내부 불변식 위반으로 처리한다.

### 5.5 v2 External Risk 입력

v2는 기존 네 최상위 필드에 필수·non-null `externalRisk`를 추가한다.
`externalRisk`는 `providerCode`, `lookupStatus`, `policyResult`, `providerAsOf`,
`lookedUpAt`, `matches`만 가지며 모든 중첩 DTO는 알 수 없는 필드를 거부한다.
MATCHED는 canonical match 1~3개, UNMATCHED는 정확히 0개다. exact 필드·Enum·시간,
중복 거부와 explicit rank 정렬은
[External Risk·Rule 분석 입력 계약](../01-requirements/external-risk-rule-analysis-input-contract.md)을
따른다.

v2는 External Risk를 검증하지만 R001~R004 evaluator에는 전달하지 않는다.
따라서 조건·점수·등급·Evidence, `ruleSetVersion`, `scoring-policy-v1`,
`featureVersion=rule-v1`, `modelVersion=null`과 기존 성공 응답은 바뀌지 않는다.
External Risk echo와 전용 hash를 응답에 추가하지 않는다. Python v2 DTO·검증과
FastAPI Endpoint는 구현됐으며 Backend Java v2 DTO·Mapper·Client는 아직 구현되지
않았다.

v2의 JSON 타입·필수·null·Enum·UTC 형식·unknown field 오류는
`400 INVALID_REQUEST`, match 조합·개수·canonical 순서와 시간 관계 오류는
`422 RULE_CONTRACT_ERROR`다. Java가 생성한 요청의 이 실패는 거래 API 호출자
오류가 아니므로 Spring Boot가 `500 INTERNAL_ERROR`로 축약한다. FastAPI는 배열을
자동 정렬하거나 빈 Snapshot을 `UNMATCHED`로 보정하지 않는다.

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
- `externalRisk`와 External Risk 전용 hash

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
혼합하지 않는다. 구현된 Exception Handler가 Pydantic wire 오류와 Rule 업무
계약 오류를 위 기준으로 구분한다.

다음 실행 계획 semantic category는 오류 origin이 `REQUEST_CONTRACT`인 경우에만
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

서버 소유 Rule Code Bridge, dependency 또는 configuration의 중복·누락·손상은
같은 category 이름을 사용하더라도 `500 INTERNAL_ERROR`로 처리한다. 배포된
evaluator 또는 Registry capability 누락은 `500 UNSUPPORTED_RULE_CAPABILITY`로
처리한다. origin과 category가 모순되는 조합은 fail-closed 방식으로
`500 INTERNAL_ERROR`로 처리한다.

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
| `behaviorEvents` | 최대 1,000개 | DTO→도메인 매퍼에서 적용 |
| `ruleVersions` | 1~32개 | DTO→도메인 매퍼에서 적용 |
| HTTP 요청 본문 | 최대 1 MiB = 1,048,576 bytes | FastAPI Middleware에서 실제 수신 byte 기준 적용 |

FastAPI Middleware는 `Content-Length`만 신뢰하지 않고 실제 수신 byte 상한을
적용하며, 제한 초과 요청을 Rule 분석과 DTO 역직렬화 전에
`413 PAYLOAD_TOO_LARGE`로 거부해야 한다. Gateway가 도입되면 동일하거나 더 작은
제한으로 조기 차단할 수 있지만 이는 추가 방어이며 FastAPI Middleware 제한을
대체하지 않는다. Endpoint, Middleware와 Exception Handler는 현재 구현되어
있다.

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

## 13. Spring Boot Client 연동 계약

이 절은 Spring Boot가 이 문서의 wire 계약을 호출하는 Client의 단일 상세
기준이다. 현재 v1 요청·응답 DTO, HTTP 상태와 FastAPI 오류 envelope는 변경하지
않는다. v1 FastAPI HTTP 경계, Spring Boot Client와 결과 채택·영속화
오케스트레이션이 구현되어 있다. v2 Client는 후속 구현이다.

### 13.1 계층과 책임

Spring Boot Controller는 FastAPI를 직접 호출하지 않는다. 애플리케이션
오케스트레이션은 평가 Snapshot 준비와 Client 호출 시점을 조정하고, 별도 HTTP
adapter인 Client는 다음 책임만 가진다.

- 이 문서의 요청 DTO 직렬화
- 현재 요청의 `traceId`를 `X-Trace-Id`로 전달
- FastAPI 동기 HTTP 호출
- 성공·오류 응답 역직렬화와 오류 해석
- Trace와 성공 응답의 계약·무결성 검증
- transport 실패를 13.6의 Spring Boot 내부 category로 분류

Client는 Rule 적중 여부, scoring 또는 Evidence를 Java에서 다시 계산하거나
응답값을 보정하지 않는다. DetectionResult·DetectionEvidence 생성·채택·영속화,
거래 상태 변경, 위험 대응과 사건 생성도 Client 책임이 아니다.

### 13.2 HTTP Client 선택과 호출 경로

- Spring Framework의 동기 `RestClient`를 사용한다.
- 현재 `spring-boot-starter-web` 의존성 안에서 구현하고 WebClient, Reactor,
  Apache HttpClient, Resilience4j 등 신규 의존성을 추가하지 않는다.
- 현재 Client Endpoint path는 base URL과 분리된 고정값
  `/api/v1/rule-analysis`를 사용한다. 목표 전환은 `/api/v2/rule-analysis`를
  사용하며 v1과 v2를 optional 필드로 자동 협상하지 않는다.
- 하나의 Client 호출은 하나의 HTTP 요청만 수행한다.

### 13.3 설정 계약

| Spring property | 필수·기본값 | 의미 |
| --- | --- | --- |
| `finguardops.ai-service.base-url` | 필수, 기본값 없음 | 환경별 AI Service base URL. 코드에 하드코딩하지 않는다. |
| `finguardops.ai-service.connect-timeout` | 기본값 `1s` | AI Service와 연결을 수립할 수 있는 최대 시간 |
| `finguardops.ai-service.response-timeout` | 기본값 `3s` | 연결 후 FastAPI 응답을 기다리고 응답 본문을 읽는 transport 제한 |

Spring Boot relaxed binding에 대응하는 환경 변수 이름은 다음과 같다.

```text
FINGUARDOPS_AI_SERVICE_BASE_URL
FINGUARDOPS_AI_SERVICE_CONNECT_TIMEOUT
FINGUARDOPS_AI_SERVICE_RESPONSE_TIMEOUT
```

`base-url`은 환경별로 반드시 주입하며 로컬 주소를 포함한 기본값을 두지 않는다.
테스트에서도 같은 property를 override하고 별도 테스트 전용 설정 키를 만들지
않는다. `response-timeout`은 FastAPI 응답 대기·읽기 구간의 제한이며 거래 전체
처리 시간, 거래 실패 확정 기한이나 수동 복구 기한을 뜻하지 않는다. 이 Issue는
설정 계약만 정의하며 `application.yml`과 `.env.example`에는 값을 추가하지
않는다.

### 13.4 Retry와 fallback

- 초기 Client의 자동 retry는 `0회`이다.
- circuit breaker를 적용하지 않는다.
- timeout, 오류 또는 무결성 실패를 `LOW`, 0점이나 빈 Evidence로 변환하지
  않는다.
- 일부 Rule 또는 일부 응답만 정상 결과로 채택하지 않는다.
- AI 리포트의 템플릿·LLM fallback을 Rule 분석 흐름에 적용하지 않는다.

Client 실패 시 대상 DetectionResult와 거래는 `FAILED`로 기록하고 결과를
채택하지 않는다. 수동 재개와 재분석은 Client의 자동 retry 0회와 별도인 후속
계약에서 결정한다.

### 13.5 Trace와 성공 응답 검증

Client는 현재 Spring Boot 요청에서 확정한 유효한 `traceId` 하나를 요청
`X-Trace-Id`로 전달한다. 정상 응답을 채택하기 전에 다음 세 값이 원문 기준으로
모두 같은지 검증한다.

```text
요청 X-Trace-Id
= 응답 X-Trace-Id
= 응답 body.traceId
```

응답 `X-Trace-Id`가 누락되거나 복수 값이거나 body 값과 불일치하면 정상 결과로
채택하지 않는다. 오류 응답도 요청 Trace, 응답 헤더와 body `traceId`의 일치를
검증한 뒤 오류 code를 해석한다. 잘못된 upstream Trace 원문은 외부 응답이나
로그에 노출하지 않는다.

`200 OK`는 곧바로 신뢰하지 않고 strict DTO 역직렬화 후 최소 다음 항목을
검증한다.

- 응답 `transactionId`가 요청 거래의 `transactionId`와 일치한다.
- 응답 `analysis.evaluationCutoffAt`이 요청 `evaluationCutoffAt`과 정확히
  일치한다.
- `ruleSetVersion`이 64자 lowercase SHA-256 형식이다.
- `scoringPolicyVersion`이 Spring Boot가 지원하는 정책 버전과 일치한다.
- `riskScore`, `riskLevel`, Rule contribution과 group summary가 이 문서와
  승인된 scoring 계약의 타입·범위·허용값·상호 관계를 만족한다.
- contribution의 `ruleId`와 `executionOrder`는 지원하는 실행 계획 안에서
  중복 없이 일관된 순서를 이룬다.
- Evidence의 `ruleId`, `ruleVersionId`, `ruleCode`, `ruleVersion`,
  `reasonCode`, `executionOrder`와 `scoreContribution`이 요청 RuleVersion
  Snapshot 및 대응 contribution과 일치한다.
- 적중 contribution마다 정확히 하나의 Evidence가 있고, 미적중 contribution에
  Evidence가 없으며, Evidence만 존재하는 Rule이 없다.
- Rule별 `observationSummary`가 9.2절의 exact allowlist와 타입 계약을
  만족하고 지원하지 않는 Rule이 없다.
- 최상위와 모든 중첩 DTO에 알 수 없는 필드, 필수 필드 누락과 null 불일치가
  없다.

지원하지 않는 Rule이나 알 수 없는 필드, malformed·잘린 JSON, 불완전 응답,
DTO 역직렬화 실패와 상호 모순되는 응답은 13.6의
`AI_SERVICE_INVALID_RESPONSE`로 fail-closed 처리한다. Client는 이를 보정하거나
Rule 조건, evaluator 선택, scoring과 Evidence를 Java에서 재계산하지 않는다.

Client의 현재 validator는 `ruleSetVersion` 형식과 응답 내부 정합성을 검증한다.
Spring Boot 오케스트레이션은 PENDING 결과를 만들기 전에 canonical Rule
Snapshot으로 예상 해시를 고정하고, Client validator가 응답 해시를 요청
Snapshot의 예상 값과 exact 비교한다. 이 연결 검증은 구현되어 있다.

### 13.6 Spring Boot 내부 오류 category

다음 category는 Spring Boot 내부 Client 분류이며 외부 거래 API의 공개 오류
code가 아니다. HTTP 오류 매핑은 HTTP 상태, 공통 오류 envelope, `code`와 Trace
계약이 모두 일치할 때만 적용한다.

| 내부 category | FastAPI 응답 또는 실패 조건 |
| --- | --- |
| `AI_SERVICE_REQUEST_CONTRACT_ERROR` | `400` + `INVALID_REQUEST` |
| `AI_SERVICE_PAYLOAD_TOO_LARGE` | `413` + `PAYLOAD_TOO_LARGE` |
| `AI_SERVICE_RULE_CONTRACT_ERROR` | `422` + `RULE_CONTRACT_ERROR` |
| `AI_SERVICE_CAPABILITY_MISMATCH` | `500` + `UNSUPPORTED_RULE_CAPABILITY` |
| `AI_SERVICE_INTERNAL_ERROR` | `500` + `INTERNAL_ERROR` |
| `AI_SERVICE_CONNECT_TIMEOUT` | 연결 수립 timeout |
| `AI_SERVICE_RESPONSE_TIMEOUT` | 응답 대기 또는 응답 본문 읽기 timeout |
| `AI_SERVICE_UNAVAILABLE` | DNS 실패, 연결 거부와 timeout이 아닌 그 밖의 transport I/O 실패 |
| `AI_SERVICE_INVALID_RESPONSE` | 신뢰할 수 없는 HTTP 또는 응답 계약 |

`AI_SERVICE_INVALID_RESPONSE`에는 다음이 포함된다.

- 지원하지 않는 HTTP 상태
- `application/json`과 호환되지 않는 응답 `Content-Type`
- malformed 또는 잘린 JSON
- DTO 역직렬화 실패
- HTTP 상태와 오류 `code`의 모순
- 필수 필드가 누락되거나 알 수 없는 필드가 있는 오류 envelope
- 오류 응답의 Trace 불일치
- 13.5의 성공 응답 무결성 위반

FastAPI 원본 오류 메시지, 응답 원문과 내부 예외를 외부 거래 API에 그대로
전달하지 않는다. 외부 거래 API에는 다음처럼 기존 공통 오류만 사용한다.

| 내부 category | 외부 HTTP·공통 code |
| --- | --- |
| `AI_SERVICE_CONNECT_TIMEOUT`, `AI_SERVICE_RESPONSE_TIMEOUT` | `503 DEPENDENCY_TIMEOUT` |
| `AI_SERVICE_UNAVAILABLE` | `503 DEPENDENCY_UNAVAILABLE` |
| 나머지 Client category | `500 INTERNAL_ERROR` |

Spring Boot가 만든 요청·Rule·배포 capability나 upstream 응답 계약의 결함은 외부
거래 API 호출자의 `400`·`413`·`422`로 전가하지 않는다. 거래 상태·결과 채택과
함께 적용하는 상세 경계는
[Spring Boot Rule v1 분석 오케스트레이션·결과 채택 계약](../01-requirements/spring-rule-analysis-orchestration-contract.md)을
따른다.

### 13.7 DB와 외부 HTTP 트랜잭션 경계

다음 순서를 지킨다.

1. 거래 접수와 멱등 단일 승자를 확정하고 `RECEIVED` 거래 저장을 commit한다.
2. 상위 거래 처리 흐름이 거래 `occurredAt`을 단일 `evaluationCutoffAt`으로
   사용한다.
3. 상위 거래 처리 흐름이 DB 트랜잭션과 행 잠금 없이 External Risk를 조회한다.
   현재 승인된 실패 정책은 no retry·no cache·no stale data·no fallback·no
   Circuit Breaker다. timeout·unavailable·invalid response는 typed failure로
   전파하고 Rule 분석을 시작하지 않는다.
4. Provider 호출 뒤 Spring Boot 소유 Snapshot assembly 경계가 짧은 DB read
   transaction에서 거래·행동 이벤트·실행 가능한 활성 RuleVersion과 External
   Risk를 완전한 immutable 목표 v2 요청으로 조합한다. DetectionResult와 거래
   상태는 바꾸지 않는다.
5. 완성된 요청을 `RuleAnalysisOrchestrationService`에 전달한다. 짧은 분석 시작
   쓰기 트랜잭션에서 거래를 잠그고 요청의 소유 관계·cutoff·시간을 재검증하며 예상
   `ruleSetVersion`과 다음 DetectionResult 버전을 고정한다. 이 Service는 External
   Risk를 직접 조회하거나 정책을 결정하지 않는다.
6. 같은 트랜잭션에서 DetectionResult `PENDING → IN_PROGRESS`와 거래
   `RECEIVED → ANALYZING`을 commit한다.
7. DB 트랜잭션과 잠금을 유지하지 않은 상태에서 목표 FastAPI v2를 정확히 한 번 호출한다.
8. 응답 wire 계약, 무결성과 선확정 Snapshot 대응을 검증·변환한다.
9. 후속 별도 쓰기 트랜잭션에서 Evidence, DetectionResult `COMPLETED`, 결과
   채택과 거래 `ANALYZING → ANALYZED`를 원자적으로 수행한다.
10. 9단계 commit으로 Rule 분석 HTTP 오케스트레이터의 책임이 끝난다.
    `ANALYZED`는 최종 성공이 아닌 중간 상태이며 External Risk를 새로 조회하거나
    다시 반영하지 않는다.
11. 이 계약 밖의 상위 거래 처리 흐름이 위험 대응, 최종 거래 상태 전이와
    HIGH·CRITICAL의 사건 생성 또는 기존 사건 연결을 수행하고 commit한다.
12. 모든 최종 업무 commit 이후에만 ADR-006의 Snapshot v2를 확정한다.

네트워크 응답을 기다리는 동안 DB 쓰기 트랜잭션과 잠금을 장시간 유지하지
않는다. 현재 내부 구현은 External Risk가 없는 거래·행동·RuleVersion Snapshot을
분석 시작 경계에서 조합한 뒤 시작·HTTP·완료·채택 책임을 수행한다. 목표 v2의
별도 Snapshot assembly와 External Risk 포함 완성 요청 전달, 위험 대응과 Snapshot
v2 단계는 아직 구현되지 않았다. Rule 분석 HTTP 오케스트레이터는 External
Risk 조회·정책, 위험 대응, 사건 또는 Snapshot v2를 소유하지 않는다.

현재 FastAPI v1 `RuleAnalysisRequest`에는 External Risk 입력이 없다. Issue #150은
Spring Boot 내부의 독립 Port·Policy Service·local/dev/test Mock·인메모리 성공
Snapshot만 구현했으며 FastAPI·Python·`RuleAnalysisRequest`를 변경하지 않았다.
Issue #160에서 승인한 v2 입력 계약에 따라 Issue #162에서 Python DTO·검증과
FastAPI Endpoint를 구현했다. Backend Java v2 Client와 호출 연결은 후속 Issue다.

### 13.8 로그와 정보 보호

Client 로그는 다음 최소 항목만 기록할 수 있다.

- `traceId`
- 대상 서비스 식별자
- Endpoint 식별자
- HTTP 상태 분류
- 13.6의 내부 오류 category
- 호출 소요 시간

다음 값은 로그에 기록하지 않는다.

- 요청·응답 JSON 원문
- 고객·계좌·기기·수취인 참조값
- `conditionDefinition` 원문
- Evidence 원문
- FastAPI 원본 오류 메시지
- 인증정보

stack trace와 내부 예외 상세는 외부 응답에 노출하지 않는다. 내부 진단 로그가
필요해도 위 민감정보와 HTTP 원문을 포함하지 않는다.

### 13.9 Java Client 테스트 상태와 후속 오케스트레이션 검증

현재 Java Client와 오케스트레이션 단위·통합 테스트는 정상 요청,
all-unmatched 성공, 엄격한 Trace·wire·업무 응답 검증, Client category 분류,
connect·response timeout, 자동 retry 0회, 트랜잭션 밖 HTTP 호출과 결과
완료·실패 경계를 검증한다. 유지해야 할 회귀 항목은 다음과 같다.

- 정상 `200 OK` 요청·응답과 요청 DTO 직렬화
- 전 Rule 정상 미적중인 0점·`LOW`·빈 Evidence 응답
- 요청·응답 헤더·body Trace 일치와 누락·복수·불일치 거부
- `transactionId`, cutoff, RuleSet, scoring, contribution, group summary와
  Evidence 무결성
- FastAPI `400`, `413`, `422`, `500`의 상태·code별 내부 category 변환
- capability 불일치와 FastAPI 내부 오류의 구분
- connect timeout과 response timeout의 구분
- DNS 실패, 연결 거부와 그 밖의 transport 연결 실패
- 지원하지 않는 상태·Content-Type, malformed·잘린·불완전 응답과 DTO
  역직렬화 실패
- 알 수 없는 필드, 지원하지 않는 Rule과 상호 모순되는 성공·오류 응답
- Client 단계의 모든 실패에서 정상 분석 응답을 반환하지 않음
- 한 Client 호출에서 HTTP 요청이 한 번만 수행되어 자동 retry가 없음
- 로그와 외부 오류 응답에 요청·응답 원문, 참조값, Evidence, upstream 오류
  메시지와 인증정보가 노출되지 않음

## 14. 현재 구현 이후 제외 범위

- 거래 접수 Service에서 Rule v1 오케스트레이터를 호출하는 연결
- Backend Java v2 DTO·Mapper·Client와 External Risk→FastAPI v2 호출 연결
- 거래 접수에서 구현된 위험 대응·최종 거래 상태·사건·AuditLog 원자적 최종화
  경계를 호출하는 연결
- 최종 동기 응답과 Snapshot v2 확정
- Snapshot 완료 간극 운영 복구
- RuleVersion publish·운영 준비와 별도 동기화·캐시·배포 산출물
- 인증·인가, CORS와 네트워크 보안 구현
- 선택적 Gateway 요청 본문 조기 차단 구현
- ML과 생성형 AI 연동
- DB 스키마와 Migration 변경
