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

Rule 가중치와 위험 등급 임계값은 테스트 데이터를 기반으로 검증한 뒤 결정합니다.

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

* PostgreSQL: 거래, 행동 이벤트, 탐지 결과, 사건, 감사 로그, Rule, AI 사용량·비용
* Redis: 정확 일치 리포트 캐시, 외부 위험정보 캐시, 집계 데이터
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

* Python
* FastAPI
* Rule Engine
* Machine Learning
* Generative AI API

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
* FinGuardOps 제품 포지셔닝 결정

### In Progress

* FinGuardOps README·서비스 범위·ADR 정비
* 플랫폼 운영 요구사항 정의 준비

### Planned

* 거래·행동 이벤트 모델링
* 거래·사건 상태 전이
* 시스템 아키텍처 명세
* ERD와 REST API 명세
* Rule 관리와 실행
* 위험 점수 산출
* 사건 생성·조회·상태 변경
* 감사 로그
* FastAPI 연동
* ML 추론
* AI 사건 리포트
* AI 사용량·토큰·비용 기록
* PostgreSQL·Redis 연동
* Docker Compose
* Kafka 비동기 처리
* React 관리자 화면
* GitHub Actions CI/CD
* Kubernetes·AWS 배포
* Observability
* 장애·비용 실험

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
