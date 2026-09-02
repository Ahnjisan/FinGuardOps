# AuditLog 영속 스키마

## 1. 범위

이 문서는 Issue #156의 append-only `AuditLog`, Issue #209의 사건 workflow 감사와 Issue #211의 resolution 감사
확장을 정의한다. Flyway V7은 테이블·Index·UPDATE/DELETE 차단 trigger를 추가하고,
V11은 V1~V10을 수정하지 않고 workflow check를 확장하며 V12는 V1~V11을 수정하지 않고 resolution action·reason·snapshot check만 additive 확장한다.

구현 범위는 typed 감사 INSERT, 내부 위험 대응 최종화 및 성공한 사건 조사
상태·담당자·종료 명령의 실제 통합까지다. 공개 조회 API, 실제 인증 사용자 연결과 실패·
거부 요청 별도 감사는 포함하지 않는다.

관련 논리·API 계약은 다음 문서를 함께 따른다.

- [`../02-architecture/domain-erd.md`](../02-architecture/domain-erd.md)
- [`../03-api/case-audit-api.md`](../03-api/case-audit-api.md)
- [`../03-api/domain-event-contracts.md`](../03-api/domain-event-contracts.md)
- [`fraud-case-schema.md`](fraud-case-schema.md)

## 2. 식별자와 Actor

- 내부 PK `id`와 외부 업무 식별자 `audit_id`를 분리한다.
- `audit_id`는 Service가 생성한 UUID v4다.
- `SYSTEM` actor의 `actor_id`는 `finguardops-backend`로 고정한다.
- `USER` actor의 `actor_id`는 canonical lowercase 내부 사용자 업무 UUID v4만
  허용한다. Actor Directory FK는 추가하지 않았다.
- 외부 인증 Provider subject는 향후 인증 계층이 내부 사용자 UUID v4로
  매핑한 후 전달해야 한다. 사용자명, 이메일, 사번, 전화번호 원문은
  `actor_id`로 저장하지 않는다.
- 실제 인증·인가 기반 `USER` actor 연결은 아직 구현하지 않았다.

## 3. audit_log

| 컬럼 | 타입 | null | 계약 |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY` | 불가 | 내부 PK |
| `audit_id` | `UUID` | 불가 | UUID v4, Unique |
| `actor_type` | `VARCHAR(16)` | 불가 | `SYSTEM`, `USER` |
| `actor_id` | `VARCHAR(128)` | 불가 | actor type별 Check |
| `action` | `VARCHAR(64)` | 불가 | 승인 action Check |
| `reason_code` | `VARCHAR(64)` | 불가 | 승인 reason code와 action 조합 Check |
| `target_type` | `VARCHAR(32)` | 불가 | `FINANCIAL_TRANSACTION`, `FRAUD_CASE` |
| `target_id` | `UUID` | 불가 | 대상의 외부 업무 UUID v4 |
| `transaction_id` | `UUID` | 가능 | 거래 조회 문맥과 FK |
| `case_id` | `UUID` | 가능 | 사건 조회 문맥과 FK |
| `trace_id` | `VARCHAR(64)` | 가능 | 제공 시 8~64자 승인 패턴 |
| `before_value_summary` | `JSONB` | 가능 | action별 제한된 이전 값 |
| `after_value_summary` | `JSONB` | 가능 | action별 제한된 변경 값 |
| `metadata` | `JSONB` | 불가 | 기본 `{}`, action별 allowlist |
| `changed_at` | `TIMESTAMPTZ(6)` | 불가 | UTC Clock 기반 변경 시각 |

`changed_at`은 호출자가 입력하지 않는다. `AuditLogPersistenceService`가 주입된
UTC `Clock`을 한 번 호출하고 마이크로초로 정규화한다.

## 4. 승인 Enum과 조합

최초 action과 reason code 조합은 다음으로 제한한다.

| action | reasonCode | targetType | 필수 문맥 |
| --- | --- | --- | --- |
| `CASE_CREATED` | `CASE_REQUIRED_BY_RISK_POLICY` | `FRAUD_CASE` | `targetId=caseId`, `transactionId`, `caseId` |
| `CASE_TRANSACTION_LINKED` | `CASE_REQUIRED_BY_RISK_POLICY` | `FRAUD_CASE` | `targetId=caseId`, `transactionId`, `caseId` |
| `TRANSACTION_RISK_RESPONSE_APPLIED` | `RISK_RESPONSE_DECIDED_BY_POLICY` | `FINANCIAL_TRANSACTION` | `targetId=transactionId`, `transactionId` |
| `TRANSACTION_STATUS_CHANGED` | `TRANSACTION_FINALIZED_BY_RISK_POLICY` | `FINANCIAL_TRANSACTION` | `targetId=transactionId`, `transactionId` |
| `CASE_STATUS_CHANGED` | `CASE_REVIEW_STARTED`, `CASE_ADDITIONAL_INFORMATION_REQUESTED`, `CASE_REVIEW_RESUMED` | `FRAUD_CASE` | `targetId=caseId`, `caseId`, `transactionId=null` |
| `CASE_ASSIGNEE_CHANGED` | `CASE_ASSIGNEE_ASSIGNED`, `CASE_ASSIGNEE_CHANGED`, `CASE_ASSIGNEE_RELEASED` | `FRAUD_CASE` | `targetId=caseId`, `caseId`, `transactionId=null` |
| `CASE_RESOLVED` | `CASE_RESOLUTION_COMPLETED` | `FRAUD_CASE` | `targetId=caseId`, `caseId`, `transactionId=null` |

`transaction_id`는 `financial_transaction(transaction_id)`, `case_id`는
`fraud_case(case_id)`를 `ON DELETE RESTRICT`로 참조한다. 다형적인
`target_type + target_id`에는 직접 FK를 두지 않고 승인된 문맥 조합 Check로
보강한다.

## 5. JSON 계약

세 JSON 값은 object 또는 승인된 nullable 상태여야 한다. 배열, 중첩 객체,
null value와 임의 key를 허용하지 않는다. JSON 크기는 PostgreSQL
`jsonb::text` UTF-8 표현을 기준으로 세 object 합계 최대 8192 byte까지
허용하고 8193 byte부터 거부한다. Java는 허용된 ASCII UUID·Enum,
boolean, 양의 정수 scalar 계약에서 object 구분자, `: `, `, ` 공백을
포함해 PostgreSQL과 같은 byte 수를 계산한다.

`AuditLogDraft`는 첫 deep copy 전에 복사 없이 bounded traversal을 수행한다.
root object, object당 최대 8 field, 중첩·배열·null 금지, ASCII key·scalar,
scalar 길이와 세 JSON의 8192 byte 예산을 확인한 후에만 defensive deep
copy한다. 저장 경계와 Draft·Entity accessor의 defensive copy 계약은 유지한다.

| action | before | after | metadata allowlist |
| --- | --- | --- | --- |
| `CASE_CREATED` | null | `caseStatus=OPEN` | `detectionResultId`, `detectionResultVersion` |
| `CASE_TRANSACTION_LINKED` | null | `linked=true` | `detectionResultId`, `detectionResultVersion` |
| `TRANSACTION_RISK_RESPONSE_APPLIED` | nullable `riskResponseOutcome` | `riskResponseOutcome` | `sourceRiskLevel`, `detectionResultId`, `detectionResultVersion` |
| `TRANSACTION_STATUS_CHANGED` | `processingStatus` | `processingStatus` | `sourceRiskLevel`, `detectionResultId`, `detectionResultVersion` |
| `CASE_STATUS_CHANGED` | `caseStatus`, optional canonical UUID v4 `assigneeRef` | 동일 제한 필드 | 없음 (`{}`) |
| `CASE_ASSIGNEE_CHANGED` | `caseStatus`, optional canonical UUID v4 `assigneeRef` | 동일 제한 필드 | 없음 (`{}`) |
| `CASE_RESOLVED` | `caseStatus=IN_REVIEW`, canonical UUID v4 `assigneeRef` | `caseStatus=CLOSED`, 승인 `finalDisposition`, 동일 `assigneeRef` | 없음 (`{}`) |

UUID는 canonical lowercase UUID v4, version은 양의 32-bit 정수, Enum은 현재 Java
계약에 존재하는 값만 허용한다. 자유 텍스트 reason과 임의 metadata를 저장하지
않는다.

고객·계좌·기기·IP 원문, 인증정보, 요청·응답 원문, Prompt와 LLM 입출력,
내부 예외, 조사 메모, 원본 Idempotency-Key와 불필요한 거래 원문은 저장하지
않는다.

## 6. INSERT 전용 Persistence 경계

public 쓰기 경계는 다음과 같다.

```text
PersistedAuditLog append(AuditLogDraft draft)
```

- `@Transactional` 기본 `REQUIRED`를 사용한다.
- 외부 트랜잭션이 있으면 같은 트랜잭션에 참여한다.
- 저장 실패는 catch하거나 성공으로 변환하지 않는다.
- `REQUIRES_NEW`를 사용하지 않는다.
- Entity 대신 식별자와 문맥만 포함한 immutable record를 반환한다.
- Repository는 Spring Data `JpaRepository`를 노출하지 않고
  `EntityManager.persist()`와 `flush()`만 사용한다.

`RiskResponseFinalizationService`는 거래 잠금·검증과 필요한 사건 생성 또는 재사용,
거래 최종화·flush 뒤 이 경계를 같은 REQUIRED 트랜잭션에서 호출한다. AuditLog
append 실패는 전파되어 사건·연결·거래 변경과 이전 감사 INSERT까지 모두
rollback한다.

감사 append 순서와 수는 다음과 같다.

1. 신규 사건: `CASE_CREATED`, `CASE_TRANSACTION_LINKED`,
   `TRANSACTION_RISK_RESPONSE_APPLIED`, `TRANSACTION_STATUS_CHANGED`
2. 기존 활성 사건 재사용: 거래 감사 2건만 기록
3. LOW·MEDIUM: 거래 감사 2건만 기록

기존 활성 사건 재사용은 사건 또는 연결의 새 변경이 아니므로 사건 감사를 중복
append하지 않는다.

사건 workflow는 성공한 명령마다 정확히 1건만 append한다. resolution은 성공한
종료에만 `SYSTEM/finguardops-backend` actor로 정확히 1건을 append한다. 사건 flush와 감사
append·flush는 같은 기본 `REQUIRED` 트랜잭션에 참여하며 optimistic conflict 또는
감사 저장 실패 시 모두 rollback한다. 상태·담당자 snapshot의 reasonCode 조합은
Java 정책과 V11/V12 check에서 함께 제한한다. stale·CLOSED·금지 상태·validation
실패에는 감사를 만들지 않는다.

## 7. append-only 보장과 한계

Java 방어:

- Hibernate `@Immutable`
- setter·상태 변경·삭제 메서드 없음
- 모든 감사 컬럼 `updatable=false`
- `@PreUpdate`, `@PreRemove` 거부
- INSERT 전용 Repository

PostgreSQL 방어:

- `tr_audit_log_reject_mutation`이 일반 UPDATE·DELETE를 항상 거부한다.
- 업무 FK는 `ON DELETE RESTRICT`를 사용한다.

현재 Flyway와 애플리케이션은 같은 datasource 계정을 사용한다. 따라서 V7은 일반
UPDATE·DELETE를 차단하지만 table owner의 trigger disable, DDL과 TRUNCATE까지
막지는 않는다. runtime DB role 분리, 보존·파기, purge와 partition 정책은 이번
범위가 아니다.

## 8. Index

- `uq_audit_log_audit_id`
- `ix_audit_log_case_changed(case_id, changed_at DESC, id DESC)` partial
- `ix_audit_log_transaction_changed(transaction_id, changed_at DESC, id DESC)` partial
- `ix_audit_log_target_changed(target_type, target_id, changed_at DESC, id DESC)`
- `ix_audit_log_trace_changed(trace_id, changed_at DESC)` partial

deduplication key와 action 단독 Index는 추가하지 않는다. 후속 호출자는 실제 업무
변경이 발생했을 때만 append해야 하며, 현재 업무 멱등성·상태·version·도메인
제약을 사용한다.

## 9. 사건 조사 메모 감사

V13은 `CASE_NOTE_CREATED/CASE_INVESTIGATION_NOTE_ADDED`를 additive 확장한다.
`targetType=FRAUD_CASE`, `targetId=caseId`, `transactionId=null`,
`actorType=SYSTEM`, `actorId=finguardops-backend`, before/after summary는 SQL NULL이다.
metadata는 exact `{ "noteId": "<canonical lowercase UUID v4>" }` 한 키만 허용한다.
PostgreSQL CHECK는 key 존재, exact key set, JSON string type과 UUID 형식을 독립적으로
검증해 JSON null의 CHECK UNKNOWN 통과를 막는다.

메모 content, 길이, hash, preview, 고객·계좌·거래·credential 원문과 내부 note PK는
감사에 저장하지 않는다. 성공 생성만 정확히 1건이며 validation·미존재·상태·동시성
충돌은 0건이다. note 또는 감사 실패 시 부모 사건 version과 시각까지 rollback한다.
관련 API는
[`../03-api/case-audit-api.md#11-조사-메모-생성`](../03-api/case-audit-api.md#11-조사-메모-생성)을 따른다.

## 10. 미구현 경계

- 거부 감사와 별도 commit 경계
- 공개 AuditLog 조회 API
- 인증·인가 기반 USER actor
- 실패·거부·stale 사건 요청 별도 감사
- deduplication key
- runtime DB role 분리
- 보존·파기
- Snapshot v2
