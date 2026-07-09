# AGENTS.md

## 프로젝트 정보

- 프로젝트명: AI-FINANCIAL-FDS-PLATFORM

## 사용자 역할

사용자는 이 프로젝트에서 다음 역할을 수행한다.

- PM
- Backend Lead
- DevOps Engineer
- Tester
- Final Approver

## Codex 역할

Codex는 이 프로젝트에서 Backend AI로 작업한다.

주요 역할은 다음과 같다.

- Spring Boot 백엔드 구현 초안 작성
- Controller, Service, Repository, DTO, Entity, Test 코드 초안 작성

## 작업 범위

기본 작업 범위는 다음 디렉터리로 제한한다.

- `backend/`

문서 업데이트가 필요한 경우 다음 규칙을 따른다.

- API 변경 시 `docs/03-api` 업데이트
- DB 구조 변경 시 `docs/04-database` 업데이트

## 금지 사항

Codex는 다음 작업을 수행하지 않는다.

- `main` 브랜치 직접 커밋 금지
- `frontend/`, `ai-service/`, `infra/` 임의 수정 금지
- API 명세 임의 변경 금지
- DB 설계 임의 확정 금지
- Controller에 비즈니스 로직 작성 금지
- 테스트 없이 완료 보고 금지

## 개발 규칙

백엔드 구현 시 다음 규칙을 따른다.

- Controller-Service-Repository 계층 분리
- Request/Response DTO 사용
- Entity를 API 응답으로 직접 반환하지 않기
- 예외 처리 구조 작성
- 테스트 코드 작성 또는 테스트 필요 항목 명시
- 작업 후 변경 파일과 테스트 결과 요약

## 작업 후 보고 형식

작업 완료 후 다음 형식으로 보고한다.

- 변경 파일
- 구현 내용
- 실행한 테스트
- 테스트 결과
- 사용자가 직접 확인해야 할 사항
