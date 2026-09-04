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
따른다. 생성되는 certificate, private key, client secret, completion state와 검사 config는
모두 ignored `keycloak/.local/`에만 둔다. 실제 credential을 환경 파일, 명령 인자, 로그 또는
문서에 복사하지 않는다.

별도 proxy/service/image와 helper 공유 volume은 없다. 이 overlay가 추가하는 persistent named
volume은 Keycloak runtime signing key와 dev-file DB를 위한 `keycloak-data` 하나뿐이다.
2026-09-05 fresh/existing runtime, verifier 5회 연속 실행과 host public HTTPS·비공개 port 검증이
모두 통과했다.

Keycloak wrapper는 외부 argument를 받지 않고 내부의 exact `kc.sh start --import-realm` 배열만
`exec`한다. Static verifier는 merged long-syntax mount, entrypoint/command, 승인된 `KC_*`, privilege,
capability, Docker socket과 Backend dependency를 exact 검사한다.

생성기는 physical `infra/keycloak` 아래의 exact `.local/secrets`와 `.local/tls`만 사용하며 `.local`,
output directory 또는 artifact symlink를 거부한다. Git Bash가 Windows junction을 physical target으로
resolve할 수 있는 환경에서는 approved root 밖 junction도 거부한다. 동시에 filesystem을 바꾸는
악의적인 local operator에 대한 완전한 TOCTOU 방어는 보장하지 않으며 해당 주체는 local trust
boundary 밖이다.
