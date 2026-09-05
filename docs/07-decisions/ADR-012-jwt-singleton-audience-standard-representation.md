# ADR-012: JWT singleton audience 표준 표현 호환

- 상태: Accepted
- 결정일: 2026-09-04
- 결정자: Architecture Owner
- 관련 Issue: `#236 [Backend/Security] JWT singleton audience 표준 표현 호환`
- 선행 결정:
  - [`ADR-008`](ADR-008-oauth2-resource-server-rbac-user-audit-actor.md)
  - [`ADR-011`](ADR-011-keycloak-authorization-server-and-claim-contract.md)
- 관련 구현 선행 작업: `#235 [Infra/Security] Keycloak local/dev runtime 구현`

## 1. 배경

Issue #235 사전점검에서 stock Keycloak 26.7.3이 논리적으로 audience가 하나인 access token의
`aud`를 JSON string으로 직렬화하는 반면, 당시 Backend raw JWT 경계는 JSON string array만
허용한다는 충돌을 확인했다. 이 충돌 때문에 다른 보안 계약을 만족하는 stock Keycloak token도
서명과 JWK 검증 전에 401로 거부됐다.

Keycloak 26.7.3 source에서 token audience는 내부적으로 `String[]`로 관리되고 Audience protocol
mapper는 `addAudience`로 값을 추가한다. JSON 직렬화에는 `StringOrArraySerializer`가 사용되며,
원소가 하나이면 string, 둘 이상이면 array로 기록한다. 따라서 built-in mapper만으로 singleton
array 표현을 강제하지 않고 JSON string으로 발급되는 것은 정상 동작이다.

- [Keycloak 26.7.3 `JsonWebToken`](https://github.com/keycloak/keycloak/blob/26.7.3/core/src/main/java/org/keycloak/representations/JsonWebToken.java)
- [Keycloak 26.7.3 `AudienceProtocolMapper`](https://github.com/keycloak/keycloak/blob/26.7.3/services/src/main/java/org/keycloak/protocol/oidc/mappers/AudienceProtocolMapper.java)
- [Keycloak 26.7.3 `StringOrArraySerializer`](https://github.com/keycloak/keycloak/blob/26.7.3/core/src/main/java/org/keycloak/json/StringOrArraySerializer.java)

RFC 7519 Section 4.1.3은 일반적인 경우 `aud`를 string array로 표현하고, audience가 하나일 때는
단일 JSON string으로 표현할 수 있도록 허용한다. 두 raw 표현은 동일한 singleton audience
의미다.

- [RFC 7519 Section 4.1.3](https://www.rfc-editor.org/rfc/rfc7519#section-4.1.3)

## 2. 결정

논리적으로 승인하는 Backend recipient는 계속 정확히 하나인
`finguardops-backend-api`다. Backend는 raw JWT JSON에서 다음 두 표현만 허용한다.

- exact JSON string: `"finguardops-backend-api"`
- exact singleton JSON string array: `["finguardops-backend-api"]`

string을 허용하는 것은 recipient 집합을 넓히는 보안 완화가 아니라 RFC 7519의 동등한 singleton
표현을 수용하는 호환성 보정이다. 추가 audience, 순서를 바꾼 추가 audience, duplicate audience,
빈 값, null, 다른 값, 숫자·boolean·object, 문자열이 아닌 원소, mixed-type 배열과 중첩 배열은
모두 거부한다. trim, 대소문자 변환, coercion, normalization 또는 raw claim 재작성으로 값을
일치시키지 않는다.

## 3. 검증 경계

Backend는 두 경계를 유지한다.

1. Nimbus/JWK 처리 전 raw JSON shape 검증은 `aud`가 승인된 exact string 또는 exact singleton
   string array인지 확인한다. 실패 메시지에는 token, claim map이나 공격자 입력을 포함하지 않는다.
2. Nimbus가 audience를 `List<String>`으로 변환한 뒤 claim converter는 `aud`의 List 타입을 다시
   확인하고, 최종 semantic validator는 전체 List가 승인 audience 하나와 정확히 같은지 확인한다.

따라서 raw shape 검증과 normalized semantic 검증은 서로 대체하지 않는다. 최종 검증에는
`contains`, Set equality 또는 normalization을 사용하지 않으며 additional·duplicate audience를
계속 차단한다.

## 4. 유지되는 계약

이번 결정은 RS256, nonblank `kid`, `jku`·`x5u` 거부, exact issuer와 승인 JWK URI, canonical
lowercase UUID v4 subject, USER·SERVICE principal 분리, role allowlist와 중복·혼합 거부,
`iat`·`exp`·`nbf` 및 최대 15분 lifetime, 401·403·503·500 분류를 변경하지 않는다.

Local JWT fixture는 fixture 고유의 기존 singleton array 발급을 유지해도 된다. Backend의 일반
허용 계약과 fixture가 선택한 발급 표현은 구분한다.

## 5. 대안과 결과

custom Keycloak provider 또는 custom Keycloak image로 singleton array를 강제하는 방안은
선택하지 않았다. 표준이 허용하는 stock 발급 표현을 Backend의 이중 검증 경계에서 안전하게
수용하는 변경이 더 작고 제품 종속성이 없다.

이 결정으로 Issue #235의 stock Keycloak 26.7.3 runtime 작업을 다시 진행할 수 있다. 다만 이
ADR은 Keycloak runtime, Compose, realm, client, scope 또는 mapper를 구현하지 않는다. API, DB,
Frontend와 dependency 변경도 없다.

## 6. Issue #235 runtime 연결 (2026-09-04)

Issue #235는 pinned stock Keycloak 26.7.3을 custom provider나 custom image 없이 연결했다. 실제
두 SERVICE Client Credentials access token의 raw `aud`가 exact JSON string
`finguardops-backend-api`이고 Backend 인증 경계를 통과하는지 runtime verifier가 검사한다.
Backend의 raw exact string/exact singleton array 허용과 normalized exact singleton 검증은 그대로
유지되며 additional·reversed additional·duplicate·empty·wrong·malformed audience는 허용하지
않는다.

2026-09-05 초기 container namespace 발급 검증에서는 stock Keycloak의 raw string 표현을 확인했지만
당시 listener bind 구성으로 host public HTTPS issuer 접근이 실패했다. 이 실패는 ADR-012의 audience
표현 결정을 변경하지 않았으며 그 시점에는 Issue #235 전체 runtime 완료로 간주하지 않았다.

이후 Issue #235 OWNER correction에서 `KC_HTTP_HOST=0.0.0.0`, host `127.0.0.1:8443` 단일 publish와
namespace loopback 내부 접근으로 보정했다. fresh/existing runtime, host public HTTPS, 8082·9000
host 비공개와 SERVICE token·Backend 400/403 검증이 통과했다. USER browser E2E와 Frontend
refresh-token fail-closed는 여전히 후속 범위이며 production Authorization Server는 미결정이다.

## 7. Issue #239 USER browser 후속 상태 (2026-09-05)

위 문단은 Issue #235 완료 당시의 역사적 상태다. Issue #239 Phase 3은 실제 USER browser access
token의 raw claim을 process memory에서만 검사해 논리 audience가 정확히
`finguardops-backend-api` 하나인지 확인한다. 같은 로그인에서 access/ID token의 `sub` 원문과 USER
role 집합도 비교하고, USER token의 Backend 200·403 및 credential 없음·손상 token 401 경계를
검증한다. 이 후속 검증은 singleton string/string-array 호환 결정을 변경하지 않으며 production
Authorization Server는 여전히 미결정이다.
