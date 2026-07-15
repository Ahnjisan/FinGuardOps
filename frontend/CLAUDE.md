# frontend/CLAUDE.md

## 역할

Claude Code는 `frontend/` 디렉터리에서 Frontend AI로 작업한다.

주요 책임은 다음과 같다.

- React/TypeScript 기반 프론트엔드 구현 초안 작성
- 화면 컴포넌트, API client, 상태 관리 코드 작성
- 사용자 흐름에 맞는 UI 상태와 에러 상태 구현
- 프론트엔드 테스트 및 검증 항목 작성

## 작업 범위

- 기본 작업 범위는 `frontend/`로 제한한다.
- 백엔드 API 명세를 임의로 변경하지 않는다.
- API 변경이 필요하면 Issue 또는 PR 코멘트로 사용자와 백엔드 담당자에게 먼저 확인한다.
- `backend/`, `ai-service/`, `infra/`는 사용자 요청 없이 수정하지 않는다.

## React/TypeScript 개발 원칙

- TypeScript 타입을 명확히 정의한다.
- 컴포넌트와 API client를 분리한다.
- 화면 컴포넌트에 API 호출 세부 구현을 직접 작성하지 않는다.
- API 요청/응답 타입은 별도 타입 또는 schema로 관리한다.
- 재사용 가능한 UI는 컴포넌트로 분리한다.
- 비즈니스 흐름에 가까운 상태와 단순 UI 상태를 구분한다.
- loading, empty, error, success 상태를 명시적으로 처리한다.
- 폼 입력값은 validation 규칙을 명확히 둔다.

## API 연동 규칙

- `docs/03-api` 또는 백엔드가 제공한 명세를 기준으로 연동한다.
- 명세에 없는 필드, 엔드포인트, 상태 코드를 임의로 가정하지 않는다.
- 임시 mock 데이터는 실제 API 전환 지점을 명확히 표시한다.
- API client는 컴포넌트 외부에 분리하고, 호출 함수 이름은 도메인 동작을 드러내도록 작성한다.
- 인증 토큰, 사용자 식별자, 민감 정보는 안전하게 처리한다.

- 화면에 필요하다는 이유로 백엔드 필드를 임의로 생성하지 않는다.
- API가 아직 정의되지 않은 기능은 `planned` 또는 `mock` 상태로 구분한다.
- 구현되지 않은 Kafka, Kubernetes와 AWS 상태를 실제 데이터처럼 표시하지 않는다.

## FinGuardOps 사용자와 화면 범위

### FDS 분석 담당자 화면

- FDS 대시보드와 거래 모니터링
- 사건 대기열과 사건 상세
- Rule·ML 탐지 근거와 행동 타임라인
- AI 사건 리포트와 조사 메모
- 사건 상태와 최종 판정
- 감사 이력

### 플랫폼·클라우드 운영자 화면

- 서비스 Health와 배포 버전
- API 응답시간·오류율·처리량
- Rule 실행시간과 ML 추론시간
- DB Connection Pool과 Kafka Consumer Lag
- AI 호출량, 입력·출력 토큰과 모델별 비용
- 캐시 적중률과 fallback 비율
- 장애와 배포 이력

Kafka와 Kubernetes는 향후 도입 범위이며, 관련 API와 화면이 구현되기 전에는 `planned` 또는 `mock` 상태로만 표현한다.

## React 관리자 화면과 Grafana의 경계

### React 관리자 화면

- 거래와 사건 등 업무 상태
- 조사와 최종 판정
- 운영 정책과 Rule 정보
- AI 비용 요약
- fallback과 장애 이력
- 배포 이력과 서비스 상태 요약

### Grafana

- 시계열 기술 메트릭
- 서비스별 지연시간, 오류율과 처리량
- 인프라 상태
- DB Connection Pool과 Kafka Consumer Lag
- 상세 Observability 지표

React 관리자 화면에서 Grafana와 동일한 기술 대시보드를 전부 다시 구현하지 않는다.

## 테스트 및 검증 원칙

- 핵심 컴포넌트는 렌더링과 사용자 상호작용을 검증한다.
- API client 변경 시 요청 경로, method, payload, response mapping을 확인한다.
- 주요 화면은 loading, error, empty 상태를 확인한다.
- 테스트를 실행할 수 없는 경우, 미실행 사유와 수동 확인 항목을 명시한다.

## 금지 사항

- API 명세 임의 변경 금지
- 백엔드 응답 구조 임의 가정 금지
- 컴포넌트 내부에 API 호출 로직 과도하게 작성 금지
- 사용자 승인 없는 디자인 시스템 교체 금지
- 사용자 승인 없는 대규모 상태 관리 도구 도입 금지

## 작업 후 보고 형식

작업 완료 후 다음 항목을 보고한다.

- 변경 파일
- 구현 내용
- 실행한 테스트
- 테스트 결과
- 사용자가 직접 확인해야 할 사항
