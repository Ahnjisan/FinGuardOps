# FinGuardOps Frontend

React·TypeScript·Vite 기반의 FinGuardOps 프론트엔드다. 표준 OIDC Authorization Code + PKCE
인증 경계는 구현되어 있으나, 업무 화면과 권한 UI는 아직 구현되지 않았다.

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
| `src/app` | Router 구성과 최상위 App Shell (navigation, `Outlet`) |
| `src/pages` | 화면 단위 컴포넌트 |
| `src/api` | Backend HTTP client, 오류 분류, API 타입, 데이터 조회 hook |
| `src/auth` | 인증 상태 machine, `AuthClient` port, `oidc-client-ts` adapter, transaction storage, callback URL·복귀 경로 처리, React context와 hook |
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
| `/health` | `HealthPage` | Backend `/api/health` 상태 조회 (public) |
| `/auth/callback` | `AuthCallbackPage` | OIDC redirect callback 처리 |
| 그 외 모든 경로 | `NotFoundPage` | 404 |

`/`와 `/health`는 public이며 인증 초기화 실패나 Authorization Server 장애와 무관하게 계속
열려 있다. 보호 업무 route, 로그인 전용 route, silent renew callback, logout callback route는
존재하지 않는다. 업무 화면(거래, 사건, 조사, 판정, 운영 대시보드)과 권한 UI도 이번 범위에
포함되지 않는다.

## 인증 경계

로그인은 표준 OIDC **Authorization Code + PKCE** top-level redirect다. popup, BFF,
HttpOnly cookie session을 사용하지 않는다. PKCE·state·nonce 생성과 OIDC protocol 검증은
직접 dependency인 `oidc-client-ts@3.5.0`이 담당하며, 애플리케이션은 token을 직접 decode하지
않는다. public SPA client이므로 client secret이 없다.

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

- access token과 ID token은 in-memory user store에만 존재한다. refresh token은 발급을
  요청하지 않는다.
- localStorage, sessionStorage, IndexedDB에 token을 저장하지 않으며 reload 후 복원하지
  않는다. 새로고침하면 다시 로그인해야 한다.
- sessionStorage에는 Authorization Code + PKCE redirect 수행에 필요한 **transient protocol
  transaction record**만 저장한다. 이 레코드에는 state 식별자, nonce, PKCE verifier, 생성
  시각, authority, client ID, redirect URI, scope, request type 등 라이브러리가 요구하는
  비밀 token이 아닌 transaction 정보가 포함될 수 있다. 애플리케이션이 추가하는 데이터는
  `{ returnTo }` 하나뿐이며 `url_state`는 사용하지 않는다.
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

- silent renew, refresh token, `offline_access`, silent callback route가 없다.
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
- logout은 memory token과 transaction record를 제거하는 local logout이다. remote
  end-session redirect와 logout callback route는 실제 Authorization Server 선정 후 결정한다.

### 인증 상태

인증 상태는 boolean 조합이 아니라 discriminated union이다: `initializing`,
`unauthenticated`, `authenticating`, `authenticated`, `error`. 모든 전이는 출발 상태로
보호되므로 중복 로그인 시작, 중복 callback 처리, 늦게 도착한 결과가 상태를 되돌리지 못한다.
UI에는 `subject`, token, claim, Provider 원문을 렌더링하지 않으며 오류는
`configuration`·`sign-in`·`callback` 세 가지 고정 메시지로만 표시한다.

### Authorization Server 경계

애플리케이션 시작이나 public route 렌더만으로는 discovery·JWKS·authorize·userinfo 요청을
보내지 않는다. 실제 Authorization Server 통신은 사용자가 `Sign in`을 눌렀을 때와
`/auth/callback` 처리에서만 발생한다. 따라서 Authorization Server 장애는 로그인 실패로
한정되고 `/`·`/health`와 기존 Health fetch 계약에는 영향을 주지 않는다.

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

## 미구현 범위

- Backend 보호 API 호출과 `Authorization` header 전송, 401·403 UX
- 역할·권한 기반 UI
- 실제 Authorization Server 제품 선정·배포
- remote end-session(RP-initiated logout), silent renew, refresh token
- 거래·사건·조사·판정 등 업무 화면
- Local JWT fixture(Issue #225의 `infra/compose.local-jwt-e2e.yml`)는 로컬/수동 인증 E2E
  검증용 컴포넌트이며, 브라우저에서 사용하는 OIDC Provider가 아니다. 프론트엔드는 아직 이
  fixture나 다른 어떤 Authorization Server와도 연동하지 않는다. `VITE_OIDC_AUTHORITY`는
  검증된 설정값일 뿐 실제 연동을 의미하지 않는다.

## 테스트

`npm run test`는 Vitest와 jsdom, Testing Library로 환경변수 검증, application entry의 fail-fast
부트스트랩, HTTP client의 요청 생애주기 전체 timeout과 오류 분류, Health API 계약과 trace id
정규식 경계, React StrictMode 아래에서의 최초 fetch 단일 실행·unmount 이후 state 미갱신·genuine
remount의 신규 fetch·loading 중 중복 retry 방지, Router와 화면 상태(loading·success·error,
명시적 재시도, 접근성 있는 role/name)를 검증한다.

인증 경계는 실제 Authorization Server 없이 `AuthClient` port와 fake adapter로 검증한다.
OIDC 설정 exact 값, memory user store와 prefix가 붙은 session transaction store, prefix 밖
key 보존, transaction 정리 시점, callback URL 조기 정리와 fail-closed, exact key 기반
callback parameter 판정, 복귀 경로 allowlist, StrictMode 아래 initialize·callback 1회 실행과
listener 등록·해제 균형, unmount 이후 미갱신, fake clock 기반 15분 hard deadline(899,999ms
유지 / 900,000ms 무효화, 60분 token도 15분, 더 짧은 token은 그 시각), idempotent
invalidation, local logout, public route에서 Authorization Server 요청 0회를 확인한다.

Web Storage에 token을 저장하는 코드는 없다. sessionStorage에는 transaction record만
존재하며 JWT 형태 값이 남지 않는다. IndexedDB는 사용하지 않고, Backend `GET /api/health`
요청에는 계속 `Authorization` header를 붙이지 않는다. Issue #225의 Local JWT fixture는
사용하지도 수정하지도 않는다.

설계 근거는
[`ADR-009`](../docs/07-decisions/ADR-009-frontend-oidc-pkce-memory-token-boundary.md)를
따른다.
