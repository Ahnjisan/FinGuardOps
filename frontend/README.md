# FinGuardOps Frontend

React·TypeScript·Vite 기반의 FinGuardOps 프론트엔드다. 표준 OIDC Authorization Code + PKCE
인증 경계와, 승인된 Backend 업무 endpoint에만 credential을 전달하는 인증 API transport가
구현되어 있다. local/dev Authorization Server는 Keycloak으로 선정했고 Issue #239에서 실제
로그인을 연결했다. Issue #243에서는 검증된 session profile의 USER role로 UI capability를
결정하는 판정 계층과 route guard 컴포넌트를 구현했다.

Issue #249에서 첫 production 업무 화면인 거래 목록(`/transactions`)과, 이후 업무 화면이 재사용할
FDS operations console 디자인 기반(`src/styles/app.css`)을 구현했다. Issue #251에서는 같은 디자인
기반 위에 거래 상세(`/transactions/{transactionId}`)와 목록→상세 탐색을 구현했다. 두 route 모두
`transaction:view` capability로 보호되며, 상세 화면은 조회 전용이다. Issue #253에서는 같은 디자인
기반 위에 조회 전용 사건 목록(`/cases`)을 구현했고, 이 route는 `case:view` capability로 보호된다.
Issue #255에서는 같은 capability로 보호되는 조회 전용 사건 상세(`/cases/{caseId}`)와 목록의 Case ID
→ 상세 탐색을 구현했다. 사건 상세 화면은 Backend 사건 상세 응답의 10개 필드만 읽기 전용으로
표시하며 mutation UI가 없다. Issue #257에서는 같은 상세 화면 하단에 별도 route 없는 read-only
Audit history section을 구현했다. Issue #259에서는 같은 화면에 별도 route 없는 read-only
Investigation notes section을 추가해 Case record → Investigation notes → Audit history 순서로 배치했다.
조사 메모 작성·수정·삭제 UI와 Backend POST API 사용은 구현하지 않았다. 사건 workflow·담당자 변경·
최종 판정·연관 거래·Detection·Rule Evidence·AI 사건 리포트 화면과 운영 대시보드는 아직 없고,
콘솔 전체의 최종 시각적 리뉴얼은 후속 Issue로 남아 있다.

## 요구사항

- Node.js `>=24.15.0 <25` (`package.json`의 `engines.node`), 검증 환경은 Node `v24.20.0`
- npm `11.6.2` (`package.json`의 `packageManager`에 고정된 실제 사용 버전)

## 설치

```bash
npm install
```

재현 가능한 설치가 필요하면 `package-lock.json`을 그대로 사용하는 `npm ci`를 사용한다.

```bash
npm ci
```

## 명령어

| 명령 | 설명 |
| --- | --- |
| `npm run dev` | 로컬 개발 서버 실행 |
| `npm run lint` | ESLint 정적 검사 |
| `npm run typecheck` | production app·Vite/Node config 타입 검사 (`tsc -b --noEmit`)와 test 전용 타입 검사 (`tsc --noEmit -p tsconfig.test.json`)를 함께 실행 |
| `npm run test` | Vitest 기반 단위·컴포넌트 테스트 실행 |
| `npm run build` | 타입 검사 후 production build (`dist/`) 생성 |
| `npm run preview` | `dist/` 정적 build 결과를 로컬에서 미리보기 |

## 환경변수

`.env.example`을 참고해 로컬 `.env` 파일을 만든다. 실제 `.env` 파일은 Git에 커밋하지 않는다.

| 변수 | 필수 | 설명 |
| --- | --- | --- |
| `VITE_API_BASE_URL` | 예 | Backend origin base URL. `http` 또는 `https` scheme만 허용하며, username/password, query string, fragment를 포함할 수 없다. |
| `VITE_OIDC_AUTHORITY` | 예 | Authorization Server issuer(authority). `http`/`https`만 허용한다. production build는 HTTPS만, 그 밖의 mode에서는 loopback host(`localhost`, `127.0.0.1`, `[::1]`)의 HTTP만 허용한다. userinfo, query, fragment(빈 `?`·`#` 포함), ASCII control 문자, 앞뒤 whitespace는 거부한다. issuer path의 `@`는 허용한다. |
| `VITE_OIDC_CLIENT_ID` | 예 | public SPA client ID. 앞뒤 whitespace와 control 문자를 거부하며 원문 그대로 사용한다. client secret은 사용하지 않는다. |

`VITE_OIDC_AUTHORITY`와 `VITE_OIDC_CLIENT_ID`는 **원문 그대로** 사용한다. URL parser는 검증
용도로만 쓰며 애플리케이션이 값을 정규화하지 않는다. 특히 issuer의 trailing slash는 의미가
있는 설정 차이이므로 자동으로 붙이거나 제거하지 않는다(`https://as.example/realms/fin`과
`https://as.example/realms/fin/`은 서로 다른 설정이다).

`redirect_uri`는 환경변수로 받지 않고 현재 origin에서 `/auth/callback`으로 파생한다. client
secret, response type, scope, silent redirect URI, refresh token, logout callback 변수는
만들지 않는다.

`VITE_` 접두사가 붙은 Vite client 환경변수는 build 시 번들에 인라인되는 **공개 설정**이다.
secret을 넣지 않는다.

세 변수 모두 `src/main.tsx`의 `bootstrap()`이 React root를 생성·render하기 전에 앱 시작 시
정확히 한 번 검증한다(fail-fast). Backend 설정(`getEnv()`)과 인증 설정(`getAuthEnv()`)은
별도 memoized 값이므로 public Health 화면이 OIDC 설정에 의존하지 않는다. 값이 없거나 형식이 유효하지 않으면 `createRoot`나
render에 도달하지 않고, 애플리케이션은 원문 값을 화면이나 콘솔에 출력하지 않고 고정된 오류로
실패한다. `HealthPage`를 포함한 나머지 코드는 같은 memoized 설정(`getEnv()`)을 재사용하며 검증을
반복하지 않는다.

## 디렉터리 책임

| 경로 | 책임 |
| --- | --- |
| `src/app` | Router 구성, 최상위 App Shell (navigation, `Outlet`), capability route guard |
| `src/pages` | 화면 단위 컴포넌트 |
| `src/api` | Backend HTTP client, endpoint allowlist, 인증 transport, query·pagination 계약, 응답 검증 primitive, 업무 typed API module, 오류 분류, API 타입, 데이터 조회 hook |
| `src/auth` | 인증 상태 machine, `AuthClient` port, `oidc-client-ts` adapter, transaction storage, callback URL·복귀 경로 처리, USER role·capability 판정, React context와 hook |
| `src/pages/transactions` | 거래 목록 화면의 filter·table·pagination 컴포넌트와, 목록·상세가 함께 쓰는 표시 규칙 |
| `src/pages/cases` | 사건 목록 화면의 filter·table·pagination 컴포넌트와 사건 표시·query 규칙 |
| `src/styles` | 전역 디자인 token과 console 스타일 (`app.css`, `src/main.tsx`에서 1회 import) |
| `src/config` | 환경변수 검증 |
| `src/shared` | 화면 전반에서 재사용하는 타입 (예: `AsyncState`) |
| `src/test` | 테스트 공용 설정과 helper (production build에 포함되지 않음) |

`tsconfig.app.json`은 `src/**/*.test.ts(x)`와 `src/test/**`를 production 컴파일에서 명시적으로
제외한다(`exclude`). 테스트 코드는 별도 `tsconfig.test.json`(strict, `vitest/globals`·
`@testing-library/jest-dom`·`node` 타입 명시)으로 독립 typecheck하며, `npm run typecheck` 한
번으로 production app·Vite/Node config·test 세 영역을 모두 검증한다. `npx tsc --listFilesOnly -p
tsconfig.app.json` 결과에는 test 또는 test-support 파일이 포함되지 않는다.

## 구현된 Route

| Path | 화면 | 설명 |
| --- | --- | --- |
| `/` | `HomePage` | 진입 화면 (public) |
| `/transactions` | `TransactionListPage` | 거래 목록 (보호, `RequireCapability("transaction:view")`) |
| `/transactions/{transactionId}` | `TransactionDetailPage` | 거래 상세, 조회 전용 (보호, `RequireCapability("transaction:view")`) |
| `/cases` | `CaseListPage` | 사건 목록, 조회 전용 (보호, `RequireCapability("case:view")`) |
| `/cases/{caseId}` | `CaseDetailPage` | 사건 상세, 조회 전용 (보호, `RequireCapability("case:view")`) |
| `/health` | `HealthPage` | Backend `/api/health` 상태 조회 (public) |
| `/auth/callback` | `AuthCallbackPage` | OIDC redirect callback 처리 |
| 그 외 모든 경로 | `NotFoundPage` | 404 |

`/`와 `/health`는 public이며 인증 초기화 실패나 Authorization Server 장애와 무관하게 계속
열려 있다. 로그인 전용 route, silent renew callback, logout callback route는 존재하지 않는다.
사건 workflow·담당자 변경·최종 판정, 조사 메모 생성·수정·삭제 UI와 별도 notes route,
연관 거래·Detection·Rule Evidence·AI 사건 리포트 화면과 운영 대시보드는 구현되지 않았다.
조사 메모 조회는 `/cases/{caseId}` 내부의 읽기 전용 section으로 구현되어 있다.

거래 목록, 거래 상세, 사건 목록과 사건 상세가 `RequireCapability`를 적용한 production route다.
guard는 navigation이 아니라 route element에 있으므로 rail 클릭, 목록의 상세 link, 직접 URL 진입이
모두 같은 판정을 받는다. capability가 없는 USER는 `AccessDeniedPage`를, session이 없는 방문자는
`Sign in required`를 보고, 두 경우 모두 Backend 요청은 0회다. `/transactions/{id}/...`처럼 segment가
더 깊은 경로는 어느 route도 아니므로 `NotFoundPage`다.

사건 route는 exact `/cases`와 한 segment짜리 `/cases/:caseId` 두 개다. 두 route 모두
`case:view`로 보호한다. `/cases/{caseId}/notes`, `/cases/{caseId}/audit-logs`,
`/cases/{caseId}/resolution`처럼 segment가 더 깊은 경로와 `/casesx`처럼 prefix만 공유하는 경로는
어느 route도 아니므로 `NotFoundPage`다. `/cases/`는 React Router가 SPA 코드 실행 전에 exact
`cases` route로 정규화하므로 사건 상세가 아니라 사건 목록이 렌더된다. 사건 navigation 항목은
`case:view`를 별도로 확인하며 `transaction:view` 결과를 재사용하지 않는다.

### SPA 입력 경계와 canonical transaction ID

Frontend의 입력 경계는 **브라우저 URL parser가 제공한 최종 location**이다. 주소창 입력, link
click, redirect는 React와 SPA가 실행되기 전에 모두 브라우저 URL parser를 거치고, dot segment,
raw backslash, 제거 가능한 control character 같은 표현은 그 단계에서 canonical URL로 정규화된다.

- `/x/../transactions/{uuid}`, `/transactions/%2e%2e/transactions/{uuid}`,
  `/transactions\{uuid}`, 제거 가능한 trailing control character가 붙은 주소는 브라우저에서
  모두 `/transactions/{uuid}`로 수렴한다.
- SPA는 이 정규화 이전의 표현을 복구하거나 판별할 수 없고, 그렇게 주장하지도 않는다. SPA가
  받은 location은 정상 link click과 구분되지 않으므로 canonical 상세 route로 처리된다.
- 이 경우에도 authentication, `RequireCapability("transaction:view")`, Backend authority
  `transaction:read` 판정은 그대로 적용된다.
- SPA 이전의 raw request-target을 구분하려면 reverse proxy·web server 같은 navigation 경계가
  필요하다. 이는 production hosting의 관심사이며 SPA가 구현할 수 있는 계층이 아니므로 이번
  범위에 포함하지 않는다. service worker, dev server middleware, navigation monkey patch,
  inline bootstrap script, `document.referrer`·history 추정, encoded URL 복원 로직으로 이를
  흉내내지 않는다.

SPA에 **보존된 채 도달한** location에 대해서는 strict하게 판정한다. 상세 route의
`transactionId`는 canonical lowercase UUID v4만 유효하며, 판정은 `useParams()`가 아니라
`useLocation()`의 `pathname`·`search`·`hash`에 대해 수행한다. React Router는 route parameter를
percent-decode해서 넘겨주므로 `/transactions/%32f4c0a4e-...`의 parameter는 정상 UUID처럼 보이지만,
브라우저가 넘긴 path segment는 `%32`를 그대로 가지고 있어 거부된다.

브라우저가 정규화하지 않고 location에 남긴 다음 표현은 모두 거부된다.

uppercase UUID, UUID v1/v3/v5, 잘못된 RFC variant, trailing slash, 추가 segment, 중복 slash,
semicolon(matrix parameter), query, fragment, percent-encoded UUID 문자(`%32`), `%2F`·`%5C`,
double encoding(`%252F`), malformed percent(`%2`), 살아남은 whitespace·control character
(`%20`·`%0d`), 그리고 canonical pathname과 다른 모든 representation.

absolute URL, protocol-relative URL, userinfo, 다른 origin·scheme·port는 브라우저가 만든
location의 pathname에 그대로 남을 수 있는 형태가 아니다. 이 문자열들에 대한 거부는 아래 로그인
후 복귀 경로 allowlist가 담당한다.

거부되는 주소는 고정된 `This is not a transaction address` 상태로 fail-closed된다. 이 경로에서
credential 조회 0회, `fetch` 0회이며, 입력값의 어떤 부분도 화면·오류 문구·`console`·DOM 속성에
출력되지 않는다. 브라우저가 이미 정규화해 버린 표현은 저장·복원·로그하지 않는다.

### 로그인 후 복귀 경로

새 보호 route를 추가할 때는 `src/app/router.tsx`와 `src/auth/returnRoute.ts`의 allowlist를 함께
갱신해야 로그인 후 복귀가 성립한다. allowlist는 `/`, `/health`, `/transactions`, `/cases` 네
literal과, `/transactions/{canonical lowercase UUID v4}`·`/cases/{canonical lowercase UUID v4}`
두 가지 parameterized 형태뿐이다.

- 판정 대상은 `location.pathname` 하나가 아니라 현재 location의 `pathname + search + hash`를
  그대로 이어붙인 값이다. AppShell이 query나 fragment를 떼고 넘기면
  `/transactions/{uuid}?tab=raw`가 canonical 상세 route로 되살아나므로, 이 결합에는 trim,
  decode, normalization을 적용하지 않는다. query나 fragment가 있으면 주소 전체가 allowlist를
  통과하지 못해 기본 route `/`로 fail-closed되며, 제거 후 재허용은 하지 않는다.
- literal 세 개는 `===` 문자열 비교다. prefix 매칭이 아니다.
- 상세 경로는 `startsWith("/transactions/")`나 `includes("/transactions")`로 판정하지 않는다.
  정확히 한 segment를 떼어내 `isCanonicalUuidV4`로 검사하고, 통과한 36자로 경로를 **다시 조립해**
  반환한다. 따라서 입력 문자열의 어떤 바이트도 그대로 반환되지 않는다.
- decode·trim·slash 치환·URL normalization 후 재허용은 하지 않는다. `/transactions/`,
  `/transactions/1`, `/transactions/2F4C...`, `/transactions/{uuid}/`, `/transactions/{uuid}?x=1`,
  `/transactions/{uuid}#a`, `/transactions/%32f4c...`, `/transactions//{uuid}`, `/transactionsx/{uuid}`,
  `//evil.example/transactions/{uuid}`, `https://user:pass@evil.example/transactions/{uuid}`,
  `%2ftransactions`는 모두 기본 route `/`로 떨어진다.
- 사건 상세 경로도 같은 규칙이다. `startsWith("/cases/")`나 `includes("/cases")`가 아니라 정확히
  한 segment를 떼어 `isCanonicalUuidV4`로 검사하고, 통과한 36자로 `/cases/{caseId}`를 **다시
  조립해** 반환한다. 두 parameterized 형태는 각각 자기 collection 아래에서만 판정되므로 거래
  주소가 사건 주소로, 사건 주소가 거래 주소로 바뀌지 않는다.
- 사건 경로에서 거부되는 값은 다음과 같다. `/cases/`, `/cases/1`, `/casesx`, `/casesx/{caseId}`,
  `/x/cases`, `/cases?status=OPEN`, `/cases#content`, `/Cases`, ` /cases`, `%2fcases`,
  `/cases%2f`, `/cases%5c`, `%252fcases`, `\cases`, `/\cases`, `//cases`,
  `https://evil.example/cases`, `https://user:pass@evil.example/cases`,
  `http://localhost:8080/cases`, uppercase·v1/v3/v5·잘못된 RFC variant·hyphen 없는 caseId,
  `/cases/{caseId}/`, `/cases/{caseId}/notes`, `/cases/{caseId}/audit-logs`,
  `/cases/{caseId}/resolution`, `/cases/{caseId}/transactions`, `/cases/{caseId}?tab=raw`,
  `/cases/{caseId}#assignee`, `/cases/%35c2d1e0f-...`, `/cases/{caseId}%2fnotes`,
  `/cases//{caseId}`. 모두 기본 route `/`로 떨어지며, query나 fragment를 제거한 뒤 재허용하지
  않는다.
- 거부된 값은 오류·로그·DOM 어디에도 반사되지 않는다.

## 인증 경계

로그인은 표준 OIDC **Authorization Code + PKCE** top-level redirect다. popup, BFF,
HttpOnly cookie session을 사용하지 않는다. PKCE·state 생성과 OIDC protocol 검증은 직접
dependency인 `oidc-client-ts@3.5.0`이 담당한다. nonce는 애플리케이션이 매 로그인마다 Web
Crypto로 32바이트를 생성해 padding 없는 base64url로 인코딩하고
`signinRedirect({ state, nonce })`로 직접 전달한다. `extraQueryParams`는 사용하지 않으며,
애플리케이션은 token을 직접 decode하지 않는다. public SPA client이므로 client secret이 없다.

### OIDC 설정

`src/auth/oidcAuthClient.ts`의 `createOidcSettings()`는 라이브러리 default에 의존하지 않고
다음을 명시한다.

| 설정 | 값 |
| --- | --- |
| `response_type` | `code` |
| `scope` | `openid profile` (`offline_access` 없음) |
| `redirect_uri` | 현재 origin + `/auth/callback` |
| `automaticSilentRenew` | `false` |
| `monitorSession` | `false` |
| `loadUserInfo` | `false` |
| `userStore` | `InMemoryWebStorage` (prefix `finguardops.oidc.user.`) |
| `stateStore` | `sessionStorage` (prefix `finguardops.oidc.transaction.`) |

`client_secret`, `silent_redirect_uri`, `post_logout_redirect_uri`는 설정하지 않는다.

### Token과 transaction 저장

- access token과 ID token은 in-memory user store에만 존재한다. 현재 scope는
  `offline_access`를 요청하지 않고 refresh grant·silent renew를 구현하지 않는다. 그러나 이는
  향후 Keycloak의 일반 Authorization Code token response에 온라인 refresh token이 없음을
  보장하지 않는다.
- localStorage, sessionStorage, IndexedDB에 token을 저장하지 않으며 reload 후 복원하지
  않는다. 새로고침하면 다시 로그인해야 한다.
- sessionStorage에는 Authorization Code + PKCE redirect 수행에 필요한 **transient protocol
  transaction record**만 저장한다. 이 레코드에는 state 식별자, nonce, PKCE verifier, 생성
  시각, authority, client ID, redirect URI, scope, request type 등 라이브러리가 요구하는
  비밀 token이 아닌 transaction 정보가 포함될 수 있다. 애플리케이션이 추가하는 데이터는
  `{ returnTo }` 하나뿐이며 `url_state`는 사용하지 않는다.
- transaction `stateStore`는 `oidc-client-ts`의 `WebStorageStateStore`를 검증 wrapper로 감싼다.
  wrapper는 `set`·`get`·`remove`에서 존재하는 transaction record의 nonce가 nonblank인지
  검증하며, 누락·blank·파싱 불가 record는 고정 내부 오류로 거부한다. 존재하지 않는 record는
  라이브러리가 unknown/replayed state로 거부하도록 그대로 전달한다.
- transaction record는 로그인 시작 직전, callback 성공 직후, callback 실패 직후, 그리고
  라이브러리를 호출하지 않은 직접 진입 경로에서 정리한다. 정리는
  `finguardops.oidc.transaction.` prefix만 삭제하므로 다른 애플리케이션의 key와 memory user
  store prefix는 보존한다.
- 애플리케이션 초기화의 정리 범위는 진입 경로에 따라 다르다. `/auth/callback`에서는 지금
  검증 중인 transaction(state·nonce·PKCE verifier)을 그대로 보존하고 아무것도 지우지
  않는다. 그 밖의 경로에서는 이전에 중단된 redirect가 남긴 app 전용 transaction record를
  정리한다. 정리는 동기적으로 수행하므로 실패가 초기화 오류로 관측되며, 조용히 삼켜지거나
  unhandled rejection으로 빠져나가지 않는다.
- callback 완료 후에는 성공·실패와 무관하게 adapter가 transaction을 정리한다.
- 로그인 시작 전 transaction 정리가 실패하면 redirect하지 않고 고정 오류로 끝난다
  (fail-closed).

### Web Storage를 사용할 수 없는 경우

`window.sessionStorage`는 property getter이며 partitioned·cookie 차단 컨텍스트에서는 null을
돌려주는 대신 `SecurityError`를 던진다. 이 읽기는 반드시 `try`/`catch` 안에서만 수행하고,
module import·AuthClient factory 호출·최초 React render 경로에서는 수행하지 않는다.
`UserManager`와 sessionStorage 기반 state store는 실제 인증 operation 안에서 lazy하게
생성하며, 획득과 생성이 모두 성공한 경우에만 cache한다.

결과적으로 storage getter가 던지더라도 다음이 성립한다.

- `/`와 `/health` public Outlet은 정상 렌더된다.
- 인증 영역만 고정 오류 상태가 되고 자동 redirect·자동 로그인은 없다.
- raw `DOMException`의 message·stack·storage 원문은 화면·console·context에 남지 않는다.

### Callback 처리

`/auth/callback`은 다음 순서로 동작한다.

1. callback URL을 메모리로 한 번 캡처한다.
2. 즉시 `history.replaceState`로 `/auth/callback`으로 바꿔 `code`, `state`, `iss`,
   `session_state`, `error`, `error_description`을 query·fragment째 제거한다.
3. 이 정리가 실패하면 Provider를 호출하지 않고 고정 오류 화면으로 끝난다. 인증 성공 처리도,
   자동 이동도 하지 않는다. URL 정리는 storage 접근보다 먼저 수행하므로 sessionStorage
   getter가 던지는 경우에도 `code`·`state`·fragment가 주소창에서 먼저 제거된다.
4. 캡처한 URL을 `new URL()`로 파싱해 query의 exact key `code` 또는 `error` 중 **정확히 한
   쪽만** 있을 때 protocol 처리를 진행한다. 문자열 포함 여부로 판단하지 않으므로 경로나 다른
   파라미터 값에 들어 있는 `code`를 callback parameter로 오인하지 않는다. 파싱 실패와 직접
   진입은 모두 안전한 오류로 끝난다.
5. `code`와 `error`가 동시에 있는 응답은 정상 Authorization Server가 만들 수 없는 형태이므로
   비정상 응답으로 취급한다. 라이브러리를 호출하지 않고, token 교환도 navigation도 하지 않은
   채 transaction을 정리하고 고정 오류로 끝낸다. 어느 쪽을 우선할지는 라이브러리 내부 동작에
   맡기지 않는다.
6. protocol 검증에 성공했더라도 transaction record 정리에 실패하면 인증을 완료하지 않고
   local user를 폐기한 뒤 고정 오류로 끝낸다.

StrictMode의 setup→cleanup→setup에서도 callback 작업은 공유 record로 정확히 1회만 실행되며,
첫 effect의 cleanup 이후에도 성공·실패 결과가 유실되지 않는다. 실제 unmount 이후에는
navigate도 상태 갱신도 하지 않는다.

성공 시 복귀 경로는 `/`와 `/health`만 허용하는 exact allowlist를 통과한 값만 사용한다.
decode, trim, backslash 치환, `startsWith` 판정을 하지 않으므로 절대 URL,
protocol-relative(`//host`), backslash 변형, encoded slash/backslash, allowlist 밖 내부
경로는 모두 `/`로 대체된다. raw 복귀 값은 화면·console·오류에 출력하지 않는다.

### 세션 수명과 logout

- silent renew, refresh token grant, `offline_access`, silent callback route가 현재 구현에 없다.
  `automaticSilentRenew`는 `false`이며 refresh grant 호출과 silent renew는 각각 0회다.
- 세션은 token expiry와 **로그인 완료 후 15분** 중 빠른 시점에 끝난다. `expires_at`이
  없거나 `NaN`·`Infinity`·비숫자면 15분 hard cap을 적용하고, 이미 지난 값이면 즉시
  무효화한다.
- 15분 deadline은 AuthClient 인스턴스의 memory에 있으므로 React 트리 재마운트로 연장되지
  않는다.
- 만료·logout·Provider expiry 이벤트는 모두 하나의 idempotent local invalidation 경계로
  수렴한다. 화면은 비동기 teardown(`removeUser`·storage 정리) 성공 여부를 기다리지 않고
  즉시 unauthenticated가 되며, teardown이 실패해도 그 상태를 유지하고 원문 오류를 노출하지
  않는다.
- teardown 자체도 adapter마다 하나의 in-flight 경계를 공유한다. expiry teardown이 진행 중일
  때 logout이 실행되어도 `removeUser()`와 transaction 정리는 각각 1회만 수행되고 subscriber
  통보도 1회만 발생한다.
- teardown은 마지막에 transaction prefix 전체를 정리하므로, 그 prefix에 새로 쓰는 작업은
  teardown 이후에 실행해야 한다. 따라서 새로운 `Sign in`은 기존 teardown이 완전히 끝난 뒤에
  transaction을 생성하고, callback 처리도 같은 순서로 이전 teardown을 기다린 뒤 session을
  게시한다. 이전 session의 정리가 새 로그인의 state·nonce·PKCE verifier나 새 memory user를
  지우는 일은 없다.
- 이전 teardown이 진행 중이라고 해서 재로그인이 거부되지는 않는다. 사용자의 `Sign in`은 안전
  하게 대기했다가 진행하며, 이전 teardown이 실패로 끝난 경우에도 teardown은 안전하게 settle
  되므로 재로그인이 막히지 않는다. 대기 중에는 redirect도 token 교환도 시작하지 않는다.
- 만료 후 자동 renew·자동 redirect·자동 재로그인은 없다. 사용자의 명시적 `Sign in`만
  가능하다.
- Sign in redirect가 취소되거나 back/forward cache에서 이 문서로 돌아온 경우
  (`pageshow`의 `event.persisted`), 아직 `authenticating`이면 pending을 해제하고 재시도
  가능한 고정 오류 상태로 전환한다. 자동 재로그인은 하지 않으며 사용자는 `Sign in`을 다시
  누를 수 있다. 임의의 timeout으로 pending을 해제하지는 않는다.
### Remote logout (RP-initiated)

Issue #247에서 local logout을 Keycloak RP-initiated logout으로 확장했다. realm, bootstrap,
verifier와 production router는 변경하지 않았다.

- `Sign out`은 먼저 **동기적으로** application session, session ownership, 15분 deadline
  timer를 제거하고 subscriber에게 정확히 1회 통보한다. 그다음에야 remote 작업을 시작하므로
  redirect를 준비하는 동안 UI가 인증된 상태로 남는 구간이 없다.
- OIDC user record는 library가 hint를 읽을 때까지 유지한다. adapter는 `signoutRedirect()`
  이전에 `removeUser()`나 transaction 정리를 실행하지 않는다.
- ID token은 로그인 callback에서 library가 검증해 memory user store에 보관한 값을 library가
  내부적으로 `id_token_hint`로 사용한다. application은 ID token을 state·React·DOM에 복사하지
  않고 logout 인자로 전달하지도 않는다. adapter는 존재 여부와 compact JWT 형태만 fail-closed로
  확인하며 원문을 이름에 바인딩하거나 복사·출력하지 않는다. 별도 JWK 재검증은 하지 않고
  로그인 callback에서 검증된 provenance를 신뢰한다(ADR-011 2.9).
- ID token이 없거나 runtime 형태가 잘못되었거나 getter가 throw하면 redirect하지 않고 local
  cleanup 후 고정 `AuthSignOutError`로 끝난다.
- 목적지는 discovery가 아니라 설정된 issuer에서 고정한다. `metadataSeed.end_session_endpoint`가
  issuer + exact `/protocol/openid-connect/logout`이며, library는 discovery 문서 위에 seed를
  덮어쓰므로 변조된 discovery 응답이 목적지를 옮길 수 없다.
- `post_logout_redirect_uri`는 현재 origin + exact `/`뿐이다. query·fragment·userinfo·경로가
  섞인 origin은 URL 재파싱으로 거부한다. Keycloak client의 exact allowlist와 동일한 문자열이다.
- 동시 logout은 같은 session generation에서 pending인 동안만 하나의 flight를 공유한다. 그 attempt의
  redirect, logical teardown, subscriber 통보는 각각 1회이며, flight는 settlement 후 해제된다.
  같은 page에서 재로그인한 새 session generation의 logout은 이전 flight를 재사용하지 않고 새 remote
  logout을 수행한다. 실패한 logout은 종료 상태로 남고 자동 retry하지 않는다.
- 실패 시 OIDC user와 이 애플리케이션이 소유한 transaction record를 best-effort로 제거하고
  고정 `AuthSignOutError`만 노출한다. provider 원문, token, state, end-session URL은 message,
  stack, cause 어디에도 담지 않는다. redirect가 실패해도 local logout은 복원하지 않는다.

#### Logout callback

- logout callback route는 exact `/`다. 로그인 callback `/auth/callback`과 분리되어 있고
  production router와 HomePage는 변경하지 않았다.
- `AuthProvider`가 최초 `initialize()` **이전에** 주소창을 분류한다. 응답이면 library 호출
  전에 `history.replaceState()`로 bare `/`로 정리하므로 state와 provider error description이
  주소창이나 `document.referrer`에 남지 않는다.
- 분류는 fail-closed다: exact origin, exact `/`, fragment 없음, userinfo 없음, `state` 정확히
  1개이며 nonblank, 허용 parameter는 `state`·`error`·`error_description`·`error_uri`뿐,
  중복·unknown parameter 거부, `error` 없는 `error_description` 거부, `;`(library의 url-state
  구분자)나 공백이 섞인 state 거부.
- callback은 logout transaction만 정확히 1회 consume한다. `removeUser()`, session
  invalidation, subscriber 통보를 실행하지 않으므로 session B가 살아 있는 상태에서 session A의
  stale callback이 도착해도 B의 session·memory user·deadline timer가 그대로 유지된다.
- `initialize()`는 logout callback이 claim된 동안 transaction prefix를 sweep하지 않는다.
  provider도 callback이 settle한 뒤에 `initialize()`를 호출하므로 sweep이 consume 대상 record를
  먼저 지우는 순서가 존재하지 않는다.
- 성공은 unauthenticated, 실패는 credential 없는 고정 logout 오류로 끝난다. 성공·실패 모두
  최종 주소는 exact `/`다.

### 인증 상태

인증 상태는 boolean 조합이 아니라 discriminated union이다: `initializing`,
`unauthenticated`, `authenticating`, `authenticated`, `signing-out`, `error`. 모든 전이는
출발 상태로 보호되므로 중복 로그인 시작, 중복 logout 시작, 중복 callback 처리, 늦게 도착한
결과가 상태를 되돌리지 못한다. UI에는 `subject`, token, claim, Provider 원문을 렌더링하지
않으며 오류는 `configuration`·`sign-in`·`callback`·`sign-out` 네 가지 고정 메시지로만 표시한다.

`signing-out`은 credential도 session도 담지 않는다. `Sign out` 직후 authenticated session과
capability는 즉시 화면에서 사라지고, end-session redirect가 진행되는 동안 `Sign in`과
`Sign out` 버튼은 모두 제거되므로 중복 실행이 삼켜지는 죽은 버튼이 생기지 않는다.
`RequireCapability`는 `signing-out`을 `unauthenticated`와 같은 "session 없음" 분기로 처리한다.

redirect가 취소되거나 back/forward cache에서 이 문서로 돌아오면(`pageshow`의
`event.persisted`) `signing-out`은 `unauthenticated`로 풀린다. local logout은 절대 되돌리지
않으며 자동 retry도 자동 재로그인도 하지 않는다.

### Authorization Server 경계

애플리케이션 시작이나 public route 렌더만으로는 discovery·JWKS·authorize·userinfo 요청을
보내지 않는다. 실제 Authorization Server 통신은 사용자가 `Sign in`을 눌렀을 때와
`/auth/callback` 처리에서만 발생한다. 따라서 Authorization Server 장애는 로그인 실패로
한정되고 `/`·`/health`와 기존 Health fetch 계약에는 영향을 주지 않는다.

Issue #233에서 local/dev Authorization Server 제품으로 Keycloak을 선정했다. USER client는
client secret이 없는 public client이며 Authorization Code Flow + PKCE `S256`만 허용한다.
implicit flow와 password grant는 금지한다. redirect URI와 후속 post-logout redirect URI는
환경별 exact allowlist를 사용하고 wildcard를 허용하지 않는다. Issue #239에서 pinned stock
Keycloak 26.7.3 realm·client·client scope·protocol mapper와 실제 Chromium 로그인을 연결했다.
USER client의 default scope는 FinGuardOps audience·USER claim 두 개이고 optional scope는 stock
`profile` 하나뿐이다. `profile`을 default로 옮기거나 다른 optional scope를 추가하지 않는다.
realm import와 bootstrap은 pinned Keycloak 26.7.3의 stock `profile` scope 및 mapper 계약을
재현한다. `openid`는 Frontend의 OIDC authorize 요청에만 포함하며 Keycloak client scope 객체로
생성하거나 USER client에 연결하지 않는다.
Local E2E USER에는 `.invalid` 합성 이메일과 이름을 desired state로 적용해 stock user-profile
required action 없이 로그인하며, 실제 개인식별정보를 사용하지 않는다.

Keycloak USER ID token에는 `principal_type=USER`와 같은 session의 Backend access token에
공급한 것과 중복 없는 동일한 FinGuardOps USER role 집합을 `roles` 배열로 제공한다. 배열 순서는
의미가 없고 집합 동등성으로 비교한다. unknown·duplicate role과 USER·SERVICE role 혼합은
계속 금지한다. 같은 두 token의 `sub`는 원문 기준으로 완전히 동일하고 각각 canonical lowercase
UUID v4여야 한다. trim·lowercase 변환·normalization·재직렬화로 불일치를 보정하지 않으며,
후속 provisioning 또는 E2E 실패로 처리한다. 같은 로그인에서 두 token payload의 `sub` 원문을
직접 비교해 Frontend 표시 사용자와 Backend authorization·Audit actor가 동일한 subject임을
검증해야 한다.

Frontend는 access token을 직접 decode하지 않는다. OIDC client가 검증해 게시한 session
profile의 `principal_type`과 `roles`만 navigation·button·action 노출에 사용할 수 있다. 이
정보는 UI 표시를 위한 것이며 Backend authorization을 대체하지 않는다. Backend는 ID token이
아니라 access token만 API credential로 검증하고 401·403을 최종 결정한다. 이 claim을 읽는
코드는 adapter의 `toAuthSession()` 한 곳뿐이며, 자세한 판정 규칙은 아래 `권한 UI 경계`에
있다.

`offline_access` 요청과 offline token 사용은 금지한다. 일반 온라인 refresh token은 offline
token과 별개의 credential이고 `offline_access` 없이도 반환될 수 있다고 가정한다. Frontend
adapter는 provider 설정만 신뢰하지 않고 실제 token response를 검사한다. `refresh_token`이 없으면
정상 callback을 성공시키고, 있으면 fail-closed로
session을 게시하지 않은 채 OIDC user state를 제거한다. callback 이후 `User`, `AuthContext`,
application state, OIDC user store, localStorage, sessionStorage 등 유지되는 저장 표면에 refresh
token이 남아서는 안 된다. 검사 중 라이브러리 내부에 일시적으로 존재하는 값은 애플리케이션이
보관하는 credential과 구분하되, 원문을 로그·오류·DOM·React state·관측 데이터에 노출하지 않고
callback 종료 후 유지하지 않는다. Issue #239 Playwright E2E는 성공·거부 callback, 거부 후 session·
Backend 요청 0회, refresh grant 0회, silent renew 0회와 원문 노출 0회를 검증한다.

## 권한 UI 경계

capability 판정 계층과 `RequireCapability` guard는 **표시 경계**다. Backend가 access token으로
endpoint·method authority를 다시 판정해 401·403으로 최종 결정하며, 숨긴 버튼과 guard가 그
판정을 대체하지 않는다.

### session profile에서 USER role 판정

`principal_type`과 `roles`는 OIDC client가 검증한 ID token claim이고,
`src/auth/oidcAuthClient.ts`의 `toAuthSession()` 한 곳에서만 읽는다. 애플리케이션은 access
token을 직접 decode하지 않는다. 판정은 `src/auth/userRoles.ts`의 `resolveUserRoles()`가 한다.

| 입력 | 결과 |
| --- | --- |
| `principal_type`이 정확히 `USER`이고 `roles`가 알려진 USER role을 하나 이상 담은 중복 없는 배열 | 그대로 채택 |
| `principal_type`이 `USER`가 아님(`SERVICE`, `user`, ` USER `, 누락, non-string) | 전체 거부 |
| `roles`가 배열이 아님(문자열, `Set`, array-like object, 누락) | 전체 거부 |
| `roles`가 빈 배열 | 전체 거부 |
| unknown role, SERVICE role, `ROLE_` prefix, Keycloak 내부 role, duplicate, 대소문자·공백·개행 변형, non-string 원소 | 전체 거부 |
| claim getter가 예외를 던짐 | 전체 거부 |

trim·lowercase·deduplicate·부분 채택을 하지 않는다. Backend `FinGuardOpsJwtValidator`가 같은
token을 401로 거부하므로, 알아볼 수 있는 이름만 살려 UI를 그리면 반드시 실패할 조작을
노출하게 된다.

빈 배열도 같은 이유로 거부하며, Backend와 판정을 일치시키기 위한 것이지 별도로 더 엄격한
규칙이 아니다. Backend는 모든 authority를 role claim에서만 도출하므로 role이 하나도 없는 USER
token은 어떤 업무 endpoint에서도 401이다. 이런 session을 게시하면 로그인은 성공한 것처럼
보이지만 첫 요청에서 실패하는 상태가 된다. 따라서 "로그인했지만 아무 role도 없는 session"은
존재하지 않으며, `AuthSession.roles`는 required이면서 비어 있을 수 없는 readonly USER role
배열이다.

전체 거부는 **session을 게시하지 않는 것**을 뜻한다. callback은 `discardRejectedUser()`로 OIDC
user state와 transaction record를 제거하고 고정 `AuthCallbackError`로 끝난다. session 게시 0회,
subscriber 통보 0회이며 이후 `initialize()`는 `{ session: null }`로 수렴한다. 거부된 claim
원문은 오류·DOM·로그·Web Storage 어디에도 남지 않는다.

채택된 role 배열은 `Object.freeze`로 동결해 `AuthSession.roles`에 저장하고, provider가 준
순서를 그대로 유지한다(ADR-011은 canonical role order를 정의하지 않는다). claim 배열을 그대로
참조하지 않고 복사한다.

### role에서 capability 판정

`src/auth/capabilities.ts`의 capability는 이 client가 실제로 호출할 수 있는 endpoint
(`src/api/backendEndpoints.ts`의 10개 key)에만 대응한다.

| capability | 대응 Backend endpoint |
| --- | --- |
| `transaction:view` | `GET /api/v1/transactions`, `GET /api/v1/transactions/{transactionId}` |
| `case:view` | `GET /api/v1/cases`, `GET /api/v1/cases/{caseId}`, `GET /api/v1/cases/{caseId}/notes`, `GET /api/v1/cases/{caseId}/audit-logs` |
| `case:workflow` | `PATCH /api/v1/cases/{caseId}/status`, `PATCH /api/v1/cases/{caseId}/assignee` |
| `case:note-write` | `POST /api/v1/cases/{caseId}/notes` |
| `case:resolve` | `POST /api/v1/cases/{caseId}/resolution` |

| USER role | `transaction:view` | `case:view` | `case:workflow` | `case:note-write` | `case:resolve` |
| --- | :-: | :-: | :-: | :-: | :-: |
| `FDS_VIEWER` | O | O | | | |
| `FDS_ANALYST` | O | O | O | O | |
| `FDS_APPROVER` | O | O | | | O |
| `RULE_OPERATOR` | | | | | |
| `RECOVERY_OPERATOR` | | | | | |
| `PLATFORM_ADMIN` | | | | | |

다중 role은 각 role capability의 합집합이며 배열 순서에 의존하지 않는다. `PLATFORM_ADMIN`은
사건·거래 권한을 자동 상속하지 않는다(보안 아키텍처 4장). `RULE_OPERATOR`·`RECOVERY_OPERATOR`·
`PLATFORM_ADMIN`이 가진 `rule-version:*`, `recovery:*`, `platform:*`, `ai-operations:*`,
`ai-usage:*`에는 이 client가 호출할 수 있는 endpoint가 없으므로 capability를 정의하지 않는다.
`behavior-event:read`, `detection:read`, `ai-report:read`, `ai-report:create`도 같은 이유로
제외한다. 따라서 이 세 role은 현재 도달 가능한 route·action이 0개이며, 정상 로그인 상태에서
접근 거부 화면을 보는 것이 정의된 동작이다.

`CapabilitySet`은 동결되어 있고 내부 `Set`을 밖으로 내보내지 않는다. `granted`는 동결된
배열이며 canonical 순서로 정렬하므로 role 순서가 결과를 바꾸지 않는다.

### guard 상태

`RequireCapability`는 인증 상태를 4갈래로 명시 처리한다.

| 인증 상태 | 렌더 |
| --- | --- |
| `initializing`, `authenticating` | `role="status"`의 `Checking access...` (거부로 확정하지 않음) |
| `unauthenticated`, `error` | `Sign in required` 안내. 자동 redirect·자동 로그인 없음 |
| `authenticated` + capability 보유 | children |
| `authenticated` + capability 없음 | `AccessDeniedPage` |

- 아직 결정되지 않은 상태를 거부로 표시하지 않는다. 권한 있는 사용자에게 거부 화면을 잠깐
  보였다가 통과시키면 사용자가 새로고침으로 실제 거부를 넘기려 하게 된다.
- `error`도 별도 문구 없이 `Sign in required`로 수렴한다. 실패 사유는 App Shell의 인증 status
  영역이 이미 고정 메시지로 표시한다.
- 권한 없는 action은 `disabled`가 아니라 DOM에서 제거한다. disabled 컨트롤은 접근성 트리에
  남고 속성 하나로 되살아난다.
- 접근 거부·로그인 필요·확인 중 화면은 role·authority·claim·subject를 출력하지 않는 고정
  문구만 쓴다. 어떤 role이면 통과하는지도 알려주지 않는다.
- guard는 API 호출 여부를 바꾸지 않는다. capability가 없다고 요청을 가로채지 않으며, 401은
  기존 session-bound invalidation으로 권한 UI를 즉시 제거하고, 403은 session·memory token과
  capability를 그대로 유지한다.
- session 무효화·logout·session 교체는 `AuthSession`을 통째로 바꾸므로 capability가 다시
  계산된다. 이전 session의 capability는 남지 않는다.
- 정상 로그인 상태에서 capability가 0개인 경우는 도달 가능한 endpoint가 없는
  `RULE_OPERATOR`·`RECOVERY_OPERATOR`·`PLATFORM_ADMIN`뿐이다. role이 하나도 없는 session은
  게시되지 않으므로 존재하지 않는다.
- 그럼에도 guard는 빈 `roles`와 `roles` 누락에서 거부로 수렴하는지 확인한다. 판정이 타입에만
  기대면 안 되기 때문이다. 이 두 값은 port가 만들 수 없으므로 공용 fake를 느슨하게 만들지 않고
  `src/app/RequireCapability.test.tsx` 안의 test 전용 unsafe helper 한 곳에서만 생성한다.

### capability 이름 경계

거래 화면의 Frontend capability는 `transaction:view`이고 Backend authority는 `transaction:read`다.
사건 화면의 Frontend capability는 `case:view`이고 Backend authority는 `case:read`다. 서로 다른
계층의 이름이므로 코드·문서·테스트에서 혼용하지 않는다. Frontend capability는 "이 client가 실제로
도달할 수 있는 화면과 endpoint 묶음"을, Backend authority는 "access token이 실제로 통과하는
endpoint 권한"을 뜻한다. 두 이름 중 최종 결정권은 Backend authority에만 있다.

### navigation 적용

`AppShell`의 거래 navigation 항목은 `useCapabilities()`가 `transaction:view`를 포함할 때만,
사건 navigation 항목은 `case:view`를 포함할 때만 렌더한다. 두 항목은 각자의 capability를 따로
확인하며 한쪽 결과를 재사용하지 않는다. 현재 capability matrix에서는 `FDS_VIEWER`,
`FDS_ANALYST`, `FDS_APPROVER`가 두 capability를 함께 갖지만, table이 갈라지면 각 항목이 독립적으로
따라간다.

`disabled`나 `hidden`이 아니라 DOM에서 아예 제거하므로 `RULE_OPERATOR`, `RECOVERY_OPERATOR`,
`PLATFORM_ADMIN` session에는 `/transactions`와 `/cases` 문자열 자체가 남지 않는다.
`initializing`, `authenticating`, `unauthenticated`, `signing-out`, `error`에서도 같다. 현재 위치는
`aria-current="page"`로 표시한다.

사건 navigation의 `aria-current`는 prefix가 아니라 **정확 일치**로 판정한다. React Router `NavLink`는
`to` 하위 전체를 active로 보고 `end`를 붙여도 query·fragment를 무시하므로, 사건 link는 `Link`에
browser location으로 계산한 `aria-current`를 직접 부여한다. `pathname`이 exact `/cases`이고 `search`와
`hash`가 모두 비어 있을 때만 `aria-current="page"`이며, `/cases/`, `/cases/{uuid}`, `/casesx`,
`/cases?caseStatus=OPEN`, `/cases#content`, 그리고 routing되지 않는 404 주소에서는 attribute 자체가
없다(`"false"` 문자열로 남기지 않는다). link의 `href`는 어느 경우에도 exact `/cases`이며, capability가
없는 role에는 link도 `/cases` 문자열도 계속 없다. 거래 navigation의 판정은 바꾸지 않았다.

## Health API 경계

- `src/api/healthApi.ts`는 이번 Issue에서 오직 Spring Boot Backend의 `GET /api/health`만
  호출한다.
- Authorization header를 추가하지 않으며(대소문자·plain object·`Headers`·tuple array 등 모든
  형태 기준으로 검증) 요청 본문이 없다.
- 자동 retry는 없다. 요청당 `fetch` 호출은 정확히 1회이며, `src/api/httpClient.ts`가
  `AbortController` 기반 단일 5초 deadline을 fetch 시작부터 응답 헤더 수신, status 판정, body
  read, JSON parsing까지 요청 생애주기 전체에 적용한다(fetch용 5초 + body용 5초처럼 최대 10초가
  되는 구조가 아니다). deadline을 넘기면 `AbortController.abort()`를 호출하고, mock이 abort를
  무시하더라도 요청은 자체 deadline으로 bounded time 안에 `TimeoutError`로 settle한다. timer는
  모든 성공·실패 경로에서 해제하며, deadline 이후 도착하는 body resolve/reject는 unhandled
  rejection이나 상태 변경을 만들지 않는다.
- 성공 응답은 `{"status":"UP","service":"backend"}` 정확한 두 필드만 유효하다. 필드 누락,
  타입·값 불일치, 추가 필드는 모두 `InvalidResponseError`로 분류하며 HTTP 200이어도 마찬가지다.
- 오류는 `TimeoutError`, `NetworkError`, `HttpError`, `InvalidResponseError` 네 가지로만
  분류한다. `HttpError`는 실제 2xx가 아닌 상태 코드에만 사용하며, non-2xx 응답의 body는 오류
  분류를 위해 읽거나 노출하지 않는다.
- 오류 응답 본문 원문, stack, 내부 예외 메시지는 사용자 화면에 노출하지 않는다. 화면에는 고정된
  안전 메시지만 표시한다.
- `X-Trace-Id` 응답 헤더는 공식 정규식 `^[A-Za-z0-9][A-Za-z0-9._:-]{7,63}$`(길이 8~64, 첫 문자
  영문·숫자, 이후 영문·숫자·`.`·`_`·`:`·`-`)에 전체 일치할 때만 참고용으로 보관한다. trim이나
  normalization으로 잘못된 입력을 정상화하지 않으며, 일치하지 않는 값은 폐기하고 오류 객체나
  화면에 보존하지 않는다.
- `src/api/useHealth.ts`는 module-level in-flight 요청 registry로 동시 호출(React StrictMode의
  setup→cleanup→setup 포함)을 하나의 실제 요청으로 공유한다. 성공 결과를 영구 캐시하지 않으며
  요청이 settle되면 registry에서 즉시 제거하므로, 실제 unmount 이후의 remount는 항상 새 fetch를
  시작한다. unmount 이후에는 state를 갱신하지 않고, loading 중 추가 사용자 동작이나 오류 상태가
  아닐 때의 retry 호출은 fetch를 추가로 만들지 않는다.
- FastAPI, management port(8081), Prometheus, Grafana, Alertmanager, External Risk를 직접
  호출하지 않는다. Backend 외 서비스를 프론트엔드에서 직접 호출하지 않는다.
- `healthApi.ts`는 endpoint registry와 `AuthClient` 어느 쪽에도 의존하지 않는다.

## 인증 Backend API 경계

`src/api/authorizedClient.ts`는 로그인한 USER를 대신해 승인된 Backend 업무 endpoint를
호출하는 **transport**다. Issue #245에서 이 transport 위에 업무 typed API module과 query·
pagination 계약을 얹었고, 화면·route·navigation·button·hook·상태관리는 여전히 포함하지
않는다. 설계 근거는
[`ADR-010`](../docs/07-decisions/ADR-010-frontend-authenticated-backend-api-boundary.md)을
따른다.

### Endpoint allowlist

호출자는 URL·method·query·header를 전달하지 않는다. endpoint key가 method와 path를 함께
결정하며, 등록되지 않은 key는 network 호출 이전에 거부된다.

| Endpoint key | Method | Path |
| --- | --- | --- |
| `transaction-list` | GET | `/api/v1/transactions` |
| `transaction-detail` | GET | `/api/v1/transactions/{transactionId}` |
| `case-list` | GET | `/api/v1/cases` |
| `case-detail` | GET | `/api/v1/cases/{caseId}` |
| `case-note-list` | GET | `/api/v1/cases/{caseId}/notes` |
| `case-audit-list` | GET | `/api/v1/cases/{caseId}/audit-logs` |
| `case-status-change` | PATCH | `/api/v1/cases/{caseId}/status` |
| `case-assignee-change` | PATCH | `/api/v1/cases/{caseId}/assignee` |
| `case-resolution-create` | POST | `/api/v1/cases/{caseId}/resolution` |
| `case-note-create` | POST | `/api/v1/cases/{caseId}/notes` |

이 10개는 Backend production endpoint matrix의 USER principal 행과 정확히 일치한다. 필요한
authority는 Backend가 강제하며 프론트엔드는 이를 판단하지 않는다.

다음에는 endpoint key 자체가 없으므로 credential을 전달할 코드 경로가 존재하지 않는다.

- `GET /api/health` (public Health client가 credential 없이 호출)
- SERVICE 전용 `POST /api/v1/transactions`, `POST /api/v1/behavior-events`
- `/actuator/**`와 management listener 8081
- FastAPI AI Service, External Risk Provider, Prometheus, Grafana, Alertmanager
- 그 밖의 모든 외부 origin과 문서에만 존재하는 후보 endpoint

GET은 body를 허용하지 않고 PATCH·POST만 JSON body를 보낸다. `Authorization`과
`Content-Type`을 호출자가 override할 수 없고 custom header 입력도 제공하지 않는다.

### URL과 path parameter

`caseId`와 `transactionId`는 canonical lowercase UUID v4/RFC variant
(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)만 허용한다.
대문자 UUID, 다른 version·variant, 공백, prefix·suffix, slash, backslash, `%2F`, `%2e%2e`,
`%25`, semicolon parameter, dot traversal, protocol-relative URL, userinfo, query, fragment,
trailing slash는 모두 fetch 이전에 거부한다.

URL은 검증된 `VITE_API_BASE_URL`과 endpoint descriptor로만 조립한다. `VITE_API_BASE_URL`이
path prefix를 가질 수 있으므로 base pathname과 endpoint pathname의 결합 결과 전체를 비교
대상으로 삼고, 조립 결과를 다시 파싱해 protocol·origin·username·password·pathname·search·
hash를 **exact** 비교한다. `startsWith()`나 substring 판정은 사용하지 않는다. 허용되지 않는
URL이면 Authorization을 만들기 전에 실패하고 fetch는 0회다.

이 검증은 세 계층에서 독립적으로 이루어진다.

1. **URL helper** — 조립 결과를 재파싱해 near-miss URL을 거부한다.
2. **transport** — 보유한 URL이 승인된 Backend USER 요청이며 호출자가 요청한 바로 그
   endpoint인지 다시 확인한다. URL이 어떻게 만들어졌는지에 의존하지 않으므로, 다른 승인
   endpoint에 도달하는 것도 거부한다.
3. **credential capability** — 위의 "Token 경계와 port 분리" 참조.

한 계층의 결함을 다른 계층이 가리지 않도록 각각 별도로 검증한다.

### query parameter

호출자는 raw query string도, 완성된 URL도, `URL`·`URLSearchParams` 객체도 전달하지 않는다.
endpoint별 typed plain object만 전달하며, registry가 선언한 순서대로
`URLSearchParams.set()`으로만 조립한다. `set()`이므로 한 이름에 값이 정확히 하나이고,
값 안의 `&`·`=`·`#`·`%`·공백은 percent-encoding되어 query 구조가 되지 못한다. opaque
참조값에 구분자가 들어 있어도 그것은 데이터이지 두 번째 parameter가 아니다.

query를 선언하는 endpoint는 목록 네 개뿐이다.

| Endpoint key | 허용 query |
| --- | --- |
| `transaction-list` | `occurredAtFrom`, `occurredAtTo`, `transactionType`, `processingStatus`, `externalCustomerRef`, `accountRef`, `page`, `size`, `sort` |
| `case-list` | `caseStatus`, `finalDisposition`, `assigneeRef`, `createdAtFrom`, `createdAtTo`, `lastChangedAtFrom`, `lastChangedAtTo`, `transactionId`, `page`, `size`, `sort` |
| `case-note-list` | `page`, `size`, `sort` |
| `case-audit-list` | `page`, `size`, `sort` |

나머지 여섯 개(detail 둘, write 넷)는 query를 선언하지 않는다. 이 endpoint들은 query가
붙은 URL뿐 아니라 **호출자가 query 인자를 전달했다는 사실 자체**를 거부하며, 빈 객체
`{}`도 예외가 아니다. `{}`를 넘겼다는 것은 호출자가 이 endpoint에 filter가 있다고 믿고
있다는 뜻이고, 그 오해는 조용히 무시하기보다 드러내는 편이 낫다.

값 규칙은 Backend validator와 같다. `page`는 0 이상 Java `int` 범위의 정수, `size`는
1~100, `sort`는 endpoint별 단일 필드의 `asc`·`desc`다. reference filter는 Backend
validator별로 **분리**한다. 거래의 `externalCustomerRef`·`accountRef`는
`TransactionQueryValidator`와 같이 Java `String.isBlank()` 하나만 적용하므로 trim·정규화
없이 원문 그대로 검색하고, `" acct "` 같은 nonblank padded 값을 허용해 그대로
percent-encoding하며(`accountRef=+acct+`), **길이 제한을 전혀 두지 않는다**. 257자든
4,096자든 non-blank이면 그대로 보낸다. 공통 structural validator에도 길이 상한이 없다.
길이는 구조가 아니라 endpoint 계약이고 두 endpoint의 계약이 다르므로, 공유 상한을 두면
Backend가 받아들일 거래 filter를 클라이언트가 먼저 거부하게 된다. `page`·`size`는 자릿수로,
`instant`·`uuid`는 문법으로, `assigneeRef`는 Backend의 128자로 각자 자기 상한을 갖는다.
사건의 `assigneeRef`는
`FraudCaseQueryValidator`의 계약대로 nonblank·128자 이하·Java `trim()` 동일을 유지한다.
두 규칙을 하나로 합치면 거래 쪽에 Backend에 없는 제약이 생기므로, 분리는 회귀 테스트로
고정되어 있다. 음수, 소수,
`Number.isSafeInteger` 초과, `NaN`·`Infinity`, 숫자처럼 보이는 문자열, 대소문자·공백 변형,
다중 정렬, unknown·inherited·symbol key는 모두 URL이 만들어지기 전에 거부한다. 시각은
trim·보정 없이 UTC ISO-8601 `Z`(fractional second 1~9자리)만 허용하고, `from > to` 범위는
Backend의 422를 기다리지 않고 요청 이전에 거부한다.

범위 비교는 **나노초 해상도**다. `Date.getTime()`은 밀리초에서 잘리므로
`2026-07-23T00:00:00.000000002Z` → `2026-07-23T00:00:00.000000001Z` 같은 역전을 같은 값으로
보고 통과시킨다. Backend는 두 값을 `Instant`로 다루므로 클라이언트도
`(epoch second, nanosecond)`로 비교하며, 이 비교는 API module이 아니라 registry의 범위
계약에 있으므로 typed builder·canonical builder·URL 재검증 세 곳이 모두 실행한다.

값 규칙의 **소유자는 endpoint descriptor**다. registry는 이름 목록이 아니라 이름과
값 validator의 쌍, 그리고 query 전체의 **교차 의미 계약**을 함께 선언하고, typed
builder와 완성 URL 재검증이 **같은 선언**을 실행한다. 그래서 typed 경로를 거치지 않고
손으로 만든 URL도 동일하게 판정된다.

개별 값만으로는 판정할 수 없는 계약이 있기 때문에 교차 계약이 필요하다.
`occurredAtFrom`은 그 자체로는 정상 instant이고, 자기보다 이른 `occurredAtTo`와 나란히
놓일 때만 422가 된다. 그래서 범위는 개별 parameter가 아니라 endpoint에 속한다.

| Endpoint | 범위 계약 |
| --- | --- |
| `transaction-list` | `occurredAtFrom <= occurredAtTo` |
| `case-list` | `createdAtFrom <= createdAtTo` |
| `case-list` | `lastChangedAtFrom <= lastChangedAtTo` |

한쪽 bound만 있으면 허용하고, 같은 값은 빈 범위로 허용하며, 두 범위는 서로 독립이다.
비교는 나노초까지 정확하다.

완성된 URL은 세 계층에서 **독립적으로** 재검증한다.

1. **endpoint registry** — `findApprovedBackendRequest`가 URL에서 query를 다시 파싱해
   이름당 값이 하나인지 확인하고, 각 값을 그 endpoint의 규칙에, query 전체를 그
   endpoint의 범위 계약에 통과시킨 뒤, 같은 canonical builder로 재조립한 결과와
   **byte-for-byte** 일치를 요구한다.
2. **transport** — 보유한 URL이 호출자가 요청한 바로 그 endpoint의 승인된 요청인지
   스스로 다시 확인한다. URL이 어떻게 만들어졌는지에 의존하지 않는다.
3. **credential capability** — token을 조회하기 전에 같은 검증을 다시 수행한다.

그래서 다음은 모두 credential 조회와 fetch **이전에** 거부된다.

- 중복 이름 `?page=0&page=1`, unknown 이름 `?unknown=1`
- 빈 query `?`, 빈 값 `?page=`, 이름 없는 값 `?=0`
- canonical form이 아닌 encoding — `%20` 대 `+`, `%70age`, `page=%30`
- 규칙을 어긴 값 — `page=-1`, `page=2147483648`, `page=00`, `size=0`, `size=101`,
  `sort=createdAt,asc`, `caseStatus=in_review`, `transactionId=not-a-uuid`,
  `createdAtFrom=2026-07-23`
- 역전된 시간 범위 — `occurredAtFrom=2026-07-24T00:00:00Z&occurredAtTo=2026-07-23T00:00:00Z`,
  그리고 밀리초 아래에서만 역전되는 `...00.000000002Z` → `...00.000000001Z`
- query를 선언하지 않은 detail·write endpoint의 모든 query

거부는 고정 오류만 반환하며 query 값이나 원문을 오류·로그에 반사하지 않는다.

transport 또는 credential capability의 재검증을 하나라도 제거하면 테스트가 실패한다.

### Token 경계와 port 분리

`AuthClient` port에는 token accessor가 없다. port는 두 개로 나뉜다.

| Port | 표면 | 전달 대상 |
| --- | --- | --- |
| `AuthClient` | `initialize`, `signIn`, `completeSignIn`, `signOut`, `onSessionInvalidated` | React tree |
| `SignOutCallbackClient` | `completeSignOut` | `AuthProvider`만 (React tree 비공개) |
| `CredentialAuthClient extends AuthClient` | 위 + `authorizeRequest` | 인증 transport만 |

```ts
authorizeRequest(request: Request): Promise<AuthorizedRequest | null>;

interface AuthorizedRequest {
  readonly request: Request;
  readonly invalidateIfCurrent: () => void;
}
```

- `request`는 `Authorization: Bearer`가 정확히 한 번 설정된 **새로운** `Request`다.
- 호출자의 원본 `Request`에는 Authorization을 설정하지 않으며, 호출자가 이미 넣어 둔
  Authorization header는 병합하지 않고 제거한 뒤 다시 설정한다.
- raw token은 반환되지 않는다. 호출자는 "인증할 수 없다"(`null`)까지만 알 수 있다.
- token은 기존 oidc-client-ts memory user store에서만 조회하고 cache·복제하지 않으며 직접
  JWT decode도 하지 않는다.
- token은 URL·body·query·오류 객체·console·Web Storage에 나타나지 않는다.

**destination은 이 capability가 직접 검증한다.** token을 조회하기 전에, 대상 `Request`가
승인된 Backend USER endpoint(exact origin·base pathname·endpoint pathname·method·UUID
parameter, query·fragment·userinfo·trailing slash·encoded path 없음)인지 스스로 확인한다.
transport의 선행 allowlist에 의존하지 않으므로, 이 capability에 임의의 `Request`를 직접
건네도 credential이 붙지 않는다. 검증 실패 시 runtime·user store 조회 0회, token 조회 0회,
Authorization 생성 0회, 반환 Request 없음, fetch 0회다.

destination이 승인되더라도, session이 없거나, memory user store가 비었거나, store 읽기가
실패했거나, token이 없거나 원문이 `b64token` 문법에 맞지 않거나, `expires_at`이
지났거나, memory user의 `sub`가 게시된 session과 다르거나, store 읽기 중 session이
교체·종료되면 `null`을 반환하고 호출자는 `AuthenticationRequiredError`로 끝낸다. 이때도
fetch는 0회다.

요청은 `credentials: "omit"`(Backend CORS는 `allowCredentials=false`)과
`redirect: "error"`(승인된 endpoint 중 redirect하는 것이 없다)로 전송한다.

**Bearer 문법은 두 지점에서 확인한다.** 어느 한쪽이 다른 쪽을 대신하지 않는다.

1. **adapter 선검증** — credential capability는 memory user store에서 읽은 raw
   `access_token`을, `Headers.set()`을 부르기 **전에** `b64token`
   (`1*( ALPHA / DIGIT / "-" / "." / "_" / "~" / "+" / "/" ) *"="`) 문법으로 원문 그대로
   검사한다. 플랫폼 `Headers.set()`은 값의 앞뒤 whitespace를 스스로 제거하므로,
   `"opaque.token "` 같은 token은 header가 되는 순간 `Bearer opaque.token`으로 정리되어
   header만 보는 검사를 통과해 버린다. 그래서 header가 아니라 원문을 본다. 문법 검사
   다음에 오는 session ownership 최종 검사도 header를 만들기 **전에** 끝나고, 그 뒤의
   header 구성과 Request 반환에는 `await`가 없어 하나의 동기 구간이다.
2. **transport 재검증** — transport는 port가 돌려준 요청의 Authorization header 전체를
   같은 문법으로 다시 확인한다. `Bearer`, `Bearer `, `Bearer =`, `Bearer abc=def`,
   `bearer abc`, `Bearer  abc`, 병합된 두 credential은 모두 거부한다.

본문은 1자 이상이어야 하고 `=`는 뒤쪽 padding으로만 허용하므로 `abc=`·`abc==`는 통과하고
빈 문자열, `=abc`, `abc=def`, 공백·tab·CR·LF·제어문자·비ASCII는 거부한다. JWT 3구간 형태로
좁히지 않으므로 opaque token도 유효하다. **token을 trim·normalize·재작성하지 않는다.**
정규화로 통과시키는 경로는 없고, scheme 대소문자나 여분 공백도 임의로 허용하지 않는다.

원문 검증에 실패한 token은 `null`로 끝난다. 새 오류 type을 만들지 않고, Authorization
header를 만들지 않으며, 호출자의 원본 `Request`도 그대로 둔다. fetch 0회이고, 401이 아니므로
session invalidation·subscriber 통보·`removeUser`·teardown·retry도 0회다. token 값은 오류
메시지·로그·DOM·React state 어디에도 남지 않는다.

### Public AuthContext

`AuthProvider`가 React tree에 게시하는 값은 adapter가 아니라 **명시적으로 구성한 public
facade** object literal이다. 타입만 좁히는 방식은 사용하지 않는다. runtime에서 다음이
성립한다.

- Context value와 `context.client` 어디에도 `authorizeRequest` property가 없다.
- raw token accessor가 없다.
- object spread로 adapter를 복사하지 않는다.
- facade의 prototype은 `Object.prototype`이므로 prototype chain으로 internal method에
  도달할 수 없다.
- facade는 adapter에 memoize되어 render마다 재생성되지 않으므로 consumer effect가 다시
  실행되지 않는다.

로그인·callback·logout·auth state·subscriber lifecycle 등 기존 public 동작은 그대로다.

### 401과 403

401에서는 안전한 `X-Trace-Id`만 추출한 뒤, **그 요청과 함께 발급된**
`invalidateIfCurrent()`를 호출한다. 전역 invalidation은 사용하지 않는다.

401은 그 요청에 실린 credential을 발급한 session에 대한 정보이지, 지금 로그인되어 있는
사람에 대한 정보가 아니다. session이 게시될 때마다 새 opaque identity를 만들고, 요청을
승인할 때 그 identity를 캡처해 두었다가 callback 시점에 현재 identity와 비교한다. 다르면
완전한 no-op이다. 따라서 session A의 요청이 pending인 동안 session B가 게시되고 A의 401이
도착해도 B는 그대로 유지된다. logout·expiry로 이미 끝난 session의 늦은 401도 마찬가지다.

같은 session의 동시 401은 token expiry·15분 hard deadline·local logout과 동일한 idempotent
invalidation 경계로 수렴하므로 subscriber 통보, `removeUser()`와 transaction 정리가 각각
1회만 일어난다. 자동 redirect, 자동 재로그인, 실패 요청 replay는 없다.

403에서는 로그인 상태와 memory token을 그대로 유지한다. invalidation을 호출하지 않고
teardown·redirect·retry·replay를 하지 않는다.

두 경우 모두 response body, role, claim, token, `WWW-Authenticate` 원문과 내부 예외를
노출하지 않는다. 화면에는 고정된 안전 메시지만 표시하고, 공식 정규식
`^[A-Za-z0-9][A-Za-z0-9._:-]{7,63}$`에 **전체 일치**하는 `X-Trace-Id`만 참고 정보로 보관한다.
이 정규식은 public Health client와 공유하는 `src/api/traceId.ts`에 한 번만 정의한다.

### 오류 모델

| 오류 | 조건 |
| --- | --- |
| `AuthenticationRequiredError` | local 인증 부재. fetch 이전 |
| `RequestNotAllowedError` | allowlist·URL·parameter·method·body 계약 위반. fetch 이전 |
| `UnauthorizedError` | HTTP 401만 |
| `ForbiddenError` | HTTP 403만 |
| `HttpError` | 그 밖의 non-2xx |
| `TimeoutError` | 자체 deadline 초과 |
| `NetworkError` | fetch 실패 또는 외부 abort |
| `InvalidResponseError` | malformed JSON 또는 response validator 실패 |

non-2xx response body는 읽지 않으며 오류 객체나 메시지에 저장하지 않는다. 2xx 응답은
호출자가 제공한 type guard를 통과해야 성공이며, 검증 없이 업무 타입으로 cast하지 않는다.

### Typed API module

`src/api`의 네 module이 위 10개 endpoint를 도메인 함수로 노출한다. 화면·route·navigation·
button·hook·상태관리는 포함하지 않는다.

| Module | 함수 | Endpoint | 성공 status |
| --- | --- | --- | --- |
| `transactionApi.ts` | `fetchTransactionList` | GET `/api/v1/transactions` | 200 |
| `transactionApi.ts` | `fetchTransactionDetail` | GET `/api/v1/transactions/{transactionId}` | 200 |
| `caseApi.ts` | `fetchCaseList` | GET `/api/v1/cases` | 200 |
| `caseApi.ts` | `fetchCaseDetail` | GET `/api/v1/cases/{caseId}` | 200 |
| `caseApi.ts` | `changeCaseStatus` | PATCH `/api/v1/cases/{caseId}/status` | 200 |
| `caseApi.ts` | `changeCaseAssignee` | PATCH `/api/v1/cases/{caseId}/assignee` | 200 |
| `caseApi.ts` | `createCaseResolution` | POST `/api/v1/cases/{caseId}/resolution` | 200 |
| `investigationNoteApi.ts` | `fetchInvestigationNoteList` | GET `/api/v1/cases/{caseId}/notes` | 200 |
| `investigationNoteApi.ts` | `createInvestigationNote` | POST `/api/v1/cases/{caseId}/notes` | 201 |
| `caseAuditApi.ts` | `fetchCaseAuditList` | GET `/api/v1/cases/{caseId}/audit-logs` | 200 |

성공 status는 endpoint별로 **정확히** 비교한다. `response.ok`는 200~299 구간이므로 그것만
믿으면 `202`나 `204`가 계약된 응답으로 통과한다. 조사 메모 생성만 `201`이고 나머지 아홉
개는 `200`이며, 다른 2xx는 body를 읽지 않고 `InvalidResponseError`로 거부한다.

#### 요청 body

write 요청은 호출자의 object를 그대로 보내지 않는다. 계약 field 집합을 runtime에 exact
검증한 뒤 **새 plain object로 재구성**해 직렬화한다. unknown field, inherited field,
non-enumerable own field, symbol key, 그리고 서버가 검증된 JWT로 결정하는
`authorRef`·`actorType`·`actorId`는 전송 경로 자체가 없다. `expectedVersion`은 0 이상의
safe integer만 허용하며 누락 시 기본값을 만들지 않는다. 낙관적 잠금 토큰을 client가
지어내면 stale write가 조용한 덮어쓰기가 되기 때문이다.

`assigneeRef`의 **존재 여부 자체가 명령**인 두 endpoint에서는 key 유무를 보존한다. 상태
변경에서 key를 생략한 요청과 명시적 `null`을 보낸 요청은 서로 다른 요청이며, 담당자
변경에서는 `null`이 해제 명령이고 key 생략은 400이다. 어느 쪽도 다른 쪽으로 바꾸지 않는다.

두 workflow write는 `reasonCode`를 discriminant로 하는 discriminated union이고, runtime
validator가 같은 표를 다시 강제한다. `FraudCaseWorkflowService`가 전이마다 정확히 하나의
reason만 받고, 담당자를 실을 수 없는 두 전이에서는 `assigneeRef` **key 자체**를 거부하기
때문이다.

| `reasonCode` | `targetStatus` | `assigneeRef` |
| --- | --- | --- |
| `CASE_REVIEW_STARTED` | `IN_REVIEW` | 필수, canonical UUID v4 |
| `CASE_ADDITIONAL_INFORMATION_REQUESTED` | `ADDITIONAL_INFORMATION_REQUIRED` | key 금지 |
| `CASE_REVIEW_RESUMED` | `IN_REVIEW` | key 금지 |

| `reasonCode` | `assigneeRef` |
| --- | --- |
| `CASE_ASSIGNEE_ASSIGNED`·`CASE_ASSIGNEE_CHANGED` | canonical UUID v4 |
| `CASE_ASSIGNEE_RELEASED` | 명시적 `null`만 |

`assigneeRef: null` + `CASE_ASSIGNEE_ASSIGNED`, `assigneeRef: <uuid>` +
`CASE_ASSIGNEE_RELEASED`, `CASE_REVIEW_RESUMED` + `assigneeRef` key처럼 성공할 수 없는
조합은 credential 조회 이전에 거부한다. `assigneeRef?: never` 덕분에 두 전이의 key는
타입 단계에서도 쓸 수 없다.

#### 응답 검증

응답은 `unknown`으로 받아 모든 중첩 object의 **exact own-key**와 타입을 검증한다. key가
하나라도 없거나, 계약에 없는 key가 하나라도 더 있으면 거부한다. 배열은 항목이 하나라도
계약과 다르면 부분 채택 없이 응답 전체를 거부한다. 파싱하지 못한 행만 조용히 빠진 사건
대기열이나 감사 이력은 정상 화면처럼 보이기 때문이다.

- Java `long`(`concurrencyVersion`, `relatedTransactionCount`, `totalElements`)은
  `Number.isSafeInteger` 범위만 허용한다. 그 밖의 값은 `JSON.parse` 시점에 이미 정밀도를
  잃었으므로 복구하지 않고 fail-closed한다.
- 금액은 `number`로 변환하지 않고 계약상 10진 정수 문자열로 유지한다. 부호, 선행 0,
  소수부, 지수 표기는 거부하며 길이는 **최대 15자리**다. `amount`는 `numeric(19,4)`이므로
  15자리가 정수부의 전 범위이고, `999999999999999`는 허용하되 16자리 이상은 거부한다.
- `currencyCode`는 계약이 정의한 `KRW` **정확히 하나**만 허용한다. `USD`·`JPY`처럼
  저장될 수 없는 값을 일반 ISO 4217 형태 검사로 통과시키지 않는다.
- UUID는 canonical lowercase UUID v4만, 시각은 UTC ISO-8601 `Z`만 허용하며 달력상
  존재하지 않는 날짜(`2026-02-30T00:00:00Z`)는 형식이 맞아도 거부한다.
- page metadata는 형태뿐 아니라 산술도 검증한다. `totalPages`가 `totalElements`/`size`의
  올림인지, `first`·`last`가 `number`와 모순되지 않는지, 항목 수가 해당 페이지 위치와
  일치하는지까지 확인한다.
- 감사 항목은 discriminated union이며, 판정 기준은 `action`만이 아니라 `action`과
  `reasonCode`의 조합이다. Backend `AuditMetadataPolicy`를 그대로 옮겨 **값 사이의 관계**
  까지 검증한다. `CASE_CREATED`는 `OPEN`으로만 생성되고, `CASE_TRANSACTION_LINKED`의
  `linked`는 `true`여야 하며, `CASE_REVIEW_STARTED`는 `OPEN`·미배정에서 `IN_REVIEW`·배정
  으로만 가고, `CASE_ADDITIONAL_INFORMATION_REQUESTED`와 `CASE_REVIEW_RESUMED`는 담당자를
  유지해야 하며, `CASE_ASSIGNEE_RELEASED` 이후 담당자는 `null`이어야 하고,
  `CASE_RESOLVED`는 `IN_REVIEW → CLOSED`이면서 담당자가 바뀌지 않아야 한다. 넓은 공용
  summary 형태를 여러 reason에 재사용해 승인하지 않으므로, action과 항목 수는 맞고
  의미만 조작된 응답도 거부된다.
- 감사 `changedAt`은 mapper와 같은 **microsecond 정밀도**를 요구한다. 감사 column이
  microsecond 해상도이고 Backend가 더 정밀한 값을 만나면 페이지 전체를 500으로 처리하기
  때문이다. `...000001Z`는 허용하고 `...000000001Z`는 거부하며, 형식만 정상이고 정밀도만
  잘못된 항목이 하나 있어도 페이지 전체를 거부한다. 이 조건은 감사 `changedAt`에만
  적용하고 다른 DTO 시각은 공통 UTC validator를 그대로 쓴다.
- 성공 응답의 `X-Trace-Id`는 **부재와 malformed를 구분**한다. 부재는 허용한다(proxy가
  제거할 수 있고, 참조가 없다고 응답이 틀린 것은 아니다). 존재하지만 trace 계약을
  만족하지 못하면 `InvalidResponseError`다. `TraceIdFilter`는 모든 응답에 이 header를
  설정하므로, 계약 밖 값을 가진 2xx는 그 filter의 결과가 온전히 도착한 것이 아니다.
  유효하면 body `traceId`와 정확히 일치해야 한다. 서로 다른 요청을 가리키는 추적
  참조는 없느니만 못하다.
- non-2xx는 반대 규칙을 유지한다. 그쪽에서는 malformed header를 폐기한다. 오류는 오류로
  남아야 하고, header 하나 때문에 재분류되어서는 안 된다.

조사 메모 `content`는 신뢰할 수 없는 plain text다. 원문 그대로 보존하며 trim·정규화·해석을
하지 않는다. 표시하는 쪽이 escaping 책임을 지며 `innerHTML`·`dangerouslySetInnerHTML`에
넘기면 안 된다.

공백 판정은 Java `Character.isWhitespace(cp) || Character.isSpaceChar(cp)`와 동일한 명시적
predicate로 구현하며 JavaScript `\s`에 의존하지 않는다. 두 집합이 다르기 때문이다. NBSP
(U+00A0), FIGURE SPACE(U+2007), NARROW NO-BREAK SPACE(U+202F)는 Java가 공백으로 보므로
이들만으로 이루어진 메모는 거부하고, U+FEFF는 Java가 format 문자로 분류하므로 Backend와
동일하게 공백으로 오인하지 않는다. 길이는 계속 Unicode code point 기준 1~4,000이다.

### 요청 lifecycle

인증 준비부터 response validator까지 **하나의 5초 deadline**을 적용한다. 포함 범위는 memory
user 조회, request authorization, fetch, response header, status 처리, body read, JSON
parse, response validator다. token 조회 5초 + fetch 5초처럼 단계별 timeout을 합산하는 구조가
아니다.

deadline은 monotonic clock(`performance.now()`) 위의 하나의 절대 시각으로 한 번만 계산하고,
timer와 단계 사이의 명시적 경과시간 검사가 이를 공유한다. **동기 작업은 timer로 중단할 수
없다.** 오래 도는 동기 validator는 timer callback 실행 자체를 막으므로, 이 구현은 동기 작업을
강제 중단한다고 주장하지 않는다. 대신 deadline을 넘겨 반환된 결과를 성공으로 채택하지 않는다.
4,999ms는 성공할 수 있고 정확히 5,000ms와 그 이상은 `TimeoutError`다.

요청당 fetch는 정확히 1회이고 자동 retry는 0회이며, `POST`와 `PATCH`를 어떤 실패에서도 자동
재실행하지 않는다. client는 실패한 요청이나 직렬화된 body를 보관하지 않으므로 replay할
대상 자체가 남지 않는다.

외부 `AbortSignal`은 같은 lifecycle에 결합한다. 자체 deadline은 `TimeoutError`, 외부 abort는
`NetworkError`로 분류한다. 이미 취소된 요청은 credential을 요청하지도 전송하지도 않고,
준비 중 취소된 요청도 전송하지 않는다. 모든 종료 경로에서 timer와 abort listener를
제거하며 늦게 도착하는 resolve·reject는 unhandled rejection을 만들지 않는다.

## 거래 목록 화면

Issue #249에서 구현한 첫 production 업무 화면이다. Backend·API·DB·Infra 변경은 없고, 신규
dependency와 외부 폰트·아이콘·이미지도 없다.

### 디자인 기반

`src/styles/app.css` 한 파일이 전역 token과 console 스타일을 모두 담고, `src/main.tsx`에서 한 번
import한다. `:root`에 canvas, surface, text, muted, border, primary, focus, success, warning,
danger, spacing, radius, shadow, font stack, content max width token을 정의한다.

- 화면 구조는 깊은 navy navigation rail + 따뜻한 light-neutral canvas + 흰색 surface다.
- surface를 surface 안에 중첩하지 않는다. 작업 영역의 흰 면은 filter panel과 거래 sheet 둘뿐이다.
- glassmorphism, 보라색 gradient, 장식용 hero, 마케팅 UI를 쓰지 않는다.
- shadow token은 하나이고 rail 경계에만 쓴다. transition은 120ms 색 변화뿐이며
  `prefers-reduced-motion: reduce`에서 사실상 제거된다.
- 숫자와 시각은 `font-variant-numeric: tabular-nums`, reference와 UUID는 monospace stack이다.
- 외부 asset을 전혀 불러오지 않는다. font stack은 OS에 이미 있는 이름만 나열하며(`Pretendard`,
  `Apple SD Gothic Neo`, `Malgun Gothic` 등) 네트워크 요청을 만들지 않는다.
- badge는 색 + 서로 다른 도형 marker + 문자열 label을 함께 쓴다. 색만으로 상태를 구분하지 않는다.

### Responsive 기준

| viewport | navigation rail | filter grid |
| --- | --- | --- |
| 1440px 이상 | 240px | 4 column |
| 1280px 이상 | 208px | 3 column |
| 1024px 이상 | 180px | 2 column |
| 1024px 미만 | 상단 bar로 전환 | 1 column |

좁은 화면에서 거래 sheet는 `overflow-x: auto` 컨테이너 안에서 가로 스크롤한다. column을 숨기지
않으며, 스크롤 컨테이너는 `role="region"`과 `tabIndex=0`으로 키보드에서도 스크롤할 수 있다.

### query 계약

초기 query는 `page=0`, `size=20`, `sort=occurredAt,desc`다. 요청은 Issue #245의
`fetchTransactionList()`만 사용하며 raw `fetch`, DTO 재정의, validator 우회를 하지 않는다.

draft filter와 committed filter를 분리한다.

- 입력 중에는 요청하지 않는다. `Apply filters`(또는 field 안에서 Enter)만 draft를 commit한다.
- commit 시 page를 0으로 되돌린다. 새 filter의 4페이지는 이전 filter의 4페이지가 아니다.
- `Reset filters`는 draft·committed·page·size·sort를 모두 초기값으로 되돌린다.
- sort·page·page size 변경은 즉시 새 query가 된다. sort와 page size 변경도 page를 0으로 되돌린다.
- retry는 사용자가 `Try again`을 누를 때만 실행한다. 자동 retry·replay·polling은 0회다.

### 시간 (Asia/Seoul, KST)

모든 시각은 KST로 명시해 표시하고, 브라우저·OS timezone 설정에 의존하지 않는다.

- 표시는 `<time datetime="원본 UTC 값">2026-07-23 10:15:30 KST</time>` 형태다. `datetime`에는
  Backend가 보낸 UTC instant 원문이 그대로 들어간다.
- 변환은 `src/pages/transactions/transactionPresentation.ts`가 고정 +09:00 offset으로 수행한다.
  `Date.UTC`로 epoch를 만들고 `getUTC*`로 되읽으므로 host timezone이 개입할 지점이 없다.
- filter 입력도 KST임을 label(`From (KST)`, `To (KST)`)과 hint로 명시하고, commit 시점에
  `YYYY-MM-DDTHH:MM:SSZ` UTC로 명시 변환한다.
- `2026-02-30`처럼 존재하지 않는 날짜는 `Date.UTC` round-trip 비교로 거부한다.
- 역전된 시간 범위는 요청을 보내지 않고 화면에서 거부한다. 반열림 범위와 동일 경계는 허용한다.

### 표시 필드

현재 Transaction List API 응답에 실제로 있는 필드만 표시한다.

| column | 필드 |
| --- | --- |
| Occurred (KST) | `occurredAt`, 보조행에 `createdAt` |
| Type | `transactionType` |
| Amount | `amount`, `currencyCode` |
| Processing status | `processingStatus` |
| Transaction ID | `transactionId` |
| Customer | `externalCustomerRef` |
| From account | `senderAccountRef` |
| To account | `recipientAccountRef` (null이면 `None recorded`) |

**위험도 열은 없다.** 현재 응답에 risk score·risk level이 없으므로 만들지 않으며,
`processingStatus`를 위험도처럼 표시하거나 명명하지 않는다. status label은 처리 단계 이름이고,
badge tone은 처리 진행 상태를 뜻할 뿐 위험 판정이 아니다.

금액은 문자열 상태를 유지하며 `Number`로 변환하지 않는다. `BigInt`와 `Intl.NumberFormat`으로
그룹핑하므로 계약 최대치인 15자리 정수 문자열이 정확히 보존된다. KRW 표기이고 tabular numeral을
적용한다.

긴 UUID와 reference는 자르지 않고 cell 안에서 줄바꿈해 layout을 지킨다(`overflow-wrap: anywhere`).
`title`이나 `data-` 속성으로 값을 다시 노출하지 않으므로 DOM에 같은 값이 두 번 남지 않는다.
reference는 trim·대소문자 변환 없이 저장된 그대로 표시하고 그대로 전송한다.

`externalCustomerRef`와 `accountRef` filter는 컴포넌트 메모리에만 있다. URL query·history·
Web Storage에 넣지 않고, 로그와 오류 메시지에 원문을 반사하지 않는다.

### API hook 계약

`src/api/useTransactionList.ts`가 화면 상태를 소유한다. 인증 client는 이 hook 내부의 effect에서
`getOidcAuthClient()`로만 얻고 closure 밖으로 내보내지 않는다. hook이 반환하는 값은 `state`와
`retry` 둘뿐이며, credential capability는 React context·props·state·DOM·Web Storage 어디에도
게시되지 않는다.

- session과 query를 **identity**로 추적한다. 최신 요청만 state를 게시하고, 이전 filter·page·
  session에 속한 늦은 성공·실패는 무시된다.
- 이전 요청은 `AbortController`로 취소한다. StrictMode의 setup→cleanup→setup 재생은
  microtask로 지연된 해제와 subscriber 카운트로 흡수하므로 network 호출은 1회다.
- session 교체·logout·401 invalidation은 render 단계에서 즉시 데이터를 제거한다. effect를
  기다리지 않으므로 이전 session의 행이 한 프레임도 남지 않는다.
- 403은 session을 유지하고 고정 `Access denied` 상태를 보여준다. 통보도 invalidation도 없다.
- 오류는 `timeout`, `network`, `invalid-response`, `access-denied`, `session-lost`,
  `request-rejected`, `unknown`으로 분류하며 값에는 raw response·trace id·token·reference가
  들어가지 않는다.
- 요청당 `fetch`는 최대 1회, 자동 retry는 0회다.

### 화면 상태

초기 loading, filter 적용 loading, data, empty, safe error, timeout, network failure,
401/session loss, 403/access denied, invalid response, explicit retry를 각각 명시적으로
구현하고 테스트한다. malformed response는 한 항목만 잘못돼도 전체를 거부하고 부분 표시하지
않는다. empty 상태에는 filter 초기화 동작을 제공한다.

### 접근성

- 문서 첫 focusable 요소가 skip link이며 `main` landmark(`#main-content`)로 이동한다.
- `header`/`nav`/`main` landmark를 사용하고 현재 위치에 `aria-current="page"`를 붙인다.
- 모든 filter에 visible label이 있고 관련 filter는 `fieldset`/`legend`로 묶는다.
- 정렬 가능한 column header에 `aria-sort="ascending" | "descending"`을 붙인다.
- loading·result count·오류에 live region(`role="status"` / `role="alert"`)을 사용한다.
- 새 거부가 나타나면 오류 요약으로 focus를 옮긴다. 같은 거부에서 반복 이동하지 않는다.
- focus ring은 2px이며 navy rail 안에서는 밝은 색으로 대비를 유지한다.
- keyboard만으로 filter·Apply·Reset·정렬·페이지 이동을 모두 수행할 수 있다.
- `prefers-reduced-motion: reduce`를 존중한다.

## 거래 상세 화면

Issue #251에서 구현했다. Backend·API·DB·Infra 변경은 없고, 신규 dependency도 없다. Issue #249의
AppShell과 `app.css` token을 그대로 재사용하며, 상세 화면에 필요한 최소 class
(`.detail`, `.panel`, `.facts`)만 추가했다. 전체 UI 최종 리뉴얼은 이번 범위가 아니다.

### 목록 → 상세 탐색

거래 sheet의 `Transaction ID` cell이 상세로 가는 유일한 진입점이다.

- 행 전체에 click handler를 걸지 않는다. `<tr>`에 `role`도 `tabindex`도 붙이지 않는다.
- 실제 `<a>` anchor이므로 keyboard, Ctrl/Cmd/Shift 클릭, 가운데 클릭, `새 탭에서 열기`가 모두
  브라우저 기본 동작대로 작동한다. `preventDefault`를 직접 호출하는 handler는 없다.
- `href`는 정확히 `/transactions/{canonical transactionId}`다. query도 fragment도 붙지 않는다.
- navigation state를 사용하지 않는다. 금액·고객·계좌·device reference는 history state,
  URL query, Web Storage, hidden field, `data-` 속성 어디에도 복제되지 않는다.
- accessible name은 `View details for transaction {id}`이며, 화면에 보이는 값은 식별자 하나뿐이다.

목록으로 돌아갈 때 filter를 복원하지 않는다. reference filter는 컴포넌트 메모리 전용이라는
계약을 유지하기 위해서이며, 목록은 기본 query로 다시 시작한다.

### API hook 계약

`src/api/useTransactionDetail.ts`가 화면 상태를 소유하고, 요청은 Issue #245의
`fetchTransactionDetail()`만 사용한다. raw `fetch`, DTO 재정의, endpoint registry·validator 우회는
없다. 인증 client는 hook 내부 effect의 `getOidcAuthClient()`로만 얻고 closure 밖으로 내보내지
않는다. 반환값은 `state`와 `retry` 둘뿐이며, token·traceId·raw error·authClient·raw response는
포함되지 않는다. 200 응답의 envelope에서도 `transaction`만 게시하고 `traceId`는 hook 경계에서
버린다.

- transactionId·session·retry attempt를 identity로 추적한다. 최신 요청만 state를 게시한다.
- route가 A에서 B로 바뀌면 render 단계에서 즉시 A의 데이터를 제거한다. effect cleanup을 기다리지
  않으므로 이전 거래의 값이 새 ID 아래에 한 프레임도 남지 않는다.
- A의 늦은 성공과 늦은 실패는 모두 무시한다. abort를 쓰더라도 generation 검증을 유지한다.
- session 교체·logout·current-session 401은 즉시 상세 데이터를 제거한다. stale 401은 새 session과
  그 데이터를 제거하지 않는다.
- 403과 404는 session을 유지한다.
- 오류는 `timeout`, `network`, `invalid-response`, `not-found`, `access-denied`, `session-lost`,
  `request-rejected`, `unknown`으로 구분한다.
- 요청당 `fetch`는 정확히 1회, 자동 retry·replay·polling은 0회다. 명시적 `Try again` 1회당
  `fetch` 1회다. StrictMode에서도 중복 요청이 생기지 않는다.

### 화면 상태

initial loading, transaction ID 변경 loading, data, transaction not found(404),
access denied(403), authentication required(401/local session 없음), timeout, network failure,
invalid response, generic safe error, explicit retry, 그리고 malformed 주소에 대한 고정
invalid-route 상태를 각각 명시적으로 구현하고 테스트한다. `Try again`은 timeout·network·
invalid-response·unknown에만 제공한다. 404와 403에는 retry 버튼을 두지 않는다.

### 표시 필드

현재 Transaction Detail API 응답에 실제로 있는 13개 필드만 표시한다.

| section | 항목 |
| --- | --- |
| Transaction | `transactionId`, `transactionType`, `channel`, `processingStatus`, `amount`+`currencyCode`, `occurredAt` |
| Customer, accounts and device | `externalCustomerRef`, `senderAccountRef`, `recipientAccountRef`, `deviceRef` |
| Ledger record | `createdAt`, `updatedAt` |

**위험도·탐지 결과·사건 정보는 표시하지 않는다.** risk score, risk level, fraud probability,
`DetectionResult`, `DetectionEvidence`, `FraudCase`는 이 응답에 없으므로 만들지 않으며,
`processingStatus`를 위험도로 표현하지도 않는다. 거래 수정·재처리·사건 생성 같은 업무 action과
copy-to-clipboard도 없다. 이 화면에는 누를 것이 없다(오류 시의 `Try again` 제외).

표시 규칙은 거래 목록과 같은 helper(`transactionPresentation.ts`)를 재사용한다. 금액은 문자열과
`BigInt`로 처리해 15자리 정수 precision을 보존하고 `Number`로 변환하지 않는다. 시각은
`<time datetime="원본 UTC">... KST</time>` 형태이며 고정 +09:00 offset으로 변환한다. nullable인
`recipientAccountRef`와 `deviceRef`는 공통 상수 `ABSENT_REFERENCE_LABEL`(`None recorded`)로
표시한다. 긴 UUID·reference는 자르지 않고 자기 열 안에서 줄바꿈하므로 1024px에서도 document
전체가 가로로 스크롤되지 않으며, `title`·`aria-label`·hidden text·`data-` 속성에 값을 다시
복제하지 않는다.

### 접근성

- `Transaction {id}` page heading과 `Back to transactions` anchor를 제공한다.
- `main` > `section` > `h3` > `dl`/`dt`/`dd` semantic markup을 사용하고, 모든
  `aria-labelledby`가 실재하는 단일 id를 가리킨다.
- loading과 결과는 `role="status" aria-live="polite"` live region이, 오류는 `role="alert"`
  요약이 알린다. 새 오류가 나타나면 요약으로 focus를 옮기고, 같은 오류에서 반복 이동하지 않는다.
- processing status는 색 + 도형 marker + 문자열 label을 함께 쓴다.
- keyboard만으로 목록 복귀와 재시도를 수행할 수 있고, focus ring은 2px로 보인다.
- `prefers-reduced-motion: reduce`를 존중한다.

## 사건 목록 화면

Issue #253에서 구현했다. Backend·API·DB·Infra 변경은 없고, 신규 dependency·UI framework·font·
icon·상태관리 library도 없다. Issue #249의 AppShell과 `app.css` token을 그대로 재사용하며, 사건
목록에 필요한 최소 class(`.cases`, `.sheet--cases`, `.cell-count`)만 추가했다. `frontend-design`
skill은 사용하지 않았고 전체 UI 최종 리뉴얼은 이번 범위가 아니다.

이 화면은 **조회 전용**이다. Issue #255에서 Case ID 열만 canonical `/cases/{caseId}` link로
바꿨고, 행 전체를 clickable하게 만들지 않으며 drawer·modal·copy button도 없다. 상태 변경·담당자
변경·최종 판정·사건 생성 같은 업무 action은 하나도 없다.

### query 계약

초기 query는 `page=0`, `size=20`, `sort=lastChangedAt,desc`다. 요청은 Issue #245의
`fetchCaseList()`만 사용하며 raw `fetch`, DTO 재정의, endpoint registry·query validator·response
validator 우회를 하지 않는다.

filter는 `CaseListQuery`에 이미 있는 이름만 쓴다. `caseStatus`, `finalDisposition`, `assigneeRef`,
`createdAtFrom`/`createdAtTo`, `lastChangedAtFrom`/`lastChangedAtTo`, `transactionId`, `page`,
`size`, `sort`가 전부다.

- 입력 중에는 요청하지 않는다. `Apply filters`(또는 field 안에서 Enter)만 draft를 commit한다.
- commit 시 page를 0으로 되돌린다. sort와 page size 변경도 page를 0으로 되돌린다.
- `Reset filters`는 draft·committed·page·size·sort를 모두 초기값으로 되돌린다.
- 자동 요청은 committed query가 실제로 바뀔 때만 발생한다. 자동 retry·replay·polling은 0회이고,
  명시적 `Try again` 1회당 `fetch`는 정확히 1회다.

### filter 검증

무효한 submit은 credential 조회와 `fetch` 이전에 거부되며, 오류 문구는 입력 원문을 반사하지 않는다.

- 생성 시간 범위와 최종 변경 시간 범위는 **서로 독립적으로** 검증한다. 한쪽만 역전되어도 그
  범위의 문구로 거부한다. 반열림 범위와 동일 경계는 Backend와 같이 허용한다.
- 시각 입력은 KST label(`Opened from (KST)` 등)로 명시하고, commit 시점에
  `YYYY-MM-DDTHH:MM:SSZ` UTC로 명시 변환한다. `2026-02-30` 같은 존재하지 않는 날짜는 거부한다.
- `transactionId`는 canonical lowercase UUID v4만 허용한다. uppercase, non-v4, 잘못된 variant,
  hyphen 없는 형태, 공백이 붙은 값, percent-encoded 문자는 모두 거부하며 trim이나 case fold로
  고쳐서 보내지 않는다.
- `assigneeRef`는 `FraudCaseQueryValidator` 계약 그대로 blank 아님, 128자 이하, 자기 Java
  `trim()`과 동일이어야 한다. 거래 reference 규칙(길이 제한 없음, trim 비교 없음)과 합치지 않으므로
  `" acct "`는 거래에서는 유효하고 사건에서는 거부된다. 128자는 통과하고 129자는 거부한다.
- 연속된 서로 다른 단일 validation 오류마다 오류 요약으로 focus가 다시 이동한다. focus signature는
  거부 횟수 counter이며, 오류 개수나 금융 reference 원문을 쓰지 않는다.

`assigneeRef`와 `transactionId` filter는 컴포넌트 메모리에만 있다. URL query·history state·Web
Storage에 넣지 않고, 로그와 오류 메시지에 원문을 반사하지 않는다.

### 표시 필드

현재 Case List API 응답에 실제로 있는 7개 필드만 표시한다.

| column | 필드 |
| --- | --- |
| Last changed (KST) | `lastChangedAt` (정렬 기준) |
| Case status | `caseStatus` |
| Final disposition | `finalDisposition` |
| Assignee | `assigneeRef` |
| Related transactions | `relatedTransactionCount` |
| Opened (KST) | `createdAt` |
| Case ID | `caseId` |

- `caseId`는 plain text다. anchor도, row click도, drawer도 없다.
- `caseStatus`는 색 + 도형 marker + 문자열 label을 함께 쓴다. tone은 업무 진행 단계를 뜻할 뿐
  위험 판정이 아니며, `danger` tone을 배정하지 않는다.
- `caseStatus`와 `finalDisposition` label은 공식 enum을 exhaustive하게 매핑한다. 알 수 없는 값을
  위한 fallback 문자열은 없고, 그런 값은 API response validator가 응답 전체를 거부한다.
- `finalDisposition === null`은 정확히 `Not resolved`, `assigneeRef === null`은 정확히
  `Unassigned`로 표시한다. 둘 다 결정된 판정처럼 읽히지 않는 별도 문구다.
- `relatedTransactionCount`는 validator가 허용한 안전한 정수를 그대로 출력한다. 자리수 구분자,
  축약, 반올림을 적용하지 않는다.
- 두 시각은 `<time datetime="원본 UTC 값">... KST</time>` 형태이며 고정 +09:00 offset으로
  변환한다. 값은 한 번만 렌더링하고 hidden text로 복제하지 않는다.
- 긴 UUID·reference는 자르지 않고 자기 열 안에서 줄바꿈한다. Case ID link의 이름을 만드는
  `aria-label` 하나를 빼면 `title`·hidden text·`data-` 속성에 금융 값을 다시 복제하지 않는다.

- Case ID 열은 `<Link to={`/cases/${item.caseId}`}>`로 렌더한다. `href`는 response validator가
  canonical lowercase UUID v4로 허용한 값으로만 조립하며 query·fragment·trailing slash를 붙이지
  않는다. 거래 식별자를 사건 상세 주소에 쓰지 않는다. 화면에 보이는 값은 식별자 하나뿐이고,
  accessible name은 `aria-label`이 주는 `View case details for {caseId}`다. link의 목적을 column
  header에만 맡기지 않고 이름 자체에 적되, 거래 sheet가 쓰는 anchor 안의 `visually-hidden`
  접두사는 여기서 쓰지 않는다. 그 class는 `position: absolute`이고
  `.sheet`·`.sheet__scroll` 어느 쪽도 positioned가 아니어서 1024px에서 실제로 가로 스크롤되는 이
  sheet의 마지막 열에 두면 스크롤 컨테이너를 벗어나 document를 넓히기 때문이다. 이는 geometry
  E2E가 반례로 고정한다. 식별자가 남는 곳은 anchor text, `href`, 그 `aria-label` 세 곳뿐이고
  `title`·hidden mirror·`data-` 속성에는 다시 쓰지 않는다. `state`·`onClick`·`preventDefault`가
  없는 평범한 anchor이므로 Ctrl/Meta/Shift-click, 가운데 클릭, 새 탭 열기가 브라우저 기본 동작
  그대로다.

**표시하지 않는 것**: risk score, risk level, priority, SLA, `DetectionResult`,
`DetectionEvidence`, 거래 상세 정보, `FraudCase`에 없는 파생 정보, 업무 action, copy button.

### API hook 계약

`src/api/useCaseList.ts`가 화면 상태를 소유하며 `useTransactionList.ts`와 같은 구조다. 인증
client는 이 hook 내부의 effect에서 `getOidcAuthClient()`로만 얻고 closure 밖으로 내보내지 않는다.
hook이 반환하는 값은 `state`와 `retry` 둘뿐이며, credential capability는 React context·props·
state·DOM·Web Storage 어디에도 게시되지 않는다.

- session과 query를 **identity**로 추적한다. 최신 요청만 state를 게시하고, 이전 filter·page·
  session에 속한 늦은 성공·실패는 무시된다. 이 판정은 abort 성공 여부와 무관하므로 signal을 아예
  무시하는 fetch에서도 성립한다.
- 이전 요청은 query·session 교체 시 `AbortController`로 취소한다. StrictMode의
  setup→cleanup→setup 재생은 microtask로 지연된 해제와 subscriber 카운트로 흡수하므로 network
  호출은 1회다. unmount 이후 게시는 0회다.
- session 교체·logout·signing-out·401 invalidation은 render 단계에서 즉시 데이터를 제거한다.
  effect를 기다리지 않으므로 이전 session의 사건이 한 프레임도 남지 않는다.
- current-session 401만 session을 무효화하고 subscriber에게 1회 통보한다. 이미 교체된 session의
  늦은 401은 no-op이며 새 session의 subscriber 통보를 늘리지 않고 새 session 데이터를 지우지
  않는다. 401에서 retry·redirect·replay는 0회다.
- 403은 session과 capability를 유지하고 고정 `Access denied` 상태를 보여준다. 통보도
  invalidation도 없고 `Try again` 버튼도 제공하지 않는다.
- 오류는 `timeout`, `network`, `invalid-response`, `access-denied`, `session-lost`,
  `request-rejected`, `unknown`으로 분류하며 값에는 raw response·status·token·reference가 들어가지
  않는다.
- 성공 값은 화면이 그리는 두 필드뿐이다. API transport는 `CaseListPage` envelope의 `traceId`를
  response header와 대조해 **검증하고 보유**하지만, `useCaseList`는 성공 직후 `content`와 `page`만
  `Pick<CaseListPage, "content" | "page">`로 투영해 게시한다. `traceId`는 React state 경계 앞에서
  폐기되므로 state에 그 이름의 property가 없고, `JSON.stringify(state)`·React DevTools·직렬화하는
  error reporter 어디에도 나타나지 않는다.
- 투영은 새 객체로 만든다. envelope, `content` 배열, `page`, 각 row 모두 새 값이므로 parse된
  envelope의 어떤 객체도 React state에서 identity로 도달되지 않는다. 값은 그대로 옮기며 정규화·
  반올림·trim·기본값 적용을 하지 않고, validator를 다시 구현하지도 않는다. `src/api/caseApi.ts`는
  변경하지 않는다.
- 이 투영은 사건 목록 hook 하나에만 적용했다. `useTransactionList`는 여전히 검증된 envelope을
  그대로 게시하며, 그 보정은 이번 Issue의 범위가 아니라 별도 후속 Issue로 다룬다.

### 화면 상태

initial loading, filter 적용 loading, data, deterministic empty, access denied, authentication
required, timeout, network failure, invalid response, generic safe error, explicit retry를 각각
명시적으로 구현하고 테스트한다. malformed response는 한 항목만 잘못돼도 전체를 거부하고 부분
표시하지 않는다. 500·503·raw status·raw body·trace id는 화면에 나오지 않는다. 403에는 Retry를
표시하지 않는다.

### 접근성과 responsive

- `section` > `h2`(`#cases-heading`) > filter form > `role="status"` 결과 줄 > `table` 구조이며,
  모든 `aria-labelledby`가 실재하는 단일 id를 가리키고 중복 id는 없다.
- 모든 filter에 visible label이 있고 네 개의 `fieldset`/`legend`로 묶는다.
- `Last changed` column header에 `aria-sort="ascending" | "descending"`을 붙인다.
- loading·result count는 `role="status" aria-live="polite"`, validation·request 오류는
  `role="alert"`이 알린다. 새 오류 1회마다 요약으로 focus가 이동하며, 같은 alert DOM을 재사용해도
  다음 새 오류에서 다시 이동한다.
- keyboard만으로 filter·Apply·Reset·정렬·페이지 이동을 모두 수행할 수 있고, focus ring은 2px다.
- rail·filter grid의 1440/1280/1024 규칙은 거래 목록과 동일하다. 사건 sheet는 좁은 화면에서
  column을 숨기지 않고 `overflow-x: auto` 컨테이너 안에서만 가로 스크롤하며, document 전체는
  가로로 스크롤되지 않는다. 스크롤 컨테이너는 `role="region"`과 `tabIndex=0`으로 키보드에서도
  스크롤할 수 있다.
- `prefers-reduced-motion: reduce`와 skip link는 그대로 유지된다.

## 사건 상세 화면

`/cases/{caseId}` 사건 상세는 Issue #255에서 구현했고 Issue #257에서 하단 감사 이력 section을
추가했다. Backend·API·DB·Infra 변경은 없고 신규 dependency·UI framework·font·icon·상태관리
library도 없다. 거래 상세의 `.detail`·`.panel`·`.facts`를 유지하고 audit list wrapping만
`app.css`에 최소 추가한다.

이 화면은 **조회 전용**이다. mutation form·button·요청은 0개이며, `concurrencyVersion`은 읽기 전용
Record metadata로만 표시하고 `expectedVersion`으로 어디에도 보내지 않는다.

### 요청 계약

`GET /api/v1/cases/{caseId}`와
`GET /api/v1/cases/{caseId}/audit-logs?page=0&size=20&sort=changedAt%2Cdesc`가 병렬로 시작한다.
Issue #245의 `fetchCaseDetail()`과 `fetchCaseAuditList()`만 사용하며 raw `fetch`, DTO 재정의,
endpoint registry·response validator 우회를 하지 않는다. 두 요청 모두 body가 없다. Frontend
capability는 `case:view` 그대로이고 Backend의 기존 `case:read`·`case-audit:read`가 최종 판정한다.

### 주소 판정

거래 상세와 같은 규칙이다. 판정 대상은 `useParams()`가 아니라 `useLocation()`의
`pathname`·`search`·`hash`이며, `/cases/` 아래 정확히 한 segment를 떼어 `isCanonicalUuidV4`로
검사한다. uppercase UUID, UUID v1/v3/v5, 잘못된 RFC variant, hyphen 없는 형태, trailing slash,
추가 segment, matrix parameter, query, fragment, `%2F`·`%5C`, double encoding, malformed percent,
살아남은 whitespace·control character는 모두 거부된다. 거부된 주소는 고정된
`This is not a case address` 상태로 fail-closed되며 이 경로에서 credential 조회 0회, `fetch`
0회이고 입력값의 어떤 부분도 화면·오류 문구·`console`·DOM 속성에 출력되지 않는다.

### 표시 필드

Backend 사건 상세 응답의 **10개 필드만** 세 panel로 나눠 읽기 전용 `<dl>`로 표시한다. `<dt>`는
정확히 10개이며 계약에 없는 이름은 만들지 않는다.

| Panel | `<dt>` | 원본 필드 |
| --- | --- | --- |
| Case | `Case ID` | `caseId` |
| Case | `Case status` | `caseStatus` |
| Case | `Final disposition` | `finalDisposition` |
| Case | `Assignee` | `assigneeRef` |
| Case | `Related transactions` | `relatedTransactionCount` |
| Investigation timeline | `Created` | `createdAt` |
| Investigation timeline | `Review started` | `reviewStartedAt` |
| Investigation timeline | `Closed` | `closedAt` |
| Investigation timeline | `Last changed` | `lastChangedAt` |
| Record metadata | `Concurrency version` | `concurrencyVersion` |

- nullable 4종은 값을 추정하지 않고 presentation 단계에서만 고정 문구로 바꾼다.
  `finalDisposition === null`은 `Not decided`, `assigneeRef === null`은 `Unassigned`,
  `reviewStartedAt === null`은 `Not started`, `closedAt === null`은 `Not closed`다. 사건 목록의
  `Not resolved`와 다른 문구인 이유는 두 화면의 문구가 각 Issue에서 따로 확정됐기 때문이며, 어느
  쪽도 결정된 판정처럼 읽히지 않는다. 고정 문구는 `<time>`으로 감싸지 않는다.
- 시각은 `<time datetime="Backend가 보낸 원본 UTC 값">... KST</time>`이며 고정 +09:00 offset으로
  변환한다. 소수초 1~9자리는 `datetime`에 원문 그대로 남는다. 변경 시각의 이름은 계약 그대로
  `lastChangedAt`(`Last changed`)이고 `updatedAt`은 만들지 않는다.
- `caseStatus`는 색 + 도형 marker + 문자열 label을 함께 쓴다. `finalDisposition`은 badge 없이
  단어로만 표시하므로 어느 쪽도 색만으로 전달되지 않는다.
- `relatedTransactionCount`와 `concurrencyVersion`은 validator가 허용한 안전한 정수를 그대로
  출력한다. 자리수 구분자·축약·반올림이 없다.
- 긴 UUID와 128자 `assigneeRef`는 자르지 않고 `.detail__id`·`.facts__ref`의 기존 wrapping 규칙
  안에서 줄바꿈하므로 document가 가로로 넓어지지 않는다. 값은 DOM에 1회만 남고
  `title`·`aria-label`·hidden text·`data-` 속성으로 복제하지 않는다.

**Case record에 표시하지 않는 것**: risk score, risk level, priority, SLA, `DetectionResult`,
`DetectionEvidence`, Rule Evidence, External Risk, 연관 거래 목록, AI 사건 리포트, `traceId`,
Backend `code`·`message`·raw body, 업무 action, copy button. 조사 메모 조회 결과는 record field가
아니라 뒤의 독립 read-only Investigation notes section에만 표시한다.

### API hook 계약

`src/api/useCaseDetail.ts`가 화면 상태를 소유하며 `useTransactionDetail.ts`와 같은 구조다. 인증
client는 hook 내부 effect에서 `getOidcAuthClient()`로만 얻고 closure 밖으로 내보내지 않는다. hook이
반환하는 값은 `state`와 `retry` 둘뿐이다.

- session과 canonical case ID를 **identity**로 추적한다. 최신 요청만 state를 게시하고, 이전 ID·
  session에 속한 늦은 성공·실패·404·401은 무시된다. 이 판정은 abort 성공 여부와 무관하다.
- 이전 요청은 ID·session 교체와 unmount 시 `AbortController`로 취소한다. StrictMode의
  setup→cleanup→setup 재생에서 실제 network 호출은 1회다. unmount 이후 게시는 0회다.
- session 교체·logout·signing-out·401 invalidation은 render 단계에서 즉시 데이터를 제거한다.
- current-session 401만 session을 무효화한다. 이미 교체된 session의 늦은 401은 no-op이며 새
  session 데이터를 지우지 않는다. 403·404는 session을 유지한다.
- 상태는 `idle`, `loading`, `success`, `not-found`, `forbidden`, `error` 여섯 가지다. `not-found`와
  `forbidden`은 재시도해도 같은 답이 오는 확정 상태이므로 `error`의 종류가 아니라 별도 상태이며
  `retry()`가 no-op이다. `error`의 종류는 `timeout`, `network`, `invalid-response`,
  `session-lost`, `request-rejected`, `unknown`이다.
- 자동 retry·polling은 0회이고, 일시적 오류에서만 `Try again` 1회당 `fetch` 정확히 1회다.
- 성공 값은 10개 필드를 **field 단위로 새 객체에 투영**한 `CaseDetail` 하나뿐이다. API transport는
  envelope의 `traceId`를 response header와 대조해 검증하고 보유하지만, hook은 성공 직후 `case`
  객체의 10개 필드만 새 객체로 복사해 게시한다. envelope·`case` 원본 객체·`traceId`·raw error
  body·credential·token·header는 React state 경계 앞에서 폐기되므로
  `JSON.stringify(state)`·React DevTools·직렬화하는 error reporter 어디에도 나타나지 않는다.

### 화면 상태

loading, success, not-found, forbidden, timeout, network failure, invalid response, generic safe
error, session lost, invalid route, explicit retry를 각각 명시적으로 구현하고 테스트한다.
malformed response는 한 필드만 잘못돼도 전체를 거부하고 부분 표시하지 않는다. 404·403·500·503·
raw status·raw body·trace id는 화면에 나오지 않는다. 404와 403에는 Retry를 표시하지 않는다.

### 접근성

- `section` > `h2`(`#case-detail-heading`) > `role="status"` 결과 줄 > 세 record `h3` panel과 audit
  `h3` section 구조이며,
  모든 `aria-labelledby`가 실재하는 단일 id를 가리킨다.
- loading·결과 요약은 `role="status" aria-live="polite"`, 실패와 무효 주소는 `role="alert"`이
  알린다. 새 오류 1회마다 요약으로 focus가 이동하고, 같은 종류가 반복되면 다시 가져가지 않는다.
- `Back to cases`는 exact `/cases`로 가는 link 하나뿐이며 성공·실패·무효 주소 어디서나 제공된다.
- 1440/1280/1024 폭 모두에서 document는 가로로 스크롤되지 않는다.

### 감사 이력 section

Audit history는 별도 route 없이 record 아래 독립 `<section aria-labelledby="case-audit-heading">`으로
렌더한다. `<ol>` 안의 각 `<li><article>`은 raw action code와 KST changed time을 접근 가능한 제목으로
가지며, `reasonCode`·`actorType`과 summary enum도 번역하지 않는다. Before와 After를 항상 구분하고
summary 전체 null은 `Not applicable`, `assigneeRef` null은 `Unassigned`로 표시한다.
`CASE_NOTE_CREATED.metadata.noteId`는 링크 없는 `Note ID` 텍스트이며 caseId·actorId·traceId·raw
Backend error는 항목이나 state에 남지 않는다.

`useCaseAuditLog`는 `idle`, `loading`, `success`, `empty`, `forbidden`, `not-found`,
`authentication-required`, `timeout`, `network-error`, `invalid-response`, `generic-error`를 직접
구분한다. request identity는 session identity·caseId·page·size·고정 sort·retry attempt 전부이며,
StrictMode flight 공유, latest-wins, identity 변경/unmount abort, zero-listener settle replay와 마지막
subscriber cleanup 뒤 outcome 폐기를 적용한다. 성공/empty state는 `content`와 `page`만 보유하고
envelope·content array·item·summary·metadata·stored outcome·subscriber delivery를 field 단위 fresh
projection해 subscriber mutation이 replay에 전파되지 않는다.

pagination은 URL/history가 아닌 section local state다. 초기 page/size는 0/20, size 선택지는
20·50·100, sort는 `changedAt,desc` 고정이다. page/size 전환 즉시 이전 content를 제거하고 size·caseId·
session 변경 시 page 0으로 돌아간다. out-of-range empty page를 자동 보정 재요청하지 않는다. pager의
accessible name은 `Audit history pages`다. 상세 성공 뒤 audit 실패는 record를 유지하고 audit 내부
alert만 표시하지만, 상세 403/404는 section을 unmount해 pending/stored audit state를 폐기한다.

## 미구현 범위

- 사건 workflow·담당자 변경·최종 판정·연관 거래·Detection·Rule Evidence·AI 사건 리포트
  **화면**과 운영 대시보드. 조사 메모는 사건 상세 내부의 별도 route 없는 read-only section과
  조회 Hook만 구현됐고, 작성·수정·삭제 route·navigation·form·button·mutation은 구현되지 않았다.
- 사건 상세 화면의 mutation UI (상태 변경·담당자 변경·최종 판정 form·button·요청이 0개이며,
  `concurrencyVersion`은 읽기 전용 표시로만 쓰인다)
- 행 전체 클릭 navigation과 상세 drawer·modal (상세는 별도 route이며 행은 계속 기록이다)
- 목록으로 돌아갈 때의 filter 복원
- 거래·사건 화면의 production 업무 action button (상태 변경·담당자 변경·판정·메모 작성·수정·삭제·
  재처리·사건 생성은 범위 밖이다)
- 상세 화면의 copy-to-clipboard
- 위험도(risk score·risk level)와 탐지 결과·사건 연결 표시 (현재 Transaction API 응답에 해당
  필드가 없다)
- 사건의 위험도·우선순위·SLA 표시 (현재 Case API 응답에 해당 필드가 없다)
- 사건 상세의 `updatedAt`·`riskLevel`·`riskScore`·`transactionId` 표시 (사건 상세 응답 계약에
  해당 필드가 없으므로 만들지 않는다)
- 콘솔 전체의 최종 시각적 리뉴얼 (HomePage 문구 정리를 포함해 후속 Issue로 남아 있다)
- SPA 이전 raw request-target 검사 (reverse proxy·web server 등 production hosting 경계의
  책임이며 SPA에서 구현하지 않는다)
- 문서에만 존재하는 후보 endpoint (`GET /api/v1/cases/{caseId}/transactions` 등)
- 오류 응답 body 모델(`code`·`message`·`fieldErrors`)
- production Authorization Server와 production runtime 배포
- silent renew와 refresh token 사용은 지원하지 않으며 도입하려면 별도 승인이 필요하다.
- Local JWT fixture(Issue #225의 `infra/compose.local-jwt-e2e.yml`)는 로컬/수동 인증 E2E
  검증용 컴포넌트이며, 브라우저에서 사용하는 OIDC Provider가 아니다. Frontend browser E2E는
  stock Keycloak 26.7.3만 사용한다. Local JWT fixture와
  Keycloak은 같은 Backend issuer 설정에서 동시에 사용하지 않는다.

## 테스트

`npm run test`는 Vitest와 jsdom, Testing Library로 환경변수 검증, application entry의 fail-fast
부트스트랩, HTTP client의 요청 생애주기 전체 timeout과 오류 분류, Health API 계약과 trace id
정규식 경계, React StrictMode 아래에서의 최초 fetch 단일 실행·unmount 이후 state 미갱신·genuine
remount의 신규 fetch·loading 중 중복 retry 방지, Router와 화면 상태(loading·success·error,
명시적 재시도, 접근성 있는 role/name)를 검증한다.

거래 목록 test는 표시 규칙·hook·화면·route를 나누어 검증한다. 표시 규칙은 KST 변환(자정·연말·
윤년 경계, 존재하지 않는 날짜 거부, host timezone 비의존), KST↔UTC 왕복, 역전 범위 판정,
15자리 금액과 2^53+1 정밀도, reference 무변형 표시와 줄바꿈 판정, nullable recipient,
status label이 risk·score를 뜻하지 않음을 확인한다. hook은 session 없을 때 요청 0회,
StrictMode에서 fetch 1회, 최신 요청만 게시, 이전 요청의 늦은 성공·실패 무시, 이전 요청 abort,
session 교체·logout·401에서 즉시 data 제거, stale 401이 새 session을 제거하지 않음, 403에서
session 유지, timeout·network·invalid response 구분, 자동 retry 0회, 게시 값에 credential·
trace id 부재를 확인한다. 화면은 초기 query 계약, draft/committed 분리, apply·reset·sort·page·
page size, 역전 범위와 공백 reference 거부(요청 0회), empty·error·retry, reference의 URL·Web
Storage 비노출, `aria-sort`·live region·focus 이동·keyboard 순회를 확인한다. route는 세 허용
role의 직접 진입, 세 비허용 role의 거부와 Backend 요청 0회, unauthenticated 진입, 복귀 경로
exact `/transactions`, `/transactionsx`와 더 깊은 경로의 404를 확인한다. 사건 navigation은
`case:view`로 별도 판정하므로 role 목록도 AppShell·router test에 따로 적어 두 capability table이
갈라지면 실패하게 한다.

사건 목록 test도 같은 방식으로 나눈다. 표시 규칙(`casePresentation.test.ts`)은 두 enum label의
exhaustive 매핑과 enum 밖 값에 대한 fallback 부재, `Not resolved`·`Unassigned` 정확 문구,
grouping 없는 정수 출력, KST 변환과 Seoul 날짜 경계, 역전 범위 판정, assignee reference의 blank·
128/129자 경계·Java trim 비교, 거래 reference와 다른 규칙(`" acct "`), transaction filter의
canonical UUID 판정, 긴 reference의 무변형 표시를 확인한다. hook(`useCaseList.test.ts`)은 session
없을 때 요청 0회와 credential 조회 0회, StrictMode에서 fetch 1회, 요청 URL이 `/api/v1/cases`이고
11개 filter가 모두 전달됨, 최신 요청만 게시, abort를 무시하는 fetch에서도 이전 요청의 늦은 성공·
실패 무시, query·session 교체 시 이전 요청 abort와 즉시 data 제거, unmount 이후 게시 0회,
current 401의 session 1회 무효화와 retry·redirect 0회, stale 401이 새 session을 제거하지 않음,
403에서 session 유지와 자동 retry 0회, timeout·network·invalid response·negative count 거부,
계약 밖 query의 요청 전 거부, 120초 대기에도 polling 0회, 게시 값에 credential 부재를 확인한다.
화면(`CaseListPage.test.tsx`)은 초기 query 계약(`page`·`size`·`sort` 세 개뿐), 7개 필드 표시와
7개 column, null disposition·null assignee 문구, 정수 무변형 출력, 두 시각의 KST 표시와 UTC
`datetime`, 긴 reference의 단일 노출, 위험도·priority·SLA·업무 action 부재, 사건 ID link의
accessible name과 exact `href`, 행의 control 부재, draft/committed 분리, 모든 filter의 canonical 전송과 apply page 0 reset,
reset 후 기본 query 정확 일치, 두 시간 범위 각각의 역전 거부, malformed UUID와 assignee 거부에서
credential 조회·fetch 0회, reference의 URL·history·Web Storage 비노출, empty·403·500·invalid
response·retry, 중복 id 부재와 `aria-labelledby` 해석, visible label과 4개 fieldset, keyboard
순회·정렬·pagination, 연속된 서로 다른 단일 오류에서의 focus 재이동을 확인한다. route test는 세
허용 role의 직접 진입과 실제 `/api/v1/cases` 요청, 세 비허용 role의 거부와 Backend 요청·credential
조회 0회, unauthenticated·인증 오류·session invalidation, 복귀 경로 exact `/cases`,
`/casesx`와 `/cases/{uuid}/notes`·`/audit-logs`·`/resolution`의 404, `/cases/`가 상세가 아니라
목록으로 정규화됨, rail keyboard 진입과 `aria-current`를 확인한다. shell test는 `aria-current`의
정확 일치 계약을 반례로 고정한다. exact `/cases`와 canonical `/cases/{uuid}`에서만
`aria-current="page"`가 있고 문서 전체에 그 attribute가 하나뿐이며, `/cases/`, `/casesx`,
`/cases?caseStatus=OPEN`, `/cases#content`, `/cases/{uuid}?tab=raw`, `/cases/{uuid}#assignee`,
`/cases/{uuid}/`, uppercase·v1·잘못된 variant·malformed caseId, `/cases/{uuid}/notes`, 무관한 404
주소에서는 사건 link의 `href`가 여전히 `/cases`인 채로 attribute가 없고
`[aria-current="false"]`도 0개다.

거래 상세 test도 같은 방식으로 나눈다. hook은 malformed transactionId에서 credential 조회 0회·
fetch 0회, StrictMode fetch 1회, 200·404·403·401·stale 401·timeout·network·invalid response·
generic error, latest-wins와 이전 요청의 늦은 성공·실패 무시, route A→B 교체 시 즉시 데이터 제거,
session 교체·logout·invalidation에서 데이터 제거, 403·404에서 session 유지, explicit retry 1회당
fetch 1회, 자동 retry 0회, unmount 이후 게시 0회, 반환값에 raw error·trace id·token·envelope
부재를 확인한다. 화면은 13개 필드 표시와 그 외 필드 부재, 15자리 금액, KST 표시와 UTC `datetime`,
nullable reference, 긴 reference의 단일 노출, 모든 enum label, 위험도·탐지·사건·업무 action DOM
부재, 404·403·503·network·timeout·invalid-response 문구, `Try again` 1회당 요청 1회, 오류 요약
focus, malformed 주소의 요청 0회와 원문 비노출을 확인한다. 목록의 상세 link는 accessible name,
exact `href`, 행 click handler·role 부재, navigation state 부재, 금융 값이 href·state·`data-`
속성에 없음, keyboard 진입, modifier click과 가운데 click의 기본 동작 보존을 확인한다. 복귀 경로는
canonical 상세 path 허용과 uppercase·non-v4·잘못된 variant·trailing slash·하위 path·query·
fragment·encoded slash·backslash·중복 slash·prefix sibling·absolute·protocol-relative·userinfo·
다른 scheme·host·port·공백·제어문자의 거부, 그리고 입력값 비노출을 확인한다. AppShell 통합
test는 실제 shell과 fake `AuthClient`로 canonical 상세 주소·`/transactions`·`/`·`/health`의
정확한 복귀와, query·fragment·둘 다가 붙은 상세 주소의 `/` fail-closed, 그 원문이 `signIn`
인자·DOM·`console`에 없음, Backend 요청 0회를 확인한다. `pathname`만 넘기는 구현으로 되돌리면
query·fragment 반례가 실패한다.

사건 상세 test도 같은 방식으로 나눈다. hook(`useCaseDetail.test.ts`)은 malformed caseId 15종에서
credential 조회 0회·fetch 0회, 요청 URL이 `/api/v1/cases/{caseId}`이고 query 없음, StrictMode
fetch 1회, 200·404·403·401·stale 401·timeout·network·invalid response·generic error,
latest-wins와 이전 case·session에 속한 늦은 성공·실패·404 무시, case A→B 교체 시 즉시 데이터
제거와 이전 요청 abort, session 교체·logout·invalidation에서 데이터 제거, 403·404에서 session
유지와 통보 0회, current 401의 session 1회 무효화, explicit retry 1회당 fetch 1회, 404·403에서
`retry()` no-op, 자동 retry·polling 0회, unmount 이후 게시 0회를 확인한다. 게시 경계는 반환 key가
정확히 `state`·`retry` 두 개이고 `state.data`의 key가 정확히 10개이며, Backend가 보낸 key 순서를
뒤집어도 게시된 객체의 key 순서가 hook 투영의 순서라는 점으로 **envelope과 게시 객체의 identity
분리**를 관찰한다. `traceId`·`"case"`·access token·raw error body가 `JSON.stringify(state)`에
없음, nullable 4종이 `null` 그대로 유지됨, 403·404 게시 값이 각각 `{"status":"forbidden"}`과
`{"status":"not-found"}` 문자열 전체와 정확히 일치함도 확인한다. 화면
(`CaseDetailPage.test.tsx`)은 10개 `<dt>`/`<dd>`와 그 정확한 이름 목록, `Updated`·`updatedAt`
문자열 부재, 네 nullable의 고정 문구와 그 자리의 `<time>` 부재, KST 표시와 원본 UTC `datetime`
(소수초 9자리 포함), 4개 status·3개 disposition label, status의 badge marker와 disposition의
badge 부재, 128자 assignee의 단일 노출, 큰 정수 무변형 출력, risk·detection·evidence·note·audit·
AI 부재, button·form·textbox·combobox·checkbox 0개와 link 1개, 404·403·503·network·timeout·
invalid-response 문구, 404·403의 retry 부재와 `dd` 0개, `Try again` 1회당 요청 1회, 자동 retry
0회, 오류 요약 focus와 재이동 억제, malformed 주소 11종의 요청 0회·원문 비노출, `Back to cases`
navigation을 확인한다. route test는 세 허용 role의 직접 진입과 실제
`/api/v1/cases/{caseId}` 요청(query 없음), 세 비허용 role의 거부와 Backend 요청·credential 조회
0회, unauthenticated·인증 오류·session invalidation, 복귀 경로 exact `/cases/{caseId}`,
query·fragment가 붙은 주소의 `/` fail-closed, malformed 주소 17종의 요청 0회, 목록 Case ID link를
클릭해 상세에 도달하고 그 사이 모든 요청이 `GET`임을 확인한다. 복귀 경로 test는 canonical
`/cases/{caseId}` 허용과 uppercase·non-v4·잘못된 variant·hyphen 없는 형태·trailing slash·하위
path(`/notes`·`/audit-logs`·`/resolution`·`/transactions`)·query·fragment·encoded slash·
backslash·중복 slash·prefix sibling·absolute·protocol-relative·userinfo·다른 scheme·host·port·
공백·제어문자·wrapper object의 거부, 두 parameterized route가 서로 바뀌지 않음, 그리고 입력값
비노출을 확인한다.

router test는 두 계층을 분리해 기술한다. `browser URL parser boundary` 블록은 표준 URL parser가
dot segment·encoded dot segment·raw backslash·trailing control character·trailing space를
canonical 상세 route로 정규화한다는 **브라우저의 성질**을 기록하며, 이는 Frontend가 raw 입력을
검사했다는 증거가 아니다. 같은 블록에서 정규화 결과 location에도 capability guard와 미인증 거부가
그대로 적용되고 Backend 요청이 0회임을 확인한다. malformed 주소 목록은 표준 URL parser를 통과해도
표현이 그대로 남는 입력만 담고, 각 case가 실제로 그렇다는 것을 먼저 단언한다. `%2e%2e`처럼
브라우저가 정규화해 없애는 입력의 거부는 `MemoryRouter`에서만 도달 가능한 defence in depth로
따로 표시한다.

인증 경계 unit test는 실제 Authorization Server 없이 `AuthClient` port와 fake adapter로 검증한다.
OIDC 설정 exact 값, memory user store와 prefix가 붙은 session transaction store, prefix 밖
key 보존, transaction 정리 시점, callback URL 조기 정리와 fail-closed, exact key 기반
callback parameter 판정, 복귀 경로 allowlist, StrictMode 아래 initialize·callback 1회 실행과
listener 등록·해제 균형, unmount 이후 미갱신, fake clock 기반 15분 hard deadline(899,999ms
유지 / 900,000ms 무효화, 60분 token도 15분, 더 짧은 token은 그 시각), idempotent
invalidation, local logout, public route에서 Authorization Server 요청 0회를 확인한다.

remote logout unit test는 정상 logout에서 session·credential·timer가 모두 0이 되고 subscriber
통보·`removeUser()`·`signoutRedirect()`가 각각 정확히 1회임을, `signoutRedirect()` 인자가 고정
logout marker 하나뿐이며 ID token도 access token도 담기지 않음을, redirect 시작 전에 transaction
sweep이 0회이고 memory user가 그대로 남아 있음을 확인한다. 동시 logout 3회는 하나의 flight를
공유하고 settle 이후 재호출도 redirect를 늘리지 않는다. expiry·401이 먼저 session을 끝낸 경우
logout은 redirect 없이 고정 오류로 끝나고 통보는 총 1회다. ID token 누락·빈 문자열·공백·2/4
segment·빈 segment·비 base64url 문자·개행·숫자·null·object·array와 getter 예외, user store 읽기
예외는 모두 redirect 0회로 거부하고 local logout은 1회로 유지한다. `metadataSeed`는 issuer·
scheme·host·port·path·query·fragment·userinfo·`javascript:`로 변조된 discovery
`end_session_endpoint`를 모두 덮어쓰며 다른 endpoint는 건드리지 않는다.
`post_logout_redirect_uri`는 trailing slash·경로·query·fragment·userinfo·opaque scheme·빈 origin을
거부한다. transaction record는 `si:r`/`so:r` 두 schema로 분리해 malformed JSON, key/id 불일치,
unknown·missing field, login nonce 삭제, logout nonce·PKCE verifier 삽입, popup·silent·unknown
request type, schema 교차를 set·get·remove 모두에서 거부한다. logout callback은 captured URL을
그대로 1회만 넘기고 `removeUser()`·invalidation·통보를 0회 수행하며, session B가 살아 있는 상태
에서 성공·실패 어느 쪽으로 끝나도 B의 session·credential·deadline을 그대로 둔다. root callback
분류는 bare 진입·무관 parameter·다른 경로·다른 origin·다른 port·다른 scheme을 `none`으로,
fragment·userinfo·중복 state·blank state·unknown parameter·`error` 없는 `error_description`·
`;` 포함 state를 거부로 판정하고, 거부 시 library 호출 0회와 주소창 정리를 확인한다. React 계층은
`signing-out`에서 session·displayName·양쪽 버튼이 사라지는 것, 반복 클릭에도 logout 1회, 진행 중
`Sign in` 0회, 실패 시 고정 문자열과 재로그인 가능, persisted `pageshow`에서 unauthenticated 유지와
자동 retry·자동 login 0회를 확인한다.

credential capability는 외부 origin, 유사 host, public health, SERVICE ingestion 두 개,
management 8081, FastAPI 후보 origin, Prometheus, Grafana, Alertmanager, 승인 path의 trailing
slash·query·fragment·잘못된 method, encoded slash·period·percent·semicolon path를 실제 adapter에
직접 전달해도 모두 거부하며 그때 token 조회와 credential 부착이 0임을 확인한다. public
AuthContext는 실제 `AuthProvider`와 `useAuth()`로 렌더한 뒤 runtime property를 관찰해
credential capability 부재, adapter 미게시, prototype chain 도달 불가, facade identity
안정성과 기존 public 기능 유지를 확인한다. session ownership은 실제 adapter와 transport를
연결해 동일 session 동시 401 단일화, session B 게시 후 A의 401에서 B 유지와 B 후속 요청 성공,
logout·expiry 후 늦은 401의 no-op, authorization 중 session 교체 시 credential 반환 0,
stale callback 반복 호출 no-op, 새 sign-in의 teardown sequencing을 확인한다. deadline은
monotonic clock을 직접 제어해 4,999ms 성공, 정확히 5,000ms와 5,001ms·6,000ms timeout,
timer callback 없이도 성립하는 post-check를 확인한다. Bearer 문법은 두 계층을 각각 검증한다.
실제 adapter에 raw `access_token`을 심어 `opaque.token`·`abc`·`abc.def`·`abc-._~+/`·`abc=`·
`abc==`는 통과하고 `"opaque.token "`, `" opaque.token"`, `"opaque token"`, tab·CR·LF 포함
token, `abc=def`, `=abc`, 빈 문자열은 header 생성 0회로 거부되며 원본 `Request`가 그대로임을
확인한다. 실제 adapter와 실제 transport를 연결한 상태에서도 앞뒤 공백·tab이 붙은 raw token은
fetch 0회이고 platform 정규화로 정상 token이 되어 전송되지 않으며, 이것이 401로 오분류되지
않고 invalidation·teardown·retry가 0회임을 확인한다. transport 단독으로는 허용·거부
credential 형태를 각각 검증한다. production exact URL wiring은 URL builder를 near-miss
결과로 대체해 transport 자체의 검사가 실패하는지 확인한다.

인증 transport는 endpoint registry의 exact method·path matrix, 중복 key·method·template
부재, GET body 거부와 PATCH·POST JSON body 허용, canonical UUID v4 허용과 잘못된 UUID·path
traversal·encoded path 거부, query·fragment·trailing slash 생성 불가, base path prefix를
포함한 URL exact 조립, external origin 생성 불가, unknown endpoint key의 fetch 0회를
검증한다. 승인된 10개 endpoint에서만 Bearer가 전달되고 그 값이 정확히 하나이며, health·
SERVICE ingestion·management·AI·관측·외부 origin에는 Authorization도 fetch도 0회이고,
URL·body·query에 token이 없으며, 미인증·만료·부재 memory user에서 fetch가 0회임을 확인한다.
raw token accessor가 없고 JWT decode가 없으며 Web Storage에 token이 저장되지 않는 것도
함께 확인한다.

query·pagination과 typed API module은 반례 중심으로 검증한다. query는 `page`·`size`·
`sort`·filter의 음수·소수·unsafe integer·Java `int` 초과·`NaN`·숫자 문자열·unknown·
대소문자·공백 변형, UUID의 version·variant·대문자·공백 오류, offset·local·달력상 불가능한
시각과 `from > to`를 각각 요청 조립 이전 거부로 확인하고, 그때 credential 조회 0회·fetch
0회임을 함께 확인한다. `from > to`는 세 범위 각각에 대해, 밀리초 아래에서만 역전되는
반례(`...00.000000002Z` → `...00.000000001Z`)까지 포함해 확인하고, 두 사건 범위가 서로
독립임도 확인한다. 거래 reference와 사건 assignee가 같은 값(`" acct "`, 129자)에서 서로
다르게 판정되는지도 확인해 두 규칙이 다시 합쳐지는 퇴행을 잡는다.

3중 검증은 **손으로 만든 URL**을 각 계층에 직접 주입해 확인한다. registry에는
`findApprovedBackendRequest`로, transport에는 URL builder를 교체해, credential capability
에는 실제 adapter에 `Request`를 그대로 건네서 각각 `page=-1`·`size=101`·
`sort=createdAt,asc`·`transactionId=not-a-uuid`·`caseStatus=in_review`·**역전된 시간
범위**·중복·unknown·비정규 encoding·빈 query를 거부하고 그때 token 조회 0회·
Authorization 0회·fetch 0회임을 확인한다. transport 또는 credential capability의 재검증을 제거하면 해당 테스트가
실패한다. opaque 참조값 안의 `&`·`=`·`#`·`%`가 query 구조가 되지 못하는 것, detail·write
endpoint가 `{}`를 포함한 query 인자 자체를 거부하는 것, inherited·non-enumerable·symbol
key와 `URLSearchParams`·class instance 같은 non-plain container가 거부되는 것도 확인한다.

응답 검증은 endpoint별로 missing key, unknown key, enum·UUID·시각·금액·safe integer 오류,
그리고 page metadata의 산술·`first`/`last`·항목 수 불일치를 각각 거부로 확인한다. 금액은
15자리 허용·16자리 거부를, `currencyCode`는 `KRW` 외 `USD`·`JPY` 거부를 확인한다. 배열
항목 하나만 malformed여도 응답 전체가 거부된다.

감사 `changedAt`은 microsecond 허용·nanosecond 거부를 여섯 action 모두에서 확인하고,
정밀도만 잘못된 항목 하나가 페이지 전체를 거부시키는지도 확인한다.

감사 항목은 값 관계까지 mutation으로 확인한다. action·reasonCode·key 집합·항목 수를 모두
그대로 두고 값 하나만 바꾼 반례 — `CASE_CREATED`인데 `CLOSED`, `CASE_TRANSACTION_LINKED`
인데 `linked=false`, `CASE_REVIEW_STARTED`·`CASE_REVIEW_RESUMED`·
`CASE_ADDITIONAL_INFORMATION_REQUESTED`의 잘못된 상태·담당자 변화, `CASE_ASSIGNEE_RELEASED`
이후 담당자가 남아 있는 행, `IN_REVIEW → CLOSED`와 담당자 유지를 어긴 `CASE_RESOLVED` —
이 각각을 거부하는지 확인하고, 요청한 `caseId`와 다른 감사 페이지도 거부되는지 확인한다.

write 조합은 표 전체를 검증한다. reason과 `targetStatus`의 불일치, `CASE_REVIEW_STARTED`의
담당자 누락·`null`·비정규 UUID, 담당자를 실을 수 없는 두 전이의 `assigneeRef` key(값이
`null`이든 UUID든), `CASE_ASSIGNEE_RELEASED`+UUID와 `CASE_ASSIGNEE_ASSIGNED`+`null`을
각각 credential 조회 0회·fetch 0회 거부로 확인한다.

status는 조사 메모 생성의 `201`과 나머지 아홉 개의 `200`을 각각 정확히 요구하고 다른 2xx
에서 body를 읽지 않음을 확인한다. trace는 header가 없으면 body 값을 쓰고, header가 있지만
계약을 만족하지 못하면 `InvalidResponseError`이며, 유효하지만 body와 다르면 거부하고,
non-2xx에서는 계속 malformed header를 폐기해 오류가 오류로 남는지 확인한다. write 실패
(`400`·`401`·`403`·`404`·`409`·`422`·`500`·`503`·network)에서 fetch 1회·retry 0회·replay
0회와 401 invalidation·403 session 유지·5초 deadline·raw body 비노출 회귀도 함께 확인한다.

Keycloak browser E2E는 Chromium 1 worker·retry 0·strict TLS로 실제 Keycloak 로그인과
refresh-token, state, 저장 nonce 삭제·blank·불일치, ID token nonce 누락·불일치, PKCE,
callback 재사용 반례를 각각 실행한다. 정상 경로는 authorize URL과 저장 transaction의 nonce가
동일하고 256-bit base64url인지, authorize/transaction scope가 exact `openid profile`인지와 stock
`profile`의 `preferred_username` claim이 실제 발급되는지도 확인한다. repository root의
`frontend/scripts/run-keycloak-e2e.ps1`이 격리 Chromium container와 전용 Compose project 수명주기를
함께 관리한다.

브라우저는 host 브라우저가 아니다. Chromium은 `@playwright/test` 버전과 정확히 일치하고 immutable
digest로 고정한 공식 Playwright Linux image에서 `frontend/Dockerfile.playwright-e2e`로 빌드한 전용
image 안에서 실행되고, 실행별 NSS database에 검증된 `localhost` leaf만 `certutil -t "P,,"`로
등록한다. runner는 Windows `CurrentUser`·`LocalMachine` 인증서 저장소를 열지 않고
`ignoreHTTPSErrors`·`--ignore-certificate-errors`·SPKI allowlist·hostname 우회도 쓰지 않으므로
strict TLS와 `localhost` 이름 검증이 그대로 성립한다.

준비와 실행은 분리되어 있다. `-Mode Prepare`만 registry와 package archive에 접근해 Compose image를
pull·build하고 browser image를 빌드하며, `apt`도 그 image build 안에서만 exact version으로 실행되고
같은 layer에서 cache가 제거된다. 공식 `-Mode Run`은 필요한 모든 image가 local에 있는지 먼저 확인하고,
어긋나면 registry에 접근하지 않고 고정 오류로 끝난다.

browser image 판정은 label에 두지 않는다. label은 누구나 복제할 수 있으므로 Run은 label을 조기 중단
용도로만 읽고, local 검사만으로 고정 digest base가 local에 있는지, base와 준비 image가 모두
linux/amd64인지, base의 RootFS layer 목록이 준비 image RootFS의 exact ordered prefix인지, Dockerfile
구조대로 layer가 정확히 하나만 추가되었는지, `Config.User`가 exact `pwuser`인지를 확인한다. 이어서
`--network none --read-only --cap-drop ALL --security-opt no-new-privileges` container를 한 번
실행해 `libnss3-tools`·`libnss3` exact version, `certutil` 위치·소속 package와 실제 NSS database
생성·조회, exact Node version, mount된 `playwright-core`의 exact version과 그 `browsers.json`이
요구하는 browser revision 집합, Chromium·headless shell 실행 파일의 존재·실행 가능·exact build,
실제 UID·GID·계정 이름을 확인하고, 같은 container가 `NoNewPrivs`·capability 0·read-only root·
loopback 단독 interface까지 확인한다. 실패는 관측값을 반사하지 않는 고정 문장이다.

mount가 사용자 지정인지는 container 안에서는 판정할 수 없다. `--tmpfs /dev/shm/x`는 daemon이
직접 mount하는 `/dev/shm`과, `/proc` 아래의 bind는 runc가 만드는 kernel 가상 mount와 구분되지
않기 때문이다. 따라서 이 runner가 만드는 모든 container는 `docker create`로 멈춰서 만들고,
`docker container inspect`로 daemon의 기록을 다시 읽어 승인된 구성과 exact하게 비교한 뒤,
검사한 바로 그 exact container ID만 `docker start`한다. 비교 대상은 network mode와 attach된 network,
`ReadonlyRootfs`, `Privileged`, `CapAdd`·`CapDrop`, `SecurityOpt`, device·device request·`VolumesFrom`,
`Binds`와 `Mounts`의 physical source·destination·type·read-only·propagation, `Tmpfs`의 target과
option, 그리고 published port 전체다. 승인 목록에 없는 것은 이름되지 않았다는 이유로 거부되므로
Docker socket·repository working tree·credential·private key mount는 target을 어디로 잡아도 container가
시작되기 전에 끝난다. bind source는 repository 안의 link 없는 physical 경로로 먼저 해석하므로
junction이나 prefix만 같은 경로도 다른 값으로 거부된다.

Compose는 `--no-build --pull never`, browser는 `--pull never`로 시작하며 대상은 tag가
아니라 시작 전에 확인한 exact image ID이고, 시작 직후 container가 그 image로 돌고 있는지 한 번 더
확인한다. 종료 경로의 `finally`는 이번 실행이 만든 exact container ID만 제거한다. Run 경로에는 npm과 npx 실행이 없다. runner는 설치된 `node_modules/@playwright/test/cli.js`를
현재 Node executable에 argument vector로 넘겨 직접 실행하고, web server는 `frontend`를 cwd로 삼아
`node node_modules/vite/bin/vite.js`를 실행한다. 둘 다 package 이름이 아니라 설치된 파일 경로라서
registry fallback 자체가 없고, entry point가 없거나 package version이 다르면 container를 하나도 만들기
전에 고정 오류로 끝난다. Run 단계의 build·pull·`apt`·npm/npx 다운로드는 0회다.

container loopback의 5173·8443만 host loopback으로 TCP 중계하며 TLS를 종료하지 않는다. 중계 목록은
code에 고정되어 있고 relay는 인자를 받지 않으므로 CLI argument·환경 변수·임의 host/port로 넓힐 수
없다. privileged mode, 추가 capability, Docker socket mount는 사용하지 않고 dependency와 lockfile도
변경하지 않는다. trace·screenshot·video·HTML report는 만들지 않고 임시 output·NSS·profile은 종료 시
제거한다.
자세한 내용은 [`Local Keycloak runbook`](../docs/09-deployment/local-keycloak-auth-e2e-runbook.md)
3.1에 있다.

lifecycle은 인증 준비 pending, fetch pending, body·JSON pending timeout이 각각 전체 5초로
합산되는 것, 외부 abort와 이미 aborted signal, timeout 시 `AbortController` 호출,
timer·listener 정리, late resolve·reject의 unhandled rejection 0을 검증한다. 401은 오류
타입, 안전 traceId 유지와 unsafe traceId 폐기, body 원문 비노출, 즉시 무효화, 동시 401의
단일 teardown, GET·POST·PATCH replay 0과 redirect 0을, 403은 오류 타입, session 유지,
teardown 0, retry·replay·redirect 0과 role·claim·body·token 비노출을 확인한다.

권한 UI는 반례 중심으로 검증한다. role 판정은 `principal_type`의 `SERVICE`·대소문자·공백·
non-string 변형, `roles`의 unknown·SERVICE·`ROLE_` prefix·Keycloak 내부 role·duplicate·
대소문자·공백·개행·prototype property 이름·non-string 원소·비배열, 그리고 claim getter 예외를
각각 전체 거부로 확인하고, 정상 claim에서는 동결·복사·순서 보존을 확인한다. adapter는 거부 시
session 게시 0회, subscriber 통보 0회, `removeUser()` 1회, transaction 정리, 이후 `initialize()`
`{ session: null }`, `authorizeRequest()` `null`과 claim 원문 비노출을 확인한다.
capability는 USER role 6종 각각의 exact 집합, 다중 role 합집합과 순서 무관성,
`PLATFORM_ADMIN`의 사건·거래 미상속, 타입으로는 도달할 수 없는 빈 role·미정 role에서의 0개,
`CapabilitySet` 동결과 mutable `Set` 비노출을 검증한다. 빈 `roles` claim은 session 게시 0회·
subscriber 통보 0회·`removeUser()` 1회·transaction 정리·고정 `AuthCallbackError`로 끝나는 것을
adapter 테스트가 따로 확인한다. guard는 test 전용 `MemoryRouter` route에서 initializing·authenticating·
unauthenticated·error·허용·거부 6가지 결과, 직접 URL 진입, 권한 없는 action의 DOM 부재와
`disabled`·`aria-disabled` 잔여 0개, session 무효화·logout·session 교체 시 즉시 제거와 재계산,
StrictMode에서의 단일 결정, DOM에 role·`principal_type`·subject·token 문자열 비노출,
`authorizeRequest` 호출 0회를 확인한다.

test 공용 fake(`src/test/fakeAuthClient.ts`)는 session을 대신 만들어 주지 않는다. session 입력은
port와 동일하게 role이 required이며 비어 있을 수 없고, callback 결과를 지정하지 않은 채
`completeSignIn()`을 호출하면 고정된 test 전용 오류로 실패하며 session을 게시하지 않는다. 따라서
callback 성공 테스트는 각자 session과 유효한 USER role을 명시한다.

Web Storage에 token을 저장하는 코드는 없다. sessionStorage에는 transaction record만
존재하며 JWT 형태 값이 남지 않는다. IndexedDB는 사용하지 않고, Backend `GET /api/health`
요청에는 계속 `Authorization` header를 붙이지 않는다. Issue #225의 Local JWT fixture는
사용하지도 수정하지도 않는다.

설계 근거는
[`ADR-009`](../docs/07-decisions/ADR-009-frontend-oidc-pkce-memory-token-boundary.md)와
[`ADR-010`](../docs/07-decisions/ADR-010-frontend-authenticated-backend-api-boundary.md),
[`ADR-011`](../docs/07-decisions/ADR-011-keycloak-authorization-server-and-claim-contract.md)을
따른다.

## Issue #235 Keycloak runtime 연동 상태

local/dev stock Keycloak 26.7.3 runtime과 `finguardops-frontend` public client 구성은
[`Local Keycloak runbook`](../docs/09-deployment/local-keycloak-auth-e2e-runbook.md)에 구현되었다.
authority는 `https://localhost:8443/realms/finguardops-local`이고 Authorization Code Flow와 PKCE
S256만 허용한다. Local JWT fixture issuer는 Frontend OIDC authority가 아니다.

Issue #239는 외부 USER password로 실제 browser login/callback과 USER access/ID token 계약,
refresh-token 반환 fail-closed를 Chromium에서 검증한다.

role 기반 UI는 두 부분으로 나누어 본다. Issue #243에서 검증된 session profile의 USER role로 UI
capability를 결정하는 판정 계층(`src/auth/userRoles.ts`, `src/auth/capabilities.ts`,
`src/auth/useCapabilities.ts`)과 `RequireCapability` guard 컴포넌트를 구현했고, Issue #249에서
이를 `/transactions` production route와 rail navigation 항목에 적용했고, Issue #253에서 같은
방식을 `case:view`와 `/cases`에 적용했고, Issue #255에서 같은 capability를 `/cases/{caseId}`
사건 상세 route에 적용했다. 자세한 내용은 위 `권한 UI 경계`, `거래 목록 화면`, `사건 목록 화면`과
`사건 상세 화면`에 있다. Keycloak remote logout은 Issue #247에서 구현했다.
자세한 내용은 위 `Remote logout (RP-initiated)`에 있다.

Issue #249의 browser E2E는 실제 Keycloak USER(`FDS_ANALYST`)로 로그인해 거래 navigation 표시,
`/transactions` 진입, 실제 Backend `GET /api/v1/transactions` 1회 200 응답, 1440/1280/1024
viewport의 rail 폭·filter column 수·가로 overflow 부재, skip link가 첫 tab stop임, filter 적용이
정확히 요청 1회임, 자동 retry 0회, credential 비노출을 확인한다. 이 relay는 status만이 아니라
Backend가 실제로 보낸 body를 그대로 브라우저에 전달하므로, 화면이 보는 것은 실제 응답이다.
API mock과 test 전용 auth bypass는 사용하지 않고 strict TLS를 유지한다.

Issue #251은 여기에 거래 상세 404 경계 1개를 더한다(#251 시점 총 14개). 로그아웃 상태에서 canonical 상세
주소로 직접 진입해 Backend 요청 0회를 확인하고, 그 주소에서 실제 Keycloak 로그인을 수행해 복귀
경로가 정확히 같은 canonical 상세 주소임을 확인한 뒤, 실제 Backend
`GET /api/v1/transactions/{transactionId}`가 1회 404로 답하는 것과 `Transaction not found` 화면,
session 유지, retry 버튼 부재, 1초 후에도 요청 수 불변(자동 retry 0회), 주소창에 query·fragment
부재, credential 비노출, 세 viewport에서 가로 overflow 부재를 확인한다.

같은 test는 relay가 받은 **실제 Backend 404 body**를 E2E process memory에서만 파싱해 `code`,
`message`, `traceId`를 추출하고, 그 값 각각이 rendered text, markup, 모든 attribute, `title`,
주소창, `history.state`, `localStorage`·`sessionStorage`, `console`에 존재하지 않음을 확인한다.
body를 mock하거나 sentinel을 주입하지 않고, 브라우저로 전달하는 기존 relay 경계도 바꾸지 않는다.
parse 실패, JSON object 아님, 세 필드의 타입 불일치나 blank는 모두 고정 문구로 fail-closed되고,
추출한 값과 body 원문은 실패 메시지·reporter·stdout·stderr·파일 어디에도 출력하지 않는다.
고정 UI 문구가 Backend 값을 포함하면 exact-value 비교로 provenance를 구분할 수 없으므로, 그
경우에도 통과시키지 않고 고정 문구로 실패한다.

이 runtime에는 seed된 거래 row가 없으므로 상세 **200** 화면은 E2E에서 만들지 않는다. 상세 200
상태는 typed API fixture를 쓰는 component/hook test의 책임이며, API mock을 실제 Backend E2E 성공
증거로 표현하지 않는다.

Issue #253은 여기에 사건 목록 시나리오 1개, relay 경계 negative 2개·positive 1개, test-only
`CaseTable` geometry 1개를 더했다(#253 시점 실제 Keycloak·Backend 통합 15개 + relay 경계 3개 +
geometry 1개 = 총 19개). 기존 14개의 assertion은 그대로 유지한다. 뒤의 4개는 Keycloak·Backend 통합 시나리오가
아니라 relay 경계와 browser geometry 전용이며, 통합 증거로 집계하지 않는다. 신규 test는 실제
Keycloak `FDS_ANALYST`로 로그인해 사건 navigation 표시와
`aria-current`, `/cases` 진입, 실제 Backend `GET /api/v1/cases` 1회 200을 확인한다. relay가 socket에
실제로 쓴 request target을 initial `/api/v1/cases?page=0&size=20&sort=lastChangedAt%2Cdesc`와
filter 적용 후
`/api/v1/cases?caseStatus=OPEN&assigneeRef=E2E+Assignee+01&page=0&size=20&sort=lastChangedAt%2Cdesc`
고정 문자열로 비교하므로, 어느 단계에서 trim·case fold·filter 누락이 생겨도 실패한다. 관측값은
브라우저 URL 복사본이 아니라 relay가 쓴 target에서 만든다. 이어서 initial 1회·Apply 1회, 1초 대기
후에도 요청 수 불변(자동 retry·replay·polling 0회), assignee reference가 typing한 field 밖(주소창·
history state·Web Storage·`console`·다른 attribute·다른 control)에 남지 않음, credential 비노출,
1440/1280/1024 viewport의 rail 폭·filter column 수·document 가로 overflow 부재, non-GET 요청 0회를
확인한다.

Issue #255는 여기에 사건 상세 404 경계 1개를 더한다(실제 Keycloak·Backend 통합 **16개** + relay
경계 3개 + geometry 1개 = **총 20개**, worker 1·retries 0·strict TLS). 기존 19개의 assertion은
그대로 유지하고, relay test 개수도 3개 그대로다. 신규 test는 로그아웃 상태에서 canonical
`/cases/{caseId}`로 직접 진입해 Backend 요청 0회를 확인하고, 그 주소에서 실제 Keycloak 로그인을
수행해 복귀 경로가 정확히 같은 canonical 상세 주소임을 확인한 뒤, 실제 Backend
`GET /api/v1/cases/{caseId}`가 query 없이 1회 404로 답하는 것과 `Case not found` 화면, 상세 field
(`dd`) 0개, session 유지, retry 버튼 부재, 1초 후에도 요청 수 불변(자동 retry 0회), non-GET 요청
0회, 사건 read 계약 밖 endpoint 요청 0회, `Back to cases` link와 그 클릭으로 exact `/cases` 복귀,
rail의 `aria-current="page"`가 문서 전체에 하나뿐임, 주소창에 query·fragment 부재, credential
비노출, 세 viewport에서 가로 overflow 부재를 확인한다. 거래 상세 404 test와 같은 방식으로 relay가
받은 **실제 Backend 404 body**를 E2E process memory에서만 파싱해 `code`·`message`·`traceId`가
rendered text, markup, 모든 attribute, `title`, 주소창, `history.state`, Web Storage, `console`에
없음을 확인하며, 고정 UI 문구가 Backend 값을 포함하면 통과시키지 않고 고정 문구로 실패한다.

이 runtime에는 seed된 사건 row가 없으므로 상세 **200** 화면은 E2E에서 만들지 않는다. 상세 200
상태는 typed API fixture를 쓰는 component/hook test의 책임이며, Backend·DB에 fixture나 seed를
추가하지 않는다.

Issue #257은 같은 사건 상세 시나리오에서 detail과 audit 요청이 병렬로 시작되고 audit가 exact
`/api/v1/cases/{caseId}/audit-logs?page=0&size=20&sort=changedAt%2Cdesc`로 정확히 1회 나가는지를
추가로 검증한다. 두 요청 모두 실제 Backend 404이며 session은 유지된다. 상세 404가 확정된 뒤에는 audit
heading·alert·pager가 렌더되지 않고, 1초 후에도 두 endpoint의 요청 수가 각각 1회라 자동
retry·replay·polling은 0회다. 실제 404의 `code`·`message`·`traceId`와 credential·JWT·cookie는
rendered text, DOM·attribute, URL·history, Web Storage와 console에 남지 않는다. 실제 통합 test 수는
계속 **16개**이고 populated audit history는 별도 production-component geometry fixture가 맡는다.
Issue #257 당시 분해는 실제 Keycloak·Backend 통합 **16개** + relay 경계 **3개** + geometry
**2개** = **총 21개**였다. 현재 Issue #259 분해는 아래 최신 절의 **16 + 3 + 3 = 22개**다.

relay가 socket에 쓰는 주소는 **exact endpoint closed allowlist**로 결정한다. 현재 승인된 read
descriptor는 정확히 6종이다.

| endpoint | method | path | query |
| --- | --- | --- | --- |
| 거래 목록 | `GET` | exact `/api/v1/transactions` | 승인된 이름 9개 |
| 거래 상세 | `GET` | `/api/v1/transactions/{canonical lowercase UUID v4}` | 없음 |
| 사건 목록 | `GET` | exact `/api/v1/cases` | 승인된 이름 11개 |
| 사건 상세 | `GET` | `/api/v1/cases/{canonical lowercase UUID v4}` | 없음 |
| 사건 감사 이력 | `GET` | `/api/v1/cases/{canonical lowercase UUID v4}/audit-logs` | `page`·`size`·`sort`와 endpoint별 의미값 검증 |
| 사건 조사 메모 | `GET` | `/api/v1/cases/{canonical lowercase UUID v4}/notes` | `page`·`size`·`sort`와 endpoint별 의미값 검증 |

Issue #257은 감사 이력 한 줄을, Issue #259는 조사 메모 한 줄을 추가했다. 승인된 write probe는 계속 정확히 1종,
`POST /api/v1/cases/{canonical lowercase UUID v4}/resolution`이며 query를 실을 수 없다. 사건 상세
주소와 audit 주소는 `GET`으로만 도달할 수 있고, 같은 주소의 `POST`·`PATCH`·`PUT`·`DELETE`는 거부된다.
`/status`, `/assignee`, `/resolution` `GET`, `/transactions`,
`/ai-reports/current`, 사건 status·assignee write, note create를 포함해 그 밖의 모든 `/api/v1/**`
주소는 계속 허용하지 않는다. 화면 E2E가 실제로 필요로 할 때만 allowlist를 넓히며, 미사용 endpoint를
미리 허용하지 않는다.

**query가 없는 `GET`도 같은 exact path allowlist를 통과해야 한다.** path 문법 검증과 endpoint 승인
검증은 별개다. `/api/v1/...` 형태가 문법적으로 유효하다는 사실은 이 suite가 그 endpoint를 읽어도
된다는 뜻이 아니며, lowercase 경로라는 이유만으로 승인되지 않는다. Issue #257 이전 구현은 query가
없는 `GET`을 path allowlist 검사 **전에** 반환했으므로, 당시 미승인 주소였던
`GET /api/v1/cases/{caseId}/notes`나 `GET /api/v1/unknown`도 Backend socket에 그대로 쓰였다. 검증은 하나의 선형 경로가 아니라 method로
갈라진다. 공통 단계로 origin·userinfo·빈 query marker·fragment·method 문자열 문법·path 문법을 이
순서대로 검증한 뒤 method가 분기를 결정하며, write probe와 `GET`은 서로 다른 경로로 target을 반환한다.
`GET`이 아닌 요청은 write probe 분기로 가서 method와 exact endpoint 조합이 승인된 write probe인지,
이어서 query가 없는지를 확인하고 path를 반환한다. `GET`은 read 분기로 가서 method와 exact endpoint
조합이 승인된 read path인지 먼저 확인하고, query가 없으면 그대로 path를 반환한다. query가 있을 때만
그 endpoint가 query를 받도록 선언됐는지(`queryNames !== null`)를 확인한 뒤 duplicate query 이름 →
빈 query 이름/값 → endpoint별 query allowlist → endpoint별 의미값 → canonical encoding → target의 쓰기 가능한 문자
구성을 차례로 검증하고 target을 반환한다.

endpoint별로 query 목록을 따로 두므로 사건 filter가 거래 endpoint로, 또는 그 반대로 relay될 수 없다.
중복·미승인·빈 이름/값·non-canonical encoding query는 Backend에 쓰이기 전에 거부되고, 실패 메시지는
URL·path·UUID·query·userinfo 원문을 반사하지 않는다.

HTTP method 검사는 query 유무를 확인하기 **전에** 수행한다. relay는 읽기 전용이며, 유일한 예외는
`RELAYABLE_WRITE_PROBES`에 method·주소·query 부재로 선언한 사건 resolution 403 boundary probe
하나뿐이다. 따라서 query가 없는 `POST /api/v1/cases`·`PATCH /api/v1/cases`·`POST /api/v1/transactions`도
process spawn과 socket 전달 이전에 거부된다.

이 경계는 relay test 3개가 고정한다.

- 첫 번째 negative test는 기존 31개에 audit query 반례 17개를 더한 **48개** 거부 반례를 실행한다.
  기존 반례는 query 없는 write 3종, 선언된 filter를 실은 write, write
  probe의 잘못된 method와 query, endpoint 간 query 오염 5종, 미승인 이름, 중복 이름, `page=%30`,
  non-canonical sort comma, 빈 이름/값, bare `?` 6종(거래 목록·사건 목록·거래 상세·**사건 상세**·
  resolution probe·fragment 결합), 사건 상세 주소의 query 3종(`page=0`, 선언된 사건 filter
  `caseStatus=OPEN`, 임의 `include=notes`)과 fragment, fragment, userinfo, 미승인 path, 다른
  origin)를 실행한다. `page`와 `caseStatus`는 사건 **목록**이 실제로 선언하는 이름이므로, 한 segment
  아래에서도 그대로 허용되지 않는다는 것이 여기서 고정된다.
  추가 17개는 duplicate·empty name·empty value·unknown·bare `?`, raw comma와 `%30`, page 음수·
  leading zero·소수·int32 overflow, size 0·101, 잘못된 sort field·direction, transaction-list filter와
  case-list filter 혼합이다.
- 두 번째 negative test는 Issue #257 당시 **59개**였고, Issue #259에서 notes의 고유 path·method
  반례를 더해 현재 method+URL 고유 조합 **70개**인 미승인 endpoint 반례를 실행한다. canonical
  investigation notes `GET`은 승인된 read이고, query가 없는 valid lowercase 미승인 `GET`은 사건
  status·assignee·resolution·related transactions·`ai-reports/current`·임의 suffix,
  `behavior-events`, 존재하지 않는 endpoint, 거래 상세 뒤 임의
  suffix), 거래 상세 주소의 non-canonical 표기 9종(uppercase, UUID v1, invalid RFC variant, hyphen
  없는 UUID, trailing slash, 추가 segment, encoded slash, encoded backslash, percent-encoded 문자),
  **사건 상세 주소의 non-canonical 표기 9종**(uppercase, UUID v1, invalid RFC variant, hyphen 없는
  UUID, percent-encoded 문자, trailing slash, 추가 segment, encoded slash, encoded backslash),
  audit endpoint의 non-canonical 표기 9종과 fragment, **사건 상세 주소의 method confusion 4종**
  (`POST`·`PATCH`·`PUT`·`DELETE`), audit endpoint의 method confusion 4종(기존 write 반례의 `POST`와
  이번에 추가한 `PATCH`·`PUT`·`DELETE`), write probe 확장 반례
  14종(사건 status·assignee·notes·audit-logs·임의 suffix `POST`, resolution의 `PATCH`·`PUT`·
  `DELETE`, query를 실은 resolution `POST`, uppercase·UUID v1·invalid variant 식별자, trailing
  slash, 추가 segment)이다. notes의 `POST`·`PATCH`·`PUT`·`DELETE`, trailing/extra path,
  non-canonical UUID와 notes 규칙 밖 query도 모두 미승인이다. 사건 상세와 notes의 canonical `GET`은
  이 목록에서 빠지고 아래 positive test로 옮겼다.
- 두 negative test 모두 각 반례가 고정 문장으로 실패하고, URL·path·path segment·UUID·query·userinfo
  원문을 반사하지 않으며, relay process spawn 0회와 Backend observation 0회임을 확인한다.
- Issue #257 당시 positive test는 실제로 보내는 read **10종**(`GET /api/v1/transactions`, 승인된 query를 실은 거래 목록
  2종, canonical UUID 거래 상세, `GET /api/v1/cases`, 승인된 query를 실은 사건 목록 2종, canonical
  UUID **사건 상세**, bare audit path, canonical page/size/sort audit path)과 선언된 write probe 1종이
  그대로 통과하고, 각 target이 입력 주소와 byte
  단위로 동일하게 반환됨을 확인한다.

이 runtime에는 seed된 사건 row도 없으므로 사건 목록 E2E는 deterministic empty 상태도 통과 조건으로
인정한다. Backend·DB·Infra에 test data 생성 경로를 추가하지 않으며, 채워진 표의 **의미**는 typed API
fixture를 쓰는 component/hook test의 책임이다.

채워진 표의 **browser geometry**만 별도의 test-only fixture로 측정한다. `frontend/e2e/`의
`case-table-geometry.html`과 `case-table-geometry.tsx`는 production `CaseTable` component와
production `src/styles/app.css`를 그대로 import해 `createRoot`로 렌더링하고, 고정 synthetic
`CaseListItem` **5행**을 넣는다. 5행은 canonical lowercase UUID v4 식별자 5개(중복 없음), 128자
`assigneeRef` 1개와 `null` `assigneeRef` 1개, 네 개의 `caseStatus`(`OPEN`·`IN_REVIEW`·
`ADDITIONAL_INFORMATION_REQUIRED`·`CLOSED`) 전부, 그리고 세 개의 `finalDisposition`
(`NORMAL`·`FALSE_POSITIVE`·`CONFIRMED_FRAUD`) 전부와 미해결 `null`을 덮는다. 실제 credential·
token·고객·계좌 정보는 들어 있지 않고, 값은 모두 이 fixture 전용으로 쓴 합성 값이다. Playwright는
이 페이지를 실제 Vite origin `http://localhost:5173/e2e/case-table-geometry.html`에서 연다.

이 fixture의 경계는 분명하다. 요청을 하나도 보내지 않으므로 **API mock도 route interception도 없고**,
session이 없으므로 **인증 bypass도 없다**. production router에 test route를 추가하지 않고, `index.html`
에서 참조하지 않으며, `vite build`는 `index.html`만 빌드하므로 `dist`에 포함되지 않는다. credential·
token·실제 고객 정보는 들어 있지 않다. **실제 Backend 연동 증거가 아니며 그렇게 보고하지 않는다.**
사건 목록의 실제 Backend 증거는 위의 empty/data 분기와 실제 200 query를 쓰는 별도 test다.

fixture는 `MemoryRouter`로 감싼다. `CaseTable`의 Case ID 열이 `Link`이므로 router context가
필요하기 때문이며, `MemoryRouter`의 history는 이 페이지 memory 안에만 있어 fixture는 여전히 어디로도
navigate하지 않고 요청도 보내지 않는다. `Routes`도 route element도 없다.

geometry test는 먼저 tbody row가 정확히 5개임과, final disposition column이 `Normal`·
`False positive`·`Confirmed fraud`·`Not resolved` 네 문구를 각각 기대한 행에서 실제로 렌더하는지를
확인한다. 기대 문구는 production label map을 다시 읽지 않고 literal로 적으므로, label이 바뀌면 이
assertion이 함께 통과해 버리지 않는다. Case ID 열은 anchor의 `href`가 exact `/cases/{caseId}`이고
accessible name이 exact `View case details for {caseId}`이며 cell text는 식별자 단독이고 식별자가
cell 안에 정확히 1회만 나타남을 확인한다. 이 assertion과 1024px의 document overflow 검사가 함께, 이 열에 absolute로 배치되는
screen-reader 접두사를 넣으면 실패하도록 고정한다. 이어서 1440×900·1280×800·1024×768 각각에서 table element
존재, tbody row 5개, 7개 heading과 7개 data cell, `display:none`·`visibility:hidden`인 cell 0개, `documentElement.scrollWidth <=
clientWidth`, `body.scrollWidth <= clientWidth`, scroll container의 bounding box가 `main`과 viewport
경계 안, 사건 table `min-width: 980px`의 실제 적용을 확인한다. 1024px에서는 추가로 table
`scrollWidth > container clientWidth`와 container의 computed `overflow-x`가 `auto` 또는 `scroll`임을
확인하므로, 긴 reference가 document를 밀어내지 않는다는 주장이 "우연히 다 들어맞았다"가 아니라 실제
overflow가 컨테이너에 담긴 결과임이 드러난다.

Issue #257의 두 번째 fixture `case-audit-geometry.html`과 `case-audit-geometry.tsx`는 production
`CaseAuditPanel`과 같은 production CSS를 직접 사용한다. Backend·auth·router를 우회하거나 mock하지
않고 API 요청도 만들지 않는다. 고정 synthetic content 6개는 여섯 action, 모든 summary shape,
null before/after, null assignee, 128자 assignee reference, 긴 reason code와 canonical note UUID를
포함하며 실제 credential·금융정보는 포함하지 않는다. Playwright는 여섯 ordered-list article,
action과 changed time을 함께 가진 accessible heading, `Not applicable`·`Unassigned`, link가 아닌 Note ID를
확인하고 1440×900·1280×800·1024×768에서 document-level horizontal overflow가 없음을 측정한다.
production router·`index.html`·build entry에서는 이 fixture를 참조하지 않는다.

local realm에는 USER가 하나뿐이라 role 조합별 browser E2E는 수행하지 않았고, role·capability
판정은 단위·컴포넌트 테스트가 담당한다. Frontend production code는 access token을 직접
decode하지 않으며, 검증된 ID token role은 UI 표시 정보일 뿐이다. 최종 접근 결정은 계속
Backend의 독립적인 access-token 검증과 401/403 응답이다.

## 사건 상세 Investigation notes section (Issue #259)

`/cases/{caseId}`는 이제 Case record → Investigation notes → Audit history 순서로 읽힌다. 세 GET은
같은 React commit에서 독립적으로 시작한다. detail의 403/404만 notes와 audit를 함께 unmount하며,
notes 자체의 403/404·timeout·network·invalid response·generic error는 notes section 안에만 남는다.
403/404에는 retry가 없고 session을 유지한다. timeout·network·invalid response·generic error만 사용자가
명시적으로 재시도할 수 있으며 자동 retry·polling·last-page correction은 없다.

새 endpoint나 DTO를 만들지 않는다. `fetchInvestigationNoteList`, 기존 endpoint registry, authorized
transport, investigation-note DTO·validator, pagination helper와 KST helper를 그대로 재사용한다.
GET 200은 모든 `item.caseId === requestedCaseId`, `response.page.number === requestedPage`,
`response.page.size === requestedSize`가 exact equality일 때만 성공한다. 생략한 query의 실제 기본값은
page 0·size 20이다. trim, case-fold,
UUID 재정규화를 하지 않고 한 item이라도 다르거나 섞여 있으면 전체 응답을 `invalid-response`로
거부한다. 고정 오류에는 expected/actual ID, raw response와 traceId를 반사하지 않는다. POST 조사 메모
작성 함수와 POST 응답 계약은 이번 변경에서 수정하지 않았다.

`useCaseInvestigationNotes(caseId, page, size)`의 request identity는 session identity·caseId·page·size·
고정 `createdAt,asc`·retry attempt다. 공개 반환 key는 `state`와 `retry`뿐이다. StrictMode setup-cleanup-
setup은 grace-window flight 하나를 공유한다. listener가 0인 순간 settle된 sanitized outcome은 같은 key
replay가 사용하지만, 마지막 subscriber release 후에는 flight와 outcome을 제거한다. released 또는 이미
settled된 flight는 lazy terminal factory를 실행하지 않아 late payload projection과 failure classification을
하지 않는다. raw response→stored outcome과 stored outcome→subscriber delivery를 각각 field 단위로 새로
투영하므로 item array·item·page의 nested mutation이 raw/stored/첫째·둘째·셋째 delivery 사이에 전파되지
않는다. state에는 `noteId`, `authorType`, `authorRef`, `content`, `createdAt`과 page metadata만 남고 response
`caseId`, envelope·Response·Error·credential·traceId는 남지 않는다.

pagination은 section local state다. page 0, size 20으로 시작하고 size는 20·50·100이며 size 변경은 page
0으로 돌아간다. Previous·Next는 URL/history를 바꾸지 않고 페이지 변경 즉시 이전 content를 제거한다.
전체 0건과 totalElements가 존재하는 out-of-range empty page는 서로 다른 문구로 표시하며 자동 보정하지
않는다.

content는 `dangerouslySetInnerHTML` 없이 React text node로 전부 표시한다. CSS는 `white-space: pre-wrap`,
`overflow-wrap: anywhere`를 사용해 CR/LF와 연속 공백을 보존하고 4,000 code point 및 긴 unbroken text를
viewport 안에서 줄바꿈한다. HTML·Markdown 해석, URL autolink, 식별자 강조·추출, truncation은 없다.
noteId는 link가 아닌 text metadata이고 authorType·authorRef는 raw opaque value로만 표시해 SYSTEM/USER를
사람·실명·이메일·역할로 추정하지 않는다.

E2E relay read allowlist에는 exact
`GET /api/v1/cases/{canonical-lowercase-uuid-v4}/notes`를 추가했다. notes query는 `page` canonical int32
0 이상, `size` 1..100, `sort=createdAt,asc|desc`만 허용한다. bare `?`, duplicate·empty·unknown,
non-canonical number/encoding, audit sort와 다른 endpoint query, UUID·suffix·separator 변형 및 notes의
POST·PATCH·PUT·DELETE는 relay process spawn과 Backend observation 전에 고정 비반사 문구로 거부한다.
전체 query/method 반례 64건과 method+URL이 모두 고유한 endpoint/path 반례 70건을 두 negative test가 실행하고, positive test는
read 12종과 write probe 1종(총 13종)을 허용한다. notes의 bare와 실제 initial target도 byte-exact로
보존한다.

실제 Keycloak·Backend case 404 시나리오는 relay가 detail·notes·audit initial target 세 개를 모두
관찰할 때까지 그 세 요청만 forwarding하지 않는 parallel-start barrier를 통과한 뒤 각각 정확히 1회 실제
Spring Boot 경계를 통과해 모두 실제 404를 받는지, detail 확정 후 notes·audit UI가 모두 제거되는지,
세 Backend body의 `code`·`message`·`traceId`가 노출되지 않는지, session 유지와 자동 retry·polling·
mutation 0회를 검증한다. barrier는 pending·released·failed·disposed 상태를 구분하고 15초 상한 안에
세 exact target이 모이지 않으면 원문을 반사하지 않는 고정 오류로 completion과 도착한 handler를 모두
종료한다. 성공·timeout·test 예외·page 종료 모두 timer·waiter·page listener·exact route를 정리하며,
spec-local controllable scheduler 반례가 target 1·2·3개 누락, 중복·unexpected target과 release·timeout·
dispose 경쟁에서 이중 settle 및 잔존 callback 0을 고정한다. populated Backend seed는 만들지 않는다. populated 의미 검증은 Hook/component
unit test와 test-only `case-investigation-notes-geometry.html/.tsx`가 맡는다. fixture는 production
`CaseInvestigationNotesPanel`과 `app.css`를 직접 사용하되 API mock·route interception·auth bypass가 없고
production router/build entry에서 참조하지 않는다. SYSTEM·USER, Unicode, CR/LF·연속 공백,
HTML/URL-like text, 정확히 4,000 code point, 긴 unbroken content·noteId·authorRef, 여러 item과 pager를
1440×900·1280×800·1024×768에서 렌더해 document horizontal overflow 0을 측정한다.

최종 browser 분해는 실제 Keycloak·Backend 통합 16개 + relay contract 3개 + geometry 3개 = 22개다.
runner는 worker 1, retries 0, strict TLS이고 Run 경로는 Prepare·pull·build·package download를 수행하지
않는다. Backend·DB·공식 API·dependency·auth·production router·E2E runner는 변경하지 않았다.
