# FinGuardOps local infrastructure

`compose.yml`은 기본 애플리케이션·관측 stack이다. 인증 공급자는 기본 stack에 자동 포함하지
않으며 목적에 맞는 overlay 하나만 명시적으로 결합한다.

| Overlay | 목적 | Backend issuer |
| --- | --- | --- |
| `compose.local-jwt-e2e.yml` | 결정적인 JWT/JWK 장애·회귀 fixture | fixture 전용 issuer |
| `compose.keycloak-local-e2e.yml` | stock Keycloak 26.7.3 local/dev 발급·호환 검증 | `https://localhost:8443/realms/finguardops-local` |

두 overlay를 한 Backend에 동시에 결합하거나 신뢰하지 않는다. 저장소 공식 절차는 merged
configuration을 먼저 검사해 이를 차단하지만 verifier를 우회한 임의의 raw Compose 명령까지
차단한다고 보장하지 않는다.

Keycloak은 Backend namespace를 공유하고 stock image의 HTTP·HTTPS 공통 listener host 제약 때문에
`KC_HTTP_HOST=0.0.0.0`을 사용한다. Backend는 기존 non-internal Compose network에도 참여해 public
HTTPS 8443만 host `127.0.0.1`에 publish한다. 8082·9000은 publish하지 않으며 Backend와 승인 helper는
JWK/Admin/management 요청에 namespace loopback URI를 사용한다. 같은 local/dev Docker network의
participant는 operator 신뢰 경계이며 이 구성을 production network나 credential 전달 방식으로
사용하지 않는다.

Keycloak 실행, TLS·secret 생성, fresh/existing volume 검증과 제한된 clean reset은
[`local-keycloak-auth-e2e-runbook.md`](../docs/09-deployment/local-keycloak-auth-e2e-runbook.md)를
따른다. 생성되는 certificate, private key, admin·SERVICE secret, USER password와 검사 config는
모두 ignored `keycloak/.local/`에만 둔다. 실제 credential을 환경 파일, 명령 인자, 로그 또는
문서에 복사하지 않는다.

별도 proxy/service/image와 helper 공유 volume은 없다. 이 overlay가 추가하는 persistent named
volume은 Keycloak runtime signing key와 dev-file DB를 위한 `keycloak-data` 하나뿐이다.

USER password는 bootstrap helper에만 Compose secret `user_password`로 read-only mount한다.
Keycloak server, SERVICE client와 verifier에는 이 secret을 mount하지 않는다. USER public client는
Authorization Code + PKCE S256만 유지하고 `use.refresh.tokens=false`, direct grant·implicit flow
비활성화, `offline_access` 미부여를 exact desired state로 reconcile한다. Phase 1은 browser login,
Frontend callback, Playwright와 Windows CurrentUser Root 인증서 import를 수행하거나 완료로 보지 않는다.

Issue #239 Phase 3의 [`run-keycloak-e2e.ps1`](../frontend/scripts/run-keycloak-e2e.ps1)은 Phase 1
artifact를 변경하지 않고 exact localhost leaf를 Windows 현재 사용자 Root에 한시적으로 신뢰시킨다.
전용 Compose project와 실제 Chromium으로 USER Authorization Code + PKCE, refresh-token fail-closed,
token claim과 Backend 200·401·403을 검증한 뒤 자신이 추가한 exact certificate와 전용 Docker resource,
임시 Playwright output만 정리한다. pre-existing exact certificate와 `.local` artifact는 유지한다.

Issue #241 SERVICE ingestion 검증은 별도 경계다. `python -B infra/keycloak/verify_e2e.py all`이
실제 두 SERVICE token으로 거래·행동 신규/재생/충돌과 401·403을 호출하고, PostgreSQL row
global delta·거래별 cardinality, External Risk 고정 marker와 Rule v2 exact Uvicorn access line이
최초 거래에서만 각각 1회 증가하는지 검사한다. Backend outcome metric은 실제 hit와 분리해
보조 검증한다. fresh와
동일 `keycloak-data` existing-volume 재실행 후 전용 project의 container·network·volume을 모두
정리한다. 이 명령은 USER password를 verifier·Backend·AI Service에 전달하지 않고 Windows 인증서
저장소, Chromium, Playwright와 Frontend production 파일을 사용하지 않는다. 공용 local Docker
image는 자동 삭제하거나 cleanup 실패 대상으로 분류하지 않는다.

Keycloak wrapper는 외부 argument를 받지 않고 내부의 exact `kc.sh start --import-realm` 배열만
`exec`한다. Static verifier는 merged long-syntax mount, entrypoint/command, 승인된 `KC_*`, privilege,
capability, Docker socket과 Backend dependency를 exact 검사한다.

secret 생성기는 fresh 상태에서 4개를 만들며, 기존 admin·SERVICE 3개가 모두 유효하고 USER password만
없을 때에는 기존 값을 유지하고 USER password만 추가한다. 그 밖의 partial/existing 상태는 거부한다.
TLS 생성기는 RSA 3072/SHA-256, 최대 30일, `CA:FALSE` localhost leaf를 만들며 기존 certificate/key를
overwrite하지 않는다. 두 생성기는 physical `infra/keycloak` 아래의 exact `.local/secrets`와 `.local/tls`
만 사용하며 `.local`, output directory 또는 artifact symlink를 거부한다. Git Bash가 Windows junction을
physical target으로 resolve할 수 있는 환경에서는 approved root 밖 junction도 거부한다. 동시에 filesystem을 바꾸는
악의적인 local operator에 대한 완전한 TOCTOU 방어는 보장하지 않으며 해당 주체는 local trust
boundary 밖이다.
