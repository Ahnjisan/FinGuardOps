# FinGuardOps Frontend

React·TypeScript·Vite 기반의 FinGuardOps 프론트엔드다. 표준 OIDC Authorization Code + PKCE
인증 경계와, 승인된 Backend 업무 endpoint에만 credential을 전달하는 인증 API transport가
구현되어 있다. 업무 화면과 role·authority 권한 UI는 아직 구현되지 않았다.

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
| `src/api` | Backend HTTP client, endpoint allowlist, 인증 transport, 오류 분류, API 타입, 데이터 조회 hook |
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
- `healthApi.ts`는 endpoint registry와 `AuthClient` 어느 쪽에도 의존하지 않는다.

## 인증 Backend API 경계

`src/api/authorizedClient.ts`는 로그인한 USER를 대신해 승인된 Backend 업무 endpoint를
호출하는 **transport**다. 이번 범위는 transport뿐이며 화면·hook·Context·route는 포함하지
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

query parameter는 이번 범위에서 지원하지 않는다. 완성 URL 입력 API도 `URLSearchParams`
입력 API도 없으며 `?`와 `#`가 포함된 요청을 만들지 않는다. 목록 API는 Backend 기본
pagination 동작까지만 사용할 수 있고, `page`·`size`·`sort`는 실제 업무 목록 화면의 typed
API module과 함께 후속 Issue에서 구현한다.

### Token 경계와 port 분리

`AuthClient` port에는 token accessor가 없다. port는 두 개로 나뉜다.

| Port | 표면 | 전달 대상 |
| --- | --- | --- |
| `AuthClient` | `initialize`, `signIn`, `completeSignIn`, `signOut`, `onSessionInvalidated` | React tree |
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

## 미구현 범위

- 거래·사건·조사·판정 등 업무 화면과 업무 DTO·typed API module
- 역할·권한 기반 navigation·button·route guard UI
- `page`·`size`·`sort` query pagination
- 실제 Authorization Server 제품 선정·배포
- remote end-session(RP-initiated logout), silent renew, refresh token
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

lifecycle은 인증 준비 pending, fetch pending, body·JSON pending timeout이 각각 전체 5초로
합산되는 것, 외부 abort와 이미 aborted signal, timeout 시 `AbortController` 호출,
timer·listener 정리, late resolve·reject의 unhandled rejection 0을 검증한다. 401은 오류
타입, 안전 traceId 유지와 unsafe traceId 폐기, body 원문 비노출, 즉시 무효화, 동시 401의
단일 teardown, GET·POST·PATCH replay 0과 redirect 0을, 403은 오류 타입, session 유지,
teardown 0, retry·replay·redirect 0과 role·claim·body·token 비노출을 확인한다.

Web Storage에 token을 저장하는 코드는 없다. sessionStorage에는 transaction record만
존재하며 JWT 형태 값이 남지 않는다. IndexedDB는 사용하지 않고, Backend `GET /api/health`
요청에는 계속 `Authorization` header를 붙이지 않는다. Issue #225의 Local JWT fixture는
사용하지도 수정하지도 않는다.

설계 근거는
[`ADR-009`](../docs/07-decisions/ADR-009-frontend-oidc-pkce-memory-token-boundary.md)와
[`ADR-010`](../docs/07-decisions/ADR-010-frontend-authenticated-backend-api-boundary.md)을
따른다.
