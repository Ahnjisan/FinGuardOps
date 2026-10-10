# Local Keycloak 인증 E2E Runbook

## 1. 목적과 경계

이 절차는 stock Keycloak 26.7.3으로 FinGuardOps local/dev SERVICE Client Credentials 발급,
Backend 인증·인가 호환성, 외부 USER password 주입과 USER credential desired state, 실제 USER
Authorization Code + PKCE 브라우저 로그인을 검증한다. production secret 보관·HA·외부 공개 배포
절차가 아니다. USER public client의 `use.refresh.tokens=false`는 realm import와 bootstrap reconcile
양쪽에 적용하며, Playwright는 실제 token response의 refresh token 부재와 Frontend의 합성
`refresh_token` fail-closed를 함께 검사한다. Issue #247에서 실제 USER RP-initiated logout과 exact
root post-logout callback을 같은 runner로 검증한다. capability로 보호되는 production route·action과
production Authorization Server는 아직 구현하지 않았다.

USER client의 default scope는 `finguardops-backend-audience`, `finguardops-user-claims` 두 개로
고정하고 optional scope는 Keycloak stock `profile` 하나만 연결한다. `profile`을 default로 옮기거나
다른 optional scope를 추가하면 verifier가 거부한다. `openid`는 OIDC authorize 요청의 필수 scope
값일 뿐 realm의 client scope 객체로 생성하거나 USER client에 연결하지 않는다. realm import와
bootstrap은 pinned Keycloak 26.7.3의 stock `profile` scope와 14개 mapper 계약을 desired state로
재현한다. SERVICE client의 scope·audience·role과 refresh-token 부재 계약은 이 변경의 영향을 받지
않는다.
Local E2E USER에는 `.invalid` 합성 이메일과 `Local Analyst` 이름을 적용해 stock user-profile
required action 없이 로그인한다. 실제 사용자 개인정보가 아니다.

Keycloak overlay와 Local JWT fixture overlay는 별개다. 한 명령에 둘을 결합하거나 한 Backend가
두 issuer를 동시에 신뢰하는 구성은 지원하지 않는다. 공식 절차는 stack 생성 전 static
verifier로 동시 사용과 issuer/JWK 혼합을 거부한다. Compose 문법 자체가 verifier를 우회한 모든
raw 명령을 막는 것은 아니다.

## 2. 사전 요구사항

- Docker Desktop과 Compose v2
- Windows Git Bash의 Bash와 OpenSSL
- container에 bind mount하는 모든 `.sh`가 LF로 checkout된 상태
- host Python 3.12(사전 static verifier와 unit test용)
- Node.js 24.15 이상 25 미만, npm 11.6.2, Playwright Chromium
- `infra/.env`의 기존 Compose 필수 변수. 실제 값은 Git에 추가하지 않는다.
- host의 `127.0.0.1:8443`이 비어 있어야 한다.

PowerShell이 아니라 repository root의 Git Bash에서 생성기를 실행한다.

```bash
if grep -q $'\r' infra/keycloak/start-keycloak.sh; then
  printf 'blocked: start-keycloak.sh must use LF\n' >&2
  exit 1
fi
```

`.gitattributes`의 `*.sh text eol=lf`는 tracked shell script의 working-tree LF를 고정한다. 실제 byte에
CRLF나 bare CR이 있으면 Keycloak wrapper가 실패할 수 있으므로 runtime 전에 모든 tracked `.sh`를
검사한다. 임시 LF 복사본이나 임시 Compose overlay의 성공을 공식 결과로 기록하지 않는다.

```bash
bash infra/keycloak/setup-local-secrets.sh
bash infra/keycloak/setup-local-tls.sh
```

The directory/file fallback can be `UNAVAILABLE` only when the source and destination inspections both
succeed and both are empty (or, for a file, the bytes match exactly). Native probe stdout/stderr is not
forwarded; failures use fixed identities. Unexpected inspection, command, or cleanup results fail the test.
Windows records exactly five deferred symlink cases, while Linux/WSL requires five actual symlinks and
records zero deferred or skipped cases.

Certificate subject의 단일 source는 TLS script가 생성하는 OpenSSL config이며 `[subject]`의
`CN=localhost`로 고정한다. Windows Git Bash는 native OpenSSL에 전달하는 slash-leading subject
argument를 Windows path로 변환할 수 있으므로 별도 slash-leading `-subj` argument를 사용하지 않는다.
공식 실행 명령은 위와 같이 변경 없이 `bash infra/keycloak/setup-local-tls.sh`이다.
TLS shell test의 Windows Git Bash directory/file probe는 선조건, `ln -s` 종료값, object type,
`test -L`, reparse 여부와 cleanup·residue를 확인한다. `ln -s`가 0이지만 symbolic link와 reparse가
아니고, directory는 source/destination이 모두 비어 있거나 file은 byte가 정확히 같은 검증된
copy-style fallback일 때만 capability unavailable로 판정해 symlink 전용 fixture 5건을 deferred한다.
선조건·명령·type·reparse·fallback 내용·cleanup에서 예상 밖이거나 모순된 결과는 test failure이며
deferred로 바꾸지 않는다. Junction fixture와 나머지 검증은 계속 실행한다. Linux/WSL에서는
directory/file probe가 모두 실제 symbolic link를 만들어야 하며 deferred/skip 수는 0이어야 한다.

TLS script는 certificate/key 중 하나라도 있으면 overwrite하지 않고 실패한다. Secret script는 fresh
상태에서 admin·SERVICE 3개와 USER password를 만들며, 기존 3개가 모두 유효하고 USER password만 없을
때에는 기존 값을 유지한 채 USER password 하나만 추가한다. 그 밖의 partial 상태와 4개가 이미 있는
상태는 실패한다. 생성물은 `infra/keycloak/.local/` 아래에만 있고 전체 디렉터리가 ignore된다. Windows NTFS/Docker
Desktop bind mount에서는 POSIX mode가 완전히 보장되지 않을 수 있다. 따라서 mode 표기만
신뢰하지 않고 container UID의 실읽기, 내용 계약과 read-only mount를 runtime에서 확인한다.
두 생성기는 script의 physical directory와 exact `.local/secrets`·`.local/tls` child를 검증하고
`.local`, output directory와 artifact symlink를 거부한다. Git Bash `realpath`가 Windows junction을
physical target으로 해석하는 환경에서는 approved root 밖 junction도 거부한다. 동시에 경로를
교체하는 악의적인 local operator에 대한 완전한 TOCTOU 방어는 보장하지 않는다.

## 3. 인증서 신뢰

public discovery는 `https://localhost:8443`을 사용하며 verifier는
`ssl.create_default_context(cafile="/run/secrets/keycloak_tls_certificate")`로 생성 certificate만
명시적으로 신뢰한다. 인증서는 RSA 3072 이상, SHA-256 이상, 최대 30일의 self-signed leaf이며 extension은
`basicConstraints=critical,CA:FALSE`, `keyUsage=critical,digitalSignature,keyEncipherment`,
`extendedKeyUsage=serverAuth`, `subjectAltName=DNS:localhost`와 정확히 일치해야 한다. 생성기는 extension
전체, 자체서명, certificate/private-key 일치를 fail-closed로 검사한다.

### 3.1 격리 Chromium NSS 신뢰와 브라우저 E2E

브라우저는 이 머신의 브라우저가 아니다. Chromium은 `package.json`이 의존하는 Playwright 버전과
정확히 일치하고 immutable digest로 고정한 공식 Playwright Linux image에서 빌드한 전용 image 안에서
실행되며, 그 안의 실행별 NSS database에만 local `localhost` leaf가 들어간다. runner는 Windows
`CurrentUser` · `LocalMachine` 인증서 저장소를 읽지도 열지도 변경하지도 않고, 신뢰 확인창을 자동
클릭하지 않으며, `ignoreHTTPSErrors` · `--ignore-certificate-errors` · SPKI allowlist · hostname
우회를 쓰지 않는다.

PowerShell runner가 `Prepare`, `Service`, `Run`, `Validate`, `Cleanup`과 receipt를 소유하는 유일한
lifecycle owner다. 모든 mode는 첫 receipt I/O 전에 같은 global mutex를 fail-fast로 획득한다.
Python verifier는 `Service`의 child process이며 Git, receipt, mutex, Docker build와 cleanup에 접근하지
않는다. 공식 순서는 `Prepare → Service → Run`이고, 복구가 필요하면 `Cleanup`을 실행한다.

repository root의 PowerShell에서 dependency를 먼저 설치한 뒤 준비 단계를 실행한다. host Chromium은
더 이상 필요하지 않다. 실제 credential은 명령 인자나 환경 변수로 전달하지 않는다.

```powershell
Set-Location frontend
npm ci
Set-Location ..
.\frontend\scripts\run-keycloak-e2e.ps1 -Mode Prepare
```

Prepare는 clean committed worktree의 full commit SHA, tree SHA와 canonical repository identity를
확정하고 build 전후에 이 값과 clean 상태가 변하지 않았는지 검사한다. Git archive는 사용하지 않고
normal working tree를 build context로 사용한다. Backend, AI Service와 browser에는 공통
`e2e-<commit12>-<runId32>` suffix를 가진 unique tag를 사용한다. 기존
`finguardops-backend:local`, `finguardops-ai-service:local`, `finguardops-playwright-e2e:local`은
build, tag, remove 대상이 아니다. 고정 digest의 Playwright base image를 pull한 뒤
`frontend/Dockerfile.playwright-e2e`로 unique browser image를 빌드한다.
`apt`는 이 image build 안에서만 실행되어 exact version `libnss3-tools=2:3.98-1ubuntu0.2`를 설치하고
같은 layer에서 package manager cache를 제거한다. Playwright version, `certutil` package version과
base image digest는 OCI label로 image에 고정되며 browser는 image의 non-root `pwuser`로 실행된다.

준비가 끝나면 SERVICE ingestion 경계를 먼저 실행하고 browser E2E를 실행한다.

```powershell
.\frontend\scripts\run-keycloak-e2e.ps1 -Mode Service
.\frontend\scripts\run-keycloak-e2e.ps1 -Mode Run
```

Service와 Run은 registry에 접근하지 않는다. Compose는 `--no-build --pull never`, browser는
`--pull never`로만 시작한다. raw overlay Compose와 raw `--build`는 공식 경로가 아니다. 각 mode는
현재 exact tag를 inspect해 image ID와 다섯 ownership label(commit, tree, run ID, repository ID,
image role)을 검증하고, 생성된 container의 `.Config.Image`와 `.Image`도 비교한다.

receipt JSON은 immutable canonical compact UTF-8/no-BOM/LF 파일이며
`schemaVersion`, `runId`, `repositoryId`, `commitSha`, `treeSha` 다섯 key만 이 순서로 가진다.
`e2e-image-manifest.json`은 Prepared, `e2e-image-cleanup-required.json`은 Recovery 상태다. Prepare는
Recovery를 CreateNew로 만들고 성공 시 Prepared로 atomic rename한다. Service는 실행 동안
Prepared를 Recovery로 이동하고 성공 시 resource cleanup 뒤 Prepared로 복원한다. Run은 Recovery로
이동한 뒤 성공·실패 모두 resource, owned image와 receipt를 정리한다. 두 receipt가 동시에 있거나
schema·경로·reparse 검증이 실패하면 fail-closed한다.

browser image는 label로 판정하지 않는다. label은 image를 만드는 쪽이 자유롭게 쓸 수 있으므로 같은
label을 그대로 복제한 임의 image가 통과해서는 안 된다. Run은 label을 조기 중단용으로만 읽고, 실제
판정은 image가 스스로 바꿀 수 없는 것들에 둔다. 먼저 local 검사만으로 고정 digest의 Playwright base가
local에 있고 그 digest를 실제로 들고 있는지, base와 준비 image가 모두 `linux/amd64`이고 variant가
없는지, base의 RootFS layer 목록이 준비 image RootFS layer 목록의 exact ordered prefix인지,
`Dockerfile.playwright-e2e`의 구조대로 layer가 정확히 하나만 더해졌는지(`RUN` 하나, `LABEL`과 `USER`는
layer를 만들지 않는다), `Config.User`가 exact `pwuser`인지를 확인한다.

이어서 같은 image를 network 없는(`--network none`) 일회용 container로 한 번 실행해 runtime을 확인한다.
그 container는 `--read-only`, `--cap-drop ALL`, `--security-opt no-new-privileges`로 시작하고
`frontend/scripts`와 설치된 `playwright-core`만 read-only로, 작업용 tmpfs 하나만 rw로 받는다.
container 안에서 확인하는 것은 `dpkg-query`가 답하는 `libnss3-tools`·`libnss3` exact version,
`certutil`의 위치·소속 package와 실제 NSS database 생성·조회 성공, exact Node version, mount된
`playwright-core`의 exact version과 그 `browsers.json`이 요구하는 browser revision 집합이 image의
`/ms-playwright`와 정확히 같은지, Chromium과 headless shell 실행 파일의 존재·실행 가능·exact build,
그리고 `id`가 답하는 실제 UID·GID·계정 이름이다. 같은 container가 자신의 confinement도 확인한다.
`/proc/self/status`의 `NoNewPrivs`와 네 capability set, `/` 가 read-only라는 것과 쓰기 시도 실패,
loopback 외 network interface 0, 그리고 이 image가 필요로 하는 세 mount가 실제로 그 자리에 그 option으로
있다는 것이다. 그 밖의 target은 container runtime이 스스로 만드는 mount 이름 집합에 exact하게 속해야
하며, `/proc`·`/sys`·`/dev` 하위를 prefix로 포괄 허용하는 규칙은 없다. 검사 실패는 어떤 관측값도
반사하지 않는 고정 문장 하나로 끝난다.

다만 mount가 사용자 지정인지는 container 안에서 판정할 수 없는 질문이다. `--tmpfs /dev/shm/x`는
daemon이 직접 mount하는 `/dev/shm`과, `/proc` 아래의 bind는 runc가 만드는 kernel 가상 mount와
구분되지 않기 때문이다. 그래서 이 runner가 만드는 모든 container는 세 단계로 시작한다. 먼저
`docker create`로 멈춰서 만들고, `docker container inspect`로 daemon의 기록을 읽어 승인된 구성과
exact하게 비교한 뒤, 검사한 바로 그 exact container ID만 `docker start`한다. 비교 대상은
`HostConfig.NetworkMode`와 attach된 network, `ReadonlyRootfs`, `Privileged`, `CapAdd`·`CapDrop`,
`SecurityOpt`, device·device request·`VolumesFrom`, `Binds`와 `Mounts`의 physical source·
destination·type·read-only·propagation, `Tmpfs`의 target과 option, 그리고 published port 전체다.
검증 container는 network mode exact `none`, `ReadonlyRootfs` true, `CapAdd` 없음, `CapDrop` exact
`ALL`, `SecurityOpt` exact no-new-privileges이고, runtime 검증 container의 `Tmpfs`는
`/finguardops/work` 하나뿐이며 option까지 exact하게 같다. browser container도 같은 방식으로
certificate·scripts·playwright-core mount와 network, 그리고 host `127.0.0.1:14250` publish까지
exact하게 비교한 뒤 시작한다. 승인 목록에 없는 것은 이름되지 않았다는 이유로 거부되므로, Docker
socket·repository working tree·credential·private key mount는 target을 어디로 잡든 container가
시작되기 전에 고정 오류로 끝난다. bind source는 repository 안의 link 없는 physical 경로로 먼저
해석하므로 junction이나 prefix만 같은 경로도 다른 값으로 거부된다.

하나라도 없거나 어긋나면 registry metadata·auth 요청 없이 즉시 고정 오류로 끝나며 `-Mode Prepare`를
안내한다. Compose는 `up -d --no-build --pull never`로만 시작하고 browser container도
`--pull never`로 만든다. 대상은 tag가 아니라 시작 전에 확인한 exact image
ID이며, 승인 시점과 시작 직후에 container가 실제로 그 image로 돌고 있는지를
`docker container inspect`로 다시 확인한다.
따라서 검사와 실행 사이에 tag가 다른 image로 옮겨져도 실행되는 것은 검사한 image다. Run 단계의 build,
pull, `apt`와 npm/npx 다운로드는 0회다.

Run 경로에는 npm과 npx 실행이 아예 없다. npm script는 shell이 해석하는 command line을 한 겹 더
얹고 lifecycle hook을 돌리며, npx는 찾지 못한 binary를 registry에서 가져오는 것이 정상 동작이므로
"아무것도 가져오지 않는다"는 계약과 양립하지 않는다. 대신 runner가 설치된
`node_modules/@playwright/test/cli.js`를 현재 Node executable에 argument vector로 넘겨 직접 실행하고,
Playwright의 web server는 `node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort`를
`frontend`를 cwd로 삼아 실행한다. 두 경로 모두 package 이름이나 script 이름이 아니라 설치된 파일
경로이므로 resolver의 registry fallback 자체가 존재하지 않는다. entry point가 없거나 설치된 package
version이 `@playwright/test`·`playwright-core` `1.62.1`, `vite` `8.2.2`와 다르면 container를 하나도
만들기 전에 고정 오류로 끝난다. runner가 넘기는 절대 경로는 shell을 거치지 않는 argument vector의
원소이고, web server command line은 cwd 상대 경로만 쓰는 ASCII token뿐이므로 공백이나 한글이 들어간
Windows 절대 경로도 그대로 안전하다. `package.json`과 lockfile은 변경하지 않는다.

인증서 검증은 두 단계다. host PowerShell은 파일만 읽어 repository 안의 physical 경로,
`CA:FALSE`, exact Key Usage, `serverAuth` 단일 EKU, exact `DNS:localhost`, self-issued,
RSA 3072 이상, SHA-256 이상, 현재 유효성과 최대 30일을 검사한다. 이어서 network 없는
(`--network none`) 일회용 container가 같은 image로 `localhost.crt`와 `localhost.key`를
read-only mount 받아 self-signature 검증과 private-key 일치까지 확인한다. private key는 이
검증 container에만 mount하며 브라우저가 있는 container에는 mount하지 않는다.

브라우저 container는 `localhost.crt`만 read-only로 받고, `certutil -A -t "P,,"`로 실행별 NSS
database에 등록한다. `P,,`는 SSL peer로 신뢰되는 leaf라는 뜻이며 CA 권한도, 다른 이름을 보증할
권한도 주지 않는다. 등록 직전에 같은 container 안에서 인증서를 다시 검증하므로 신뢰되는 바이트는
방금 검사한 바이트다.

container 안의 Chromium은 `http://localhost:5173`과 `https://localhost:8443`에 그대로 접속한다.
이 문자열이 OIDC issuer이자 allowlist된 redirect·post-logout URI이기 때문이다. 중계 대상은 code에
고정된 5173·8443 두 개뿐이며 relay는 인자를 받지 않는다. CLI argument·환경 변수·임의 host/port로
목록을 넓힐 수 없고, 인자가 하나라도 있으면 listener를 만들기 전에 거부한다. container loopback의
5173·8443만 host loopback으로 TCP 그대로 중계하며, TLS를 종료하지도 바이트를 바꾸지도 않으므로
handshake와 hostname 검증은 Chromium과 Keycloak 사이에서 end-to-end로 성립한다. Backend
management 8081, Keycloak HTTP 8082와 management 9000은 중계 목록에 없으므로 비공개 경계가 그대로
유지된다. Backend `8080` 트래픽은 기존대로 Playwright가 가로채 전용 Compose container 안에서
중계한다.

container는 privileged mode, 추가 capability, Docker socket mount를 사용하지 않으며, 종료 경로의
`finally`가 이번 실행이 만든 exact container ID만 제거한다. 실행별 임시 HOME 아래의 NSS database,
browser profile과 artifacts는 container와 함께 사라진다. `package.json`과 lockfile dependency는 변경하지 않으며, `certutil`은 실행 중에 설치하지
않고 Prepare가 빌드한 image에 이미 들어 있다. 공용 image cache는 자동 삭제하지 않고 잔존 실패
판정에서도 제외한다.

비정상 종료로 전용 resource가 남으면 다음 명령이 Prepared 또는 Recovery receipt 하나의 ownership을
재검증한 뒤 exact Compose resource와 세 unique tag만 정리한다. Image 삭제는
`docker image rm --no-prune <exact-reference>` 형태만 허용한다. moved tag, label/ID 불일치와 사용 중
image는 삭제하지 않으며 receipt를 유지한다. `.local` certificate·private key·password와 기존
`*:local` image는 삭제하지 않는다.

```powershell
.\frontend\scripts\run-keycloak-e2e.ps1 -Mode Cleanup
```

이 개정 이전 revision을 실행한 적이 있다면 그 revision의 `-Mode Cleanup`을 한 번 실행해
`CurrentUser\Root`에 남았을 수 있는 exact 인증서와 marker를 제거한다. 현재 runner는 저장소를
열지 않으므로 그 정리를 대신 수행하지 않는다.

runner가 실행하는 test 명령은 설치된 Playwright CLI 하나로 고정된다. Playwright는 Chromium,
`workers=1`, `retries=0`, strict TLS를 사용하고 trace·screenshot·video·HTML report를 만들지 않는다.
`FINGUARDOPS_E2E_BROWSER_WS`가 격리 browser server를 가리키지 않으면 config가 즉시 실패하므로 이
suite를 host 브라우저로 실행할 방법은 없다. output directory는 OS 임시 경로에 생성하고 종료 시
삭제한다. 실제 USER password는 ignored file에서 process memory로만 읽는다.

정상 시나리오는 Web Crypto로 매 로그인마다 생성한 32바이트 이상의 padding 없는 base64url nonce,
authorize URL과 저장 transaction의 동일 nonce, exact `openid profile` scope와 stock `profile`의
`preferred_username` claim, exact callback URI, PKCE S256 verifier/challenge, callback URL의
code 제거, refresh token 부재와 session 게시를 확인한다. access/ID token의 동일 canonical UUID v4
`sub`, `principal_type=USER`, 중복 없는 동일 `FDS_ANALYST` role 집합과 access token exact singleton
audience를 검사한다. 실제 USER access token으로 Backend case 목록 200, credential 없음·손상 token 401,
analyst resolution 403과 403 이후 session 유지를 검증하며 write 성공과 retry는 0회다. 별도 시나리오는
합성 refresh token, state, 저장 nonce 삭제·blank·불일치, ID token nonce 누락·불일치, PKCE와
callback 재사용을 각각 거부하고 session·subscriber·Backend 요청·refresh grant·silent renew가
생기지 않는지 확인한다. 저장 nonce 누락·blank와 소비된 callback은 token endpoint 호출 전에
거부한다. ID token nonce 누락·불일치는 ID token을 받기 위한 authorization-code 교환 1회 뒤 즉시
거부하며 재교환·session·subscriber·Backend 요청은 0회다. password·state·nonce·code·token·Provider
오류 원문은 DOM·console·storage·report·artifact에 기록하지 않는다.

logout 시나리오는 실제 USER 로그인 뒤 `Sign out`을 눌러 end-session 요청이 정확히 1회, 설정된
issuer의 exact `/protocol/openid-connect/logout`으로 나가며 parameter가 정확히
`id_token_hint`·`post_logout_redirect_uri`·`state` 세 개인지 확인한다. `post_logout_redirect_uri`는
exact `http://localhost:5173/`이고 `id_token_hint`는 token response의 ID token과 동일해야 한다.
post-logout callback은 exact application root에 `state` 하나만 달고 도착하며, 처리 후 주소창은
exact `http://localhost:5173/`이고 `Sign in` 버튼이 다시 나타나며 `finguardops.oidc.` prefix
storage는 0개다. logout 중 Backend 요청, refresh grant와 silent renew는 0회다. 소비된 callback URL을
다시 열면 고정 sign-out 오류만 표시되고 session 게시·storage 복원·추가 end-session 요청·grant 교환은
0회다. 변조된 root 응답 5종(provider error, 주입된 `code`, 중복 `state`, blank `state`, `;`가 섞인
`state`)은 library를 호출하지 않고 거부하며 주소창을 정리한다. 마지막으로 다시 `Sign in`을 누르면
이전 SSO session이 재사용되지 않고 Keycloak 로그인 화면이 나타나야 한다. password, access/ID token과
logout state 원문은 DOM·storage·console에 남지 않는다.

## 4. 공식 사전검증

Prepared receipt가 있을 때만 다음 명령을 사용한다. 이 mode는 receipt와 source identity, exact image
reference·ID·label, browser runtime과 certificate를 읽기 전용으로 검증하고 lifecycle 상태를 바꾸지
않는다.

```powershell
.\frontend\scripts\run-keycloak-e2e.ps1 -Mode Validate
```

Required image/label 환경변수를 runner가 검증해 설정하므로 overlay를 raw Compose 명령에 직접 전달하는
경로는 공식 사전검증이 아니다. 특히 변수를 수동으로 구성하거나 `--build`를 추가해 runner를 우회하지
않는다.

## 5. Fresh start와 완료 판정

`Service`가 Python verifier child를 실행해 static, fresh, existing-volume restart, host TLS와 단계별
ingestion을 수행한다. Python에는 PowerShell이 검증한 Backend/AI image reference, commit SHA,
tree SHA, run ID와 repository ID만 환경변수로 전달한다. Python은 `--no-build --pull never`로만
Compose를 시작하고 cleanup하지 않는다. PowerShell이 child 성공 후 container image identity를
검사하고 resource를 정리한 뒤 Recovery receipt를 Prepared로 되돌린다.

```powershell
.\frontend\scripts\run-keycloak-e2e.ps1 -Mode Service
```

Compose `--wait` 출력만으로 성공을 판정하지 않는다. 최종 준비 완료 조건은 `keycloak-verify`
exit code 0과 고정 완료 메시지다. verifier는 readiness, HTTPS discovery, internal JWKS, RS256
key/kid, 두 SERVICE token과 raw string audience, UUID v4 subject, exact role,
cross-secret 거부, Backend `400 VALIDATION_ERROR`와 반대 endpoint `403 ACCESS_DENIED`를 검사한다.
잘못된 업무 body는 deserialization 단계에서 끝나므로 거래·행동 record를 만들지 않는다.

USER password secret은 bootstrap에만 `/run/secrets/user_password`로 read-only mount된다. Bootstrap은
password를 조회·출력·report하지 않고 매 실행마다 외부 값을 `temporary=false`로 exact reset한 뒤
credential metadata가 `password` 1개인지 확인한다. USER UUID와 단일 `FDS_ANALYST` role은 유지한다.
Keycloak server, SERVICE client와 verifier에는 USER password를 mount하지 않는다.

Keycloak service의 Compose `command`는 image 기본 CMD를 제거하는 explicit empty list다. Wrapper는
외부 argument가 하나라도 있으면 secret을 읽거나 Keycloak을 실행하기 전에 고정
`ARGUMENTS_NOT_ALLOWED`로 종료하며, 정상 PID 1 command는 `kc.sh start --import-realm`만 포함한다.

Bootstrap은 Admin API의 두 service-account UUID v4와 자신이 메모리에서 발급한 token `sub`를
원문 exact 비교하고 어떤 ID/token/report도 verifier에 전달하지 않는다. Container verifier와
별도로 host mode는 명시한 `CA:FALSE` local leaf, TLS hostname, redirect 없음, HTTP 200, exact issuer와
public HTTPS `jwks_uri`를 검사하고 host 8082·9000 TCP 연결이 성립하면 실패한다. `--insecure`,
HTTP fallback 또는 container 내부 discovery 성공으로 이 검사를 대체하지 않는다.

## 6. Existing-volume 재실행

Existing-volume restart는 `Service` child 내부의 검증 단계다. Operator가 같은 project에 raw Compose를
반복 실행하지 않는다. 이 단계도 `--no-build --pull never`를 유지하며 완료 후 PowerShell owner가
전용 volume을 포함한 exact project resource를 정리한다.

Keycloak log의 `Import skipped`, bootstrap 완료와 verifier 완료를 함께 확인한다. reconcile은 exact
name/clientId로 재조회하고 role/client/scope/mapper duplicate를 거부하며 USER client에 stock
`profile` optional scope만 재적용하고 외부 SERVICE secret과 USER password를 같은 값으로 다시
적용한다. stock `profile` scope가 없으면 pinned 정의로 생성·reconcile하지만 `openid` client scope
객체는 생성하지 않는다. USER password는 응답으로 조회하지 않고 credential metadata만 password
1개인지 확인한다.

각 verifier 실행은 각 token 응답을 완전히 받은 뒤 그 token 전용 정수초 현재 시각을 캡처한다.
이전 구현처럼 발급 전에 잡은 stale `now`를 두 token에 재사용하면 초 경계에서 뒤에 발급된 token의
`iat`가 미래로 보일 수 있다. retry·sleep·clock-skew 확장 없이 `iat <= now < exp`, 선택적
`nbf <= now`, `exp - iat <= 900`을 검사한다.

Keycloak의 configured `accessTokenLifespan`은 **899초**이고 verifier의 실제 JWT lifetime 상한은
**900초**다. 두 값의 1초 차이는 operational margin이며 근거는 pinned Keycloak 26.7.3의 token 생성
경로다. `iat`는 `JsonWebToken.issuedNow()`의 `Time.currentTime()`에서, `exp`는
`TokenManager.getTokenExpiration()`의 `Time.currentTimeMillis()`에서 각각 **별개의 clock read**로
계산되고 양쪽 모두 초 단위로 floor되므로, 발급된 token은 `exp - iat = configured + D`가 된다. 여기서
`D`는 두 read 사이에 넘어간 정수초 경계의 개수다. 따라서 configured 899는 통상적인 `D=1` 경계 교차를
흡수하며, `D <= 1`에서는 900초를 넘지 않는다.

| D | 발급 lifetime | verifier 판정 |
| --- | --- | --- |
| 0 | 899초 | 수락 |
| 1 | 900초 | 수락 |
| 2 이상 | 901초 이상 | `TOKEN_TIME_LIFETIME_INVALID`로 거부 |

Keycloak 구현은 두 clock read 사이의 최대 실행 시간을 보장하지 않으므로 899가 모든 `D`에서 성공한다고
주장하지 않는다. `D >= 2`에 해당하는 비정상 장시간 지연은 계속 fail-closed로 거부한다. retry, sleep,
clock-skew allowance는 추가하지 않으며 verifier의 900초 상한도 완화하지 않는다.
`STATIC_REALM_CONTRACT`의 유효 범위 `1..900`도 그대로이며 899는 그 범위 안에 있다. authoritative
runtime realm 값을 admin API로 read-back 검증하는 일은 이 scope에 포함되지 않는 후속 hardening이다.

Token 시간 계약의 두 경계는 각각 독립된 fixed identity를 가진다. `exp <= iat`는
`TOKEN_TIME_ORDER_INVALID`, `exp - iat > 900`은 `TOKEN_TIME_LIFETIME_INVALID`이며 한 code가 두
predicate를 겸하지 않는다. 정확히 900초는 허용하고 901초부터 거부한다. verifier의 실제 JWT lifetime
상한은 900초이며 이 상한은 완화되지 않는다. 두 판정 모두 실제
`iat`·`exp` 값, 그 차이, JWT 또는 그 어떤 claim 원문도 출력하지 않고 고정 identity만 기록한다.

2026-09-05 correction 실행은 fresh/existing volume, host 검증과 existing verifier 5회를 모두
첫 시도에 통과했고 시간 오류는 재발하지 않았다.

## 7. 안전한 종료와 제한된 clean reset

중단 또는 실패 뒤에는 `-Mode Cleanup`만 사용한다. Prepared와 Recovery receipt 중 정확히 하나가
있어야 하며 둘 다 있으면 fail-closed한다. Cleanup은 ownership을 재검증한 exact browser container,
Compose project, volume과 unique tag만 제거한다. Receipt 삭제도 cleanup 단계이며 실패하면 cleanup
실패로 처리한다. 기존 primary failure가 있으면 그 오류를 유지하고 고정된 cleanup 진단만 부가한다.
`--force` image 삭제, image ID 직접 삭제, glob/prefix 삭제와 모든 prune은 금지한다.

## 8. Rotation과 local artifact 제거

USER password만 rotation하려면 stack을 먼저 중지하고 exact `user-password`만 안전하게 폐기한 뒤
secret script를 다시 실행한다. Admin·SERVICE secret rotation은 4개 secret 전체를 의도적으로 폐기한
fresh 상태에서만 수행한다. TLS rotation은 exact certificate/key 두 파일을 함께 폐기한다. 새 credential과
private key를 terminal에 출력하지 말고 Keycloak과 helper를 재생성해 bootstrap/verifier를 다시
통과시킨다. 이전 SERVICE secret의 교차 사용은 실패해야 한다.

검증이 끝나면 필요한 증거를 비민감 결과로 기록한 뒤 ignored `infra/keycloak/.local/`의 credential과
TLS artifact를 별도 승인 절차로 삭제할 수 있다. runner는 Windows trust store를 사용하지 않는다.
실행 중인 container와 receipt가 해당 파일을 참조하지 않는지 먼저 확인한다.

## 9. 신뢰 경계와 troubleshooting

- Docker 관리자 권한 보유자는 mount source와 Keycloak PID 1 child environment를 볼 수 있다.
  bootstrap admin secret은 정적 `Config.Env`나 argv에 없지만 Keycloak process environment에는
  일시적으로 존재한다. 이는 local operator 신뢰 경계이며 production secret 보관 방식이 아니다.
- bootstrap은 public discovery가 아니라 management readiness 후 실행한다. Bootstrap과 verifier는
  파일·state·UUID·token·JSON report를 공유하지 않고 Compose 성공 dependency만 사용한다.
- `user_password`는 bootstrap에만 mount하며 Keycloak server와 verifier의 config/inspect에는 없어야 한다.
  실제 password, admin·SERVICE secret과 private key 원문은 config, inspect, bootstrap/verifier/server
  log 어디에도 나타나면 안 된다.
- bootstrap 실패 시 verifier는 시작하지 않는다. `ADMIN_HTTP_*`, `*_AMBIGUOUS` 같은 고정 코드와
  Keycloak의 비민감 server log로 원인을 좁힌다. HTTP body나 token을 출력하지 않는다.
- `TLS_CERTIFICATE_INVALID`면 SAN `DNS:localhost`, certificate 유효기간과 trust mount를 확인한다.
- `HTTP_TRANSPORT_FAILED` 또는 Backend 503이면 8082 loopback JWK와 Keycloak health를 확인한다.
- 401은 signature/issuer/audience/subject/claim 실패, 403은 role mapping 실패, 400
  `VALIDATION_ERROR`는 기대한 인증·인가 통과 결과다. 예상 밖 500은 성공으로 간주하지 않는다.
- Stock Keycloak은 HTTP와 HTTPS에 공통 listener host를 적용하므로 `KC_HTTP_HOST=0.0.0.0`을
  사용한다. Host에는 Backend를 통해 HTTPS 8443만 `127.0.0.1`에 publish하고 8082·9000은
  publish하지 않는다. Backend와 승인 helper는 JWK/Admin/management에 namespace loopback URI를
  사용한다.
- HTTP listener 자체는 공유 namespace의 `0.0.0.0:8082`에 있으므로 Backend가 참여한 local/dev
  Docker network participant의 접근 불가능을 주장하지 않는다. 이 participant와 Docker 관리자는
  operator 신뢰 경계다. Production에서는 별도 network segmentation, trusted TLS, secret manager와
  Authorization Server 계약이 필요하다.
- 별도 proxy/service/image와 helper 공유 volume은 없다. Overlay가 추가하는 persistent named
  volume은 `keycloak-data` 하나뿐이다.

## 10. SERVICE ingestion E2E (#241)

이 절은 위 #239 USER 브라우저 E2E를 대체하지 않는다. 같은 Keycloak·realm·bootstrap 계약을
유지하면서 두 SERVICE Client Credentials가 실제 Backend ingestion과 PostgreSQL 영속 경계까지
도달하는지를 별도 검증한다. `finguardops-transaction-ingestor`는 `transaction:intake`,
`finguardops-behavior-ingestor`는 `behavior-event:intake` authority만 가진다.

### 10.1 추가 경계와 사전조건

- `user_password`는 기존대로 `keycloak-bootstrap`에만 read-only mount한다. verifier, Backend,
  AI Service에는 전달하지 않는다.
- verifier에 PostgreSQL credential이나 Docker socket을 전달하지 않는다. DB 검사는 host
  orchestrator가 전용 Compose PostgreSQL container의 기존 환경 안에서 `psql`을 실행해 수행한다.
- 신규 service, proxy, dependency, shared volume을 추가하지 않는다.
- Chromium, Playwright, Windows 인증서 저장소를 사용하지 않는다. TLS host 검증은 생성한
  `CA:FALSE` certificate를 Python SSL context에 직접 지정한다.
- External Risk marker는 exact lookup POST 수신 직후 body parsing 전에
  `FINGUARDOPS_EXTERNAL_RISK_LOOKUP_RECEIVED` 한 줄만 출력한다. payload, token, trace ID,
  거래·고객·계좌 reference는 포함하지 않는다.
- Rule v2 실제 호출은 Uvicorn의 exact
  `POST /api/v2/rule-analysis HTTP/1.1` `200 OK` access line만 계산한다. Overlay는
  `--access-log`를 명시하며 누락, `--no-access-log`, 순서 변이를 static 단계에서 거부한다.

### 10.2 공식 fresh/existing-volume 명령

위 2절의 local secret·TLS artifact와 Prepared receipt가 있는 상태에서 repository root의 PowerShell로
실행한다. Python `all`을 직접 실행하지 않는다. PowerShell이 검증한 여섯 환경변수와 lock 아래에서만
child가 실행되며, lifecycle transition과 cleanup은 PowerShell이 담당한다.

```powershell
.\frontend\scripts\run-keycloak-e2e.ps1 -Mode Service
```

검사는 다음 단계 사이마다 DB global snapshot, transaction-specific cardinality, 두 dependency
실제 hit 수와 Backend outcome metric을 각각 비교한다.

1. 인증 거부: 반대 SERVICE `403` 2건, credential 누락·손상 `401` 4건
2. Behavior 생성: `PASSWORD_CHANGED`, `TRANSFER_LIMIT_CHANGED` 각각 `201`
3. Behavior replay·conflict: 동일 event `200`, payload conflict `409 DUPLICATE_EVENT`
4. Transaction 최초 성공: 12,000,000 KRW로 R001(15)+R003(40)을 유도해 `201`, score 55,
   `HIGH`, `ADDITIONAL_AUTH_REQUIRED`를 검증
5. 동일 key replay `201`과 동일 key payload conflict `409 IDEMPOTENCY_KEY_CONFLICT`
6. 다른 key의 동일 transaction 최초 요청 `409 DUPLICATE_TRANSACTION`
7. 같은 duplicate key replay `409 DUPLICATE_TRANSACTION`

최종 transaction-specific 결과는 FinancialTransaction·완료 IdempotencyRecord·완료
DetectionResult·FraudCase·CaseTransaction이 각각 1건, DetectionEvidence 2건, action별
`CASE_CREATED`, `CASE_TRANSACTION_LINKED`, `TRANSACTION_RISK_RESPONSE_APPLIED`,
`TRANSACTION_STATUS_CHANGED` AuditLog가 각각 1건이다. duplicate key의 연결되지 않은
`FAILED/DUPLICATE_TRANSACTION` IdempotencyRecord는 최초와 replay 뒤 모두 1건이다.

External Risk와 Rule v2 실제 hit는 Transaction 최초 성공에서만 각각 1 증가해야 한다.
`finguardops_external_risk_outcomes_total`과 `finguardops_rule_analysis_outcomes_total` delta도
각각 1이어야 하지만 이는 실제 dependency log count와 분리된 보조 검증이다. replay, conflict,
401, 403에서는 모든 업무 row와 두 실제 hit, 두 metric이 증가하지 않아야 한다.

### 10.3 SERVICE cleanup과 판정

Service 성공 시 PowerShell은 exact Compose project label을 가진 container, network와 volume이 0인지
확인한 뒤 Recovery receipt를 Prepared로 복원하며 unique image는 Run을 위해 유지한다. Service 실패
시 primary failure를 보존하면서 resource, 세 owned unique tag와 Recovery receipt를 정리한다. cleanup이
완전하지 않으면 Recovery receipt를 유지해 Browser Run을 구조적으로 차단한다. 기존 `*:local` image와
ignored credential·TLS artifact는 자동 삭제하지 않는다.

## 11. Run 전용 high-risk fixture orchestration

`Run`은 기존 `keycloak-verify runtime`의 exit code 0을 확인한 뒤에만 host-side
`run-fixture-before` verifier를 실행한다. 이 verifier는 기존 deterministic Rule publication helper로 네
rule version의 active 상태를 확인·준비하고 업무 테이블·External Risk/Rule hit·outcome metric의 전
상태를 canonical hash snapshot으로 반환한다. snapshot의 exact schema 앞부분은 `schemaVersion`,
`runId`, `repositoryId`, `commitSha`, `treeSha`, `composeProject`이고, 기대 identity는 Prepared receipt와
PowerShell의 authoritative Compose project plan에서만 온다. PowerShell은 이 identity와 canonical bytes를
검증한 뒤 격리된 fixed `keycloak-run-fixture` service를
`up -d --no-deps --no-build --pull never keycloak-run-fixture`로 시작하고, project inventory로 확정한
fixture container의 exact 64-hex ID 하나만 `docker wait <id>`에 넘겨 종료를 기다린다. `compose wait`는 실행
중인 container만 나열하므로 먼저 끝난 fixture를 놓칠 수 있어 쓰지 않는다. `docker wait`의 성공은 종료
사실일 뿐 성공 판정이 아니며, 그 뒤 authoritative inspect가 같은 container의 exited / exit code 0을 확인해야
다음 단계로 간다. `docker wait`가 실패하면 inspect 상태가 exited / exit code 0이어도
`RUN_FIXTURE_SERVICE_WAIT_EXITED_ZERO`로 실패한다. service에는 검증한 plan만 일시적인 `FINGUARDOPS_E2E_FIXTURE_PLAN`으로 전달하며 환경은 즉시
복원한다. 종료 container의 authoritative full ID, fixed project/service/name, `oneoff=False`, image,
label, network, mount, state와 exit code 0을 검증한 뒤, 전 snapshot을 stdin으로만
`run-fixture-after` verifier에 전달해 후 상태와 비교한다. fixture service는 public transaction/behavior API만 호출하며
SQL은 snapshot과 cardinality의 read-only 검증에만 사용한다. Service mode의 `all`, fresh-volume,
existing-volume 동작은 변경하지 않는다.

fixture service 실패의 primary는 `RUN_FIXTURE_SERVICE_START_FAILED`, `RUN_FIXTURE_SERVICE_WAIT_FAILED`,
`RUN_FIXTURE_SERVICE_WAIT_EXITED_ZERO`, `RUN_FIXTURE_SERVICE_EXIT_NONZERO`,
`RUN_FIXTURE_SERVICE_STATE_INVALID`, `RUN_FIXTURE_CONTAINER_INVALID` 중 하나다. 이 가운데 container가 0이 아닌
exit code로 종료한 `RUN_FIXTURE_SERVICE_EXIT_NONZERO`에서만, 그리고 이번 Run이 확정한 exact 64-hex
container ID가 있을 때만 PowerShell이 cleanup 전에 `docker logs <id>`를 bounded native process로 정확히
1회 읽는다. 판정 대상은 container의 stderr뿐이며 stdout과 Docker CLI 자체의 오류 출력은 marker로 취급하지
않는다. stderr가 128 bytes 이하의 strict UTF-8 한 줄이고 전체가 `verification failed: <CODE>`이며 `<CODE>`가
runner에 literal로 열거된 allowlist와 ordinal exact로 일치할 때만 다음 경고를 Run당 정확히 1회 출력한다.

```text
RUN_FIXTURE_SERVICE_SECONDARY=<CODE>
```

그 밖의 경우는 원문을 반사하지 않는 고정 literal로 대체한다. log 읽기 실패는
`RUN_FIXTURE_SERVICE_LOG_READ_FAILED`, 빈 stderr는 `RUN_FIXTURE_SERVICE_MARKER_ABSENT`, 형식 불일치는
`RUN_FIXTURE_SERVICE_MARKER_INVALID`, 상한 초과는 `RUN_FIXTURE_SERVICE_MARKER_TOO_LARGE`, allowlist 밖 code는
`RUN_FIXTURE_SERVICE_MARKER_NOT_ALLOWED`다. allowlist는 `verify_e2e.py`의 `RUN_FIXTURE_WORKER_FAILURE_CODES`와
같은 집합이고 테스트가 두 쪽의 일치를 고정한다. `run-fixture` worker 안에서는 공유 HTTP identity 세 개를
transaction token, behavior token, JWKS, cross-secret, password event, transfer-limit event, transaction
ingestion 단계별 literal로 바꾸어 기록하며, Service mode를 포함한 다른 mode의 identity는 그대로다. secondary에는
secret, token, HTTP body, URL, 업무 payload, container log 원문이 포함되지 않는다.

secondary는 진단일 뿐이다. primary `RUN_FIXTURE_SERVICE_EXIT_NONZERO`와 Run 실패는 그대로이고, log 읽기가
실패해도 resource cleanup, image cleanup, residue audit, fixture artifact와 receipt 처리 순서는 바뀌지 않는다.
나머지 다섯 primary와 성공 경로에서는 log를 읽지 않고 secondary도 출력하지 않는다. 이 진단 경로는 단위
테스트로만 검증했고 공식 Docker Gate에서는 아직 확인되지 않았다.

`run-fixture-after` verifier는 PowerShell의 bounded native process로 정확히 1회 실행한다. argv는 기존과
같은 `-B <verify_e2e.py> run-fixture-after --repo-root <repo> --project <fixed project> --fixture-directory <dir>`
이고, 검증된 전 snapshot의 base64는 끝에 LF 하나를 붙여 stdin으로만 전달한다. stdin은 child만 읽기 끝을
상속하는 전용 pipe이며 쓰기 끝은 PowerShell만 가진다. 별도 writer가 payload 전체를 쓴 뒤 handle을 닫고,
child가 읽지 않고 종료하거나 timeout으로 Job이 종료되면 막힌 write는 실패로 끝나므로 교착하지 않는다.
stdout 4096 bytes, stderr 128 bytes, timeout 630초(verifier 자체 overall deadline 600초 + 여유)로
제한하고 timeout·실패 시 기존과 같이 process와 descendant를 Job 단위로 정리한다. stdin overload를 쓰지 않는
기존 호출은 상속 stdin과 기존 동작을 그대로 유지한다.

성공 조건은 기존과 같다. exit code 0이고 stdout을 strict UTF-8로 해석해 앞뒤 공백을 제거한 값이 고정 문장
`run fixture orchestration completed: exact delta and manifest passed`와 ordinal로 같아야 한다. 성공 판정에
stderr는 사용하지 않으며 성공 시 경고도 출력하지 않는다. 다만 stdin 전체 전달 실패는 exit 0이어도 성공으로
보지 않는다.

실패하면 primary는 언제나 `RUN_FIXTURE_AFTER_FAILED`이고, 다음 경고를 Run당 정확히 1회 출력한다.

```text
RUN_FIXTURE_AFTER_SECONDARY=<CODE>
```

exit 1 또는 2일 때만 stderr를 판독한다. stderr가 128 bytes 이하의 strict UTF-8 한 줄이고 LF 또는 CRLF로
한 번 끝나며, 전체가 `verification failed: <CODE>`이고 `<CODE>`가 아래 allowlist와 ordinal exact로 일치할
때만 그 code를 secondary로 쓴다. 접두어 pattern이나 `CHILD_*` 전체 허용은 없다. allowlist 30개는
`run-fixture-after` 호출 그래프에서 도달 가능한 fixed literal이다.

| 구분 | code |
| --- | --- |
| 인자·owner·state | `HOST_ARGUMENT_INVALID`, `OWNER_CONTRACT_INVALID`, `FIXTURE_DIRECTORY_INVALID`, `RUN_FIXTURE_STATE_TOO_LARGE`, `RUN_FIXTURE_STATE_INVALID`, `RUN_FIXTURE_STATE_IDENTITY_INVALID`, `INGESTION_PLAN_INVALID` |
| host native 호출 | `OVERALL_DEADLINE_EXCEEDED`, `SUBPROCESS_FAILED`, `DATABASE_GLOBAL_SNAPSHOT_INVALID`, `BACKEND_METRIC_SNAPSHOT_INVALID`, `DATABASE_TRANSACTION_SNAPSHOT_INVALID`, `DATABASE_CASE_IDENTITY_INVALID` |
| metric-runtime child | `CHILD_METRIC_STATUS_INVALID`, `CHILD_METRIC_TRANSPORT_FAILED`, `CHILD_METRIC_BODY_TOO_LARGE`, `CHILD_METRIC_BODY_INVALID`, `CHILD_INPUT_INVALID`, `CHILD_UNEXPECTED_ERROR` |
| delta·cardinality | `DATABASE_GLOBAL_DELTA_INVALID`, `DEPENDENCY_HIT_DELTA_INVALID`, `BACKEND_OUTCOME_METRIC_DELTA_INVALID`, `DATABASE_TRANSACTION_CARDINALITY_INVALID` |
| manifest | `FIXTURE_MANIFEST_CARDINALITY_INVALID`, `FIXTURE_MANIFEST_READ_FAILED`, `FIXTURE_MANIFEST_BYTES_INVALID`, `FIXTURE_MANIFEST_SCHEMA_INVALID`, `FIXTURE_MANIFEST_IDENTITY_INVALID` |
| process fallback | `INPUT_INVALID`, `UNEXPECTED_ERROR` |

after의 host native 호출은 before stage를 넘기지 않으므로 generic `SUBPROCESS_FAILED`와 child marker
forwarding이 적용된다. 그중 자기 marker를 출력하는 child는 `keycloak-verify metric-runtime`뿐이어서 그
여섯 identity만 `CHILD_` 접두로 허용한다. 호출 그래프에 있지만 고정 after 인자로는 도달하지 않는
`SUBPROCESS_TIMEOUT_INVALID`, `DEPENDENCY_SERVICE_INVALID`, `DATABASE_GLOBAL_EXPECTATION_INVALID`,
`FIXTURE_OWNER_IDENTITY_INVALID`, `COMMAND_INVALID`는 허용하지 않는다. PowerShell 테스트는
`verify_e2e.py`를 읽어 같은 집합을 도출하고 allowlist와 비교하며, 호출 그래프가 바뀌면 실패한다.

그 밖의 경우는 원문 대신 고정 local code 11개 중 하나로 보고한다. 판정 순서는 다음과 같다.

| 순위 | 조건 | secondary |
| --- | --- | --- |
| 1 | capture 형태 불일치, native 경계 예외 | `RUN_FIXTURE_AFTER_CAPTURE_FAILED` |
| 2 | process·pipe·Job cleanup 불확실 | `RUN_FIXTURE_AFTER_CLEANUP_FAILED` |
| 3 | 실행 파일 해석·process 시작 실패 | `RUN_FIXTURE_AFTER_PROCESS_START_FAILED` |
| 4 | timeout | `RUN_FIXTURE_AFTER_TIMEOUT` |
| 5 | wait·exit code·stream capture 실패 | `RUN_FIXTURE_AFTER_CAPTURE_FAILED` |
| 6 | exit 0이지만 stdin 전체 전달 실패 | `RUN_FIXTURE_AFTER_STDIN_WRITE_FAILED` |
| 7 | exit 0이지만 stdout 성공 문장 불일치·초과·invalid UTF-8 | `RUN_FIXTURE_AFTER_SUCCESS_OUTPUT_INVALID` |
| 8 | exit 1/2와 allowlist marker | 해당 verifier code (stdin 전달 실패보다 우선) |
| 9 | exit 1/2 외 nonzero, 또는 marker가 유효하지 않으면서 stdin 전달 실패 | `RUN_FIXTURE_AFTER_STDIN_WRITE_FAILED` |
| 10 | exit 1/2 외 nonzero | `RUN_FIXTURE_AFTER_EXIT_CODE_INVALID` |
| 11 | 128 bytes 초과 | `RUN_FIXTURE_AFTER_MARKER_TOO_LARGE` |
| 12 | 빈 stderr | `RUN_FIXTURE_AFTER_MARKER_ABSENT` |
| 13 | BOM, 복수 행, 종결 누락, bare CR, invalid UTF-8, control/Cf, 형식 불일치 | `RUN_FIXTURE_AFTER_MARKER_INVALID` |
| 14 | 형식은 맞지만 allowlist 밖 code | `RUN_FIXTURE_AFTER_MARKER_NOT_ALLOWED` |

유효한 verifier marker가 stdin 전달 실패보다 우선하는 이유는, verifier가 stdin을 읽기 전에 실패하면
(예: `OWNER_CONTRACT_INVALID`) 읽는 쪽이 사라진 pipe에 대한 write가 실패하는 것이 결과일 뿐 원인이 아니기
때문이다. 어느 경우에도 primary, 기존 production cleanup, receipt, ownership 처리 순서는 바뀌지 않으며
`verify_e2e.py`의 업무 검증 조건과 동작도 바뀌지 않는다. state 원문, base64, argv, raw stdout/stderr,
예외 문장과 경로는 경고·메시지에 포함하지 않는다. 이 진단 경로는 단위 테스트와 실제 bounded child의
stdin round-trip으로 검증했고 공식 Docker Gate에서는 아직 확인되지 않았다.

Run fixture의 canonical Compose project는 exact literal
`finguardops-keycloak-browser-e2e`이다. `run-fixture-before`, `run-fixture`, `run-fixture-after`는 이 값을
ordinal/case-sensitive exact로만 허용하며 candidate state나 manifest에서 기대값을 역산하지 않는다.
Service verifier는 기존 dynamic pattern
`finguardops-kc241-e2e-[a-z0-9][a-z0-9-]{5,32}`만 사용한다. Run project는 Service 경계에서,
dynamic Service project는 Run fixture 경계에서 각각 거부한다.

`run-fixture-before`의 host native subprocess는 다음 순서와 stage로 고정한다. 3·4는 published/active
precondition이 충족되지 않을 때만 실행하고, 4는 activation 확인까지 bounded polling한다. 모든 argv는
Python의 고정 list와 해당 함수가 생성한 read-only query에서 오며 `shell`을 사용하지 않는다.

| 순서 | call site | 목적 | executable·argv | exit/stdout/stderr | timeout | stage |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `publish_rules` → `sql_scalar` | published rule count | `docker compose exec ... psql -tAc <fixed query>` | 0 / decimal scalar / empty | CLI bound | `RULE_PUBLISHED_STATE` |
| 2 | `publish_rules` → `sql_scalar` | active rule count | 동일 psql 경계와 fixed active query | 0 / decimal scalar / empty | CLI bound | `RULE_ACTIVE_STATE` |
| 3 | `publish_rules` → `HostContext.execute` | 필요 시 deterministic Rule v1 publication | `docker compose run --rm --no-deps --pull never ... backend <fixed args>` | 0 / bounded UTF-8 success marker 1회 / bounded semantic stderr | 240s 및 overall bound | `RULE_PUBLICATION_COMMAND` |
| 4 | `publish_rules` → `sql_scalar` | publication activation poll | psql fixed active query | 0 / decimal scalar / empty | 각 CLI bound 및 overall bound | `RULE_ACTIVATION_POLL` |
| 5 | `transaction_cardinality` → `sql_scalar` | fixture ID cardinality precondition | psql `concat_ws` read-only query | 0 / 14 decimal fields / empty | CLI bound | `TRANSACTION_CARDINALITY_SNAPSHOT` |
| 6 | `database_snapshot` | global repeatable-read snapshot | `docker compose exec ... psql -f -`, SQL은 stdin | 0 / canonical bounded snapshot / empty | CLI bound | `DATABASE_GLOBAL_SNAPSHOT` |
| 7 | `dependency_hit_counts` → `service_logs` | External Risk hit baseline | `docker compose logs --no-color --no-log-prefix external-risk-mock` | 0 / bounded UTF-8 log / empty | CLI bound | `EXTERNAL_RISK_LOG_SNAPSHOT` |
| 8 | `dependency_hit_counts` → `service_logs` | Rule v2 hit baseline | 동일 logs 경계의 `ai-service` | 0 / bounded UTF-8 log / empty | CLI bound | `RULE_V2_LOG_SNAPSHOT` |
| 9 | `backend_metric_totals` | outcome metric baseline | `docker compose run --rm --no-deps --pull never -T keycloak-verify metric-runtime` | 0 / finite numeric pair JSON / bounded semantic stderr | 60s 및 overall bound | `BACKEND_METRIC_SNAPSHOT` |

각 stage는 candidate output과 무관한 fixed suffix
`PROCESS_START_FAILED`, `TIMEOUT`, `EXIT_NONZERO`, `OUTPUT_INVALID`, `CLEANUP_FAILED` 중 하나만 결합한
명시적 literal로 실패한다. before의 열거된 native call site에서는 generic `SUBPROCESS_FAILED`나 child
stderr code를 전달하지 않는다. unknown stage·unexpected Python exception은 기존 안전 fallback으로
redact하고 raw command, query, path, exit code, stdout/stderr, credential·token을 diagnostic에 포함하지
않는다. PowerShell은 이 표에서 도달 가능한 exact literal만 allowlist하고 primary
`RUN_FIXTURE_BEFORE_FAILED`와 secondary diagnostic 1회 계약을 유지한다.

Compose `run`을 사용하는 `RULE_PUBLICATION_COMMAND`와 `BACKEND_METRIC_SNAPSHOT`만 stderr의
존재 자체를 실패로 간주하지 않는다. 두 stage의 stderr는 strict UTF-8, BOM·NUL·C0/C1·Unicode Cf
금지, 단일 LF 또는 CRLF style, final newline, line count·line length bound를 통과해야 한다. 또한
authoritative publication marker, 승인된 publication failure identity, Java exception headline 또는 stack
frame이 stdout이나 stderr에 있으면 exit 0이어도 해당 stage의 `OUTPUT_INVALID`로 거부한다. Raw stderr는
외부 diagnostic에 반사하지 않는다. 그 밖의 native stage는 기존 empty-stderr 계약을 그대로 유지한다.

Publication의 positive evidence는 canonical Spring Boot log line 안의
`RULE_PUBLICATION_RUNNER_SUCCESS_MARKER` 정확히 1회와 뒤따르는 DB activation poll의 published/active
`4/4` postcondition이다. Metric snapshot의 positive evidence는 기존 authoritative finite numeric pair
parser가 소유하며, stderr shape 통과만으로 malformed metric stdout을 승인하지 않는다.

`RULE_PUBLICATION_COMMAND_EXIT_NONZERO`는 backend runner의 raw Java exception을 전달하지 않는다.
Service와 Run은 동일한 `publish_rules` 함수와 동일한 Compose `run --rm --no-deps --pull never -T`
argument vector를 사용한다. 두 project의 startup과 publication one-shot은 canonical
`infra/.env.example`을 같은 Compose option 위치에 전달하고 host process environment를 그대로 상속한다.
publication HostContext는 별도 PostgreSQL credential 값을 만들거나 덮어쓰지 않으므로 ambient 변수가 absent,
present/non-empty, present/empty인 경우 모두 project startup과 같은 Compose interpolation precedence를 따른다.
credential 값은 state, stdout/stderr, manifest, diagnostic에 기록하지 않는다.

전용 publication profile에서 활성화되는 Backend process-local boundary는 실패 시
logger를 거치지 않고 stderr에 다음 exact line을 process당 최대 한 번 기록한다.

```text
FINGUARDOPS_RULE_PUBLICATION_FAILURE=<FIXED_CODE>
```

Boundary는 `UNARMED → ARMED_PRE_RUN → CONTEXT_REFRESH → CONTEXT_REFRESHED_PRE_RUN →
RUNNER_CONFIGURATION → SERVICE_EXECUTION → PUBLICATION_COMMITTED → RUNNER_SUCCEEDED` 단방향 상태를
사용한다. app-owned `SpringApplication.refresh(context)` wrapper는 `super.refresh(context)` 호출 직전에
`CONTEXT_REFRESH`, 정상 반환 직후에 `CONTEXT_REFRESHED_PRE_RUN`으로 전이한다. 따라서 refresh 진입 전,
refresh 내부, refresh 반환 후 runner callback 첫 문장 전 실패가 각각 고정 marker로 구분된다. configuration
marker는 service 호출 전, service marker는 transactional proxy가 실패한 경우에만 허용한다.
Proxy가 정상 반환하면 success log 전에 `PUBLICATION_COMMITTED`가 되므로 이후 logging, Boot ready, shutdown
failure를 service/rollback failure로 오분류하지 않는다. 정상 Backend profile과 recovery one-shot은
`UNARMED`이며 marker를 출력하지 않는다.

Authoritative marker가 없을 때만 runner/service source가 고정한 exception line의 legacy exact classifier를
사용한다. Authoritative marker가 정확히 하나 있으면 동일 code의 legacy line은 중복으로 계산하지 않지만,
서로 충돌하는 recognized identity는 generic fallback으로 처리한다.

| runner/service source contract | fixed secondary |
| --- | --- |
| context refresh 진입 전 Backend startup failure | `RULE_PUBLICATION_BACKEND_STARTUP_FAILED` |
| `SpringApplication.refresh(context)` 내부 failure | `RULE_PUBLICATION_CONTEXT_REFRESH_FAILED` |
| refresh 정상 반환 후 publication runner callback 진입 전 failure | `RULE_PUBLICATION_PRE_RUNNER_FAILED` |
| 승인 identity에 해당하지 않는 runner configuration failure | `RULE_PUBLICATION_RUNNER_CONFIGURATION_FAILED` |
| 승인 identity에 해당하지 않는 transactional service/proxy failure | `RULE_PUBLICATION_SERVICE_EXECUTION_FAILED` |
| production profile 거부 | `RULE_PUBLICATION_RUNNER_PRODUCTION_PROFILE_REJECTED` |
| publication profile과 local/dev/test profile 조합 누락 | `RULE_PUBLICATION_RUNNER_APPROVED_PROFILE_REQUIRED` |
| non-web mode 누락 | `RULE_PUBLICATION_RUNNER_NON_WEB_MODE_REQUIRED` |
| confirmation 불일치 | `RULE_PUBLICATION_RUNNER_CONFIRMATION_REJECTED` |
| effective-from canonical UTC 형식 거부 | `RULE_PUBLICATION_RUNNER_EFFECTIVE_FROM_FORMAT_REJECTED` |
| effective-from이 runner 실행 시점의 미래가 아님 | `RULE_PUBLICATION_RUNNER_EFFECTIVE_FROM_NOT_FUTURE` |
| V5 default Rule v1 set 불완전 | `RULE_PUBLICATION_SERVICE_DEFAULT_SET_INCOMPLETE` |
| V5 identity 계약 불일치 | `RULE_PUBLICATION_SERVICE_IDENTITY_MISMATCH` |
| default FraudRule 비활성 | `RULE_PUBLICATION_SERVICE_FRAUD_RULE_INACTIVE` |
| default RuleVersion period 비정상 | `RULE_PUBLICATION_SERVICE_VERSION_PERIOD_INVALID` |
| default RuleVersion status 조합 비정상 | `RULE_PUBLICATION_SERVICE_VERSION_STATUS_INVALID` |
| DRAFT period metadata 비정상 | `RULE_PUBLICATION_SERVICE_DRAFT_METADATA_INVALID` |
| service publication 시점에 effective-from 만료 | `RULE_PUBLICATION_SERVICE_EFFECTIVE_FROM_EXPIRED` |
| amountThreshold canonical format 비정상 | `RULE_PUBLICATION_SERVICE_AMOUNT_THRESHOLD_FORMAT_INVALID` |

허용 marker는 Backend source가 소유한 ASCII uppercase/underscore fixed code의 exact wire line이다.
prefix/suffix가 추가된 line, 같은 marker의 중복, 서로 다른 marker의 동시 출현, valid marker와 malformed marker의
동시 출현, stdout marker, mixed CR/LF, invalid UTF-8, control/Cf, oversized capture, authoritative marker와
충돌하는 legacy identity, success marker와 nonzero exit 조합은 기존
`RULE_PUBLICATION_COMMAND_EXIT_NONZERO`로 안전하게 fallback한다. Exit 0에 failure marker가 있으면
`RULE_PUBLICATION_COMMAND_OUTPUT_INVALID`로 거부한다. 이 분류는 raw line, command, SQL, path,
environment 또는 exit code를 외부 diagnostic에 포함하지 않는다.

`RULE_PUBLICATION_COMMAND`의 exit 0 output validation은 네 단계이며 각 단계가 독립된 fixed identity를
가진다. 두 stream을 먼저 strict UTF-8로 해석하고, failure evidence를 판정한 뒤, stderr와 stdout의 line
구조를 검사하고, 마지막으로 success marker를 검사한다.

| exit 0 output validation 단계 | fixed secondary |
| --- | --- |
| stderr strict UTF-8, final newline, bare CR, CRLF/LF 혼용, C0/C1/Cf/NUL, line 수·길이 상한 | `RULE_PUBLICATION_COMMAND_STDERR_INVALID` |
| authoritative backend marker, malformed·중복·충돌 marker, legacy approved Java identity, exception headline, stack frame, stdout wire prefix | `RULE_PUBLICATION_COMMAND_FAILURE_EVIDENCE_INVALID` |
| stdout strict UTF-8, final newline, bare CR, CRLF/LF 혼용, C0/C1/Cf/NUL | 아래 표의 predicate별 fixed code, 이름 붙이지 못한 거부만 `RULE_PUBLICATION_COMMAND_STDOUT_INVALID` |
| success marker 누락·중복, stderr marker, marker line cardinality, canonical success log-line 불일치 | `RULE_PUBLICATION_COMMAND_SUCCESS_MARKER_INVALID` |
`RULE_PUBLICATION_COMMAND_STDOUT_INVALID`가 담당하던 stdout 구조 규칙은 각 predicate가 독립된 fixed
code를 가진다. 한 capture가 여러 규칙을 위반해도 아래 고정 순서의 **첫 identity 하나만** 반환한다.

| 순위 | stdout predicate | fixed secondary |
| --- | --- | --- |
| 1 | strict UTF-8 decode 실패 | `RULE_PUBLICATION_COMMAND_STDOUT_ENCODING_INVALID` |
| 2 | non-empty stdout이 LF로 끝나지 않음 | `RULE_PUBLICATION_COMMAND_STDOUT_FINAL_NEWLINE_INVALID` |
| 3 | CR 뒤에 LF가 없음 | `RULE_PUBLICATION_COMMAND_STDOUT_BARE_CR_INVALID` |
| 4 | CRLF와 lone LF 혼용 | `RULE_PUBLICATION_COMMAND_STDOUT_MIXED_NEWLINE_INVALID` |
| 5 | U+0000 | `RULE_PUBLICATION_COMMAND_STDOUT_NUL_INVALID` |
| 6 | U+0009 | `RULE_PUBLICATION_COMMAND_STDOUT_TAB_INVALID` |
| 7 | U+001B | `RULE_PUBLICATION_COMMAND_STDOUT_ESCAPE_INVALID` |
| 8 | CR·LF·TAB·ESC·NUL을 제외한 U+0001–U+001F | `RULE_PUBLICATION_COMMAND_STDOUT_C0_INVALID` |
| 9 | U+007F–U+009F | `RULE_PUBLICATION_COMMAND_STDOUT_C1_INVALID` |
| 10 | Unicode category Cf (BOM U+FEFF 포함) | `RULE_PUBLICATION_COMMAND_STDOUT_FORMAT_INVALID` |
| 11 | 위 어느 것도 이름 붙이지 못한 `semantic_text_lines` 거부 | `RULE_PUBLICATION_COMMAND_STDOUT_INVALID` (fallback) |

`semantic_text_lines`는 여전히 최종 authority이며, 신규 classifier는 같은 규칙을 같은 강도로 세분화할
뿐이다. classifier가 이름 붙이지 않은 capture는 `semantic_text_lines`도 수락하는 capture이므로 정상
출력 허용 범위는 변하지 않는다. 기존 `RULE_PUBLICATION_COMMAND_STDOUT_INVALID`는 예상 밖 내부 상태의
fail-closed fallback으로 남는다. 거부된 문자, code point, line, candidate, raw stdout/stderr, command,
argv, SQL, path, environment, exception text, credential은 diagnostic과 PowerShell warning에 포함하지
않고 compile-time fixed code 하나만 외부로 전달한다.

#### Publication stdout TAB의 무조건적 producer 하나

공식 Run이 `RULE_PUBLICATION_COMMAND_STDOUT_TAB_INVALID`를 보고했고, artifact 수준에서 확인된
producer는 **Hibernate ORM 6.6.53.Final**의 `org.hibernate.orm.connections.pooling` logger가
INFO로 emit하는 `ConnectionInfoLogger.logConnectionInfoDetails` (**HHH10001005**,
`"Database info:"`)이다. payload는 `DatabaseConnectionInfoImpl.toInfoString()`이며 TAB으로
시작하는 7개 continuation line을 만든다. `JdbcEnvironmentInitiator.initiateService`의 세 분기가
모두 guard 없는 `logConnectionInfo` 호출로 수렴하고 jar 전체에서 호출자가 하나뿐이므로,
publication one-shot startup에서 **무조건 실행되는 정상 출력이고 failure evidence가 아니다.**
repository-owned publication 코드에는 stdout TAB producer가 없다.

> **단일 producer라고 단정하지 않는다.** `classify_semantic_stdout_violation`은 위반된 첫 규칙에서
> 반환하고 stage는 fail-closed로 종료하므로, capture에 TAB line이 몇 개이든 producer가 몇 개이든
> 한 Run이 보고할 수 있는 identity는 언제나 하나다. "정확히 1회"는 fail-fast validator의 성질이며
> producer 유일성의 증거가 아니다. 확립된 것은 HHH10001005가 이 startup 경로의 무조건적 TAB
> producer라는 사실과, repository-owned 코드가 stdout TAB을 만들지 않는다는 사실이다. 다음 공식
> Run에서 두 번째 producer가 드러나도 이 변경이 반증되는 것은 아니며, 이 변경은 여전히 필요한
> 단계다.

따라서 validator를 완화하지 않고 producer를 소유한다. `rule_publication_arguments()`가
backend application argument 영역에 다음 property를 정확히 1회 전달한다.

```
--logging.level.org.hibernate.orm.connections.pooling=WARN
```

| 항목 | 내용 |
| --- | --- |
| 억제 대상 | HHH10001005 (INFO) 하나뿐이다. 이 logger의 유일한 INFO message다. |
| 유지 대상 | 같은 logger의 WARN 4종(HHH10001002·10001006·10001009·10001010). 이 logger는 ERROR-level message를 선언하지 않지만 WARN 이상은 모두 통과한다. |
| 적용 범위 | `local,rule-v1-default-publication` profile의 publication one-shot 명령 한 개 |
| Service·Run | 같은 argv builder를 쓰므로 동일하게 적용된다. `before_stage`만 다르다. |
| Backend global logging | 변경하지 않는다. root level과 `org.hibernate` 전역 level은 그대로다. |
| 일반 runtime | 영향 없다. publication one-shot 외에는 이 property가 전달되지 않는다. |
| Image rebuild | 불필요하다. Spring Boot가 command line에서 runtime에 bind한다. |
| `OFF` | 사용하지 않는다. WARN/ERROR를 잃기 때문이다. |
| 환경변수 형태 | 사용하지 않는다. argv literal 하나로 유지한다. |
| banner·ANSI·JPA·Flyway·Hikari | 변경하지 않는다. |

**TAB validator는 완화하지 않는다.** `semantic_text_lines`의 TAB 거부, 10개 stdout predicate,
`RULE_PUBLICATION_COMMAND_STDOUT_INVALID` fallback, failure-evidence 검사, success marker
cardinality·fullmatch, activation 4/4, stderr validator, stage 우선순위가 모두 그대로다.
Hibernate INFO TAB block 모양의 fixture는 계속 `STDOUT_TAB_INVALID`로 거부되며, 그 테스트는
validator가 완화되지 않았음을 고정하는 것이다.

**Java stack trace fail-closed backstop을 유지한다.** `RULE_PUBLICATION_RUNNER_STACK_FRAME`이
matching하는 canonical frame은 TAB 검사보다 먼저 `FAILURE_EVIDENCE_INVALID`가 된다. 반면
packaging suffix `~[?:?]`, `app//` qualified frame, 중첩 `\t\tat`, line number 없는 frame,
`\t... N more`, `\t... N common frames omitted`는 그 regex가 matching하지 않으므로 **TAB 규칙이
유일한 차단선이다.** 그래서 TAB을 전역 허용하면 exit 0과 success marker를 갖춘 capture가 이
shape들을 그대로 통과시킨다. TAB 전역 허용은 금지하고, frame regex 확장은 별도 후속 hardening
으로 남긴다.

actual Green은 다음 공식 Docker Run에서 확인해야 한다.

stdout/stderr overflow, 분류할 수 없는 output-validation failure, 승인 literal 외의 candidate는 기존
`RULE_PUBLICATION_COMMAND_OUTPUT_INVALID`으로 fail-closed fallback한다. 위 두 표의 모든 code는
compile-time literal이며 raw stdout/stderr, line, path, exception, SQL, environment, credential을
반사하지 않는다.
`BACKEND_METRIC_SNAPSHOT`을 포함한 다른 여덟 native stage의 identity와 stderr 계약, 그리고
`PROCESS_START_FAILED`·`TIMEOUT`·`EXIT_NONZERO`·`CLEANUP_FAILED` 우선순위는 변하지 않는다. 허용
범위와 검증 강도도 변하지 않는다. 이 분리는 product root-cause fix가 아니라 실패 단계를 한 번의 공식
Run에서 식별하기 위한 safe classification 개선이다.

Run fixture는 `PASSWORD_CHANGED`, `TRANSFER_LIMIT_CHANGED` behavior event와 12,000,000 KRW
`ACCOUNT_TRANSFER`를 한 세트 생성한다. 기대 delta는 BehaviorEvent 2, FinancialTransaction 1,
IdempotencyRecord 1, DetectionResult 1, DetectionEvidence 2, FraudCase 1, CaseTransaction 1,
AuditLog 4이며 risk/outcome/status는 `HIGH` / `ADDITIONAL_AUTH_REQUIRED` / `OPEN`이다. External
Risk와 Rule v2 hit 및 두 outcome metric도 각각 정확히 1 증가해야 한다.

PowerShell은 receipt의 runId로 다음 repository 외부 OS temp 경로를 결정한다.

```text
<System temp>\finguardops-keycloak-e2e-fixture-<runId>\fixture-identity.json
```

경로는 system temp의 exact descendant여야 하며 wildcard, ADS, `..`, symlink, junction 또는 다른
reparse point를 허용하지 않는다. 기존 directory/file이 있으면 자동 복구하거나 덮어쓰지 않고 Run을
중단한다. fixture service에는 이 directory만 `/finguardops/fixture`로 writable bind하며 USER password와
TLS private key를 mount하지 않는다. transaction/behavior SERVICE secret은 이 service에만 read-only
secret으로 mount한다.

Manifest는 1,024 bytes 이하의 UTF-8 strict/no-BOM compact JSON이고 LF 하나로 끝난다. key 순서는
`schemaVersion`, `runId`, `repositoryId`, `commitSha`, `treeSha`, `composeProject`, `transactionId`, `caseId`,
`expectedRiskLevel`, `expectedResponseOutcome`, `expectedInitialCaseStatus`로 고정한다. unknown,
duplicate, missing, reordered key와 null/array/object/boolean/float, C0/C1 control, Unicode format
character를 거부한다. 두 업무 ID는 lowercase canonical UUID v4이고 receipt identity는 ordinal exact로
일치해야 하며 `composeProject`는 위 Run literal과 exact로 일치해야 한다. writer는 final preexistence와 non-empty directory를 거부하고 same-directory CreateNew
temp, write/flush/fsync, no-replace publication, final byte 재검증을 수행한다.

최종 이름의 no-replace publication에는 구현 경로가 둘 있다. 어느 경로도 기존 최종 파일을 덮어쓰지 않는다.

1. 기본 경로는 `renameat2`에 `RENAME_NOREPLACE`만 지정한 단일 atomic rename이다. 성공하면 temp 이름이
   사라지고 최종 이름만 남는다.
2. `renameat2`가 errno `EINVAL`, `ENOSYS`, `EOPNOTSUPP`/`ENOTSUP` 중 하나로 실패한 경우에만 link 경로로
   넘어간다. 이 값들은 renameat2(2)가 "filesystem 또는 kernel이 해당 flag나 call을 지원하지 않음"으로
   문서화한 값이고, 이 호출은 flag 하나와 같은 directory의 두 이름만 넘기므로 `EINVAL`의 다른 문서화된
   원인은 해당하지 않는다. link 경로는 `link(temp, final)`로 최종 이름을 만든 뒤 temp 이름을 `unlink`한다.
   최종 이름의 생성 자체는 atomic하고 기존 이름이 있으면 실패하지만, link와 unlink 두 단계 전체는 단일
   atomic rename이 아니다. 두 단계 사이에는 두 이름이 함께 존재한다.

`EEXIST`, 권한·소유권 거부, I/O 오류, 분류되지 않은 errno, libc symbol 부재에서는 link 경로로 넘어가지 않는다.
실패는 errno·예외 문장·경로 없이 다음 고정 code 하나로만 기록하며 runner는 이를 secondary로 전달한다.

| 경계 | fixed code |
| --- | --- |
| 최종 이름이 이미 존재 (`renameat2` 또는 `link`) | `FIXTURE_MANIFEST_FINAL_EXISTS` |
| libc 로드 실패, `renameat2` symbol 부재, 호출 자체의 예외 | `FIXTURE_MANIFEST_RENAME_UNAVAILABLE` |
| `renameat2`가 `EACCES`·`EPERM`·`EROFS` | `FIXTURE_MANIFEST_RENAME_DENIED` |
| `renameat2`가 `EIO`·`ENOSPC`·`EDQUOT` | `FIXTURE_MANIFEST_RENAME_IO_FAILED` |
| `renameat2`의 그 밖의 errno (원인 구분 불가) | `FIXTURE_MANIFEST_RENAME_FAILED` |
| link 경로의 `link`가 `EACCES`·`EPERM`·`EROFS` | `FIXTURE_MANIFEST_LINK_DENIED` |
| link 경로의 `link`가 그 밖의 오류 | `FIXTURE_MANIFEST_LINK_FAILED` |
| link 성공 후 temp `unlink` 실패 | `FIXTURE_MANIFEST_TEMP_UNLINK_FAILED` |

temp `unlink`가 실패하면 성공으로 처리하지 않는다. 방금 만든 최종 이름이 여전히 temp와 같은 파일일 때에만 그
최종 이름을 회수하고, 이후 temp는 writer의 기존 실패 정리가 best effort로 제거한다. 회수도 best effort이므로 mount가
unlink를 모두 거부하면 두 이름이 남을 수 있고, 그 경우에도 결과는 실패이며 runner의 기존 artifact cleanup 계약이
남은 파일을 판정한다. `FIXTURE_MANIFEST_LINK_DENIED`는 권한 거부뿐 아니라 mount가 hard link 자체를 지원하지 않아
`EPERM`을 돌려준 경우일 수도 있다. 어느 경로로 성공했든 뒤따르는
cardinality 확인과 final byte 재검증은 동일하다. primary identity와 runner의 cleanup·receipt 계약은 바뀌지 않는다.
link 경로가 Docker Desktop의 host 공유 mount에서 실제로 동작하는지는 단위 테스트로 확인할 수 없고, 공식 Docker
Gate에서 아직 검증되지 않았다.

Manifest 검증 전에는 Browser `docker create`와 `docker start`가 호출되지 않는다. 최초 검증한 manifest의
SHA-256과 directory/file identity를 보존하고, Browser readiness 이후 Playwright process 생성 직전에 path,
reparse, exact cardinality, bounded exclusive read, canonical schema·receipt binding과 최초 hash/identity를
다시 검증한다. 교체 또는 내용 변경은 `FIXTURE_MANIFEST_CHANGED`로 거부하며 Playwright process를 만들지 않는다. Browser container의
기존 read-only bind 3개, create argv와 ownership validator는 그대로 유지한다. Windows host의 Playwright
child에만 `FINGUARDOPS_E2E_FIXTURE_MANIFEST`로 검증된 canonical host path를 전달하고 child 종료 직후
Process environment를 원래 값으로 복원한다. 기존 값 또는 SERVICE secret/token 환경변수가 있으면
오염으로 거부한다. Manifest 내용, token, password, client secret과 Secret path는 stdout/stderr에
출력하지 않는다.

#### Playwright 실패의 고정 필드 진단

Playwright child의 stdout과 stderr는 모두 runner가 받아 버린다. `line` reporter 원문, test·web server
출력, stderr `ErrorRecord`, 문자열이 아닌 객체는 Run 로그에 남지 않는다. Runner는 child마다 32자리
lowercase hex nonce를 새로 만들어 `FINGUARDOPS_E2E_REPORTER_NONCE`로 Playwright 주 process에만
전달한다. `playwright.config.ts`는 이 값이 형식에 맞을 때만 `e2e/safe-failure-reporter.ts`를 `line`
reporter 옆에 추가하고, web server 환경에서는 제외한다. Reporter는 생성 즉시 값을 process 환경에서
지운다. 그래서 그 뒤에 시작되는 worker와 web server는 nonce를 모른다. Child가 끝나면 runner가 이전
값을 복원한다. Nonce는 test 출력이 우연히 record 모양을 띠더라도 진짜로 받아들여지지 않게 하는
장치일 뿐이다. 악의적인 test code에 대한 보안 경계는 아니다.

Reporter는 아래 record만 stdout에 쓴다. Runner는 `FINGUARDOPS_E2E_PW_V1 <nonce> <record>` 한 줄
전체가 아래 문법과 정확히 일치할 때만 nonce를 뺀 `PLAYWRIGHT_DIAGNOSTIC=<record>`를 warning으로
전달한다. 160자 초과, 앞뒤 공백, CR/LF, 대소문자 변형, field 추가·누락·순서 변경, 범위 밖 숫자,
선행 0, 비ASCII 숫자, 다른 nonce는 모두 버린다.

| record | 의미 |
| --- | --- |
| `TEST line=<1-99999\|none> n=<1-999> status=<failed\|timedOut\|interrupted> kind=<REQUIRE_CONDITION\|EXPECT\|TIMEOUT\|INTERRUPTED\|OTHER> at=<1-99999\|none> stage=<RELAY_INIT\|GUARD\|LOGIN\|USAGE_API\|USAGE_UI\|DETAIL_API\|DETAIL_UI\|CLEANUP\|none>` | `line`은 test 선언 줄, `n`은 같은 줄에서 선언된 test 중 순번, `at`은 `requireCondition` helper frame을 건너뛴 첫 spec frame 줄. `stage`는 관리자 test의 첫 실패 고정 단계, 단계 오류가 기록되지 않았으면 마지막 진입 단계다. 단계 이전의 beforeEach 실패와 다른 test는 `none`이다. |
| `GLOBAL kind=<WEBSERVER\|OTHER>` | test 밖 오류. `config.webServer` 오류만 `WEBSERVER` |
| `SUMMARY status=<passed\|failed\|timedout\|interrupted> passed=<0-9999> failed=<0-9999> skipped=<0-9999>` | 실행 종료 요약 |
| `OVERFLOW` | reporter 상한(TEST 32, GLOBAL 4) 초과 |

관리자 test의 `USAGE_API`는 목록·집계 GET 각각의 200 관측, `DETAIL_API`는 상세 GET의 200 관측만
확인한다. 추가 요청은 보내지 않으며 기존 화면·권한·민감정보 비노출 단언과 60초 전체 제한을 유지한다.
관리자 test만 단계 값을 기록하고 테스트별 단계 상태는 서로 공유하지 않는다. 정상 완료는 TEST 실패 record를 내지 않고, 전체 timeout은
`at=none`이어도 고정 `stage` 하나만 낸다. finally 정리 또는 그 뒤의 afterEach 실패는 `CLEANUP`으로
표시하되, 그보다 앞선 단계 오류가 이미 기록되었다면 그 단계가 우선한다. 단계가 시작되기 전의
beforeEach 실패는 `none`이다. URL·응답 본문·token·사용자·Provider·원문 예외는 stage에 포함되지 않는다.

Runner가 직접 만드는 고정 code는 셋이다. 전달 상한 40줄을 넘으면 `RUNNER_OVERFLOW`를 한 번만 남기고
나머지를 버린다. Child가 실패했는데 SUMMARY가 없으면 `SUMMARY_ABSENT`, nonce 생성에 실패했으면
`NONCE_UNAVAILABLE`을 남긴다. Test title, error message, stack, URL, DOM text, screenshot, trace,
credential과 token은 출력하지 않는다. Error 원문은 reporter 안에서 `kind`를 고르는 데만 읽는다.

판정은 기존 그대로 Playwright exit code 하나다. `SUMMARY status=passed`가 있어도 exit code가 0이
아니면 `Playwright Keycloak E2E failed.`로 실패한다. 반대로 실패 record가 있어도 exit code가 0이면
성공이다. 진단 writer가 실패해도 예외를 던지거나 primary failure를 대체하지 않는다. 환경변수 복원,
Browser·project·output cleanup 순서, receipt 계약도 바뀌지 않는다. 이 진단은 다음 Gate에서 실패한
test 위치와 종류를 식별하기 위한 것이다. 이번 변경으로 실제 실패 원인이 확인된 것은 아니다.

사건 목록 E2E의 populated 분기는 #255 이후 제품 계약인 행당 링크 1개를 검증한다. 각 링크는
`/cases/<canonical lowercase UUID v4>` exact href여야 하고 query·fragment가 없어야 한다. 표시 text는
같은 ID여야 하고, `aria-label`은 `View case details for <ID>`여야 하며, 행 사이에 ID가 중복되면 안 된다.
기존 "링크 없음" 단언은 #255의 링크 계약과 충돌했다. 빈 DB에서는 empty 분기만 실행되어 이 충돌이
드러나지 않았는데, Run fixture가 사건을 만들면서 populated 분기가 실행되게 되었다. 이 충돌은
정적 분석으로 찾은 별도 결함이다. 직전 Gate 실패의 원인으로 확인된 것은 아니다. Oracle의 반례
20개는 공식 test 수를 늘리지 않도록 같은 사건 목록 test 안에서 먼저 검증한다.

Run과 명시적 Cleanup의 순서는 exact project resource cleanup → owned unique image cleanup → final
Docker residue audit → exact fixture artifact cleanup → receipt 삭제다. 앞의 세 단계가 실패하면 artifact와
receipt를 보존한다. Artifact cleanup은 Prepared 또는 Recovery receipt의 runId로 파생한 exact directory가
없으면 idempotent success이고, safe-path/reparse 검증을 통과한 exact empty directory도 manifest 생성 전
실패의 owned residue로서 non-recursive exact 삭제한다. directory에 canonical manifest가 정확히 하나 있으면
receipt binding을 다시 검증한 뒤 exact file과 빈 directory만 삭제한다. partial/temp/extra/foreign artifact는 자동 삭제하지 않고
receipt를 보존한다. glob, prefix enumeration, label 기반 broad cleanup은 사용하지 않는다.

## 12. Run fixture 사건 처리·감사 Browser E2E (#314)

Issue #339의 AI 조사 리포트 Browser 검증은 #337 테스트가 동일 Run 사건을
`IN_REVIEW`로 재개한 **뒤**, 종결하기 **전**에 실행한다. #314가 끝날 때의
`ADDITIONAL_INFORMATION_REQUIRED`·version 3·Audit 5와 #337의 재개·종결
write 및 Audit 단언을 유지한다. AI POST는 별도의 exact 현재 사건
method/path/body/Idempotency-Key relay arm으로 요청 횟수를 검증한다.
Gate의 모의 Ollama는 기존 external-risk-mock 컨테이너의 독립 내부 포트에서
실행되며 FastAPI HTTP 경로·영속 상태·재조회만 검증한다. Qwen 품질·속도·
메모리 검증은 실제 로컬 모델 합성 평가에서만 기록한다. 이 문서의 공식
Prepare→Service→Run은 clean commit 이후 OWNER가 실행한다.

`a real USER works the Run fixture case through review, a note and the audit trail` test는 §11의 Run
fixture가 만든 현재 Run의 거래·사건으로 핵심 사건 처리 흐름을 검증한다. 기존 FDS_ANALYST USER
(`local-fds-analyst`)의 실제 Keycloak 로그인과 실제 Spring Boot만 사용한다. production, API, DB,
Keycloak, Compose, runner와 verifier는 바꾸지 않았다. 별도 실행 명령도 없으며 §3.1의 공식
`Prepare → Service → Run`에서 함께 실행된다.

### 12.1 Fixture identity 사용

test는 Playwright child에 전달된 `FINGUARDOPS_E2E_FIXTURE_MANIFEST`를 직접 다시 검증한다. 검증 조건은 다음과 같다.

- 절대 경로이면서 정규화된 경로다.
- 정확히 `finguardops-keycloak-e2e-fixture-<runId>/fixture-identity.json` 위치다.
- 파일에서 root까지 symbolic link나 junction이 없다.
- 1,024 bytes 이하의 regular file 하나다.
- §11의 canonical compact JSON bytes와 정확히 일치한다.
- manifest의 `runId`가 directory 이름과 일치한다.

실패하면 path, ID, bytes를 반사하지 않는 고정 문장으로 끝난다. 사용하는 값은 `transactionId`,
`caseId`와 기대 초기 상태뿐이다. 반례 oracle은 같은 test 안에서 먼저 실행한다. bytes 반례는 22개이고,
가짜 file system 위의 path·symlink·junction·크기·읽기 실패·binding 반례는 20개다.

`expectedRiskLevel=HIGH`와 `expectedResponseOutcome=ADDITIONAL_AUTH_REQUIRED`는 `run-fixture-after`가
DB 기준으로 검증한 계약이다. 거래 기록 응답에는 위험 등급·대응 결과·사건 ID가 없다. #335의 별도
`GET /api/v1/transactions/{transactionId}/adopted-detection-result`는 채택된 완료 탐지 결과와 RULE 근거를
공개하며, Browser는 이 응답의 `HIGH`와 거래 화면의 채택 탐지 결과 영역을 확인한다. 거래 기록의 공개
`processingStatus=ADDITIONAL_AUTH_REQUIRED`도 별도로 확인한다. 채택 위험 등급은 최종 판정이 아니다.

### 12.2 검증 흐름

1. 거래 상세 주소에서 실제 로그인을 한다. 거래 기록 응답이 manifest의 `transactionId`이고 `processingStatus`가
   위와 같은지 확인한다. 그 응답에 `riskLevel`, `riskResponseOutcome`, `caseId`가 없는지도 확인한다.
   별도 채택 탐지 결과 응답과 화면에서 `HIGH` 및 RULE 근거를 확인한다.
2. 사건 목록에서 공개 필터 `Related transaction ID`(`transactionId`)에 manifest 거래 ID를 넣는다. 확인 항목은 다음과 같다.
   - 요청 target이 `?transactionId=<id>&page=0&size=20&sort=lastChangedAt%2Cdesc`와 정확히 같다.
   - 결과가 정확히 1건이다.
   - 그 행이 manifest의 `caseId`, `OPEN`, 미배정, 연관 거래 1건이다.

   첫 행을 임의로 고르지 않는다. 이 필터는 공개 목록 계약이며, 사건별 거래 목록이나 거래 상세의
   사건 연결을 주장하지 않는다.
3. 행 링크로 사건 상세에 들어간다. 확인 항목은 다음과 같다.
   - 공개 10개 필드와 `OPEN`, `concurrencyVersion` v0
   - note 0건과 OPEN 작성 잠금 문구
   - 초기 Audit 2건: 최신순 `CASE_TRANSACTION_LINKED`, `CASE_CREATED`. 둘 다
     `CASE_REQUIRED_BY_RISK_POLICY`·`SYSTEM`이다.
4. 금지 전이 `OPEN → ADDITIONAL_INFORMATION_REQUIRED` status PATCH를 production authorized client로 한 번
   보낸다. 확인 항목은 다음과 같다.
   - 응답이 409와 `CASE_STATUS_CONFLICT`, 빈 `fieldErrors`다.
   - 공개 API 재조회 결과, detail(status·version), notes, 업무 Audit가 trace를 제외하고 거부 전과 같다.
   - 오류 code·message·trace가 DOM과 console에 나오지 않는다.
5. 화면에서 `Start review`로 `OPEN → IN_REVIEW`를 수행한다. 결과는 v0+1이고
   Audit `CASE_STATUS_CHANGED/CASE_REVIEW_STARTED/USER`가 추가된다.
6. 화면에서 note를 작성한다. 확인 항목은 다음과 같다.
   - POST 201
   - 응답과 GET의 note가 같다.
   - 화면 Note ID와 원문 표시
   - v0+2
   - Audit `CASE_NOTE_CREATED/CASE_INVESTIGATION_NOTE_ADDED/USER`, metadata `{noteId}`
7. 새로고침하면 in-memory session이 사라져 `Sign in required`가 된다. 이 동안 Backend 요청은 0건이다.
   같은 browser context에서 다시 로그인하면 Keycloak 화면 또는 살아 있는 SSO session을 거쳐 실제
   code exchange가 일어난다. 같은 USER subject인지 확인하고, Backend에서 note를 다시 읽어 표시하는지 확인한다.
8. 화면에서 `Request additional information`으로 `IN_REVIEW → ADDITIONAL_INFORMATION_REQUIRED`를
   수행한다. 결과는 v0+3이고 Audit `CASE_ADDITIONAL_INFORMATION_REQUESTED`가 추가된다.
9. 채워진 Audit UI 5건을 확인한다. 각 항목의 action, reason, actorType, KST 표시와 API의 `changedAt`,
   before/after summary, note의 Note ID를 대조한다. Audit 영역에 actorId(로그인 subject), targetId(caseId),
   transactionId, traceId, raw JSON이 없는지도 확인한다.
10. 업무 write가 순서대로 정확히 `PATCH status 409`, `PATCH status 200`, `POST notes 201`,
    `PATCH status 200` 네 건이고 모두 manifest 사건 주소인지 확인한다. Backend 요청 전체가 이 거래와
    사건의 주소만 사용했는지도 확인한다.

assignee는 실행마다 새로 만든 canonical lowercase UUID v4다. 현재 production 계약
(`FraudCaseWorkflowValidator`)은 이 형식만 검사하고 담당자 디렉터리나 허용 목록은 구현되어 있지 않다.
따라서 이 값은 실제 사용자나 유효한 담당자를 뜻하지 않으며, 로그인 subject와 다른 값이다.

### 12.3 Relay write 경계

Issue #318은 Run 사건에 대한 status PATCH, note POST, assignee PATCH, resolution POST 네 write 주소를
relay descriptor에 포함했다. live Run에서는 이전 synthetic resolution probe를 비활성화한다. 네 주소 모두
test가 직전에 arm한 manifest 사건 ID의 exact method, path, body와 일치할 때만 한 번 전달하며, relay
request를 만들 때 arm을 소비한다. #314는 이 중 status와 note만 사용한다. #318의 권한 반례와 #337의
resolution 시나리오도 같은 1회 arm 경계를 사용한다. 다음 요청은 process 생성 전에 거부한다.

- arm하지 않은 write와 중복 요청
- 다른 caseId
- query
- trailing slash와 추가 segment
- 대문자 ID
- PUT·DELETE
- 다른 body와 재서식 body
- 중복 key와 빈 body

audit-log write와 그 밖의 미승인 주소는 선언하지 않는다. 기존 query·endpoint 반례 matrix도 유지한다.

### 12.4 Verified gate for Issue #314

The official Docker Prepare, Service, and Run modes each succeeded once after the #315 fixture work. The #314 Browser suite passed 23/23, including the seeded case workflow, note, denied status transition, and populated Audit UI. The #315 gate had a separate wrapper exit-code observation limit even though its Browser 22/22 passed; do not attribute that limit to the later #314 gate. The #314 cleanup left zero owned resources, fixture artifacts, and receipts.

## 13. Real case-role denial Browser E2E (Issue #318)

Run creates one manifest-bound OPEN case. The #318 test runs before the #314 mutating workflow test in this file, with workers=1 and retries=0. It signs in separately as local-fds-viewer, local-fds-analyst, and local-fds-approver, using the existing ignored user-password Secret. All three must issue matching USER access/ID subjects and exactly one expected role, with the Backend singleton audience. The Browser and host Playwright receive no SERVICE credential.

Each USER can read the case, all note pages, and all business Audit pages. The Browser checks capability controls separately from the Backend response. Viewer sends note/status/assignee/resolution writes once each and receives 403. Analyst sends resolution once and receives 403. Approver sends note/status/assignee once each and receives 403; an additional OPEN resolution receives CASE_STATUS_CONFLICT 409. No resolution succeeds and the case remains OPEN throughout this test. The relay arms one exact current-run caseId/method/path/body and consumes it once. Wrong caseId, query, trailing slash, changed body, and duplicate writes are rejected before a Backend process starts.

For each denied write, the same USER reads case status, concurrencyVersion, assigneeRef, finalDisposition, every note page, and every public business Audit page before and after. The comparison excludes per-request trace IDs. Application/security logs are not business Audit rows. The following #314 test then performs its approved successful mutations on the same Run fixture.

Issue #318의 [PR #319](https://github.com/Ahnjisan/FinGuardOps/pull/319)은 공식 Prepare·Service·Run 각 1회와
Browser 26/26 통과를 보고했다. 해당 PR의 cleanup 보고에는 owned resource·fixture·receipt 잔여 0이 포함된다.
이는 #337의 Approver 성공 시나리오에 대한 Gate 결과가 아니다.

## 14. Analyst 조사 재개 → Approver 최종 판정 → CLOSED 재조회 (Issue #337)

#314 Browser 테스트 **뒤에** 독립 테스트 하나가 같은 Run manifest 사건을 사용한다. #318은 `OPEN`에서
권한 거부를 확인하고, #314는 정확히 4건의 업무 write와 Audit 5건을 확인한 뒤 사건을
`ADDITIONAL_INFORMATION_REQUIRED`로 남긴다. 두 기존 테스트의 요청·Audit 단언은 변경하지 않는다.

1. Approver USER가 공개 상세·전체 메모·전체 업무 Audit를 읽어 추가 정보 필요, version 3, 기존 담당자,
   최종 판정 없음, 메모 1건, Audit 5건을 확인한다.
2. `expectedVersion=2` resolution은 `409 CONCURRENT_MODIFICATION`, 현재 version 3에서 직접 종료는
   `409 CASE_STATUS_CONFLICT`다. 각각 한 번만 보내고 매번 사건 상세·전체 메모·전체 업무 Audit가 거부
   전과 같음을 확인한다.
3. Analyst USER가 기존 담당자를 유지하며 `CASE_REVIEW_RESUMED`로 `IN_REVIEW`에 복귀한다. version 4,
   Audit 6건 및 `CASE_STATUS_CHANGED/CASE_REVIEW_RESUMED` 한 건을 확인한다.
4. `IN_REVIEW`에서도 Viewer·Analyst에게 사건 종결 UI가 없고 두 USER의 실제 resolution 요청이 각각
   Backend 403인지 확인한다. 각 거부 뒤 사건·전체 메모·전체 Audit가 불변이어야 한다.
5. Approver USER가 최신 사건을 다시 읽고 UI에서 `NORMAL`을 **명시적으로 선택**한 뒤 사건을 종결한다.
   `reasonCode=CASE_RESOLUTION_COMPLETED`, `expectedVersion=4` 요청 하나의 HTTP 200, `CLOSED`, version 5,
   `closedAt=lastChangedAt`, 메모 1건, Audit 7건과 새 `CASE_RESOLVED` 한 건을 확인한다.
6. 새로고침·실제 재로그인 뒤 공개 상세·메모·Audit를 다시 읽고 같은 판정·시각·version과 정확한 공개
   필드만 남는지 확인한다. 모든 write는 기존 relay의 현재 Run 사건 method/path/body를 한 번씩만 arm한다.

`NORMAL`은 이 테스트에서 OWNER가 지정한 입력값이다. `HIGH` 채택 탐지 결과를 정상 판정의 근거로
해석하지 않는다. 이 시나리오는 판정의 업무적 타당성, 담당자 디렉터리 연동, 실제 승인 요청 단계나
외부 서비스 성능을 검증하지 않는다. #337의 실제 성공과 cleanup은 clean commit의 다음 공식
`Prepare → Service → Run` Gate가 통과한 후에만 완료로 기록한다.

## Issue #339 Service publication recovery (2026-10-05)

The Service verifier runs the Rule v1 publication through `docker compose run --rm ... backend`. A host subprocess failure does not prove that Docker removed the one-off container. The Service publication call now reports fixed `RULE_PUBLICATION_COMMAND_PROCESS_START_FAILED`, `RULE_PUBLICATION_COMMAND_TIMEOUT`, `RULE_PUBLICATION_COMMAND_EXIT_NONZERO`, or `RULE_PUBLICATION_COMMAND_CLEANUP_FAILED` codes. These codes apply to **future** executions; the earlier `SUBPROCESS_FAILED` cannot be reclassified retrospectively. After a successful publication command, Service checks that no backend one-off remains and fails with `RULE_PUBLICATION_ONEOFF_REMAINS` if one does.

For the existing recovery receipt, Cleanup first validates the one-off's full Docker ID, exact Service project and backend service, receipt-bound run/repository/source/revision labels, owned backend image reference and ID, publication command, environment, and runtime configuration. The regular backend container supplies the expected inherited configuration. A name or project label alone grants no removal authority. Any mismatch fails closed with `RESOURCE_CLEANUP_FAILED`; do not use a manual `docker rm`, `compose down --remove-orphans`, image prune, or receipt deletion to bypass it.

After code and counterexample tests pass, run the official `-Mode Cleanup` once with the recovery receipt still present. It removes verified project resources first, then owned unique images, audits both projects and the browser container, and deletes the receipt last. If Cleanup fails, preserve the receipt and remaining resources for another read-only diagnosis; do not retry or delete more resources until the fixed failure boundary is understood. This recovery does not establish why the original Service subprocess failed and does not count as a successful Prepare → Service → Run Gate.

## Issue #380 Rule+ML local Browser Gate

The official `Prepare → Validate → Service → Run` lifecycle remains the owner of
images, receipts, the fixed Run Compose project, and cleanup. With the local ML
profile enabled, the existing Service and Run ingestion fixtures retain their
Rule scores and business responses but persist one additional ML Evidence row
with zero contribution. Their expected row cardinality is three Evidence rows.

The Run Browser suite then publishes RuleVersion 2 and creates a separate
synthetic transaction through the public API. Twelve preceding behavior events
produce an applied `fraud-logistic-v2` result. The Run fixture checks External
Risk/Rule/ML call deltas, pinned cutoff and SHA-256, Rule 85 + ML 35 = capped
100/CRITICAL, HELD, four RULE plus one ML Evidence, one linked OPEN case, four
Audit rows, and a completed idempotent replay without another call or row.
The same Run project creates a separate transaction with 1001 preceding events;
ML must fail explicitly with `ML_EVENT_LIMIT_EXCEEDED`, public 503, no adopted
result, Evidence, case, risk response or business Audit. The analyst signs in
through real Keycloak and reads the normal transaction, adopted result, case,
and the failed transaction in the production UI. The existing USER permission,
case workflow and AI report Browser tests still run. The Ollama response in
this Gate is a local mock and is not a real Qwen result. All model metrics and
the 5000bp/40 point score rule are synthetic local validation, not measured
financial fraud detection performance.
