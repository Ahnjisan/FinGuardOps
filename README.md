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
Issue #186 외 사건·AI·복구 상태 등의
추가 업무 metric은 아직 구현되지 않았습니다. 로컬 Docker Compose의 Prometheus 서버·
Backend scrape와 기존
업무 Meter 기반 recording rule 14개와 실패율 alert rule 6개, 각각의 deterministic
promtool test, raw·recorded query와 로컬 alert 상태 검증 경계가 구현되었습니다. 기존
recording rule 14개와 target·alert 상태를 표시하는 로컬 Grafana dashboard도 file
provisioning과 결정적 검증 경계로 구현되었습니다. 로컬
Alertmanager runtime, Prometheus 연결, grouping·routing, signal별 warning inhibition,
bounded in-memory webhook receiver와 firing·resolved·restart·장애 검증 경계도 구현되었습니다.
production Prometheus·Alertmanager·Grafana·credential 배포와 외부 알림은 미구현입니다.
Spring Boot runtime Prometheus registry와 opt-in Actuator endpoint의 경계도
구현되었습니다. 기존 Rule 분석
v1은 당장 제거하지 않으며 Rule v1 기본
RuleVersion 집합의 제한된 local/dev/test one-shot 발행 경계만 제공하고 공개 관리
API나 정상 시작 자동 발행은 제공하지 않습니다. `ANALYZED`는 위험 대응 전 중간
상태이므로 최종 성공으로 반환하거나 성공 Snapshot으로 확정하지 않습니다.

Spring Backend에는 제품 중립적인 Spring Security·OAuth2 Resource Server 기반이
구현되었습니다. RS256 Bearer JWT와 JWK를 사용하며 issuer·audience·kid·subject·
`principal_type`·roles·시간 claim을 검증하고, USER·SERVICE principal을 분리해
role-derived authority를 생성합니다. 실제 13개 production endpoint의 USER·SERVICE
authority matrix, strict deny-by-default URL matcher와 네 high-risk write Service의
method security도 구현했습니다. stateless session·CSRF·exact-origin CORS와
401·403·JWK 장애 오류·trace 처리도 적용했습니다. 자세한 계약은
[`보안 아키텍처`](docs/02-architecture/security-architecture.md)와
[`ADR-008`](docs/07-decisions/ADR-008-oauth2-resource-server-rbac-user-audit-actor.md)을
따릅니다.

`/api/health`와 profile별 승인된 health·Actuator 경계는 credential 없이 접근할 수 있지만
invalid Bearer가 명시되면 401이다. 12개 업무 method·path는 승인된 authority를 요구하며
그 밖의 application path·method·trailing slash는 deny-by-default다. credential 없음·invalid
JWT는 401, valid JWT의 authority 부족과 USER·SERVICE 경계 위반은 403이다. 권한을 통과한
실제 resource 없음과 미노출 Actuator는 기존 404를 유지한다. `PLATFORM_ADMIN`은 viewer·
업무 write·ingestion 권한을 자동 상속하지 않는다. management 8081은 업무 Resource Server
chain과 분리한다.

사건 상태·담당자·종결·조사 메모 write는 검증된 USER JWT의 canonical lowercase UUID v4
`sub`를 AuditLog actor와 InvestigationNote author로 기록합니다. 자동 사건 생성·거래 처리·
Rule/AI orchestration·복구·one-shot writer는 기존 `SYSTEM/finguardops-backend`를 유지합니다.
local/dev Authorization Server는 Keycloak으로 선정했지만 Keycloak container·realm·client·
protocol mapper와 실제 Frontend·Backend 연동 runtime은 아직 구현되지 않았다. production
Authorization Server와 management mTLS·인증 proxy도 별도 후속 범위다. Frontend는 Authorization
Code + PKCE 로그인·callback·local logout과 memory-only token 경계에 더해, 승인된 10개
USER method·path에만 `Authorization: Bearer`를 전달하는 인증 API transport와 401·403 경계를
구현했다. credential capability는 승인된 Backend USER endpoint가 아닌 destination을 스스로
거부하며, React tree에 게시되는 값에는 이 capability가 존재하지 않는다. 업무 화면과
role·authority 권한 UI도 아직 없다. Issue #225의 Local JWT fixture는 production Authorization
Server나 브라우저 OIDC Provider가 아닌 Backend 회귀·장애 검증용 local/manual E2E이며,
Keycloak과 같은 Backend issuer 설정에서 동시에 사용하지 않는다. 상세 결정은
[`ADR-011`](docs/07-decisions/ADR-011-keycloak-authorization-server-and-claim-contract.md)을
따릅니다.

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
* Vite
* React Router
* Vitest·Testing Library
* React·TypeScript·Vite foundation과 Router, public health client, OIDC Authorization
  Code + PKCE 인증 경계(`oidc-client-ts`), 승인 endpoint 전용 인증 API transport와 401·403
  경계 구현. 권한 UI와 거래·사건·운영 업무 화면은 아직 구현되지 않음

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
* OAuth2 Resource Server·JWT·RBAC·USER Audit actor 보안 아키텍처 계약 문서화
* Spring Boot OAuth2 Resource Server 기반, RS256·strict JWT claim 검증,
  USER·SERVICE principal과 role-derived authority 구현
* 안전한 401·403·JWK 503·decoder 500·trace 응답, stateless·CSRF·exact-origin CORS와
  application/management listener 분리 구현
* 13개 production endpoint의 USER·SERVICE authority matrix, strict deny-by-default URL
  matcher와 사건 workflow·resolution·조사 메모 생성 Service method security 구현
* 핵심 도메인 ERD 작성
* API 공통 규칙 정의
* 거래·행동·탐지 API 계약 작성
* 사건·조사 메모·감사 API 계약 작성
* AI 리포트·AI 사용량·비용 API 계약 작성
* 도메인 이벤트 계약 작성
* 관측성·운영 메트릭 명세 작성
* Spring Boot runtime Prometheus registry와 `prometheus` profile 전용
  `/actuator/prometheus` endpoint 구현
* 로컬 Alertmanager routing·signal별 inhibition, Prometheus 연결과 bounded webhook
  receiver fixture의 firing·resolved·restart·장애 검증 경계 구현
* 로컬 Grafana loopback UI, Prometheus datasource·dashboard file provisioning과
  recording rule 14개·target·alert 상태의 16-panel 검증 경계 구현
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
* React·TypeScript·Vite 기반 Frontend foundation, `createBrowserRouter` 기반 Router(`/`,
  `/health`, `/auth/callback`, `*`)와 App Shell, public Backend `GET /api/health` client와
  loading·success·error 화면 상태 구현
* Frontend OIDC Authorization Code + PKCE 인증 경계 구현. `oidc-client-ts` 기반 redirect
  로그인·callback·local logout, memory-only access/ID token, sessionStorage에는 transient
  protocol transaction record만 보관, 최대 15분 hard session deadline, callback URL 조기 정리와
  `/`·`/health` exact allowlist 복귀 경로. local/dev 제품은 Keycloak으로 선정했지만 runtime
  연동은 미구현이며 silent renew, refresh token 사용·반환 시 fail-closed adapter, remote logout과
  권한 UI도 구현하지 않음
* Frontend 인증 Backend API transport와 401·403 경계 구현. endpoint key가 method·path를
  결정하는 승인 10개 USER endpoint allowlist, canonical UUID v4 path parameter 검증과 exact
  origin·pathname 재검증, raw token을 반환하지 않고 승인 `Request`에 Authorization을 부착하는
  `authorizeRequest()` 경계와 그 capability 자체의 destination 검증, credential capability를
  노출하지 않는 public AuthContext facade, 요청을 승인한 session에만 적용되는 조건부 401
  invalidation과 동시 401 단일 teardown, 403 session 유지, 자동 retry·write replay 0, RFC 6750
  Bearer 문법 검증, 인증 준비부터 response validator까지 monotonic clock 기반 단일 5초 deadline.
  public `GET /api/health`와 SERVICE ingestion·management·AI·관측·외부 origin에는 credential을
  전달하지 않으며 query pagination과 업무 화면은 구현하지 않음
* ADR-011에서 local/dev Authorization Server를 Keycloak으로 선정하고 USER public client의
  Authorization Code + PKCE `S256`, 분리된 SERVICE confidential client의 Client Credentials,
  Backend access token exact claim, USER access/ID token의 동일 subject·role 집합과 일반 refresh
  token fail-closed 계약을 문서로 확정. Keycloak runtime·realm·client·mapper, 실제 연동과 role
  UI 및 refresh token 검사 adapter는 구현하지 않음

Backend Security 설정은 `FINGUARDOPS_SECURITY_ISSUER`,
`FINGUARDOPS_SECURITY_JWK_SET_URI`, `FINGUARDOPS_SECURITY_ALLOWED_ORIGINS`와 JWK
connect/read timeout을 사용합니다. issuer·JWK 기본값은 접근 불가능한 HTTPS `.invalid`
placeholder여서 설정 누락 시 인증을 끄지 않고 fail-closed하며, 기본 CORS origin 목록은
비어 있습니다. 실제 key·JWT·credential은 저장소와 `.env.example`에 저장하지 않습니다.

현재 PostgreSQL 연동은 애플리케이션 코드·Testcontainers와 로컬 Compose 검증 범위입니다. 운영 PostgreSQL과 production container, Kubernetes·AWS 배포 환경이 구현되었다는 의미는 아닙니다. Public 거래 접수의 신규 성공 응답은 최종 거래·탐지·위험 대응·필요한 사건 결과를 반영한 HTTP `201`이며, 최종 동기 분석 경계는 [`ADR-003`](docs/07-decisions/ADR-003-transaction-processing-boundary.md)을 따릅니다.

### Planned

Public 최종 동기 거래 접수와 실제 External Risk HTTP Provider, 공개 오류 매핑 및
성공 Snapshot v2 연결은 구현되었습니다. 후속 계획은 다음과 같습니다.

* recovery scheduler·batch와 장기 `IN_PROGRESS` Gauge·completion gap alert·dashboard
* 불확실 상태 재실행과 `FAILED` 재분석은 별도 operation scope·승인 계약 전까지 금지

보안 기반과 endpoint RBAC는 Issue #219와 Issue #221에서, 사건 USER 감사 주체 연결은
Issue #223에서 구현되었습니다. Issue #225에서는 production Authorization Server가 아닌
local/manual 전용 RS256 fixture와 Compose 인증 E2E를 추가했습니다. 실행·token 비노출·
rotation·sidecar 재생성 절차는
[`Local JWT 인증 E2E runbook`](docs/09-deployment/local-jwt-auth-e2e-runbook.md)을 따릅니다.
남은 보안 후속 순서는 다음과 같습니다.

1. Keycloak local/dev Compose·realm·client·role·client scope·protocol mapper 구현
2. Frontend OIDC와 Spring Backend를 연결한 USER 로그인 E2E
3. SERVICE Client Credentials 기반 거래·행동 이벤트 접수 E2E
4. Frontend role·authority UI 계약과 구현
5. 거래·사건·메모·감사 typed API module과 query pagination
6. Keycloak remote logout 계약과 구현

제품과 claim 계약은 Issue #233의
[`ADR-011`](docs/07-decisions/ADR-011-keycloak-authorization-server-and-claim-contract.md)에서
확정되었습니다. USER client는 public client + Authorization Code Flow + PKCE `S256`, SERVICE
ingestion은 분리된 confidential client + Client Credentials Flow를 사용합니다. Backend는 exact
claim 계약을 만족하는 access token만 API credential로 받습니다. USER와 두 SERVICE client의
raw JSON `aud`는 string이 아닌 정확한 singleton array `["finguardops-backend-api"]`여야 하며,
기본 Audience Resolve를 포함한 모든 추가 audience source를 통제하고 실제 발급 token 전체
배열을 E2E에서 검사합니다. 이를 위해 Backend validator를 완화하지 않습니다.

동일 USER session의 access token과 ID token은 원문이 완전히 같은 canonical lowercase UUID v4
`sub`와 중복 없는 동일한 FinGuardOps USER role 집합을 가져야 합니다. `roles` 배열 순서는
의미가 없으며 후속 E2E는 정규화하지 않은 `sub` 원문과 순서 독립적인 role 집합을 비교합니다.
ID token의 `principal_type=USER`와 `roles`는 Frontend UI 표시용이며 Backend 401·403을 대체하지
않습니다. `offline_access`와 offline token은 금지하지만 일반 온라인 refresh token은 별도로
반환될 수 있다고 가정합니다. 후속 runtime adapter는 실제 response에 `refresh_token`이 있으면
session을 게시하지 않고 user state와 유지 credential을 제거하며 refresh grant·silent renew를
0회로 유지해야 합니다. 검증된 Keycloak 26.x exact image tag·digest, 구체적인 mapper 설정,
Frontend adapter와 E2E는 1~3번 후속 Issue에서 구현·확정합니다.

Infra 인증 E2E는 Frontend 구현의 일부가 아니고 Frontend OIDC도 Compose traffic fixture의
일부가 아니다. 이 세 단계는 토큰 절약을 위한 인위적
분할이 아니라 기술 책임·선행 관계·실패 영향·검증 시간이 다르기 때문에 분리한다.

* ML 추론
* AI 사건 리포트
* AI 사용량·토큰·비용 기록
* External Risk 운영 credential 실제 배포와 stuck·completion-gap 탐지 metric·alert·dashboard
* 자동 retry·fallback·cache는 별도 Issue와 계약 승인 전까지 도입하지 않음
* Redis 연동
* Kafka 비동기 처리
* 프론트엔드 최종 응답·사건 조회 연동과 React 관리자 화면
* 이미지 빌드·배포를 포함한 GitHub Actions CI/CD 확장
* Kubernetes·AWS 배포와 실제 배포 환경 E2E
* production Prometheus·Alertmanager·Grafana, 보안·TLS·SSO·RBAC·HA·장기 보존을
  포함한 Observability
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

로컬 Compose 실행·target `UP`·업무 Meter와 recording rule query 절차는
[`Prometheus 로컬 scrape runbook`](docs/09-deployment/prometheus-local-scrape-runbook.md)을
따릅니다. Compose에서만 management address를 `0.0.0.0`으로 override합니다. Backend는
application·management port를 host에 publish하지 않고 internal application·observability
network에만 연결합니다. Prometheus만 `prometheus-ui` bridge에도 연결하며 UI는
`127.0.0.1:9090`에 bind합니다. Grafana는 internal observability와 Grafana 전용
`grafana-ui` bridge에만 연결하며 UI는 `127.0.0.1:3000`에 bind합니다. Alertmanager와
local webhook receiver는 internal
observability network에만 연결하며 host port와 Alertmanager UI를 publish하지 않습니다.
내부 API 확인은 helper 또는 `docker compose exec`를 사용합니다. 이 network 경계는
인증·TLS를 대신하지 않습니다.

PostgreSQL과 AI Service는 application network DNS를 사용하지만 External Risk fixture는
Backend의 network namespace를 공유하며 `127.0.0.1:8001`에만 bind합니다. Backend도
loopback으로 fixture를 호출하므로 non-production plain HTTP loopback 제한은 유지됩니다.
이 sidecar 경계는 production External Risk Provider 정책이나 인증·TLS를 대체하지 않습니다.

Issue #225의 선택형 `infra/compose.local-jwt-e2e.yml` overlay는 같은 Backend network
namespace에 local JWT fixture를 하나 더 추가하고 JWKS를 `127.0.0.1:8002`에만 bind합니다.
Backend의 issuer는 고정 HTTPS 식별자이고 plain HTTP JWK opt-in은 이 overlay에만 있습니다.
발급·rotation·fault 제어는 private Unix socket과 container CLI로만 수행하며 HTTP token
endpoint, host port, 추가 network·volume은 없습니다. Backend recreate 전 두 sidecar를
제거하고 recreate 후 둘 다 새 namespace에 생성해야 합니다. base Compose와 production
기본값은 변경하지 않으며 전체 인증 E2E는 local/manual입니다.

로컬 recording rule은 5분 window와 30초 evaluation으로 service 수준의 rate·failure
ratio·평균 duration만 계산합니다. 로컬 alert rule 6개는 거래 terminal, External Risk,
Rule Analysis 실패율에 warning `> 0.10`/`for: 2m`, critical `> 0.30`/`for: 5m`과
최소 처리율 `>= 0.10/s`를 적용합니다. 이 값은 production SLA·SLO가 아니며
로컬 Alertmanager는 `alertname,service`로 묶어 local webhook으로 firing·resolved를
전달하고 동일 service·동일 signal의 critical로 warning을 억제합니다. inhibition 전에
전달된 warning은 취소되지 않습니다. 이 로컬 전달은 exactly-once나 고정 retry 횟수를
보장하지 않으며 restart·ambiguous failure에서 duplicate 또는 loss가 가능합니다. local
webhook은 production receiver가 아닙니다. 로컬 Grafana는 datasource UID
`finguardops-prometheus`, dashboard UID `finguardops-local-overview`를 fresh·existing named
volume에 file provisioning하며 admin credential은 ignored `infra/.env`에서만 받습니다.
anonymous access는 비활성화되고 bootstrap credential 변경은 기존 volume의 admin 계정을
자동 변경하지 않습니다. completion gap, `deployment.error_ratio`, `deployment.latency`, 장기
`IN_PROGRESS` Gauge는 구현하지 않았습니다. production Prometheus·Alertmanager·Grafana·
receiver·credential 배포와 외부 Slack·email·SMS·PagerDuty, 인증·TLS·SSO·RBAC,
HA·장기 retention, Kubernetes·AWS, OpenTelemetry와 process crash까지 보장하는
durable metric/outbox도 아직 구현하지 않았습니다.
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
