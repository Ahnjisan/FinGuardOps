# ADR-002: 저장소명을 FinGuardOps로 변경

- 상태: Accepted
- 결정일: 2026-07-15
- 결정자: Project Owner
- 관련 ADR:
  - [ADR-001: FinGuardOps 제품 포지셔닝](./ADR-001-finguardops-positioning.md)
- 관련 문서:
  - `AGENTS.md`
  - `README.md`
  - `docs/00-overview/fds-service-scope.md`
  - `docs/00-overview/project-summary.md`

## 변경 배경

ADR-001에서 공식 제품명을 `FinGuardOps`로 정의했지만 당시 저장소명은 `AI-FINANCIAL-FDS-PLATFORM`으로 유지했다. 이후 GitHub 저장소명과 로컬 프로젝트 폴더명이 `FinGuardOps`로 변경되면서, 문서와 템플릿에 남은 기존 저장소 식별 정보가 현재 저장소 상태와 달라졌다.

제품명, GitHub 저장소명과 로컬 폴더명을 하나의 명칭으로 통일해 프로젝트 식별의 혼선을 줄이고 현재 저장소 상태를 문서에 정확히 반영할 필요가 있다.

## 결정

- 기존 저장소명 `AI-FINANCIAL-FDS-PLATFORM`을 새 저장소명 `FinGuardOps`로 변경한다.
- GitHub 저장소명과 로컬 프로젝트 폴더명을 모두 `FinGuardOps`로 통일한다.
- 공식 GitHub 저장소 URL은 `https://github.com/Ahnjisan/FinGuardOps`로 사용한다.
- 현재 프로젝트를 식별하는 문서와 템플릿에서는 `FinGuardOps`를 사용한다.
- ADR-001의 기존 저장소명은 당시 결정의 역사적 기록으로 유지하고, 이 ADR을 후속 결정으로 연결한다.

## 유지 범위

- 제품명 `FinGuardOps`
- 기존 금융 FDS 요구사항과 8개 이상거래 시나리오
- 플랫폼 운영 요구사항과 아키텍처 우선순위
- Java package `com.aifds.backend`
- 코드와 테스트
- API와 DB 설계
- 기존 Git 이력, Issue와 Pull Request

## 긍정적 결과

- 제품명, GitHub 저장소명과 로컬 폴더명이 일치한다.
- 문서와 템플릿에서 현재 프로젝트를 일관된 이름으로 식별할 수 있다.
- 저장소 URL과 로컬 경로를 안내할 때 발생할 수 있는 혼선을 줄인다.
- 제품 범위나 구현 구조를 바꾸지 않고 명칭만 정비한다.

## Trade-off

- 과거 Issue, Pull Request, 커밋 메시지와 외부 문서에는 기존 저장소명이 남을 수 있다.
- 기존 로컬 clone이나 북마크를 사용하는 사용자는 새 저장소명과 경로를 확인해야 한다.
- ADR-001의 당시 결정과 현재 저장소명이 다르므로 두 ADR을 함께 확인해야 변경 경위를 이해할 수 있다.

## 제외 범위

- 제품 범위, 금융 FDS 요구사항과 이상거래 시나리오 변경
- 아키텍처, 디렉터리 책임과 코드 구조 변경
- Java package, Java·Python·TypeScript 코드와 테스트 변경
- Gradle 설정, API 경로와 DB 설계 변경
- Docker 이미지명과 Kubernetes 리소스명 확정
- 신규 기능, 프레임워크, 라이브러리와 외부 API 의존성 추가
- 기존 Git 이력의 재작성
