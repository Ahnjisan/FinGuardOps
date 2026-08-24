# External Risk 선행 조회·Rule v1 분석 입력 연결 계약

## 1. 목적과 승인 범위

이 문서는 거래 접수의 External Risk 선행 조회 결과를 Rule v1 분석 입력에
결합하는 목표 계약의 단일 기준이다. Issue
[#160](https://github.com/Ahnjisan/FinGuardOps/issues/160)과 OWNER 승인 댓글
`5390984333`에서 승인한 문서 계약만 다루며 Java·Python·테스트·DB·Flyway·Gradle,
공개 Controller와 실제 Provider 구현을 완료한 것으로 간주하지 않는다.

현재 구현과 목표 계약은 다음과 같이 구분한다.

| 구분 | 현재 구현 | 목표 계약 |
| --- | --- | --- |
| External Risk | 독립 Port·Policy Service, local/dev/test Mock, immutable 성공 Snapshot | 거래 접수의 멱등 단일 승자가 DB 트랜잭션 밖에서 선행 조회 |
| Rule HTTP | `POST /api/v1/rule-analysis`, External Risk 없는 네 필드 요청 | `POST /api/v2/rule-analysis`, 필수 `externalRisk`를 추가한 다섯 필드 요청 |
| Rule 실행 의미 | Rule v1 R001~R004·scoring·Evidence | 같은 Rule v1 실행 의미를 그대로 유지 |
| 거래 접수 | `RECEIVED`/null v1 Snapshot을 반환·재생 | External Risk→Rule 분석→위험 대응→Snapshot v2 전체 연결 |

`POST /api/v2/rule-analysis`는 wire schema의 새 버전이다. Rule 엔진, evaluator와
scoring 정책을 Rule v2로 바꾸는 이름이 아니다.

## 2. 책임과 처리 순서

상위 거래 처리 Service는 `@Transactional`을 적용하지 않고 다음을 조정한다.

```text
거래 접수와 멱등 선점
→ RECEIVED 거래 저장 commit
→ DB 트랜잭션과 행 잠금이 없는 상태에서 External Risk 조회·정책 적용
→ 성공 ExternalRiskSnapshot 고정
→ External Risk 결과를 포함한 immutable Rule 분석 입력 확정
→ Rule 분석 시작 persistence 경계
→ DetectionResult IN_PROGRESS·거래 ANALYZING commit
→ DB 트랜잭션과 행 잠금이 없는 상태에서 FastAPI 1회 호출
→ 응답 검증·변환·채택
→ 위험 대응 최종화
→ 최종 멱등 Snapshot v2 확정
```

상위 Service가 `ExternalRiskPolicyService`, `RuleAnalysisOrchestrationService`,
`RiskResponseFinalizationService`를 이 순서로 호출한다. 기존 Rule 분석
오케스트레이터는 Provider 호출이나 External Risk 정책 결정을 소유하지 않는다.
Provider 호출을 `startAnalysis()` 트랜잭션 안에 넣거나 성공 Snapshot 전에
DetectionResult를 생성해서는 안 된다.

한 분석 시도의 External Risk Port 호출은 최대 한 번이다. retry, cache, stale
data, Circuit Breaker와 fallback은 없으며 실패를 `UNMATCHED`로 바꾸지 않는다.

## 3. immutable 전달 계약

External Risk 성공 뒤 상위 Service는 Spring Boot가 소유하는 Snapshot assembly
경계에 `ExternalRiskSnapshot`과 거래 식별자를 전달한다. 이 경계는 Provider 호출이
끝난 뒤 필요한 짧은 DB read transaction에서 거래·행동 이벤트·실행 가능한
RuleVersion을 읽고 External Risk와 조합해 완전한 immutable v2
`RuleAnalysisRequest`를 반환한다. 이 read transaction에는 외부 호출이 없고
DetectionResult 생성이나 거래 상태 변경도 없다.

상위 Service는 이 완성된 요청을 Rule 분석 시작 persistence 경계에 전달한다. 시작
경계는 거래를 잠근 뒤 `transactionId`, `evaluationCutoffAt`, 거래 소유 관계와 시간
계약을 다시 검증하고 요청 RuleVersion으로 예상 `ruleSetVersion`을 계산한다. 현재
`startAnalysis()`가 External Risk 없는 요청을 내부에서 조합하는 구현은 목표 v2에
맞게 변경해야 하며, 문서 계약만으로 변경된 것으로 간주하지 않는다. 외부 호출자가
`RuleAnalysisRequest`, `evaluationCutoffAt`이나 `ruleSetVersion`을 직접 제공해
조작할 수 없어야 한다.

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

### 4.2 목표 v2

목표 `POST /api/v2/rule-analysis`는 기존 네 필드와 필수·non-null
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
- 멱등 레코드는 확인된 실패 category로 `FAILED` 확정
- 같은 operation scope·Idempotency-Key·fingerprint 재생은 저장된 실패를 반환하고
  Provider를 다시 호출하지 않음

| External Risk 내부 category | 목표 공개 응답 |
| --- | --- |
| `TIMEOUT` | `503 Service Unavailable`, `DEPENDENCY_TIMEOUT` |
| `UNAVAILABLE` | `503 Service Unavailable`, `DEPENDENCY_UNAVAILABLE` |
| `INVALID_REQUEST`, `UNSUPPORTED_CAPABILITY`, `INVALID_RESPONSE`, `TRANSFORMATION_ERROR` | `500 Internal Server Error`, `INTERNAL_ERROR` |

이 공개 매핑과 멱등 실패 연결은 목표 계약이며 현재 거래 접수 코드에는 구현되지
않았다. 실패를 성공 DetectionResult, `UNMATCHED`, 0점, `LOW` 또는 빈 Evidence로
변환하지 않는다.

목표 FastAPI v2가 `externalRisk`의 JSON 타입·필수·null·Enum·UTC 형식·unknown
field를 거부하면 `400 INVALID_REQUEST`, match 조합·개수·canonical 순서와 시간
관계 같은 cross-field 계약을 거부하면 `422 RULE_CONTRACT_ERROR`다. 둘 다 Spring
Boot가 만든 upstream 요청의 결함이므로 공개 거래 API에서는 `500 INTERNAL_ERROR`로
축약한다. Provider 응답을 검증하는 Spring 경계의 `INVALID_RESPONSE`와 FastAPI
요청 검증 실패를 같은 category로 혼합하지 않는다.

동일 멱등 요청의 Provider 중복 호출 방지는 거래 접수의 기존 단일 승자 선점에
의존한다. 상위 오케스트레이션을 독립 공개 분석 API로 노출하지 않는다. 재분석이나
독립 내부 호출에는 별도의 durable analysis claim 계약과 승인이 필요하다.

## 11. trace·민감정보·관측

최초 거래 요청의 `traceId`를 External Risk Provider 호출과 FastAPI 호출에 그대로
전달한다. `ExternalRiskSnapshot`, `externalRisk` JSON과 멱등 Snapshot에는
`traceId`를 저장하지 않는다. 완료·실패 재생은 현재 재요청 trace를 사용한다.

로그는 `traceId`, Provider 식별자, 성공·실패 category, policy result, match 수,
호출 지연처럼 승인된 allowlist만 사용한다. 실제 reference, Provider 원문,
요청·응답 JSON, 인증정보는 로그·AuditLog·DetectionEvidence에 저장하지 않는다.

후속 구현의 최소 관측 후보는 External Risk 호출 수·지연·category, v2 요청 검증
실패와 FastAPI 미호출 수다. metric 구현은 이번 Issue 범위가 아니다.

## 12. 호환성과 배포 순서

필수 필드 추가를 기존 v1에 적용하지 않는다. 배포는 다음 순서를 따른다.

1. AI Service가 기존 v1을 유지하면서 v2 Endpoint·필수 DTO 검증을 선배포한다.
2. Java·Python v2 fixture와 golden vector로 wire 호환성을 확인한다.
3. Backend Client와 상위 거래 흐름을 v2로 전환한다.
4. 전환 동안 v1과 v2의 호출량·오류를 구분해 관측한다.

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

이번 Issue에서 구현한 것은 문서 계약뿐이다. 독립 External Risk 도메인과 Mock,
현재 v1 Rule 경로, R001~R004, 내부 위험 대응 최종화는 기존 구현이다.

다음은 아직 구현되지 않았다.

- 실제 External Risk HTTP Provider
- Java·Python v2 DTO와 Endpoint·Client
- 상위 비트랜잭션 거래 오케스트레이션
- 거래 접수 전체 연결과 공개 오류 매핑
- Snapshot v2와 완료 간극 운영 복구
- External Risk 영속화·Evidence·AuditLog는 이번 목표에서 제외되며 별도 승인 필요
- retry·cache·Circuit Breaker·fallback
- 운영 metric

문서 변경 자체는 DB·Flyway·Gradle·공개 API 동작을 바꾸지 않고 외부 호출이나
AI 비용을 발생시키지 않는다. 후속 연결은 성공한 단일 승자에 External Risk 1회와
FastAPI 1회를 추가하지만 LLM 호출·토큰 비용은 발생시키지 않는다. fail-closed
경계는 불완전한 외부 정보로 Rule 분석을 진행하는 업무 정합성 위험을 줄이는 대신
Provider 장애가 거래 최종 처리 실패로 노출되므로 category별 관측과 운영 절차가
필요하다.
