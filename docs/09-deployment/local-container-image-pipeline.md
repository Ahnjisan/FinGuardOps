# 로컬 컨테이너 이미지 빌드 검증

Issue #351의 GitHub Actions `Local Image Build`는 Backend와 AI Service의 기존 Dockerfile을 빌드한다. 각 빌드 컨텍스트는 해당 서비스 디렉터리(`backend/`, `ai-service/`)다. 이 workflow는 이미지 발행이나 배포를 하지 않는다.

## 이미지 이름과 보존 범위

| 경로 | 이미지 참조 | 의미와 보존 범위 |
| --- | --- | --- |
| 기본 Compose (`infra/compose.yml`) | `finguardops-backend:local`, `finguardops-ai-service:local` | 개발자 로컬 Docker에 붙는 가변 태그다. Compose의 `build`는 각 서비스 디렉터리를 컨텍스트로 사용한다. |
| Keycloak Browser Gate `Prepare` | `finguardops-backend:e2e-<commit12>-<runId32>`, `finguardops-ai-service:e2e-<commit12>-<runId32>`, `finguardops-playwright-e2e:e2e-<commit12>-<runId32>` | 승인된 clean commit과 실행 ID에 연결된 로컬 준비 이미지다. Gate의 receipt와 정리 절차가 소유한다. 기존 `:local` 태그를 대체하지 않는다. |
| GitHub Actions의 관련 `main` push | `finguardops-backend:sha-<전체 GITHUB_SHA>`, `finguardops-ai-service:sha-<전체 GITHUB_SHA>` | 해당 job의 runner Docker에만 생성되는 로컬 태그다. 로그의 `commit_sha`, `image_tag`, `image_id`로 빌드 결과를 확인한다. |

`pull_request`에서는 두 이미지를 빌드하지만 태그를 붙이거나 registry에 올리지 않는다. `main` push에서도 GHCR 로그인·push를 하지 않는다. GitHub-hosted runner의 로컬 이미지는 실행 종료 후 내려받을 수 없으며, `sha-<전체 SHA>`라는 이름만으로 영구 보존되거나 원격에서 pull 가능한 이미지가 되지 않는다. Docker의 로컬 이미지 ID도 registry digest가 아니다.

## 검사 결과 확인

PR 또는 관련 `main` push의 **Actions → Local Image Build**에서 Backend와 AI Service의 별도 build job을 확인한다. Workflow 파일 자체 또는 `backend/**`, `ai-service/**`가 바뀌면 두 이미지를 모두 다시 빌드한다. Dockerfile, `.dockerignore`, 잠금 파일도 이 경로에 포함된다. `main` push의 각 job 끝에는 전체 커밋 SHA, runner 로컬 태그, 이미지 ID가 출력된다.

이 workflow의 빌드 성공은 기존 Backend 단위·PostgreSQL 통합 테스트나 AI Service lint·format·pytest 통과를 뜻하지 않는다. 해당 결과는 기존 `Backend Tests`, `AI Service Tests` workflow에서 별도로 확인한다. Frontend Playwright 이미지는 Keycloak Browser Gate의 `Prepare` 절차가 관리하며 이 CI의 빌드 대상이 아니다.

PR의 Dockerfile은 실행 가능한 비신뢰 코드로 취급한다. 이 workflow는 표준 GitHub-hosted Ubuntu runner와 `contents: read`만 사용하고, checkout 자격 증명을 작업 트리에 저장하지 않는다. 빌드 컨텍스트는 저장소 루트의 `.git`을 포함하지 않는 서비스 디렉터리이며 각 `.dockerignore`는 `.env` 파일을 제외한다. 비밀 build arg, 로그인, 패키지 발행, artifact 업로드는 사용하지 않는다.
