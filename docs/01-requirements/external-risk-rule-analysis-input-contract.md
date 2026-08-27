# External Risk 선행 조회·Rule v1 분석 입력 연결 계약

## 1. 목적과 승인 범위

이 문서는 거래 접수의 External Risk 선행 조회 결과를 Rule v1 분석 입력에
결합하는 승인 계약과 현재 구현의 단일 기준이다. Issue
[#160](https://github.com/Ahnjisan/FinGuardOps/issues/160)과 OWNER 승인 댓글
`5390984333`에서 승인한 계약과 Issue #162·164·166·168의 단계별 내부 구현,
Issue #178의 실제 Provider·public 거래 접수 최종 동기 연결 상태를 함께 기록한다.
Rule 실행 의미와 기존 v1 경계는 유지하면서 public 단일 승자가 External Risk,
Rule v2, 위험 대응 최종화와 성공·실패 멱등 재생을 조정한다.

현재 구현과 남은 운영 범위는 다음과 같이 구분한다.

| 구분 | 현재 구현 | 남은 운영 범위 |
| --- | --- | --- |
| External Risk | 실제 HTTP Provider와 local/dev/test Mock, 무잠금 command read·Policy lookup, typed Failure Snapshot 저장·재생 | 운영 credential 배포와 신규 metric·dashboard |
| Rule HTTP | v1 유지, 필수 `externalRisk`를 갖는 v2 DTO·Client·오케스트레이션과 public intake 연결 | 자동 retry·fallback·cache 없음 유지 |
| Rule 실행 의미 | Rule v1 R001~R004·scoring·Evidence 불변 | 운영 배포 검증 |
| 거래 접수 | 단일 승자의 `RECEIVED`·`IN_PROGRESS` commit 뒤 External Risk→Rule v2→최종화→Snapshot v2 | crash·완료 간극과 장기 `IN_PROGRESS` 운영 복구 |

`POST /api/v2/rule-analysis`는 wire schema의 새 버전이다. Rule 엔진, evaluator와
scoring 정책을 Rule v2로 바꾸는 이름이 아니다.

## 2. 책임과 처리 순서

상위 거래 처리 Service는 `@Transactional`을 적용하지 않고 다음을 조정한다.

```text
헤더·요청 Validation
→ fingerprint 계산
→ Idempotency IN_PROGRESS 단일 승자 선점 commit
→ RECEIVED 거래 저장·Idempotency 거래 연결 commit
→ DB 트랜잭션과 행 잠금이 없는 상태에서 External Risk 조회·정책 적용
→ 성공 ExternalRiskSnapshot 고정
→ 잠긴 분석 시작 트랜잭션에서 v1 Snapshot 조립·v2 mapper 실행
→ mapper 성공 뒤 DetectionResult IN_PROGRESS·거래 ANALYZING commit
→ DB 트랜잭션과 행 잠금이 없는 상태에서 FastAPI 1회 호출
→ 응답 검증·변환·채택
→ 위험 대응 최종화
→ 최종 멱등 Snapshot v2 확정
```

Validation 실패는 claim 전에 끝나므로 Transaction, Idempotency record와 Failure
Snapshot을 생성하지 않는다. Issue #178 public intake는 위 순서를 구현하며 신규
성공을 Snapshot v2로 저장한다. legacy·v1 완료 Snapshot은 저장 status 그대로
재생하고 소급 변환하지 않는다.

상위 Service가 `ExternalRiskPolicyService`, `RuleAnalysisOrchestrationService`,
`RiskResponseFinalizationService`를 이 순서로 호출한다. 기존 Rule 분석
오케스트레이터는 Provider 호출이나 External Risk 정책 결정을 소유하지 않는다.
Provider 호출을 `startAnalysisV2()` 트랜잭션 안에 넣거나 v2 mapper 성공 전에
DetectionResult를 생성해서는 안 된다. 기존 `startAnalysis()`와 `analyze(...)`는
External Risk 없는 v1 전용 경계로 유지한다.

한 분석 시도의 External Risk Port 호출은 최대 한 번이다. retry, cache, stale
data, Circuit Breaker와 fallback은 없으며 실패를 `UNMATCHED`로 바꾸지 않는다.
현재 coordinator는 per-invocation 내부 경계이므로 직접 재호출과 멱등 경계 밖 동시
호출에서는 Provider가 다시 호출될 수 있다. 기존 거래 잠금은 Rule 분석 시작의 단일
승자만 보장한다. [ADR-007](../07-decisions/ADR-007-external-risk-idempotent-failure-replay-contract.md)에
따라 같은 멱등 요청의 Provider 단일 승자와 재호출 방지는 public transaction
intake의 Idempotency claim이 소유하며 Issue #178에서 연결되었다.

## 3. immutable 전달 계약

External Risk 성공 뒤 상위 Service는 `RuleAnalysisOrchestrationService.analyzeV2(...)`에
거래 식별자, 성공한 `ExternalRiskSnapshot`과 trace만 전달한다. 이 public 메서드는
`@Transactional`이 아니며 외부 호출자가 `RuleAnalysisRequestV2`,
`evaluationCutoffAt`이나 `ruleSetVersion`을 직접 제공해 조작할 수 없게 한다.

`startAnalysisV2(...)`는 `REQUIRES_NEW`, `REPEATABLE_READ` 트랜잭션에서 거래 행을
먼저 잠그고 시작 가능 상태를 검증한다. 같은 잠금 아래 기존 v1 Snapshot assembler로
거래·행동 이벤트·실행 가능한 RuleVersion을 고정한 다음 v2 mapper로 성공 Snapshot을
결합한다. mapper가 성공한 뒤에만 다음 DetectionResult version을 조회하고
DetectionResult 생성과 거래 `ANALYZING` 전이를 수행한다. mapper가 실패하면 전체
트랜잭션이 rollback되어 거래는 `RECEIVED`, DetectionResult·Evidence는 0건이며
HTTP Client와 `failAnalysis()`는 호출하지 않는다. 시작 commit 뒤에만 활성 DB
트랜잭션 없이 `RuleAnalysisHttpClient.analyzeV2(...)`를 정확히 한 번 호출한다.

`ExternalRiskSnapshot`은 Rule HTTP 호출을 위한 인메모리 값이다. 이 계약은
External Risk DB Entity, DetectionEvidence, AuditLog 또는 최종 Snapshot v2에
External Risk 원문을 저장하지 않는다.

## 4. HTTP 버전과 최상위 요청

### 4.1 현재 v1

현재 구현된 `POST /api/v1/rule-analysis` 요청은 다음 네 필드만 가진다.

```text
evaluationCutoffAt
transaction
behaviorEvents
ruleVersions
```

v1 DTO와 Endpoint는 당장 제거하거나 의미를 확장하지 않는다. v1에 선택적
`externalRisk`를 추가하지 않는다.

### 4.2 구현된 FastAPI v2

구현된 `POST /api/v2/rule-analysis`는 기존 네 필드와 필수·non-null
`externalRisk`를 정확히 가진다.

```text
evaluationCutoffAt
transaction
behaviorEvents
ruleVersions
externalRisk
```

기존 `transaction`과 `behaviorEvents`의 비식별 reference는 R002~R004에 필요한
기존 계약이므로 제거하지 않는다. 다만 `externalRisk`에는 그 reference를
중복하지 않는다. 모든 최상위·중첩 객체는 알 수 없는 필드를 거부하고 JSON
필드명은 camelCase, Enum은 아래 exact 대문자 문자열을 사용한다.

## 5. `externalRisk` exact schema

| JSON 필드 | 타입 | 필수·null | 계약 |
| --- | --- | --- | --- |
| `providerCode` | string | 필수·non-null | `^[A-Z][A-Z0-9_]{0,63}$` |
| `lookupStatus` | string | 필수·non-null | 현재 `SUCCEEDED`만 허용 |
| `policyResult` | string | 필수·non-null | `MATCHED`, `UNMATCHED` |
| `providerAsOf` | string | 필수·non-null | 7절의 UTC 시각 |
| `lookedUpAt` | string | 필수·non-null | 7절의 UTC 시각 |
| `matches` | array | 필수·non-null | `MATCHED`면 1~3개, `UNMATCHED`면 정확히 0개 |

각 match는 다음 세 필드만 가진다.

| JSON 필드 | 타입 | 필수·null | 허용값 |
| --- | --- | --- | --- |
| `subjectType` | string | 필수·non-null | `SENDER_ACCOUNT`, `RECIPIENT_ACCOUNT`, `DEVICE` |
| `externalRiskType` | string | 필수·non-null | `SUSPICIOUS_ACCOUNT`, `RISK_DEVICE` |
| `reasonCode` | string | 필수·non-null | `SUSPICIOUS_SENDER_ACCOUNT`, `SUSPICIOUS_RECIPIENT_ACCOUNT`, `RISK_DEVICE` |

Java 도메인의 `ExternalRiskMatch.riskType`은 wire의 `externalRiskType`으로
명시적으로 매핑한다. 허용되는 exact 조합은 다음뿐이다.

| `subjectType` | `externalRiskType` | `reasonCode` |
| --- | --- | --- |
| `SENDER_ACCOUNT` | `SUSPICIOUS_ACCOUNT` | `SUSPICIOUS_SENDER_ACCOUNT` |
| `RECIPIENT_ACCOUNT` | `SUSPICIOUS_ACCOUNT` | `SUSPICIOUS_RECIPIENT_ACCOUNT` |
| `DEVICE` | `RISK_DEVICE` | `RISK_DEVICE` |

실제 고객·계좌·기기 reference, Provider 원문, `traceId`, `transactionId`,
`evaluationCutoffAt`, 인증정보, cache·fallback·retry·Mock scenario와 자유 텍스트는
`externalRisk`에 포함하지 않는다.

## 6. JSON 예시

### 6.1 MATCHED

```json
{
  "providerCode": "EXTERNAL_RISK_MOCK_V1",
  "lookupStatus": "SUCCEEDED",
  "policyResult": "MATCHED",
  "providerAsOf": "2026-08-24T03:00:00.123456Z",
  "lookedUpAt": "2026-08-24T03:00:01.654321Z",
  "matches": [
    {
      "subjectType": "SENDER_ACCOUNT",
      "externalRiskType": "SUSPICIOUS_ACCOUNT",
      "reasonCode": "SUSPICIOUS_SENDER_ACCOUNT"
    }
  ]
}
```

### 6.2 UNMATCHED

```json
{
  "providerCode": "EXTERNAL_RISK_MOCK_V1",
  "lookupStatus": "SUCCEEDED",
  "policyResult": "UNMATCHED",
  "providerAsOf": "2026-08-24T03:00:00.123456Z",
  "lookedUpAt": "2026-08-24T03:00:01.654321Z",
  "matches": []
}
```

다음은 거부 예다.

- `MATCHED`와 빈 `matches`
- `UNMATCHED`와 하나 이상의 match
- 같은 exact match의 중복
- 네 번째 match
- 지원하지 않는 Enum·필드·필드 조합
- null, snake_case alias, UTC `Z`가 아닌 시각 또는 6자 초과 소수 초
- 7절의 시간 순서를 만족하지 않는 값

## 7. 시간 계약

```text
providerAsOf <= evaluationCutoffAt <= lookedUpAt
evaluationCutoffAt == transaction.occurredAt
```

모든 시각은 canonical UTC `Z`이고 소수 초는 생략하거나 1~6자리만 허용한다.
마이크로초를 넘는 값을 반올림하거나 자동 절삭해 수용하지 않는다. 현재 독립
External Risk 정책은 `providerAsOf <= lookedUpAt`까지 검증하므로, 목표 연결은
분석 시작 전에 위 전체 관계를 추가로 검증해야 한다. 이를 만족하지 못하는 실제
Provider는 production 연결 전에 historical as-of 또는 별도 freshness 정책 승인이
필요하다.

## 8. match canonical 순서

중복 exact tuple은 정렬 전에 거부한다. 이후 다음 explicit rank tuple 오름차순으로
정렬한다.

```text
subjectType rank
→ externalRiskType rank
→ reasonCode rank
```

rank는 다음과 같다.

| 축 | rank 순서 |
| --- | --- |
| `subjectType` | `SENDER_ACCOUNT` → `RECIPIENT_ACCOUNT` → `DEVICE` |
| `externalRiskType` | `SUSPICIOUS_ACCOUNT` → `RISK_DEVICE` |
| `reasonCode` | `SUSPICIOUS_SENDER_ACCOUNT` → `SUSPICIOUS_RECIPIENT_ACCOUNT` → `RISK_DEVICE` |

Java와 Python은 같은 명시적 rank를 사용한다. Enum ordinal이나 문자열 자연 정렬,
Provider 반환 순서에 의존하지 않는다. Java v2 매퍼는 전송 전에 이 순서로
정렬하고 Python v2 요청 검증은 이미 canonical한 배열만 허용하며 조용히 재정렬하지
않는다.

## 9. Rule·점수·버전·응답 계약

FastAPI v2는 `externalRisk`의 wire·업무 불변식을 검증하고 해당 요청 실행 동안
immutable 값으로 유지하되 현재 R001~R004 evaluator 입력에는 전달하지 않는다.

- R001~R004 적중 조건과 실행 순서 변경 없음
- `riskScore`·`RiskLevel`·그룹 상한 변경 없음
- DetectionEvidence 추가·변경 없음
- `ruleSetVersion` canonical RuleVersion hash 변경 없음
- `scoringPolicyVersion = scoring-policy-v1` 유지
- `featureVersion = rule-v1` 유지
- `modelVersion = null` 유지
- FastAPI 응답에 External Risk echo 없음
- External Risk 전용 hash 없음

기존 RuleVersion identity만으로 계산하는 golden vector hash도 그대로 유지한다.
External Risk 값이나 순서를 hash 입력에 추가하지 않는다.

External Risk를 점수·등급·Evidence에 반영하려면 별도 Rule·scoring·버전 계약과
OWNER 승인이 필요하다.

## 10. 실패·멱등 경계

External Risk 실패는 Rule 분석 시작 전 실패다. 다음 상태를 함께 지킨다.

- 거래는 `RECEIVED` 유지
- DetectionResult를 생성하지 않음
- FastAPI를 호출하지 않음
- 위험 대응 최종화를 호출하지 않음
- 성공 Snapshot v2를 생성하지 않음
- 사건·연결·관련 AuditLog를 생성하지 않음
- 여섯 typed category의 실패 저장 commit이 확인되면 별도 Failure Snapshot과
  공개 `failure_code`로 멱등 `FAILED` 확정
- 같은 operation scope·Idempotency-Key·fingerprint 재생은 저장된 실패를 반환하고
  Provider·FastAPI·위험 대응 최종화를 다시 호출하지 않음

| External Risk 내부 category | 현재 공개 응답 |
| --- | --- |
| `TIMEOUT` | `503 Service Unavailable`, `DEPENDENCY_TIMEOUT` |
| `UNAVAILABLE` | `503 Service Unavailable`, `DEPENDENCY_UNAVAILABLE` |
| `INVALID_REQUEST`, `UNSUPPORTED_CAPABILITY`, `INVALID_RESPONSE`, `TRANSFORMATION_ERROR` | `500 Internal Server Error`, `INTERNAL_ERROR` |

이 공개 매핑과 멱등 실패 연결은 Issue #178에서 거래 접수 코드에 구현되었다.
실패를 성공 DetectionResult, `UNMATCHED`, 0점, `LOW` 또는 빈 Evidence로 변환하지
않는다.

`TIMEOUT`, `UNAVAILABLE`, `INVALID_REQUEST`, `UNSUPPORTED_CAPABILITY`,
`INVALID_RESPONSE`, `TRANSFORMATION_ERROR`는 durably confirmed되면 같은 key에서
모두 terminal이다. 같은 key로 자동 재실행하지 않고 새 Idempotency-Key도 같은
`transactionId`의 공식 재처리 수단으로 사용하지 않는다. 재처리는 후속 별도
operation scope의 승인된 복구·재분석 명령으로 설계한다. 예상하지 못한 일반
`RuntimeException`은 이 여섯 category나 전용 Failure Snapshot 대상으로 확장하지
않는다.

별도 Failure Snapshot의 exact type·version·필드·4 KiB 상한은 ADR-007이 소유하고,
공개 응답은 [거래·탐지 API](../03-api/transaction-detection-api.md)가 소유한다. 성공
`ExternalRiskSnapshot`, 성공 Snapshot v1과 성공 Snapshot v2를 실패 재생에
사용하지 않는다.

구현된 FastAPI v2가 `externalRisk`의 JSON 타입·필수·null·Enum·UTC 형식·unknown
field를 거부하면 `400 INVALID_REQUEST`, match 조합·개수·canonical 순서와 시간
관계 같은 cross-field 계약을 거부하면 `422 RULE_CONTRACT_ERROR`다. 둘 다 Spring
Boot가 만든 upstream 요청의 결함이므로 공개 거래 API에서는 `500 INTERNAL_ERROR`로
축약한다. Provider 응답을 검증하는 Spring 경계의 `INVALID_RESPONSE`와 FastAPI
요청 검증 실패를 같은 category로 혼합하지 않는다.

동일 멱등 요청의 Provider 중복 호출 방지는 거래 접수의 기존 단일 승자 선점에
의존한다. `IN_PROGRESS` 재요청은 Provider를 호출하거나 기다리지 않고 즉시
`409 IDEMPOTENCY_REQUEST_IN_PROGRESS`다. 같은 key·다른 fingerprint는 상태와
무관하게 key conflict다. 상위 오케스트레이션을 독립 공개 분석 API로 노출하지
않는다. 재분석이나 독립 내부 호출에는 별도의 durable analysis claim 계약과
승인이 필요하다.

Failure Snapshot 저장 직전 crash, writer 실패 또는 Provider 호출 여부를 DB만으로
확정할 수 없는 `IN_PROGRESS`에서는 External Risk 실패를 terminal로 추측하지 않고
Provider를 자동 재호출하지 않는다. 같은 key에는 409를 반환하며 운영 복구 실행은
후속 Issue다.

## 11. trace·민감정보·관측

최초 거래 요청의 `traceId`를 External Risk Provider 호출과 FastAPI 호출에 그대로
전달한다. `ExternalRiskSnapshot`, `externalRisk` JSON과 멱등 Snapshot에는
`traceId`를 저장하지 않는다. 완료·실패 재생은 현재 재요청 trace를 사용한다.
Failure Snapshot의 exact replay는 저장 HTTP status·공개 code·안전 message·빈
`fieldErrors`의 의미적 동일성이고 trace, HTTP 추적 헤더와 JSON byte ordering은
재생 범위가 아니다. 공개 `replayed` 필드는 추가하지 않는다.

로그는 `traceId`, Provider 식별자, 성공·실패 category, policy result, match 수,
호출 지연처럼 승인된 allowlist만 사용한다. 실제 reference, Provider 원문,
요청·응답 JSON, 인증정보는 로그·AuditLog·DetectionEvidence에 저장하지 않는다.
구현된 공개 안전 mapper는 저장된 status·code·안전 message와 현재 trace만 응답에
사용하고 Provider cause·category를 공개하지 않는다. category별 신규 운영 로그와
metric은 아직 구현되지 않았다.

후속 구현의 최소 관측 후보는 External Risk 호출 수·지연·category, v2 요청 검증
실패와 FastAPI 미호출 수다. metric 구현은 이번 Issue 범위가 아니다.

## 12. 호환성과 배포 순서

필수 필드 추가를 기존 v1에 적용하지 않는다. 배포는 다음 순서를 따른다.

1. AI Service가 기존 v1을 유지하면서 v2 Endpoint·필수 DTO·strict 검증 코드를 준비한다. — 코드 구현 완료
2. Backend 전환 전에 AI Service v2를 선배포한다. — 실제 운영 배포 미실행
3. Java·Python v2 fixture와 golden vector로 wire 호환성을 확인한다.
4. Backend Java v2 DTO·wire mapper·HTTP Client를 구현한다. — 코드 구현 완료
5. 내부 Rule 분석 오케스트레이터에 별도 v2 경계를 추가한다. — 코드 구현 완료
6. Mock Policy 성공 Snapshot을 내부 v2 경계에 전달하는 per-invocation coordinator를 구현한다. — 코드 구현 완료
7. 실제 Provider와 public 거래 접수 API를 coordinator·멱등 실패 재생에
   연결한다. — Issue #178 코드 구현 완료
8. 전환 동안 v1과 v2의 호출량·오류를 구분해 관측한다.

FastAPI v2 코드 구현은 실제 운영 선배포를 의미하지 않는다. Backend Java 내부
오케스트레이터는 기존 v1을 유지하면서 명시적인 별도 v2 메서드를 제공하고, 실제
Provider와 Mock 경로 모두 public 거래 접수 상위 오케스트레이션에 연결되어 있다.

지원하지 않는 v2 배포 조합은 optional 필드나 빈 Snapshot으로 우회하지 않고
fail-closed한다. v1 제거 시점은 별도 승인 대상이다.

## 13. 후속 구현 검증 기준

### Backend 단위 테스트

- Snapshot→v2 JSON exact mapping과 immutable 전달
- MATCHED·UNMATCHED, 최대 3개, 중복 거부와 canonical 순서
- 시간 관계와 필수·non-null·Enum·unknown field 검증
- 실제 reference와 금지 필드 미포함
- External Risk 실패 시 startAnalysis·FastAPI·최종화 미호출
- 기존 `ruleSetVersion`·scoring·feature metadata 불변

### Python 테스트

- strict Pydantic v2 요청과 Java fixture 호환
- MATCHED·UNMATCHED와 canonical 순서
- 중복·4개 이상·잘못된 조합·시각·unknown field 거부
- R001~R004 golden vector, 점수·등급·Evidence·응답 회귀

### 통합 테스트

- 멱등 단일 승자 뒤 External Risk 성공→분석 시작
- Provider 호출 중 활성 DB 트랜잭션·거래 잠금 없음
- 실패 시 거래 `RECEIVED`, DetectionResult 0건, FastAPI·최종화 미호출
- 같은 멱등 실패 재생에서 Provider 미재호출
- 성공 immutable 요청의 Java→FastAPI v2 호환

## 14. 구현·미구현 범위와 영향

독립 External Risk 도메인과 Mock, 현재 v1 Rule 경로, R001~R004와 내부 위험 대응
최종화는 기존 구현이다. Issue #162에서 FastAPI v2 strict DTO, External Risk
wire·교차 필드 검증과 Endpoint를 구현했으며 v1과 같은 Rule v1 실행 경계를 재사용한다.

Issue #164에서 Backend Java v2 DTO·exact wire mapper와 v1을 유지하는
`/api/v2/rule-analysis` 직접 Client 경계를 구현했다. Issue #166에서는 기존 v1
`analyze(...)`를 유지하면서 별도 `analyzeV2(...)`와 잠긴 시작 경계, mapper 선실행,
commit 뒤 v2 Client 호출 및 기존 완료·실패 경계 재사용을 구현했다. Issue #168에서는
별도 `READ_COMMITTED` command read와 Mock Policy 호출, 성공 Snapshot의
`analyzeV2(...)` 전달을 조정하는 비트랜잭션 내부 coordinator를 구현했다. Issue
#178에서는 실제 HTTP Provider와 Mock 경로를 public 단일 승자에 연결하고 성공
Snapshot v2, External Risk Failure Snapshot, typed 오류 재생과 위험 대응 최종화를
구현했다. 다음은 아직 구현되지 않았다.

- crash·완료 간극 운영 복구와 장기 `IN_PROGRESS` 복구
- External Risk 영속화·Evidence·AuditLog는 이번 목표에서 제외되며 별도 승인 필요
- retry·cache·Circuit Breaker·fallback
- 운영 credential 배포와 신규 metric·dashboard

Issue #178 연결은 성공한 단일 승자에 External Risk 1회와 FastAPI 1회를 실행하지만
LLM 호출·토큰 비용은 발생시키지 않는다. terminal 재생은 downstream을 호출하지
않는다. fail-closed
경계는 불완전한 외부 정보로 Rule 분석을 진행하는 업무 정합성 위험을 줄이는 대신
Provider 장애가 거래 최종 처리 실패로 노출되므로 category별 관측과 운영 절차가
필요하다.

## 15. Issue #178 구현 상태 (2026-08-27)

실제 HTTP Provider와 Mock coordinator Bean 경로를 public 거래 접수의 단일
Idempotency 승자에 연결했다. 순서는 Validation·fingerprint, coordinator 가용성,
claim commit, 거래 `RECEIVED`·연결 commit, 트랜잭션 밖 Provider, Rule v2, 위험 대응
최종화 commit, 별도 성공 Snapshot v2 완료 commit이다. 성공 재생과 External Risk
실패 재생은 Provider·FastAPI·최종화를 다시 호출하지 않는다.

lookup과 Rule 단계를 명시적으로 분리하므로 command read·Provider 단계의 일반
오류는 Rule 실패 reader 대상이 아니며 Idempotency를 `IN_PROGRESS`로 유지한다. 여섯
External Risk typed category만 Failure Snapshot으로 확정하고, External Risk 성공 뒤
Rule 단계 예외만 안전 상태 판정을 거친다. Provider 미설정은 claim 전에 고정
`503 DEPENDENCY_UNAVAILABLE`이며 DB write가 없다. retry·cache·Circuit Breaker·
fallback, 운영 credential·metric과 복구 자동화는 구현하지 않았다.
