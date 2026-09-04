# ADR-011: Keycloak Authorization Server와 권한 Claim 계약

- 상태: Accepted
- 결정일: 2026-09-04
- 결정자: Architecture Owner
- 관련 Issue: `#233 [Security/Architecture] Keycloak Authorization Server와 권한 Claim 계약 확정`
- 선행 결정:
  - [`ADR-008`](ADR-008-oauth2-resource-server-rbac-user-audit-actor.md)
  - [`ADR-009`](ADR-009-frontend-oidc-pkce-memory-token-boundary.md)
  - [`ADR-010`](ADR-010-frontend-authenticated-backend-api-boundary.md)
- 관련 문서:
  - [`security-architecture.md`](../02-architecture/security-architecture.md)
  - [`frontend/README.md`](../../frontend/README.md)
  - [`Local JWT 인증 E2E runbook`](../09-deployment/local-jwt-auth-e2e-runbook.md)

## 1. 배경과 문제

ADR-008은 Backend를 제품 중립적인 OAuth2 Resource Server로 두고 RS256 JWT,
USER·SERVICE principal, role·authority와 Audit actor 계약을 확정했다. ADR-009는 Frontend의
Authorization Code + PKCE, memory-only token과 local logout 경계를, ADR-010은 승인된 Backend
USER endpoint에만 access token을 전달하는 transport와 401·403 경계를 확정했다.

Issue #225의 ephemeral Local JWT fixture는 Backend 보안 회귀와 JWK rotation·장애 검증을 위한
local/manual test fixture다. 사용자 로그인, OIDC session, Authorization endpoint와 remote
logout을 제공하는 Authorization Server가 아니다.

거래·사건 업무 화면과 role·authority UI를 구현하려면 local/dev에서 사용할 실제
Authorization Server 제품, USER와 SERVICE의 발급 flow, Backend access token claim과 Frontend
표시용 ID token claim의 관계를 먼저 확정해야 한다. 이 결정은 선행 ADR의 역사적 결정문을
바꾸지 않고 그 제품 중립 계약을 Keycloak이 어떻게 충족해야 하는지 정한다.

## 2. 결정

### 2.1 제품 선택과 적용 범위

- FinGuardOps의 **local/dev Authorization Server로 Keycloak을 선택**한다.
- Keycloak runtime, realm, 사용자, client, role, client scope와 protocol mapper는 아직
  구현되지 않았다.
- 후속 구현 Issue에서 검증된 Keycloak 26.x의 exact image tag와 digest를 함께 고정한다.
  `latest` tag는 사용하지 않는다. 이 ADR은 검증 전 tag나 digest를 미리 정하지 않는다.
- ADR-008의 issuer·audience·claim·role·authority 계약은 제품 중립적인 목표 계약으로
  유지된다. Keycloak은 이 계약의 local/dev 공급자이며 Backend validator를 Keycloak 기본
  token 형태에 맞춰 완화하지 않는다.
- production AWS의 Authorization Server와 배포 방식은 Kubernetes·AWS 배포 Issue에서 별도로
  결정한다. production에서 Cognito 등 다른 제품으로 변경하려면 호환성 검증과 별도 ADR이
  필요하다.

Keycloak은 표준 OIDC flow, public·confidential client 분리, client scope와 protocol mapper,
로컬에서 재현 가능한 container 실행과 JWK rotation 검증 경계를 제공한다. 따라서 이미
구현된 Frontend OIDC 경계와 Backend Resource Server 계약을 local/dev에서 연결하기에 적합하다.

### 2.2 Cognito를 현재 선택하지 않는 이유

AWS Cognito는 production AWS 후보에서 제외하지 않는다. 다만 현재 단계는 local/dev E2E와
결정적인 재현이 우선이며, Cognito를 선택하면 AWS 계정·리전·원격 서비스·credential과 배포
상태에 개발·검증이 결합된다. client·claim mapping과 key rotation 장애를 저장소의 local
환경에서 독립적으로 재현하기도 어렵다. production AWS 운영 모델, 비용, HA, 조직 SSO와
계정 수명주기가 아직 확정되지 않았으므로 지금 Cognito를 선택하지 않는다.

### 2.3 Frontend USER public client 계약

- Browser SPA는 client authentication이 없는 **public OIDC client**다. Frontend에 client
  secret을 발급하거나 제공하지 않는다.
- Authorization Code Flow만 허용하고 PKCE는 `S256`만 허용한다.
- implicit flow와 Resource Owner Password Credentials flow(Keycloak direct access grants)는
  금지한다.
- `openid`를 사용하되 `offline_access` 요청과 offline token 사용은 금지한다. 일반 온라인
  refresh token은 offline token과 별개의 credential이며 `offline_access` 없이도 token response에
  반환될 수 있다고 가정한다. Frontend는 이를 보관하거나 인증 갱신에 사용하지 않으며, 구체적인
  fail-closed adapter 구현과 검증은 후속 runtime Issue 범위다.
- redirect URI와 post-logout redirect URI는 환경별 exact allowlist로 제한한다. wildcard
  redirect URI를 사용하지 않는다.
- 기존 ADR-009의 top-level redirect, state·nonce·PKCE 검증, memory-only token, 최대 15분
  Frontend hard session deadline과 local logout 계약을 유지한다.

### 2.4 SERVICE confidential client 계약

거래 접수와 행동 이벤트 접수는 USER client 및 서로에게서 분리된 두 confidential client를
사용한다. 두 client 모두 client authentication과 service account를 사용하고 **Client
Credentials Flow만 허용**한다. Authorization Code, implicit, password grant와 USER interactive
login은 허용하지 않는다.

| 용도 | `principal_type` | exact `roles` | Backend authority |
| --- | --- | --- | --- |
| Transaction ingestion client | `SERVICE` | `["TRANSACTION_INGESTOR"]` | `transaction:intake` |
| Behavior ingestion client | `SERVICE` | `["BEHAVIOR_INGESTOR"]` | `behavior-event:intake` |

한 SERVICE access token에는 USER role이나 다른 ingestion role을 혼합하지 않는다. client ID의
exact 이름, credential 공급·rotation 절차와 배포 주체는 후속 구현 Issue에서 고정한다.

### 2.5 Backend access token exact claim 계약

Backend는 USER와 SERVICE의 **access token만** API credential로 받는다. ID token은 Backend
API credential로 받지 않는다. Keycloak access token은 ADR-008과 현재 Backend validator의
다음 exact 계약을 그대로 충족해야 한다.

| 위치·Claim | 타입·필수 | exact 계약 |
| --- | --- | --- |
| JWT header `alg` | string, 필수 | 정확히 `RS256` |
| JWT header `kid` | string, 필수 | 게시된 JWK를 식별하며 재사용 금지 |
| `iss` | JSON string, 필수 | 실행 환경에 승인된 Keycloak issuer와 exact match |
| `aud` | JSON string array, 필수 | 정확히 `["finguardops-backend-api"]`; 단일 string이나 추가 audience 금지 |
| `sub` | JSON string, 필수 | USER·SERVICE 모두 canonical lowercase UUID v4 |
| `principal_type` | JSON string, 필수 | 정확히 `USER` 또는 `SERVICE` |
| `roles` | JSON string array, 필수 | 중복 없는 알려진 FinGuardOps role만 허용 |
| `iat` | NumericDate, 필수 | Backend의 60초 future clock-skew 검증 대상 |
| `exp` | NumericDate, 필수 | `exp - iat <= 15분`이고 만료 전이어야 함 |
| `nbf` | NumericDate, 선택 | 존재하면 Backend의 60초 clock-skew 검증 대상 |

USER role은 `FDS_VIEWER`, `FDS_ANALYST`, `FDS_APPROVER`, `RULE_OPERATOR`,
`RECOVERY_OPERATOR`, `PLATFORM_ADMIN`만 허용한다. SERVICE role은
`TRANSACTION_INGESTOR`, `BEHAVIOR_INGESTOR`만 허용한다. USER와 SERVICE role을 혼합하거나
unknown·duplicate·wrong-type role을 넣지 않는다. token의 `authorities`, `jku`, `x5u`는
권한 또는 key source로 신뢰하지 않는다.

Keycloak 기본 claim이 이 계약과 다르더라도 Backend의 RS256·issuer·audience·UUID subject·
principal type·role·time validator를 완화하지 않는다. mapper 구성 또는 identity provisioning이
계약을 만족하지 못하면 발급·E2E 구성을 실패로 처리한다.

### 2.6 USER ID token subject·role 공급 계약

Keycloak USER ID token에는 `principal_type=USER`와 같은 인증 session의 USER access token에
공급한 것과 중복 없는 동일한 FinGuardOps role 집합을 `roles` JSON string array로 제공한다.
배열 원소 순서는 의미가 없으며 순서가 달라도 집합이 같으면 계약을 만족한다. 두 token 모두
USER role allowlist, unknown·duplicate 금지, USER·SERVICE role 혼합 금지와 Keycloak 내부 role
제외 규칙을 동일하게 적용한다. canonical role order는 별도로 정의하지 않는다.

동일 USER session의 access token `sub`와 ID token `sub`는 원문 기준으로 완전히 동일해야 하며,
두 값 모두 canonical lowercase UUID v4 문자열이어야 한다. trim, lowercase 변환, normalization,
재직렬화 등으로 서로 다른 값을 보정해 일치시키지 않는다. 불일치는 provisioning 또는 E2E
검증 실패로 처리한다. 이는 Frontend가 표시하는 사용자와 Backend가 authorization 및 Audit
actor로 사용하는 사용자가 동일한 subject임을 보장한다. SERVICE Client Credentials Flow에는
이 ID token UI 계약을 적용하지 않으며 ID token을 요구하지 않는다.

Frontend는 access token을 직접 decode하지 않는다. 기존 OIDC client가 서명·issuer·audience·
nonce 등 OIDC protocol 검증을 마치고 게시한 session profile의 `principal_type`과 `roles`만
navigation·button·action 노출 같은 UI 표시에 사용할 수 있다. ID token 원문이나 전체 claim을
Context·DOM·Web Storage·로그에 게시하지 않는다.

### 2.7 Protocol mapper와 client scope 책임

후속 Keycloak 구현은 FinGuardOps 전용 client scope와 명시적인 protocol mapper로 claim을
공급한다. 책임은 다음과 같이 분리한다.

- Backend access token의 `aud`는 JSON string이 아니라 JSON string array이며 전체 배열이 정확히
  `["finguardops-backend-api"]`여야 한다. USER client와 두 SERVICE client 모두 추가 audience를
  하나도 허용하지 않는다.
- Audience mapper는 기존 audience를 대체하지 않고 추가할 수 있으므로 hardcoded audience mapper
  하나만으로 singleton을 보장했다고 간주하지 않는다. 기본 `roles` client scope의 Audience
  Resolve mapper를 포함해 `account`와 그 밖의 audience를 추가할 수 있는 모든 source를 제거,
  비활성화하거나 명시적으로 통제한다.
- USER용 mapper는 USER access token과 ID token에 `principal_type=USER`를 넣고, 허용된
  FinGuardOps USER role만 `roles` 배열로 투영한다.
- 각 SERVICE client용 mapper는 access token에 `principal_type=SERVICE`와 그 client 하나에
  허용된 ingestion role 하나만 넣는다.
- subject provisioning과 mapper 결과는 USER·SERVICE 모두 canonical lowercase UUID v4 `sub`
  계약을 만족해야 한다.
- mapper와 client scope는 필요한 token type에만 claim을 넣으며 범용 realm role 전체를
  그대로 복사하지 않는다.

realm·client·client scope·mapper의 exact 이름과 구체적인 realm JSON 설정은 검증된 Keycloak
26.x exact tag·digest와 함께 runtime 구현 Issue에서 확정하고 검증한다. 이 ADR은 realm JSON을
만들거나 구현 완료를 선언하지 않는다.

### 2.8 Keycloak 내부 role 필터링

FinGuardOps `roles` claim은 전용 allowlist의 결과만 포함한다. `realm_access.roles`,
`resource_access.*.roles` 또는 Keycloak role 전체를 포괄적으로 복사해 만들지 않는다. 다음은
USER·SERVICE access token과 USER ID token의 FinGuardOps `roles`에서 제외한다.

- `offline_access`
- `uma_authorization`
- `default-roles-*`
- 그 밖의 Keycloak 기본·관리·내부 role

Backend는 여전히 unknown role을 401로 거부한다. 공급자 필터링은 Backend 검증을 대체하지
않으며, Backend 검증도 mapper의 과다 노출을 정당화하지 않는다.

### 2.9 Frontend UI와 Backend authorization 신뢰 경계

Frontend role은 표시와 action 노출을 위한 UX 정보이며 보안 경계가 아니다. UI가 버튼·route를
숨겨도 Backend의 endpoint·method authority 검증을 대체하지 않는다. UI role이 오래되거나
변조되어도 Backend가 access token을 독립적으로 검증해 반환하는 401·403이 최종 결정이다.

- Frontend는 ID token을 Backend에 API credential로 보내지 않는다.
- Backend는 access token만 검증하고 ID token이나 Frontend의 권한 판단을 신뢰하지 않는다.
- 401은 ADR-010의 session-bound local invalidation을, 403은 session 유지 계약을 따른다.
- role UI는 아직 구현되지 않았으며 후속 Issue 전에는 이 계약을 구현 완료로 표현하지 않는다.

### 2.10 Local JWT fixture와 Keycloak profile·issuer 분리

Issue #225 fixture는 Backend validator 회귀, deterministic identity, key rotation과 장애 주입을
위한 local/manual test fixture로 유지한다. Keycloak이나 production Authorization Server로
승격하거나 브라우저 OIDC Provider로 확장하지 않는다.

Keycloak local E2E와 기존 fixture E2E는 실행 profile 또는 Compose overlay와 Backend issuer·
JWK 설정을 분리한다. 하나의 Backend 실행에는 승인된 issuer와 JWK URI 한 쌍만 설정하며,
fixture issuer와 Keycloak issuer를 동시에 신뢰하지 않는다. 두 공급자를 같은 Backend issuer
설정에서 동시에 사용하지 않는다. exact profile·overlay 이름은 후속 구현 Issue에서 정한다.

### 2.11 Token lifetime과 key rotation

- USER·SERVICE access token의 `exp - iat`는 최대 15분이다. 더 짧은 lifetime은 허용한다.
- `offline_access` 요청과 offline token 사용은 금지한다. 일반 온라인 refresh token은 offline
  token과 별개이고 `offline_access` 없이도 반환될 수 있으므로, provider 설정만으로 미발급을
  가정하지 않는다.
- Frontend는 일반 refresh token을 session credential로 보관하거나 인증 갱신에 사용하지 않는다.
  `automaticSilentRenew=false`를 유지하고 refresh token grant 호출과 silent renew는 각각 0회여야
  한다.
- 후속 runtime Frontend adapter는 실제 token response를 검사한다. `refresh_token`이 반환되면
  fail-closed로 해당 로그인 session을 게시하지 않고 OIDC user state를 제거한다. callback 처리가
  끝난 뒤 `User`, `AuthContext`, application state, OIDC user store, `localStorage`,
  `sessionStorage` 등 유지되는 저장 표면에 refresh token이 남아서는 안 된다.
- token response 검사 중 라이브러리 내부에 일시적으로 존재하는 값은 애플리케이션이 보관하는
  session credential과 구분한다. 일시적 값도 원문을 로그, 오류, DOM, React state 또는 관측
  데이터에 노출하지 않으며 callback 종료 후 유지하지 않는다. provider 설정뿐 아니라 Frontend
  adapter 경계에서 실제 반환값을 검증한다.
- RS256 signing key rotation 시 old/new public key를 최소 30분 함께 게시한다.
- 새 key는 새 `kid`를 사용하고 기존 `kid`를 재사용하지 않는다.
- Backend의 known-key cache 검증과 unknown-key refresh fail-closed 계약은 ADR-008을 유지한다.
- Keycloak rotation의 실제 운영 절차와 장애 E2E는 후속 runtime 구현 범위다.

### 2.12 Secret 비저장

USER public client에는 secret이 없다. SERVICE client secret, Keycloak 관리자 credential,
private signing key, 실제 사용자 password, access·ID·refresh token은 코드, realm JSON, 문서,
`.env.example`, 로그와 Git에 저장하지 않는다. 비밀이 아닌 변수명과 placeholder만 예시로
문서화할 수 있다. 실제 credential의 생성·주입·rotation·폐기는 후속 배포 계약에서 정한다.

### 2.13 Logout 후속 경계

현재 Frontend는 memory token과 transient transaction record를 제거하는 local logout을
유지한다. Keycloak remote end-session(RP-initiated logout), post-logout callback과 server-side
session 종료 연동은 후속 범위다. 구현 시 post-logout redirect URI는 환경별 exact allowlist를
사용하며 wildcard를 허용하지 않는다.

remote logout 실패는 local UI 무효화를 되돌리거나 Backend write retry·API replay를 발생시키지
않는다. refresh token, silent renew, session monitoring과 offline session은 별도 승인 없이
remote logout 구현에 함께 도입하지 않는다.

### 2.14 후속 runtime 인증 E2E 검증 계약

다음 검증은 production code나 runtime을 이번 문서 Issue에서 구현했다는 뜻이 아니다. 후속
Keycloak·Frontend runtime 구현 Issue는 실제 발급·callback 결과를 대상으로 다음 조건을
자동화해야 한다.

- `refresh_token`이 없는 정상 callback은 성공한다.
- `refresh_token`이 포함된 callback은 session 게시에 실패하고 OIDC user state를 제거한다. 실패
  후 유지되는 memory·user store·Web Storage의 refresh token은 0개다.
- refresh token grant 호출은 0회, silent renew는 0회다. refresh token 원문의 로그·오류·DOM·
  React state·관측 데이터 노출도 0회다.
- USER client와 Transaction·Behavior SERVICE client의 실제 발급 access token을 decode한 raw
  JSON에서 `aud` 타입이 array이고 전체 값이 정확히 `["finguardops-backend-api"]`인지 검사한다.
  검증 라이브러리가 가공한 audience 값만 비교하지 않는다.
- 같은 로그인에서 발급된 USER access token과 ID token payload의 `sub` 원문을 직접 비교한다.
  두 값의 완전한 동일성과 각 값의 canonical lowercase UUID v4 형식을 별도로 검사하며 값을
  정규화해 통과시키지 않는다.
- 같은 두 USER token의 `roles`는 JSON string array이고 각자 중복이 없으며 FinGuardOps USER role
  집합이 동일한지 검사한다. 배열 순서는 비교하지 않으며 unknown role과 USER·SERVICE role 혼합은
  실패로 처리한다.

singleton audience나 UUID subject 조건을 맞추기 위해 Backend validator를 완화하지 않는다.
이 refresh token fail-closed 정책과 위 E2E는 모두 후속 runtime Issue의 구현·검증 범위이며 현재
구현 완료 상태가 아니다.

## 3. 결과

### 3.1 장점

- 제품 중립 Backend 계약을 바꾸지 않고 local/dev에서 실제 OIDC 로그인과 SERVICE 발급을
  검증할 공급자를 정했다.
- public USER client와 confidential SERVICE client가 credential·flow·role 경계에서 분리된다.
- access token과 ID token의 용도, Frontend UI와 Backend authorization의 최종 신뢰 경계가
  명확해진다.
- 전용 allowlist mapper로 Keycloak 내부 role이 금융 권한 claim에 섞이는 것을 방지한다.
- local JWT fixture의 결정적 장애 검증 목적을 유지하면서 Keycloak issuer와의 충돌을 막는다.

### 3.2 비용과 제약

- Keycloak realm·client·mapper·key rotation과 local container 운영·E2E 책임이 추가된다.
- Keycloak 기본 token을 그대로 쓸 수 없고 exact audience·UUID subject·principal type·role
  mapper를 구성하고 검증해야 한다.
- Frontend 표시용 role과 Backend access token role의 일관성을 token별로 검증해야 한다.
- production AWS 제품과 운영 모델은 여전히 별도 결정이 필요하다.
- remote logout과 장기 browser session은 아직 제공하지 않는다.

## 4. 검토한 대안

### 4.1 AWS Cognito를 local/dev와 production에 즉시 채택

현재 선택하지 않았다. AWS 의존성과 원격 상태가 local 재현성·장애 주입·독립 E2E를 제한하고,
production 운영 요구가 확정되지 않았다. 향후 production 후보로는 유지한다.

### 4.2 Backend가 Authorization Server 역할 수행

선택하지 않았다. credential·password·token 발급·refresh·revoke·logout과 signing key 운영을
금융 업무 정합성의 최종 소유자인 Spring Backend에 결합해 ADR-008의 책임 분리를 위반한다.

### 4.3 Local JWT fixture를 OIDC Provider로 확장

선택하지 않았다. 결정적 Backend 검증 fixture에 사용자 로그인·session·client·logout 책임을
추가하면 test fixture와 Authorization Server의 경계가 사라진다.

### 4.4 다른 범용 OIDC Provider 또는 제품 미선정 유지

표준 호환성은 가능하지만 local/dev 구현의 구체적인 realm·client·mapper 검증을 시작할 제품
결정이 계속 미뤄진다. 현재 요구에 필요한 local 재현성과 mapper 통제를 기준으로 Keycloak을
선택한다.

## 5. 구현되지 않은 범위

이 ADR은 문서 계약만 확정한다. 다음은 구현하지 않았다.

- Keycloak container, exact 26.x image tag·digest와 Docker Compose 변경
- realm import JSON, realm·사용자·role·client·client scope·protocol mapper 생성
- 실제 client secret·관리자 credential·사용자 password·signing key와 token 저장
- Frontend 코드·설정 변경과 Keycloak 연동 E2E
- Backend 코드·설정·validator 변경과 Keycloak 연동 E2E
- Infra runtime, GitHub Actions, Kubernetes·AWS와 Cognito 구성
- role·authority 기반 navigation·button·route guard UI와 업무 화면
- remote logout, refresh token, silent renew, session monitoring과 offline session
- API·DB·dependency 변경

## 6. 후속 Issue 순서

1. Keycloak local/dev Compose·realm·client·role·client scope·protocol mapper 구현
2. Frontend OIDC와 Spring Backend를 연결한 USER 로그인 E2E
3. SERVICE Client Credentials 기반 거래·행동 이벤트 접수 E2E
4. Frontend role·authority UI 계약과 구현
5. 거래·사건·메모·감사 typed API module과 query pagination
6. Keycloak remote logout 계약과 구현

각 후속 Issue는 구현 당시 검증된 Keycloak 버전, exact URI·client 설정, secret 공급 경계와
실행 증거를 별도로 확정한다.
