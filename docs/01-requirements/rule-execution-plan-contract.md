# RuleVersion 기반 Rule 실행 계획 내부 계약

## 1. 문서 목적과 구현 상태

이 문서는 [GitHub Issue #108](https://github.com/Ahnjisan/FinGuardOps/issues/108)에
따라 Spring Boot가 고정한 활성 RuleVersion 업무 snapshot을 FastAPI 내부의
결정적인 ordered `RuleExecutionPlan`으로 변환하는 계약을 정의한다.

이 문서는 다음 연결 경계를 다룬다.

```text
Spring Boot의 활성 RuleVersion 업무 snapshot
→ FastAPI 실행 계획 생성 계층의 mapping·dependency·설정·capability 검증
→ 불변 ordered RuleExecutionPlan
→ 기존 RuleExecutionOrchestrator
→ ordered raw RuleEvaluationResult tuple
```

현재 구현 상태는 다음과 같다.

- FraudRule·RuleVersion JPA·PostgreSQL 물리 모델과 lifecycle: 구현됨
- R001~R004 순수 evaluator: 구현됨
- 불변 `RuleEvaluatorRegistry`: 구현됨
- `RuleExecutionOrchestrator`: 구현됨
- 이 문서의 RuleVersion 기반 실행 계획 계약: 문서 정의 완료
- 활성 RuleVersion 전체 조회·업무 snapshot 생성: 미구현
- `RuleExecutionPlan`과 `RuleExecutionPlanItem` Python 클래스: 미구현
- RuleVersion 설정 전달과 typed evaluator settings: 미구현
- Spring Boot·FastAPI 실제 연동: 미구현
- 점수·위험 등급·Evidence·DetectionResult 처리: 미구현 후속 범위

계약 문서가 존재한다는 사실은 실행 계획 생성이나 서비스 연동이 구현되었다는
뜻이 아니다.

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
아니다. 서비스 간 실제 DTO와 통신 방식은 이 문서에서 정의하지 않지만,
개념적으로 다음 검증에 필요한 업무 값을 포함해야 한다.

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
`RuleEvaluationResult`다. `PlannedRuleResult`는 하나의 plan item과 같은
index의 raw result를 묶는 후속 구현용 개념 계약이다. 이 문서는 해당 Python
클래스를 구현하지 않는다.

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

### 3.2 FastAPI 실행 계획 생성 계층

FastAPI의 실행 계획 생성 계층은 기존 Orchestrator보다 앞에서 다음 책임을
가진다.

- 전달받은 업무 snapshot 구조와 선택 조건 재검증
- exact `ruleCode → RuleId` mapping
- Rule dependency 검증
- RuleVersion `conditionDefinition` typed parsing과 현재 evaluator 설정
  호환성 검증
- Rule v1 canonical order 적용과 `executionOrder` 부여
- `ruleSetVersion` 생성
- 모든 `RuleId`의 Registry capability 사전 검증
- 불변 `RuleExecutionPlan` 생성
- 기존 `RuleExecutionOrchestrator.execute()` 호출
- plan item과 ordered raw result의 strict index 결합

FastAPI는 Spring Boot의 업무 DB를 직접 조회하지 않고 RuleVersion lifecycle,
status, 기간과 weight를 임의로 변경하지 않는다.

### 3.3 기존 RuleExecutionOrchestrator

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

`reasonCode`와 `weight`는 후속 Evidence·scoring 연결을 위한 snapshot이다.
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
13. 위 검증이 모두 성공한 뒤 기존 `RuleExecutionOrchestrator.execute()`를
    호출한다.

앞 단계 실패는 뒤 단계보다 우선한다. 실행 계획 생성 계층은 오류를 모두
수집하기 위해 validation을 계속 진행하지 않아도 된다. 다만 어느 단계에서
실패하더라도 evaluator 호출 횟수는 0회여야 한다.

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

이 문서는 다음을 확정하지 않는다.

- Python 예외 클래스명
- HTTP 상태와 외부 오류 응답 코드
- Spring Boot 내부 예외와 실패 코드
- DetectionResult 생성·`FAILED` 전이 여부
- 거래 상태, 재시도와 수동 복구 정책

오류를 `LOW`, 빈 plan, 빈 raw result, 미적중 결과 또는 부분 성공으로
변환하지 않는다. 설정 오류가 난 Rule만 제외하고 나머지를 실행하지 않는다.

## 15. Orchestrator 호출과 evaluator 0회 보장

plan 생성과 Registry capability 사전 검증이 모두 성공한 뒤 기존
Orchestrator에는 다음 ordered Rule ID tuple만 전달한다.

```python
tuple(item.ruleId for item in plan.items)
```

기존 공개 계약은 변경하지 않는다.

```python
execute(
    rule_ids: Sequence[str],
    rule_input: RuleEvaluationInput,
) -> tuple[RuleEvaluatorResult, ...]
```

실행 계획 생성 계층의 사전 Registry 검증은 현재 Registry가 immutable이라는
계약을 전제로 한다. Orchestrator도 전달받은 전체 ID를 다시 resolve한 뒤
첫 evaluator를 실행한다.

따라서 다음 오류에서는 evaluator 호출 횟수가 0회다.

- snapshot·기간·중복·mapping·dependency 오류
- 설정 구조와 호환성 오류
- Registry 미지원 capability
- Orchestrator의 빈 plan·중복 ID·미지원 ID validation 오류

evaluator 실행 중 예외가 발생한 경우에는 기존 Orchestrator 계약대로 이전
evaluator가 이미 실행되었을 수 있다. 이 경우에도 부분 raw result를 정상
반환하지 않고 이후 evaluator 실행을 중단한다.

## 16. plan item과 raw result의 strict 결합

Orchestrator가 정상 완료하면 plan과 raw result는 index 기반으로만 결합한다.

```text
plan.items[i] ↔ rawResults[i]
```

필수 불변식은 다음과 같다.

- `len(plan.items) == len(rawResults)`
- 모든 index에서 `plan.items[i].ruleId == rawResults[i].rule_id`
- 누락되거나 초과한 result가 없음
- `RuleId`를 기준으로 raw result를 다시 정렬하지 않음
- dictionary에 넣고 RuleId로 다시 조회해 결합하지 않음
- 모든 불변식 검증이 성공한 뒤에만 불변
  `PlannedRuleResult(planItem, rawResult)` 개념 pair로 결합
- 하나라도 위반하면 정상 planned result나 부분 pair를 반환하지 않음

`PlannedRuleResult`는 후속 scoring·Evidence 연결을 위한 개념이며 이번
Issue에서 Python 클래스로 구현하지 않는다. 기존 Orchestrator의 반환 타입도
변경하지 않는다.

## 17. 정상·실패 흐름 예시

### 17.1 R001·R003·R004 정상 실행

```text
evaluationCutoffAt 고정
→ R001·R003·R004 실행 가능 snapshot
→ exact mapping 성공
→ R003 dependency인 R001 존재 확인
→ 설정 호환성 성공
→ canonical order R001, R003, R004
→ executionOrder 1, 2, 3
→ Registry capability 사전 검증 성공
→ Orchestrator.execute(("R001", "R003", "R004"), ruleInput)
→ 같은 index의 plan item과 raw result 결합
```

### 17.2 R004만 활성인 정상 실행

```text
R004 실행 가능 snapshot
→ R004는 독립 Rule이므로 dependency 성공
→ executionOrder 1
→ Orchestrator.execute(("R004",), ruleInput)
```

### 17.3 활성 RuleVersion 0개

```text
실행 가능 snapshot이 비어 있음
→ NO_EXECUTABLE_RULE_VERSION
→ evaluator 호출 0회
→ 빈 성공·0점·LOW 생성 금지
```

### 17.4 동일 Rule 복수 활성 버전

```text
같은 fraudRuleId에 cutoff 기준 실행 가능 RuleVersion 2건
→ MULTIPLE_EXECUTABLE_RULE_VERSIONS
→ 최신 version·첫 행 선택 금지
→ evaluator 호출 0회
```

### 17.5 알 수 없는 ruleCode

```text
ruleCode = UNKNOWN_RULE
→ exact bridge 실패
→ UNKNOWN_RULE_CODE
→ trim·uppercase·alias fallback 금지
→ evaluator 호출 0회
```

### 17.6 R001 없이 R002 활성

```text
활성 Rule = R002
→ exact mapping 성공
→ R001 dependency 누락
→ MISSING_RULE_DEPENDENCY
→ evaluator 호출 0회
```

### 17.7 지원하지 않는 amountThreshold 또는 windowSeconds

```text
R001 amountThreshold = "20000000"
또는 R002 windowSeconds = 3600
→ typed parsing은 가능
→ 현재 evaluator 지원값과 불일치
→ UNSUPPORTED_RULE_CONFIGURATION
→ evaluator 호출 0회
```

### 17.8 Registry에서 지원하지 않는 RuleId

```text
공식 bridge와 dependency·설정 검증 성공
→ Registry에서 mapping된 RuleId capability 해결 실패
→ UNSUPPORTED_RULE_CAPABILITY
→ Orchestrator 호출 전 evaluator 호출 0회
```

### 17.9 plan과 raw result 불일치

```text
plan item 3개, raw result 2개
또는 같은 index의 ruleId와 returned rule_id 불일치
→ strict 결합 실패
→ 정상 PlannedRuleResult·부분 pair 반환 금지
```

Orchestrator의 반환 `rule_id` 불일치는 기존 구현이 evaluator 반환 직후
계약 위반으로 거부한다.

### 17.10 snapshot 이후 원본 설정 변경

```text
Spring Boot가 RuleVersion 값을 deep copy한 snapshot 생성
→ FastAPI가 typed 불변 plan 확정
→ 원본 Entity·JsonNode·입력 collection 변경
→ 현재 plan과 ruleSetVersion은 변경되지 않음
→ 현재 실행은 고정 plan만 사용
```

새 평가 실행은 자신의 `evaluationCutoffAt`과 새 snapshot을 사용한다.

## 18. 후속 scoring 계약으로 전달할 경계

정상 결합된 후속 계층은 다음 값을 사용할 수 있다.

- plan의 `evaluationCutoffAt`과 `ruleSetVersion`
- plan item의 RuleVersion 업무 ID·코드·버전·Reason Code·weight·순서
- raw result의 `rule_id`, 적중 여부와 facts

후속 scoring 계약은 적중 Rule의 weight 적용, 그룹 상한, 점수 합산과
`scoringPolicyVersion`을 정의해야 한다. Evidence 계약은 `reasonCode`,
`conditionDefinition`과 facts를 사용해 typed Evidence를 구성하고 Spring
Boot 저장 전 검증 경계를 정의해야 한다.

이 문서는 weight를 보존하지만 적용하지 않으며 점수·위험 등급·Evidence와
DetectionResult를 생성하지 않는다.

## 19. 제외 범위

다음 항목은 이 계약 문서와 Issue #108의 구현 범위에 포함하지 않는다.

- Python·Java 구현
- DB Migration과 `execution_order` 컬럼
- Repository·Service 구현
- 서비스 간 API DTO, FastAPI endpoint와 통신 방식
- evaluator signature와 `RuleEvaluationInput` 변경
- typed evaluator settings 구현과 주입
- `RuleExecutionPlan`·`RuleExecutionPlanItem` Python 클래스
- `PlannedRuleResult` Python 클래스
- weight 적용, 점수 합산과 그룹별 상한
- 위험 등급
- Evidence와 Reason Code 변환
- DetectionResult 생성·저장·채택
- 오류의 HTTP·Python·Spring 표현
- 재시도와 fallback
- Spring Boot·FastAPI 실제 연동
- Redis, Kafka, ML과 LLM
- 전체 시스템 아키텍처와 ERD 문서 정합화

## 20. 후속 구현 검증 조건

후속 구현은 최소한 다음 조건을 자동화된 테스트로 검증해야 한다.

- `transaction.occurredAt`만 활성 선택 cutoff로 사용
- 적용 기간 시작 포함·종료 제외
- 활성 0개와 동일 Rule 복수 활성 버전 거부
- exact mapping과 정규화·alias 금지
- DB 반환 순서와 무관한 canonical order
- 일부 Rule filtering 후 1부터 연속 `executionOrder` 부여
- R002·R003의 R001 dependency와 R004 독립 실행
- Rule별 typed `conditionDefinition` 필드·타입·정확한 지원값 검증
- JSON key 순서와 공백이 의미 비교에 영향 없음
- 공통 고액 기준·시간창 불일치 거부
- 같은 ordered snapshot의 `ruleSetVersion` 결정성
- DB·Registry·JSON 객체 순서가 hash에 영향 없음
- plan 확정 뒤 원본 Entity·JSON·collection 변경 격리
- 모든 구성·capability 오류에서 evaluator 호출 0회
- 기존 Orchestrator 공개 계약 유지
- plan item과 raw result의 strict index 결합
- 개수·RuleId 불일치 시 부분 planned result 금지
