# FinGuardOps

> Cloud Native 금융 AI 이상거래 탐지·사건 처리·운영 플랫폼

FinGuardOps는 금융거래와 사용자 행동을 기반으로 이상거래를 탐지하고, 위험 대응과 사건 처리를 지원하며, AI 서비스의 장애·성능·비용을 통합 관리하는 Cloud Native 금융 AI FraudOps 플랫폼입니다.

단순히 이상거래 위험 점수를 계산하는 데서 끝나지 않고 다음 흐름을 하나의 시스템으로 구현하는 것을 목표로 합니다.

```text
금융거래·행동 이벤트
→ Rule·ML 기반 이상거래 탐지
→ 위험 등급별 대응
→ 사건 생성과 담당자 검토
→ 생성형 AI 사건 리포트
→ 장애·성능·비용·배포 상태 운영
```

---

## 프로젝트 배경

금융 이상거래 탐지 시스템에서는 이상 여부를 판단하는 것뿐만 아니라 다음과 같은 운영 문제가 함께 해결되어야 합니다.

* 탐지 결과를 담당자가 어떻게 검토하고 설명할 것인가
* 위험 거래와 사건 상태를 어떻게 일관성 있게 관리할 것인가
* 중복 요청·이벤트·사건 생성을 어떻게 방지할 것인가
* AI 및 외부 서비스 장애가 핵심 거래 처리에 영향을 주지 않도록 어떻게 격리할 것인가
* LLM 호출량과 토큰 비용을 어떻게 측정하고 통제할 것인가
* 거래 접수부터 탐지·사건·AI 리포트까지의 처리 흐름을 어떻게 추적할 것인가
* 새로운 버전을 어떻게 안전하게 배포하고 운영 상태를 확인할 것인가

FinGuardOps는 이러한 문제를 금융 FDS, AI 운영, Cloud Native 운영의 세 영역으로 나누어 해결합니다.

---

## 주요 사용자

### FDS 분석 담당자

위험 거래와 사건을 조회하고 다음 정보를 검토합니다.

* Rule·ML 탐지 근거
* 위험 점수와 위험 등급
* 고객 행동 타임라인
* 연관 거래와 외부 위험정보
* 생성형 AI 사건 리포트
* 조사 메모와 감사 이력

검토 결과에 따라 사건 상태를 변경하고 정상, 오탐, 이상거래 여부를 최종 판정합니다.

### 플랫폼·클라우드 운영자

FinGuardOps를 구성하는 서비스와 인프라의 운영 상태를 확인합니다.

* Spring Boot·FastAPI 서비스 상태
* PostgreSQL·Redis·Kafka 상태
* API 응답시간·오류율·처리량
* Rule 실행시간·ML 추론시간
* DB Connection Pool
* Kafka Consumer Lag
* LLM 호출량·입력 토큰·출력 토큰·비용
* 모델 라우팅·캐시·fallback 비율
* 장애와 배포 이력

---

## 핵심 영역

### 1. 금융 FDS

* 계좌이체·오픈뱅킹 이체·ATM 인출 이벤트 처리
* 로그인·신규 기기·비밀번호 변경 등 사용자 행동 이벤트 처리
* Rule 기반 이상거래 탐지
* ML 기반 복합 패턴 보완
* 위험 점수와 설명 가능한 탐지 근거 제공
* 위험 등급별 승인·모니터링·추가 인증·보류 처리
* 사건 생성·검토·최종 판정
* 상태 변경과 조사 이력 감사 로그 기록

### 2. AI 운영

* HIGH·CRITICAL 사건 중심 AI 리포트 생성
* 탐지 근거와 사용자 행동 타임라인 요약
* 사건 복잡도에 따른 모델 라우팅
* LLM 입력 데이터 축약
* 입력·출력 토큰과 재생성 횟수 제한
* 동일 사건의 동일 분석 결과에 대한 정확 일치 캐시
* LLM 장애 시 Rule·ML 기반 템플릿 fallback
* 모델별 호출량·토큰·지연시간·비용 기록

생성형 AI는 위험 점수 계산, 최종 이상거래 판정, 거래 차단, 고객 제재, 사건 상태 자동 확정을 수행하지 않습니다.

### 3. Cloud Native 운영

* Docker 기반 실행 환경
* GitHub Actions 기반 CI/CD
* Kubernetes 기반 배포 환경
* AWS 기반 클라우드 인프라
* 로그·메트릭·트레이싱 기반 Observability
* API 지연시간·오류율·처리량 관측
* Kafka Consumer Lag과 DB Connection Pool 관측
* AI 호출량·토큰·비용·fallback 관측
* 장애 주입과 복구 검증
* AI 비용 및 FinOps 실험

Kafka, Kubernetes, AWS와 Observability는 핵심 금융 FDS 기능을 안정화한 뒤 단계적으로 도입합니다.

---

## 핵심 이상거래 시나리오

FinGuardOps는 다음 시나리오를 중심으로 설계합니다.

1. 신규 기기에서 발생한 고액 이체
2. 비밀번호·이체 한도 변경 직후 고액 송금
3. 외부 위험계좌로의 송금
4. 짧은 시간 동안 반복되는 분산 송금
5. 여러 고객의 자금이 특정 계좌로 집중되는 거래
6. 고액 입금 직후 ATM 인출
7. 대출 실행 직후 발생하는 자금 이동
8. 오픈뱅킹 자금 집중 후 재송금

실제 금융거래, 고객 인증, 거래 차단과 제재는 Mock으로 구현합니다.

---

## 위험 대응 원칙

```text
LOW
→ 승인

MEDIUM
→ 승인 후 모니터링

HIGH
→ 추가 인증 요청 + 사건 생성

CRITICAL
→ 거래 보류 + 긴급 사건 생성 + 알림
```

Rule v1의 R001~R004, 가중치와 위험 등급 경계는 초기 실험값으로 문서화했으며 운영 정책으로 사용하기 전에 테스트 데이터와 담당자 검토로 재검증해야 합니다. 단일 기준은 [`docs/01-requirements/rule-v1-detection-contract.md`](docs/01-requirements/rule-v1-detection-contract.md)입니다.

---

## 사건 관리

사건의 업무 진행 상태와 최종 조사 결과를 별도로 관리합니다.

### 사건 상태

```text
OPEN
IN_REVIEW
ADDITIONAL_INFORMATION_REQUIRED
CLOSED
```

### 최종 판정

```text
NORMAL
FALSE_POSITIVE
CONFIRMED_FRAUD
```

`caseStatus`는 사건의 현재 업무 진행 단계를 나타내고, `finalDisposition`은 조사 결과를 나타냅니다.

조사가 완료되지 않은 경우 최종 판정은 `null`로 유지합니다.

모든 주요 변경에는 다음 정보를 감사 로그로 기록합니다.

* 변경 사용자
* 변경 시각
* 이전 상태
* 변경 후 상태
* 변경 사유
* 관련 거래
* 관련 사건

---

## 아키텍처 방향

처음부터 전체 시스템을 마이크로서비스로 분리하지 않습니다.

아래 구성은 목표 아키텍처를 포함합니다. 현재 Spring Boot에는 거래·행동 이벤트
접수와 PostgreSQL 연동, 탐지 결과·RuleVersion 영속 모델, External Risk 조회,
Rule 분석, 위험 대응·사건·AuditLog 최종화를 연결하는 public 최종 동기 거래 접수
흐름이 구현되었습니다.

FastAPI에는 기존 `POST /api/v1/rule-analysis`와 함께 External Risk 결과를 필수
입력으로 검증하는 `POST /api/v2/rule-analysis`가 구현되었습니다. 두 endpoint는
같은 Rule v1 실행 경계를 사용하며 External Risk는 아직 점수·등급·Evidence에
반영하지 않습니다. Backend에는 v1 계약을 유지하는 Java v2 exact wire DTO,
`ExternalRiskSnapshot` mapper, `/api/v2/rule-analysis` HTTP Client, 실제 External Risk
HTTP Provider와 production Provider·Policy·coordinator Bean이 구현되었습니다.

Public 거래 접수는 Idempotency claim의 단일 승자가 거래 `RECEIVED` 저장·연결을
commit한 뒤 활성 DB transaction 없이 External Risk와 Rule 분석 v2를 호출합니다.
분석 성공 후 위험 대응·필요한 사건·AuditLog를 최종화하고, 별도 completion
transaction에서 성공 Snapshot v2와 Idempotency `COMPLETED`를 확정해 HTTP `201`을
반환합니다. External Risk typed failure는 공개 안전 오류로 매핑해 Failure Snapshot으로
저장·재생하며, legacy·Snapshot v1·Snapshot v2 성공 replay와 terminal 실패 replay는
Provider·Rule·최종화를 다시 호출하지 않습니다.

장기 `IN_PROGRESS` bounded 후보 조회, typed 상태 판정, 확정된 Snapshot v2 완료
간극의 단건 복구, 별도 append-only 운영 복구 감사와 제한된 non-web one-shot
Runner·CLI가 구현되었습니다. 실행 절차는
[`Idempotency 복구 one-shot runbook`](docs/09-deployment/idempotency-recovery-one-shot-runbook.md)을
따릅니다. scheduler·batch, 자동 retry·fallback·cache, 운영 credential 실제 배포,
USER 인증·인가, Issue #186 외 사건·AI·복구 상태 등의 추가 업무 metric과
Prometheus 서버·scrape·recording rule·alert·dashboard는 아직 구현되지
않았습니다. Spring Boot runtime Prometheus registry와 opt-in Actuator endpoint의
경계는 구현되었습니다. 기존 Rule 분석
v1은 당장 제거하지 않으며 Rule v1 기본
RuleVersion 집합의 제한된 local/dev/test one-shot 발행 경계만 제공하고 공개 관리
API나 정상 시작 자동 발행은 제공하지 않습니다. `ANALYZED`는 위험 대응 전 중간
상태이므로 최종 성공으로 반환하거나 성공 Snapshot으로 확정하지 않습니다.

```text
React·TypeScript
        │
        ▼
Spring Boot Modular Monolith
        │
        ├── PostgreSQL
        ├── Redis
        ├── External Risk Mock
        │
        ▼
FastAPI AI Service
        │
        ├── Rule Engine
        ├── ML Inference
        └── AI Report·Fallback

Kafka
→ 핵심 기능 안정화 후 비동기 사건·리포트·통계 처리에 도입
```

### Spring Boot

* 거래 접수와 검증
* 멱등성 처리
* 거래 상태 관리
* 분석 요청 오케스트레이션
* 위험 대응
* 사건 관리
* 감사 로그
* AI 사용량과 비용 기록

### FastAPI AI Service

* Feature 계산
* Rule 실행
* ML 추론
* 모델 라우팅
* AI 사건 리포트
* 템플릿 fallback

### Data

* PostgreSQL: 현재 거래·멱등·행동 이벤트·탐지 결과·RuleVersion, 사건·첫 거래
  연결과 append-only AuditLog 애플리케이션 연동 구현. AuditLog 조회·보존·파기와
  AI 사용량·비용은 목표 범위
* Redis: 정확 일치 리포트 캐시와 집계 데이터 후보. External Risk cache는 별도
  Issue와 계약 승인 전에는 현재 기능으로 간주하지 않음
* Kafka: 사건·리포트·통계 비동기 처리

---

## 기술 스택

### Frontend

* React
* TypeScript

### Backend

* Java 17
* Spring Boot
* Spring Data JPA
* Gradle
* JUnit
* OpenAPI

### AI Service

* Python 3.12
* FastAPI·Pydantic v2
* uv·pytest·Ruff
* Rule v1 Engine 구현, Machine Learning과 Generative AI API는 후속 구현

### Data

* PostgreSQL
* Redis
* Kafka

### DevOps·Cloud

* Docker
* Docker Compose
* Kubernetes
* GitHub Actions
* AWS

### Observability

* Prometheus
* Grafana
* Loki
* OpenTelemetry

기술은 로드맵에 따라 단계적으로 도입하며, 아직 적용하지 않은 기술을 구현 완료 기술로 설명하지 않습니다.

---

## 현재 구현 상태

### Completed

* 저장소와 기본 디렉터리 구조 구성
* AI 작업 규칙 작성
* Java 17 설정
* Gradle Wrapper 구성
* Spring Boot 초기 설정
* Health Check API 구현
* Health Controller 통합 테스트
* Health Service 단위 테스트
* Health API 문서 작성
* FDS 서비스 범위 정의
* 8개 핵심 이상거래 시나리오 정의
* Rule·ML·생성형 AI 역할 구분
* 사건 관리·장애 대응·FinOps 방향 정의
* FinGuardOps 제품 포지셔닝·README·ADR 정비
* 플랫폼 운영 요구사항 정의
* FDS 분석·플랫폼 운영 화면 와이어프레임 작성
* 거래·사건·AI 리포트 상태 전이 정의
* 시스템 아키텍처 명세 작성
* 핵심 도메인 ERD 작성
* API 공통 규칙 정의
* 거래·행동·탐지 API 계약 작성
* 사건·조사 메모·감사 API 계약 작성
* AI 리포트·AI 사용량·비용 API 계약 작성
* 도메인 이벤트 계약 작성
* 관측성·운영 메트릭 명세 작성
* Spring Boot runtime Prometheus registry와 `prometheus` profile 전용
  `/actuator/prometheus` endpoint 구현
* 거래 접수·목록·상세 조회와 거래 멱등성 구현
* 9개 유형 행동 이벤트 접수와 `eventId` 자연 멱등성 구현
* 금융거래·멱등성·행동 이벤트 PostgreSQL 애플리케이션 연동과 Flyway 스키마 구현
* Rule v1 탐지 계약과 평가 정책 문서화
* FastAPI AI Service 초기 실행·설정·Health API와 테스트 기반 구성
* FastAPI Rule v1 실행 계획·R001~R004·scoring·Evidence 계산과 `POST /api/v1/rule-analysis` 구현
* DetectionResult·DetectionEvidence와 FraudRule·RuleVersion Entity, Repository, Flyway V3~V5 구현
* 행동 이벤트 Rule 평가 조회와 DetectionResult 상태 전이·Evidence 저장 persistence primitive 구현
* Spring Boot `RuleAnalysisHttpClient`, Timeout·Trace 전달, 성공·오류 응답 검증과 오류 분류 구현
* Rule v1 Client 자동 retry 0회 구현·검증
* Spring Boot Rule v1 분석 오케스트레이션·결과 채택 계약 문서화
* 거래 분석 시작과 DetectionResult 시작, 성공 Evidence·결과 채택·`ANALYZED`,
  실패 결과·거래 `FAILED`를 각각 원자적으로 저장하는 persistence boundary 구현
* 거래 우선 잠금으로 동일 거래 동시 분석 시작과 terminal 결과의 늦은 완료 방지 검증
* 거래·최신 행동 이벤트 1,000건·전체 실행 가능 RuleVersion immutable Snapshot 조합
* `REPEATABLE_READ` 분석 시작 경계와 canonical `ruleSetVersion` 선계산·응답 exact 검증
* 비트랜잭션 `RuleAnalysisOrchestrationService`의 분석 시작 commit, FastAPI 정확히
  1회 호출, 응답 변환, 결과 완료·채택과 실패 기록 연결
* Rule v1 고정 metadata·Reason Code 표시 Registry와 `RuleEvidenceDraft` 변환 구현
* V5 R001~R004 기본 RuleVersion의 원자적 발행 Service와 local/dev/test 전용
  비활성 one-shot Runner 구현
* 독립 External Risk Port·응답 검증 정책 Service와 local/dev/test 전용 결정적
  Mock, 실제 HTTP Provider, production Provider·Policy·coordinator Bean과 성공 결과용
  immutable 인메모리 Snapshot 구현 및
  [`External Risk 조회 정책`](docs/01-requirements/external-risk-lookup-policy.md) 문서화
* External Risk 선행 조회와 `POST /api/v2/rule-analysis` 입력 연결 계약을
  [`External Risk·Rule 분석 입력 계약`](docs/01-requirements/external-risk-rule-analysis-input-contract.md)으로 확정하고,
  FastAPI v2 strict DTO·wire 및 교차 필드 검증·Endpoint와 Backend Java v2
  exact wire DTO·mapper·HTTP Client와 별도 내부 v2 오케스트레이션 경계 구현
* Public 거래 접수의 Idempotency 단일 승자가 거래 `RECEIVED` 저장·연결 commit 뒤
  트랜잭션 밖 External Risk Policy와 Rule 분석 v2를 호출하는 최종 동기 orchestration 구현
* External Risk typed failure의 공개 안전 오류 매핑과 Failure Snapshot 저장·재생,
  terminal replay의 Provider·Rule·최종화 미호출 구현
* LOW·MEDIUM·HIGH·CRITICAL별 목표 거래 상태, `RiskResponseOutcome`과 사건 필수
  여부를 반환하는 순수 위험 대응 결정 정책 구현
* `FraudCase`·`CaseTransaction` Entity와 Flyway V6, 거래 우선 비관적 잠금으로
  HIGH·CRITICAL 거래의 사건·첫 연결을 원자적으로 생성하거나 기존 활성 연결을
  멱등 반환하는 내부 persistence boundary 구현
* append-only AuditLog V7과 `ANALYZED` 거래의 decision·필요한 사건·최종 상태·
  `RiskResponseOutcome`·AuditLog를 함께 commit하거나 rollback하는 내부 최종화 경계 구현
* 위험 대응·필요한 사건·AuditLog 최종화와 별도 completion transaction의 성공
  Snapshot v2·Idempotency `COMPLETED`·신규 HTTP `201` 연결 구현
* 최종 성공 Snapshot v2 모델·codec·저장·재생과 strict legacy·Snapshot v1·Snapshot v2
  replay 호환 구현. 완료 간극 복구 계약은
  [`ADR-006`](docs/07-decisions/ADR-006-final-transaction-success-and-idempotency-recovery.md)으로 확정
* `updated_at` 기준 장기 `IN_PROGRESS` bounded 후보 조회, typed fail-closed 판정,
  검증된 Snapshot v2 completion gap 단건 복구와 별도 append-only 복구 감사 구현
* Idempotency→거래 잠금 순서와 terminal 재검증으로 동시 복구 단일 승자 구현
* strict 입력 검증과 제한된 non-web context를 사용하는 inspect·단건 recover one-shot
  Runner 및 운영 runbook 구현
* Backend와 AI Service 전용 GitHub Actions CI 구성

현재 PostgreSQL 연동은 애플리케이션 코드와 Testcontainers 검증 범위입니다. 운영 PostgreSQL, Docker Compose, Kubernetes와 AWS 배포 환경이 구현되었다는 의미는 아닙니다. Public 거래 접수의 신규 성공 응답은 최종 거래·탐지·위험 대응·필요한 사건 결과를 반영한 HTTP `201`이며, 최종 동기 분석 경계는 [`ADR-003`](docs/07-decisions/ADR-003-transaction-processing-boundary.md)을 따릅니다.

### Planned

Public 최종 동기 거래 접수와 실제 External Risk HTTP Provider, 공개 오류 매핑 및
성공 Snapshot v2 연결은 구현되었습니다. 후속 계획은 다음과 같습니다.

* recovery scheduler·batch와 장기 `IN_PROGRESS` Gauge·completion gap alert·dashboard
* 불확실 상태 재실행과 `FAILED` 재분석은 별도 operation scope·승인 계약 전까지 금지
* 공개 사건 조회·상태 변경·조사 메모·AuditLog API와 USER 인증·인가
* ML 추론
* AI 사건 리포트
* AI 사용량·토큰·비용 기록
* External Risk 운영 credential 실제 배포와 stuck·completion-gap 탐지 metric·alert·dashboard
* 자동 retry·fallback·cache는 별도 Issue와 계약 승인 전까지 도입하지 않음
* Redis 연동
* Docker Compose
* Kafka 비동기 처리
* 프론트엔드 최종 응답·사건 조회 연동과 React 관리자 화면
* 이미지 빌드·배포를 포함한 GitHub Actions CI/CD 확장
* Kubernetes·AWS 배포와 실제 배포 환경 E2E
* Observability
* 장애·비용 실험

## Rule v1 기본 RuleVersion one-shot 발행

V5는 항상 R001~R004를 `DRAFT`로 seed한다. 발행은 정상 애플리케이션 시작과
분리된 명시적 명령이며, 네 버전을 하나의 트랜잭션에서 같은 `effectiveFrom`과
`publishedAt`으로 전환한다. 다음은 local 환경 예시다. `effective-from`은 실행
시각보다 미래인 canonical UTC `Instant`로 교체해야 한다.

```powershell
cd backend
.\gradlew.bat bootRun --args="--spring.profiles.active=local,rule-v1-default-publication --spring.main.web-application-type=none --finguardops.rule-v1-default-publication.enabled=true --finguardops.rule-v1-default-publication.confirmation=PUBLISH_RULE_V1_DEFAULT_V1 --finguardops.rule-v1-default-publication.effective-from=2099-01-01T00:00:00Z"
```

활성화는 의도적인 2단계 방어다. Bean 생성 gate는 전용
`rule-v1-default-publication` profile과 `enabled=true`만 확인한다. Bean 생성 후
runtime fail-fast gate가 `local`/`dev`/`test` 중 하나, non-web mode, exact
confirmation, 명시적 미래 UTC 시각을 모두 요구하고 `production` 또는 `prod`
profile이 하나라도 함께 활성화되면 실행을 거부한다. 따라서 전용 profile과
`enabled=true`만 설정한 잘못된 one-shot 명령은 조용히 성공 종료하지 않고
명시적으로 실패할 수 있다. 정확히 같은 네 PUBLISHED 버전과 `effectiveFrom`
재호출만 멱등 성공한다. 조건 원문은 로그에 남기지 않는다.

현재 임계값·가중치·시간창은 실험값이므로 production 발행은 승인되지 않았다.
새 정책은 기존 PUBLISHED 버전을 되돌리거나 덮어쓰지 않고 새 `versionNumber`로
설계해야 한다.

신규 최종 성공은 Rule 결과 채택뿐 아니라 위험 대응, 최종 거래 상태와
HIGH·CRITICAL 사건 연결의 모든 업무 commit 이후 별도 completion transaction에서
`transaction-create-response-v2`와 `transaction-intake-snapshot-envelope-v2`로
저장되고 HTTP `201`로 반환됩니다. strict legacy·Snapshot v1·Snapshot v2는 저장된
응답과 HTTP 상태를 재생하며 terminal replay는 Provider·Rule·최종화를 다시 호출하지
않습니다. 최종 업무 commit 뒤 completion이 실패하면 업무 결과는 유지하고
Idempotency는 `IN_PROGRESS`로 남으며 자동 재실행하지 않습니다. 내부 운영 복구
경계는 확정된 최종 업무 상태만 검증해 같은 Snapshot v2를 복원하며 Provider·Rule·
최종화·사건 생성을 호출하지 않습니다. 실제 one-shot 명령도 정확한 내부 record 하나만
이 경계에 위임하며 scheduler·batch·자동 retry를 제공하지 않습니다. 상세 경계는
[`ADR-006`](docs/07-decisions/ADR-006-final-transaction-success-and-idempotency-recovery.md)을
따릅니다. DB의 24시간 `expires_at` 저장값도 Service 만료 판정과 정리 작업이
없어 실질적인 만료 정책은 아닙니다.

거래 처리 운영 메트릭은 Micrometer로 구현되어 public
`POST /api/v1/transactions`의 최종 intake 결과를 요청당 한 번 기록합니다.
최초 `RECEIVED`와 `APPROVED`·`ADDITIONAL_AUTH_REQUIRED`·`HELD`·`FAILED`
terminal 결과는 실제 DB commit 뒤에만 증가하고, 처리시간은
`financial_transaction.created_at`부터 최초 terminal `updated_at`까지의 DB 시각을
사용합니다. 같은 key·fingerprint의 진행·완료·실패 replay는 intake와 duplicate
request만 증가시키며 Provider·Rule·거래 terminal 결과를 다시 기록하지 않습니다.
External Risk와 Spring Rule orchestration은 실제 시도만 단조 시간으로 관측하고,
모든 custom tag는 `spring-backend`와 문서화된 enum 집합으로 제한합니다. Metric
등록·기록 실패는 업무 응답과 transaction을 변경하지 않습니다.

`micrometer-registry-prometheus`는 production runtime에 포함되지만 기본 profile에서는
export와 `/actuator/prometheus`가 비활성입니다. `prometheus` profile을 활성화하면
별도 management listener가 기본 `127.0.0.1:8081`에서 시작되고 web endpoint는 정확히
`health,prometheus`만 노출됩니다. port와 address는 각각 `MANAGEMENT_SERVER_PORT`,
`MANAGEMENT_SERVER_ADDRESS`로 재정의할 수 있습니다. 실행·확인 방법과 외부 bind 시
보안 책임은 [`Management endpoint 운영 경계`](docs/03-api/management-endpoints.md)를
따릅니다.

Prometheus 서버·scrape 설정, recording rule, custom bucket·percentile, alert·dashboard와
process crash까지 보장하는 durable metric/outbox는 아직 구현하지 않았습니다.
정확한 이름·tag·category는
[`관측성 메트릭 명세`](docs/01-requirements/observability-metrics-spec.md)를 따릅니다.

---

## 로드맵

```text
1. FinGuardOps 포지셔닝·ADR·README
2. 플랫폼 운영 요구사항
3. FDS·플랫폼 운영 화면 와이어프레임
4. 거래·사건·AI 리포트 상태 전이
5. 시스템 아키텍처
6. ERD·API·이벤트·메트릭 명세
7. 거래·행동 이벤트·Rule·사건·감사 로그 구현
8. FastAPI·ML·AI 리포트·비용 기록
9. PostgreSQL·Redis·Docker Compose
10. Kafka
11. React 프론트엔드
12. CI/CD·Kubernetes·AWS·Observability
13. 장애·비용 실험과 회고
```

핵심 거래·탐지·사건 기능을 먼저 구현하고, Kafka와 Kubernetes는 핵심 기능 안정화 이후 도입합니다.

---

## 생성형 AI 활용 방식

생성형 AI는 역할별 개발 도구로 활용합니다.

* 요구사항과 문서 초안 작성
* Spring Boot·FastAPI·React 구현 초안
* 테스트 코드 초안
* 코드 리뷰와 누락 사항 점검
* 장애·성능·비용 실험 아이디어 도출

다음 항목은 프로젝트 소유자가 직접 결정하고 검증합니다.

* 요구사항
* API 명세
* 데이터베이스 설계
* 상태 전이
* 시스템 아키텍처
* 테스트 기준
* 코드 검토
* DevOps와 배포
* 장애·비용 실험
* 최종 승인

> 생성형 AI를 역할별 개발 도구로 활용했으며, 요구사항 정의, API·DB 설계, 아키텍처 결정, 코드 검토, 테스트, DevOps와 최종 검증은 직접 수행했습니다.

---

## 프로젝트 원칙

* `main` 브랜치에 직접 커밋하지 않습니다.
* GitHub Issue 단위로 브랜치를 생성합니다.
* 기능 또는 문서 단위로 Pull Request를 생성합니다.
* PR에는 관련 Issue와 테스트·검증 결과를 기록합니다.
* 최종 merge는 프로젝트 소유자가 수행합니다.
* 구현하지 않은 기능을 완료된 것처럼 작성하지 않습니다.
* 측정하지 않은 성능 향상이나 비용 절감률을 성과로 작성하지 않습니다.
* 생성형 AI의 판단만으로 API·DB·아키텍처를 변경하지 않습니다.
