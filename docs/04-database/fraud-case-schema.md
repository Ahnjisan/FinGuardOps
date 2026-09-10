# 사건 영속 기반 스키마

## 1. 범위

이 문서는 Issue #154의 `FraudCase`·첫 거래 연결, Issue #207의 사건 조회 및
Issue #209의 조사 상태·담당자 변경 및 Issue #211의 사건 종료 경계를 정의한다. Flyway V6는 기존
V1~V5를 수정하지 않고 `fraud_case`와 `case_transaction`을 추가하며, Flyway V10은
기존 migration을 수정하지 않고 무필터 변경 시각 조회 인덱스를 추가한다.

구현 범위는 사건 영속 모델, 거래 연결, 중복 연결 제약과 내부 persistence
boundary이며, 위험 대응 최종화 경계가 이를 재사용해 신규 사건·첫 연결 또는 기존
활성 사건을 거래 최종 상태·대응 결과·AuditLog와 같은 REQUIRED 트랜잭션에서
확정한다. 현재 public `POST /api/v1/transactions`도 Rule 결과 채택 뒤 이 finalization
경계를 호출하고, 그 commit이 성공한 뒤 별도 transaction에서 Snapshot v2를
완료한다. Issue #209의 상태·담당자 mutation과 Issue #211의 사건 종료를 포함하지만
기존 사건에 다른 거래 추가와 사건 병합·분리는 포함하지 않는다. Snapshot v2의
저장·codec 책임은 이 문서가 아니라 `idempotency_record.response_snapshot` 계약에
있다. AuditLog 계약은
[`audit-log-schema.md`](audit-log-schema.md)를 따른다.

## 2. 관계와 식별자

```text
FraudCase 1 ─ N CaseTransaction N ─ 1 FinancialTransaction
```

- `CaseTransaction`이 두 FK의 JPA 관계 소유자이다.
- `FinancialTransaction`에는 사건 컬럼이나 mutable 사건 컬렉션을 추가하지 않는다.
- 내부 PK와 외부 업무 식별자인 UUID v4 `caseId`를 분리한다.
- 한 거래는 여러 `CLOSED` 사건 이력을 가질 수 있지만 활성 사건은 최대 하나이다.

## 3. fraud_case

| 컬럼 | 타입 | 제약 |
| --- | --- | --- |
| `id` | `BIGINT` identity | PK |
| `case_id` | `UUID` | NOT NULL, UUID v4, UNIQUE |
| `case_status` | `VARCHAR(48)` | NOT NULL, 승인 Enum Check |
| `final_disposition` | `VARCHAR(32)` | nullable, 승인 Enum Check |
| `assignee_ref` | `VARCHAR(128)` | nullable, trim·길이 Check |
| `review_started_at` | `TIMESTAMPTZ` | nullable |
| `closed_at` | `TIMESTAMPTZ` | nullable |
| `concurrency_version` | `BIGINT` | NOT NULL, 0 이상, JPA `@Version` |
| `created_at` | `TIMESTAMPTZ` | NOT NULL |
| `last_changed_at` | `TIMESTAMPTZ` | NOT NULL |

활성 상태는 `OPEN`, `IN_REVIEW`, `ADDITIONAL_INFORMATION_REQUIRED`이며
`final_disposition`과 `closed_at`이 모두 null이어야 한다. `CLOSED`에는 두 값이
모두 필요하고 `IN_REVIEW`에는 `assignee_ref`가 필요하다.

## 4. case_transaction

| 컬럼 | 타입 | 제약 |
| --- | --- | --- |
| `id` | `BIGINT` identity | PK |
| `fraud_case_id` | `BIGINT` | NOT NULL, FK, ON DELETE RESTRICT |
| `financial_transaction_id` | `BIGINT` | NOT NULL, FK, ON DELETE RESTRICT |
| `linked_at` | `TIMESTAMPTZ` | NOT NULL |

`UNIQUE(fraud_case_id, financial_transaction_id)`로 동일 사건–거래 연결 중복을
막는다. 대표 거래, 연결 사유와 대표 위험 등급은 V6에 포함하지 않는다.

## 5. Index

- `uq_fraud_case_case_id`
- `ix_fraud_case_status_last_changed(case_status, last_changed_at, id)`
- `ix_fraud_case_last_changed(last_changed_at, id)` — 무필터 기본 정렬과 변경 시각 범위
- `uq_case_transaction_case_transaction(fraud_case_id, financial_transaction_id)`
- `ix_case_transaction_transaction_case(financial_transaction_id, fraud_case_id)`

## 6. 트랜잭션과 동시성

내부 Service는 Spring 기본 `READ_COMMITTED`, `@Transactional` 기본 `REQUIRED`
전파를 사용한다. 잠금 순서는 다음과 같다.

1. `FinancialTransaction` `PESSIMISTIC_WRITE`
2. 기존 활성 `FraudCase`를 `caseId` 오름차순으로 잠금
3. `CaseTransaction`을 같은 순서로 잠금

동일 거래 재호출은 기존 활성 연결을 반환한다. 활성 사건이 둘 이상이면 임의로
선택하지 않고 정합성 오류로 거부한다. V6에는 cross-table trigger, 중복 활성
상태 컬럼과 별도 활성 관계를 추가하지 않는다.

사건 생성 시각, 변경 시각과 첫 연결 시각은 기존 UTC `Clock`의 한 값을
PostgreSQL 마이크로초 정밀도로 정규화해 사용한다.

resolution은 일반 조회로 사건을 가져와 `expectedVersion`을 먼저 비교하고
`IN_REVIEW`·담당자·`review_started_at` 불변식을 검증한다. 하나의 resolution 시각을
`closed_at`과 `last_changed_at`에 사용하고 `created_at`, `assignee_ref`,
`review_started_at`은 유지한다. `FraudCase` flush로 실제 version 증가를 확정한 뒤
같은 REQUIRED 트랜잭션에서 감사 append·flush를 수행한다. row lock과 자동 retry는
사용하지 않으며 충돌·감사 실패는 종료 필드·version·감사를 모두 rollback한다.

위험 대응 최종화는 이 Service에 참여하기 전에 거래를 먼저 잠근다. 이 Service의
기존 동일 거래 재잠금은 같은 REQUIRED 트랜잭션에서 수행되며 잠금 순서를 바꾸지
않는다. 사건 생성·연결, 거래 최종화, 감사 중 어느 단계라도 실패하면 모두
rollback한다.

## 7. Public 거래 finalization과 recovery 경계

채택된 DetectionResult의 risk별 현재 finalization 결과는 다음과 같다.

| Risk | 거래 최종 `processing_status` | `risk_response_outcome` | `CaseTransaction` 관계 |
| --- | --- | --- | --- |
| `LOW` | `APPROVED` | `APPROVED` | 0개 |
| `MEDIUM` | `APPROVED` | `APPROVED_WITH_MONITORING` | 0개 |
| `HIGH` | `ADDITIONAL_AUTH_REQUIRED` | `ADDITIONAL_AUTH_REQUIRED` | 정확히 1개 |
| `CRITICAL` | `HELD` | `HELD` | 정확히 1개 |

HIGH/CRITICAL finalization은 해당 transaction에 활성 사건 관계가 없으면 `OPEN`
FraudCase와 첫 CaseTransaction을 생성하고, 이미 같은 transaction에 정확히 하나의
활성 사건 관계가 있으면 그 관계만 재사용한다. 다른 기존 사건에 새 거래를 추가하는
일반 기능은 구현하지 않았다. 여러 활성 관계를 임의 선택하지 않으며 사건 생성·관계,
거래 최종 상태·outcome과 finalization AuditLog는 같은 `REQUIRED` transaction에서 함께
commit하거나 rollback한다. LOW/MEDIUM은 사건 Service를 호출하지 않는다.

one-shot recovery는 모든 CaseTransaction 관계를 transaction PK로 조회해 LOW/MEDIUM은
0개, HIGH/CRITICAL은 정확히 1개인지 확인한다. 정확히 한 관계인 경우
`CaseTransaction.belongsTo(fraudCase, transaction)`로 같은 FraudCase와 transaction을
연결하는지도 확인한다. 누락·복수·LOW/MEDIUM의 허용되지 않은 관계와 소유 불일치는
`INCONSISTENT_CASE_RELATIONSHIP`으로 거부한다.

recovery는 FraudCase의 `case_status`, 담당자, `final_disposition`, InvestigationNote
내용이나 사건 lifecycle 의미 전체를 검사하지 않는다. 특히 현재 source는 recovery
시 FraudCase 상태가 active인지 확인하지 않는다.

finalization 감사 검사는 `audit_log`에서 해당 transaction ID와 두 transaction action을
조회한 결과에 한정한다.

- 전체 로그가 정확히 2개이며 `TRANSACTION_RISK_RESPONSE_APPLIED`와
  `TRANSACTION_STATUS_CHANGED`가 각각 정확히 1개
- 두 로그 모두 `target_type=FINANCIAL_TRANSACTION`, `target_id`와 `transaction_id`가
  대상 transaction ID, `case_id=null`
- risk action은 reason `RISK_RESPONSE_DECIDED_BY_POLICY`, before summary null,
  after summary의 exact `riskResponseOutcome`가 현재 outcome과 일치
- status action은 reason `TRANSACTION_FINALIZED_BY_RISK_POLICY`, before summary의 exact
  `processingStatus=ANALYZED`, after summary의 exact 상태가 현재 최종 상태와 일치
- 두 metadata 모두 exact `sourceRiskLevel`, `detectionResultId`,
  `detectionResultVersion` field와 현재 값을 사용

recovery는 이 검사에서 `audit_id`, actor, `trace_id`, `changed_at`을 비교하지 않으며
CASE_CREATED·CASE_TRANSACTION_LINKED 같은 사건 action의 존재·내용도 다시 검사하지
않는다.

일반 `audit_log`는 transaction/case 업무 상태 변경, finalization과
InvestigationNote 관련 감사를 저장한다. V7의 DB trigger가 UPDATE·DELETE를 거부한다.
반면 `idempotency_recovery_audit_log`는 inspect가 아니라 실제 단건 `recover`의
성공·거부·내부 실패 판정 결과를 업무 감사와 분리해 저장한다. recovery audit의
append-only는 `@Immutable`, lifecycle mutation 거부와 insert-only Repository인
application 경계이며 V9에는 UPDATE·DELETE 거부 DB trigger가 없다. V9의 CHECK·Unique·
index 책임을 DB trigger 보장으로 확대하지 않는다.

계속 미구현인 사건 범위는 기존 사건에 다른 거래 추가, 사건 병합·분리다. Snapshot
v2 one-shot recovery는 구현되어 있지만 이 문서는 Snapshot 자체를 저장하지 않는다.

## 8. 조회 경계

사건 목록은 `fraud_case` Page query와 해당 페이지의 PK만 사용하는
`case_transaction GROUP BY fraud_case_id` 집계를 분리한다. 목록 항목별 count query,
Entity collection 추가와 전체 연관 거래 로딩은 사용하지 않는다. 관련 거래
`transactionId` 필터는 `EXISTS`로 처리하며 거래 UUID unique와
`ix_case_transaction_transaction_case`를 사용한다.

기본 정렬은 `last_changed_at, id` 같은 방향이며 내부 `id`는 API에 노출하지 않는다.
정확한 필터·응답·오류 계약은
[`../03-api/case-audit-api.md`](../03-api/case-audit-api.md)를 따른다.

위험 대응·사건·감사 finalization은 public 거래 처리에 연결되었다. 다만 finalization
commit만으로 HTTP 성공이 확정되는 것은 아니며 별도 Snapshot v2 completion이 성공해야
신규 `201 Created`를 반환한다. 사건 조사 lifecycle 전체가 자동 완료되는 것도 아니다.

## 9. 조사 mutation 동시성

상태·담당자 명령은 `caseId` 일반 조회 후 body의 `expectedVersion`을 먼저 비교하고
Entity 업무 메서드를 적용한다. `JpaRepository.flush()`에서 실제 `@Version` 증가를
확정한 뒤 같은 기본 `REQUIRED` 트랜잭션의 AuditLog append·flush를 수행한다.
row lock과 자동 retry는 사용하지 않으며 optimistic conflict 또는 감사 INSERT 실패
시 사건과 감사 변경을 모두 rollback한다.

신규 write API는 `assignee_ref`에 canonical lowercase UUID v4만 허용하지만 V6의
DB-wide 1~128자 trimmed check는 기존 행과 조회 계약을 위해 변경하지 않는다.

resolution도 같은 낙관적 잠금 경계를 사용한다. 종료는 `fraud_case` 한 행만
변경하며 `financial_transaction`, 위험 필드와 `case_transaction`을 변경하지 않는다.
V12는 AuditLog check만 확장하므로 `fraud_case` 테이블·컬럼·제약·인덱스에는 변경이
없다.

## 10. investigation_note

Flyway V13은 내부 `BIGINT identity` PK와 외부 UUID v4 `note_id`, `fraud_case_id`
FK(`ON DELETE RESTRICT`), `TEXT content`,
`TIMESTAMPTZ(6) created_at`을 추가한다. `content`는 DB `char_length` 1..4,000,
Unicode whitespace-only 및 CR/LF 이외 제어문자 방어 CHECK를 적용한다.
V13 당시 note author와 `CASE_NOTE_CREATED` audit actor는
`SYSTEM/finguardops-backend` 조합만 허용했다. V14가 두 CHECK를 교체해 기존 SYSTEM
조합을 유지하면서 `USER/canonical lowercase RFC 4122 UUID v4` note author와 audit
actor를 허용한다. SQL NULL·문자열 null·교차 조합·비정규 UUID는 거부하고 기존 행은
재작성하지 않는다.

`ix_investigation_note_case_created(fraud_case_id, created_at, id)`는 asc·desc Page와
같은 시각의 내부 tie-breaker를 지원한다. 내부 `id`는 API에 노출하지 않는다.
`InvestigationNote`는 Hibernate `@Immutable`, lifecycle callback과 전용 table trigger로
UPDATE·DELETE를 거부한다. `FraudCase`에는 note collection을 추가하지 않는다.

생성은 부모 `FraudCase.last_changed_at`을 단일 `activityTime`으로 갱신하고 부모를
먼저 flush한 뒤 note와 AuditLog를 flush한다. 감사 실패를 포함한 어느 단계의 실패도
부모 version·시각·note·감사를 모두 rollback한다. API 계약은
[`../03-api/case-audit-api.md#11-조사-메모-생성`](../03-api/case-audit-api.md#11-조사-메모-생성)을 따른다.

## 11. Flyway V1~V14 책임 경계

세 DB 상세 문서에서 참조하는 migration 계보는 다음과 같이 귀속한다.

| Version | 실제 책임 |
| --- | --- |
| V1 | `financial_transaction`, `idempotency_record`, `response_snapshot`과 기본 제약·index |
| V2 | `behavior_event` |
| V3 | DetectionResult/Evidence와 거래 채택·risk 컬럼, 관련 제약·trigger |
| V4 | behavior-event Rule 조회 index |
| V5 | FraudRule·RuleVersion과 Evidence의 nullable RuleVersion FK·snapshot 검증 |
| V6 | `fraud_case`, `case_transaction`과 관계·index |
| V7 | 일반 `audit_log`, finalization 감사 제약·index와 DB append-only trigger |
| V8 | `idempotency_record`의 FAILED 상태 CHECK를 typed External Risk Failure Snapshot 허용 형태로 교체 |
| V9 | `idempotency_recovery_audit_log`와 recovery audit/candidate index. recovery audit DB append-only trigger는 없음 |
| V10 | 사건 무필터 변경 시각 조회 index |
| V11 | 일반 AuditLog의 사건 상태·담당자 workflow action/reason/summary 제약 확장 |
| V12 | 일반 AuditLog의 사건 resolution action/reason/summary 제약 확장 |
| V13 | InvestigationNote table·index·append-only trigger와 note 감사 schema. 당시 author/actor는 SYSTEM-only |
| V14 | 기존 SYSTEM 조합을 유지하며 note USER author와 USER audit actor CHECK 허용 확장 |

후속 version이 선행 migration 파일을 소급 수정한 것으로 해석하지 않는다. 특히 V9의
recovery 감사와 V7의 일반 업무 감사, V13의 SYSTEM-only 도입과 V14의 USER 확장을
각각 분리한다.
