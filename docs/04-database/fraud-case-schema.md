# 사건 영속 기반 스키마

## 1. 범위

이 문서는 Issue #154의 `FraudCase`·첫 거래 연결, Issue #207의 사건 조회 및
Issue #209의 조사 상태·담당자 변경 및 Issue #211의 사건 종료 경계를 정의한다. Flyway V6는 기존
V1~V5를 수정하지 않고 `fraud_case`와 `case_transaction`을 추가하며, Flyway V10은
기존 migration을 수정하지 않고 무필터 변경 시각 조회 인덱스를 추가한다.

구현 범위는 사건 영속 모델, 거래 연결, 중복 연결 제약과 내부 persistence
boundary이며, 위험 대응 최종화 경계가 이를 재사용해 신규 사건·첫 연결 또는 기존
활성 사건을 거래 최종 상태·대응 결과·AuditLog와 같은 REQUIRED 트랜잭션에서
확정한다. Issue #209의 상태·담당자 mutation과 Issue #211의 사건 종료를 포함하지만 기존 사건에
다른 거래 추가, 사건 병합·분리, 거래 접수 전체 연결과 Snapshot v2는 포함하지
않는다. AuditLog 계약은
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

## 7. 미구현 경계

- 기존 사건에 추가 거래 연결
- 사건 병합·분리
- 거래 접수 전체 오케스트레이션과 Snapshot v2

## 8. 조회 경계

사건 목록은 `fraud_case` Page query와 해당 페이지의 PK만 사용하는
`case_transaction GROUP BY fraud_case_id` 집계를 분리한다. 목록 항목별 count query,
Entity collection 추가와 전체 연관 거래 로딩은 사용하지 않는다. 관련 거래
`transactionId` 필터는 `EXISTS`로 처리하며 거래 UUID unique와
`ix_case_transaction_transaction_case`를 사용한다.

기본 정렬은 `last_changed_at, id` 같은 방향이며 내부 `id`는 API에 노출하지 않는다.
정확한 필터·응답·오류 계약은
[`../03-api/case-audit-api.md`](../03-api/case-audit-api.md)를 따른다.

내부 위험 대응·사건·감사 최종화는 구현되었지만, 이를 사건 업무 전체나 공개 거래
처리 완료로 간주하지 않는다.

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
V14는 `SYSTEM/finguardops-backend`와 `USER/canonical lowercase UUID v4` 작성자 조합만
허용하며 SQL NULL·문자열 null·교차 조합·비정규 UUID를 거부한다. 기존 행은 재작성하지 않는다.

`ix_investigation_note_case_created(fraud_case_id, created_at, id)`는 asc·desc Page와
같은 시각의 내부 tie-breaker를 지원한다. 내부 `id`는 API에 노출하지 않는다.
`InvestigationNote`는 Hibernate `@Immutable`, lifecycle callback과 전용 table trigger로
UPDATE·DELETE를 거부한다. `FraudCase`에는 note collection을 추가하지 않는다.

생성은 부모 `FraudCase.last_changed_at`을 단일 `activityTime`으로 갱신하고 부모를
먼저 flush한 뒤 note와 AuditLog를 flush한다. 감사 실패를 포함한 어느 단계의 실패도
부모 version·시각·note·감사를 모두 rollback한다. API 계약은
[`../03-api/case-audit-api.md#11-조사-메모-생성`](../03-api/case-audit-api.md#11-조사-메모-생성)을 따른다.
