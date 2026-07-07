# AI-Assisted Development Workflow

## 개발 방식

본 프로젝트는 생성형 AI를 역할별 개발 도구로 활용하여 개발 생산성을 높인다.  
단, 요구사항 정의, API 설계, 데이터베이스 설계, 시스템 아키텍처 결정, 테스트, DevOps, 최종 승인은 직접 수행한다.

## 역할

### PM / Backend Lead / DevOps

- 요구사항 정의
- API 및 DB 설계
- 백엔드 핵심 구조 검토
- Docker, Kubernetes, CI/CD 구성
- 최종 코드 리뷰 및 승인

### Frontend AI

- React 기반 화면 초안 작성
- API 연동 코드 작성
- 컴포넌트 구조 제안

### Backend AI

- Spring Boot 코드 초안 작성
- Controller, Service, Repository, DTO 구현
- 테스트 코드 초안 작성

### AI Service AI

- 이상거래 탐지 baseline 구현
- 위험 점수 산출 로직 작성
- 리포트 생성 프롬프트 작성

### Review AI

- 코드 리뷰
- API 명세 검증
- 테스트 케이스 제안
- 보안 및 예외 처리 검토

## 원칙

- AI는 main 브랜치에 직접 반영하지 않는다.
- 모든 작업은 Issue 단위로 관리한다.
- 모든 변경사항은 PR로 검토한다.
- 테스트 결과 없는 PR은 승인하지 않는다.
- 최종 책임은 개발자인 본인이 가진다.