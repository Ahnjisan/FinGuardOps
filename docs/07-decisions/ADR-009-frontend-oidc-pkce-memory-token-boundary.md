# ADR-009: Frontend OIDC Authorization Code + PKCE와 memory-only token 경계

- 상태: Accepted
- 결정일: 2026-09-04
- 결정자: Architecture Owner
- 관련 Issue: `#229 [Frontend/Security] OIDC PKCE와 memory-only 인증 기반 구현`
- 관련 문서:
  - [`ADR-008`](ADR-008-oauth2-resource-server-rbac-user-audit-actor.md)
  - [`security-architecture.md`](../02-architecture/security-architecture.md)
  - [`system-architecture.md`](../02-architecture/system-architecture.md)
  - [`frontend/README.md`](../../frontend/README.md)

## 1. 배경

Issue #227에서 React·TypeScript·Vite 기반 Frontend foundation, Router, 환경 설정
fail-fast 검증과 public Health API client가 구현되었다. Backend에는 Issue #219·#221·#223에서
OAuth2 Resource Server, RS256 JWT 검증, USER·SERVICE principal 분리, endpoint RBAC와 사건
write USER Audit actor가 구현되어 있다.

이 결정 시점에 실제 Authorization Server 제품은 선정되지 않았다. Issue #225의 Local JWT
fixture는 Backend Resource Server E2E를 위한 local/manual 도구이며 브라우저가 사용하는
OIDC Provider가 아니다.

따라서 Frontend 인증 경계는 특정 Provider 기능을 가정하지 않고, 표준 OIDC만으로 성립하는
범위에서 확정해야 한다.

## 2. 결정

### 2.1 Flow

- Authorization Code + PKCE top-level redirect를 사용한다.
- popup flow를 사용하지 않는다.
- BFF·HttpOnly cookie session을 사용하지 않는다.
- PKCE·state·nonce 생성과 OIDC protocol validation은 `oidc-client-ts@3.5.0`이 수행한다.
- Frontend는 PKCE나 ID token 검증을 직접 구현하지 않고 token을 직접 decode하지 않는다.
- public SPA client이며 client secret을 사용하지 않는다.

### 2.2 Token 저장

- access token, ID token은 in-memory user store에만 존재한다.
- refresh token은 발급을 요청하지 않는다. `offline_access` scope를 사용하지 않는다.
- localStorage, sessionStorage, IndexedDB에 token을 저장하지 않는다.
- reload 후 저장된 token을 복원하지 않는다. 재로그인이 필요하다.
- token은 DOM에 렌더링하지 않고 오류·URL·console에 노출하지 않는다.
- 애플리케이션 상태에는 `subject`와 선택적 `displayName`만 나오며 raw `User` 객체는
  adapter 경계를 넘지 않는다.

### 2.3 Transaction metadata 저장

sessionStorage에는 Authorization Code + PKCE redirect를 수행하는 데 필요한 **transient
protocol transaction record**만 저장한다. 이 레코드에는 state 식별자, nonce, PKCE verifier,
생성 시각, authority, client ID, redirect URI, scope, request type 등 라이브러리가 요구하는
비밀 token이 아닌 transaction 정보가 포함될 수 있다. 애플리케이션이 추가하는 데이터는
`{ returnTo }` 하나로 제한한다. access token, ID token, refresh token, authorization code,
client secret은 sessionStorage에 저장하지 않는다. `url_state`는 사용하지 않는다.

- memory user store prefix: `finguardops.oidc.user.`
- session transaction store prefix: `finguardops.oidc.transaction.`

정리 시점은 다음과 같다.

- 로그인 시작 직전(실패 시 redirect하지 않고 fail-closed)
- callback 성공 직후
- callback 실패 직후
- 직접 진입 등 라이브러리를 호출하지 않은 경로

애플리케이션 초기화의 정리 범위는 진입 경로로 결정한다. `/auth/callback`에서는 지금 검증
중인 transaction(state·nonce·PKCE verifier)을 보존하며 아무것도 지우지 않고, 그 밖의
경로에서는 중단된 redirect가 남긴 app 전용 record를 정리한다. 이 정리는 동기적으로 수행해
실패가 초기화 오류로 관측되게 하며, 라이브러리의 private JSON 구조를 직접 파싱해 age를
계산하지 않는다. transaction 정리는 자기 prefix 밖의 key를 삭제하지 않는다.

`window.sessionStorage`는 property getter이므로 partitioned·cookie 차단 컨텍스트에서
`SecurityError`를 던질 수 있다. 이 읽기는 `try`/`catch` 안에서만 수행하고, module import·
AuthClient factory·최초 render 경로에서는 수행하지 않는다. `UserManager`와 session 기반
state store는 실제 인증 operation 안에서 lazy하게 만들고 완전히 성공한 경우에만 cache한다.
따라서 storage 획득 실패는 public Outlet을 중단시키지 않고 인증 영역의 고정 오류로만
수렴하며, raw `DOMException`은 화면·console·context에 노출되지 않는다.

### 2.4 대안 비교

| 대안 | 장점 | 단점 | 채택 |
| --- | --- | --- | --- |
| redirect + sessionStorage transaction record | 표준 flow, Provider 중립, top-level 이동으로 third-party cookie 의존 없음, 저장 대상이 일회성 비밀 아님 | redirect로 페이지가 reload되어 memory token이 사라짐, transaction record가 잠시 Web Storage에 존재 | 채택 |
| popup + opener in-memory transaction state | transaction을 전부 memory에 유지 | popup 차단·모바일 UX 저하, opener 통신 경계 추가, `window.opener` 취급이 Provider별로 다름 | 미채택 |
| BFF·HttpOnly cookie session | token이 브라우저 JS에 전혀 노출되지 않음 | 서버 구성 요소 추가, CSRF 재결정 필요, ADR-008의 stateless Bearer-only 경계 변경 | 이번 Scope 밖 |

### 2.5 Renew·session 수명

- `automaticSilentRenew=false`, `monitorSession=false`로 명시한다.
- silent callback route를 만들지 않는다. iframe·third-party cookie에 의존하지 않는다.
- 세션 종료 시점은 token expiry와 로그인 완료 후 15분 중 **빠른 값**이다.
- `expires_at`이 없거나 비정상(NaN·Infinity·비숫자)이면 15분 hard cap을 적용하고, 이미 지난
  값이면 즉시 무효화한다.
- 15분 deadline은 AuthClient 인스턴스의 memory에 보관하므로 React 트리 재마운트로 연장되지
  않는다. reload 시에는 token 자체가 복원되지 않는다.
- 만료 시 자동 renew·자동 redirect·자동 재로그인을 하지 않고 unauthenticated로 전환한다.
  이후에는 사용자의 명시적 재로그인만 허용한다.

hard deadline은 ADR-008의 `exp - iat` 최대 15분 계약과 방향이 같으며, Provider가 더 긴
수명을 발급하더라도 브라우저 세션이 그보다 오래 살아 있지 않게 한다.

### 2.6 Logout

- logout은 memory token과 transaction record를 즉시 제거하는 local logout이다.
- UI 무효화는 비동기 teardown 성공 여부에 의존하지 않는다.
- hard deadline·token expiry·local logout은 동일한 local invalidation뿐 아니라 동일한
  in-flight teardown Promise를 공유한다. 따라서 동시에 발생해도 `removeUser()`와 transaction
  정리는 각각 1회, subscriber 통보도 1회만 일어난다. 진행 중인 이전 teardown이 새 session의
  memory user를 제거하지 못하도록, 새 session 게시는 이전 teardown 완료 이후로 sequencing
  한다.
- remote end-session redirect와 `/auth/logout/callback`은 실제 Authorization Server 선정 후
  별도로 결정한다.

### 2.7 Route와 복귀 경로

- 신규 route는 `/auth/callback` 하나이며 `/`와 `/health`의 public 경계는 유지한다.
- 보호 업무 route, 로그인 전용 route, placeholder route를 만들지 않는다.
- callback URL은 처리 시작 시점에 즉시 `/auth/callback`으로 replace하여 `code`, `state`,
  `iss`, `session_state`, `error`, `error_description`을 query·fragment째 제거한다. 이 정리는
  storage 접근보다 먼저 수행하므로 sessionStorage getter가 던지는 경우에도 주소창이 먼저
  정리된다. 정리가 실패하면 Provider를 호출하지 않고 fail-closed한다.
- `code`와 `error`가 동시에 있는 응답은 정상 Authorization Server가 만들 수 없는 형태이므로
  라이브러리에 넘기지 않는다. token 교환도 navigation도 없이 transaction을 정리하고 고정
  오류로 끝내며, 우선순위를 라이브러리 내부 동작에 의존하지 않는다.
- Sign in redirect 취소나 back/forward cache 복귀(`pageshow`의 `event.persisted`)로 아직
  `authenticating`인 상태로 돌아오면 pending을 해제하고 재시도 가능한 고정 오류 상태로
  전환한다. 자동 재로그인·자동 redirect는 하지 않으며, 임의의 timeout도 사용하지 않는다.
- 복귀 경로는 `/`와 `/health`만 허용하는 exact allowlist이며 그 밖의 모든 값은 `/`로
  대체한다. decode·trim·backslash 치환·prefix 판정을 사용하지 않는다.

### 2.8 환경 설정

- 신규 필수 변수는 `VITE_OIDC_AUTHORITY`와 `VITE_OIDC_CLIENT_ID` 둘뿐이다.
- `response_type=code`, `scope=openid profile`은 고정값이다.
- redirect URI는 환경변수가 아니라 현재 origin에서 `/auth/callback`으로 파생한다.
- authority는 http/https만 허용하고 production build는 HTTPS만, 그 밖에서는 loopback
  hostname(`localhost`, `127.0.0.1`, `[::1]`)의 HTTP만 허용한다.
- authority의 userinfo delimiter, query, fragment(빈 delimiter 포함), ASCII control 문자와
  앞뒤 whitespace는 거부한다. issuer path의 `@`는 허용한다.
- 검증을 통과한 값은 **원문 그대로** 사용한다. issuer의 trailing slash는 의미가 있는 설정
  차이이므로 애플리케이션이 자동 보정하지 않는다.
- 오류 메시지에 원문 값·host·credential·client ID를 포함하지 않는다.
- Vite client 환경변수는 번들에 인라인되는 공개 설정이며 secret을 담지 않는다.

### 2.9 오류 노출

- Provider 응답 원문, 내부 예외, stack, storage `DOMException`을 UI·console·상태에 담지
  않는다.
- 인증 오류는 `configuration`·`sign-in`·`callback` 세 가지 고정 메시지로만 표시한다.

## 3. Local test 경계

- 실제 Authorization Server 없이 `AuthClient` port와 fake adapter로 검증한다.
- Issue #225의 Local JWT fixture를 브라우저 OIDC Provider로 확장하지 않으며 사용하거나
  수정하지 않는다.
- `oidc-client-ts` 설정 객체와 adapter 호출 경계는 production 코드 기준으로 검증하되
  라이브러리 내부 PKCE 알고리즘 자체는 재시험하지 않는다.
- storage 검증은 라이브러리 내부 JSON 구조나 private field 이름이 아니라 observable storage
  behavior(어떤 prefix의 key가 남는가, JWT 형태 값이 있는가)를 기준으로 한다.
- jsdom에서 실제 browser navigation과 popup을 억지로 재현하지 않는다.
- `crypto.subtle` polyfill을 production에 추가하지 않는다.

## 4. 결과

- Frontend는 Provider 제품에 종속되지 않는 표준 OIDC 인증 경계를 갖는다.
- XSS가 발생하더라도 지속 저장된 token이 없고 세션은 최대 15분이므로 탈취 가능한 자격증명의
  수명이 제한된다.
- reload마다 재로그인이 필요하고 세션이 최대 15분이라는 UX 비용을 수용한다. 이는 실제
  Authorization Server가 정해지기 전까지 silent renew의 iframe·third-party cookie 의존을
  가정하지 않기 위한 선택이다.
- `/`와 `/health`는 인증 초기화 실패나 Authorization Server 장애와 무관하게 계속 열려 있다.
  애플리케이션 시작이나 public route 렌더만으로는 Authorization Server에 요청하지 않는다.

## 5. 후속 작업

- 실제 Authorization Server 제품 선정과 issuer·client 등록
- Backend 보호 API 호출용 `Authorization` header와 401·403 UX
- role·authority 기반 UI
- remote end-session(RP-initiated logout)과 `post_logout_redirect_uri`
- audience claim(`finguardops-backend-api`) 확보 방식 결정
- silent renew 또는 refresh token 도입 여부 재검토

이 결정은 위 후속 작업에서 필요하면 새 ADR로 갱신한다.
