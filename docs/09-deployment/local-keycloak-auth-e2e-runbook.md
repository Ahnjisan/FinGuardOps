# Local Keycloak 인증 E2E Runbook

## 1. 목적과 경계

이 절차는 stock Keycloak 26.7.3으로 FinGuardOps local/dev SERVICE Client Credentials 발급과
Backend 인증·인가 호환성을 검증한다. production secret 보관·HA·외부 공개 배포 절차가 아니다.
USER resource와 public client 구성은 reconcile하지만 USER password를 만들지 않으므로 실제
browser login, Authorization Code callback, access/ID token `sub` 비교, refresh-token
fail-closed와 remote logout은 검증하지 않는다.

Keycloak overlay와 Local JWT fixture overlay는 별개다. 한 명령에 둘을 결합하거나 한 Backend가
두 issuer를 동시에 신뢰하는 구성은 지원하지 않는다. 공식 절차는 stack 생성 전 static
verifier로 동시 사용과 issuer/JWK 혼합을 거부한다. Compose 문법 자체가 verifier를 우회한 모든
raw 명령을 막는 것은 아니다.

## 2. 사전 요구사항

- Docker Desktop과 Compose v2
- Windows Git Bash의 Bash와 OpenSSL
- host Python 3.12(사전 static verifier와 unit test용)
- `infra/.env`의 기존 Compose 필수 변수. 실제 값은 Git에 추가하지 않는다.
- host의 `127.0.0.1:8443`이 비어 있어야 한다.

PowerShell이 아니라 repository root의 Git Bash에서 생성기를 실행한다.

```bash
bash infra/keycloak/setup-local-secrets.sh
bash infra/keycloak/setup-local-tls.sh
```

두 script는 기존 대상이 하나라도 있으면 overwrite하지 않고 실패한다. 생성물은
`infra/keycloak/.local/` 아래에만 있고 전체 디렉터리가 ignore된다. Windows NTFS/Docker
Desktop bind mount에서는 POSIX mode가 완전히 보장되지 않을 수 있다. 따라서 mode 표기만
신뢰하지 않고 container UID의 실읽기, 내용 계약과 read-only mount를 runtime에서 확인한다.
두 생성기는 script의 physical directory와 exact `.local/secrets`·`.local/tls` child를 검증하고
`.local`, output directory와 artifact symlink를 거부한다. Git Bash `realpath`가 Windows junction을
physical target으로 해석하는 환경에서는 approved root 밖 junction도 거부한다. 동시에 경로를
교체하는 악의적인 local operator에 대한 완전한 TOCTOU 방어는 보장하지 않는다.

## 3. 인증서 신뢰

public discovery는 `https://localhost:8443`을 사용하며 verifier는
`ssl.create_default_context(cafile="/run/secrets/keycloak_tls_certificate")`로 생성 certificate만
명시적으로 신뢰한다. SAN의 exact `DNS:localhost`, PEM, 유효기간과 certificate/private-key
일치 검증을 우회하지 않는다. 브라우저 수동 확인이 필요하면 OS 또는 브라우저의 local trust
store에 `infra/keycloak/.local/tls/localhost.crt`만 가져온다. private key는 가져오지 않는다.
작업 후에는 해당 localhost certificate를 같은 trust store에서 제거한다.

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

Keycloak service의 Compose `command`는 image 기본 CMD를 제거하는 explicit empty list다. Wrapper는
외부 argument가 하나라도 있으면 secret을 읽거나 Keycloak을 실행하기 전에 고정
`ARGUMENTS_NOT_ALLOWED`로 종료하며, 정상 PID 1 command는 `kc.sh start --import-realm`만 포함한다.

Bootstrap은 Admin API의 두 service-account UUID v4와 자신이 메모리에서 발급한 token `sub`를
원문 exact 비교하고 어떤 ID/token/report도 verifier에 전달하지 않는다. Container verifier와
별도로 host mode는 local certificate CA, TLS hostname, redirect 없음, HTTP 200, exact issuer와
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
name/clientId로 재조회하고 role/client/scope/mapper duplicate를 거부하며 외부 SERVICE secret을
같은 값으로 다시 적용한다.

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

SERVICE/admin secret 또는 TLS를 rotation하려면 stack을 먼저 중지하고 exact 대상 세 secret 또는
두 TLS 파일만 안전하게 폐기한 뒤 생성 script를 다시 실행한다. 새 credential을 terminal에
출력하지 말고 Keycloak과 helper를 재생성해 bootstrap/verifier를 다시 통과시킨다. 이전 secret의
교차 사용은 실패해야 한다. USER password는 이 절차에 없다.

검증이 끝나면 trust store에서 localhost certificate를 제거하고, 필요한 증거를 비민감 결과로
기록한 뒤 ignored `infra/keycloak/.local/` 전체를 삭제할 수 있다. 실행 중인 container가 해당
파일을 참조하지 않는지 먼저 확인한다.

## 9. 신뢰 경계와 troubleshooting

- Docker 관리자 권한 보유자는 mount source와 Keycloak PID 1 child environment를 볼 수 있다.
  bootstrap admin secret은 정적 `Config.Env`나 argv에 없지만 Keycloak process environment에는
  일시적으로 존재한다. 이는 local operator 신뢰 경계이며 production secret 보관 방식이 아니다.
- bootstrap은 public discovery가 아니라 management readiness 후 실행한다. Bootstrap과 verifier는
  파일·state·UUID·token·JSON report를 공유하지 않고 Compose 성공 dependency만 사용한다.
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
