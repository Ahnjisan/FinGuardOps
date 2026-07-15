# ADR-001: FinGuardOps 제품 포지셔닝

- 상태: Accepted
- 결정일: 2026-07-15
- 결정자: Project Owner
- 관련 문서:
  - `README.md`
  - `docs/00-overview/fds-service-scope.md`
  - `docs/01-requirements/fds-user-scenarios.md`
  - `docs/07-decisions/ADR-002-rename-repository-to-finguardops.md`

## Context

기존 프로젝트는 금융거래와 사용자 행동을 기반으로 이상거래를 탐지하고 분석 리포트를 제공하는 방향으로 시작했다. 그러나 이상거래 탐지만으로는 탐지 결과가 실제 FDS 분석 담당자의 검토와 사건 처리로 어떻게 연결되는지 보여주기 어렵다.

또한 AI 서비스를 사용하려면 정상 동작뿐 아니라 Timeout, 오류, fallback, 지연시간, 사용량과 비용을 함께 운영해야 한다. 백엔드·클라우드·DevOps·플랫폼 역량을 금융 업무 문제와 연결해 보여주려면 사건 처리, Observability, 장애 격리와 AI FinOps를 공식 목표로 강화할 필요가 있다.

현재는 거래·사건 Entity, ERD와 핵심 API를 구현하기 전이다. 따라서 기존 금융 FDS의 범위와 시나리오를 유지하면서 제품 정의와 이후 설계의 기준을 정비하기에 적절한 시점이다.

## Decision

- 저장소명은 `AI-FINANCIAL-FDS-PLATFORM`으로 유지하고, 공식 제품명을 `FinGuardOps`로 정의한다.
- FinGuardOps는 기존 금융 FDS 프로젝트를 폐기하거나 대체하는 새 프로젝트가 아니다. 기존 기능과 시나리오를 유지하면서 탐지 결과를 위험 대응과 사건 관리로 연결하고, AI 서비스의 운영 범위를 강화한다.
- 주요 사용자를 `FDS 분석 담당자`와 `플랫폼·클라우드 운영자`로 구분한다.
- 프로젝트를 `금융 FDS`, `AI 운영`, `Cloud Native 운영`의 세 영역으로 설명한다.
- 탐지 결과를 위험 등급별 대응과 사건 생성·검토·최종 판정으로 연결한다.
- HIGH·CRITICAL 사건을 중심으로 생성형 AI 사건 리포트를 제공한다.
- AI와 외부 서비스의 장애가 핵심 거래 판단을 중단시키지 않도록 fallback과 장애 격리를 설계한다.
- LLM의 호출량, 입력·출력 토큰, 지연시간과 비용을 기록하고 측정한다.
- 초기 구조는 Spring Boot Modular Monolith와 FastAPI AI Service로 구성하며, 처음부터 전체 시스템을 MSA로 분리하지 않는다.
- Kafka와 Kubernetes는 핵심 거래·탐지·사건 기능이 안정화된 이후 단계적으로 도입한다.

이 결정은 제품과 아키텍처의 목표 및 단계적 계획을 정하는 것이다. 아래 강화 범위와 개발 우선순위에 포함된 항목을 현재 모두 구현된 기능으로 간주하지 않는다.

## 후속 변경

2026-07-15에 제품명과 저장소명을 통일하기 위해 GitHub 저장소명과 로컬 폴더명을 `FinGuardOps`로 변경했다. 자세한 결정은 [ADR-002](./ADR-002-rename-repository-to-finguardops.md)를 따른다.

이 변경은 제품 범위나 아키텍처를 변경하지 않으며, 기존 Git 이력, Issue, PR과 구현 구조는 유지한다.

## 유지 범위

다음 범위는 기존 금융 FDS 프로젝트에서 유지한다.

- 기존 금융거래 유형
- 기존 사용자 행동 이벤트
- 기존 8개 이상거래 시나리오
- Rule 기반 탐지
- ML 기반 복합 패턴 보완
- 위험 점수와 설명 가능한 탐지 근거
- 위험 등급별 대응
- 사건 생성·검토·최종 판정
- 사건 상태와 최종 판정의 분리
- 감사 로그
- 고위험 사건 AI 리포트
- 정확 일치 AI 리포트 캐시
- 실제 거래·인증·차단 기능을 Mock으로 처리하는 원칙
- 기존 Spring Boot 초기 설정과 Health API

## 강화 범위

다음 항목은 FinGuardOps의 목표와 계획 범위로 새롭게 강화한다. 각 항목은 해당 개발 단계에서 구현하고 실제 측정 및 검증을 거쳐야 하며, 이 ADR의 채택만으로 구현 완료된 것으로 보지 않는다.

- 플랫폼·클라우드 운영자 역할
- Spring Boot·FastAPI·PostgreSQL·Redis 상태와 향후 도입할 Kafka 상태 관측
- API·Rule·ML·LLM 지연시간 관측
- 오류율과 처리량 관측
- DB Connection Pool과 Kafka 도입 이후 Consumer Lag 관측
- 로그·메트릭·트레이싱 기반 처리 흐름 추적
- 외부 위험정보·FastAPI·LLM 장애 격리
- 모델 라우팅과 템플릿 fallback
- 모델별 호출량·입력 토큰·출력 토큰·비용 기록
- 캐시 적중률과 fallback 비율
- 배포 상태와 장애 이력
- 장애 주입·복구 검증
- AI 비용 및 FinOps 실험

## 제외 범위

- 실제 금융기관 계좌와 실제 고객 거래
- 실제 거래 승인·인증·차단·고객 제재
- 처음부터 전체 시스템을 MSA로 분리하는 구성
- 핵심 거래·탐지·사건 기능보다 앞선 Kafka·Kubernetes 도입
- 시맨틱 캐시
- 범용 FDS 챗봇
- Investigation Copilot
- Reason Code만을 기준으로 한 사건 간 AI 리포트 재사용
- 실제 금융기관 수준의 대규모 인프라
- 생성형 AI를 통한 위험 점수 계산
- 생성형 AI를 통한 최종 이상거래 판정
- 생성형 AI를 통한 사건 상태 자동 확정
- 측정되지 않은 비용 절감률과 성능 향상 주장

## Consequences

### 긍정적 결과

- 이상거래 탐지부터 사건 처리까지 전체 업무 흐름을 보여줄 수 있다.
- Spring Boot의 트랜잭션·멱등성·상태 전이·감사 로그 역량을 강조할 수 있다.
- FastAPI·Rule·ML·생성형 AI의 역할을 구분할 수 있다.
- AI 장애·성능·비용을 통제하는 운영 역량을 보여줄 수 있다.
- Docker·Kafka·Kubernetes·AWS·Observability의 단계적 도입 이유를 금융 업무 문제와 연결할 수 있다.
- 백엔드·클라우드·DevOps·플랫폼 직무 포트폴리오로 활용할 수 있다.

### Trade-off

- 플랫폼 운영 요구사항과 문서 작성 범위가 증가한다.
- 메트릭, 장애와 비용을 실제로 측정하고 검증해야 한다.
- 프론트엔드에서 FDS 분석 화면과 플랫폼 운영 화면을 구분해야 한다.
- 프로젝트 범위가 과도하게 확장되지 않도록 단계별 우선순위를 관리해야 한다.
- Kafka·Kubernetes 같은 기술을 목적 없이 조기 도입하지 않도록 통제해야 한다.

## 개발 우선순위 영향

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

핵심 거래·탐지·사건 기능을 먼저 구현하고 안정화한다. Kafka와 Kubernetes는 이 핵심 기능보다 먼저 구현하지 않는다. Kafka·Kubernetes·AWS·Observability는 위 우선순위에 따른 향후 도입 및 검증 대상이지, 현재 구현 완료된 구성요소가 아니다.

## 캐시 원칙

AI 사건 리포트 캐시는 다음 값이 모두 일치하는 정확 일치 키를 사용한다.

```text
caseId
+ detectionResultVersion
+ promptVersion
+ modelVersion
```

Reason Code가 같다는 이유만으로 다른 사건의 AI 리포트를 재사용하지 않는다. 캐시 재사용은 동일 사건에서 탐지 결과, 프롬프트와 모델 버전까지 모두 일치할 때만 허용한다.
