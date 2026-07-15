# AGENTS.md

## 프로젝트 정보

- 저장소명: AI-FINANCIAL-FDS-PLATFORM
- 제품명: FinGuardOps
- 설명: 금융거래와 사용자 행동을 기반으로 이상거래를 탐지하고, 위험 대응과 사건 처리를 지원하며, AI 서비스의 장애·성능·비용을 통합 관리하는 Cloud Native 금융 AI FraudOps 플랫폼

기존 금융 FDS 기능을 유지하면서 백엔드, 프론트엔드, AI 서비스, 인프라, 문서를 역할별로 분리해 개발한다.

## 주요 사용자

### FDS 분석 담당자

- 위험 거래와 사건을 조회한다.
- Rule·ML 탐지 근거를 검토한다.
- 행동 타임라인과 연관 거래를 확인한다.
- AI 사건 리포트를 검토한다.
- 조사 메모를 작성한다.
- 사건 상태와 최종 판정을 관리한다.
- 감사 이력을 조회한다.

### 플랫폼·클라우드 운영자

- Spring Boot·FastAPI 상태를 확인한다.
- PostgreSQL·Redis·Kafka 상태를 확인한다.
- API 지연시간·오류율·처리량을 확인한다.
- DB Connection Pool과 Kafka Consumer Lag을 확인한다.
- LLM 호출량·토큰·비용을 확인한다.
- 캐시·fallback·장애·배포 상태를 추적한다.

## 프로젝트 핵심 영역

- 금융 FDS
- AI 운영
- Cloud Native 운영

## 사용자 역할

사용자는 이 프로젝트에서 다음 역할을 수행한다.

- PM
- Backend Lead
- DevOps Engineer
- Tester
- Final Approver

AI 개발 도구는 사용자의 승인과 지시에 따라 작업하며, 최종 의사결정과 승인 권한은 사용자에게 있다.

## 공통 작업 원칙

- 작업은 GitHub Issue를 기준으로 시작한다.
- 구현 전 Issue의 목적, 범위, 완료 조건을 확인한다.
- 작업 브랜치는 Issue 단위로 생성한다.
- `main` 브랜치에 직접 커밋하지 않는다.
- Pull Request는 변경 목적, 변경 파일, 테스트 결과, 확인 필요 사항을 포함해 작성한다.
- 사용자 승인 없이 API 명세, DB 설계, 디렉터리 책임 범위를 임의로 변경하지 않는다.
- 기존 파일을 수정할 때는 현재 구조와 네이밍 규칙을 우선 따른다.
- 테스트를 실행하지 못한 경우, 미실행 사유와 사용자가 직접 확인해야 할 항목을 명시한다.
- 사용자 승인 없이 `git add`, `git commit`, `git push`, `git reset`, `git clean`, `git rebase`를 실행하지 않는다.
- 파일 생성/수정/삭제 전에는 변경 계획을 먼저 제시하고 사용자 승인을 받는다.
- 삭제, 이동, 대규모 리팩터링, 의존성 변경은 반드시 사전 승인을 받는다.

## 아키텍처 및 범위 제약

- 처음부터 전체 시스템을 MSA로 분리하지 않는다.
- 핵심 거래·탐지·사건 기능보다 Kafka와 Kubernetes를 먼저 도입하지 않는다.
- Spring Boot는 거래·사건 상태와 업무 정합성의 최종 소유자이다.
- 생성형 AI는 위험 점수, 최종 판정, 거래 차단, 고객 제재, 사건 상태 확정을 수행하지 않는다.
- 시맨틱 캐시와 범용 FDS 챗봇을 추가하지 않는다.
- Reason Code가 같다는 이유로 다른 사건의 AI 리포트를 재사용하지 않는다.
- 구현하지 않은 기능을 완료된 기능처럼 작성하지 않는다.
- 측정하지 않은 비용 절감률이나 성능 향상을 성과처럼 작성하지 않는다.

## 공식 기준 문서

- API 명세의 기준은 `docs/03-api/`이다.
- DB 설계의 기준은 `docs/04-database/`이다.
- 시스템 구조의 기준은 `docs/02-architecture/`이다.
- AI 개발팀 운영 방식의 기준은 `docs/08-ai-team-workflow/`이다.
- 구현 코드가 문서와 다를 경우, 임의로 판단하지 말고 사용자에게 확인한다.

## AI 도구별 책임

### Codex Backend AI

- 기본 작업 범위는 `backend/`이다.
- Spring Boot 백엔드 구현 초안을 작성한다.
- Controller, Service, Repository, DTO, Entity, Test 코드 초안을 작성한다.
- API 변경이 필요한 경우 사용자 승인 후 `docs/03-api`를 함께 업데이트한다.
- DB 구조 변경이 필요한 경우 사용자 승인 후 `docs/04-database`를 함께 업데이트한다.

### Claude Code Frontend AI

- 기본 작업 범위는 `frontend/`이다.
- React/TypeScript 기반 프론트엔드 구현 초안을 작성한다.
- 화면, 컴포넌트, API client, 상태 관리 코드를 역할별로 분리한다.
- 백엔드 API 명세를 임의로 변경하지 않는다.
- API 변경이 필요하면 Issue 또는 PR 코멘트로 사용자와 백엔드 담당자에게 확인한다.

### Codex AI Service

- 기본 작업 범위는 `ai-service/`이다.
- FastAPI 기반 AI 서비스 구현 초안을 작성한다.
- 초기 구현은 rule-based baseline을 우선한다.
- scoring rule, threshold, feature 사용 근거와 한계를 문서화한다.
- 모델 또는 스코어링 응답 구조 변경은 사용자 승인 후 진행한다.

## 디렉터리 역할

- `backend/`: Spring Boot API 서버, 도메인 로직, DB 연동, 백엔드 테스트
- `frontend/`: React/TypeScript UI, 화면 컴포넌트, API client, 프론트엔드 테스트
- `ai-service/`: FastAPI AI 서비스, rule-based baseline, scoring logic, AI 서비스 테스트
- `infra/`: Docker, 배포, 환경 구성, 운영 자동화
- `docs/`: API 명세, DB 설계, 아키텍처, 운영 문서
- `.github/`: Issue/PR 템플릿, GitHub Actions 워크플로우

## 보안 규칙

- API Key, Token, Password, DB 접속 정보, 개인식별정보를 코드나 문서에 직접 작성하지 않는다.
- 환경 변수는 `.env.example`처럼 예시 파일로만 문서화한다.
- 실제 `.env` 파일은 Git에 포함하지 않는다.
- 민감 정보는 로그, 응답, 테스트 데이터에 포함하지 않는다.
- 인증/인가, CORS, 외부 API 연동 변경은 사용자 승인 후 진행한다.

## 금지 사항

- `main` 브랜치 직접 커밋 금지
- 담당 범위 밖 디렉터리 임의 수정 금지
- API 명세 임의 변경 금지
- DB 설계 임의 확정 금지
- Controller 또는 Router에 비즈니스 로직 작성 금지
- 테스트 결과 또는 검증 내역 없이 완료 보고 금지
- 사용자 승인 없이 대규모 리팩터링, 의존성 교체, 구조 변경 금지
- 사용자 승인 없이 신규 프레임워크, 라이브러리, 상태 관리 도구, 외부 API 의존성을 추가하지 않는다.
- 의존성 추가가 필요한 경우 목적, 대안, 장단점, 포트폴리오 관점의 필요성을 먼저 설명한다.

## 문서 업데이트 규칙

- API 요청/응답, 엔드포인트, 상태 코드 변경 시 `docs/03-api`를 업데이트한다.
- DB 테이블, 컬럼, 인덱스, 관계 변경 시 `docs/04-database`를 업데이트한다.
- AI scoring rule 또는 모델 판단 기준 변경 시 관련 문서 또는 `ai-service/` 내부 문서를 업데이트한다.
- 문서 변경만 수행한 경우에도 변경 파일과 검증 결과를 보고한다.

## 작업 후 보고 형식

작업 완료 후 다음 형식으로 보고한다.

- 변경 파일
- 구현 또는 문서화한 내용
- 테스트와 검증 결과(실행한 테스트, 미실행 사유와 직접 확인할 항목 포함)
- 업무 정합성 영향
- 외부 서비스 장애 전파 가능성
- 관측 지표 영향
- AI 호출량과 비용 영향
- 사용자가 직접 결정하거나 검증해야 할 사항
