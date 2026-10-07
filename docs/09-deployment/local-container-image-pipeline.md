# 로컬 컨테이너 이미지 빌드와 GHCR 발행 잠금

GitHub Actions `Local Image Build`는 Backend와 AI Service의 기존 Dockerfile을 빌드한다. 각 빌드 컨텍스트는 해당 서비스 디렉터리(`backend/`, `ai-service/`)다. Issue #353의 GHCR 발행 job은 기본 잠금 상태이며, 이 문서의 사전 결정을 마친 뒤에만 명시적으로 활성화한다. 배포는 하지 않는다.

## 이미지 이름과 보존 범위

| 경로 | 이미지 참조 | 의미와 보존 범위 |
| --- | --- | --- |
| 기본 Compose (`infra/compose.yml`) | `finguardops-backend:local`, `finguardops-ai-service:local` | 개발자 로컬 Docker에 붙는 가변 태그다. Compose의 `build`는 각 서비스 디렉터리를 컨텍스트로 사용한다. |
| Keycloak Browser Gate `Prepare` | `finguardops-backend:e2e-<commit12>-<runId32>`, `finguardops-ai-service:e2e-<commit12>-<runId32>`, `finguardops-playwright-e2e:e2e-<commit12>-<runId32>` | 승인된 clean commit과 실행 ID에 연결된 로컬 준비 이미지다. Gate의 receipt와 정리 절차가 소유한다. 기존 `:local` 태그를 대체하지 않는다. |
| GitHub Actions의 관련 `main` push | `finguardops-backend:sha-<전체 GITHUB_SHA>`, `finguardops-ai-service:sha-<전체 GITHUB_SHA>` | 해당 job의 runner Docker에만 생성되는 로컬 태그다. 로그의 `commit_sha`, `image_tag`, `image_id`로 빌드 결과를 확인한다. |
| 잠금 해제 후 GHCR 발행 | `ghcr.io/ahnjisan/finguardops-backend:sha-<전체 GITHUB_SHA>`, `ghcr.io/ahnjisan/finguardops-ai-service:sha-<전체 GITHUB_SHA>` | 별도 publish runner가 각 이미지를 다시 빌드해 발행한다. 로그의 `registry_digest`와 `image_by_digest`가 원격 결과를 식별한다. |

`pull_request`에서는 두 이미지를 빌드하지만 태그를 붙이거나 registry에 올리지 않는다. 기본 잠금 상태의 `main` push에서도 GHCR 로그인·push를 하지 않는다. GitHub-hosted runner의 로컬 이미지는 실행 종료 후 내려받을 수 없다. Docker의 로컬 `image_id`는 발행 후의 registry digest와 다르다. GHCR의 SHA 태그도 이름만으로 변경 불가가 보장되지 않으므로, 소비 시에는 검증된 `이름@sha256:...` 참조를 사용한다.

## GHCR 발행 잠금과 OWNER 결정

Publish job은 `push` 이벤트의 `refs/heads/main`에서 **저장소 변수** `FINGUARDOPS_GHCR_PUBLISH`가 정확히 `enabled`인 경우에만 실행된다. 변수가 없거나 `Enabled`, `enable` 등 다른 값이면 skip한다. PR과 fork PR에서는 변수 값과 관계없이 skip한다. Issue #353의 PR을 병합할 때는 변수를 만들거나 `enabled`로 바꾸지 않는다.

잠금을 풀기 전에 OWNER는 `ghcr.io/ahnjisan/finguardops-backend`와 `ghcr.io/ahnjisan/finguardops-ai-service`의 기존 패키지 존재, visibility, `Ahnjisan/FinGuardOps` 연결, Actions 접근 권한과 계정의 비용·예산 설정을 확인해야 한다. 현재 조회 권한으로 이를 확인할 수 없다면 추정하지 않는다. GitHub 안내상 신규 GHCR 패키지의 기본 visibility는 private이며 저장소 연결만으로 public이 되지 않는다. **초기 private 발행 승인**과 공개 전환 여부도 별도로 결정해야 한다. 공개 전환은 패키지 설정에서 OWNER가 수행하며 공개 후 private으로 되돌릴 수 없다는 GitHub 안내를 확인한다. 이 결정과 확인이 끝나기 전에는 잠금을 풀 수 없다.

잠금 해제는 위 사항을 기록한 뒤 OWNER가 저장소 변수 값을 정확히 `enabled`로 설정하는 별도 조치다. 변수 변경만으로 과거 실행이 다시 시작되지는 않는다. 이후 관련 `main` push의 로컬 build job이 모두 성공하면 publish job이 별도 runner에서 서비스별 이미지를 다시 빌드한다. 발행 job만 `packages: write`를 받고 `GITHUB_TOKEN`으로 GHCR에 로그인한다. 비밀 build arg, PAT, `latest` 및 PR 태그는 사용하지 않는다.

## 태그 충돌과 부분 성공

각 publish job은 로그인 뒤 같은 `sha-<전체 SHA>` 태그를 조회한다. 태그가 이미 있으면 중단하고, 조회 오류가 명시적인 manifest 부재로 확인되지 않아도 중단한다. 조회와 push 사이의 외부 발행 경합까지 registry 수준에서 원자적으로 막는 것은 아니므로, 같은 SHA의 병렬 실행은 job concurrency로 직렬화하고 기존 digest를 확인하기 전 재발행하지 않는다.

두 서비스는 별도 job이다. 하나가 발행되고 다른 하나가 실패할 수 있으며, 이를 두 이미지가 모두 준비된 상태로 보고하지 않는다. 두 GHCR 태그의 존재·digest와 job 로그를 먼저 대조한다. 실패한 job만 재시도하되, 그 태그가 이미 생겼다면 자동 덮어쓰기를 피하기 위해 재시도도 중단되므로 OWNER가 실제 digest와 오류 단계를 확인해야 한다. 자동 삭제나 무조건 전체 재발행은 수행하지 않는다. 발행된 GHCR 버전은 runner 종료 후에도 남으며, 보존·삭제 기간은 별도 결정 전까지 자동화하지 않는다. 기본 Compose와 Keycloak Gate에서 GHCR 이미지를 소비하는 변경도 별도 범위다.

## 검사 결과 확인

PR 또는 관련 `main` push의 **Actions → Local Image Build**에서 Backend와 AI Service의 별도 build job을 확인한다. Workflow 파일 자체 또는 `backend/**`, `ai-service/**`가 바뀌면 두 이미지를 모두 다시 빌드한다. Dockerfile, `.dockerignore`, 잠금 파일도 이 경로에 포함된다. `main` push의 각 build job 끝에는 전체 커밋 SHA, runner 로컬 태그, 이미지 ID가 출력된다. 잠금 상태에서 publish job 두 개는 skip되어야 하고 GHCR 패키지는 생성되지 않아야 한다. 잠금 해제 후의 발행 결과는 각 publish job의 registry digest와 패키지 설정에서 별도로 확인한다.

이 workflow의 빌드 성공은 기존 Backend 단위·PostgreSQL 통합 테스트나 AI Service lint·format·pytest 통과를 뜻하지 않는다. 해당 결과는 기존 `Backend Tests`, `AI Service Tests` workflow에서 별도로 확인한다. Frontend Playwright 이미지는 Keycloak Browser Gate의 `Prepare` 절차가 관리하며 이 CI의 빌드 대상이 아니다.

PR의 Dockerfile은 실행 가능한 비신뢰 코드로 취급한다. PR과 로컬 build job은 표준 GitHub-hosted Ubuntu runner와 `contents: read`만 사용하고, checkout 자격 증명을 작업 트리에 저장하지 않는다. 빌드 컨텍스트는 저장소 루트의 `.git`을 포함하지 않는 서비스 디렉터리이며 각 `.dockerignore`는 `.env` 파일을 제외한다. PR과 로컬 build job에는 비밀 build arg, 로그인, 패키지 발행, artifact 업로드가 없다. Publish job의 GHCR 자격 증명은 `main` 전용 로그인 단계에서만 주입한다.
