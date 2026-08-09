# RuleVersion 기반 Rule 실행 계획 내부 계약

## 1. 문서 목적과 구현 상태

이 문서는 [GitHub Issue #108](https://github.com/Ahnjisan/FinGuardOps/issues/108)에
따라 Spring Boot가 고정한 활성 RuleVersion 업무 snapshot을 FastAPI 내부의
결정적인 ordered `RuleExecutionPlan`으로 변환하는 계약을 정의하고,
[GitHub Issue #112](https://github.com/Ahnjisan/FinGuardOps/issues/112)에 따라
검증된 plan을 실행하고 raw evaluator 결과와 결합하는 내부 계약을 함께
정의한다.

이 문서는 다음 연결 경계를 다룬다.

```text
Spring Boot의 활성 RuleVersion 업무 snapshot
→ RuleExecutionPlanBuilder의 mapping·dependency·설정·capability 검증
→ 불변 ordered RuleExecutionPlan
→ RuleExecutionPlanRunner의 plan·입력 실행 정합성 검증
→ 기존 RuleExecutionOrchestrator의 evaluator resolve·순차 실행
→ ordered raw RuleEvaluatorResult tuple
→ RuleExecutionPlanRunner의 strict index 결합
→ ordered PlannedRuleResult tuple
→ 후속 scoring·Evidence 계층
```

현재 구현 상태는 다음과 같다.

- FraudRule·RuleVersion JPA·PostgreSQL 물리 모델과 lifecycle: 구현됨
- R001~R004 순수 evaluator: 구현됨
- 불변 `RuleEvaluatorRegistry`: 구현됨
- `RuleExecutionOrchestrator`: 구현됨
- 이 문서의 RuleVersion 기반 실행 계획 계약: 문서 정의 완료
- `RuleExecutionPlan`, `RuleExecutionPlanItem`과 순수
  `RuleExecutionPlanBuilder`: 구현됨
- `RuleExecutionPlanRunner` 실행·결합 계약과 Python 구현: 구현됨
- `PlannedRuleResult` 계약과 Python 구현: 구현됨
- 활성 RuleVersion 전체 조회·업무 snapshot 생성: 미구현
- RuleVersion 설정 전달과 typed evaluator settings: 미구현
- Spring Boot·FastAPI 실제 연동: 미구현
- `RuleScoringCalculator`와 점수·위험 등급 계산: 구현됨
- Evidence Transformer와 `RuleAnalysisResult`: 구현됨
- DetectionResult 처리: 미구현 후속 범위

현재 순수 Builder 구현은 전달받은 RuleVersion snapshot을 plan으로 변환하고,
Runner는 plan을 기존 Orchestrator로 실행해 ordered raw result와 strict index로
결합한다. 구현된 `RuleScoringCalculator`는 정상 결합 결과를
`scoring-policy-v1`에 따라 점수로 계산한다. 이 순수 실행·scoring 경로가
구현되었고 후속 Evidence 변환과 Rule 분석 결과 조합도 구현되어 있다. 이 순수
내부 경로가 구현되었다는 사실은 FastAPI Endpoint나 Spring Boot 실제 연동이
구현되었다는 뜻이 아니다.

공식 Rule 조건과 평가 의미는
[Rule v1 탐지 계약](./rule-v1-detection-contract.md)을 따르고, ordered Rule ID
실행의 하위 계약은
[Rule 실행 오케스트레이션 내부 계약](./rule-execution-orchestration-contract.md)을
따른다. RuleVersion 물리 모델과 게시 후 불변성은
[FraudRule·RuleVersion PostgreSQL 물리 DB 계약](../04-database/fraud-rule-version-schema.md)과
[ADR-005](../07-decisions/ADR-005-fraud-rule-version-model.md)를 변경하지 않는다.

## 2. 용어와 식별자

### 2.1 활성 RuleVersion 업무 snapshot

활성 RuleVersion 업무 snapshot은 하나의 평가 실행을 위해 Spring Boot가
`evaluationCutoffAt` 기준으로 선택하고 불변 값으로 고정한 실행 가능한
RuleVersion 집합이다.

snapshot은 원본 JPA Entity나 영속성 context를 FastAPI에 노출하는 객체가
아니다. 서비스 간 실제 DTO와 통신 방식은
[Rule v1 내부 분석 API](../03-api/rule-v1-analysis-api.md)를 따르며 다음 검증에
필요한 업무 값을 포함해야 한다.

- FraudRule 업무 UUID `fraudRuleId`
- `ruleCode`와 `lifecycleStatus`
- RuleVersion 업무 UUID `ruleVersionId`
- `versionNumber`, `status`, `reasonCode`, `weight`
- `conditionDefinition`
- `effectiveFrom`과 `effectiveTo`

DB 내부 BIGINT PK인 `fraud_rule.id`와 `rule_version.id`는 서비스 간 업무
식별자로 사용하지 않는다.

### 2.2 내부 RuleId

`RuleId`는 FastAPI가 구현한 evaluator capability를 식별하는 내부 ID다.
현재 값은 `R001`, `R002`, `R003`, `R004`이며 DB의 `ruleCode`나
RuleVersion 업무 ID와 같은 개념이 아니다.

### 2.3 RuleExecutionPlan

`RuleExecutionPlan`은 검증이 완료된 RuleVersion snapshot을 내부
`RuleId`로 연결하고 canonical order로 정렬한 불변 실행 계획이다. plan이
확정된 뒤에는 원본 snapshot, 원본 JSON 또는 가변 collection의 변경이
plan에 영향을 주지 않는다.

### 2.4 raw result와 planned result

raw result는 기존 `RuleExecutionOrchestrator`가 반환하는
`RuleEvaluatorResult`다. 이는 Registry가 공개하는 union type alias이며 실제
인스턴스의 generic 클래스는 `RuleEvaluationResult`다. `PlannedRuleResult`는
하나의 plan item과 같은 index의 raw result를 묶는 구현된 불변 중첩 구조다.
이 문서는 두 타입의 현재 Python 구현을 변경하지 않는다.

## 3. 책임 경계

### 3.1 Spring Boot

Spring Boot는 다음 책임을 가진다.

- 현재 거래의 `transaction.occurredAt`을 `evaluationCutoffAt`으로 확정
- PostgreSQL에서 `evaluationCutoffAt`에 실행 가능한 RuleVersion 조회
- FraudRule lifecycle, RuleVersion status와 effective period 검증
- 동일 FraudRule의 복수 실행 가능 버전을 숨기지 않고 거부
- 하나의 일관된 읽기 경계에서 RuleVersion 업무 snapshot 생성
- 필요한 값을 deep copy하고 원본 JPA Entity·`JsonNode` 참조를 제거

Spring Boot는 Python 내부 `RuleId` mapping이나
`RuleEvaluatorRegistry`를 소유하지 않는다.

### 3.2 RuleExecutionPlanBuilder

`RuleExecutionPlanBuilder`는 기존 Orchestrator와 Runner보다 앞에서 다음
책임을 가진다.

- 전달받은 업무 snapshot 구조와 선택 조건 재검증
- exact `ruleCode → RuleId` mapping
- Rule dependency 검증
- RuleVersion `conditionDefinition` typed parsing과 현재 evaluator 설정
  호환성 검증
- Rule v1 canonical order 적용과 `executionOrder` 부여
- `ruleSetVersion` 생성
- 모든 `RuleId`의 Registry capability 사전 검증
- 불변 `RuleExecutionPlan` 생성

Builder는 Orchestrator를 호출하거나 evaluator를 실행하지 않고 plan과
`RuleEvaluationInput`의 cutoff 정합성을 검증하지 않는다.

### 3.3 RuleExecutionPlanRunner

`RuleExecutionPlanRunner`는 Builder가 생성한 plan과 평가 입력 사이의 실행
경계를 담당한다.

- plan과 `RuleEvaluationInput`의 지원 타입 검증
- 실행 순서에 직접 영향을 주는 plan 구조의 방어 검증
- plan의 `evaluationCutoffAt`과 거래 `occurredAt` 일치 검증
- `plan.items`의 물리적 순서에서 ordered Rule ID tuple 생성
- 기존 `RuleExecutionOrchestrator.execute()` 정확히 1회 호출
- ordered raw result의 타입·개수·RuleId 후조건 검증
- plan item과 같은 index의 raw result를 `PlannedRuleResult`로 불변 결합
- 오류 시 planned 부분 결과, retry와 fallback 금지

Runner는 Builder의 업무·설정 validation을 다시 수행하거나 Registry를 직접
조회하지 않는다. 이 책임은 현재 Python 구현과 자동화된 테스트에 반영되어
있다.

### 3.4 기존 RuleExecutionOrchestrator

기존 `RuleExecutionOrchestrator`는 다음 책임을 유지한다.

- ordered Rule ID Sequence 형태·원소·빈 값·중복 검증
- 모든 evaluator capability resolution
- 모든 resolution 성공 후 순차 evaluator 실행
- ordered raw result tuple 반환
- evaluator 예외와 반환 `rule_id` 불일치 시 fail-fast
- 빈 결과 대체와 정상 부분 결과 반환 금지

다음은 Orchestrator 책임이 아니다.

- DB 조회와 활성 RuleVersion 선택
- RuleVersion 업무 snapshot 생성
- `ruleCode → RuleId` mapping
- dependency와 RuleVersion 설정 호환성 검증
- 실행 순서 정책 결정
- `ruleSetVersion` 생성
- weight 적용, 점수 합산과 위험 등급 계산
- Evidence·Reason Code 변환과 DetectionResult 영속화

### 3.5 scoring 계층

구현된 scoring 계층은 정상 결합된 `PlannedRuleResult`를 입력으로 적중 Rule의
weight 적용, 그룹 상한, 점수 합산과 위험 등급을 계산한다. 구현된 Evidence
계층은 plan metadata, raw facts와 scoring 결과를 사용해 Evidence와 Rule 분석
결과를 구성한다. scoring과 Evidence·DetectionResult 처리는 Builder, Runner와
Orchestrator의 책임이 아니다.

FastAPI의 모든 내부 계층은 Spring Boot의 업무 DB를 직접 조회하지 않고
RuleVersion lifecycle, status, 기간과 weight를 임의로 변경하지 않는다.

## 4. 평가 기준 시각과 활성 RuleVersion 선택

평가 기준 시각은 현재 거래의 `transaction.occurredAt`이며 이를
`evaluationCutoffAt`으로 고정한다.

실행 가능한 RuleVersion 조건은 다음과 같다.

```text
FraudRule.lifecycleStatus = ACTIVE
RuleVersion.status = PUBLISHED
RuleVersion.effectiveFrom <= evaluationCutoffAt
RuleVersion.effectiveTo IS NULL
    OR evaluationCutoffAt < RuleVersion.effectiveTo
```

적용 기간은 `[effectiveFrom, effectiveTo)` 반개방 구간이다. 다음 시각은
선택 기준으로 사용하지 않는다.

- 서버 현재 시각
- PostgreSQL 조회 시각
- Spring Boot 요청 접수 시각
- FastAPI 호출 시각
- evaluator 실행 시작·완료 시각
- DetectionResult 완료 시각

동일 FraudRule에 같은 `evaluationCutoffAt`으로 실행 가능한 RuleVersion이
둘 이상이면 `MULTIPLE_EXECUTABLE_RULE_VERSIONS` 구성·무결성 오류다.
`versionNumber`가 큰 행, 최신 `publishedAt` 행, DB의 첫 행 또는 임의의 한
행을 선택하지 않는다.

실행 가능한 RuleVersion이 0개이면
`NO_EXECUTABLE_RULE_VERSION` 실행 계획 구성 오류다. 빈 plan, 빈 raw result,
0점 또는 `LOW` 결과로 변환하지 않는다.

0개가 아닌 일부 Rule만 실행 가능한 것은 허용하되, 8절의 dependency를
만족해야 한다.

## 5. snapshot 고정과 변경 격리

Spring Boot는 `evaluationCutoffAt`을 확정한 뒤 하나의 일관된 읽기 경계에서
활성 RuleVersion 집합을 조회하고 업무 snapshot으로 고정한다. 여러 번의
독립 조회 결과를 조합해 평가 도중 서로 다른 설정 시점을 섞어서는 안 된다.

snapshot과 plan은 다음 불변성 조건을 만족해야 한다.

- 원본 JPA Entity를 보관하거나 FastAPI에 전달하지 않는다.
- 원본 `JsonNode`나 mutable mapping·list·set을 보관하지 않는다.
- `conditionDefinition`은 deep copy 후 Rule별 typed value로 파싱한다.
- collection은 순서가 확정된 immutable collection으로 고정한다.
- UUID, 코드, 정수, 시각과 nullable 종료 시각은 타입 검증한 값으로 복사한다.
- plan 확정 이후 원본 Entity, JSON이나 입력 collection의 변경을 관찰하지
  않는다.
- 같은 평가 실행 중 RuleVersion이 종료되거나 새 버전이 게시되어도 현재
  plan을 교체하거나 다시 조회하지 않는다.

PUBLISHED RuleVersion의 실행 정의가 게시 후 불변이라는 기존 DB 계약을
전제로 한다. PUBLISHED `effectiveTo`가 snapshot 이후 설정되어도 이미 고정한
현재 실행에는 영향을 주지 않는다.

## 6. exact ruleCode → RuleId bridge

Rule v1의 공식 bridge는 다음과 같다.

| `ruleCode` | 내부 `RuleId` |
| --- | --- |
| `TRANSFER_ABSOLUTE_HIGH_AMOUNT` | `R001` |
| `RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT` | `R002` |
| `RECENT_SECURITY_CHANGE_HIGH_AMOUNT` | `R003` |
| `RECENT_BENEFICIARY_TRANSFER` | `R004` |

mapping 정책은 다음과 같다.

- case-sensitive exact match만 허용한다.
- trim하지 않는다.
- uppercase나 lowercase로 변환하지 않는다.
- alias fallback을 사용하지 않는다.
- 알 수 없는 `ruleCode`는 `UNKNOWN_RULE_CODE` 오류다.
- 하나의 `ruleCode`는 하나의 `RuleId`에만 대응한다.
- 서로 다른 `ruleCode`가 같은 `RuleId`로 수렴하는 mapping을 금지한다.
- 모든 `ruleCode` mapping이 성공한 뒤에만 실행 계획을 확정한다.

하나라도 mapping에 실패하면 이미 mapping된 Rule만 실행하지 않고 evaluator
호출 0회를 유지한다.

## 7. 결정적인 canonical 실행 순서

Rule v1 canonical order는 다음과 같다.

```text
R001 → R002 → R003 → R004
```

활성 Rule만 위 순서에 따라 필터링하고 정렬한다. 최종 plan item의
`executionOrder`는 정렬된 plan 안에서 1부터 시작하는 연속 정수다.

예를 들어 활성 Rule이 `R001`, `R003`, `R004`이면 결과는 다음과 같다.

| `executionOrder` | `RuleId` |
| ---: | --- |
| 1 | `R001` |
| 2 | `R003` |
| 3 | `R004` |

실행 순서는 다음 값이나 동작에 의존하지 않는다.

- DB 조회 결과 순서
- DB 내부 PK
- `fraudRuleId` 또는 `ruleVersionId` UUID 순서
- `versionNumber`
- `effectiveFrom` 또는 `publishedAt`
- Registry 등록 순서
- `ruleCode`나 `RuleId` 문자열 사전식 정렬

현재 DB에 `execution_order` 컬럼을 추가하지 않는다. canonical order는 이
계약의 Rule v1 정책이다.

## 8. 일부 Rule 활성화와 dependency

일부 Rule만 활성화하는 것은 허용한다. 다만 다음 plan dependency를
검증한다.

- `R002`가 plan에 있으면 `R001`도 있어야 한다.
- `R003`이 plan에 있으면 `R001`도 있어야 한다.
- `R001`은 `R002`와 `R003`보다 앞서야 한다.
- `R004`는 독립적으로 활성화할 수 있다.

`R001` 없이 `R002` 또는 `R003`이 활성화된 snapshot은
`MISSING_RULE_DEPENDENCY` 오류다.

이 dependency는 evaluator result 전달 의존성이 아니다. 현재 `R002`와
`R003` evaluator는 `R001` raw result 객체를 입력받지 않고 같은 고액 조건을
독립적으로 재평가한다. dependency 검증은 공식 Rule v1 의미와 Rule 구성의
완전성을 보존하는 plan 정책이다.

## 9. RuleExecutionPlan 계약

개념적인 최소 구조는 다음과 같다.

```text
RuleExecutionPlan
├─ evaluationCutoffAt: timezone-aware UTC datetime
├─ ruleSetVersion: lowercase hexadecimal SHA-256 string
└─ items: ordered immutable collection<RuleExecutionPlanItem>
```

필드 계약은 다음과 같다.

| 필드 | 계약 |
| --- | --- |
| `evaluationCutoffAt` | 현재 거래의 `occurredAt`과 정확히 같은 평가 기준 시각 |
| `ruleSetVersion` | 12절의 canonical input으로 생성한 64자 lowercase SHA-256 |
| `items` | 1개 이상의 ordered immutable plan item |

`RuleExecutionPlan`은 실행 중 DB를 다시 조회하거나 원본 snapshot을 참조해
필드를 보충하지 않는다.

## 10. RuleExecutionPlanItem 계약

각 item은 다음 필드를 포함한다.

| 필드 | 타입·의미 |
| --- | --- |
| `ruleVersionId` | RuleVersion 업무 UUID. canonical lowercase UUID 문자열로 표현 가능 |
| `ruleCode` | FraudRule의 불변 논리 코드 |
| `ruleId` | exact bridge로 해결한 내부 `RuleId` |
| `versionNumber` | Rule별 1부터 증가하는 양의 정수 업무 버전 |
| `reasonCode` | RuleVersion이 소유하는 Evidence typed 계약 코드 snapshot |
| `weight` | 1~100 원래 점수 기여도 snapshot |
| `conditionDefinition` | deep copy 후 typed parsing과 호환성 검증을 완료한 불변 설정 |
| `effectiveFrom` | 실행 가능 기간의 포함 시작 시각 |
| `effectiveTo` | 실행 가능 기간의 제외 종료 시각, 무기한이면 null |
| `executionOrder` | canonical order에 따라 1부터 부여한 연속 정수 |

DB 내부 BIGINT `id`는 item에 포함하지 않는다. 모호한 문자열 `version` 대신
양의 정수 `versionNumber`를 사용한다.

`reasonCode`와 `weight`는 downstream Evidence·scoring 연결을 위한 snapshot이다.
이 문서는 Reason Code를 Evidence로 변환하거나 weight를 점수에 적용하지
않는다.

## 11. RuleVersion conditionDefinition 호환성

### 11.1 과도기 정책

현재 RuleVersion은 버전별 조건값을 가질 수 있지만 Python evaluator는
`RuleEvaluationInput`으로 설정값을 받지 않고 코드의 고정 상수를 사용한다.
따라서 현재 Rule v1 plan 생성 계층은 다음 과도기 정책을 적용한다.

1. `conditionDefinition`을 snapshot에 포함한다.
2. `ruleCode`별 정확한 typed 구조로 파싱한다.
3. 필수 필드 누락, 알 수 없는 필드, null과 잘못된 scalar·array 타입을
   거부한다.
4. 설정값을 JSON 문자열, key 순서, 공백 또는 객체 메모리 표현으로 비교하지
   않는다.
5. 파싱한 의미 값이 11.2절의 현재 evaluator 지원값과 정확히 같은지 검증한다.
6. 하나라도 다르면 `UNSUPPORTED_RULE_CONFIGURATION` 오류로 전체 plan을
   거부한다.
7. 모든 Rule의 설정 호환성 검증이 끝난 뒤에만 Orchestrator를 호출한다.

향후 typed evaluator settings를 evaluator에 주입하는 계약과
`RuleEvaluationInput`·evaluator signature 변경은 별도 작업이다.

### 11.2 Rule별 현재 지원 설정

다음 값은 공식 Rule v1 계약, 현재 `RuleConditionDefinition` typed allowlist,
`_shared.py`와 R001~R004 evaluator 구현이 함께 지원하는 정확한 값이다.

#### R001

| 필드 | 현재 지원값 |
| --- | --- |
| `transactionTypes` | 중복 없는 `ACCOUNT_TRANSFER`, `OPEN_BANKING_TRANSFER` 정확 집합 |
| `currencyCode` | `KRW` |
| `amountThreshold` | canonical decimal integer string `10000000` |

`transactionTypes`의 JSON 배열 순서는 의미 비교 기준이 아니지만, 다른 값,
누락과 중복은 허용하지 않는다.

#### R002

| 필드 | 현재 지원값 |
| --- | --- |
| `prerequisiteRuleCode` | `TRANSFER_ABSOLUTE_HIGH_AMOUNT` |
| `eventType` | `DEVICE_REGISTERED` |
| `windowSeconds` | 정수 `86400` |
| `matchPolicy` | `SAME_CUSTOMER_AND_DEVICE` |
| `selectionPolicy` | `LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC` |

R002 evaluator는 R001과 같은 지원 거래 유형, `KRW`와 `10000000` 고액
기준을 코드에서 재평가한다. 따라서 활성 R001 설정이 이 값과 다르면 R001
자체 호환성 검증에서 전체 plan을 거부한다.

#### R003

| 필드 | 현재 지원값 |
| --- | --- |
| `prerequisiteRuleCode` | `TRANSFER_ABSOLUTE_HIGH_AMOUNT` |
| `passwordEventType` | `PASSWORD_CHANGED` |
| `transferLimitEventType` | `TRANSFER_LIMIT_CHANGED` |
| `windowSeconds` | 정수 `86400` |
| `matchPolicy` | `SAME_CUSTOMER_AND_SENDER_ACCOUNT` |
| `sequencePolicy` | `PASSWORD_CHANGED_AT_OR_BEFORE_TRANSFER_LIMIT_CHANGED` |
| `selectionPolicy` | `LATEST_TRANSFER_LIMIT_THEN_EVENT_ID_ASC_LATEST_PASSWORD_THEN_EVENT_ID_ASC` |

R003 evaluator도 R001과 같은 고액 기준을 코드에서 재평가한다. R001과 R003의
공통 고액 의미가 달라지는 구성은 현재 지원하지 않는다.

#### R004

| 필드 | 현재 지원값 |
| --- | --- |
| `eventType` | `BENEFICIARY_REGISTERED` |
| `windowSeconds` | 정수 `86400` |
| `matchPolicy` | `SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY` |
| `selectionPolicy` | `LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC` |

R004에는 고액·통화 조건을 추가하지 않는다. R002·R003·R004의 행동 시간창은
모두 현재 evaluator가 지원하는 `86400`초와 정확히 같아야 한다. Rule별
시간창이 다르거나 `86400`이 아니면 현재 v1에서는 지원하지 않는다.

## 12. 결정적인 ruleSetVersion

별도 RuleSet Entity가 없으므로 `ruleSetVersion`은 검증과 정렬이 끝난
ordered immutable plan snapshot에서 결정적으로 생성한다.

### 12.1 canonical input

canonical input은 다음 line-based 형식이다.

```text
rule-plan-v1
<executionOrder>\t<ruleVersionId>\t<ruleCode>\t<RuleId>\t<versionNumber>
...
```

정확한 직렬화 규칙은 다음과 같다.

1. 첫 줄은 ASCII 문자열 `rule-plan-v1`이다.
2. 각 item은 확정된 `executionOrder` 오름차순으로 한 줄씩 기록한다.
3. item 필드 순서는 `executionOrder`, `ruleVersionId`, `ruleCode`,
   `RuleId`, `versionNumber`다.
4. 필드 구분자는 하나의 U+0009 TAB이다.
5. 줄 구분자는 플랫폼과 무관하게 하나의 U+000A LF다.
6. 첫 줄과 모든 item 줄 뒤에 LF를 기록하며 마지막 item 뒤에도 LF를
   포함한다.
7. `ruleVersionId`는 hyphen을 포함한 canonical lowercase UUID 문자열이다.
8. 정수는 leading zero 없는 양의 canonical decimal 문자열이다.
9. UTF-8로 인코딩하며 BOM을 추가하지 않는다.
10. JSON serializer, JSON key 순서, 객체 메모리 표현과 DB 조회 순서를
    사용하지 않는다.

`ruleCode`와 `RuleId`는 허용된 exact 값이고 UUID와 정수 표현도 제한되므로
TAB과 LF가 필드 값에 들어갈 수 없다.

### 12.2 canonical 예시

R001, R003, R004의 versionNumber 1이 활성인 예시는 다음과 같다. 아래
code block에는 각 줄 사이에 실제 LF 하나가 있고 필드 사이에는 TAB 하나가
있으며 마지막 줄 뒤에도 LF 하나가 포함된다.

```text
rule-plan-v1
1	20000000-0000-4000-8000-000000000001	TRANSFER_ABSOLUTE_HIGH_AMOUNT	R001	1
2	20000000-0000-4000-8000-000000000003	RECENT_SECURITY_CHANGE_HIGH_AMOUNT	R003	1
3	20000000-0000-4000-8000-000000000004	RECENT_BENEFICIARY_TRANSFER	R004	1
```

canonical input의 UTF-8 byte sequence에 SHA-256을 적용한다.
`ruleSetVersion`은 digest의 64자 lowercase hexadecimal 문자열이다.
위 예시의 `ruleSetVersion`은 다음과 같다.

```text
085edb92debd4e80d8472f77fab507d846810c668268ee34d8ee97ec2c917b26
```

hash input에는 weight, reasonCode, `conditionDefinition`과 effective period를
추가하지 않는다. 이 값들은 PUBLISHED 이후 불변인 `ruleVersionId`가
식별하는 실행 버전의 속성이며, RuleVersion 실행 정의가 변경되면 새
`ruleVersionId`와 `versionNumber`를 사용한다는 기존 DB 계약을 전제로 한다.

## 13. validation 순서

FastAPI 실행 계획 생성 계층은 첫 evaluator 호출 전에 최소한 다음 순서로
검증한다.

1. `evaluationCutoffAt`과 snapshot collection·원소 구조를 검증한다.
2. 실행 가능한 RuleVersion이 0개인지 검증한다.
3. 각 FraudRule lifecycle, RuleVersion status와 effective period를
   `evaluationCutoffAt` 기준으로 검증한다.
4. exact `ruleVersionId` 중복을 검증한다.
5. exact `ruleCode` 중복과 동일 FraudRule 복수 실행 가능 버전을 검증한다.
6. 모든 `ruleCode`를 exact `RuleId`로 mapping한다.
7. mapping 결과의 중복 `RuleId`를 검증한다.
8. R001·R002·R003 dependency를 검증한다.
9. 모든 `conditionDefinition`의 typed 구조와 현재 evaluator 설정 호환성을
   검증한다.
10. canonical order로 정렬하고 1부터 연속 `executionOrder`를 부여한다.
11. deep copy와 typed 불변 값만 사용하는 plan snapshot을 확정하고
    `ruleSetVersion`을 생성한다.
12. 모든 `RuleId`의 Registry capability resolution 가능성을 검증한다.
13. 검증된 불변 `RuleExecutionPlan`을 반환한다.

앞 단계 실패는 뒤 단계보다 우선한다. 실행 계획 생성 계층은 오류를 모두
수집하기 위해 validation을 계속 진행하지 않아도 된다. 다만 어느 단계에서
실패하더라도 evaluator 호출 횟수는 0회여야 한다. Builder는 정상 완료한
경우에도 Orchestrator나 evaluator를 호출하지 않는다.

## 14. 오류 범주와 실패 정책

다음은 구현 독립적인 계약 오류 범주다.

| 오류 범주 | 의미 |
| --- | --- |
| `NO_EXECUTABLE_RULE_VERSION` | 실행 가능한 RuleVersion이 0개 |
| `MULTIPLE_EXECUTABLE_RULE_VERSIONS` | 같은 FraudRule에 같은 cutoff 기준 실행 가능 버전이 복수 |
| `UNKNOWN_RULE_CODE` | exact bridge에 없는 `ruleCode` |
| `DUPLICATE_RULE_VERSION_ID` | snapshot에 같은 업무 `ruleVersionId`가 중복 |
| `DUPLICATE_RULE_CODE` | snapshot에 같은 exact `ruleCode`가 중복 |
| `DUPLICATE_RULE_ID` | mapping 결과에 같은 내부 `RuleId`가 중복 |
| `MISSING_RULE_DEPENDENCY` | R002 또는 R003에 필요한 R001이 없음 |
| `UNSUPPORTED_RULE_CONFIGURATION` | typed 설정 구조·값이 현재 evaluator 지원 계약과 불일치 |
| `UNSUPPORTED_RULE_CAPABILITY` | mapping된 `RuleId`를 Registry에서 해결할 수 없음 |
| `INVALID_RULE_EXECUTION_PLAN` | 그 밖의 plan 구조·순서·불변식 위반 |

동일 FraudRule 복수 활성 버전과 단순 중복 `ruleCode`가 함께 성립하면 업무
의미가 더 구체적인 `MULTIPLE_EXECUTABLE_RULE_VERSIONS`를 사용한다.

Python 예외 클래스와 semantic category는 현재 구현되어 있다. HTTP 상태와
서비스 간 오류 응답 매핑은
[Rule v1 내부 분석 API](../03-api/rule-v1-analysis-api.md)를 따른다. 다음 항목은
이 문서에서 확정하지 않는다.

- Spring Boot 내부 예외와 실패 코드
- DetectionResult 생성·`FAILED` 전이 여부
- 거래 상태, 재시도와 수동 복구 정책

오류를 `LOW`, 빈 plan, 빈 raw result, 미적중 결과 또는 부분 성공으로
변환하지 않는다. 설정 오류가 난 Rule만 제외하고 나머지를 실행하지 않는다.

## 15. RuleExecutionPlanRunner 입력과 실행 전 검증

### 15.1 공개 실행 계약과 구현 상태

`RuleExecutionPlanRunner`의 계약상 공개 메서드는 다음 구조다.

```python
execute(
    plan: RuleExecutionPlan,
    rule_input: RuleEvaluationInput,
) -> tuple[PlannedRuleResult, ...]
```

Runner와 `PlannedRuleResult`는 이 계약에 따라 Python으로 구현되어 있다. 기존
`RuleExecutionPlanBuilder`, `RuleExecutionOrchestrator`, evaluator, Registry와
`RuleEvaluationInput`의 공개 계약은 변경하지 않는다. 실행 단위
`executionId`, 상태, 시작·종료 시각이나 실패 상세를 담는 별도 wrapper도
정의하지 않는다.

### 15.2 지원 입력과 cutoff 정합성

Runner는 Orchestrator를 호출하기 전에 다음 두 입력 타입을 확인한다.

- `plan`은 `RuleExecutionPlan`이어야 한다.
- `rule_input`은 `RuleEvaluationInput`이어야 한다.

두 타입 중 하나라도 다르면 `INVALID_PLAN_RUNNER_INPUT`으로 실패하고
Orchestrator를 호출하지 않는다. 지원 입력의 cutoff는 다음 Python 필드끼리
비교한다.

```python
plan.evaluation_cutoff_at == rule_input.transaction.occurred_at
```

업무 문서의 `evaluationCutoffAt`과 `transaction.occurredAt`은 각각 위
snake_case Python 필드에 대응한다. 두 값은 기존 Builder와 입력 모델 계약에
따라 timezone-aware UTC여야 하며 microsecond까지 정확한 `datetime` equality를
적용한다. 서버 현재 시각, 별도 Clock, 허용 오차, 초 단위 절삭과 timezone
변환을 사용하지 않는다. 값이 다르면 `EVALUATION_CUTOFF_MISMATCH`로
Orchestrator 호출 전에 실패한다.

현재 `RuleExecutionPlan`에는 `transactionId` 또는 `transaction_id`가 없다.
따라서 Runner는 cutoff 일치 외에 plan과 입력이 같은 transaction snapshot에서
생성되었는지까지 검증할 수 없다. 이 상관관계는 plan 생성·전달을 조합하는
상위 계층이 보장해야 한다.

### 15.3 실행 순서에 영향을 주는 plan 방어 검증

Runner는 공식 `RuleExecutionPlanBuilder`가 생성한 plan을 지원 입력으로
삼는다. 다만 공개 dataclass 생성자를 통해 구조가 손상된 plan이 전달될 수
있으므로 실행 순서에 직접 영향을 주는 다음 항목만 방어적으로 검증한다.

1. `plan.items`가 비어 있지 않은 tuple인지 확인한다.
2. 모든 원소가 `RuleExecutionPlanItem`인지 확인한다.
3. 각 index에서 `item.execution_order == index + 1`인지 확인한다.
4. 모든 `item.rule_id`가 중복되지 않는지 확인한다.
5. item의 물리적 RuleId 순서가 `R001 → R002 → R003 → R004`의 canonical
   subsequence인지 확인한다.

`R004` 단독 plan과 `R001 → R003 → R004` plan은 유효하다. 비연속 또는 중복
`execution_order`, 중복 RuleId와 canonical 역순은
`INVALID_RULE_EXECUTION_PLAN`으로 Orchestrator 호출 전에 거부한다. Runner는
손상된 plan을 정렬하거나 execution order를 다시 부여해 복구하지 않는다.
canonical subsequence 검증만으로 Builder의 전체 유효성이 다시 증명되는 것은
아니다. 예를 들어 공식 Builder가 dependency 오류로 거부할 수동 구성 plan을
Runner가 dependency까지 재검증하지 않으며, 그런 plan은 지원 입력이 아니다.

Runner는 다음 Builder 책임을 중복 검증하지 않는다.

- FraudRule lifecycle과 RuleVersion status
- effective period
- exact `ruleCode → RuleId` mapping
- R001·R002·R003 plan 구성 dependency
- `conditionDefinition` typed parsing과 evaluator 설정 호환성
- `reasonCode`와 weight validation
- `ruleSetVersion` canonical hash 재계산
- Registry capability 사전 조회

## 16. 실행 순서, Orchestrator 호출과 Registry 구성 전제

### 16.1 plan 물리 순서의 전달

Runner는 `execution_order`나 RuleId로 `plan.items`를 다시 정렬하지 않는다.
15절 검증을 통과한 item의 물리적 순서에서 다음 tuple을 그대로 생성한다.

```python
tuple(item.rule_id for item in plan.items)
```

Runner는 이 tuple과 같은 `rule_input`을 기존 Orchestrator 공개 메서드에
전달한다.

```python
execute(
    rule_ids: Sequence[str],
    rule_input: RuleEvaluationInput,
) -> tuple[RuleEvaluatorResult, ...]
```

Orchestrator는 전달받은 전체 RuleId를 Registry에서 먼저 resolve하고 모든
resolution이 성공한 뒤 입력 순서대로 evaluator를 순차 실행한다. 따라서
Registry 등록 순서는 실제 호출 순서와 결과 순서에 영향을 주지 않는다.

### 16.2 호출 횟수와 evaluator 중복 실행 방지

계약상 호출 횟수는 다음과 같다.

| 상황 | Runner의 Orchestrator 호출 | Runner의 Registry 직접 조회 | Runner의 evaluator 직접 실행 | retry·fallback |
| --- | ---: | ---: | ---: | ---: |
| 잘못된 입력·plan 또는 cutoff 불일치 | 0회 | 0회 | 0회 | 0회 |
| 유효한 실행 | 정확히 1회 | 0회 | 0회 | 0회 |

Runner는 Orchestrator를 우회하거나 evaluator를 개별 호출하지 않는다. 실행 전
RuleId 중복 검증, Orchestrator 정확히 1회 호출과 기존 Orchestrator의 단일
순차 loop를 함께 적용하므로 각 plan item의 evaluator는 최대 1회 실행된다.
R002와 R003 evaluator가 고액 조건을 자체 재평가하는 것은 R001 evaluator를
다시 호출하는 동작이 아니다.

### 16.3 Registry 구성 전제와 capability 실패

애플리케이션 composition은 Builder와 Orchestrator에 동일한 Rule v1
capability 구성을 제공해야 한다. 두 계층이 반드시 동일한
`RuleEvaluatorRegistry` Python 객체 인스턴스를 공유할 필요는 없으며 Runner는
Registry 객체 identity를 비교하지 않는다.

Runner는 Registry를 직접 조회하지 않는다. Builder가 plan을 만든 이후
Orchestrator의 capability 구성이 누락되거나 달라졌다면 Orchestrator가 전체
RuleId resolution 중 `UnsupportedRuleIdError`로 fail-fast해야 한다. 이때
Runner는 이미 Orchestrator를 1회 호출했지만 evaluator 호출 횟수는 0회다.

## 17. raw 결과 후조건과 PlannedRuleResult 결합

### 17.1 PlannedRuleResult 구조

계약상 per-rule 결합 결과는 metadata를 평탄화하지 않는 다음 불변 중첩
구조다.

```python
@dataclass(frozen=True, slots=True)
class PlannedRuleResult:
    plan_item: RuleExecutionPlanItem
    evaluation_result: RuleEvaluatorResult
```

`RuleEvaluatorResult`는 현재 Registry가 공개하고 Orchestrator 반환 annotation에
사용하는 union type alias다. union의 각 값은 `models.py`에 정의된 generic
`RuleEvaluationResult` 클래스의 인스턴스다. 이 문서는 기존 raw result 타입을
변경하지 않는다.

다음 업무 metadata는 `plan_item`을 통해 원래 타입과 값 그대로 보존한다.

- `ruleVersionId` / Python `rule_version_id`
- `ruleCode` / Python `rule_code`
- `ruleId` / Python `rule_id`
- `versionNumber` / Python `version_number`
- `reasonCode` / Python `reason_code`
- `weight`
- `conditionDefinition` / Python `condition_definition`
- `effectiveFrom` / Python `effective_from`
- `effectiveTo` / Python `effective_to`
- `executionOrder` / Python `execution_order`

`evaluationCutoffAt` / Python `evaluation_cutoff_at`과 `ruleSetVersion` / Python
`rule_set_version`은 plan 전체의 값으로만 유지한다. 각
`PlannedRuleResult`에 복제하지 않는다. Runner는 metadata를 수정하거나
평탄화한 중복 필드를 만들지 않는다.

### 17.2 raw 결과 후조건

Orchestrator 호출이 정상 반환한 뒤 Runner는 `PlannedRuleResult`를 만들기
전에 다음 후조건을 모두 검증한다.

1. raw 결과 collection이 tuple인지 확인한다.
2. 모든 원소가 공식 raw evaluator 결과 클래스인
   `RuleEvaluationResult`인지 확인한다.
3. `len(plan.items) == len(raw_results)`인지 확인한다.
4. 모든 index에서
   `plan.items[i].rule_id == raw_results[i].rule_id`인지 확인한다.
5. 결과가 plan과 같은 순서를 유지하는지 확인한다.

결과 부족과 초과는 모두 `RULE_EXECUTION_RESULT_COUNT_MISMATCH`다. collection
또는 원소 타입이 잘못됐거나 그 밖의 raw 결과 구조가 손상되면
`INVALID_RULE_EXECUTION_RESULT`다. 같은 index의 RuleId가 다르면
`RULE_EVALUATOR_RESULT_MISMATCH`다. 잘못된 결과를 정상 미적중
`RuleEvaluationResult(matched=False, facts=None)`로 바꾸지 않는다.

현재 Orchestrator는 요청한 RuleId마다 결과 하나를 수집하고 반환 직후
`rule_id`를 검증하므로 정상 구현에서는 개수와 RuleId 후조건을 만족한다.
Runner의 검증은 대체 구현, 잘못된 test double 또는 런타임 계약 손상에 대한
최종 방어선이며 Orchestrator 공개 계약을 대체하지 않는다.

### 17.3 strict index 결합

허용되는 결합 관계는 다음 하나뿐이다.

```text
plan.items[i] ↔ raw_results[i]
```

모든 후조건 검증이 성공한 뒤에만 다음 의미로 완전한 tuple을 생성한다.

```python
tuple(
    PlannedRuleResult(
        plan_item=plan_item,
        evaluation_result=raw_result,
    )
    for plan_item, raw_result in zip(
        plan.items,
        raw_results,
        strict=True,
    )
)
```

RuleId dictionary 변환, RuleId 기준 재정렬, completion order 사용, 누락 결과
보충, 초과 결과 무시와 검증 전 부분 `PlannedRuleResult` 생성을 금지한다.
evaluator가 모두 미적중이어도 각 plan item에는 대응하는 raw
`RuleEvaluationResult`가 있으므로 같은 개수의 `PlannedRuleResult`를 유지한다.

## 18. Runner 오류 범주와 fail-fast 정책

### 18.1 semantic 오류 범주

다음 오류 범주는 구현된 Runner의 semantic 계약이다. FastAPI HTTP 상태와
서비스 간 오류 응답 매핑은
[Rule v1 내부 분석 API](../03-api/rule-v1-analysis-api.md)를 따른다.

| 오류 범주 | 발생 조건 | Orchestrator 호출 시점 | 부분 결과 | retry·fallback |
| --- | --- | --- | --- | --- |
| `INVALID_PLAN_RUNNER_INPUT` | `plan` 또는 `rule_input`이 지원 타입이 아님 | 호출 전 실패 | 반환 금지 | 금지 |
| `EVALUATION_CUTOFF_MISMATCH` | plan cutoff와 거래 `occurred_at`이 정확히 다름 | 호출 전 실패 | 반환 금지 | 금지 |
| `INVALID_RULE_EXECUTION_PLAN` | 빈/non-tuple items, 잘못된 item 타입, 비연속·중복 order, 중복 RuleId 또는 non-canonical 순서 | 호출 전 실패 | 반환 금지 | 금지 |
| `UNSUPPORTED_RULE_CAPABILITY` | Orchestrator의 Registry가 plan의 RuleId를 resolve할 수 없음 | Runner가 Orchestrator를 호출한 뒤, evaluator 실행 전 실패 | 반환 금지 | 금지 |
| `RULE_EVALUATOR_EXECUTION_FAILED` | evaluator가 실행 중 예외를 발생시킴 | Orchestrator 호출 후 실패 | 반환 금지 | 금지 |
| `RULE_EVALUATOR_RESULT_MISMATCH` | evaluator 또는 반환 tuple의 `rule_id`가 같은 index의 계획 RuleId와 다름 | Orchestrator 호출 후 실패 | 반환 금지 | 금지 |
| `RULE_EXECUTION_RESULT_COUNT_MISMATCH` | raw result가 plan item보다 부족하거나 초과함 | Orchestrator 반환 후 실패 | 반환 금지 | 금지 |
| `INVALID_RULE_EXECUTION_RESULT` | raw collection이 tuple이 아니거나 원소가 공식 `RuleEvaluationResult`가 아닌 등 결과 구조가 손상됨 | Orchestrator 반환 후 실패 | 반환 금지 | 금지 |

같은 입력에 여러 문제가 있더라도 Runner는 현재 단계에서 확인한 첫 오류에
fail-fast할 수 있으며 오류를 모두 수집할 의무는 없다. 어떤 오류도 빈 tuple,
`LOW`, 미적중 또는 planned 부분 성공으로 변환하지 않는다.

### 18.2 기존 내부 오류와의 관계

Registry의 `UnsupportedRuleIdError`, Orchestrator의
`InvalidRuleExecutionPlanError`, `RuleEvaluatorResultMismatchError`와 evaluator
원래 예외를 정상 결과로 변환하지 않는다. 현재 Runner 구현은 원인을
보존하면서 내부 오류를 위 semantic category로 한 번 해석할 수 있다. 동일한
오류를 Registry, Orchestrator와 Runner에서 반복 wrapping하거나 원래 원인을
잃어서는 안 된다.

`UnsupportedRuleIdError`는 Runner가 Registry를 직접 조회해서 만드는 오류가
아니다. Orchestrator가 모든 capability를 evaluator 실행 전에 resolve하는
과정에서 발생하며 Runner 관점의 semantic category는
`UNSUPPORTED_RULE_CAPABILITY`다.

### 18.3 evaluator 실패와 부분 결과 금지

evaluator 실행 중 예외가 발생하면 기존 Orchestrator 정책에 따라 이후
evaluator 실행을 즉시 중단한다. 실패 evaluator보다 앞선 evaluator가 이미
실행되어 raw result가 메모리에 계산됐을 수 있지만 Orchestrator는 부분 tuple을
반환하지 않고 Runner 호출 전체가 실패한다.

Runner는 앞서 계산된 raw result를 `PlannedRuleResult` 부분 성공으로 반환하지
않고 빈 성공 tuple도 반환하지 않는다. evaluator 예외를 미적중으로 변환하지
않으며 자동 retry와 fallback을 수행하지 않는다. Orchestrator가 반환한 뒤
결과 후조건이 실패한 경우에도 `PlannedRuleResult`를 하나도 외부로 반환하지
않는다.

## 19. Runner 정상·오류 흐름 예시

### 19.1 R001·R003·R004 정상 실행

```text
Builder가 canonical plan.items = (R001, R003, R004) 생성
→ Runner가 plan·input 타입과 cutoff equality 검증
→ executionOrder 1, 2, 3과 canonical subsequence 검증
→ Orchestrator.execute((R001, R003, R004), rule_input) 정확히 1회
→ Orchestrator가 세 evaluator를 plan 순서대로 각각 최대 1회 실행
→ ordered raw result tuple 반환
→ Runner가 타입·개수·index RuleId 후조건 검증
→ ordered PlannedRuleResult tuple 반환
```

Registry 등록 순서가 `R004, R003, R001`이어도 Runner가 전달한
`R001, R003, R004` 순서가 실행과 결과 순서를 지배한다. behavior event 등
입력 snapshot 내부 순서도 evaluator 선택 순서를 바꾸지 않는다.

### 19.2 R004 단독 정상 실행

```text
plan.items = (R004 executionOrder=1,)
→ canonical subsequence 검증 성공
→ Orchestrator.execute((R004,), rule_input) 정확히 1회
→ R004 evaluator 최대 1회 실행
→ PlannedRuleResult 1개인 tuple 반환
```

### 19.3 전체 evaluator 미적중과 metadata 보존

모든 evaluator가 `matched=False`, `facts=None`을 반환해도 실행 성공이면 각
plan item별 `PlannedRuleResult`를 유지한다. R001·R003·R004 plan이면 결과도
같은 순서의 3개 tuple이다. 각 결과의 `plan_item`은 `weight`, `reason_code`,
RuleVersion ID·코드·버전, typed `condition_definition`, effective period와
`execution_order`를 그대로 보존한다.

Runner는 적중 여부와 관계없이 weight를 적용하거나 점수를 계산하지 않는다.
Reason Code도 Evidence로 변환하지 않는다.

### 19.4 실행 전 오류 예시

| 사례 | 오류 범주 | Orchestrator 호출 | 결과 |
| --- | --- | ---: | --- |
| `plan`이 `RuleExecutionPlan`이 아님 | `INVALID_PLAN_RUNNER_INPUT` | 0회 | 실패 |
| `rule_input`이 `RuleEvaluationInput`이 아님 | `INVALID_PLAN_RUNNER_INPUT` | 0회 | 실패 |
| plan cutoff와 거래 `occurred_at`의 microsecond가 다름 | `EVALUATION_CUTOFF_MISMATCH` | 0회 | 실패 |
| `plan.items == ()` | `INVALID_RULE_EXECUTION_PLAN` | 0회 | 실패 |
| `plan.items`가 list 등 tuple이 아님 | `INVALID_RULE_EXECUTION_PLAN` | 0회 | 실패 |
| item 중 하나가 `RuleExecutionPlanItem`이 아님 | `INVALID_RULE_EXECUTION_PLAN` | 0회 | 실패 |
| executionOrder가 `1, 3`처럼 비연속 | `INVALID_RULE_EXECUTION_PLAN` | 0회 | 실패 |
| executionOrder가 `1, 1`처럼 중복 | `INVALID_RULE_EXECUTION_PLAN` | 0회 | 실패 |
| RuleId가 `R001, R001`처럼 중복 | `INVALID_RULE_EXECUTION_PLAN` | 0회 | 실패 |
| RuleId가 `R004, R001`처럼 canonical 역순 | `INVALID_RULE_EXECUTION_PLAN` | 0회 | 실패 |

Runner는 위 plan을 정렬·보충·정규화해 실행하지 않는다.

### 19.5 Orchestrator·evaluator·결과 오류 예시

| 사례 | 오류 범주 | Orchestrator 호출 | evaluator 실행 | planned 결과 |
| --- | --- | ---: | --- | --- |
| Orchestrator Registry에 후순위 RuleId capability 누락 | `UNSUPPORTED_RULE_CAPABILITY` | 1회 | 0회 | 없음 |
| 두 번째 evaluator가 예외 발생 | `RULE_EVALUATOR_EXECUTION_FAILED` | 1회 | 앞 evaluator와 실패 evaluator는 실행됐을 수 있음 | 없음 |
| raw collection이 list임 | `INVALID_RULE_EXECUTION_RESULT` | 1회 | 완료됐을 수 있음 | 없음 |
| raw 원소가 `RuleEvaluationResult`가 아님 | `INVALID_RULE_EXECUTION_RESULT` | 1회 | 완료됐을 수 있음 | 없음 |
| 반환 `rule_id`가 같은 index의 계획 RuleId와 다름 | `RULE_EVALUATOR_RESULT_MISMATCH` | 1회 | 불일치 지점까지 실행됐을 수 있음 | 없음 |
| plan item 3개에 raw result 2개 | `RULE_EXECUTION_RESULT_COUNT_MISMATCH` | 1회 | 완료됐을 수 있음 | 없음 |
| plan item 3개에 raw result 4개 | `RULE_EXECUTION_RESULT_COUNT_MISMATCH` | 1회 | 완료됐을 수 있음 | 없음 |

현재 공식 Orchestrator는 결과 타입을 제외한 RuleId·개수 불변식을 자체적으로
보장한다. 위 raw collection·개수 사례는 계약을 위반한 대체 구현이나 test
double까지 Runner가 방어해야 한다는 의미다. 어떤 실패에서도 부분
`PlannedRuleResult`를 반환하지 않는다.

## 20. 후속 계층 경계와 제외 범위

### 20.1 scoring·Evidence·DetectionResult 경계

Runner는 raw result의 `matched`와 `facts`를 plan metadata에 결정적으로 결합할
뿐이다. 정상 결합된 후속 계층은 plan의 `evaluationCutoffAt`과
`ruleSetVersion`, plan item metadata와 raw result를 사용할 수 있다.

Runner는 다음 작업을 수행하지 않는다.

- weight 적용과 그룹 상한 계산
- risk score와 risk level 산정
- Reason Code의 Evidence 변환
- `observationSummary` 생성
- DetectionResult 생성·완전성 검증·저장·채택

`RuleScoringCalculator`는 `scoring-policy-v1` 정책에 따라 Runner의 정상 결과를
점수로 계산하는 구현된 공개 scoring 소유 타입이다. 공개 계산 진입점은 다음
`RuleScoringCalculator.calculate(...)` 하나다.

```python
RuleScoringCalculator.calculate(
    plan: RuleExecutionPlan,
    planned_results: tuple[PlannedRuleResult, ...],
) -> RuleScoringResult
```

공식 계산식, `RuleScoringResult`, `RuleScoreContribution`,
`RuleScoreGroupSummary`, `ScoringGroupId`, `RiskLevel`의 필드와
`scoring-policy-v1` 의미는 [Rule v1 탐지 계약 7절](./rule-v1-detection-contract.md#7-점수-합산과-시나리오군-상한)을
따른다. scoring 계층은 정상 Runner 결과의 `matched`와 같은 index plan item의
`weight`만 contribution 계산에 사용하며 Rule 조건과 facts를 다시 평가하지
않는다. `scoring-policy-v1`은 R001·R002·R003·R004의 canonical group·weight
binding 15·20·40·10을 소유하고 plan item이 이 binding과 일치하는지 검증한
뒤 실제 contribution에 검증된 `plan_item.weight`를 사용한다.

`evaluationCutoffAt`과 `ruleSetVersion`은 plan 전체의 metadata로만 유지하고
`RuleScoringResult`에 복제하지 않는다. scoring 결과에는 적용한
`scoringPolicyVersion = scoring-policy-v1`을 포함한다. 후속 조합 계층은 plan과
scoring 결과를 함께 전달해야 하며 scoring이 plan metadata를 평탄화한 별도
사본을 만들지 않는다.

scoring은 계산 전에 최소한 다음 정합성을 fail-fast로 검증한다.

1. `plan`이 `RuleExecutionPlan`인지 검증한다.
2. `planned_results`가 비어 있지 않은 tuple인지 검증한다.
3. 모든 원소가 `PlannedRuleResult`인지 검증한다.
4. `len(plan.items) == len(planned_results)`인지 검증한다.
5. 모든 index에서 `planned_results[i].plan_item == plan.items[i]`인지 검증한다.
6. 각 index의 `execution_order == i + 1`이고 plan item과 planned result에서
   RuleId와 execution order가 유지되는지 검증한다.
7. raw evaluation result의 RuleId가 같은 index plan item의 RuleId와 같은지
   검증한다.
8. 모든 RuleId가 `scoring-policy-v1`의 R001~R004 그룹 mapping에 포함되는지
   검증한다.
9. 각 weight가 bool이 아닌 정수이고 기존 plan 계약의 1~100 범위이며
   R001·R002·R003·R004의 canonical weight 15·20·40·10과 같은지 검증한다.
10. 선택된 scoring policy의 버전·그룹 mapping·그룹 상한·최종 상한·등급
    경계가 `scoring-policy-v1`과 정확히 같은지 검증한다.

구현 독립적인 semantic category는 `INVALID_SCORING_INPUT`,
`SCORING_PLAN_RESULT_MISMATCH`, `UNSUPPORTED_SCORING_RULE`,
`INVALID_RULE_WEIGHT`, `INVALID_SCORING_POLICY`의 최소 집합을 사용한다. 구체적인
Python 예외 클래스와 HTTP 상태는 확정하지 않는다.

`INVALID_SCORING_POLICY`는 `RuleScoringCalculator`에 적용되는 scoring policy
구성 또는 policy binding이 유효하지 않은 경우를 뜻한다.

scoring 계층은 결과를 RuleId 기준으로 재정렬하거나 누락 결과를 보충하지
않고 plan과 같은 index에서만 결합한다. 잘못된 입력과 mismatch를 0점,
`LOW`, 부분 점수, 빈 성공 결과로 바꾸지 않으며 retry와 fallback을 수행하지
않는다.

구현된 Evidence 변환 계층의 공개 handoff는 다음 세 입력으로 확정한다.

```python
RuleEvidenceTransformer.transform(
    plan: RuleExecutionPlan,
    planned_results: tuple[PlannedRuleResult, ...],
    scoring_result: RuleScoringResult,
) -> RuleAnalysisResult
```

`evaluationCutoffAt`과 `ruleSetVersion`은 plan에서, RuleVersion metadata와
`executionOrder`는 같은 index plan item에서, `matched`와 typed facts는
planned result에서, contribution·그룹 summary·점수·등급·정책 버전은 scoring
result에서 가져온다. `RuleAnalysisResult`는 plan의 두 global metadata,
중첩 `RuleScoringResult`와 ordered immutable Evidence tuple을 소유하고 scoring
필드를 다시 평탄화하지 않는다.

Transformer는 공식 Builder → Runner → Calculator 경로의 index·identity·순서,
matched·contribution과 metadata 정합성을 방어적으로 검증하되 evaluator Rule
조건이나 행동 이벤트 선택을 다시 실행하지 않는다. scoring policy 상수와
계산 알고리즘도 Evidence 모듈에 복제하지 않고 기존 calculator 또는 scoring
모듈의 단일 검증 경로를 재사용해야 한다. Rule별 Evidence와 observation의
정확한 필드·시각·순서·semantic 오류 계약은
[Rule v1 탐지 계약 6절](./rule-v1-detection-contract.md#6-reason-code와-evidence)을
따른다. Transformer와 해당 공개 결과·오류 타입은 현재 Python으로 구현되어
있다.

### 20.2 Spring Boot·DB·API 경계

Runner는 다음 작업을 수행하지 않는다.

- 거래 상태와 사건 상태 변경
- Spring Boot 업무 DB와 PostgreSQL 직접 조회
- 실제 활성 RuleVersion 선택과 snapshot 생성
- RuleVersion 재조회 또는 plan 교체
- FastAPI endpoint와 서비스 간 API DTO 정의
- Spring Boot 통신과 DetectionResult 영속화

Spring Boot는 거래·RuleVersion·DetectionResult 업무 정합성의 최종 소유자다.
서비스 간 분석 요청·응답과 API 오류 매핑은
[Rule v1 내부 분석 API](../03-api/rule-v1-analysis-api.md)에 정의되어 있으며
Endpoint·Spring Boot Client 구현, 거래 실패 상태와 복구 정책은 후속 범위다.

### 20.3 현재 제외 범위

Builder·Runner·Scoring Calculator와 Evidence Transformer는 현재 구현되어
있다. 다음 항목은 아직 구현되지 않은 후속 범위다.

- 실행 단위 wrapper, `executionId`, 상태와 실행 시각
- Python·Java Service, Repository와 DB Migration 구현
- `execution_order` DB 컬럼
- DetectionResult 생성·검증·저장·채택
- 문서로 정의된 FastAPI Endpoint·Pydantic DTO·HTTP 오류 처리와 Spring Boot
  실제 연동
- retry와 fallback
- 로그와 메트릭
- Redis, Kafka, ML과 LLM
- 전체 시스템 아키텍처와 ERD 정합화

## 21. 구현 검증 조건

### 21.1 현재 Builder·Orchestrator 구현

현재 자동화 테스트는 Builder의 canonical order·hash·불변성·설정 검증과
Orchestrator의 입력 순서 보존, 전체 capability 사전 resolution, 순차 실행,
반환 RuleId 검증과 fail-fast를 검증한다. 다음 Builder 조건도 계속 유지해야
한다.

- 전달받은 timezone-aware UTC `evaluation_cutoff_at`을 plan에 그대로 보존
- 적용 기간 시작 포함·종료 제외
- 활성 0개와 동일 Rule 복수 활성 버전 거부
- exact mapping과 정규화·alias 금지
- DB·Registry 입력 순서와 무관한 canonical order
- 일부 Rule filtering 후 1부터 연속 `executionOrder` 부여
- R002·R003의 R001 dependency와 R004 독립 실행
- Rule별 typed `conditionDefinition` 구조·타입·지원값 검증
- 같은 ordered snapshot의 `ruleSetVersion` 결정성
- plan 확정 뒤 원본 JSON·collection 변경 격리
- Builder의 evaluator 호출 0회

### 21.2 현재 Runner 구현

현재 Runner 자동화 테스트는 최소한 다음 조건을 검증한다.

- 지원 plan·input 타입과 잘못된 타입
- cutoff의 UTC·microsecond exact equality와 불일치 시 Orchestrator 0회
- non-empty tuple items와 item 타입
- `execution_order == index + 1`, 중복 order와 비연속 order 거부
- 중복 RuleId와 non-canonical 순서 거부
- R004 단독과 R001·R003·R004 canonical subsequence 허용
- plan item의 물리 순서를 재정렬하지 않고 Orchestrator에 전달
- 유효 실행에서 Orchestrator 정확히 1회, Runner의 Registry·evaluator 직접 호출 0회
- Registry 등록 순서와 무관한 evaluator 실행 순서
- 각 evaluator 최대 1회 실행과 retry·fallback 0회
- raw collection tuple과 `RuleEvaluationResult` 원소 타입 검증
- plan item과 raw result의 개수·index RuleId 일치
- raw 결과 부족·초과·손상과 RuleId 불일치 거부
- 모든 raw 후조건 성공 후에만 strict index 결합
- 전체 미적중에도 plan item별 `PlannedRuleResult` 보존
- evaluator·결과 오류 시 planned 부분 결과와 빈 성공 tuple 미반환
- plan metadata 보존과 weight·Reason Code 미사용

Runner와 위 테스트는 구현되어 있다. Evidence 변환도 별도 계층으로 구현되어
있지만, 이 사실만으로 DetectionResult 생성 또는 실제 서비스 연동이
구현되었다는 뜻은 아니다.

### 21.3 현재 scoring 구현

현재 scoring 구현과 자동화된 테스트는 다음 점수·등급 사례를 검증한다.

- 전체 미적중: 0, `LOW`
- R004만 적중: 10, `LOW`
- R001만 적중: 15, `LOW`
- R001 + R002: 35, `MEDIUM`
- R001 + R003: 55, `HIGH`
- R001 + R004: 25, `MEDIUM`
- R001~R004 전체 적중: 원래 합계 85, 적용 점수 75, `HIGH`
- security 원래 점수 70에 cap 60 적용, reduction 10
- 그룹 상한 뒤에도 개별 contribution 15·20·40·10 유지
- security reduction 10을 특정 Rule에 배분하지 않음

다음 정합성·순수성 조건도 함께 검증해야 한다.

- contribution은 plan 순서, group summary는 `amount → security` 순서
- 입력 collection과 중첩 원소 불변성
- 잘못된 plan 타입과 non-tuple·빈 result 거부
- 잘못된 `PlannedRuleResult` 원소 타입 거부
- plan/result 개수와 같은 index plan item 불일치 fail-fast
- RuleId·execution order mismatch를 재정렬·보충 없이 fail-fast
- 지원하지 않는 RuleId와 bool·범위 밖·canonical binding 불일치 weight 거부
- `scoring-policy-v1`과 다른 버전 또는 정의 거부
- 오류 시 0점·`LOW`·부분 결과·retry·fallback 미사용
- DB·네트워크·현재 시각·mutable 전역 상태 미사용

현재 `RuleScoringCalculator`, 결과 타입, 오류 범주와 해당 테스트는 구현되어
있다. `RuleEvidenceTransformer`와 Evidence 결과·오류 타입도 구현되어 있다.
FastAPI Endpoint·Pydantic DTO와 Spring Boot 연동은 아직 구현되지 않았다.
