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

준비와 실행은 분리된 두 단계다. 네트워크를 쓰는 준비 작업은 `-Mode Prepare`에만 있고, 공식 E2E인
`-Mode Run`은 이미 local에 있는 image만 사용한다. `-Mode Run`은 준비 작업을 암묵적으로 대신
실행하지 않는다.

repository root의 PowerShell에서 dependency를 먼저 설치한 뒤 준비 단계를 실행한다. host Chromium은
더 이상 필요하지 않다. 실제 credential은 명령 인자나 환경 변수로 전달하지 않는다.

```powershell
Set-Location frontend
npm ci
Set-Location ..
.\frontend\scripts\run-keycloak-e2e.ps1 -Mode Prepare
```

Prepare는 이 단계에서만 네트워크를 사용한다. 고정된 Compose image를 모두 pull하고, 이 저장소가
빌드하는 image를 build하며, 고정 digest의 Playwright base image를 pull한 뒤
`frontend/Dockerfile.playwright-e2e`로 browser image `finguardops-playwright-e2e:local`을 빌드한다.
`apt`는 이 image build 안에서만 실행되어 exact version `libnss3-tools=2:3.98-1ubuntu0.2`를 설치하고
같은 layer에서 package manager cache를 제거한다. Playwright version, `certutil` package version과
base image digest는 OCI label로 image에 고정되며 browser는 image의 non-root `pwuser`로 실행된다.

준비가 끝나면 공식 E2E를 실행한다.

```powershell
.\frontend\scripts\run-keycloak-e2e.ps1
```

Run은 registry에 접근하지 않는다. 시작 시 merged Compose 설정이 선언한 모든 service image와 browser
image가 local에 있는지 `docker image inspect`로만 확인한다.

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

비정상 종료로 전용 resource가 남으면 다음 명령이 전용 browser container와 전용 Compose project만
정리한다. `.local` certificate·private key·password, Prepare가 빌드한
`finguardops-playwright-e2e:local`과 다른 Docker resource는 삭제하지 않는다.

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

다음 순서를 application stack보다 먼저 실행한다. `--no-interpolate`는 merged config에 기존
Compose credential 값이 펼쳐지는 것을 피한다.

```bash
mkdir -p infra/keycloak/.local/config
docker compose \
  -f infra/compose.yml \
  -f infra/compose.keycloak-local-e2e.yml \
  config --no-interpolate --format json \
  > infra/keycloak/.local/config/keycloak-merged.json
python -B infra/keycloak/verify_e2e.py static \
  --config infra/keycloak/.local/config/keycloak-merged.json \
  --realm infra/keycloak/realm/finguardops-local-realm.json
```

두 overlay를 넣은 반례는 `STATIC_MULTIPLE_ISSUERS`로 non-zero 종료해야 한다.

```bash
docker compose \
  -f infra/compose.yml \
  -f infra/compose.local-jwt-e2e.yml \
  -f infra/compose.keycloak-local-e2e.yml \
  config --no-interpolate --format json \
  > infra/keycloak/.local/config/forbidden-mixed.json
python -B infra/keycloak/verify_e2e.py static \
  --config infra/keycloak/.local/config/forbidden-mixed.json \
  --realm infra/keycloak/realm/finguardops-local-realm.json
```

## 5. Fresh start와 완료 판정

project 이름은 이 실행 전용의 exact 값으로 고정한다. 아래 예시는 credential 값이 아니다.

```bash
PROJECT_NAME=finguardops-keycloak-local
compose=(docker compose -p "$PROJECT_NAME" --env-file infra/.env \
  -f infra/compose.yml -f infra/compose.keycloak-local-e2e.yml)
"${compose[@]}" up -d --build keycloak-verify
"${compose[@]}" wait keycloak-verify
verify_id=$("${compose[@]}" ps -aq keycloak-verify)
test "$(docker inspect --format '{{.State.ExitCode}}' "$verify_id")" = 0
"${compose[@]}" logs --no-color keycloak-bootstrap keycloak-verify
python -B infra/keycloak/verify_e2e.py host \
  --certificate infra/keycloak/.local/tls/localhost.crt
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

같은 `PROJECT_NAME`을 유지하고 `keycloak-data`는 삭제하지 않는다.

```bash
"${compose[@]}" up -d --force-recreate keycloak keycloak-bootstrap keycloak-verify
"${compose[@]}" wait keycloak-verify
verify_id=$("${compose[@]}" ps -aq keycloak-verify)
test "$(docker inspect --format '{{.State.ExitCode}}' "$verify_id")" = 0
"${compose[@]}" logs --no-color keycloak keycloak-bootstrap keycloak-verify
python -B infra/keycloak/verify_e2e.py host \
  --certificate infra/keycloak/.local/tls/localhost.crt

for run in 1 2 3 4 5; do
  "${compose[@]}" up -d --force-recreate keycloak-verify
  "${compose[@]}" wait keycloak-verify
  verify_id=$("${compose[@]}" ps -aq keycloak-verify)
  test "$(docker inspect --format '{{.State.ExitCode}}' "$verify_id")" = 0
done
```

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

2026-09-05 correction 실행은 fresh/existing volume, host 검증과 existing verifier 5회를 모두
첫 시도에 통과했고 시간 오류는 재발하지 않았다.

## 7. 안전한 종료와 제한된 clean reset

데이터를 유지한 종료는 다음과 같다.

```bash
"${compose[@]}" stop
```

fresh 검증을 다시 할 때만 현재 exact project의 container/network를 내리고 그 project의
`keycloak-data` 하나를 제거한다. 광범위한 prune이나 다른 volume 삭제는 금지한다.

```bash
test "$PROJECT_NAME" = finguardops-keycloak-local
"${compose[@]}" down
docker volume rm "${PROJECT_NAME}_keycloak-data"
```

## 8. Rotation과 local artifact 제거

USER password만 rotation하려면 stack을 먼저 중지하고 exact `user-password`만 안전하게 폐기한 뒤
secret script를 다시 실행한다. Admin·SERVICE secret rotation은 4개 secret 전체를 의도적으로 폐기한
fresh 상태에서만 수행한다. TLS rotation은 exact certificate/key 두 파일을 함께 폐기한다. 새 credential과
private key를 terminal에 출력하지 말고 Keycloak과 helper를 재생성해 bootstrap/verifier를 다시
통과시킨다. 이전 SERVICE secret의 교차 사용은 실패해야 한다.

검증이 끝나면 trust store에서 localhost certificate를 제거하고, 필요한 증거를 비민감 결과로
기록한 뒤 ignored `infra/keycloak/.local/` 전체를 삭제할 수 있다. 실행 중인 container가 해당
파일을 참조하지 않는지 먼저 확인한다.

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

위 2절의 local secret·TLS artifact를 준비한 뒤 repository root에서 실행한다. project 이름은
이번 실행 전용이어야 하며 아래 명령 하나가 static, fresh, existing-volume restart, host TLS,
단계별 ingestion과 cleanup을 수행한다.

```bash
python -B infra/keycloak/verify_e2e.py all \
  --repo-root . \
  --project finguardops-kc241-e2e-manual
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

정상·실패 종료 모두 exact Compose project label을 가진 전용 container, network, volume과 전용
browser container만 대상으로 삼고 네 종류의 잔존이 0인지 확인한다. 공용 local
Docker image는 자동 삭제하지 않으며 잔존 여부를 cleanup 실패로 분류하지 않는다. ignored
credential·TLS artifact는 자동 삭제하지 않는다. 필요하면 위 7·8절의 OWNER 확인 절차로만
별도 정리한다.
