# ADR-005: FraudRule·RuleVersion 물리 모델

## 상태

- 결정: 승인됨
- 구현 상태: Spring Boot JPA, Flyway V5와 PostgreSQL 통합 테스트 구현
- 결정일: 2026-07-30

## 배경

Rule v1 계약은 R001~R004의 안정적인 `ruleCode`, 조건, 가중치,
Reason Code, 활성 버전 고정과 과거 Evidence 재현을 요구한다. 기존
`detection_evidence`는 Rule 물리 테이블이 없어서 `rule_code`와
`rule_version` 문자열만 불변 snapshot으로 저장했다.

논리 ERD는 하나의 FraudRule 테이블에 버전별 행을 저장하는 방안과 논리
Rule·실행 버전을 분리하는 방안을 함께 검토했다. 이름·설명·논리 생명주기와
실행 조건·가중치·적용 기간·Evidence 계약은 변경 이유와 불변성 경계가
다르므로 물리 모델을 분리할 필요가 있다.

## 결정

### FraudRule과 RuleVersion 분리

`FraudRule`은 버전과 무관한 논리 정체성을 소유한다.

- 내부 BIGINT PK와 UUID v4 `fraudRuleId`
- 불변 `ruleCode`
- 수정 가능한 이름·설명
- `ACTIVE → RETIRED` 단방향 lifecycle
- 동시 변경 검출용 `concurrencyVersion`

`RuleVersion`은 특정 버전의 실행 설정과 Evidence 출력 계약을 소유한다.

- 내부 BIGINT PK와 UUID v4 `ruleVersionId`
- FraudRule FK와 Rule별 `versionNumber`
- `DRAFT`, `PUBLISHED`, `WITHDRAWN`
- `reasonCode`, 1~100 `weight`, typed `conditionDefinition`
- `[effectiveFrom, effectiveTo)` 적용 기간
- `createdAt`, `publishedAt`, `concurrencyVersion`

R001~R004는 문서 별칭이며 별도 영속 컬럼을 만들지 않는다.

### Rule Code와 Reason Code

`ruleCode`는 논리 Rule의 안정적인 식별자다. `reasonCode`는 Evidence의
설명과 typed `observationSummary` 계약을 선택한다. Rule v1 초기값은
두 문자열이 같지만 개념적으로 독립적이며 DB와 Java에서 값의 동일성을
강제하지 않는다.

Reason Code는 RuleVersion에 저장한다. 게시된 버전의 Evidence 출력
계약을 재현하려면 조건·가중치와 함께 어떤 typed observation 계약을
사용했는지 보존해야 하기 때문이다.

### JSONB와 typed allowlist

R001~R004는 사용하는 조건 구조가 다르므로 `condition_definition JSONB`를
사용한다. 범용 Rule DSL이나 임의 속성 저장소로 사용하지 않는다.
Spring Boot의 `RuleConditionDefinition`이 `ruleCode`별 정확한 필드,
필수값, 타입, 범위와 고정 정책값을 검증하고 defensive copy를 제공한다.

조건 JSON의 개별 필드로 RuleVersion을 조회하지 않으므로 GIN 인덱스를
만들지 않는다.

### 상태와 게시 후 불변성

- DRAFT는 실행 정의와 예정 기간을 수정할 수 있다.
- DRAFT는 PUBLISHED 또는 WITHDRAWN으로만 전이한다.
- WITHDRAWN은 terminal이다.
- PUBLISHED의 Rule FK, versionNumber, reasonCode, weight,
  conditionDefinition, effectiveFrom과 publishedAt은 수정하지 않는다.
- PUBLISHED의 effectiveTo는 null에서 유효한 종료 시각으로 한 번만
  설정할 수 있다.
- 조건이나 가중치 변경은 새 RuleVersion으로 표현한다.
- FraudRule과 RuleVersion은 물리 삭제하지 않는다.

JPA 낙관적 잠금과 DB Trigger를 함께 사용한다. `versionNumber`는 업무
내용 버전이고 `concurrencyVersion`은 동시 쓰기 충돌 검출값이다.

### 적용 기간과 중복 방지

PUBLISHED 적용 기간은 `[effectiveFrom, effectiveTo)`다. 시작은 포함하고
종료는 제외하며 null 종료는 무기한이다. 같은 시각에 이전 버전을
종료하고 다음 버전을 시작할 수 있다.

동일 FraudRule의 PUBLISHED 기간 중복은 `btree_gist`와 다음 의미의
PostgreSQL exclusion constraint로 막는다.

```sql
EXCLUDE USING gist (
    fraud_rule_id WITH =,
    tstzrange(effective_from, effective_to, '[)') WITH &&
)
WHERE (status = 'PUBLISHED')
```

게시 Service는 FraudRule을 잠그고 먼저 중복을 검사해 업무 오류를
반환한다. exclusion constraint는 직접 SQL과 동시 쓰기를 포함한 최종
방어선이다. 실행 가능 조회는 중복을 limit으로 숨기지 않고 0건 또는
1건을 전제로 한다.

이 적용 기간 계약은 BehaviorEvent 조회의 `[T-window, T]` 양끝 포함
계약과 별개다.

### DetectionEvidence FK와 snapshot 병행

기존 `rule_code`, `rule_version`, `reason_code`, 설명, 점수 기여도와
관측 요약 snapshot을 유지하면서 nullable `rule_version_id` FK를
추가한다.

- 기존 Evidence는 FK가 null이어도 조회할 수 있고 backfill하지 않는다.
- 신규 애플리케이션 생성 경로는 PUBLISHED RuleVersion을 요구한다.
- ruleCode, canonical decimal versionNumber, reasonCode와 weight는
  RuleVersion에서 파생한다.
- FK가 있는 Evidence는 DB Trigger가 네 snapshot 값과 참조 버전의
  일치를 검증한다.
- Rule Code와 Reason Code 자체의 동일성은 검사하지 않는다.
- FK는 `ON DELETE RESTRICT`, JPA 관계는 LAZY이며 cascade와
  orphanRemoval을 사용하지 않는다.

### 초기 DRAFT seed

V5는 R001~R004의 FraudRule 4건과 versionNumber 1 RuleVersion 4건을
고정 UUID v4로 생성한다. RuleVersion은 모두 DRAFT이고 effectiveFrom,
effectiveTo, publishedAt은 null이다.

현재 임계값, 가중치와 24시간 창은 Rule v1 구현 검증용 실험값이며 측정
완료된 운영 정책이 아니다. DRAFT seed는 실행 가능 Rule이 아니고 거래나
탐지 결과를 자동 변경하지 않는다.

## 대안

### 단일 FraudRule 버전행

초기 구현은 단순하지만 논리 이름·설명과 불변 실행 설정이 반복되고,
논리 lifecycle과 버전 상태가 섞인다. Evidence가 특정 실행 버전을
참조하는 FK 의미도 분리 모델보다 불명확하다.

### 명시적 조건 컬럼

DB 타입 제약은 강하지만 서로 다른 Rule 전용 nullable 컬럼이 늘고 새
조건마다 스키마 변경이 필요하다. JSONB를 선택하되 애플리케이션의 typed
allowlist로 임의 구조를 차단한다.

### Evidence FK만 저장

중복 데이터는 줄지만 기존 계약을 깨고, 과거 화면·감사 조회가 Rule
테이블에만 의존한다. FK와 불변 snapshot을 함께 유지한다.

### Service 중복 검사만 사용

이해 가능한 오류를 제공하지만 직접 SQL과 경쟁 쓰기의 최종 무결성을
보장하지 못한다. PostgreSQL exclusion constraint를 함께 사용한다.

### category 또는 enabled 추가

`enabled`는 lifecycle·버전 상태와 의미가 중복된다. 현재 amount·security
그룹은 단순 분류가 아니라 점수 합산 정책이므로 FraudRule category로
고정하지 않고 후속 Scoring Policy 책임으로 유지한다.

## 결과

- PostgreSQL과 Spring Boot가 Rule 정의·버전·활성 상태의 업무 원본이
  된다.
- 과거 Evidence는 참조 버전과 자체 snapshot으로 재현할 수 있다.
- 게시된 실행 정의의 덮어쓰기와 기간 중복을 애플리케이션·DB 양쪽에서
  차단한다.
- Rule 실행 엔진, 공개 관리 API, 거래 오케스트레이션과 Scoring Policy는
  이 결정의 구현 범위에 포함하지 않는다.
