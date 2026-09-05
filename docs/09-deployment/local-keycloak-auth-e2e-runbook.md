# Local Keycloak 인증 E2E Runbook

## 1. 목적과 경계

이 절차는 stock Keycloak 26.7.3으로 FinGuardOps local/dev SERVICE Client Credentials 발급,
Backend 인증·인가 호환성, 외부 USER password 주입과 USER credential desired state, 실제 USER
Authorization Code + PKCE 브라우저 로그인을 검증한다. production secret 보관·HA·외부 공개 배포
절차가 아니다. USER public client의 `use.refresh.tokens=false`는 realm import와 bootstrap reconcile
양쪽에 적용하며, Playwright는 실제 token response의 refresh token 부재와 Frontend의 합성
`refresh_token` fail-closed를 함께 검사한다. role UI, remote logout과 production Authorization Server는
아직 구현하지 않았다.

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

### 3.1 Windows CurrentUser 신뢰와 브라우저 E2E

repository root의 PowerShell에서 dependency와 Chromium을 먼저 설치한다. 실제 credential은 명령
인자나 환경 변수로 전달하지 않는다.

```powershell
Set-Location frontend
npm ci
npx playwright install chromium
Set-Location ..
.\frontend\scripts\run-keycloak-e2e.ps1
```

runner는 import 전에 physical certificate 경로와 `CA:FALSE`, exact Key Usage,
`serverAuth`, exact `DNS:localhost`, RSA 3072 이상, SHA-256 이상, 현재 유효성과 최대 30일을 검증한다.
검증에 실패하면 저장소를 열기 전에 종료한다. 접근하는 저장소는 현재 사용자 Root 하나뿐이며 관리자
권한, TLS 오류 우회 flag, subject·CN·wildcard 검색을 사용하지 않는다. exact certificate byte가 이미
있으면 재등록하지 않고 실행 종료 때도 삭제하지 않는다. 이번 실행이 추가한 exact certificate만
비대화식 Windows `Import-Certificate` API로 등록하고 `finally`에서 제거한다. 일반 경고창 클릭,
SendKeys와 UI automation은 사용하지 않는다.

cleanup marker는 OS 임시 경로 아래의 고정된 regular file만 허용하며 marker와 부모 경로에
symlink·junction·reparse point가 있으면 거부한다. marker에는 이번 실행의 식별자, exact thumbprint와
repository certificate DER의 SHA-256을 기록한다. 정상 종료와 cleanup-only 모두 현재 repository의
`localhost.crt`를 다시 전체 안전성 검증하고 marker 값과 결속한 뒤, CurrentUser Root에서 thumbprint로
찾은 집합과 DER exact 집합이 같은 단일 인증서일 때만 삭제한다. marker 손상·변조, certificate rotation,
DER 불일치와 중복은 인증서를 건드리지 않고 fail-closed한다. 삭제 후 exact DER가 0개인지 재검증하고
marker 내용이 cleanup 도중 바뀌지 않았을 때만 marker를 삭제한다.

비정상 종료로 임시 cleanup marker가 남으면 다음 명령이 위 계약으로 이번 실행이 추가한 exact
certificate와 전용 Compose project만 정리한다. `.local` certificate·private key·password와 다른
certificate·Docker resource는 삭제하지 않는다.

```powershell
.\frontend\scripts\run-keycloak-e2e.ps1 -Mode Cleanup
```

runner가 실행하는 npm 명령은 `npm run e2e:keycloak` 하나로 고정된다. Playwright는 Chromium,
`workers=1`, `retries=0`, strict TLS를 사용하고 trace·screenshot·video·HTML report를 만들지 않는다.
output directory는 OS 임시 경로에 생성하고 종료 시 삭제한다. 실제 USER password는 ignored file에서
process memory로만 읽는다.

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
