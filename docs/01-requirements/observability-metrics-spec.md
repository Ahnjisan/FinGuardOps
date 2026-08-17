# FinGuardOps 관측성 및 운영 메트릭 명세

## 1. 문서 목적

이 문서는 FinGuardOps에서 서비스 상태, 금융 FDS 업무 처리, Rule·ML 분석, AI 리포트, Provider 호출, 비용과 인프라 상태를 일관되게 관측하기 위한 논리 메트릭 계약을 정의한다.

주요 사용자는 다음과 같다.

- FDS 분석 담당자: 거래 접수·탐지·위험 대응·사건·AI 리포트의 업무 처리 현황과 조사 지원 기능의 가용성을 확인한다.
- 플랫폼·클라우드 운영자: Spring Boot, FastAPI, PostgreSQL, AI Provider와 배포 버전별 오류·지연·처리량·비용을 확인하고 장애 범위를 판단한다.

이 문서는 메트릭의 의미와 집계 경계를 정의하는 설계 문서이다. Java, Python, Prometheus, Grafana, OpenTelemetry, Docker, Kubernetes와 AWS 설정 또는 코드를 구현하지 않는다.

## 2. 기준 문서

이 명세는 다음 문서를 전체 대조해 작성했다.

- `docs/01-requirements/platform-operation-requirements.md`
- `docs/02-architecture/system-architecture.md`
- `docs/02-architecture/domain-erd.md`
- `docs/03-api/api-conventions.md`
- `docs/03-api/transaction-detection-api.md`
- `docs/03-api/case-audit-api.md`
- `docs/03-api/ai-report-usage-api.md`
- `docs/03-api/domain-event-contracts.md`
- `docs/01-requirements/transaction-state-transition.md`
- `docs/01-requirements/case-state-transition.md`
- `docs/01-requirements/ai-report-state-transition.md`
- `docs/07-decisions/ADR-003-transaction-processing-boundary.md`

API 요청·응답과 상태 코드는 `docs/03-api/`, 시스템 책임은 `docs/02-architecture/`, 상태 의미는 전용 상태 전이 문서를 기준으로 한다. 문서 사이에 표현 차이가 있으면 이 명세에서 임의로 업무 정책을 확정하지 않고 16절에 기록한다.

## 3. 범위와 적용 상태

### 3.1 적용 상태 분류

이 문서에서 `적용` 열은 메트릭 구현 완료 여부가 아니라 적용 대상을 구분한다.

| 구분 | 의미 |
| --- | --- |
| `현재 최소 범위` | 저장소에서 현재 구현이 확인된 Spring Boot Health API에 우선 적용할 계약. Observability 수집 구현은 아직 완료되지 않았다. |
| `핵심 기능 구현 시` | 문서로 계약되어 있으나 아직 구현되지 않은 거래·탐지·사건·FastAPI·PostgreSQL·AI 리포트 기능과 함께 적용할 계약 |
| `향후 도입 시` | Redis, Kafka, Kubernetes 또는 AWS를 실제로 도입하고 운영 책임이 확정될 때 적용할 계약 |

현재 저장소에서 구현이 확인된 범위는 Spring Boot 초기 설정과 Health API이다. 거래·탐지·사건, FastAPI, PostgreSQL, AI 리포트, Provider 연동과 Observability Stack은 문서 또는 로드맵 범위이며 현재 수집 중인 것으로 표현하지 않는다.

### 3.2 수집 경계

```text
업무 결과를 최종 확정하는 Spring Boot
→ 거래·사건·AI 요청·실행 결과 메트릭

Rule·ML·Provider 계산을 수행하는 FastAPI
→ Rule·ML 단계와 Provider 호출 구간 메트릭

PostgreSQL client·pool 계층
→ 연결, 대기, 오류와 쿼리 지연 메트릭

향후 인프라 수집 계층
→ Redis, Kafka, Kubernetes와 AWS 메트릭
```

동일한 업무 사실을 여러 서비스가 각각 성공으로 계수하지 않는다. FastAPI가 계산 완료를 반환해도 DetectionResult 저장·검증이 끝나기 전에는 Spring Boot의 `탐지 완료`로 계수하지 않는다.

## 4. 명명·유형·단위 원칙

### 4.1 논리 이름과 구현 후보

- `논리 이름`은 제품과 라이브러리에 독립적인 계약 이름이다.
- `구현 후보`는 Prometheus 계열 이름을 사용할 경우의 후보이며 확정된 라이브러리 이름이 아니다.
- Counter 구현 후보는 누적값임을 나타내는 `_total` 접미사를 사용한다.
- 시간 Histogram은 초 단위 `_seconds`, 데이터 크기는 `_bytes`, 토큰은 `_tokens`, 연결과 요청 수는 개수 단위를 사용한다.
- 실제 프레임워크의 자동 계측 이름을 채택할 경우 논리 의미, 라벨 정책과 집계 기준을 유지하고 중복 계측을 피한다.

### 4.2 유형

| 유형 | 사용 원칙 |
| --- | --- |
| Counter | 요청, 완료, 실패, 상태 변경, 호출, 토큰과 비용처럼 누적되는 사건 |
| Gauge | 현재 연결 수, 대기 수, Lag, 준비 상태, CPU와 메모리처럼 오르내리는 현재값 |
| Histogram | API, Rule, ML, DB, Provider와 리포트 처리시간의 분포 |

오류율, 캐시 적중률, fallback 비율과 단위 업무당 비용은 원본 Counter에서 시간 구간별로 계산하는 파생 Gauge이다. 파생값을 애플리케이션에서 별도 누적 Counter로 중복 저장하지 않는다.

### 4.3 시간과 집계 구간

- 시간은 UTC를 기준으로 한다.
- 기간 집계는 `from <= observedAt < to` 반개구간을 권장한다.
- 평균만으로 이상을 판단하지 않고 처리량, 오류율과 지연시간 분포를 함께 본다.
- 백분위, Histogram bucket, scrape 주기와 보존 기간은 구현 전에 별도 승인한다.
- Counter 재시작은 시계열 backend의 rate/increase 계산으로 처리하며 재시작 전후 누적값을 단순 합산하지 않는다.

## 5. 라벨 정책

### 5.1 허용 라벨

| 라벨 | 허용 범위와 제약 |
| --- | --- |
| `service` | `spring-backend`, `fastapi-ai-service`처럼 승인된 낮은 카디널리티 서비스명 |
| `route` | 실제 URL이 아닌 `/api/v1/cases/{caseId}` 같은 route template. `endpoint`와 동시에 사용하지 않고 `route`로 통일하는 것을 권장 |
| `method` | 승인된 HTTP method |
| `status` | HTTP 상태 코드 또는 문서에 정의된 제한된 업무 상태 |
| `result` | `success`, `failure`, `accepted`, `shared`, `cache_hit`, `conflict` 등 메트릭별 허용 목록 |
| `riskLevel` | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `ruleId` | 승인된 Rule registry의 제한된 식별자만 허용. 동적 표현식이나 사용자 입력 금지 |
| `ruleVersion` | 배포·등록된 제한된 Rule 버전 |
| `modelName` | 승인된 모델 이름 |
| `modelVersion` | 배포·라우팅 가능한 제한된 모델 버전 |
| `provider` | 승인된 Provider 이름 |
| `failureCategory` | Timeout, 연결 실패, Provider 오류, 출력 검증 실패 등 안전한 제한 Enum |
| `fallbackType` | `template` 등 승인된 제한 Enum |
| `deploymentVersion` | 빌드 또는 배포 단위의 안정적인 버전. commit 전체 SHA처럼 지나치게 긴 값을 무제한 유지하지 않음 |
| `costCurrency` | 비용 메트릭에만 사용하는 ISO 4217 원통화 코드 후보. 승인된 Provider 통화 목록으로 제한 |

`endpoint`를 선택하는 구현에서도 실제 path가 아니라 route template만 사용한다. `route`와 `endpoint`를 동시에 노출해 같은 차원을 중복 생성하지 않는다.

Kafka 도입 시 `topic`과 `consumerGroup`, AWS 도입 시 `dependency` 같은 추가 운영 차원이 필요할 수 있다. 이들은 17절의 사용자 결정 후 제한된 허용 목록으로만 추가한다.

### 5.2 nullable·비적용 라벨값

같은 메트릭 이름의 시계열은 결과에 따라 라벨 집합을 임의로 생략하거나 변경하지 않는다. 다음 라벨은 값이 존재하지 않을 수 있는 경로에서 아래 기준을 공통 적용한다.

| 라벨 | `unknown` | `not_applicable` | 그 밖의 기본값 |
| --- | --- | --- | --- |
| `riskLevel` | 위험 분석 대상이지만 아직 계산·채택되지 않음 | Health, 캐시 인프라처럼 위험 등급을 사용하지 않는 업무 경로 | 계산·채택된 `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `modelName` | 모델 사용 대상이지만 라우팅·식별이 아직 결정되지 않음 | Rule 전용 처리, 캐시 적중처럼 모델 호출이 적용되지 않는 경로 | 실제 승인된 모델 이름 |
| `modelVersion` | 모델 버전 사용 대상이지만 아직 결정·확인되지 않음 | 모델을 사용하지 않는 업무 경로 | 실제 고정된 모델 버전 |
| `failureCategory` | 실패했지만 승인된 실패 분류로 아직 매핑되지 않음 | 실패 개념 자체가 없는 메트릭 경로 | 실패가 아닌 결과는 `none` |
| `fallbackType` | fallback이 결정되었지만 유형이 아직 확인되지 않음 | fallback을 수행할 수 없는 업무 경로 | fallback이 아닌 결과는 `none` |

- 빈 문자열과 null을 메트릭 라벨값으로 사용하지 않는다.
- `unknown`은 계산·결정·분류 대상이지만 아직 값을 알 수 없는 경우에만 사용한다.
- `not_applicable`은 해당 업무 경로에 그 개념이 적용되지 않는 경우에만 사용한다.
- 실패가 아닌 결과의 `failureCategory`는 `none`, fallback이 아닌 결과의 `fallbackType`은 `none`으로 고정한다.
- 메트릭 표의 허용 라벨에 위 라벨이 포함되어 있고 일부 결과에서 값이 없을 수 있으면 이 절의 대체값을 사용한다.
- 의미 없는 라벨이 대부분의 시계열에 과도하게 붙는 경우에는 라벨을 조건부로 생략하지 않고 성공·실패 또는 시작·결과 메트릭을 별도 이름으로 분리한다.

### 5.3 금지 또는 제한 라벨

다음 값은 메트릭 라벨로 사용하지 않는다.

- `transactionId`
- 행동 이벤트 업무 식별자와 도메인 이벤트 식별자를 포함한 `eventId`
- `caseId`
- `aiRequestId`
- `executionId`
- `attemptId`
- `traceId`
- 고객 식별자와 고객 참조값
- 계좌번호와 계좌 참조값
- 기기·IP 원문
- Prompt 원문
- Provider 요청·응답 원문
- 오류 메시지와 내부 예외 원문
- 조사 메모와 변경 사유 원문

이 식별자들은 단일 요청이나 사건을 찾기 위한 값이므로 집계용 메트릭 라벨이 아니라 로그 필드와 Span attribute에서 사용한다. 메트릭 exemplar를 도입하더라도 사용자에게 노출 가능한 `traceId`만 보안·샘플링 정책 승인 후 제한적으로 연결한다.

### 5.4 라벨 조합 제한

- 모든 허용 라벨을 모든 메트릭에 붙이지 않는다.
- `ruleId × ruleVersion × modelName × modelVersion × route × deploymentVersion`처럼 불필요한 곱집합을 만들지 않는다.
- `deploymentVersion`은 배포 비교가 필요한 서비스·업무 결과 메트릭에만 사용한다.
- 원문 오류를 `failureCategory`로 변환하지 못하면 `unknown`으로 계수하고 원인은 로그에서 확인한다.
- 사용되지 않는 과거 버전 시계열의 보존과 삭제는 관측 backend의 보존 정책으로 관리한다.

## 6. Spring Boot API와 업무 처리 메트릭

| 논리 이름 | 구현 후보 | 목적 | 수집 주체 | 유형 | 단위 | 증가·관측 시점 | 허용 라벨 | 집계 기준 | 대시보드 활용 | 알림 활용 | 적용 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `spring.http.requests` | `finguardops_http_server_requests_total` | API 요청 수와 처리량 확인 | Spring Boot HTTP 경계 | Counter | 요청 건 | HTTP 응답 상태가 확정될 때 1 증가 | `service`, `route`, `method`, `status`, `deploymentVersion` | 실제 path가 아닌 route template별 요청. 같은 요청을 filter와 controller에서 중복 계수하지 않음 | API 처리량, 상태 코드 분포, 배포 전후 비교 | 요청 급감·급증은 기준선 측정 후 결정 | Health는 `현재 최소 범위`, 나머지는 `핵심 기능 구현 시` |
| `spring.http.duration` | `finguardops_http_server_request_duration_seconds` | 사용자 관점 API 응답시간 분포 확인 | Spring Boot HTTP 경계 | Histogram | 초 | 요청 수신부터 응답 완료까지 한 번 관측 | `service`, `route`, `method`, `status`, `deploymentVersion` | route·method·status별 분포. 비동기 AI 생성은 `202` 접수시간이며 최종 생성시간과 분리 | p50·p95·p99 후보, 느린 route, 배포 비교 | 임계값은 부하 테스트 후 결정 | Health는 `현재 최소 범위`, 나머지는 `핵심 기능 구현 시` |
| `spring.http.errors` | `finguardops_http_server_errors_total` 또는 `spring.http.requests`의 오류 상태 필터 | HTTP 상태별 오류 수 확인 | Spring Boot HTTP 경계 | Counter | 오류 응답 건 | 4xx·5xx 응답 확정 시 1 증가 | `service`, `route`, `method`, `status`, `failureCategory`, `deploymentVersion` | 가능하면 HTTP 요청 Counter에서 파생해 중복 계측을 피함. 4xx와 5xx를 분리 | 오류율, 오류 코드 계열, 영향 route | 기준선 측정 후 결정. 5xx와 4xx의 심각도는 분리 | Health는 `현재 최소 범위`, 나머지는 `핵심 기능 구현 시` |
| `spring.transaction.intake_outcomes` | `finguardops_transaction_intake_outcomes_total` | 거래 접수 성공·검증 거부·저장 실패와 기존 결과 반환을 구분 | Spring Boot 거래 접수 Service | Counter | 접수 시도 건 | 거래 생성 HTTP 요청의 접수 결과가 `accepted`, `validation_rejected`, `persistence_failed`, `idempotent_replay`, `conflict` 중 하나로 확정될 때 | `service`, `result`, `failureCategory`, `deploymentVersion` | 거래 접수 경계까지 도달한 HTTP 요청 시도별 한 번. 기본 검증 거부, 저장 실패, 멱등 재전송과 충돌을 포함하며 새 Transaction 생성 수와 별도로 집계. 같은 멱등 요청 재전송은 intake outcome과 duplicate request에 각각 기술적 의미로 계수할 수 있지만 새 Transaction이나 새 업무 결과로 계수하지 않음 | 접수 성공률, 검증·저장·중복 결과 분포 | 실패율은 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `spring.transaction.received` | `finguardops_transactions_received_total` | 최초 거래 접수량 확인 | Spring Boot 거래 Service | Counter | 거래 건 | 기본 검증을 통과한 Transaction이 최초 `RECEIVED`로 확정될 때 | `service`, `result`, `deploymentVersion` | 동일 멱등 요청 재전송은 증가하지 않음. `TransactionReceived` 업무 사실과 1:1 | 거래 유입량, 시간대별 처리량 | 기준선 대비 접수 급감은 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `spring.transaction.outcomes` | `finguardops_transaction_outcomes_total` | 거래 처리 성공·실패와 최종 Mock 대응 확인 | Spring Boot 거래 Service | Counter | 거래 건 | 거래가 `APPROVED`, `ADDITIONAL_AUTH_REQUIRED`, `HELD`, `FAILED` 등 승인된 종료 결과로 최초 확정될 때 | `service`, `status`, `result`, `riskLevel`, `deploymentVersion` | Transaction별 해당 처리 버전의 최초 확정만 계수. AI 리포트 실패는 거래 실패로 계수하지 않음 | 승인·추가 인증·보류·실패 분포 | 실패율은 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `spring.transaction.processing_duration` | `finguardops_transaction_processing_duration_seconds` | 거래 접수부터 위험 대응 확정까지의 업무 지연 확인 | Spring Boot 거래 Service | Histogram | 초 | 최초 접수 시각부터 최종 거래 처리 결과 확정까지 관측 | `service`, `result`, `riskLevel`, `deploymentVersion` | 완료된 Transaction 단위. HTTP 재전송 대기시간을 복제하지 않음 | 거래 처리시간 분포와 병목 비교 | 임계값은 부하 테스트 후 결정 | `핵심 기능 구현 시` |
| `spring.detection.requests` | `finguardops_detection_requests_total` | 승인된 탐지 시작 요청량 확인 | Spring Boot 탐지 오케스트레이션 | Counter | 탐지 요청 건 | `transactionId+detectionResultVersion` 분석 시작이 최초 승인될 때 | `service`, `result`, `deploymentVersion` | 동일 버전 중복 요청·늦은 재전달은 증가하지 않음 | 탐지 입력량과 완료량 비교 | 요청 대비 완료 감소는 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `spring.detection.outcomes` | `finguardops_detection_outcomes_total` | 탐지 완료·실패 결과 확인 | Spring Boot 탐지 Service | Counter | 탐지 결과 건 | Spring Boot가 FastAPI 결과를 검증·저장해 완료하거나 실패를 최종 확정할 때 | `service`, `result`, `riskLevel`, `modelVersion`, `failureCategory`, `deploymentVersion` | `transactionId+detectionResultVersion`별 한 번. FastAPI 응답 수와 별개 | 완료율, 실패 원인, 위험 등급 분포 | 실패율은 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `spring.detection.orchestration_duration` | `finguardops_detection_orchestration_duration_seconds` | Spring Boot 관점 전체 탐지 소요시간 확인 | Spring Boot 탐지 오케스트레이션 | Histogram | 초 | 분석 시작 승인부터 DetectionResult 완료·실패 확정까지 | `service`, `result`, `riskLevel`, `deploymentVersion` | 탐지 결과 버전 단위. FastAPI 내부 Rule·ML 시간과 분리 | 전체 분석시간과 FastAPI 내부시간 비교 | 임계값은 부하 테스트 후 결정 | `핵심 기능 구현 시` |
| `spring.risk_response.outcomes` | `finguardops_risk_response_outcomes_total` | 승인된 위험 대응 결과 확인 | Spring Boot 위험 대응 Service | Counter | 대응 건 | 채택 DetectionResult와 `riskResponseOutcome`, 최종 처리 상태가 최초 확정될 때 | `service`, `result`, `riskLevel`, `deploymentVersion` | 동일 채택 결과의 반복 적용은 증가하지 않음 | 위험 등급별 Mock 대응 분포 | 정책 이탈 또는 결과 급변은 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `spring.cases.created` | `finguardops_cases_created_total` | 사건 신규 생성량 확인 | Spring Boot 사건 Service | Counter | 사건 건 | FraudCase와 최초 CaseTransaction 연결이 정합하게 확정될 때 | `service`, `riskLevel`, `result`, `deploymentVersion` | 새 `caseId`별 한 번. 기존 사건 연결은 신규 생성으로 계수하지 않음 | 신규 사건량, 위험 등급별 대기 유입 | 중복 생성·급증은 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `spring.case.status_change_outcomes` | `finguardops_case_status_change_outcomes_total` | 사건 상태 변경 성공과 거부된 시도의 결과 확인 | Spring Boot 사건 Service | Counter | 상태 변경 시도 건 | 성공 전이 또는 승인된 거부 결과가 확정될 때 | `service`, `status`, `result`, `failureCategory`, `deploymentVersion` | 성공과 거부를 `result`로 분리. 거부는 실제 상태 변경으로 해석하지 않으며 같은 멱등 종료 재전송은 새 변경으로 계수하지 않음 | 사건 흐름, 전이 충돌, 종료 처리량 | 충돌·거부율은 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `spring.http.duplicate_requests` | `finguardops_http_duplicate_requests_total` | 동일 멱등 요청 재전송과 업무 중복 방지 동작 확인 | Spring Boot 멱등성 경계 | Counter | 중복 요청 건 | 같은 키+같은 fingerprint가 기존 진행·완료·실패 요청으로 판별될 때 | `service`, `route`, `method`, `result`, `deploymentVersion` | HTTP 재전송 1건당 증가. 새 Transaction·AiReportRequest·상태 변경으로 계수하지 않음 | 재전송량, 진행/완료 결과 재사용 분포 | 비정상 급증은 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `spring.http.idempotency_conflicts` | `finguardops_http_idempotency_conflicts_total` | 같은 키에 다른 fingerprint가 들어온 충돌 확인 | Spring Boot 멱등성 경계 | Counter | 충돌 건 | `IDEMPOTENCY_KEY_CONFLICT`가 확정될 때 | `service`, `route`, `method`, `result`, `deploymentVersion` | 충돌 HTTP 요청별 1회. 키 원문은 라벨에 기록하지 않음 | API·클라이언트별 충돌 추세 | 기준선 측정 후 결정 | `핵심 기능 구현 시` |

`spring.http.errors`는 `spring.http.requests`에서 계산할 수 있으면 별도 Counter를 만들지 않는 것을 권장한다. 문서상 논리 지표는 유지하되 하나의 HTTP 요청이 두 독립 계측 경로에서 서로 다른 값으로 집계되지 않도록 한다.

## 7. FastAPI Rule·ML 분석 메트릭

| 논리 이름 | 구현 후보 | 목적 | 수집 주체 | 유형 | 단위 | 증가·관측 시점 | 허용 라벨 | 집계 기준 | 대시보드 활용 | 알림 활용 | 적용 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `fastapi.rule.evaluations` | `finguardops_rule_evaluations_total` | Rule별 실행량과 결과 확인 | FastAPI Rule Service | Counter | Rule 평가 건 | 승인된 Rule 평가가 종료될 때 | `service`, `ruleId`, `ruleVersion`, `result`, `failureCategory`, `deploymentVersion` | 거래 분석 1건 안에서 실제 평가한 Rule마다 1회. Rule registry 값만 라벨 허용 | 느리거나 실패하는 Rule, 버전별 처리량 | 실패율은 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `fastapi.rule.duration` | `finguardops_rule_evaluation_duration_seconds` | Rule 처리시간 분포 확인 | FastAPI Rule Service | Histogram | 초 | 개별 Rule 평가 시작부터 종료까지 | `service`, `ruleId`, `ruleVersion`, `result`, `deploymentVersion` | 개별 Rule 평가 단위. 전체 Rule 묶음 시간과 혼합하지 않음 | Rule별 p95 후보, 버전 비교 | 임계값은 부하 테스트 후 결정 | `핵심 기능 구현 시` |
| `fastapi.rule.failures` | `finguardops_rule_failures_total` 또는 Rule 평가 Counter의 실패 필터 | Rule 실패 유형 확인 | FastAPI Rule Service | Counter | 실패 건 | Rule 평가가 예외·Timeout·승인된 실패로 종료될 때 | `service`, `ruleId`, `ruleVersion`, `failureCategory`, `deploymentVersion` | 가능하면 평가 Counter에서 파생. 오류 원문은 로그로 보냄 | 실패 Rule과 배포 버전 확인 | 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `fastapi.ml.inferences` | `finguardops_ml_inferences_total` | 모델·버전별 추론량과 성공 상태 확인 | FastAPI ML Service | Counter | 추론 건 | 실제 ML 추론이 종료될 때 | `service`, `modelName`, `modelVersion`, `result`, `failureCategory`, `deploymentVersion` | 실제 추론 실행 단위. 모델 미도입 시 0이 아니라 미수집으로 표시 | 모델별 처리량·실패 비교 | 실패율은 기준선 측정 후 결정 | ML 도입 후 `핵심 기능 구현 시` |
| `fastapi.ml.duration` | `finguardops_ml_inference_duration_seconds` | ML 추론시간 분포 확인 | FastAPI ML Service | Histogram | 초 | 모델 입력 준비 완료부터 추론 결과 반환까지 | `service`, `modelName`, `modelVersion`, `result`, `deploymentVersion` | 실제 추론 단위. Feature 계산시간과 분리 | 모델·버전별 지연 비교 | 임계값은 부하 테스트 후 결정 | ML 도입 후 `핵심 기능 구현 시` |
| `fastapi.ml.failures` | `finguardops_ml_inference_failures_total` 또는 추론 Counter의 실패 필터 | ML 실패 유형 확인 | FastAPI ML Service | Counter | 실패 건 | 추론 실패가 확정될 때 | `service`, `modelName`, `modelVersion`, `failureCategory`, `deploymentVersion` | 가능하면 추론 Counter에서 파생 | 모델 장애와 버전 회귀 확인 | 기준선 측정 후 결정 | ML 도입 후 `핵심 기능 구현 시` |
| `fastapi.analysis.duration` | `finguardops_analysis_duration_seconds` | Feature·Rule·ML을 포함한 FastAPI 전체 분석시간 확인 | FastAPI 분석 Service | Histogram | 초 | 분석 요청 검증 후 계산 시작부터 응답 생성까지 | `service`, `result`, `riskLevel`, `modelVersion`, `failureCategory`, `deploymentVersion` | FastAPI가 실제 처리한 분석 요청 단위. Spring Boot 저장시간 제외 | 전체 분석 지연과 내부 단계 비교 | 임계값은 부하 테스트 후 결정 | `핵심 기능 구현 시` |
| `fastapi.analysis.outcomes` | `finguardops_analysis_outcomes_total` | FastAPI 분석 완료·실패 확인 | FastAPI 분석 Service | Counter | 분석 건 | 분석 응답 성공 또는 실패가 확정될 때 | `service`, `result`, `riskLevel`, `modelVersion`, `failureCategory`, `deploymentVersion` | FastAPI 처리 시도 단위. Spring Boot의 DetectionResult 완료 Counter와 동일 지표가 아님 | 요청·완료·실패, 모델 버전 비교 | 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `fastapi.client.failures` | `finguardops_fastapi_client_failures_total` | Spring Boot에서 본 FastAPI Timeout·연결 실패 확인 | Spring Boot FastAPI client 경계 | Counter | 호출 실패 건 | FastAPI 호출이 Timeout 또는 연결 실패로 종료될 때 | `service`, `result`, `failureCategory`, `deploymentVersion` | 실제 client 호출 시도별 1회. 거래 최종 실패와 별개 | 호출자 관점 의존성 장애 | 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `fastapi.component.ready` | `finguardops_ai_component_ready` | Rule·모델 버전의 로드·준비 상태 확인 | FastAPI 시작·상태 점검 계층 | Gauge | 0 또는 1 | 승인된 Rule 또는 모델 구성의 준비 상태가 바뀔 때 | `service`, `ruleVersion` 또는 `modelName`·`modelVersion`, `deploymentVersion` | 서로 다른 component 유형을 한 시계열에 무제한 혼합하지 않음 | 배포 버전별 Rule·모델 준비 상태 | 준비 상태 0의 지속 시간은 기준선 측정 후 결정 | `핵심 기능 구현 시` |

`fastapi.client.failures`는 호출자 관점의 네트워크 결과이고 `fastapi.analysis.outcomes`는 FastAPI가 실제 처리한 결과이다. 연결에 실패해 FastAPI에 도달하지 않은 요청을 FastAPI 내부 실패로 중복 계수하지 않는다.

## 8. AI 리포트·실행·ProviderCallAttempt 메트릭

### 8.1 집계 불변식

```text
AiReportRequest
→ 외부 요청 수, 진행 실행 공유 수, 캐시 적중 수

AiReportExecution
→ 신규 논리 실행 수, 실행 종료 결과와 리포트 생성시간

ProviderCallAttempt
→ 실제 Provider 호출 수, 호출 지연, 토큰과 비용의 원본
```

- 토큰과 비용의 원본은 중복 제거된 실제 `ProviderCallAttempt`이다.
- `AiReportExecution`은 attempt를 묶는 실행 단위이며 실행 자체를 별도 Provider 호출이나 비용으로 합산하지 않는다.
- 실행 공유 요청별로 attempt, 토큰 또는 비용을 복제하지 않는다.
- 캐시 적중 요청은 새 `AiReportExecution`과 `ProviderCallAttempt`가 없으므로 신규 비용이 없다.
- template fallback은 기존 실행 안의 결과 처리이며 새 `AiReportRequest`, 새 `AiReportExecution` 또는 가상 attempt가 아니다.
- 같은 `Idempotency-Key`의 재전송은 기존 `AiReportRequest`를 반환하므로 `AiReportRequest 접수 수`를 다시 증가시키지 않는다.

### 8.2 원본 메트릭

| 논리 이름 | 구현 후보 | 목적 | 수집 주체 | 유형 | 단위 | 증가·관측 시점 | 허용 라벨 | 집계 기준 | 대시보드 활용 | 알림 활용 | 적용 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `ai.report.requests.accepted` | `finguardops_ai_report_requests_accepted_total` | 새 외부 AiReportRequest 접수량 확인 | Spring Boot AI operations Service | Counter | 요청 건 | 새 `AiReportRequest`와 `aiRequestId`가 영속적으로 확정될 때 | `service`, `result`, `riskLevel`, `deploymentVersion` | 새 요청 행 기준. 같은 키 재전송은 제외 | 요청량, 상태별 유입 | 요청 급증은 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `ai.report.executions.created` | `finguardops_ai_report_executions_created_total` | 신규 실제 실행 수 확인 | Spring Boot AI operations Service | Counter | 실행 건 | 새 `AiReportExecution`이 생성되고 최초 요청과 연결될 때 | `service`, `modelVersion`, `deploymentVersion` | distinct `executionId` 기준. 공유·캐시 요청은 제외 | 요청 수 대비 실행 수 | 실행 급증은 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `ai.report.execution_shared_requests` | `finguardops_ai_report_execution_shared_requests_total` | 진행 중 실행을 공유한 외부 요청 수 확인 | Spring Boot AI operations Service | Counter | 요청 건 | 새 AiReportRequest가 기존 활성 실행에 연결될 때 | `service`, `modelVersion`, `deploymentVersion` | `executionShared=true`인 새 요청 기준. 동일 키 재전송 제외 | 동시 요청 흡수 효과, 실행 중복 방지 | 비정상 급증은 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `ai.report.cache_hits` | `finguardops_ai_report_cache_hits_total` | 완료 리포트 정확 일치 재사용 수 확인 | Spring Boot AI operations Service | Counter | 요청 건 | 새 요청이 `cacheHit=true`, `executionId=null`로 종결될 때 | `service`, `modelVersion`, `result`, `deploymentVersion` | 새 AiReportRequest 기준. 캐시 원본 상태는 `result=completed` 또는 `fallback_completed` 후보 | 캐시 적중량과 적중률 | 적중률 급락은 기준선 측정 후 결정 | 논리 캐시는 `핵심 기능 구현 시`, Redis 저장은 `향후 도입 시` |
| `ai.report.execution_outcomes` | `finguardops_ai_report_execution_outcomes_total` | 정상 완료·fallback 완료·최종 실패 실행 수 확인 | Spring Boot AI operations Service | Counter | 실행 건 | 실행이 `COMPLETED`, `FALLBACK_COMPLETED`, `FAILED` 중 하나로 최초 종료될 때 | `service`, `result`, `modelVersion`, `failureCategory`, `fallbackType`, `deploymentVersion` | distinct `executionId`별 종료 상태 한 번. 연결 요청 수만큼 복제하지 않음 | 생성 완료, fallback, 실패 비율 | 실패·fallback 증가는 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `ai.report.generated` | `finguardops_ai_reports_generated_total` | 사용 가능한 AiReport 결과 생성 수 확인 | Spring Boot AI operations Service | Counter | 리포트 건 | 검증된 `AiReport`가 `LLM` 또는 `TEMPLATE_FALLBACK` 출처로 저장될 때 | `service`, `result`, `modelVersion`, `fallbackType`, `deploymentVersion` | distinct `reportId` 기준. 캐시 재사용은 새 생성으로 계수하지 않음 | 실제 생성량과 출처 | 생성 급감·fallback 증가 확인 | `핵심 기능 구현 시` |
| `ai.report.fallbacks` | `finguardops_ai_report_fallbacks_total` | template fallback 결과와 원인 확인 | Spring Boot AI operations Service | Counter | fallback 완료 건 | 같은 실행 안에서 template 결과가 사용 가능해 `FALLBACK_COMPLETED`로 확정될 때 | `service`, `fallbackType`, `failureCategory`, `modelVersion`, `deploymentVersion` | distinct 실행당 최대 한 번. 공유 요청·캐시 요청 수만큼 복제하지 않음 | 원인별 fallback 실행 수 | 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `ai.report.final_failures` | `finguardops_ai_report_final_failures_total` | LLM과 fallback이 모두 실패한 실행 확인 | Spring Boot AI operations Service | Counter | 실패 실행 건 | 실행이 최종 `FAILED`로 최초 확정될 때 | `service`, `failureCategory`, `modelVersion`, `deploymentVersion` | distinct 실행 기준. 기존 유효 리포트가 유지되어도 실패 실행은 계수 | 최종 실패율, 원인 분포 | 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `ai.report.generation_duration` | `finguardops_ai_report_generation_duration_seconds` | 신규 실행의 실제 생성시간 확인 | Spring Boot AI operations Service | Histogram | 초 | `AiReportExecution.startedAt`부터 `COMPLETED`, `FALLBACK_COMPLETED`, `FAILED` 중 최초 종료 상태 확정 시각까지 | `service`, `result`, `modelVersion`, `fallbackType`, `deploymentVersion` | distinct 실행 단위. 실행 생성 후 시작 전 대기시간은 제외. 캐시 요청은 실행이 없어 제외하고 공유 요청의 개별 대기시간은 request duration에서만 관측 | 전체 생성 p95 후보, 정상·fallback 비교 | 임계값은 부하 테스트 후 결정 | `핵심 기능 구현 시` |
| `ai.report.request_duration` | `finguardops_ai_report_request_duration_seconds` | 외부 요청 접수부터 요청 종료까지의 사용자 경험 확인 | Spring Boot AI operations Service | Histogram | 초 | 각 새 AiReportRequest가 종료될 때 | `service`, `result`, `modelVersion`, `deploymentVersion` | 요청 단위. 공유 요청은 자신의 대기시간을 한 번 관측하고 비용은 복제하지 않음. 캐시는 즉시 종료시간 관측 가능 | 신규·공유·캐시 요청 대기 비교 | 임계값은 부하 테스트 후 결정 | `핵심 기능 구현 시` |
| `ai.provider.calls_started` | `finguardops_ai_provider_calls_started_total` | 실제 외부 Provider 네트워크 호출 시작 수 확인 | FastAPI Provider client 경계 | Counter | 호출 시도 건 | FastAPI Provider client가 실제 외부 Provider 호출을 시작할 때 | `service`, `provider`, `modelName`, `modelVersion`, `deploymentVersion` | 실제 네트워크 호출 시도 기준. 시작 시점에는 결과와 실패 원인을 알 수 없으므로 `result`, `failureCategory`를 사용하지 않음. template fallback과 Provider 호출 전 FastAPI 실패는 제외 | 시작 수, 미완료 호출 조사 | 결과 수와 차이는 기준선 측정 후 조사 | `핵심 기능 구현 시` |
| `ai.provider.call_outcomes` | `finguardops_ai_provider_call_outcomes_total` | 실제 Provider 호출의 성공·실패·Timeout 결과 확인 | FastAPI Provider client 경계 | Counter | 호출 결과 건 | 실제 외부 Provider 호출이 성공, 실패 또는 Timeout으로 종료될 때 | `service`, `provider`, `modelName`, `modelVersion`, `result`, `failureCategory`, `deploymentVersion` | 호출 결과가 확정된 실제 attempt 기준. FastAPI 기술 Counter로 한 번만 증가시키며 Spring Boot의 ProviderCallAttempt 영속 시 같은 메트릭을 다시 증가시키지 않음 | Provider·모델별 완료·실패와 시작 대비 결과 수 | 실패율과 미완료 차이는 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `ai.provider.duration` | `finguardops_ai_provider_call_duration_seconds` | 실제 Provider 호출 지연시간 확인 | FastAPI Provider client 경계 | Histogram | 초 | Provider 요청 시작부터 성공·실패·Timeout 결과 확정까지 | `service`, `provider`, `modelName`, `modelVersion`, `result`, `failureCategory`, `deploymentVersion` | 실제 호출 결과가 확인된 attempt에 대해 한 번만 관측. 결과가 확인되지 않은 미완료 호출에는 관측값을 만들지 않음 | Provider·모델별 p95·p99 후보 | 임계값은 부하 테스트 후 결정 | `핵심 기능 구현 시` |
| `ai.provider.input_tokens` | `finguardops_ai_provider_input_tokens_total` | 실제 입력 토큰 누적량 확인 | ProviderCallAttempt 영속 집계 계층 | Counter | 토큰 | attempt 종료 후 Provider가 확인한 입력 토큰이 있을 때 그 값만 증가 | `service`, `provider`, `modelName`, `modelVersion`, `result`, `deploymentVersion` | distinct attempt 기준. null을 0으로 만들지 않고 캐시·fallback 가상 토큰을 생성하지 않음 | 모델별 입력 토큰, 비용 원인 | 비용 예산 확정 후 결정 | `핵심 기능 구현 시` |
| `ai.provider.output_tokens` | `finguardops_ai_provider_output_tokens_total` | 실제 출력 토큰 누적량 확인 | ProviderCallAttempt 영속 집계 계층 | Counter | 토큰 | attempt 종료 후 Provider가 확인한 출력 토큰이 있을 때 그 값만 증가 | `service`, `provider`, `modelName`, `modelVersion`, `result`, `deploymentVersion` | distinct attempt 기준. null을 0으로 만들지 않음 | 모델별 출력 토큰, 출력 제한 영향 | 비용 예산 확정 후 결정 | `핵심 기능 구현 시` |
| `ai.provider.estimated_cost` | `finguardops_ai_provider_estimated_cost_total` | 실제 호출 사용량 기반 추정 비용 확인 | ProviderCallAttempt 영속 집계 계층 | Counter | `costCurrency`별 통화 금액 | attempt의 `estimatedCost`와 `costCurrency`가 확인될 때 해당 원통화 값만 증가 | `service`, `provider`, `modelName`, `modelVersion`, `costCurrency`, `result`, `deploymentVersion` | distinct attempt 기준. 서로 다른 통화는 별도 시계열이며 환율 없이 합산 금지. 추정값이며 실제 청구액 아님 | 모델·통화별 비용, 예산 추세 | 비용 예산 확정 후 결정 | `핵심 기능 구현 시` |
| `ai.report.quality_validations` | `finguardops_ai_report_quality_validations_total` | 승인된 품질 검증 통과·실패 확인 | FastAPI 출력 검증 계층, 최종 결과는 Spring Boot 검증 | Counter | 품질 평가 건 | 승인된 품질 검증이 실제 수행되어 pass 또는 fail로 확정될 때 | `service`, `modelName`, `modelVersion`, `result`, `failureCategory`, `deploymentVersion` | 실제 검증 수행 결과 기준. 미정 기준은 `not_evaluated`로 성공 분모에 넣지 않음 | 모델·버전별 품질 통과율 | 기준과 목표는 품질 기준 확정 후 결정 | 품질 정책 확정 후 `핵심 기능 구현 시` |

Provider 호출 계측 경계에는 다음 원칙을 적용한다.

- `ai.provider.calls_started`와 `ai.provider.call_outcomes`는 FastAPI Provider client의 기술 운영 Counter이다.
- FastAPI 기술 Counter와 Spring Boot에 영속된 `ProviderCallAttempt`를 같은 메트릭으로 중복 증가시키지 않는다.
- 호출 시작 수와 호출 결과 수의 차이는 프로세스 장애, 응답 유실 또는 아직 종료되지 않은 호출 조사에 사용한다.
- `ProviderCallAttempt`는 확인된 토큰과 비용의 감사 및 FinOps 원본이다. 영속되지 않은 호출에 대해 토큰이나 비용 원본을 추정해 만들지 않는다.
- 비용·토큰의 업무 집계 원본은 기존 정책대로 중복 제거된 distinct `ProviderCallAttempt`이다.
- template fallback과 FastAPI가 Provider 호출 전에 실패한 경우에는 Provider 호출 시작으로 계수하지 않는다.
- Provider duration은 실제 호출 결과가 확인된 attempt에 대해 한 번만 관측한다.
- 실행 시작 전 대기시간이 필요하면 향후 별도 `ai.report.queue_duration` 메트릭으로 정의한다.

## 9. FinOps 파생 지표

FinOps 파생 Gauge는 애플리케이션이 직접 누적하거나 현재값을 설정하는 원본 Gauge가 아니다. 다음 원칙을 적용한다.

- 원본 Counter와 업무 데이터의 같은 UTC 시간 구간에서 계산한다.
- Prometheus recording rule, Grafana query 또는 승인된 운영 집계 작업으로 계산한다.
- 대시보드와 응답에는 계산 시간 구간을 반드시 명확히 표시한다.
- 파생값을 애플리케이션 원본 메트릭으로 중복 발행하지 않는다.
- 분모가 0이면 0으로 단정하지 않고 값 없음으로 처리한다.
- 요청별 상세 화면에 투영된 attempts를 합산하지 않고 distinct `ProviderCallAttempt`를 사용한다.
- 서로 다른 통화는 계속 분리하며 환율 없이 합산하지 않는다.
- 미측정 토큰과 비용을 0으로 계산하지 않는다.

| 논리 이름 | 구현 후보 | 목적 | 수집 주체 | 유형 | 단위 | 증가·관측 시점 | 허용 라벨 | 집계 기준 | 대시보드 활용 | 알림 활용 | 적용 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `finops.provider_calls_per_1000_transactions` | `finguardops_ai_provider_calls_per_1000_transactions` | 거래량 대비 실제 LLM 호출 정책 확인 | 운영 집계 계층 | Gauge | 거래 1,000건당 attempt 건 | 선택 시간 구간 조회 시 계산 | `provider`, `modelName`, `modelVersion`, `deploymentVersion` 후보 | `distinct ProviderCallAttempt 수 / 최초 접수 Transaction 수 × 1000`. 분모 0이면 값 없음 | 호출 정책·재시도 영향 | 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `finops.estimated_ai_cost_per_1000_transactions` | `finguardops_ai_estimated_cost_per_1000_transactions` | 거래량 대비 예상 AI 비용 확인 | 운영 집계 계층 | Gauge | `costCurrency`별 통화 금액/1,000거래 | 선택 시간 구간 조회 시 계산 | `provider`, `modelName`, `modelVersion`, `costCurrency`, `deploymentVersion` 후보 | `distinct attempts의 통화별 비용 합 / 최초 접수 Transaction 수 × 1000`. 환율 없이 통화별 분리 | 비용 효율과 트래픽 영향 | 비용 예산 확정 후 결정 | `핵심 기능 구현 시` |
| `finops.estimated_ai_cost_per_case` | `finguardops_ai_estimated_cost_per_case` | 사건당 실제 AI 실행 비용 확인 | 운영 집계 계층 | Gauge | `costCurrency`별 통화 금액/사건 | 선택 시간 구간 조회 시 계산 | `provider`, `modelName`, `modelVersion`, `costCurrency` 후보 | 구간 내 attempts의 통화별 비용 합 / AI 실행과 연결된 distinct 사건 수. 공유 요청 비용 복제 금지 | 사건 복잡도·모델별 비용 비교 | 비용 예산 확정 후 결정 | `핵심 기능 구현 시` |
| `finops.model_call_ratio` | `finguardops_ai_model_call_ratio` | 실제 Provider 호출의 모델별 비율 확인 | 운영 집계 계층 | Gauge | 비율 0~1 | 선택 시간 구간 조회 시 계산 | `provider`, `modelName`, `modelVersion` | 해당 모델 distinct attempts / 전체 distinct attempts | 라우팅 쏠림과 모델 전환 확인 | 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `finops.cache_hit_ratio` | `finguardops_ai_report_cache_hit_ratio` | 외부 AI 요청 중 완료 결과 재사용 비율 확인 | 운영 집계 계층 | Gauge | 비율 0~1 | 선택 시간 구간 조회 시 계산 | `modelVersion`, `deploymentVersion` 후보 | `cacheHit=true인 새 AiReportRequest / 새 AiReportRequest`. 동일 키 재전송 제외 | 정확 일치 캐시 효과와 원본 호출 변화 | 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `finops.fallback_execution_ratio` | `finguardops_ai_report_fallback_execution_ratio` | 실제 실행 중 template fallback 결과 비율 확인 | 운영 집계 계층 | Gauge | 비율 0~1 | 선택 시간 구간 조회 시 계산 | `modelVersion`, `failureCategory`, `fallbackType`, `deploymentVersion` 후보 | `FALLBACK_COMPLETED distinct executions / 종료된 distinct executions`. 공유·캐시 요청을 분모·분자에 복제하지 않음 | Provider·품질 장애와 fallback 의존도 | 기준선 측정 후 결정 | `핵심 기능 구현 시` |
| `finops.report_quality_pass_ratio` | `finguardops_ai_report_quality_pass_ratio` | 승인된 리포트 품질 기준 통과율 확인 | 운영 집계 계층 | Gauge | 비율 0~1 | 선택 시간 구간 조회 시 계산 | `modelName`, `modelVersion`, `deploymentVersion` | `pass 품질 평가 / 실제 완료된 품질 평가`. 미평가 결과 제외 | Cost·Latency·Quality 동시 비교 | 품질 기준과 기준선 확정 후 결정 | 품질 정책 확정 후 `핵심 기능 구현 시` |
| `finops.estimated_cost_by_currency` | `finguardops_ai_provider_estimated_cost_total`의 통화별 increase | 환율 없이 통화별 예상 비용 합계 확인 | 운영 집계 계층 | Gauge | `costCurrency`별 통화 금액 | 선택 시간 구간 조회 시 계산 | `provider`, `modelName`, `modelVersion`, `costCurrency` | distinct attempts의 확인된 비용만 통화별 합산. 일부 attempt 비용이 미측정이면 완전·불완전 집계를 구분 | USD·KRW 등 통화별 비용 표 | 비용 예산 확정 후 결정 | `핵심 기능 구현 시` |

### 9.1 비용 완전성

- Provider가 확인한 실제 토큰을 우선한다.
- 확인하지 못한 토큰과 비용은 0으로 단정하지 않는다.
- 비용이 있는 attempt에는 `costCurrency`가 필요하다.
- 일부 attempt의 비용 또는 통화가 누락되면 확인된 일부 합계를 전체 비용처럼 표시하지 않는다.
- 서로 다른 통화는 `costCurrency`별로 분리하며 승인된 가격·환율 정책 없이 단일 금액으로 합치지 않는다.
- 캐시 요청은 새 attempt가 없으므로 신규 토큰과 비용이 없다.
- fallback 전에 실제 Provider 호출이 있었다면 성공·실패와 관계없이 확인된 토큰·비용을 포함한다.
- template fallback 자체에는 Provider 비용을 만들지 않는다.

## 10. PostgreSQL과 데이터 접근 메트릭

| 논리 이름 | 구현 후보 | 목적 | 수집 주체 | 유형 | 단위 | 증가·관측 시점 | 허용 라벨 | 집계 기준 | 대시보드 활용 | 알림 활용 | 적용 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `postgres.pool.active` | `finguardops_db_pool_active_connections` | 현재 사용 중인 DB 연결 수 확인 | Spring Boot DB Pool 계층 | Gauge | 연결 수 | Pool 상태 수집 시 | `service`, `deploymentVersion` | 인스턴스별 원본을 서비스 합계·최댓값으로 구분 | Pool 사용량과 포화 추세 | 임계값은 부하 테스트 후 결정 | PostgreSQL 연동 시 `핵심 기능 구현 시` |
| `postgres.pool.idle` | `finguardops_db_pool_idle_connections` | 즉시 사용 가능한 유휴 연결 수 확인 | Spring Boot DB Pool 계층 | Gauge | 연결 수 | Pool 상태 수집 시 | `service`, `deploymentVersion` | active와 같은 Pool 범위 | 가용 연결과 설정 검토 | 임계값은 부하 테스트 후 결정 | PostgreSQL 연동 시 `핵심 기능 구현 시` |
| `postgres.pool.pending` | `finguardops_db_pool_pending_requests` | 연결 획득 대기 요청 수 확인 | Spring Boot DB Pool 계층 | Gauge | 대기 요청 수 | Pool 상태 수집 시 | `service`, `deploymentVersion` | 현재 대기 수. 누적 요청 Counter가 아님 | Pool 고갈 조기 징후 | 임계값은 부하 테스트 후 결정 | PostgreSQL 연동 시 `핵심 기능 구현 시` |
| `postgres.pool.max` | `finguardops_db_pool_max_connections` | Pool 최대 크기와 사용률 계산 | Spring Boot DB Pool 설정·상태 계층 | Gauge | 연결 수 | 설정 로드·변경 또는 상태 수집 시 | `service`, `deploymentVersion` | Pool별 설정값. Secret과 접속 정보 제외 | active/max, pending과 함께 표시 | 설정 불일치 확인. 수치는 부하 테스트 후 결정 | PostgreSQL 연동 시 `핵심 기능 구현 시` |
| `postgres.connection.acquire_duration` | `finguardops_db_connection_acquire_duration_seconds` | DB 연결 획득 지연 확인 | Spring Boot DB Pool 계층 | Histogram | 초 | 연결 요청부터 획득·Timeout까지 | `service`, `result`, `failureCategory`, `deploymentVersion` | 연결 획득 시도 단위 | Pool 병목과 API 지연 상관관계 | 임계값은 부하 테스트 후 결정 | PostgreSQL 연동 시 `핵심 기능 구현 시` |
| `postgres.connection.timeouts` | `finguardops_db_connection_timeouts_total` | 연결 획득 Timeout 확인 | Spring Boot DB Pool 계층 | Counter | Timeout 건 | 연결 획득이 Timeout으로 종료될 때 | `service`, `failureCategory`, `deploymentVersion` | 실제 획득 Timeout별 한 번 | Pool 고갈·DB 장애 | 기준선 및 부하 테스트 후 결정 | PostgreSQL 연동 시 `핵심 기능 구현 시` |
| `postgres.query.duration` | `finguardops_db_query_duration_seconds` | DB 작업 지연 분포 확인 | Spring Boot 데이터 접근 경계 | Histogram | 초 | 승인된 DB 작업 시작부터 성공·실패 종료까지 | `service`, `result`, `deploymentVersion` | SQL 원문·파라미터를 라벨로 사용하지 않음. 세부 원인은 trace·로그에서 확인 | DB 지연과 API 지연 비교 | 느린 쿼리 기준은 부하 테스트 후 결정 | PostgreSQL 연동 시 `핵심 기능 구현 시` |
| `postgres.errors` | `finguardops_db_errors_total` | DB 연결·쿼리·트랜잭션 오류 확인 | Spring Boot 데이터 접근 경계 | Counter | 오류 건 | DB 작업 오류가 안전한 범주로 확정될 때 | `service`, `result`, `failureCategory`, `deploymentVersion` | 오류 원문·SQLState 전체를 라벨로 쓰지 않고 제한 분류 사용 | 오류 유형과 업무 영향 확인 | 기준선 측정 후 결정 | PostgreSQL 연동 시 `핵심 기능 구현 시` |

DB 쿼리 원문, 테이블 키, 고객·거래·사건 식별자를 메트릭에 기록하지 않는다. 느린 쿼리의 구체적인 SQL과 실행 문맥은 접근 통제된 로그 또는 trace에서 확인한다.

## 11. 배포 버전 메트릭

| 논리 이름 | 구현 후보 | 목적 | 수집 주체 | 유형 | 단위 | 증가·관측 시점 | 허용 라벨 | 집계 기준 | 대시보드 활용 | 알림 활용 | 적용 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `deployment.version.info` | `finguardops_deployment_info` | 실행 중인 서비스와 배포 버전 식별 | 각 애플리케이션 | Gauge | 0 또는 1 | 프로세스 시작·종료 또는 배포 상태 수집 시 | `service`, `deploymentVersion` | 실행 중 버전은 1. 인스턴스 수를 표현하는 지표로 사용하지 않음 | 현재 버전, 버전 공존 확인 | 승인되지 않은 버전 공존은 배포 정책 확정 후 결정 | Spring Health는 `현재 최소 범위`, 나머지는 해당 기능 구현 시 |
| `deployment.error_ratio` | HTTP·업무 Counter에서 파생 | 버전별 오류 회귀 확인 | 운영 집계 계층 | Gauge | 비율 0~1 | 선택 시간 구간 조회 시 계산 | `service`, `route`, `deploymentVersion` | 동일 route·부하 조건에서 오류 응답/전체 응답 | 배포 전후 오류율 비교 | 기준선 측정 후 결정 | 해당 서비스 계측 시 |
| `deployment.latency` | HTTP·분석 Histogram에서 파생 | 버전별 지연 회귀 확인 | 운영 집계 계층 | Gauge | 초 | 선택 시간 구간의 백분위 계산 | `service`, `route`, `deploymentVersion` | 동일 route·시간대·처리량 조건 비교 | 배포 전후 p95·p99 후보 | 임계값은 부하 테스트 후 결정 | 해당 서비스 계측 시 |

배포 버전별 비교는 `deploymentVersion`만 보고 단정하지 않고 같은 route, 트래픽, 모델·Rule 버전과 시간 구간을 함께 확인한다.

## 12. 향후 Redis·Kafka·Kubernetes·AWS 메트릭

이 절은 도입 후 사용할 계약 후보이다. 현재 구성되거나 수집 중인 메트릭이 아니다.

### 12.1 Redis

정확 일치 캐시의 업무 적중·미적중은 Spring Boot의 AI 요청 처리 결과에서 계수한다. Redis 인프라 메트릭은 저장 기술의 가용성과 성능을 확인하며 업무 캐시 적중률을 대신하지 않는다.

| 논리 이름 | 구현 후보 | 목적 | 수집 주체 | 유형 | 단위 | 증가·관측 시점 | 허용 라벨 | 집계 기준 | 대시보드 활용 | 알림 활용 | 적용 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `redis.connection.ready` | `finguardops_redis_connection_ready` | Redis 연결 가능 상태 확인 | 애플리케이션 Redis client 또는 인프라 수집 계층 | Gauge | 0 또는 1 | 연결 상태 확인 시 | `service`, `deploymentVersion` | client 관점 준비 상태 | 캐시 사용 가능성 | 지속 실패 기준은 기준선 측정 후 결정 | `향후 도입 시` |
| `redis.operations` | `finguardops_redis_operations_total` | 읽기·쓰기 성공·실패 확인 | 애플리케이션 Redis client | Counter | 작업 건 | Redis 작업 종료 시 | `service`, `result`, `failureCategory`, `deploymentVersion` | 실제 Redis 작업 단위. key 원문 금지 | 오류율과 원본 호출 증가 상관관계 | 기준선 측정 후 결정 | `향후 도입 시` |
| `redis.operation_duration` | `finguardops_redis_operation_duration_seconds` | Redis 지연 확인 | 애플리케이션 Redis client | Histogram | 초 | Redis 작업 시작부터 종료까지 | `service`, `result`, `deploymentVersion` | key·caseId 없이 작업 결과별 집계 | 캐시 지연과 AI 요청시간 비교 | 임계값은 부하 테스트 후 결정 | `향후 도입 시` |
| `redis.cache_evictions` | `finguardops_redis_cache_evictions_total` | 메모리 압력에 따른 퇴출 확인 | Redis 인프라 수집 계층 | Counter | key 퇴출 건 | Redis가 key를 퇴출할 때 | 제한된 인프라 라벨, `service` 후보 | 전체 퇴출 추세. key 원문 금지 | 캐시 적중률 저하 원인 | 기준선 및 용량 테스트 후 결정 | `향후 도입 시` |

### 12.2 Kafka

| 논리 이름 | 구현 후보 | 목적 | 수집 주체 | 유형 | 단위 | 증가·관측 시점 | 허용 라벨 | 집계 기준 | 대시보드 활용 | 알림 활용 | 적용 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `kafka.consumer.lag` | `finguardops_kafka_consumer_lag_records` | 소비 적체 확인 | Kafka 운영 수집 계층 | Gauge | record 수 | Consumer offset 상태 수집 시 | `service`, 승인 후 `topic`, `consumerGroup` | Dashboard는 group·topic 합계와 최대값을 구분. partition 라벨은 기본 대시보드에서 제거 권장 | 비동기 적체와 복구 속도 | 임계값은 부하 테스트 후 결정 | `향후 도입 시` |
| `kafka.records.processed` | `finguardops_kafka_records_processed_total` | Consumer 처리량과 결과 확인 | Consumer 처리 경계 | Counter | record 건 | 메시지 업무 처리 성공·실패가 확정될 때 | `service`, `result`, `failureCategory`, 승인 후 `topic`, `consumerGroup`, `deploymentVersion` | 같은 `eventId` 재전달의 멱등 성공과 새 업무 결과를 구분 | 처리량·실패율 | 기준선 측정 후 결정 | `향후 도입 시` |
| `kafka.reprocess.attempts` | `finguardops_kafka_reprocess_attempts_total` | 재처리 시도와 결과 확인 | Consumer 재처리 경계 | Counter | 재처리 건 | 승인된 재처리가 수행될 때 | `service`, `result`, `failureCategory`, 승인 후 `topic`, `consumerGroup` | 재처리 시도별. eventId는 로그에서 확인 | 반복 실패와 복구 확인 | 기준선 측정 후 결정 | `향후 도입 시` |
| `kafka.dlq.records` | `finguardops_kafka_dlq_records_total` | DLQ 이동과 재투입 결과 확인 | Kafka 운영·Consumer 계층 | Counter | record 건 | DLQ 이동 또는 승인된 재투입 결과가 확정될 때 | `service`, `result`, `failureCategory`, 승인 후 `topic`, `consumerGroup` | DLQ 구현 후 실제 record 기준. payload 원문 금지 | DLQ 유입·복구 | 기준선 측정 후 결정 | `향후 도입 시` |

Kafka를 도입하더라도 Consumer 메트릭 증가가 사건·리포트·attempt·비용의 중복 업무 생성을 의미해서는 안 된다. 업무 중복 제거는 도메인 고유 제약과 처리 상태로 별도 보장한다.

### 12.3 Kubernetes

| 논리 이름 | 구현 후보 | 목적 | 수집 주체 | 유형 | 단위 | 증가·관측 시점 | 허용 라벨 | 집계 기준 | 대시보드 활용 | 알림 활용 | 적용 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `kubernetes.workload.restarts` | `finguardops_kubernetes_workload_restarts_total` | 서비스별 재시작 증가 확인 | Kubernetes 운영 수집 계층 | Counter | 재시작 건 | container 재시작 count 증가 시 | `service`, `deploymentVersion`, `failureCategory` 후보 | Pod 이름을 장기 집계 라벨에서 제거하고 workload·service 단위로 집계 | 재시작 추세와 배포 상관관계 | 기준선 측정 후 결정 | `향후 도입 시` |
| `kubernetes.workload.cpu` | `finguardops_kubernetes_workload_cpu_cores` | 서비스 CPU 사용량 확인 | Kubernetes 운영 수집 계층 | Gauge | CPU core | 자원 상태 수집 시 | `service`, `deploymentVersion` | workload 합계·요청 대비 비율을 구분 | CPU 포화와 Rule·ML 지연 비교 | 임계값은 부하 테스트 후 결정 | `향후 도입 시` |
| `kubernetes.workload.memory` | `finguardops_kubernetes_workload_memory_bytes` | 서비스 메모리 사용량 확인 | Kubernetes 운영 수집 계층 | Gauge | byte | 자원 상태 수집 시 | `service`, `deploymentVersion` | workload 합계·limit 대비 비율을 구분 | 메모리 압력·재시작 원인 | 임계값은 부하 테스트 후 결정 | `향후 도입 시` |
| `kubernetes.workload.ready_replicas` | `finguardops_kubernetes_workload_ready_replicas` | 서비스 가용 replica 확인 | Kubernetes 운영 수집 계층 | Gauge | replica 수 | Deployment 상태 수집 시 | `service`, `deploymentVersion` | desired와 ready를 별도 값으로 수집 | 배포 진행·가용성 | 기준선 및 배포 정책 확정 후 결정 | `향후 도입 시` |

### 12.4 AWS

| 논리 이름 | 구현 후보 | 목적 | 수집 주체 | 유형 | 단위 | 증가·관측 시점 | 허용 라벨 | 집계 기준 | 대시보드 활용 | 알림 활용 | 적용 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `aws.dependency.requests` | `finguardops_aws_dependency_requests_total` | 실제로 채택한 AWS 관리형 의존성의 요청·오류 확인 | AWS 또는 애플리케이션 운영 수집 계층 | Counter | 요청 건 | 승인된 AWS 의존성 요청 종료 시 | `service`, `result`, `failureCategory`, 승인 후 `dependency`, `deploymentVersion` | 실제 도입한 의존성만 허용 목록으로 추가 | AWS 의존성 오류율 | 기준선 측정 후 결정 | `향후 도입 시` |
| `aws.dependency.duration` | `finguardops_aws_dependency_duration_seconds` | AWS 의존성 지연 확인 | AWS 또는 애플리케이션 운영 수집 계층 | Histogram | 초 | 의존성 요청 시작부터 종료까지 | `service`, `result`, 승인 후 `dependency`, `deploymentVersion` | AWS 서비스 이름·resource ID의 무제한 라벨 금지 | 외부 지연과 API 영향 | 임계값은 부하 테스트 후 결정 | `향후 도입 시` |
| `aws.dependency.throttles` | `finguardops_aws_dependency_throttles_total` | 할당량·제한에 따른 throttling 확인 | AWS 운영 수집 계층 | Counter | throttle 건 | 승인된 의존성에서 throttling이 발생할 때 | `service`, `failureCategory`, 승인 후 `dependency` | 계정·ARN·resource ID를 라벨로 사용하지 않음 | 용량·할당량 검토 | 기준선 및 비용 예산 확정 후 결정 | `향후 도입 시` |

AWS 상세 서비스, 계정 구조, 비용 메트릭과 태그 체계는 배포 방식이 승인된 뒤 별도 계약으로 확정한다.

## 13. 로그·메트릭·트레이싱 구분

| 수단 | 사용 목적 | 포함 후보 | 금지·주의 |
| --- | --- | --- | --- |
| 메트릭 | 집계, 추세, 비율, 이상 징후와 배포 전후 비교 | 제한된 서비스·route·상태·결과·버전·Provider·모델·실패 분류 | 업무 식별자와 원문을 라벨로 사용하지 않음 |
| 로그 | 구체적인 업무 결과, 상태 전이, 중복 처리와 오류 원인 확인 | `transactionId`, `caseId`, `aiRequestId`, `executionId`, 필요 시 `attemptId`, `eventId`, `traceId`, 안전한 오류 코드 | 개인정보, 인증정보, Prompt·Provider 응답·오류 원문 금지 |
| 트레이싱 | Spring Boot→FastAPI→Provider와 DB 호출의 시간 흐름·병목 연결 | `traceId`, service·span 이름, route template, 목적에 맞는 업무 식별자 Span attribute | 민감 원문, 계좌·고객 정보와 Provider 원문 금지 |

`traceId`는 Spring Boot, FastAPI와 Provider 호출 흐름을 연결한다. `transactionId`, `caseId`, `aiRequestId`와 `executionId`는 해당 흐름의 업무 문맥이 필요할 때 로그 또는 Span attribute에서 사용한다. 모든 Span에 모든 업무 식별자를 무조건 복제하지 않는다.

로그와 Span attribute의 보존·접근 권한·샘플링은 별도 보안·관측 구현 정책에서 확정한다. 개인정보와 인증정보는 세 수단 어디에도 기록하지 않는다.

## 14. 대시보드 후보

### 14.1 FDS 분석 담당자

FDS 분석 담당자 화면은 업무 상태와 조사 지원 기능의 가용성을 보여주며 Provider·토큰·비용 상세를 노출하지 않는다.

| 대시보드 후보 | 주요 지표 | 사용 목적 |
| --- | --- | --- |
| 거래·탐지 처리 현황 | 거래 접수·최종 결과, 탐지 요청·완료·실패, 전체 처리시간, 위험 등급 분포 | 거래 유입과 분석 지연·실패가 조사 대기열에 미치는 영향 확인 |
| 위험 대응·사건 현황 | 위험 대응 결과, 신규 사건, 사건 상태 변경, 충돌·거부 | HIGH·CRITICAL 처리와 사건 대기 흐름 확인 |
| AI 리포트 가용성 | 새 요청, 진행 실행 공유, 캐시 적중, 정상 완료, fallback 완료, 최종 실패, 요청 대기시간 | 리포트가 준비 중인지, 기존 결과인지, fallback인지 구분 |
| 업무 영향 요약 | 서비스 상태 요약, 마지막 정상 확인, 영향받은 거래·탐지·사건·리포트 기능 | 기술 장애를 업무 영향 관점으로 확인 |

FDS 분석 담당자 화면에는 ProviderCallAttempt, 모델별 토큰·추정 비용과 인프라 상세를 표시하지 않는다.

### 14.2 플랫폼·클라우드 운영자

| Grafana 대시보드 후보 | 주요 지표 | 사용 목적 |
| --- | --- | --- |
| 서비스 개요 | Health, API 처리량·오류율·지연, 배포 버전 | 장애 범위와 배포 회귀 확인 |
| Spring Boot 업무 처리 | 거래·탐지·위험 대응·사건, 중복 요청과 멱등성 충돌 | 기술 장애가 업무 정합성에 미친 영향 확인 |
| FastAPI Rule·ML | Rule·모델 버전별 실행량·실패·지연, 전체 분석시간, client Timeout·연결 실패 | Rule·ML 병목과 버전 회귀 확인 |
| PostgreSQL | active·idle·pending·max 연결, 획득 지연·Timeout, 쿼리 지연·오류 | Pool 고갈과 DB 병목 확인 |
| AI 리포트 실행 | 요청·실행·공유·캐시, 정상·fallback·실패, 생성시간 | 요청·실행·결과의 처리 경로 확인 |
| Provider와 FinOps | distinct attempts, Provider 지연·오류, 토큰, 통화별 비용, 거래 1,000건당 호출·비용, 사건당 비용, 모델 비율, 품질 통과율 | Cost·Latency·Quality와 중복 비용 방지 검증 |
| 배포 비교 | `deploymentVersion`별 동일 route 오류율·지연·처리량 | 배포 전후 이상 징후 확인 |
| 향후 인프라 | Redis 상태, Kafka Lag·재처리·DLQ, Kubernetes 재시작·CPU·메모리, AWS 의존성 | 실제 도입된 구성만 단계적으로 표시 |

React 관리자 화면은 업무 영향과 조치 요약에 집중하고 Grafana는 기술 시계열과 원인 분석에 집중한다. 동일한 상세 기술 대시보드를 두 화면에 중복 구현하지 않는다.

## 15. 알림 후보

임계값, 지속 시간, 심각도, 알림 채널과 해제 조건은 기존 문서에 확정되어 있지 않다. 근거 없는 숫자를 사용하지 않는다.

| 알림 후보 | 판단 지표 | 함께 확인할 정보 | 임계값 결정 방식 |
| --- | --- | --- | --- |
| Spring Boot Health 실패 | Health 요청 오류·지연, 배포 버전 | 의존성 상태, 마지막 정상 시각, 영향 route | 기준선 측정 후 결정 |
| API 오류율 증가 | HTTP 요청과 오류 Counter | route, status, 처리량, 배포 버전 | 기준선 측정 후 결정 |
| API 지연 증가 | HTTP duration 백분위 | 처리량, DB·FastAPI·Provider 지연, 배포 버전 | 부하 테스트 후 결정 |
| 거래 접수 대비 완료 감소 | 거래 접수·결과 Counter | 탐지 요청·완료, DB·FastAPI 오류 | 기준선 측정 후 결정 |
| 탐지 실패 증가 | Spring 탐지 결과, FastAPI 분석 결과·client 실패 | Rule·모델 버전, failureCategory | 기준선 측정 후 결정 |
| DB Pool 고갈 위험 | active/max, pending, 획득 지연·Timeout | DB 오류, 장기 요청, 최근 배포 | 부하 테스트 후 결정 |
| 특정 Rule·모델 회귀 | Rule·ML 실패·지연 | rule/model 버전과 배포 버전 | 기준선 및 부하 테스트 후 결정 |
| Provider Timeout·오류 증가 | attempt 결과·지연 | Provider·모델, fallback, 최종 실패 | 기준선 측정 후 결정 |
| fallback 실행 비율 증가 | fallback execution ratio | failureCategory, Provider 상태, 품질 통과율 | 기준선 측정 후 결정 |
| AI 최종 실패 증가 | 최종 실패 실행 수 | fallback 실패, Provider·FastAPI 상태 | 기준선 측정 후 결정 |
| AI 비용 급증 | 통화별 비용, 거래 1,000건당 비용, 사건당 비용 | 거래량·사건량, attempts, 재시도, 캐시, 모델 비율 | 비용 예산 확정 후 결정 |
| 캐시 적중률 급락 | cache hit ratio | 정확 일치 조건 변경, Redis 도입 후 상태, 신규 실행 수 | 기준선 측정 후 결정 |
| 배포 후 회귀 | 버전별 오류율·지연·처리량 | 변경 시각, 서비스·Rule·모델 버전 | 기준선 및 부하 테스트 후 결정 |
| Kafka Consumer 적체 | Lag, 처리량, 재처리·DLQ | topic·consumer group, 마지막 성공 이벤트 | Kafka 도입 후 부하 테스트로 결정 |
| Kubernetes 재시작·자원 압력 | 재시작, CPU, 메모리, ready replica | 배포 버전, 애플리케이션 오류·지연 | Kubernetes 도입 후 기준선·부하 테스트로 결정 |
| AWS 의존성 오류·throttling | 요청 오류·지연·throttle | 실제 dependency, 할당량, 비용 | AWS 도입 후 기준선·비용 예산으로 결정 |

알림은 위험 점수, 위험 등급, 거래 대응, 사건 상태와 최종 판정을 자동 변경하지 않는다. 비용 알림도 금융 위험 신호로 해석하지 않는다.

## 16. 정합성 정비 후 남은 문서 차이

구현 전 정합성 정비에서 AI 조회 순서, 요청·실행 식별자, 자동 재시도, 무실행 캐시와 사건 담당자·종료 조건을 API·ERD·상태 전이·이벤트·아키텍처 문서와 통일했다. 다음은 아직 사용자 결정이 필요한 문서 차이이다.

| 항목 | 문서별 표현 | 메트릭 명세의 처리 |
| --- | --- | --- |
| 도메인 `eventId` 명명 | API 공통 규칙은 행동 이벤트 식별자로, 시스템·운영 문서는 향후 Kafka 이벤트 식별자로 사용 | 어떤 `eventId`도 메트릭 라벨에 사용하지 않는다. 로그·이벤트 물리 명명은 후속 결정 |
| External Risk 실패 관측 | 성공은 match 기반 `MATCHED` 또는 `UNMATCHED`. timeout·unavailable·invalid response는 분석을 중단하고 typed failure로 전파 | 현재 구현된 신규 meter는 없다. 후속 계측은 성공 결과와 failure category를 구분하고 실제 확정된 거래 결과만 계수한다. cache hit·stale data·fallback meter는 현재 계약이 아님 |
| FastAPI Timeout 거래 처리 | 상태 전이·운영 요구사항은 정책 `TBD`, 거래 API는 초기 권장으로 Transaction `FAILED`와 `503` | 실제 Spring Boot가 확정한 결과만 거래 결과로 계수. Timeout 자체는 client 실패 Counter로 별도 계수 |

Validation 거절은 Transaction과 IdempotencyRecord를 생성하지 않는다. 거래 접수·상태 결과 Counter에 포함하지 않고 HTTP 오류, Validation 오류 코드, `traceId`, 로그와 승인된 저카디널리티 오류 메트릭으로 관측한다.

External Risk의 현재 운영 경계에는 자동 retry, cache, stale data, fallback과 Circuit
Breaker가 없다. 따라서 이를 현재 구현 metric이나 성공 결과로 계수하지 않는다.
향후 도입하려면 별도 Issue·ADR에서 category, meter 이름, 라벨과 집계 시점을 먼저
승인해야 한다. Issue #150은 신규 metric 코드를 추가하지 않는다.

## 17. 사용자 결정 필요 사항

이미 확정된 요청·실행 분리, 정확 일치 네 요소, 진행 중 실행 공유, 캐시 무실행, `FAILED` 이후 새 키 요청, ProviderCallAttempt 비용 원본과 Kafka 단계적 도입은 다시 결정 사항으로 올리지 않는다.

| 결정 항목 | 선택 가능한 안 | 권장안 | 권장 이유 | 구현·데이터 모델 영향 | 차단 여부 |
| --- | --- | --- | --- | --- | --- |
| 물리 메트릭 이름 체계 | A. 프레임워크 기본 이름 / B. `finguardops_` 전용 이름 / C. 기본 이름+업무 메트릭만 전용 | C | 표준 HTTP·runtime 계측을 재사용하면서 업무 메트릭 의미를 명확히 하고 중복 계측을 줄임 | Meter 등록 이름, recording rule, dashboard query에 영향 | 현재 문서 비차단, 계측 구현 전 결정 |
| HTTP route 라벨명 | A. `endpoint` / B. `route` / C. 둘 다 | B | route template 의미가 명확하고 중복 차원을 피함 | Spring·FastAPI 공통 라벨 변환과 dashboard query에 영향 | 계측 구현 전 결정 |
| 비용 통화 라벨명 | A. `currency` / B. `costCurrency` | B | AI API와 ProviderCallAttempt의 필드 의미를 그대로 유지하기 쉬움 | 비용 meter와 recording rule 이름에 영향. DB 모델 변경은 없음 | 계측 구현 전 결정 |
| Histogram bucket과 목표 백분위 | A. 프레임워크 기본 / B. 서비스 공통 / C. API·Rule·ML·DB·Provider별 부하 테스트 기반 | C | 단계별 지연 분포가 크게 다르므로 실제 측정 근거를 반영할 수 있음 | 시계열 수, 메모리·보존 비용과 dashboard 정밀도에 영향 | Histogram 구현 전 결정 |
| scrape·집계 주기 | A. 전역 동일 / B. 애플리케이션·인프라별 차등 / C. managed backend 기본값 | B | 비용·변화 속도와 장애 탐지 요구를 대상별로 조정 가능 | Prometheus·collector 설정과 저장 비용에 영향 | 후속 구현 결정 |
| 메트릭 보존 기간 | A. 전역 동일 / B. 상세 단기+집계 장기 / C. 업무·인프라별 차등 | B | 배포 회귀 분석과 장기 FinOps 추세를 유지하면서 고해상도 저장 비용을 제한 | 시계열 backend 용량과 장기 집계 설계에 영향 | 후속 결정 |
| FinOps 시간 귀속 | A. 요청 접수 시각 / B. execution 생성 시각 / C. ProviderCallAttempt 시작 시각 | C | 비용 원본 발생 시점과 일치하고 긴 실행·재시도의 실제 비용 시점을 보존 | 집계 query와 기간 경계에 영향. 업무 Entity 변경은 없음 | FinOps dashboard 구현 전 결정 |
| 사건당 비용의 분모 | A. 기간 내 전체 사건 / B. 기간 내 AI 요청 사건 / C. 기간 내 Provider attempt가 있는 distinct 사건 | C | 실제 비용 발생 사건을 분모로 사용해 캐시·미요청 사건이 평균을 왜곡하지 않음 | 집계 query와 화면 설명에 영향 | FinOps dashboard 구현 전 결정 |
| AI 품질 통과 기준 | A. 구조 검증만 / B. 구조+필수 내용 규칙 / C. B+담당자 평가 표본 | C | 자동 검증과 실제 조사 유용성을 분리해 Cost·Latency·Quality를 비교 가능 | 품질 평가 데이터, 권한, 보존과 metric 분모에 영향 | 품질 지표·알림 구현 전 결정 |
| failureCategory 공통 Enum | A. 서비스별 자유 값 / B. 전역 제한 Enum / C. 공통 상위 Enum+서비스별 내부 코드 | C | dashboard 집계 안정성과 상세 진단을 함께 확보 | 로그 매핑, API failureCode와 meter label에 영향 | 계측 구현 전 결정 |
| deploymentVersion 원본 | A. 수동 환경 변수 / B. 빌드 산출물 버전 / C. CI/CD 배포 record | 초기 B, CI/CD 도입 후 C | 현재 단계에서 재현 가능하고 향후 배포 감사와 연결 가능 | 빌드 정보 노출, 배포 pipeline과 dashboard에 영향 | 배포 비교 구현 전 결정 |
| Kafka 운영 라벨 | A. `topic`만 / B. `topic+consumerGroup` / C. partition까지 상시 노출 | B | 책임 흐름과 Consumer 적체를 구분하면서 partition 곱집합을 제한 | Kafka exporter·recording rule과 시계열 수에 영향 | Kafka 도입 전 결정 |
| Kubernetes 인스턴스 라벨 보존 | A. pod/container 원본 장기 보존 / B. 단기 원본+service/workload 집계 장기 / C. 집계만 | B | 장애 순간 진단과 장기 카디널리티 통제를 함께 만족 | 수집·recording rule·보존 비용에 영향 | Kubernetes 도입 전 결정 |
| AWS dependency 라벨 체계 | A. AWS 서비스명 / B. 승인된 논리 dependency / C. ARN·resource ID | B | 계정·리소스 식별자 노출과 고카디널리티를 피하고 업무 의존성을 표현 | AWS 수집기, dashboard와 접근 통제에 영향 | AWS 도입 전 결정 |
| 알림 심각도·채널·해제 조건 | A. 단일 심각도 / B. 업무 영향 기반 단계 / C. 기술 지표 기반 단계 | B | 거래·탐지·사건과 AI 부가 기능의 장애 전파 범위를 구분 가능 | alert rule, 담당자 routing, 운영 이력에 영향 | 실제 알림 적용 전 결정 |

## 18. 검증 체크리스트

- [ ] 모든 원본 메트릭에 이름 후보, 목적, 수집 주체, 유형, 단위와 관측 시점이 있는가
- [ ] 모든 메트릭에 허용 라벨, 집계 기준, 대시보드·알림 활용과 적용 단계가 있는가
- [ ] Provider 호출 시작과 결과가 서로 다른 Counter이며 시작 메트릭에 `result`와 `failureCategory`가 없는가
- [ ] FastAPI Provider 기술 Counter와 Spring Boot의 ProviderCallAttempt 영속 처리를 같은 메트릭으로 중복 증가시키지 않는가
- [ ] AiReportRequest, AiReportExecution과 ProviderCallAttempt의 집계 단위를 구분했는가
- [ ] 실행 공유 요청별로 Provider attempt, 토큰과 비용을 복제하지 않는가
- [ ] 캐시 요청에 새 ProviderCallAttempt, 가상 토큰과 가상 비용을 만들지 않는가
- [ ] fallback을 새 요청·실행·Provider 호출로 계수하지 않는가
- [ ] 실패한 실제 Provider 호출의 확인 가능한 토큰·비용을 누락하지 않는가
- [ ] 서로 다른 통화를 환율 없이 하나의 숫자로 합산하지 않는가
- [ ] 거래 1,000건당 지표와 사건당 비용의 분자·분모·시간 구간이 명시되었는가
- [ ] `transactionId`, `eventId`, `caseId`, `aiRequestId`, `executionId`, `traceId`와 개인정보가 메트릭 라벨에서 제외되었는가
- [ ] nullable·비적용 라벨값에 `unknown`, `not_applicable`, `none`을 사용하고 빈 문자열과 null을 사용하지 않는가
- [ ] AI 리포트 generation duration이 `AiReportExecution.startedAt`부터 최초 종료 상태까지이며 시작 전 대기시간을 제외하는가
- [ ] 사건 상태 변경 거부가 실제 상태 변경이 아닌 status change outcome으로 표현되는가
- [ ] 거래 접수 HTTP 시도와 새 Transaction 생성 수를 분리했는가
- [ ] FinOps 파생 Gauge를 애플리케이션 원본 Gauge로 중복 발행하지 않고 시간 구간과 분모 0 처리를 명시했는가
- [ ] route는 실제 path가 아닌 route template을 사용하는가
- [ ] 오류 메시지, Prompt와 Provider 응답 원문이 라벨·로그·trace에 포함되지 않는가
- [ ] 현재 최소 범위, 핵심 기능 구현 시와 향후 도입 시를 구분했는가
- [ ] Redis, Kafka, Kubernetes와 AWS를 현재 구현·수집 중인 것으로 표현하지 않았는가
- [ ] 알림 임계값을 기준선·부하 테스트·비용 예산 근거 없이 확정하지 않았는가
- [ ] 이미 통일한 계약을 다시 충돌로 기록하지 않고, 남은 문서 차이와 사용자 결정 사항만 미확정으로 유지하는가

## 19. 제외 범위

- Java와 Python 코드 구현
- Prometheus 설치·scrape·recording rule 설정
- Grafana 대시보드 구현
- OpenTelemetry 적용과 sampling 설정
- 실제 알림 발송과 담당자 routing
- Redis와 Kafka 구현
- Docker, Kubernetes와 AWS 설정
- PostgreSQL DDL과 Entity 변경
- API 요청·응답·상태 코드 변경
- 기존 요구사항·아키텍처·API·상태 전이 문서 수정
- 신규 최상위 `docs/` 디렉터리 생성
- 실제 SLA·SLO 수치와 비용 예산 확정
- 환율 변환과 확정 청구액 정산
- 측정하지 않은 성능 향상·비용 절감률 주장
