# ADR-007: External Risk 선행 실패 멱등 저장·재생 계약

- 상태: Accepted
- 결정일: 2026-08-27
- 결정자: Project Owner
- 관련 Issue: `#170 [Docs] External Risk 선행 실패 멱등 저장·재생 계약 확정`
- 관련 문서:
  - [`ADR-003-transaction-processing-boundary.md`](./ADR-003-transaction-processing-boundary.md)
  - [`ADR-004-idempotency-response-snapshot-transition.md`](./ADR-004-idempotency-response-snapshot-transition.md)
  - [`ADR-006-final-transaction-success-and-idempotency-recovery.md`](./ADR-006-final-transaction-success-and-idempotency-recovery.md)
  - [`../01-requirements/external-risk-lookup-policy.md`](../01-requirements/external-risk-lookup-policy.md)
  - [`../01-requirements/external-risk-rule-analysis-input-contract.md`](../01-requirements/external-risk-rule-analysis-input-contract.md)
  - [`../03-api/transaction-detection-api.md`](../03-api/transaction-detection-api.md)
  - [`../04-database/transaction-intake-schema.md`](../04-database/transaction-intake-schema.md)

## 1. 배경

FinGuardOps에는 거래 생성 요청의 Validation·fingerprint·Idempotency 선점과
`RECEIVED` 거래 저장, External Risk Port·정책 Service·local/dev/test Mock,
성공 `ExternalRiskSnapshot`을 내부 Rule 분석 v2 경계에 전달하는 per-invocation
coordinator가 구현되어 있다.

ADR 작성 당시 public `POST /api/v1/transactions`와 이 coordinator는 연결되어 있지
않았다. 당시 public 거래 접수는 `RECEIVED` 거래, 성공 Snapshot v1과 멱등 `COMPLETED`를
하나의 짧은 트랜잭션에서 확정한다. 내부 coordinator를 직접 또는 동시에 호출하면
각 호출이 External Risk Provider를 호출할 수 있고, 기존 거래 잠금은 그 뒤 Rule
분석 시작의 단일 승자만 보장한다.

목표 public 동기 거래 흐름에서는 거래가 `RECEIVED`로 commit된 뒤 활성 DB
트랜잭션 없이 External Risk를 먼저 조회한다. 이 선행 단계가 실패하면 거래와
Idempotency 상태, 동일 요청 재호출의 Provider 호출 여부와 공개 응답을 결정적으로
고정해야 한다.

## 2. ADR 작성 당시 구현 상태

이 절은 ADR 작성 당시의 역사적 상태다. 현재 상태는 23절을 따른다. 당시 구현된
범위는 다음과 같다.

- public `POST /api/v1/transactions`
- 요청 Validation과 정규화 SHA-256 fingerprint
- `(operation_scope, idempotency_key)` Unique Insert 기반 단일 선점
- `IN_PROGRESS`, `COMPLETED`, `FAILED` 상태와 기존 code-only 실패 재생 기반
- `RECEIVED` 거래·성공 Snapshot v1·멱등 `COMPLETED`의 원자적 저장
- External Risk Port·Policy·local/dev/test 결정적 Mock
- 거래 read 뒤 트랜잭션 밖 External Risk를 호출하고 성공 Snapshot을 내부
  `analyzeV2(...)`에 전달하는 per-invocation coordinator
- Rule 분석 v2 시작·완료·채택·실패 persistence 경계
- 위험 대응·필요한 사건·AuditLog의 내부 원자적 최종화 경계

당시 구현되지 않은 범위는 다음과 같다.

- 실제 External Risk HTTP Provider와 production coordinator Bean
- public intake와 External Risk coordinator·Rule v2·위험 대응 최종화의
  end-to-end 연결
- 거래를 연결한 채 Idempotency를 `IN_PROGRESS`로 유지하는 목표 접수 경계
- External Risk Failure Snapshot과 codec·decoder
- External Risk 전용 공개 오류 mapper
- 동일 멱등 요청의 External Risk 실패 저장·재생
- 최종 동기 응답과 성공 Snapshot v2
- Failure Snapshot을 허용하는 신규 Flyway Migration
- crash·완료 간극 운영 복구 명령과 운영 메트릭

이 ADR의 Accepted 상태는 문서 결정을 승인했다는 뜻이며 위 Java·DB·운영 구현이
완료되었다는 뜻이 아니다.

## 3. 해결해야 하는 문제

다음을 하나의 공식 계약으로 확정해야 한다.

- External Risk Provider 호출 단일 승자의 소유자
- 선점·거래 저장·외부 호출·실패 저장의 트랜잭션 순서
- 여섯 typed failure category의 terminal 정책
- 성공 Snapshot v1·v2와 구분되는 실패 Snapshot type·version
- 최초 실패와 같은 멱등 요청 재생의 HTTP·오류·trace 의미
- 동시 요청과 `IN_PROGRESS` 응답
- Provider 호출 여부를 알 수 없는 crash 상태의 불변식
- 현재 DB 제약과 목표 Failure Snapshot 사이의 Migration 전제
- Provider 원문·금융 참조값·low-level 예외의 저장·노출 금지

## 4. 결정

External Risk Provider 호출 단일 승자는 public transaction intake의 Idempotency
claim이 소유한다. 정상적으로 확정된 여섯 External Risk typed failure는 모두 같은
operation scope·Idempotency-Key에서 terminal `FAILED`다. 같은 key·fingerprint
재요청은 Provider, FastAPI와 위험 대응 최종화를 호출하지 않고 저장된 안전한 실패
응답을 재생한다.

성공 Snapshot v1·v2를 실패에 재사용하지 않는다. 별도
`external-risk-failure` type과 `transaction-create-error-v1` response schema,
`external-risk-failure-snapshot-envelope-v1` codec을 사용한다.

Provider 호출 여부나 실패 저장 commit을 확인할 수 없는 상태에서는 terminal 실패를
추측하지 않는다. Idempotency는 `IN_PROGRESS`로 남을 수 있고 같은 key는 즉시 409로
거부하며 Provider를 자동 재호출하지 않는다.

## 5. Validation과 fingerprint 순서

처리 순서는 Validation이 Idempotency 선점보다 먼저다.

```text
헤더·JSON 형식 Validation
→ 거래 DTO·도메인 Validation
→ 검증·정규화한 열 개 필드의 fingerprint 계산
→ Idempotency claim
```

fingerprint에는 다음 필드를 고정 순서로 포함한다.

```text
transactionId
transactionType
amount
currencyCode
occurredAt
externalCustomerRef
senderAccountRef
recipientAccountRef
channel
deviceRef
```

기존 UUID·금액·UTC Instant·nullable JSON null 정규화와 UTF-8 JSON의 SHA-256
소문자 16진수 64자 계약을 유지한다. Validation 실패는 claim 전에 발생하므로
Transaction, Idempotency record와 Failure Snapshot을 생성하지 않는다.

## 6. Idempotency 단일 승자

operation scope는 기존 `POST:/api/v1/transactions`를 유지한다. Spring Boot의
public transaction intake가 Idempotency-Key 검증, fingerprint 비교와
`(operation_scope, idempotency_key)` Unique Insert를 소유한다.

- 최초 Insert 승자만 목표 end-to-end 흐름을 시작한다.
- 같은 key·같은 fingerprint의 `IN_PROGRESS` 요청은 즉시
  `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`다.
- 같은 key·다른 fingerprint는 terminal 상태와 관계없이
  `409 IDEMPOTENCY_KEY_CONFLICT`다.
- `COMPLETED`는 저장된 성공 Snapshot을 재생한다.
- External Risk Failure Snapshot이 있는 `FAILED`는 저장된 안전한 실패를 재생한다.

External Risk Policy, per-invocation coordinator, Rule 분석 시작의 거래 잠금과
FastAPI 호출 경계는 Provider 호출 단일성을 보장하지 않는다. 거래 잠금은 External
Risk 성공 뒤 Rule 분석 시작의 단일 승자만 보장한다.

## 7. 목표 transaction sequence

목표 순서는 다음과 같다.

```text
헤더·요청 Validation
→ fingerprint 계산
→ Idempotency IN_PROGRESS 단일 승자 선점 commit
→ 거래 RECEIVED 저장
→ Idempotency record와 거래 연결
→ RECEIVED와 연결 상태 commit
→ 활성 DB transaction 없이 External Risk Provider 최대 1회 호출
→ External Risk 성공 시 기존 Rule 분석 v2 경계
→ External Risk 확정 실패 시 Failure Snapshot과 FAILED 저장 commit
→ 같은 key·fingerprint 재요청은 Provider 0회로 저장 실패 재생
```

선점, `RECEIVED`·거래 연결, terminal 실패 저장과 재생 read는 각각 짧은 DB
트랜잭션이어야 한다. External Risk 호출 동안 DB write transaction, 거래 행 잠금,
Idempotency 행 잠금과 장시간 read transaction을 유지하지 않는다.

ADR 작성 당시 구현은 다음과 달랐다.

```text
Validation
→ fingerprint
→ IN_PROGRESS 선점
→ RECEIVED 거래·성공 Snapshot v1·COMPLETED 원자적 저장
```

이 차이는 Issue #178에서 해소되었다.

## 8. terminal failure category

다음 `ExternalRiskLookupException` category는 실패 저장 commit이 확인된 경우 모두
terminal `FAILED`다.

```text
TIMEOUT
UNAVAILABLE
INVALID_REQUEST
UNSUPPORTED_CAPABILITY
INVALID_RESPONSE
TRANSFORMATION_ERROR
```

같은 key에서는 원인이 사라졌더라도 External Risk를 자동 재실행하지 않는다. 새
Idempotency-Key로 같은 거래를 다시 접수하는 방식도 `transactionId` Unique 제약과
충돌하므로 공식 재처리 수단이 아니다. 재처리는 후속 Issue에서 별도 operation
scope의 승인된 복구·재분석 명령으로 설계한다.

자동 retry, fallback, cache와 stale data를 추가하지 않고 실패를 `UNMATCHED`,
정상 결과, 0점 또는 `LOW`로 변환하지 않는다.

예상하지 못한 일반 `RuntimeException`은 여섯 category 중 하나로 임의 변환하거나
External Risk Failure Snapshot 대상으로 확장하지 않는다.

## 9. Failure Snapshot exact schema

신규 typed External Risk 실패는 다음 canonical envelope를 목표로 한다.

```json
{
  "snapshotType": "external-risk-failure",
  "responseBody": {
    "code": "DEPENDENCY_TIMEOUT",
    "message": "탐지 서비스를 사용할 수 없습니다.",
    "fieldErrors": []
  },
  "httpStatus": 503,
  "failureCategory": "TIMEOUT",
  "responseSchemaVersion": "transaction-create-error-v1",
  "codecVersion": "external-risk-failure-snapshot-envelope-v1",
  "finalizedAt": "2026-08-27T00:00:00Z"
}
```

계약은 다음과 같다.

- 최상위와 `responseBody`는 위 필드만 정확히 허용하는 strict object다.
- `responseBody.code`와 `idempotency_record.failure_code`는 정확히 일치한다.
- `failureCategory`는 위 여섯 값 중 하나이며 내부 영속·관측용이다.
- `fieldErrors`는 빈 배열이다.
- `finalizedAt`은 UTC ISO-8601 Instant다.
- `finalizedAt`과 record의 `finished_at`은 같은 실패 확정 시각을 사용한다.
- canonical JSON의 UTF-8 직렬화 크기는 최대 4 KiB다.
- terminal 저장 후 Snapshot의 모든 값은 불변이다.
- 알 수 없는 type·version·필드, 타입 오류, 크기 초과와 손상 데이터는 fail-closed다.
- JSON의 의미적 재생을 보장하며 원본 byte 배열·공백·필드 출력 순서를 보존하지 않는다.

현재 성공 legacy·v1·v2 decoder의 의미를 바꾸거나 failure decoder로
재사용하지 않는다.

## 10. 공개 오류 매핑

공개 API의 authoritative 세부 계약은
[`transaction-detection-api.md`](../03-api/transaction-detection-api.md)를 따른다.

| 내부 category | HTTP | 공개 code | 공개 안전 message |
| --- | ---: | --- | --- |
| `TIMEOUT` | 503 | `DEPENDENCY_TIMEOUT` | `탐지 서비스를 사용할 수 없습니다.` |
| `UNAVAILABLE` | 503 | `DEPENDENCY_UNAVAILABLE` | `탐지 서비스를 사용할 수 없습니다.` |
| `INVALID_REQUEST` | 500 | `INTERNAL_ERROR` | `요청을 처리하는 중 오류가 발생했습니다.` |
| `UNSUPPORTED_CAPABILITY` | 500 | `INTERNAL_ERROR` | `요청을 처리하는 중 오류가 발생했습니다.` |
| `INVALID_RESPONSE` | 500 | `INTERNAL_ERROR` | `요청을 처리하는 중 오류가 발생했습니다.` |
| `TRANSFORMATION_ERROR` | 500 | `INTERNAL_ERROR` | `요청을 처리하는 중 오류가 발생했습니다.` |

내부 category와 Provider 상세를 공개 code·message에 포함하지 않는다. 이 표는 후속
Java mapper 구현 기준이며 현재 전용 mapper가 구현되었다는 의미가 아니다.

## 11. trace와 exact replay 범위

ADR-004와 ADR-006의 trace 원칙을 유지한다.

- 최초 실패 `traceId`를 Failure Snapshot에 저장하지 않는다.
- 최초 실패 trace를 replay 요청의 trace로 재사용하지 않는다.
- replay body와 `X-Trace-Id`에는 현재 재요청의 `traceId`를 결합한다.
- exact replay는 HTTP status, 공개 code, 안전 message와 빈 `fieldErrors`의 의미적
  동일성을 뜻한다.
- `traceId`, HTTP 추적 헤더, JSON byte ordering·공백은 exact replay 범위가 아니다.
- 공개 `replayed` 필드를 추가하지 않는다.

최초 실패 trace의 영속 보존이 필요하면 response Snapshot이 아닌 별도 보안 승인된
운영 추적 데이터로 후속 검토한다.

## 12. 순차·동시 재호출

| 상황 | Provider 호출 | 처리 |
| --- | ---: | --- |
| 최초 승자 | 최대 1회 | 성공 또는 확정 실패까지 진행 |
| 같은 key·fingerprint, `IN_PROGRESS` | 0회 | 즉시 `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` |
| 같은 key·다른 fingerprint | 0회 | `409 IDEMPOTENCY_KEY_CONFLICT` |
| 같은 key·fingerprint, External Risk `FAILED` | 0회 | 저장된 안전한 실패 응답 재생 |
| 같은 key·fingerprint, `COMPLETED` | 0회 | 저장된 성공 응답 재생 |

동시 요청을 위해 blocking wait, 제한 시간 대기, DB lock 장기 유지, 자동 polling,
자동 retry와 Provider 재호출을 추가하지 않는다.

## 13. crash·완료 간극

Issue #170은 상태, 탐지 기준, 불변식과 자동 재실행 금지만 결정한다. 복구 명령,
scheduler, batch와 자동화는 후속 Issue다.

운영 탐지 대상 후보는 다음과 같다.

- 오래 지속되는 `IN_PROGRESS`
- 거래 없이 남은 `IN_PROGRESS`
- `RECEIVED` 거래가 연결된 채 남은 `IN_PROGRESS`
- terminal 도메인 상태와 `IN_PROGRESS`가 함께 존재하는 상태
- External Risk failure writer 실패
- final success completion gap

Provider 호출 여부를 DB만으로 확정할 수 없는 `IN_PROGRESS` 상태에서는 Provider를
자동 재호출하지 않는다.

External Risk 실패 저장 직전 crash 또는 failure writer 실패에서는 typed failure가
durably confirmed된 것으로 간주하지 않고 exact External Risk 실패 replay를 제공하지
않는다. Idempotency가 `IN_PROGRESS`로 남을 수 있고 같은 key 재요청은 409다. 원본
예외를 유지하고 failure writer 오류는 동일 객체가 아닐 때 suppressed exception으로
보존한다. 최초 공개 응답을 만들 수 있는 경우 응답은 `500 INTERNAL_ERROR`다.

최종 업무 commit 뒤 성공 Snapshot v2 완료 간극은 ADR-006을 따른다. 이미 확정된
업무를 되돌리거나 외부 호출을 반복하지 않고, 후속 운영 복구가 검증된 상태에서
누락된 v2 완료만 복원한다.

## 14. 민감정보·로그 제한

Failure Snapshot에는 다음을 저장하지 않는다.

- `traceId`, `transactionId`, `evaluationCutoffAt`
- 원본 Idempotency-Key와 fingerprint
- Provider request·response body, 원문 code와 URL
- 고객·계좌·기기 reference와 IP·행동 원문
- low-level exception message와 stack trace
- 인증정보
- Provider 구현 클래스와 내부 설정

`transactionId`는 Idempotency record의 거래 FK로 확인한다.

예상된 typed External Risk failure의 후속 전용 안전 mapper는 category와 현재 trace만
승인된 형식으로 기록하고 Provider cause 전체를 generic stack trace 로그로 보내지
않아야 한다. 현재 로그 코드는 이 ADR에서 변경하지 않는다.

## 15. DB Migration 전제

ADR 작성 당시 V1과 Entity는 `FAILED`에서 `response_snapshot IS NULL`, non-null
`failure_code`, non-null `finished_at`을 강제하므로 Failure Snapshot을 저장할 수
없었다. 후속 Issue에서 신규 Flyway V8과 Java 구현을 적용했다.

- 적용 완료된 V1 Migration을 수정하지 않는다.
- 기존 `response_snapshot JSONB`를 재사용할 수 있으며 신규 컬럼은 필수가 아니다.
- 신규 Migration에서 `FAILED` 상태 필드 제약을 변경한다.
- legacy code-only `FAILED + response_snapshot NULL`을 계속 허용한다.
- 신규 typed External Risk failure는 strict Failure Snapshot과 거래 FK가 필수다.
- 기존 행을 backfill하지 않는다.
- Entity·codec·decoder·mapper·테스트는 후속 구현에서 변경한다. — Issue #170 완료

현재 terminal 불변성은 Entity 상태 검증, Service와 `PESSIMISTIC_WRITE` row lock
수준이다. 직접 SQL까지 막는 DB trigger 수준의 절대 불변성은 구현되지 않았다.
DB trigger 추가는 Issue #170과 후속 필수 구현 범위에 포함하지 않는다.

## 16. legacy 호환성

기존 strict legacy 성공 Snapshot과 성공 envelope v1·v2의 type·version·
decoder 의미를 변경하지 않는다. 기존 Snapshot을 Failure Snapshot으로 추측하거나
제자리 변환하지 않는다.

기존 code-only `FAILED` record의 nullable 거래 FK와 null `response_snapshot`을
허용하고 backfill하지 않는다. 기존 실패는 기존 `failure_code` whitelist 규칙으로
재생한다. 신규 typed failure와 legacy 실패는 Snapshot 존재와 strict type/version으로
명확히 구분한다.

알 수 없는 데이터는 현재 거래 상태나 Provider 재호출로 보정하지 않는다.

## 17. Issue #178 이전 구현과 목표 구현 구분

Issue #178 이전 public intake:

```text
Validation
→ fingerprint
→ IN_PROGRESS claim
→ RECEIVED 거래·성공 Snapshot v1·COMPLETED 원자적 commit
```

Issue #178에서 구현한 public intake:

```text
Validation
→ fingerprint
→ IN_PROGRESS claim commit
→ RECEIVED 거래와 Idempotency 연결 commit
→ 트랜잭션 밖 External Risk
→ 성공 흐름은 Rule v2·최종 업무·성공 Snapshot v2
→ 확정 실패는 Failure Snapshot·FAILED
```

당시에는 public API와 내부 coordinator의 존재만으로 production Provider, public
failure replay 또는 최종 동기 거래 완료를 의미하지 않았다. Issue #178은 별도의
테스트와 public 연결로 이 목표를 구현했다.

## 18. 장점

- 같은 멱등 요청이 External Risk 장애를 반복 호출하지 않는다.
- 성공 Snapshot과 실패 Snapshot의 의미·decoder가 분리된다.
- 내부 category를 보존하면서 공개 응답은 안전한 공통 code로 제한한다.
- 거래 `RECEIVED`, DetectionResult 미생성과 Provider 호출 결과 사이의 정합성
  경계를 명시한다.
- 불확실한 crash 상태에서 중복 외부 호출을 막는다.
- legacy 성공·실패 데이터를 수정하지 않고 하위 호환을 유지한다.

## 19. 단점·위험

- ADR 작성 당시 DB 제약과 Entity로는 목표 Snapshot을 저장할 수 없었으며, 현재는
  V8 Migration과 Java 구현으로 저장·strict 재생한다.
- 일시적 TIMEOUT·UNAVAILABLE도 같은 key에서 재실행되지 않으므로 별도 복구·재분석
  operation scope가 필요하다.
- crash 뒤 `IN_PROGRESS`가 장기 잔류할 수 있고 운영 복구 구현 전에는 자동 해소되지
  않는다.
- legacy code-only 실패와 신규 typed 실패 decoder를 함께 유지해야 한다.
- terminal 불변성은 현재 DB trigger로 절대 보장되지 않는다.

## 20. 후속 구현

아래 ADR 작성 당시 후속 목록 중 public 연결 항목은 Issue #178까지 완료되었다.

- public intake에서 `RECEIVED` 거래와 Idempotency 연결만 commit하는 새 경계 — 완료
- 실제 External Risk HTTP Provider와 production coordinator Bean — 완료
- public intake와 External Risk·Rule v2·위험 대응 최종화 연결 — 완료
- 신규 Failure Snapshot codec·strict decoder·4 KiB 검증 — 완료
- 신규 Flyway Migration과 Entity·Service·Repository 변경 — 완료
- External Risk typed failure 전용 안전 mapper·로그 처리 — 완료
- 최초·재생 응답과 동시 요청 통합 테스트 — 완료
- 오래된 `IN_PROGRESS`와 완료 간극 운영 탐지·복구 Issue
- 별도 operation scope의 승인된 복구·재분석 계약
- 최종 성공 Snapshot v2 구현 — 완료

## 21. 제외 범위

이 절은 ADR 결정 당시 구현 Issue의 제외 범위이며 현재 구현 상태 목록이 아니다.

- Java Production·Test 코드와 Flyway Migration 변경
- 실제 Provider·Controller·공개 DTO 구현
- retry·fallback·cache·Circuit Breaker·stale data
- 복구 명령·scheduler·batch·자동화와 실제 metric
- FastAPI·Rule·scoring·RiskLevel·Evidence 변경
- DB trigger를 통한 terminal 불변성 강화
- 기존 Snapshot backfill
- 공개 `replayed` 필드와 별도 trace 저장

## 22. ADR-003·004·006과의 관계

- ADR-003의 거래 접수부터 External Risk·Rule 분석·위험 대응·사건까지 이어지는 최종
  동기 처리 방향을 유지한다.
- ADR-004의 성공 Snapshot legacy·v1·v2 의미와 현재 trace 재결합 원칙을 유지하고,
  ADR-004가 별도 승인을 요구한 실패 Snapshot을 이 ADR에서 확정한다.
- ADR-006의 분석 전 확정 실패, 불확실한 `IN_PROGRESS`, 원본 예외와 suppressed
  writer 오류, 최종 업무 commit 뒤 완료 간극 불변식을 구체화한다.
- 기존 ADR을 대체하거나 소급 수정하지 않는다.

## 23. 후속 구현 상태 (2026-08-27, Issue #178)

public intake의 Idempotency 단일 승자 뒤 External Risk를 호출하고, 여섯
`ExternalRiskLookupException` category를 기존 Failure Snapshot 서비스로 저장해
`FAILED`로 확정하는 연결을 구현했다. 동일 key·fingerprint는 strict decode한 저장
HTTP status·공개 code·안전 message와 현재 요청 trace를 반환하며 Provider·Rule·
최종화를 다시 호출하지 않는다. Snapshot에는 trace, Provider 원문, credential,
reference와 내부 예외 정보를 저장하거나 공개하지 않는다.

Failure Snapshot writer가 실패하면 원본 External Risk 예외를 유지하고 writer
오류만 suppressed로 보존하며 Idempotency는 `IN_PROGRESS`일 수 있다. Provider가
없는 context는 claim 전에 고정 `503 DEPENDENCY_UNAVAILABLE`로 종료하므로 terminal
replay도 제공하지 않는다. crash·장기 `IN_PROGRESS` 복구와 자동 retry·fallback·
cache는 구현하지 않았다.

External Risk lookup과 Rule 분석 단계를 분리했다. command read·Provider 단계의 일반
`RuntimeException`은 Failure Snapshot이나 Rule 실패 reader 대상으로 확장하지 않고
원본 객체 그대로 전파해 Idempotency `IN_PROGRESS`를 유지한다.
