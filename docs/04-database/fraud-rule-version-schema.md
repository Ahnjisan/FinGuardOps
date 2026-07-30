# FraudRule·RuleVersion PostgreSQL 물리 DB 계약

## 1. 목적과 범위

이 문서는 Flyway
`V5__create_fraud_rule_and_rule_version_tables.sql`로 구현한 논리 Rule,
불변 실행 버전, 적용 기간과 DetectionEvidence 연결의 물리 계약을
정의한다.

포함 범위는 다음과 같다.

- `fraud_rule`, `rule_version` 테이블
- Rule v1 R001~R004 DRAFT seed
- PUBLISHED 기간 중복 방지
- 게시 후 불변성과 물리 삭제 방지
- `detection_evidence.rule_version_id`와 snapshot 일치
- JPA Entity, Repository와 내부 lifecycle service

Rule 실행 엔진, 거래별 오케스트레이션, 공개 관리 API와 Scoring Policy는
포함하지 않는다.

## 2. `fraud_rule`

| 컬럼 | 타입 | Null | 의미 |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY` | NOT NULL | 내부 PK |
| `fraud_rule_id` | `UUID` | NOT NULL | UUID v4 업무 ID |
| `rule_code` | `VARCHAR(64)` | NOT NULL | 불변 논리 Rule 코드 |
| `name` | `VARCHAR(128)` | NOT NULL | 수정 가능한 현재 이름 |
| `description` | `VARCHAR(512)` | NOT NULL | 수정 가능한 현재 설명 |
| `lifecycle_status` | `VARCHAR(16)` | NOT NULL | `ACTIVE`, `RETIRED` |
| `concurrency_version` | `BIGINT` | NOT NULL | 낙관적 잠금 값 |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | 생성 시각 |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL | 마지막 허용 변경 시각 |

`fraud_rule_id`, `rule_code`, `created_at`은 수정하지 않는다.
`lifecycle_status`는 `ACTIVE → RETIRED`만 허용한다. 이름과 설명은 수정할
수 있지만 과거 Evidence의 표시 설명은 변경하지 않는다. 물리 삭제는
Trigger가 거부한다.

업무 UUID와 Rule Code는 각각 Unique다. UUID v4와 RFC 4122 variant,
uppercase snake case 코드, trim·길이, 상태, 음수가 아닌
concurrencyVersion과 시각 순서를 CHECK로 검증한다.

## 3. `rule_version`

| 컬럼 | 타입 | Null | 의미 |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY` | NOT NULL | 내부 PK |
| `rule_version_id` | `UUID` | NOT NULL | UUID v4 업무 ID |
| `fraud_rule_id` | `BIGINT` | NOT NULL | FraudRule FK |
| `version_number` | `INTEGER` | NOT NULL | Rule별 1부터 증가하는 업무 버전 |
| `status` | `VARCHAR(16)` | NOT NULL | DRAFT, PUBLISHED, WITHDRAWN |
| `reason_code` | `VARCHAR(64)` | NOT NULL | Evidence typed 계약 코드 |
| `weight` | `INTEGER` | NOT NULL | 1~100 원래 점수 기여도 |
| `condition_definition` | `JSONB` | NOT NULL | ruleCode별 typed 실행 정의 |
| `effective_from` | `TIMESTAMPTZ` | nullable | 적용 시작 포함 |
| `effective_to` | `TIMESTAMPTZ` | nullable | 적용 종료 제외 |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | 생성 시각 |
| `published_at` | `TIMESTAMPTZ` | nullable | 게시 시각 |
| `concurrency_version` | `BIGINT` | NOT NULL | 낙관적 잠금 값 |

주요 제약은 다음과 같다.

- UUID 업무 ID Unique와 UUID v4 CHECK
- `(fraud_rule_id, version_number)` Unique
- `version_number >= 1`
- `weight BETWEEN 1 AND 100`
- reasonCode uppercase snake case
- conditionDefinition은 비어 있지 않은 JSON object
- `effective_to IS NULL OR effective_to > effective_from`
- PUBLISHED에는 effectiveFrom과 publishedAt 필수
- FraudRule FK `ON DELETE RESTRICT`
- 물리 삭제 금지

Rule Code와 Reason Code는 초기 문자열이 같지만 동일 개념이 아니며 값
동일성 제약을 두지 않는다. Reason Code가
`RuleEvidenceObservationSummary`에서 지원되는지는 Java가 검증한다.

## 4. conditionDefinition

`condition_definition`은 범용 DSL이 아니다.
`RuleConditionDefinition`이 정확한 필드와 값을 검증한다.

| Rule Code | 정확한 필드 |
| --- | --- |
| `TRANSFER_ABSOLUTE_HIGH_AMOUNT` | `transactionTypes`, `currencyCode`, `amountThreshold` |
| `RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT` | `prerequisiteRuleCode`, `eventType`, `windowSeconds`, `matchPolicy`, `selectionPolicy` |
| `RECENT_SECURITY_CHANGE_HIGH_AMOUNT` | `prerequisiteRuleCode`, `passwordEventType`, `transferLimitEventType`, `windowSeconds`, `matchPolicy`, `sequencePolicy`, `selectionPolicy` |
| `RECENT_BENEFICIARY_TRANSFER` | `eventType`, `windowSeconds`, `matchPolicy`, `selectionPolicy` |

알 수 없는 필드, 누락, null, 잘못된 scalar·array 타입, 중복 거래 유형,
0 이하 또는 32-bit 범위를 벗어난 시간창과 금액 저장 범위를 거부한다.
입력과 반환 JSON은 defensive copy한다.

개별 JSON 필드 검색을 하지 않으므로 GIN 인덱스는 없다.

## 5. 상태와 불변성

```text
DRAFT → PUBLISHED
DRAFT → WITHDRAWN
```

DRAFT에서는 reasonCode, weight, conditionDefinition과 예정 기간을 수정할
수 있다. WITHDRAWN은 terminal이다.

PUBLISHED에서는 Rule FK, versionNumber, reasonCode, weight,
conditionDefinition, effectiveFrom과 publishedAt을 수정하지 않는다.
effectiveTo만 null에서 유효한 종료 시각으로 한 번 설정할 수 있다.
변경된 실행 정의는 새 versionNumber로 생성한다.

JPA `@Version`과 DB Trigger는 서로 다른 방어선이다. Trigger는 허용되지
않은 직접 SQL UPDATE와 DELETE도 거부한다.

## 6. 적용 기간과 exclusion constraint

PUBLISHED 적용 기간은 다음 반개방 구간이다.

```text
[effectiveFrom, effectiveTo)
```

시작은 포함하고 종료는 제외한다. null 종료는 무기한이다. 인접한 두
기간은 허용하지만 겹치는 기간은 허용하지 않는다.

V5는 `CREATE EXTENSION IF NOT EXISTS btree_gist`와 PUBLISHED 행만 대상으로
하는 GiST exclusion constraint를 생성한다. 게시 Service는 FraudRule
행을 잠그고 중복을 사전 확인하며 DB 제약이 경쟁 쓰기의 최종 방어선이다.

이 계약은 행동 이벤트 Rule 조회의 `[T-window, T]` 양끝 포함 시간창과
별개다.

실행 가능 조회 조건은 다음과 같다.

```text
FraudRule.lifecycleStatus = ACTIVE
RuleVersion.status = PUBLISHED
effectiveFrom <= asOf
effectiveTo IS NULL OR asOf < effectiveTo
```

결과는 0건 또는 1건이며 중복을 `findFirst`나 limit으로 숨기지 않는다.

## 7. DetectionEvidence 연결

V5는 기존 테이블에 다음 nullable 컬럼을 additive하게 추가한다.

```text
detection_evidence.rule_version_id BIGINT NULL
```

FK는 `rule_version.id ON DELETE RESTRICT`이고 null이 아닌 참조 조회용
partial index가 있다. 기존 행은 backfill하지 않는다.

신규 Java 생성 경로는 PUBLISHED RuleVersion을 요구하고 다음 snapshot을
참조 엔티티에서 파생한다.

- `rule_code = FraudRule.ruleCode`
- `rule_version = canonical decimal versionNumber`
- `reason_code = RuleVersion.reasonCode`
- `score_contribution = RuleVersion.weight`

FK가 존재하면 Evidence insert Trigger가 네 값을 다시 검증한다. Rule
Code와 Reason Code 값의 동일성은 검증하지 않는다. 기존 Evidence
불변성과 typed observationSummary, 행동 Event UUID 최소 저장 계약은
유지한다.

JPA 관계는 LAZY이며 cascade, 양방향 컬렉션과 orphanRemoval이 없다.

## 8. R001~R004 DRAFT seed

| 별칭 | FraudRule UUID | RuleVersion UUID | weight |
| --- | --- | --- | ---: |
| R001 | `10000000-0000-4000-8000-000000000001` | `20000000-0000-4000-8000-000000000001` | 15 |
| R002 | `10000000-0000-4000-8000-000000000002` | `20000000-0000-4000-8000-000000000002` | 20 |
| R003 | `10000000-0000-4000-8000-000000000003` | `20000000-0000-4000-8000-000000000003` | 40 |
| R004 | `10000000-0000-4000-8000-000000000004` | `20000000-0000-4000-8000-000000000004` | 10 |

모든 FraudRule은 ACTIVE다. 모든 최초 RuleVersion은 versionNumber 1,
DRAFT이고 적용 시작·종료와 게시 시각은 null이다. 초기 reasonCode는
ruleCode와 같은 문자열이다.

UUID는 canonical lowercase UUID v4와 RFC 4122 variant를 만족한다.
R001~R004는 문서 별칭이고 별도 컬럼이 아니다.

임계금액 `10,000,000 KRW`, 15·20·40·10 가중치와 86,400초 시간창은
구현 검증용 실험값이다. 측정 완료된 운영 정책이 아니며 DRAFT seed는
거래 처리나 탐지 결과를 자동 변경하지 않는다.

## 9. Migration과 검증

V5는 V1~V4를 수정하지 않는다. 기존 데이터의 backfill·삭제 없이 두
테이블, 확장, 제약, Trigger, DRAFT seed와 Evidence nullable FK를
추가한다.

PostgreSQL 17 Testcontainers에서 다음을 검증한다.

- V1→V5 순차 적용과 Hibernate schema validation
- btree_gist 설치와 exclusion constraint
- UUID, 코드, 상태, weight, JSON, 기간, FK와 Unique
- 인접 기간, 겹침 거부와 동시 게시
- 게시 후 불변성, 삭제 방지와 낙관적 잠금
- DRAFT seed와 typed condition
- 실행 가능 시각 경계와 결정적 버전 목록
- Evidence legacy null FK, snapshot 일치와 LAZY 관계
- 기존 거래·행동·탐지 persistence 회귀
