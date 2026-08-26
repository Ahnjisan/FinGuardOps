# External Risk 조회 정책과 Mock 경계

## 1. 목적

이 문서는 Spring Boot가 외부 위험정보 Provider를 조회할 때 사용하는 내부 Port,
정책 Service, 결정적 Mock과 성공 Snapshot의 계약을 정의한다. 목표 Rule 입력
연결은 [External Risk·Rule 분석 입력 계약](./external-risk-rule-analysis-input-contract.md)이
소유한다. 현재 구현은 local/dev/test 검증용 인메모리 경계와 Backend Java v2
exact wire DTO·mapper·HTTP Client·내부 오케스트레이션 경계까지다. 성공 Snapshot을
받는 내부 v2 경계는 구현됐지만 거래 접수 상위 오케스트레이터에 연결되지 않아 실제
Provider 조회 결과가 유입되는 전체 경로는 없다. 위험 점수·등급·최종
대응 또는 DB 영속화와도 연결하지 않는다.

## 2. 구현 범위

구현된 범위는 다음과 같다.

- `ExternalRiskLookupPort`의 호출당 1회 조회 계약
- Provider 응답을 신뢰하지 않고 검증하는 `ExternalRiskPolicyService`
- local/dev/test 전용 결정적 Mock Adapter와 조건부 Configuration
- 성공 결과만 표현하는 immutable 인메모리 `ExternalRiskSnapshot`
- Timeout, unavailable, 요청·capability·응답·변환 오류의 내부 failure category

다음은 구현되지 않았다.

- 실제 외부 HTTP Provider와 외부 네트워크 호출
- 거래 접수 상위 오케스트레이션과 그 경로의 FastAPI v2 Client 호출 연결
- External Risk 기반 점수·등급·위험 대응과 사건 처리
- `ExternalRiskSnapshot` 영속화·감사·복구. 현재 승인된 목표가 아니며 필요해질
  경우 별도 Issue, DB 계약과 Migration 승인 대상
- IP·피싱 또는 고객 단위 match 정책
- 공개 Controller, API DTO와 HTTP 오류 매핑
- retry, fallback, cache와 Circuit Breaker

## 3. 입력과 Provider 경계

### 3.1 ExternalRiskLookupCommand

내부 immutable 명령은 다음 필드를 가진다.

| 필드 | 계약 |
| --- | --- |
| `transactionId` | RFC 4122 UUID v4, 내부 Snapshot 연결에만 사용 |
| `transactionType` | 필수 거래 유형 |
| `evaluationCutoffAt` | 필수, 마이크로초 이하 UTC `Instant` |
| `externalCustomerRef` | 필수 비식별 reference |
| `senderAccountRef` | 필수 비식별 reference |
| `recipientAccountRef` | nullable 비식별 reference |
| `deviceRef` | nullable 비식별 reference |
| `traceId` | 기존 8~64자 내부 trace 계약 |

reference는 1~128자이고 앞뒤 공백을 허용하지 않는다. 검증 예외와 로그에는
reference 실제 값을 포함하지 않는다.

### 3.2 ExternalRiskProviderRequest

Provider Port에는 `transactionType`, `evaluationCutoffAt`, 고객·송신 계좌 reference,
nullable 수신 계좌·기기 reference와 `traceId`만 전달한다. 내부 `transactionId`는
Provider 요청으로 내보내지 않는다. 명령의 `traceId`를 생성·교체하지 않고 exact
전달한다.

### 3.3 ExternalRiskProviderResponse와 match

Provider 응답은 `providerCode`, `providerAsOf`, immutable `matches`를 반환한다.
`providerCode`는 `^[A-Z][A-Z0-9_]{0,63}$`의 제한 ASCII 형식만 허용하며 원본을
trim하거나 보정하지 않는다. match는 최대 3개이고 Provider는 정책 결과를 결정하지
않는다. 허용하는 고유 match 조합은 다음 세 가지뿐이다.

| subjectType | riskType | reasonCode |
| --- | --- | --- |
| `SENDER_ACCOUNT` | `SUSPICIOUS_ACCOUNT` | `SUSPICIOUS_SENDER_ACCOUNT` |
| `RECIPIENT_ACCOUNT` | `SUSPICIOUS_ACCOUNT` | `SUSPICIOUS_RECIPIENT_ACCOUNT` |
| `DEVICE` | `RISK_DEVICE` | `RISK_DEVICE` |

정책 Service는 null·blank 필드, 중복 match, 지원하지 않는 조합, 마이크로초 초과
`providerAsOf`와 조회 시각보다 미래인 `providerAsOf`를 `INVALID_RESPONSE`로
거부한다. Provider 원문과 실제 reference는 match에 포함하지 않는다.

현재 정책 Service는 Provider match 순서를 보존하며 canonical 정렬을 수행하지
않는다. 구현된 Java v2 mapper는 기존 Rule 요청과 `ExternalRiskSnapshot`의
`transactionId` 및 evaluation cutoff 일치를 검증하고, 요청 cutoff와 거래
`occurredAt`의 일치도 방어적으로 직접 재검증한다. 중복 match를 canonical 정렬 전에
거부한 뒤 subject/type/reason별 명시적 rank로 송신 계좌→수신 계좌→기기 순서의 새
immutable 목록을 생성한다. Enum ordinal·이름·문자열 정렬에 의존하지 않으며 잘못된
입력을 `UNMATCHED`로 보정하지 않는다. 구현된 Python v2 요청 검증은 이미 canonical한
배열만 허용하고 조용히 재정렬하지 않는다.

## 4. 정책 Service와 Snapshot

`ExternalRiskPolicyService.lookup(command)`는 명령 검증, Provider request 변환,
Port 정확히 1회 호출, 응답 검증, 정책 결과 계산과 Snapshot 생성을 순서대로
수행한다. 자동 retry와 fallback은 없다. 이미 분류된
`ExternalRiskLookupException`은 category와 cause를 보존해 그대로 전파한다.

성공 `ExternalRiskSnapshot`의 필드는 다음과 같다.

- `transactionId`
- `evaluationCutoffAt`
- `lookedUpAt`
- `providerCode`
- `providerAsOf`
- `lookupStatus = SUCCEEDED`
- `policyResult = MATCHED | UNMATCHED`
- immutable `matches`

빈 match 목록은 `UNMATCHED`, 하나 이상은 `MATCHED`다. `traceId`와 고객·계좌·기기
reference는 Snapshot에 저장하지 않는다. `lookedUpAt`은 응답 구조 검증 뒤 기존
UTC `Clock`에서 한 번 얻고 PostgreSQL 호환 마이크로초 정밀도로 정규화한다.
이 Snapshot은 현재 구현과 Issue #160의 목표 모두에서 immutable 비영속 인메모리
값이다. 성공한 Rule 분석 v2 요청을 조립하는 동안만 사용하며 Entity·Repository·
테이블·FK가 없다. DB, DetectionEvidence, AuditLog와 최종 멱등 Snapshot v2에도
저장하지 않는다. V1~V7을 변경하거나 신규 Flyway Migration을 추가하지 않는다.
영속화·감사·복구는 현재 승인된 목표가 아니며, 필요해지면 별도 Issue와 DB 계약,
Migration 승인을 받아야 한다.

현재 독립 정책의 시각 검증은 `providerAsOf <= lookedUpAt`이다. 구현된 FastAPI v2는
`providerAsOf <= evaluationCutoffAt <= lookedUpAt`과 기존
`evaluationCutoffAt == transaction.occurredAt`을 Rule 실행 전에 검증하며 마이크로초
초과 값을 반올림·절삭하지 않는다. 구현된 Java v2 mapper도 요청 조립 전에
`providerAsOf <= evaluationCutoffAt <= lookedUpAt`과 요청 cutoff·거래
`occurredAt`의 exact equality를 직접 검증한다. 기존 요청 DTO와 Snapshot 불변식 및
mapper의 명시적 검증은 관련 시각의 마이크로초 이하 정밀도를 유지하며 값을
반올림하거나 절삭하지 않는다.

## 5. 실패 분류

| category | 의미 |
| --- | --- |
| `TIMEOUT` | Provider 조회 시간 초과 |
| `UNAVAILABLE` | Provider 이용 불가 |
| `INVALID_REQUEST` | 내부 명령 또는 Provider 요청 계약 위반 |
| `UNSUPPORTED_CAPABILITY` | 선택한 조회에 필요한 reference 부재 |
| `INVALID_RESPONSE` | null·불완전·모순·중복 또는 시각 계약 위반 응답 |
| `TRANSFORMATION_ERROR` | 검증 뒤 Snapshot 변환에서 발생한 예상하지 못한 오류 |

예외 메시지는 category별 안전한 고정 문자열만 사용한다. nullable 원본 cause는
보존하지만 메시지에 요청 reference나 Provider 원문을 복사하지 않는다. 실패를
`UNMATCHED` 성공으로 바꾸거나 cache·stale data·fallback으로 대체하지 않고 현재
분석을 계속하지 않는다. 승인된 목표 거래 접수 연결에서는 거래가 `RECEIVED`를
유지하고 DetectionResult를 생성하지 않으며 FastAPI와 위험 대응 최종화를 호출하지
않는다. 멱등 레코드는 실패를 확정하고 같은 요청 재생에서 Provider를 다시 호출하지
않는다. 이 연결과 공개 오류 매핑은 아직 구현되지 않았으며
cache·Circuit Breaker·fallback은 별도 Issue와 계약 승인이 필요하다.

## 6. 결정적 Mock

Bean 생성 gate는 `external-risk-mock` profile과
`finguardops.external-risk.mock.enabled=true`의 AND다. Bean 생성 뒤 설정
fail-fast gate가 다음을 확인한다.

- `production` 또는 `prod`가 하나라도 활성화되면 우선 거부
- `local`, `dev`, `test` 중 하나 필수
- `finguardops.external-risk.mock.scenario` 필수

`enabled`의 기본값은 `false`다. External Risk는 향후 웹 요청에서 사용하므로
non-web 조건은 두지 않는다. Mock 비활성 상태에는 Mock Configuration, Adapter와
Mock에 조립된 정책 Service Bean이 모두 생성되지 않는다.

| scenario | 결과 |
| --- | --- |
| `MATCHED_SENDER_ACCOUNT` | 송신 계좌 허용 match 1개 |
| `MATCHED_RECIPIENT_ACCOUNT` | 수신 계좌 허용 match 1개. reference 부재 시 `UNSUPPORTED_CAPABILITY` |
| `MATCHED_DEVICE` | 기기 허용 match 1개. reference 부재 시 `UNSUPPORTED_CAPABILITY` |
| `UNMATCHED` | 빈 match 목록 |
| `TIMEOUT` | `TIMEOUT` 예외 |
| `UNAVAILABLE` | `UNAVAILABLE` 예외 |
| `INVALID_RESPONSE` | 정책 Service가 거부하는 모순 match |

성공 Mock의 `providerCode`는 `EXTERNAL_RISK_MOCK_V1`, `providerAsOf`는 명령의
`evaluationCutoffAt`이다. 같은 scenario와 입력은 항상 같은 결과를 반환하며
reference 기반 hash·allowlist·random 분기는 없다.

예시 설정은 다음과 같다.

```properties
spring.profiles.active=local,external-risk-mock
finguardops.external-risk.mock.enabled=true
finguardops.external-risk.mock.scenario=MATCHED_SENDER_ACCOUNT
```

## 7. 로그와 보안

성공 로그는 `traceId`, `providerCode`, `lookupStatus`, `policyResult`, match 개수만
기록한다. 실패 로그는 `traceId`와 `failureCategory`만 기록한다. `transactionId`,
고객·계좌·기기 reference, Provider 요청·응답 원문과 전체 configuration은 기록하지
않는다. 현재 구현은 외부 네트워크, DB, FastAPI와 LLM 호출을 발생시키지 않는다.
