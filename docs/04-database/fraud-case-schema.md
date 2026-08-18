# 사건 영속 기반 스키마

## 1. 범위

이 문서는 Issue #154에서 구현한 `FraudCase`와 첫 거래 연결의 PostgreSQL 물리
계약을 정의한다. Flyway V6는 기존 V1~V5를 수정하지 않고 `fraud_case`와
`case_transaction`을 추가한다.

구현 범위는 사건 영속 모델, 거래 연결, 중복 연결 제약과 내부 persistence
boundary이다. 사건 조사 상태 전이 Service, 기존 사건에 다른 거래 추가, 사건
병합·분리, AuditLog, 공개 API, 최종 거래 상태 전이와 Snapshot v2는 포함하지
않는다.

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

## 7. 미구현 경계

- AuditLog Entity·테이블·action Enum
- 위험 대응 결과와 최종 거래 상태 적용
- 사건 조사 상태 전이와 종료
- 기존 사건에 추가 거래 연결
- 사건 병합·분리
- 공개 사건 Controller·DTO
- 거래 접수 전체 오케스트레이션과 Snapshot v2

AuditLog가 구현되기 전에는 이 영속 경계를 사건 업무 전체 완료로 간주하지 않는다.
