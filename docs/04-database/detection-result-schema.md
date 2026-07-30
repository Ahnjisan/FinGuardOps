# 탐지 결과·근거 PostgreSQL 물리 DB 계약

## 1. 문서 목적과 구현 범위

이 문서는 FinGuardOps의 `detection_result`,
`detection_evidence`와 Transaction 채택 결과의 PostgreSQL 물리 계약을
정의한다. 기본 구현은 Flyway
`V3__create_detection_result_and_evidence_tables.sql`이며 V5가
FraudRule·RuleVersion과 nullable Evidence FK를 additive하게 연결한다.
JPA Entity, Repository와 내부 persistence service가 구현되어 있다.

다음 기능은 구현하지 않는다.

- Rule 실행
- FastAPI Client와 ML 추론
- 외부 탐지 실행 API와 탐지 결과 조회 API
- 거래 생성의 최종 동기 탐지 응답
- 사건, 감사 로그와 AI 리포트

현재 `POST /api/v1/transactions`는 기존 계약대로 Transaction을
`RECEIVED`로 저장하고 탐지 관련 null을 반환한다.

## 2. 책임 경계

`DetectionResult`는 한 거래에 대한 한 분석 실행·결과 버전을 소유한다.
실패 실행도 결과 버전을 소비한다. `DetectionEvidence`는 한 결과를
설명하는 시스템 생성 탐지 근거이며 조사 메모, 감사 로그 또는 AI 생성
문장이 아니다.

Evidence에는 실제 고객·계좌·기기·수취인 식별자, 원문 IP, 전체 Feature
벡터와 원문 행동 로그를 저장하지 않는다.

## 3. `detection_result`

### 3.1 컬럼

| 컬럼 | PostgreSQL 타입 | Null | 의미 |
| --- | --- | --- | --- |
| `id` | `BIGINT` Identity | NOT NULL | 내부 PK |
| `detection_result_id` | `UUID` | NOT NULL | Spring Boot 생성 UUID v4 업무 ID |
| `financial_transaction_id` | `BIGINT` | NOT NULL | 분석 대상 거래 FK |
| `detection_result_version` | `INTEGER` | NOT NULL | 거래별 1부터 증가하는 분석 버전 |
| `analysis_status` | `VARCHAR(16)` | NOT NULL | `PENDING`, `IN_PROGRESS`, `COMPLETED`, `FAILED` |
| `risk_score` | `INTEGER` | nullable | 완료 결과의 0~100 정수 점수 |
| `risk_level` | `VARCHAR(16)` | nullable | 완료 결과의 위험 등급 |
| `rule_set_version` | `VARCHAR(64)` | NOT NULL | 평가 Rule 집합 버전 |
| `scoring_policy_version` | `VARCHAR(64)` | NOT NULL | 합산·등급 정책 버전 |
| `feature_version` | `VARCHAR(64)` | NOT NULL | Feature 계산 규칙 버전 |
| `model_version` | `VARCHAR(64)` | nullable | ML 사용 모델 버전 |
| `evaluation_cutoff_at` | `TIMESTAMPTZ` | NOT NULL | 불변 평가 Snapshot cutoff |
| `analysis_started_at` | `TIMESTAMPTZ` | nullable | 분석 시작 시각 |
| `analysis_completed_at` | `TIMESTAMPTZ` | nullable | 완료·실패 확정 시각 |
| `failure_code` | `VARCHAR(64)` | nullable | 안전한 내부 실패 분류 |
| `analysis_trace_id` | `VARCHAR(64)` | NOT NULL | 분석 당시 운영 추적값 |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | PENDING 생성 시각 |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL | 마지막 비terminal 전이 시각 |

`analysis_trace_id`는 기본 업무 응답에 노출하거나 메트릭 레이블로
사용하지 않는다.

### 3.2 상태별 필드

| 상태 | 필수 | 금지 |
| --- | --- | --- |
| `PENDING` | 버전·정책·cutoff·trace | 시작·완료 시각, 점수, 등급, 실패 코드 |
| `IN_PROGRESS` | `analysis_started_at` | 완료 시각, 점수, 등급, 실패 코드 |
| `COMPLETED` | 시작·완료 시각, 점수, 등급 | 실패 코드 |
| `FAILED` | 완료 시각, 실패 코드 | 점수, 등급 |

`FAILED`는 시작 전에도 확정될 수 있으므로 `analysis_started_at`이 null일
수 있다. 점수와 등급의 임계값 관계는 `scoring_policy_version`별
애플리케이션 검증 책임이며 DB에는 고정하지 않는다.

### 3.3 제약과 인덱스

- `pk_detection_result`: 내부 PK
- `uq_detection_result_business_id`: UUID 업무 ID 중복 방지
- `uq_detection_result_transaction_version`: 같은 거래·버전 중복 방지
- `uq_detection_result_adoption_target`: Transaction의 복합 FK가 같은
  거래와 위험 등급을 함께 참조하기 위해 필요한 대상 Unique
- `fk_detection_result_transaction`: 거래 삭제 제한
- UUID v4·RFC variant Check
- 양의 결과 버전, 상태, 점수 범위, 등급, 버전 문자열, 실패 코드,
  trace와 상태별 필드 Check
- `ix_detection_result_status_updated_at`: 미완료·장애 결과 운영 조회

`uq_detection_result_adoption_target`은 `id` PK와 의미상 중복 중복 방지용
제약이 아니라 PostgreSQL 복합 FK의 참조 대상 요건을 충족하기 위한
제약이다.

## 4. `detection_evidence`

### 4.1 컬럼

| 컬럼 | PostgreSQL 타입 | Null | 의미 |
| --- | --- | --- | --- |
| `id` | `BIGINT` Identity | NOT NULL | 내부 PK |
| `evidence_id` | `UUID` | NOT NULL | Spring Boot 생성 UUID v4 업무 ID |
| `detection_result_id` | `BIGINT` | NOT NULL | 소속 결과 FK |
| `evidence_type` | `VARCHAR(32)` | NOT NULL | 근거 유형 |
| `reason_code` | `VARCHAR(64)` | NOT NULL | 설명 가능한 안정 코드 |
| `display_description` | `VARCHAR(512)` | NOT NULL | 민감정보 없는 표시 설명 |
| `score_contribution` | `INTEGER` | nullable | 0~100 개별 기여도 |
| `rule_code` | `VARCHAR(64)` | nullable | RULE의 불변 Rule 코드 |
| `rule_version` | `VARCHAR(32)` | nullable | RULE의 불변 Rule 버전 |
| `rule_version_id` | `BIGINT` | nullable | V5의 정확한 RuleVersion FK. 기존 행은 null 가능 |
| `observation_summary` | `JSONB` | NOT NULL | 제한된 typed 관측 요약 |
| `evidence_occurred_at` | `TIMESTAMPTZ` | NOT NULL | 근거 관측 시각 |
| `sort_order` | `INTEGER` | NOT NULL | 결과 안의 안정적 정렬 순서 |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | 저장 시각 |

근거 유형은 `RULE`, `ML`, `EXTERNAL_RISK`, `BEHAVIOR_PATTERN`이다.
현재 Java 생성 경로는 Rule v1 `RULE` Evidence를 우선 구현한다.

RULE은 `rule_code`, `rule_version`, `score_contribution`이 필수이다.
비-RULE은 Rule 필드와 RuleVersion FK를 null로 유지한다. V3는 Rule
물리 테이블이 없어서 코드·버전 snapshot만 보존했고 V5는 기존 snapshot을
유지하면서 nullable `rule_version_id`를 추가한다. 기존 행은 backfill하지
않는다.

신규 Java 생성 경로는 PUBLISHED RuleVersion을 요구하고 ruleCode,
canonical decimal versionNumber, reasonCode와 weight를 참조 엔티티에서
파생한다. FK가 존재하는 Evidence는 insert Trigger가 네 snapshot 값의
일치를 검증한다. Rule Code와 Reason Code 자체의 동일성은 강제하지
않는다.

### 4.2 제약과 인덱스

- 결과·Evidence 업무 ID UUID v4 Check와 Unique
- 결과 FK `ON DELETE RESTRICT`
- RuleVersion FK `ON DELETE RESTRICT`, 기존 호환을 위해 nullable
- `(detection_result_id, sort_order)` Unique
- RULE 필드 조합과 점수 범위 Check
- JSON object이며 빈 object가 아닌지 Check
- `uq_detection_evidence_result_rule_code` partial unique index로 한
  DetectionResult에 같은 Rule 코드 Evidence를 한 건만 허용
- JSONB GIN 인덱스 없음
- null이 아닌 `rule_version_id` 조회용 partial index

## 5. Rule v1 `observation_summary`

금액은 API 금액 계약과 같이 19자리 이하의 음수가 아닌 KRW 정수
문자열, 시각은 UTC `Instant` 문자열, 시간 길이는 음수가 아닌 JSON
정수로 검증한다. 모든 필드는 필수이고 알 수 없는 필드, null과 중첩
객체·배열은 거부한다.

| Reason Code | 정확한 허용 필드 |
| --- | --- |
| `TRANSFER_ABSOLUTE_HIGH_AMOUNT` | `observedAmount`, `amountThreshold` |
| `RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT` | `observedAmount`, `amountThreshold`, `eventId`, `deviceRegisteredAt`, `elapsedSeconds`, `windowSeconds` |
| `RECENT_SECURITY_CHANGE_HIGH_AMOUNT` | `observedAmount`, `amountThreshold`, `passwordChangedEventId`, `passwordChangedAt`, `transferLimitChangedEventId`, `transferLimitChangedAt`, `elapsedSeconds`, `windowSeconds` |
| `RECENT_BENEFICIARY_TRANSFER` | `observedAmount`, `eventId`, `beneficiaryRegisteredAt`, `elapsedSeconds`, `windowSeconds` |

R002와 R004의 `eventId`, R003의 `passwordChangedEventId`와
`transferLimitChangedEventId`는 선택된 BehaviorEvent의 내부 BIGINT PK가
아니라 canonical lowercase UUID v4 업무 ID이다. RFC 4122 variant를
검증한다. R001은 행동 이벤트를 사용하지 않으므로 행동 Event ID 필드를
허용하지 않는다.

R003은 `PASSWORD_CHANGED`와 `TRANSFER_LIMIT_CHANGED` 두 이벤트를 모두
사용한다. `passwordChangedAt <= transferLimitChangedAt <=
DetectionResult.evaluationCutoffAt`을 검증하고 두 이벤트 모두
`windowSeconds` 안에 있어야 한다. `elapsedSeconds`는
`evaluationCutoffAt - transferLimitChangedAt`의 경과 초와 같아야 한다.
기존 단일 `securityEventType`, `securityChangedAt` 필드는 두 이벤트를
재현할 수 없어 허용 목록에서 제거한다.

DB는 JSON object와 비어 있지 않음을 검증하고, Reason Code별 정확한
allowlist, scalar 타입, 행동 Event ID, R003 순서·cutoff·경과 시간은
Java 도메인 값 객체가 검증한다. 원문 행동 이벤트 전체, 고객·계좌·기기
원문과 내부 PK를 JSONB에 복제하지 않는다.

## 6. Transaction 채택 결과

`financial_transaction`에는 다음 nullable 컬럼이 추가된다.

- `adopted_detection_result_id BIGINT`
- `risk_level VARCHAR(16)`
- `risk_response_outcome VARCHAR(32)`

채택 결과 ID와 위험 등급은 함께 null이거나 함께 존재한다. 복합 FK
`(adopted_detection_result_id, id, risk_level)`가 같은 Transaction 소속과
위험 등급 일치를 보장한다. adoption trigger가 대상 결과의
`analysis_status = COMPLETED`를 검증한다.

위험 대응은 null일 수 있다. 존재하면 다음 매핑만 허용한다.

| 위험 등급 | 위험 대응 |
| --- | --- |
| `LOW` | `APPROVED` |
| `MEDIUM` | `APPROVED_WITH_MONITORING` |
| `HIGH` | `ADDITIONAL_AUTH_REQUIRED` |
| `CRITICAL` | `HELD` |

Transaction이 `FAILED`로 전이되어도 이전에 정상 채택한 결과·등급·대응을
유지할 수 있다. DetectionResult에는 `adopted` 컬럼을 두지 않는다.

## 7. 버전과 동시성

Spring Boot는 PENDING 생성 트랜잭션에서 대상 거래 행을
`PESSIMISTIC_WRITE`로 잠그고 현재 최대 결과 버전에 1을 더한다. 버전은
1부터 단조 증가하나 롤백·실패에 따른 간격을 허용한다.

실패 결과와 동일 분석의 재시도도 새 버전을 소비한다. 거래별
Unique가 경쟁 쓰기의 최종 방어선이다. 동일 버전의 늦은 응답이나
중복 저장은 기존 결과를 수정하지 않고 거부한다.

## 8. 불변성과 Trigger

- `tg_detection_result_history_guard`
  - `PENDING → IN_PROGRESS/FAILED`
  - `IN_PROGRESS → COMPLETED/FAILED`
  - 식별자·거래·버전·정책·cutoff·trace 변경 금지
  - `COMPLETED`, `FAILED` UPDATE·DELETE 금지
  - Evidence가 남은 결과의 FAILED 전이 금지
- `tg_detection_evidence_history_guard`
  - UPDATE·DELETE 금지
  - `IN_PROGRESS` 결과에만 Evidence 추가
- `tg_financial_transaction_adoption_guard`
  - `COMPLETED` 결과만 채택

Evidence는 완료 트랜잭션에서 먼저 저장한 뒤 결과를 COMPLETED로
전환한다. 중간 단계가 실패하면 같은 트랜잭션 전체가 rollback된다.

## 9. JPA

- DetectionResult → FinancialTransaction: LAZY, cascade 없음
- DetectionEvidence → DetectionResult: LAZY, cascade 없음
- DetectionEvidence → RuleVersion: nullable LAZY, cascade 없음
- FinancialTransaction → 채택 DetectionResult: nullable LAZY, cascade 없음
- 양방향 컬렉션, `orphanRemoval` 없음
- Evidence는 전용 Repository로 조회
- 불변 컬럼 `updatable=false`
- JSONB는 `JsonNode`와 `@JdbcTypeCode(SqlTypes.JSON)`

## 10. Migration과 검증

V3는 V1·V2를 수정하지 않고 두 테이블과 거래의 nullable 컬럼을
추가한다. V5는 V1~V4를 수정하지 않고 RuleVersion nullable FK와
snapshot 검증을 추가한다. 기존 거래와 Evidence는 backfill하지 않는다.

PostgreSQL 17 Testcontainers에서 Migration 순서, Hibernate validation,
제약·Trigger, 동시 버전 할당, RuleVersion snapshot 정합성, LAZY 관계,
rollback과 기존 거래
접수·멱등·Snapshot 회귀를 검증한다. H2 호환 결과를 근거로 사용하지
않는다.
