# Spring Boot Rule v1 분석 오케스트레이션·결과 채택 계약

## 1. 목적

이 문서는 Spring Boot가 거래 접수 이후 Rule v1 분석 입력을 고정하고 FastAPI를
호출한 뒤 `DetectionResult`와 `DetectionEvidence`를 저장·채택하는 전체 흐름의
단일 기준이다.

FastAPI 내부에서 immutable execution plan을 만들고 evaluator를 순차 실행하는
책임은 [Rule 실행 오케스트레이션 내부 계약](./rule-execution-orchestration-contract.md)이
소유한다. 이 문서는 그 내부 실행 계약을 대체하거나 Spring Boot 책임으로
옮기지 않는다.

이 계약은 구현 목표를 확정한다. 현재 구현 완료 범위와 공백은 15절에서 따로
구분한다.

## 2. 적용 범위

### 2.1 포함

- 거래 접수와 멱등 처리의 분석 연결
- 거래·행동 이벤트·활성 `RuleVersion` Snapshot 고정
- `evaluationCutoffAt`과 `ruleSetVersion` 고정
- 거래별 다음 `DetectionResult` 버전 할당
- `FinancialTransaction`과 `DetectionResult` 상태 전이
- DB 트랜잭션 밖 FastAPI 동기 호출
- Client 성공·오류 응답 검증
- Evidence 저장, 결과 완료·채택과 거래 분석 완료의 원자성
- 실패 상태 기록과 외부 공통 오류 매핑
- 동시 분석, 중복 버전과 Timeout 후 늦은 응답 방지
- 최종 성공 멱등 응답 Snapshot 확정 시점

### 2.2 제외

- 실패 후 자동 재분석, 수동 복구와 새 버전 생성 정책
- External Risk, ML, LLM, 위험 대응과 사건 생성
- 신규 Endpoint, DTO, 상태, 컬럼 또는 Migration
- Client 자동 retry와 fallback

실패 후 재분석·수동 복구는 별도 후속 계약에서 정한다. 이 문서만으로
`FAILED` 거래나 terminal `DetectionResult`를 되돌리거나 재사용하지 않는다.

## 3. 소유권과 불변 원칙

- Spring Boot는 거래 상태, 분석 시도 버전, 결과 채택과 멱등 응답의 최종
  소유자다.
- FastAPI는 전달받은 Snapshot으로 Rule v1 결과를 계산하지만 업무 DB를
  조회·수정하거나 거래 상태를 확정하지 않는다.
- 생성형 AI는 이 흐름에 참여하지 않으며 점수·등급·최종 판정·거래 차단을
  수행하지 않는다.
- 외부 호출 실패를 0점, `LOW`, 빈 Evidence 또는 fallback 성공으로 바꾸지
  않는다.
- Spring Boot Client의 자동 retry는 `0회`다. 한 분석 시도는 FastAPI HTTP
  요청을 정확히 한 번만 수행한다.

## 4. 사용하는 기존 상태와 식별자

이 계약은 다음 기존 값만 사용한다.

| 대상 | 상태·식별자 |
|---|---|
| `FinancialTransaction.processingStatus` | `RECEIVED`, `ANALYZING`, `ANALYZED`, `FAILED` |
| `DetectionResult.analysisStatus` | `PENDING`, `IN_PROGRESS`, `COMPLETED`, `FAILED` |
| 멱등 레코드 처리 상태 | `IN_PROGRESS`, `COMPLETED`, `FAILED` |
| 분석 시도 식별 | `detectionResultId`, 거래별 `detectionResultVersion`, `analysisTraceId` |
| 평가 입력 식별 | `evaluationCutoffAt`, `ruleSetVersion`, `scoringPolicyVersion`, `featureVersion` |

위 표에 없는 편의 상태나 필드를 추가하지 않는다. `PENDING → IN_PROGRESS`는
`DetectionResult` 전이이고, `RECEIVED → ANALYZING`은 거래 전이다.

## 5. 전체 처리 순서

정상 경로의 순서는 다음과 같다.

1. 거래 접수 요청의 멱등 레코드를 `IN_PROGRESS`로 선점한다.
2. 거래를 저장하고 이번 실행의 `evaluationCutoffAt`을 거래의 `occurredAt`으로
   한 번만 확정한다.
3. 분석 시작 쓰기 트랜잭션에서 거래 행을 잠근다.
4. 같은 cutoff를 기준으로 거래, 행동 이벤트와 실행 가능한 활성
   `RuleVersion` Snapshot을 고정한다.
5. 고정한 Rule Snapshot의 canonical execution plan 입력으로 예상
   `ruleSetVersion`을 계산하고 분석 버전 필드와 trace를 고정한다.
6. 거래 잠금 아래 다음 `detectionResultVersion`을 할당해 `PENDING`
   `DetectionResult`를 만든다.
7. 같은 트랜잭션에서 `DetectionResult`를 `IN_PROGRESS`로, 거래를
   `RECEIVED → ANALYZING`으로 전이하고 commit한다.
8. 어떤 DB 트랜잭션이나 행 잠금도 유지하지 않은 상태에서 FastAPI를 정확히
   한 번 호출한다.
9. 성공 응답의 wire, trace, 업무 의미와 요청 Snapshot 대응을 Client 계약에
   따라 검증한다.
10. 결과 채택 쓰기 트랜잭션에서 Evidence 저장, `DetectionResult COMPLETED`,
    결과 채택과 거래 `ANALYZING → ANALYZED`를 함께 commit한다.
11. 결과 채택 commit이 성공한 뒤에만 최종 동기 성공 응답을 만들고 멱등
    `responseSnapshot`을 `COMPLETED`로 확정한다.
12. 저장된 Snapshot과 같은 HTTP 상태·본문을 반환한다.

`DetectionResult` 생성 이후 어느 단계에서든 실패하면 11절을 따른다. Snapshot
구성 자체가 실패해 분석 시도가 생성되지 않았다면 존재하지 않는 결과를
`FAILED`로 만들지 않고 거래·멱등 실패 경계에서 처리한다. 성공 응답 검증
전에는 Evidence, 점수, 등급 또는 채택 값을 저장하지 않는다.

## 6. Snapshot과 cutoff 고정

### 6.1 `evaluationCutoffAt`

- 한 실행에서 정확히 한 번 정하며 값은 `FinancialTransaction.occurredAt`과
  UTC microsecond까지 같아야 한다.
- 서버 현재 시각, FastAPI 호출 시각이나 응답 시각으로 다시 계산하지 않는다.
- 행동 이벤트 조회, `RuleVersion` 유효 기간 판정, 요청 DTO와
  `DetectionResult.evaluationCutoffAt`이 모두 같은 값을 사용한다.
- Snapshot 고정 뒤 추가된 이벤트나 바뀐 Rule은 현재 실행에 포함하지 않는다.

### 6.2 거래·행동 이벤트 Snapshot

- 거래 Snapshot은 저장된 거래의 현재 분석 입력 필드를 복사해 구성한다.
- 행동 이벤트는 현재 Rule v1 계약의 고객·이벤트 종류·포함 구간과 최대
  1,000건 제한을 사용한다.
- 행동 이벤트 구간의 상한은 `evaluationCutoffAt`을 포함한다. 고정 뒤 발생한
  이벤트는 다음 분석 계약이 정해지기 전까지 현재 실행에 섞지 않는다.
- 요청 객체는 외부 호출 전에 독립된 immutable 값으로 구성한다. 외부 호출
  중 DB Entity나 지연 로딩 관계에 의존하지 않는다.

### 6.3 활성 `RuleVersion` Snapshot

- `FraudRule.lifecycleStatus = ACTIVE`, `RuleVersion.status = PUBLISHED`이고
  `effectiveFrom <= evaluationCutoffAt < effectiveTo`인 버전만 선택한다.
  `effectiveTo`가 null이면 상한이 없다.
- 평가에 사용할 전체 버전을 한 번에 고정하며 일부 Rule만 나중에 다시
  조회하거나 대체하지 않는다.
- 빈 실행 집합, 같은 Rule의 복수 실행 버전, 미지원 Rule·의존성·설정 오류는
  정상 0점 분석이 아니라 실패다.
- V5 seed의 초기 `RuleVersion`은 모두 `DRAFT`이므로 실제 실행 전 승인된
  publish가 필요하다.

### 6.4 `ruleSetVersion` 선확정

`detection_result.rule_set_version`은 `PENDING`부터 NOT NULL이고 이후 변경할 수
없다. 따라서 Spring Boot는 FastAPI 호출 전에 고정한 Rule Snapshot으로
[Rule 실행 계획 계약](./rule-execution-plan-contract.md)의 canonical
`ruleSetVersion`을 계산해 `DetectionResult`에 저장해야 한다.

FastAPI 응답의 `analysis.ruleSetVersion`은 이 예상 값과 정확히 같아야 한다.
형식만 유효한 다른 해시는 성공으로 채택하지 않는다. 이 비교는 FastAPI가
응답에서 계산한 값을 DB의 불변 Snapshot과 결합하는 Spring 오케스트레이션
검증이다.

## 7. 분석 시작 쓰기 트랜잭션

분석 시작은 하나의 짧은 DB 쓰기 트랜잭션으로 처리한다.

1. `FinancialTransaction`을 pessimistic write lock으로 조회한다.
2. 거래가 `RECEIVED`인지, 채택 결과가 없는지 확인한다. 이미
   `ANALYZING`이거나 다른 상태면 새 분석을 시작하지 않는다.
3. Snapshot과 불변 분석 버전 필드를 확정한다.
4. 같은 거래의 현재 최대 `detectionResultVersion + 1`을 할당한다.
5. `DetectionResult PENDING`을 생성한다.
6. outbound 호출 직전 시작 시각을 기록해 `PENDING → IN_PROGRESS`로 전이한다.
7. 같은 원자적 경계에서 거래를 `RECEIVED → ANALYZING`으로 전이한다.
8. commit한 뒤 거래 잠금과 트랜잭션을 해제한다.

거래 잠금과 `UNIQUE(financial_transaction_id, detection_result_version)` 제약을
함께 사용한다. 실패한 버전도 소비하며 번호를 되돌리거나 재사용하지 않는다.

## 8. FastAPI 호출 경계

- 분석 시작 commit 뒤, DB 트랜잭션과 모든 행 잠금을 해제한 상태에서
  `RuleAnalysisHttpClient`를 호출한다.
- Controller가 Client를 직접 호출하지 않는다. 오케스트레이션 계층이 고정한
  요청 Snapshot만 Client에 전달한다.
- 기본 connect timeout은 `1s`, response timeout은 `3s`이며 설정 계약을
  따른다.
- `X-Trace-ID`는 분석 결과의 `analysisTraceId`와 연결되는 유효한 단일 값을
  사용한다.
- Client 자동 retry는 `0회`다. Timeout 또는 연결 실패 뒤 같은 시도에서
  재호출하지 않는다.

## 9. 성공 응답 검증

HTTP `200`만으로 성공으로 간주하지 않는다. 저장 전에
[Rule v1 분석 API](../03-api/rule-v1-analysis-api.md)의 Client 계약에 따라 다음을
모두 검증한다.

- 지원 HTTP 상태, `application/json`, 단일 `X-Trace-ID`, 본문 trace 일치
- 엄격한 JSON 구조와 필수·미지·null·형식 규칙
- 요청 `transactionId`, `evaluationCutoffAt`과 응답 연결
- 요청 Rule Snapshot과 응답 Evidence의 RuleVersion·reason·weight 대응
- canonical 순서, contribution, group 합계, 최종 점수와 위험 등급 정합성
- 행동 이벤트 Evidence가 요청 Snapshot과 일치하는지 여부
- 응답 `ruleSetVersion`과 호출 전에 저장한 예상 `ruleSetVersion`의 exact 일치
- 지원 `scoringPolicyVersion` 일치

검증 실패는 `AI_SERVICE_INVALID_RESPONSE` 또는 Client가 이미 분류한 더 구체적인
내부 category로 처리한다. 응답을 보정하거나 일부만 저장하지 않는다.

## 10. 성공 결과 채택 원자성

성공 응답 검증 후 새 쓰기 트랜잭션을 시작한다. 교착을 피하도록 분석 시작과
같은 순서로 거래를 먼저 잠그고 해당 `DetectionResult`를 잠근다.

다음 조건을 다시 확인한다.

- 거래는 `ANALYZING`이다.
- 대상 결과는 같은 거래·시도 ID와 버전의 `IN_PROGRESS` 결과다.
- 거래에는 채택 결과가 없다.
- cutoff, trace와 분석 버전 값이 최초 Snapshot과 같다.
- 대상 버전은 이 요청이 시작한 시도이며 다른 버전 응답이 아니다.

검증 뒤 다음 변경을 하나의 쓰기 트랜잭션으로 수행한다.

1. 검증된 응답을 기존 `RuleVersion`과 연결한 `DetectionEvidence`로 변환해
   `sortOrder` 순으로 저장한다.
2. `DetectionResult`에 점수·등급·완료 시각을 기록하고
   `IN_PROGRESS → COMPLETED`로 전이한다.
3. 완료 결과를 `FinancialTransaction.adoptedDetectionResult`로 채택하고 거래의
   `riskLevel`을 그 결과와 맞춘다.
4. 거래를 `ANALYZING → ANALYZED`로 전이한다.
5. 네 변경을 함께 commit한다.

Evidence insert는 부모 결과가 `IN_PROGRESS`일 때만 허용되고, 거래는
`COMPLETED` 결과만 채택할 수 있다. 어느 한 단계가 실패하면 전체 트랜잭션을
rollback하여 부분 Evidence, 미채택 완료 결과 또는 결과 없는 `ANALYZED`
거래를 남기지 않는다.

위 원자적 경계에는 위험 대응, 사건 생성과 최종 판정이 포함되지 않는다.

## 11. 실패 처리와 외부 오류 매핑

### 11.1 상태 기록

FastAPI transport 실패, 지원 오류 응답, 성공 응답 검증 실패 또는 결과 채택 전
영속 실패는 실패 경로를 시작한다. 별도 짧은 쓰기 트랜잭션에서 거래와 대상
결과를 잠그고 다음을 함께 기록한다.

- `DetectionResult PENDING|IN_PROGRESS → FAILED`
- `FinancialTransaction ANALYZING → FAILED`
- 안전한 `failureCode`와 완료 시각
- 채택 결과 없음

Evidence는 저장하지 않으며 이미 시작한 성공 쓰기 트랜잭션의 Evidence가
있다면 rollback한다. terminal `FAILED` 결과는 수정하거나 성공으로 바꾸지
않는다. 실패 상태 commit 뒤 멱등 레코드는 성공 Snapshot 없이 `FAILED`로
확정한다.

### 11.2 Client 내부 category의 외부 매핑

Client category는 외부 API에 노출하지 않고 기존 공통 오류 envelope로 바꾼다.

| Client 내부 category | 외부 HTTP·공통 code |
|---|---|
| `AI_SERVICE_CONNECT_TIMEOUT` | `503 DEPENDENCY_TIMEOUT` |
| `AI_SERVICE_RESPONSE_TIMEOUT` | `503 DEPENDENCY_TIMEOUT` |
| `AI_SERVICE_UNAVAILABLE` | `503 DEPENDENCY_UNAVAILABLE` |
| `AI_SERVICE_REQUEST_CONTRACT_ERROR` | `500 INTERNAL_ERROR` |
| `AI_SERVICE_PAYLOAD_TOO_LARGE` | `500 INTERNAL_ERROR` |
| `AI_SERVICE_RULE_CONTRACT_ERROR` | `500 INTERNAL_ERROR` |
| `AI_SERVICE_CAPABILITY_MISMATCH` | `500 INTERNAL_ERROR` |
| `AI_SERVICE_INTERNAL_ERROR` | `500 INTERNAL_ERROR` |
| `AI_SERVICE_INVALID_RESPONSE` | `500 INTERNAL_ERROR` |

Spring Boot가 만든 요청의 계약·크기·Rule 구성 오류와 배포 capability 불일치는
거래 API 호출자의 `400` 계열 오류가 아니다. FastAPI 원문 메시지, 응답 본문,
내부 category와 예외 상세를 외부 응답에 포함하지 않는다.

## 12. 동시 분석과 늦은 응답 방지

### 12.1 같은 거래의 동시 시작

- 모든 시작 경로는 먼저 같은 거래 행을 잠근다.
- 첫 요청이 `RECEIVED → ANALYZING`을 commit하면 뒤 요청은 `ANALYZING`을 보고
  새 분석을 시작하지 않는다.
- 버전 할당은 거래 잠금 안에서 수행하고 DB unique 제약을 최종 방어선으로
  사용한다.
- 이미 존재하는 버전이나 결과 ID를 덮어쓰지 않는다.

### 12.2 Timeout 후 늦은 성공 응답

성공 완료 경로와 실패 완료 경로는 같은 거래와 `DetectionResult` 잠금 및
terminal 상태 검증을 사용한다. Timeout 실패가 먼저 commit되어 결과가
`FAILED`이면 늦은 성공 응답은 Evidence를 저장하거나 결과를 채택할 수 없다.
성공 commit이 먼저 끝났다면 뒤의 실패 처리도 `COMPLETED`·`ANALYZED`를
덮어쓸 수 없다.

응답은 자신이 시작한 `detectionResultId`와 버전에만 적용한다. 더 최신 버전,
다른 trace 또는 다른 Snapshot의 응답으로 대체하지 않는다. Client retry가
없으므로 한 시도 안에서 두 HTTP 응답을 경쟁시키지 않는다.

## 13. 최종 멱등 응답 Snapshot

- 거래 접수의 멱등 레코드는 분석과 결과 채택이 끝날 때까지
  `IN_PROGRESS`를 유지한다.
- `DetectionResult COMPLETED`, 결과 채택과 거래 `ANALYZED`가 commit되기
  전에는 성공 `responseSnapshot`을 만들거나 `COMPLETED`로 바꾸지 않는다.
- 결과 채택 commit 이후 저장된 업무 상태로 최종 동기 응답을 만들고, 별도
  멱등 완료 경계에서 HTTP 상태와 본문 Snapshot을 저장한다.
- 성공 Snapshot 저장이 확정된 뒤에만 최초 요청에 성공을 반환한다. 동일 키와
  같은 fingerprint의 후속 요청은 저장된 동일 Snapshot을 재생한다.
- 분석 실패에는 성공 Snapshot이 없다. 같은 키의 실패 재생과 복구 정책은
  기존 멱등 오류 계약을 따르되, 실패를 성공으로 재생하지 않는다.

결과 채택 commit과 멱등 완료 사이 장애로 업무 결과는 채택되었지만 Snapshot이
아직 확정되지 않을 수 있다. 이 간극의 운영 복구는 후속 수동 복구 계약에서
정하며, 새 분석을 자동 시작하거나 임시 성공 응답을 만들지 않는다.

## 14. 관측과 보안

최소 관측 대상은 분석 시도 수, 상태별 결과 수, Client category별 실패 수,
connect·response timeout, 외부 호출 지연시간, 결과 채택 rollback과 늦은 응답
거부 수다. `traceId`, `transactionId`, `detectionResultId`와 버전으로 흐름을
연결하되 API Key, Token, 계좌·고객·기기·수취인 원문, 요청·응답 원문과 예외
상세를 로그에 남기지 않는다.

이 Rule v1 경로는 LLM을 호출하지 않으므로 토큰과 LLM 비용을 발생시키지
않는다.

## 15. 현재 구현 상태

### 15.1 구현됨

- FastAPI `POST /api/v1/rule-analysis` Endpoint, DTO, trace·본문 제한과 오류 처리
- FastAPI RuleVersion Snapshot 검증, 실행 계획, R001~R004 evaluator, scoring과
  Evidence 응답 계산
- Spring Boot `RuleAnalysisHttpClient`
- connect·response timeout 설정과 trace 전달
- 성공·오류 응답의 엄격한 검증 및 transport·응답 오류 category 분류
- Client 자동 retry 0회
- `FinancialTransaction`, `DetectionResult`, `DetectionEvidence` Entity와 DB 제약
- 거래 잠금 아래 다음 DetectionResult 버전을 할당하는 persistence primitive
- `DetectionResult`의 `PENDING → IN_PROGRESS → COMPLETED|FAILED` primitive
- Evidence 저장과 `DetectionResult COMPLETED`를 묶는 persistence transaction
- 행동 이벤트 Rule 평가 조회와 Rule 코드별 실행 가능 `RuleVersion` 조회
- Flyway V1~V5의 거래·멱등·행동 이벤트·탐지 결과·RuleVersion 제약과 index

### 15.2 구현되지 않음

- 이 문서의 Spring Boot 거래 분석 오케스트레이션 전체
- 거래·행동 이벤트·전체 활성 `RuleVersion`을 한 실행 Snapshot으로 조합하는
  Service
- 전체 실행 가능 RuleVersion을 한 번에 조회하는 경로
- Spring Boot의 예상 `ruleSetVersion` 선계산과 응답 exact 비교
- 거래의 `RECEIVED → ANALYZING → ANALYZED` 및 실패 전이 실행 경로
- 분석 시작 상태 변경을 한 트랜잭션으로 묶는 경로
- FastAPI 응답을 Evidence로 자동 변환·저장하고 완료 결과를 채택하는 통합 경로
- Evidence, 결과 완료, 채택과 거래 `ANALYZED`의 단일 쓰기 트랜잭션
- 분석 실패 시 결과와 거래를 함께 `FAILED`로 만드는 경로
- 최종 동기 분석 응답과 결과 채택 이후 멱등 Snapshot 확정
- 위험 대응과 사건 생성

### 15.3 현재 구현과 목표 계약의 차이

- 현재 `TransactionIntakeCompletionService`는 거래를 `RECEIVED`로 저장한
  트랜잭션에서 `RECEIVED`/탐지 null 응답 Snapshot을 즉시 `COMPLETED`로
  확정한다. 13절의 최종 분석 결과 이후 확정과 다르다.
- `FinancialTransaction`에는 완료 결과 채택 메서드는 있으나
  `RECEIVED → ANALYZING → ANALYZED`와 분석 실패 전이 메서드가 없다.
- 현재 `DetectionResultPersistenceService`는 PENDING 생성, 시작, 완료·Evidence,
  실패를 각각 제공하지만 거래 상태 변경·결과 채택과 한 원자적 경계로 묶지
  않는다.
- `DetectionResult`는 FastAPI 호출 전 PENDING 생성 시 `ruleSetVersion`을
  요구하지만 Spring Boot의 canonical hash 선계산 경로는 없다. 현재 Client
  validator도 응답 해시의 형식은 확인하지만 DB에 선확정한 예상 해시와의 exact
  비교는 하지 않는다.
- 현재 RuleVersion Repository는 Rule 코드 하나의 실행 가능 버전을 조회할 수
  있지만 전체 활성 Rule Snapshot 조합 경로는 없다.
- V5 초기 RuleVersion은 모두 `DRAFT`이므로 그대로는 실행 가능한 Rule 집합이
  없다.

이 차이는 문서만으로 구현 완료된 것으로 간주하지 않는다.

## 16. 후속 구현 검증 기준

- 동시 시작 시 하나만 `ANALYZING`과 고유 버전을 획득한다.
- Snapshot 고정 후 생성된 행동 이벤트와 변경된 RuleVersion이 요청에 섞이지
  않는다.
- FastAPI 호출 중 활성 DB 트랜잭션과 거래 잠금이 없다.
- Client 요청 횟수는 성공·Timeout·오류 모두 정확히 한 번이다.
- 검증 실패 응답은 Evidence와 채택 결과를 남기지 않는다.
- 성공 시 Evidence, `COMPLETED`, 채택과 `ANALYZED`가 모두 commit되거나 모두
  rollback된다.
- 실패 시 결과와 거래가 `FAILED`이고 채택 결과가 없다.
- Timeout 이후 늦은 성공 응답이 terminal 결과를 바꾸지 못한다.
- 성공 멱등 Snapshot은 결과 채택 commit보다 먼저 확정되지 않는다.
- 0점·`LOW`·빈 Evidence는 검증된 정상 all-unmatched 결과일 때만 성공이다.
