# Rule v1 탐지 계약 및 평가 정책

## 1. 문서 목적과 구현 상태

이 문서는 FinGuardOps Rule v1의 입력, 평가 시각, 시간창, R001~R004 조건, 점수, 위험 등급, 근거, 버전과 서비스 책임 경계를 정의하는 단일 기준 문서이다.

- 작업 목적: `[Docs] Rule v1 탐지 계약 및 평가 정책 정의`
- 문서 상태: Rule v1 계약 확정
- 구현 상태: 물리 Rule·탐지 영속 모델, R001~R004 evaluator부터
  `PlannedRuleResult`까지의 Rule 실행 경로와 Rule v1 Evidence 저장 전 방어
  검증 구현, scoring 이후 계층 미구현

현재 구현된 범위는 Spring Boot의 거래 접수·목록·상세 조회, 거래 멱등성,
행동 이벤트 접수·Rule 평가용 내부 조회, DetectionResult·DetectionEvidence,
FraudRule·RuleVersion PostgreSQL 영속 모델과 Rule v1 Evidence 저장 전
시간·코드 교차검증이다. AI Service에는 R001~R004 순수 evaluator,
`RuleEvaluatorRegistry`, `RuleExecutionOrchestrator`, `RuleExecutionPlan`,
`RuleExecutionPlanBuilder`, `RuleExecutionPlanRunner`와 `PlannedRuleResult`가
구현되어 있다. 현재 거래 접수 성공 응답은 단계적 구현 상태인 `RECEIVED`와
탐지 관련 null 값을 반환한다.

다음 항목은 아직 구현되지 않았다.

- Spring Boot의 평가 입력과 활성 Rule 집합 Snapshot 구성 및 실제 전달
- scoring 계층과 위험 점수·위험 등급 산출
- Evidence 변환
- 탐지 실행에 따른 DetectionResult·DetectionEvidence 자동 생성·채택
- FastAPI 탐지 endpoint와 Spring Boot 실제 연동
- 위험 대응
- 사건 생성·연결

ADR-003에서 결정한 최종 동기 분석 목표는 유지한다. 현재 단계 응답을 최종 계약으로 간주하지 않으며, 문서 확정을 구현 완료로 표현하지 않는다.

## 2. 적용 범위

Rule v1은 다음 이체 거래를 평가 대상으로 한다.

- `ACCOUNT_TRANSFER`
- `OPEN_BANKING_TRANSFER`

R001~R003은 R001의 고액·통화 조건을 기준으로 연결된다. R004는 고액 조건을 요구하지 않는다. 현재 거래 접수 API는 `KRW`만 허용하지만, R004 자체에는 별도의 금액·통화 조건을 추가하지 않는다.

다음 거래는 Rule v1 평가 대상이 아니다.

- `ATM_WITHDRAWAL`
- `LOAN_DISBURSED`

R001은 `KRW`에만 적용한다. 다른 통화가 후속 API에 추가되더라도 환율을 조회하거나 임의로 `KRW`로 환산해서 R001~R003을 적용하지 않는다. 다른 통화를 위한 금액 기준은 별도 Rule 버전 또는 별도 Rule로 승인해야 한다.

## 3. 평가 입력

### 3.1 현재 거래

Spring Boot는 최소한 다음 거래 값을 포함한 불변 평가 Snapshot을 구성한다.

| 필드 | Rule v1 사용 목적 |
| --- | --- |
| `transactionId` | 평가 대상과 결과 연결 |
| `transactionType` | 적용 대상 거래 확인 |
| `amount` | R001 고액 기준 비교 |
| `currencyCode` | R001의 `KRW` 조건 확인 |
| `occurredAt` | 평가 cutoff `T` |
| `externalCustomerRef` | 거래와 행동 이벤트의 고객 일치 확인 |
| `senderAccountRef` | R003·R004 계좌 일치 확인 |
| `recipientAccountRef` | R004 수취인 일치 확인 |
| `deviceRef` | R002 기기 일치 확인 |

R001은 이 현재 거래만 평가한다. 과거 거래 합계, 고객 평균, 거래 횟수와 다른 거래의 금액은 Rule v1 입력으로 사용하지 않는다.

### 3.2 행동 이벤트

Spring Boot는 평가 시작 시점에 조회 가능한 행동 이벤트 중 Rule v1에 필요한 최소 필드만 Snapshot에 포함한다.

| 필드 | Rule v1 사용 목적 |
| --- | --- |
| `eventId` | Evidence에서 사용한 이벤트 식별 |
| `eventType` | R002~R004 이벤트 종류 확인 |
| `occurredAt` | 시간창과 이벤트 순서 확인 |
| `externalCustomerRef` | 현재 거래와 같은 고객인지 확인 |
| `accountRef` | R003·R004의 출금 계좌 일치 확인 |
| `deviceRef` | R002의 현재 거래 기기 일치 확인 |
| `beneficiaryRef` | R004의 수취 계좌 일치 확인 |

Rule v1에서 사용하는 행동 이벤트 유형은 다음 네 가지이다.

- `DEVICE_REGISTERED`
- `PASSWORD_CHANGED`
- `TRANSFER_LIMIT_CHANGED`
- `BENEFICIARY_REGISTERED`

원문 고객번호·계좌번호·기기 식별정보, 비밀번호 값, 자유 형식 행동 로그와 불필요한 상세 데이터는 FastAPI 입력이나 Evidence에 포함하지 않는다.

## 4. 평가 기준 시각과 시간 범위

평가는 거래 접수 흐름에서 수행하며, 현재 거래의 `occurredAt`을 평가 cutoff
`T`로 사용한다. DetectionResult의 `evaluationCutoffAt`은 이 실행에서 고정한
`T`이며 Evidence 시간 검증은 시스템 현재 시각을 조회하지 않고 이 값만
사용한다.

모든 최근 24시간 시간창은 양 끝을 포함한다.

```text
T - 24시간 <= event.occurredAt <= T
```

따라서 정확히 `T - 24시간` 또는 `T`에 발생한 이벤트는 포함한다. `T - 24시간`보다 이르거나 `T`보다 늦은 이벤트는 제외한다. 서버 접수 시각, DB `createdAt`, FastAPI 호출 시각과 평가 완료 시각을 cutoff로 대체하지 않는다.

행동 Rule의 경과 초는 기존 API·DB 타임스탬프 정밀도를 유지한
`Duration.between(eventOccurredAt, evaluationCutoffAt).getSeconds()` 값이다.
임의의 오차 범위를 두지 않는다.

```text
elapsedSeconds = Duration.between(eventOccurredAt, T).getSeconds()
0 <= elapsedSeconds <= windowSeconds
```

`elapsedSeconds = 0`과 `elapsedSeconds = windowSeconds`는 허용한다.
계산값과 다른 경과 초, 음수 경과 초, `windowSeconds + 1` 이상인 경과 초와
0 이하 시간창은 거부한다.

평가 시작 시 Spring Boot가 다음 두 입력을 고정한다.

1. 현재 거래와 cutoff `T`를 기준으로 구성한 입력 Snapshot
2. 평가에 사용할 활성 Rule 버전 집합

같은 평가 실행 중 새 행동 이벤트나 Rule 활성 상태 변경이 발생해도 고정된 입력과 Rule 집합을 바꾸지 않는다.

### 4.1 내부 BehaviorEvent 조회 계약

현재 구현된 내부 Repository 조회는 다음 조건을 모두 사용한다.

```text
event.externalCustomerRef = transaction.externalCustomerRef
event.eventType IN requestedEventTypes
fromInclusive <= event.occurredAt <= toInclusive
ORDER BY event.occurredAt DESC, event.eventId ASC
```

`requestedEventTypes`는 비어 있지 않아야 한다. 호출자는 `PageRequest.of(0, limit, Sort.unsorted())`를 사용하며 `limit`은 1 이상의 유한한 값이어야 한다. Repository가 업무 최대값을 임의로 정하지 않으며 Rule Snapshot 상한은 Rule 실행 구현 전에 별도 확정한다. 반환형은 `List<BehaviorEvent>`이고 count query를 실행하지 않는다. 거래 관계는 fetch join하지 않아 `financialTransaction` LAZY 관계를 초기화하지 않는다.

이 조회는 Rule 실행을 위한 내부 계약이며 공개 행동 이벤트 조회 API를 추가하지 않는다. 다중 Event Type에서 PostgreSQL이 추가 정렬을 수행할 수 있지만 최종 업무 순서는 JPQL의 `occurredAt DESC`, `eventId ASC`가 보장한다.

## 5. R001~R004 상세 계약

### 5.1 R001: 절대 고액 이체

| 항목 | 계약 |
| --- | --- |
| Rule ID | `R001` |
| `ruleCode` | `TRANSFER_ABSOLUTE_HIGH_AMOUNT` |
| 적용 거래 | `ACCOUNT_TRANSFER`, `OPEN_BANKING_TRANSFER` |
| 통화 | `KRW` |
| 조건 | `amount >= 10,000,000` |
| 가중치 | 15 |
| 평가 데이터 | 현재 거래만 |

모든 조건을 만족하면 R001이 적중한다. 고객 평균 금액, 과거 거래, 환율과 행동 이벤트는 판단에 사용하지 않는다.

### 5.2 R002: 최근 기기 등록 이벤트가 있는 고액 이체

| 항목 | 계약 |
| --- | --- |
| Rule ID | `R002` |
| `ruleCode` | `RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT` |
| 선행 조건 | R001 적중 |
| 행동 조건 | 같은 고객과 같은 `deviceRef`의 `DEVICE_REGISTERED` 이벤트가 최근 24시간 내 존재 |
| 가중치 | 20 |

일치 조건은 다음과 같다.

```text
event.externalCustomerRef = transaction.externalCustomerRef
event.deviceRef = transaction.deviceRef
T - 24시간 <= event.occurredAt <= T
```

현재 거래 또는 이벤트의 `deviceRef`가 없으면 R002는 적중하지 않는다. 여러 이벤트가 존재해도 점수는 한 번만 부여한다. Evidence에는 `occurredAt`이 가장 늦은 적격 이벤트 하나를 사용하고, 같은 시각이면 `eventId`의 오름차순 첫 이벤트를 사용한다.

R002는 실제 신규 기기 여부, 기존 사용 이력 또는 기기 신뢰도를 판단하지 않는다. 오직 최근 `DEVICE_REGISTERED` 이벤트의 존재를 사용하는 프록시 Rule이다.

### 5.3 R003: 최근 보안정보 변경 시퀀스가 있는 고액 이체

| 항목 | 계약 |
| --- | --- |
| Rule ID | `R003` |
| `ruleCode` | `RECENT_SECURITY_CHANGE_HIGH_AMOUNT` |
| 선행 조건 | R001 적중 |
| 행동 조건 | 같은 고객의 `PASSWORD_CHANGED`와 같은 출금 계좌의 `TRANSFER_LIMIT_CHANGED`가 최근 24시간 내 모두 존재 |
| 순서 조건 | `passwordChangedAt <= transferLimitChangedAt <= transactionOccurredAt` |
| 가중치 | 40 |

두 이벤트는 모두 현재 거래와 같은 고객이어야 한다. `TRANSFER_LIMIT_CHANGED.accountRef`는 현재 거래의 `senderAccountRef`와 같아야 한다.

```text
passwordEvent.externalCustomerRef = transaction.externalCustomerRef
limitEvent.externalCustomerRef = transaction.externalCustomerRef
limitEvent.accountRef = transaction.senderAccountRef
T - 24시간 <= passwordEvent.occurredAt <= limitEvent.occurredAt <= T
T - 24시간 <= limitEvent.occurredAt <= T
```

조건을 만족하는 조합이 여러 개이면 `TRANSFER_LIMIT_CHANGED.occurredAt`이 가장 늦은 조합을 선택한다. 같은 시각이면 해당 이벤트의 `eventId` 오름차순으로 결정한다. 선택한 한도 변경 이벤트 이전 또는 같은 시각의 적격 `PASSWORD_CHANGED` 중 가장 늦은 이벤트를 사용하고, 같은 시각이면 `eventId` 오름차순으로 결정한다. 점수는 한 번만 부여한다.

R003 Evidence는 선택한 두 행동을 `passwordChangedEventId`, `passwordChangedAt`, `transferLimitChangedEventId`, `transferLimitChangedAt`으로 각각 식별한다. 두 ID는 BehaviorEvent의 내부 BIGINT PK가 아니라 canonical lowercase UUID v4 업무 ID이고 RFC 4122 variant를 만족해야 한다.

`elapsedSeconds`는 평가 cutoff `T`에서 두 선행 이벤트 중 더 최근인 `transferLimitChangedAt`을 뺀 경과 초이다.

```text
passwordChangedAt <= transferLimitChangedAt <= T
elapsedSeconds = seconds(T - transferLimitChangedAt)
```

두 이벤트 모두 `T - windowSeconds` 이상이어야 한다. 순서 역전, cutoff 이후 시각, 시간창 밖 이벤트 또는 계산과 다른 `elapsedSeconds`는 typed observation summary 검증에서 거부한다.

R003은 `TRANSFER_LIMIT_CHANGED`에 대해서 변경 발생 사실만 사용한다. 현재 입력에는 변경 전·후 한도와 변경 방향이 없으므로 실제 한도 상향 여부, 상향 폭 또는 거래 금액이 과거 한도를 초과했는지는 판단하지 못한다.

### 5.4 R004: 최근 등록 수취인 이체

| 항목 | 계약 |
| --- | --- |
| Rule ID | `R004` |
| `ruleCode` | `RECENT_BENEFICIARY_TRANSFER` |
| 고객 조건 | 행동 이벤트와 현재 거래가 같은 고객 |
| 출금 계좌 조건 | `event.accountRef = transaction.senderAccountRef` |
| 수취인 조건 | `event.beneficiaryRef = transaction.recipientAccountRef` |
| 행동 조건 | `BENEFICIARY_REGISTERED` 이벤트가 최근 24시간 내 존재 |
| 고액 조건 | 없음 |
| 가중치 | 10 |

여러 이벤트가 존재해도 점수는 한 번만 부여한다. Evidence에는 `occurredAt`이 가장 늦은 적격 이벤트 하나를 사용하고, 같은 시각이면 `eventId`의 오름차순 첫 이벤트를 사용한다.

R004는 수취인이 고객에게 최초인지 또는 과거 거래 관계가 없었는지를 판단하지 않는다. 최근 `BENEFICIARY_REGISTERED` 이벤트의 존재만 판단한다.

## 6. Reason Code와 Evidence

Rule v1에서는 적중한 Rule마다 하나의 `RULE` Evidence를 반환한다. 초기 `reasonCode`는 해당 `ruleCode`와 같은 값을 사용한다.

`ruleCode`는 FraudRule의 안정적인 논리 식별자이자 evaluator 선택자이고,
`reasonCode`는 Evidence 설명과 typed `observationSummary` 표현 형식
선택자이다. 초기 문자열은 같지만 서로 다른 개념이다. Spring Boot는 단순
문자열 equality 대신 `ruleCode → 허용 reasonCode 집합` Registry로 다음
Rule v1 조합만 허용한다. 이 구조는 향후 하나의 `ruleCode`에 여러
`reasonCode`를 등록할 수 있다.

| Rule ID | `ruleCode` | 허용 `reasonCode` |
| --- | --- | --- |
| R001 | `TRANSFER_ABSOLUTE_HIGH_AMOUNT` | `TRANSFER_ABSOLUTE_HIGH_AMOUNT` |
| R002 | `RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT` | `RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT` |
| R003 | `RECENT_SECURITY_CHANGE_HIGH_AMOUNT` | `RECENT_SECURITY_CHANGE_HIGH_AMOUNT` |
| R004 | `RECENT_BENEFICIARY_TRANSFER` | `RECENT_BENEFICIARY_TRANSFER` |

각 Evidence는 최소한 다음 내용을 포함해야 한다.

- `evidenceType = RULE`
- 불변 `ruleVersionId` 참조. 기존 호환 행은 null 가능
- `ruleCode`
- 불변 `ruleVersion`
- `reasonCode`
- 실제 점수 기여도 `scoreContribution`
- 평가 cutoff `T`
- 조건을 설명하는 민감정보 없는 관측값 요약
- 행동 이벤트를 사용한 경우 선택된 `eventId`와 `occurredAt`
- R003의 경우 선택된 비밀번호 변경·한도 변경 이벤트 식별자와 각 발생 시각

신규 애플리케이션 생성 경로는 PUBLISHED RuleVersion을 요구한다.
`ruleCode`, canonical decimal `ruleVersion`, `reasonCode`와
`scoreContribution`은 참조 RuleVersion에서 파생한다. DetectionEvidence는
FK와 위 snapshot을 함께 보존하며 FK가 있는 행은 DB에서도 네 값의
일치를 검증한다.

Reason Code별 `observationSummary`의 정확한 행동 ID 필드는 다음과 같다.

- R001은 행동 이벤트를 사용하지 않으므로 행동 Event ID 필드를 금지한다.
- R002와 R004는 선택된 단일 BehaviorEvent의 외부 업무 ID를 `eventId`로 기록한다.
- R003은 `passwordChangedEventId`와 `transferLimitChangedEventId`를 모두 기록한다.
- 행동 Event ID는 canonical lowercase UUID v4와 RFC 4122 variant를 검증한다.
- 내부 BIGINT PK, 고객·계좌·기기 원문과 행동 이벤트 전체를 복제하지 않는다.

Rule별 `evidenceOccurredAt` 기준은 다음과 같다.

| Rule ID | `evidenceOccurredAt` |
| --- | --- |
| R001 | `FinancialTransaction.occurredAt` |
| R002 | `deviceRegisteredAt` |
| R003 | `transferLimitChangedAt` |
| R004 | `beneficiaryRegisteredAt` |

행동 Rule Evidence의 `windowSeconds`는 양수여야 하며 참조
RuleVersion의 `conditionDefinition.windowSeconds`와 정확히 같아야 한다.
R001에는 `windowSeconds`를 추가하지 않는다. Spring Boot는 Evidence가
전달한 시간창과 근거 시각을 신뢰하지 않고 저장 직전에 RuleVersion,
DetectionResult의 `evaluationCutoffAt`, 거래 시각과 교차검증한다.

적중하지 않은 Rule에는 점수와 `RULE` Evidence를 만들지 않는다. 같은 Rule의 적격 이벤트가 여러 개여도 Evidence나 점수를 중복 생성하지 않는다.

`scoreContribution`은 그룹 상한 적용 전 Rule의 원래 가중치를 나타낸다. 그룹 상한으로 차감된 점수는 DetectionResult의 점수 요약에서 별도로 식별해야 하며, 개별 Evidence 값을 임의로 변경해 합계를 맞추지 않는다. 구체적인 FastAPI 응답 DTO와 DetectionResult 저장 구조는 후속 구현 계약에서 확정한다.

Evidence에는 실제 고객번호·계좌번호·기기 원문, 비밀번호 값, 전체 행동 로그 또는 설명에 필요하지 않은 원문 데이터를 포함하지 않는다.

## 7. 점수 합산과 시나리오군 상한

다음 값은 측정 완료된 운영 정책이 아니라 Rule v1 구현과 검증을 위한 초기 실험값이다.

### 7.1 scoring 계층 책임과 공개 계약

scoring 계층은 `RuleExecutionPlanRunner`가 정상적으로 결합한 결과만 입력으로
받고 다음 순수 계산만 수행한다.

- 각 실행 Rule의 적중 여부와 상한 적용 전 원래 기여도 계산
- 그룹별 `rawScore`, `cap`, `appliedScore`, `reduction` 계산
- 최종 `riskScore`와 `riskLevel` 계산
- 적용한 `scoringPolicyVersion` 기록

`RuleScoringCalculator`는 `scoring-policy-v1` 정책에 따라 Runner의 정상 결과를
점수로 계산하는 공개 scoring 소유 타입이다. 공개 계산 진입점은 다음
`RuleScoringCalculator.calculate(...)` 하나로 확정한다.

```python
RuleScoringCalculator.calculate(
    plan: RuleExecutionPlan,
    planned_results: tuple[PlannedRuleResult, ...],
) -> RuleScoringResult
```

`RuleScoringResult`와 그 중첩 값의 계약상 필드는 다음과 같다. Python 필드는
snake_case를 사용하고 업무·API 표현은 대응하는 camelCase를 사용한다.

```text
RuleScoringResult
├─ scoringPolicyVersion / scoring_policy_version: str
├─ riskScore / risk_score: int
├─ riskLevel / risk_level: RiskLevel
├─ ruleContributions / rule_contributions: tuple<RuleScoreContribution>
└─ groupSummaries / group_summaries: tuple<RuleScoreGroupSummary>

RuleScoreContribution
├─ ruleId / rule_id: RuleId
├─ executionOrder / execution_order: int
├─ matched: bool
└─ originalContribution / original_contribution: int

RuleScoreGroupSummary
├─ groupId / group_id: ScoringGroupId
├─ rawScore / raw_score: int
├─ cap: int
├─ appliedScore / applied_score: int
└─ reduction: int
```

`ScoringGroupId`의 canonical 값은 소문자 `amount`, `security` 두 개다.
`RiskLevel`의 canonical 값은 `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` 네 개다.
`ruleContributions`는 `planned_results`와 같은 plan 순서를 유지하고,
`groupSummaries`는 항상 `amount`, `security` 순서의 두 항목을 반환한다. 일부
Rule만 실행된 plan에서도 두 그룹 summary를 모두 반환하며 실행하지 않은 Rule의
기여도를 새 항목으로 보충하지 않는다.

`ruleSetVersion`과 `evaluationCutoffAt`은 이미 `RuleExecutionPlan`이 소유하는
실행 metadata이므로 `RuleScoringResult`에 복제하지 않는다. 후속 조합 계층은
plan과 scoring 결과를 함께 유지해 DetectionResult에 필요한 실행 metadata와
점수 metadata를 각각 가져와야 한다. scoring 결과에는 scoring에 고유한
`scoringPolicyVersion`만 포함한다.

scoring 계층은 raw result의 `matched`만 사용한다. Rule 조건이나 `facts`를 다시
평가하거나 보충하지 않고, plan item의 metadata와 evaluator facts를 수정하지
않는다. Evidence, `observationSummary`, DetectionResult, FastAPI DTO 생성은
scoring 책임이 아니다.

### 7.2 scoringPolicyVersion

Rule v1의 canonical `scoringPolicyVersion`은 다음 값으로 확정한다.

```text
scoring-policy-v1
```

이 값은 기존 DetectionResult·DB·API의 1~64자 trim 문자열 계약과 lowercase
kebab-case `*-v1` naming convention을 만족한다. 의미는 다음 네 정책 요소의
정확한 조합이다.

- RuleId와 `amount`·`security` 그룹의 매핑
- 그룹별 상한
- 최종 점수 상한 100
- 위험 등급 경계

`ruleSetVersion`은 어떤 RuleVersion 집합을 어떤 순서로 실행했는지 식별하는
plan의 64자 SHA-256 값이다. RuleVersion의 weight 변경은 새 불변 RuleVersion과
새 `ruleSetVersion`으로 식별한다. 반면 `scoringPolicyVersion`은 weight 자체가
아니라 위 그룹 매핑·상한·등급 정책을 식별한다.

위 네 정책 요소 중 하나라도 바뀌면 기존 `scoring-policy-v1`의 의미를 수정하지
않고 새 `scoringPolicyVersion`을 사용한다. 과거 DetectionResult가 참조한
정책 버전을 현재 정책 값으로 치환하지 않는다.

### 7.3 Rule별 원래 기여도

각 `PlannedRuleResult`의 기여도는 같은 index의 plan item weight에서만
계산한다.

```text
matched = true  → originalContribution = planItem.weight
matched = false → originalContribution = 0
```

Rule별 weight를 scoring 구현에 다시 하드코딩하지 않는다. 적중하지 않은
Rule도 실행 결과와 순서 보존을 위해 `RuleScoreContribution` 항목을 유지하되
`originalContribution`은 0이다. 적중 Rule의 원래 contribution은 그룹 상한이
적용되어도 줄이거나 다른 Rule에 재배분하지 않는다. 이 값은 후속 Evidence의
상한 적용 전 `scoreContribution`과 연결할 수 있다.

### 7.4 그룹별 raw·cap·applied·reduction

| 시나리오군 | 포함 Rule | 합산 방식 | 상한 |
| --- | --- | --- | --- |
| amount | R001 | R001 적중 점수 합산 | 15 |
| security | R002 + R003 + R004 | 적중 Rule 점수 합산 후 상한 적용 | 60 |

```text
amountRawScore = R001 originalContribution 합계
amountAppliedScore = min(15, amountRawScore)
amountReduction = amountRawScore - amountAppliedScore

securityRawScore = R002 + R003 + R004 originalContribution 합계
securityAppliedScore = min(60, securityRawScore)
securityReduction = securityRawScore - securityAppliedScore

riskScore = min(100, amountAppliedScore + securityAppliedScore)
```

`rawScore`는 해당 그룹에 속하고 실제 실행된 Rule의 원래 contribution 합계다.
`appliedScore`는 그룹 상한 적용 결과이고 `reduction = rawScore -
appliedScore`다. 그룹 차감량은 summary에만 기록하고 특정 Rule에 배분하지
않는다. 따라서 개별 contribution 합계와 최종 `riskScore`는 다를 수 있다.

공식 R001~R004 weight가 모두 적중하면 결과는 다음과 같다.

```text
Rule 원래 contribution 합계 = 15 + 20 + 40 + 10 = 85
amount raw/applied/reduction = 15/15/0
security raw/applied/reduction = 70/60/10
riskScore = min(100, 15 + 60) = 75
riskLevel = HIGH
```

Rule v1에는 음수 점수, Rule 간 상쇄, 통화 환산, ML 점수, 외부 위험 점수와 생성형 AI 점수를 포함하지 않는다.

### 7.5 순수 계산과 오류 정책

scoring은 입력 collection과 그 원소를 변경하지 않고 새로운 불변 tuple 결과를
반환한다. DB, 네트워크, 현재 시각, 환경에 따라 변하는 값과 mutable 전역 상태를
사용하지 않는다. 같은 유효 plan, 같은 ordered planned result와 같은
`scoring-policy-v1`에는 같은 결과와 순서를 반환한다.

구현 독립적인 semantic 오류 범주는 다음 최소 집합으로 정의한다. 구체적인
Python 예외 클래스와 HTTP 상태는 후속 구현·API 계약에서 정한다.

| 오류 범주 | 의미 |
| --- | --- |
| `INVALID_SCORING_INPUT` | plan 타입이 잘못됐거나 result collection이 non-tuple·빈 tuple이거나 원소가 `PlannedRuleResult`가 아님 |
| `SCORING_PLAN_RESULT_MISMATCH` | plan/result 개수, 같은 index의 plan item, RuleId 또는 execution order가 일치하지 않음 |
| `UNSUPPORTED_SCORING_RULE` | `scoring-policy-v1` 그룹 매핑에 없는 RuleId가 있음 |
| `INVALID_RULE_WEIGHT` | plan item weight가 bool이 아닌 정수 1~100 계약을 위반함 |
| `INVALID_SCORING_POLICY` | `RuleScoringCalculator`에 적용되는 scoring policy 구성 또는 policy binding이 유효하지 않거나, 정책 버전·그룹 매핑·상한·최종 상한·등급 경계가 `scoring-policy-v1` 정의와 일치하지 않음 |

scoring 계층은 plan item과 result를 같은 index에서만 연결한다. plan item을
RuleId로 재정렬하거나 결과를 보충·제거하지 않고, 잘못된 execution order를
다시 부여하지 않는다. 같은 index의 `PlannedRuleResult.plan_item`은
`plan.items[i]`와 정확히 같아야 하며 evaluation result의 RuleId도 해당 plan
item의 RuleId와 같아야 한다.

위 오류는 확인된 첫 위반에서 fail-fast할 수 있다. 오류를 `LOW`, 0점, 빈 결과,
부분 점수 또는 일부 그룹 성공으로 변환하지 않는다. retry와 fallback도
수행하지 않는다.

## 8. 위험 등급 경계

다음 경계도 초기 실험값이다.

| 위험 등급 | 점수 경계 |
| --- | --- |
| `LOW` | `0 <= riskScore < 20` |
| `MEDIUM` | `20 <= riskScore < 50` |
| `HIGH` | `50 <= riskScore < 80` |
| `CRITICAL` | `80 <= riskScore <= 100` |

R001~R004만 적용할 때 가능한 최고 점수는 `15 + 60 = 75`점이다. 따라서 현재 최소 범위에서는 `CRITICAL`이 발생하지 않는다. `CRITICAL` 경계는 후속 외부 위험 및 자금흐름 Rule 추가를 고려해 유지한다.

위험 등급은 FastAPI가 이 점수 경계로 계산해 반환하되, Spring Boot가 결과의 범위·버전·완전성을 검증하고 채택한 뒤에만 업무 기록으로 사용한다. 위험 등급은 최종 이상거래 판정이나 거래 대응 결과와 동일하지 않다.

### 8.1 후속 scoring 구현의 자동 검증 조건

후속 Python scoring 구현은 최소한 다음 사례를 자동화된 테스트로 검증해야
한다.

| 적중 Rule | 원래 contribution 합계 | 최종 `riskScore` | `riskLevel` |
| --- | ---: | ---: | --- |
| 없음 | 0 | 0 | `LOW` |
| R004 | 10 | 10 | `LOW` |
| R001 | 15 | 15 | `LOW` |
| R001 + R002 | 35 | 35 | `MEDIUM` |
| R001 + R003 | 55 | 55 | `HIGH` |
| R001 + R004 | 25 | 25 | `MEDIUM` |
| R001 + R002 + R003 + R004 | 85 | 75 | `HIGH` |

전체 적중 사례에서는 security `rawScore = 70`, `cap = 60`,
`appliedScore = 60`, `reduction = 10`과 amount `15/15/0`을 함께 검증한다.
그룹 상한 적용 뒤에도 R001~R004의 개별 `originalContribution`이 각각
15·20·40·10으로 유지되고 security 차감 10이 특정 Rule에 배분되지 않는지
검증한다.

정상·오류 사례에 공통으로 다음 조건도 검증한다.

- `ruleContributions`가 plan 순서이고 `groupSummaries`가 `amount → security`
  순서인지
- 입력 plan, planned result tuple과 중첩 원소를 변경하지 않는지
- 잘못된 plan 타입, non-tuple·빈 결과, 잘못된 결과 원소 타입을 fail-fast하는지
- plan/result 개수, 같은 index의 plan item, RuleId와 execution order 불일치를
  재정렬·보충 없이 fail-fast하는지
- 지원하지 않는 RuleId, 잘못된 weight와 잘못된 scoring policy를 거부하는지
- 오류를 0점, `LOW`, 부분 점수, retry 또는 fallback으로 바꾸지 않는지
- DB·네트워크·현재 시각·mutable 전역 상태를 사용하지 않는지

## 9. Rule 코드·버전·활성 상태 정책

- `ruleCode`는 논리 Rule의 안정적인 식별자이며 기존 의미를 다른 조건으로 재사용하지 않는다.
- FraudRule은 논리 정체성, RuleVersion은 특정 실행 설정과 Evidence
  출력 계약을 소유한다.
- R001~R004는 문서 별칭이며 별도 영속 컬럼이 아니다.
- RuleVersion의 `versionNumber`는 Rule별 1부터 증가하고
  `(fraudRuleId, versionNumber)`를 유일하게 유지한다.
- DRAFT는 실행 정의와 예정 기간을 수정할 수 있고 PUBLISHED 또는
  WITHDRAWN으로만 전이한다. WITHDRAWN은 terminal이다.
- PUBLISHED의 조건·가중치·Reason Code·적용 시작과 게시 시각은
  수정하지 않는다. 적용 종료는 null에서 유효한 시각으로 한 번만
  설정할 수 있다.
- 조건, 가중치, 시간창, 적용 대상 또는 Evidence 의미가 변경되면 기존 버전을 수정하지 않고 새 불변 버전을 생성한다.
- 과거 DetectionEvidence가 참조한 Rule 버전은 비활성화 후에도 물리 삭제하거나 현재 버전으로 치환하지 않는다.
- PUBLISHED 적용 기간은 `[effectiveFrom, effectiveTo)`이고 null 종료는
  무기한이다. `btree_gist` exclusion constraint로 같은 FraudRule의
  PUBLISHED 기간 중복을 차단한다.
- 위 반개방 적용 기간은 BehaviorEvent 조회의 `[T-window, T]` 양끝
  포함 계약과 별개다.
- Spring Boot와 PostgreSQL이 Rule 정의·버전·활성 상태의 업무 원본을 소유한다.
- 평가 시작 시 Spring Boot가 활성 Rule 집합을 고정한다.
- FastAPI는 전달받거나 승인된 방식으로 동기화한 고정 Rule 집합만 실행하며 버전·조건·가중치·활성 상태를 임의로 변경하지 않는다.

Evidence `ruleVersion` 문자열은 양의 `versionNumber`의 canonical decimal
문자열을 사용한다. 활성 전환 승인 이력, Rule 전달·동기화 방식과 불일치
시 거부 응답은 후속 구현 전에 확정한다.

## 10. Spring Boot와 FastAPI 책임 경계

### 10.1 Spring Boot

- 거래·행동 이벤트 입력 조회와 불변 Snapshot 구성
- 평가 cutoff `T` 확정
- Rule 정의·버전·활성 상태 관리와 평가 Rule 집합 고정
- FastAPI 호출 오케스트레이션
- FastAPI 결과의 요청 연결, Rule 버전, 점수, 위험 등급, Evidence와 완전성 검증
- Rule v1 Evidence의 코드 조합, 경과 초, 시간창과 근거 시각을 저장 전에
  최종 방어 검증하고 위반 시 DetectionResult와 Evidence를 함께 rollback
- DetectionResult·DetectionEvidence 영속화와 채택
- 거래·위험 대응·사건 상태의 업무 정합성 관리

### 10.2 FastAPI

- 전달된 Snapshot을 사용한 Feature 계산
- 고정된 Rule 집합으로 R001~R004 실행
- 그룹 상한을 포함한 점수와 위험 등급 계산
- Reason Code와 Evidence 생성
- 사용한 Rule 코드·버전과 분석 실행 정보 반환

FastAPI는 거래 상태, 위험 대응, 사건 생성·상태와 최종 판정을 확정하거나 Spring Boot 업무 DB에 직접 저장하지 않는다.

### 10.3 생성형 AI

생성형 AI는 R001~R004 실행, Rule 점수 계산, 그룹 상한 적용, 위험 등급 판정과 거래 대응 결정에 관여하지 않는다. 후속 AI 사건 리포트가 추가되더라도 채택된 Rule·ML 결과를 설명하는 별도 경로이며 탐지 결과를 변경하지 않는다.

## 11. 늦게 도착한 행동 이벤트

Rule v1은 거래 접수 시 한 번 평가한다. 행동 이벤트 접수를 트리거로 자동 재평가하지 않는다.

행동 이벤트의 `occurredAt`이 시간창 안에 있더라도 평가 Snapshot이 고정된 뒤 Spring Boot에 접수되었다면 현재 평가에는 포함하지 않는다. 늦게 도착한 이벤트가 기존 DetectionResult를 수정하거나 같은 버전의 점수·Evidence를 덮어써서는 안 된다.

후속 재평가 기능을 도입할 경우에는 다음을 별도 계약으로 확정해야 한다.

- 재평가 트리거와 허용 시간
- 새 DetectionResult 버전 생성 규칙
- 이전 채택 결과와 거래·사건 상태에 미치는 영향
- 중복 재평가와 동시성 통제
- 담당자 화면과 감사 이력 표현

이 정책이 확정되기 전에는 늦은 이벤트를 이유로 자동 재평가하지 않는다.

## 12. 프록시 Rule의 한계

Rule v1의 행동 Rule은 현재 수집 가능한 이벤트의 존재를 사용한 Baseline이다.

- R002는 최근 기기 등록 이벤트를 실제 신규 기기 여부나 기기 신뢰도 대신 사용한다.
- R003은 비밀번호 변경과 이체 한도 변경 이벤트의 순서를 사용하지만 실제 한도 상향·상향 폭을 판단하지 못한다.
- R004는 최근 수취인 등록 이벤트를 사용하지만 고객 최초 수취인 또는 과거 거래 관계 부재를 판단하지 못한다.
- R001은 절대 금액만 사용하며 고객별 평소 금액이나 소득·자산·거래 기준선을 판단하지 않는다.

Reason Code, 화면 설명, 사건 리포트와 운영 보고에서는 이 프록시를 실제 기기 신뢰도, 실제 한도 상향 또는 최초 수취인 판정처럼 표현하지 않는다.

## 13. 장애와 미구현 범위

FastAPI Timeout·응답 부재·검증 실패 시 Spring Boot는 임의 점수, `LOW` 또는 빈 Evidence를 정상 결과로 생성하지 않는다. 실패 상태와 재시도·복구 방식은 최종 동기 처리 구현 전에 별도 승인해야 한다.

다음 항목은 Rule v1 계약 범위에 포함하지 않는다.

- ML 추론과 ML 점수 통합
- External Risk 조회와 외부 위험 점수 통합
- 자금흐름·반복 거래·다계좌 집계 Rule
- 고객별 금액 기준선과 기기 신뢰도 계산
- 실제 한도 상향·상향 폭 판정
- 행동 이벤트 접수에 따른 자동 재평가
- 실제 거래 승인·추가 인증·보류·차단과 고객 제재
- 사건 생성·병합·분리 정책
- 생성형 AI 리포트 구현
- 운영 PostgreSQL·Redis·Kafka, Docker Compose, Kubernetes와 AWS 배포 환경

현재 PostgreSQL 애플리케이션 연동과 Flyway V1~V5 기반 거래·멱등·행동
이벤트, DetectionResult·DetectionEvidence와 FraudRule·RuleVersion 물리
스키마가 구현되어 있다. Rule 평가용 BehaviorEvent 내부 시간창 조회,
Rule·Evidence typed JSON 검증, RuleVersion 기간 중복 방지,
DetectionEvidence FK·snapshot 정합성과 Evidence 시간·코드 저장 경계
검증도 구현되어 있다. AI Service의 R001~R004 evaluator, Registry,
Orchestrator, 실행 plan·builder와 Runner·planned result까지 구현되어 있다.
scoring, Evidence 변환, DetectionResult 생성, 공개 탐지 API, Spring Boot 실제
연동과 운영 배포 환경은 구현되지 않았다.

## 14. 후속 구현 순서

1. 현재 `RECEIVED`/null 거래 접수 응답과 최종 동기 응답 사이의 전환 정책을 정하고, 기존 멱등 `response_snapshot`의 스키마·재생 호환·만료 데이터 처리 방식을 확정한다.
2. Spring Boot가 평가 cutoff, 입력 Snapshot과 활성 Rule 집합을 고정하고 실제
   서비스 입력으로 전달하는 경계를 구현한다.
3. 구현된 Runner의 정상 `PlannedRuleResult`를 입력으로 이 문서의
   `scoring-policy-v1` scoring 계층을 구현한다.
4. plan metadata와 raw facts를 사용한 Evidence·`observationSummary` 변환을
   별도 계층으로 구현한다.
5. FastAPI 탐지 endpoint와 DTO를 확정하고 DetectionResult 생성에 필요한
   결과의 완전성을 검증한다.
6. Spring Boot의 FastAPI 호출, 결과 검증·영속화·채택과 장애 처리를 구현한다.
7. ADR-003의 최종 동기 거래 처리 흐름에 위험 대응과 사건 연결을 통합하고 멱등·동시성·실패 복구를 검증한다.
8. 경계값·복합 적중·늦은 이벤트·Timeout·성능·관측 지표 테스트와 실험을 수행한 뒤 운영 정책 후보를 재승인한다.

1번의 멱등 응답 snapshot 호환 문제는 이 문서에서 해결된 것으로 간주하지 않는다. 후속 구현 전에 사용자가 결정해야 하는 과제이다.

## 15. 사용자가 재검증해야 하는 초기 실험값

다음 값은 운영 데이터로 측정하거나 승인된 정책 효과를 검증한 값이 아니다.

- R001의 `10,000,000 KRW` 기준
- R001~R004의 15·20·40·10 가중치
- 모든 행동 Rule의 24시간 시간창
- amount 15점, security 60점 그룹 상한
- `LOW`·`MEDIUM`·`HIGH`·`CRITICAL` 경계
- R001~R004만으로 `CRITICAL`이 발생하지 않는 구조의 적절성
- R002 최근 등록 이벤트가 기기 위험 프록시로 유효한 정도
- R003 이벤트 시퀀스가 실제 한도 상향 위험을 대리하는 정도
- R004 최근 등록 이벤트가 신규 수취인 위험을 대리하는 정도
- 늦게 도착한 행동 이벤트의 비율과 자동 재평가를 하지 않을 때의 누락 영향
- security 그룹에서 원래 합계 70점을 60점으로 제한하는 효과

최소한 경계값 테스트, 정상·오탐·확정 이상거래 표본, Rule 단독·복합 적중 분포, 위험 등급 분포, 이벤트 지연 분포와 FDS 담당자 검토 결과를 사용해 재검증해야 한다. 측정 전에는 비용 절감률, 탐지율, 오탐률 또는 성능 향상을 성과로 표현하지 않는다.
