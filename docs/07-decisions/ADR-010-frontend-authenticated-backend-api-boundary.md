# ADR-010: Frontend 인증 Backend API transport와 401·403 경계

- 상태: Accepted
- 결정일: 2026-09-04
- 결정자: Architecture Owner
- 관련 Issue: `#231 [Frontend/Security] 인증 Backend API client와 401·403 경계 구현`
- 관련 문서:
  - [`ADR-008`](ADR-008-oauth2-resource-server-rbac-user-audit-actor.md)
  - [`ADR-009`](ADR-009-frontend-oidc-pkce-memory-token-boundary.md)
  - [`security-architecture.md`](../02-architecture/security-architecture.md)
  - [`api-conventions.md`](../03-api/api-conventions.md)
  - [`frontend/README.md`](../../frontend/README.md)

## 1. 배경

[`ADR-009`](ADR-009-frontend-oidc-pkce-memory-token-boundary.md)에서 Frontend는 OIDC
Authorization Code + PKCE 로그인, `/auth/callback` 처리, local logout과 memory-only token
경계를 확정했다. 그 시점의 `AuthClient` port는 token을 밖으로 내보내는 수단을 전혀 갖지
않았고, Backend 보호 API 호출은 후속 작업으로 남겼다.

Backend에는 Issue #219·#221·#223에서 OAuth2 Resource Server, endpoint RBAC, USER·SERVICE
principal 분리와 고정된 401·403 오류 계약이 구현되어 있다. 따라서 이번 결정은 새 인증
방식을 만드는 것이 아니라, 이미 확정된 두 경계를 잇는 transport의 규칙을 확정하는 것이다.

이 결정 시점에도 실제 Authorization Server 제품은 선정되지 않았고, 거래·사건·메모·감사
업무 화면과 role·authority UI도 구현되지 않았다.

## 2. 결정

### 2.1 raw token accessor 미채택

`AuthClient`에 `getAccessToken(): Promise<string | null>`과 같은 raw token accessor를
추가하지 않는다.

accessor를 두면 token 문자열이 API 계층의 지역 변수가 되고, 그 뒤로는 어떤 값이 어디에
복사·로그·직렬화되는지가 규칙이 아니라 관행의 문제가 된다. ADR-009가 세운 "token은 port
밖으로 나가지 않는다"는 불변식이 검증 가능한 성질에서 지켜야 할 관례로 약해진다.

### 2.2 Request authorization 경계

대신 port에 다음을 둔다.

```ts
authorizeRequest(request: Request): Promise<AuthorizedRequest | null>;

interface AuthorizedRequest {
  readonly request: Request;
  readonly invalidateIfCurrent: () => void;
}
```

- `request`는 Authorization이 적용된 **새로운** `Request`이다.
- 호출자의 원본 `Request`에는 Authorization header를 설정하지 않는다.
- 호출자가 이미 넣어 둔 Authorization header는 병합하지 않고 제거한 뒤 다시 설정하므로,
  결과 요청의 credential은 항상 정확히 하나이고 그것을 설정한 주체는 port이다.
- token은 반환되지 않는다. 호출자는 "이 요청을 인증할 수 없다"(`null`)까지만 알 수 있다.
- `null`은 오류가 아니라 정상적인 답이며, 호출자는 이를 local 실패로 바꾸고 아무것도
  전송하지 않는다.
- `invalidateIfCurrent`는 이 요청을 승인한 session에만 적용되는 조건부 invalidation이다
  (2.9절).

`null`을 반환하는 조건은 다음과 같다.

- destination이 승인된 Backend USER endpoint가 아니다 (2.3절).
- 게시된 authenticated session이 없다.
- 인증 runtime을 획득할 수 없다.
- memory user store가 비어 있거나 읽기가 실패했다.
- access token이 없거나, 원문이 RFC 6750 `b64token` 문법에 맞지 않는다 (2.14절).
- token의 `expires_at`이 이미 지났다.
- memory user의 `sub`가 게시된 session의 subject와 다르다.
- store 읽기를 기다리는 동안 session이 교체되거나 종료되었다.

token은 기존 oidc-client-ts `InMemoryWebStorage`에서만 조회하며, cache·복제·직접 JWT
decode를 하지 않는다. localStorage·sessionStorage·IndexedDB에 token을 저장하지 않는다는
ADR-009의 결정은 그대로 유지된다.

### 2.3 credential capability의 독립적인 destination 검증

`authorizeRequest`는 token을 조회하기 **전에** 대상 `Request`가 승인된 Backend USER
endpoint인지 스스로 검증한다. 호출자가 먼저 검사했을 것이라고 가정하지 않는다.

credential을 실제로 보유한 주체가 destination을 거부하지 못하면, 이 capability를 직접
얻은 코드는 임의의 `Request`에 token을 붙일 수 있다. transport의 선행 allowlist는 정상
호출 경로만 보호할 뿐 독립적인 방어가 아니다.

검증 대상은 다음과 같다: 검증된 `VITE_API_BASE_URL`, exact protocol, exact origin, exact
base pathname, exact endpoint pathname, exact HTTP method, search 없음, hash 없음,
username·password 없음, canonical lowercase UUID v4 parameter, trailing slash 없음,
encoded-path 우회 없음.

검증 실패 시 oidc-client-ts runtime과 user store를 조회하지 않고, token 조회 0회,
Authorization 생성 0회, 반환 Request 없음, fetch 0회로 끝난다. 원본 `Request`에는 아무것도
설정하지 않으며 URL·token·내부 오류 원문을 노출하지 않는다.

`Request` 생성자 자체가 userinfo를 포함한 URL을 거부하므로 그런 URL은 이 capability에
도달하지 못한다.

### 2.4 public AuthContext에 credential capability 비노출

`AuthClient` port를 두 개로 나눈다.

- `AuthClient` — public 인증 표면. `initialize`, `signIn`, `completeSignIn`, `signOut`,
  `onSessionInvalidated`. credential을 얻을 수 없다.
- `CredentialAuthClient extends AuthClient` — `authorizeRequest`를 추가한 internal 표면.
  authenticated Backend transport에만 전달한다.

`AuthProvider`가 React tree에 게시하는 값은 adapter가 아니라 **명시적으로 구성한 public
facade** object literal이다. TypeScript 타입만 좁히는 방식은 채택하지 않는다. React tree에
전달된 값은 tree 안의 모든 코드가 property로 읽을 수 있으므로, runtime object 자체에
`authorizeRequest`가 없어야 한다.

runtime 보장:

- Context value와 `context.client` 모두 `authorizeRequest` property 없음
- raw token accessor 없음
- object spread로 full adapter를 복사하지 않음
- facade의 prototype은 `Object.prototype`이므로 prototype chain으로 internal method에
  도달할 수 없음
- facade는 adapter에 memoize되어 render마다 재생성되지 않음

### 2.5 USER browser client의 exact endpoint allowlist

호출자는 URL·method·query·header를 전달하지 않는다. endpoint key가 method와 path를 함께
결정하며, 등록되지 않은 key는 network 호출 이전에 거부한다.

| Endpoint key | Method | Path | 필수 authority |
| --- | --- | --- | --- |
| `transaction-list` | GET | `/api/v1/transactions` | `transaction:read` |
| `transaction-detail` | GET | `/api/v1/transactions/{transactionId}` | `transaction:read` |
| `case-list` | GET | `/api/v1/cases` | `case:read` |
| `case-detail` | GET | `/api/v1/cases/{caseId}` | `case:read` |
| `case-note-list` | GET | `/api/v1/cases/{caseId}/notes` | `case-note:read` |
| `case-audit-list` | GET | `/api/v1/cases/{caseId}/audit-logs` | `case-audit:read` |
| `case-status-change` | PATCH | `/api/v1/cases/{caseId}/status` | `case:workflow:write` |
| `case-assignee-change` | PATCH | `/api/v1/cases/{caseId}/assignee` | `case:workflow:write` |
| `case-resolution-create` | POST | `/api/v1/cases/{caseId}/resolution` | `case:resolution:write` |
| `case-note-create` | POST | `/api/v1/cases/{caseId}/notes` | `case-note:write` |

이 10개는 [`security-architecture.md`](../02-architecture/security-architecture.md) 5장의
production endpoint matrix에서 USER principal 행과 정확히 일치한다. authority는 Backend가
강제하며 Frontend는 이를 판단하지 않는다.

`caseId`와 `transactionId`는 canonical lowercase UUID v4/RFC variant만 허용한다.

```text
^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$
```

URL은 검증된 `VITE_API_BASE_URL`과 endpoint descriptor로만 조립하고, 조립 결과를 다시
파싱해 origin·userinfo·pathname·search·hash를 **exact** 비교한다. `startsWith()`나 substring
판정은 사용하지 않는다. `VITE_API_BASE_URL`이 path prefix를 가질 수 있으므로 base pathname과
endpoint pathname의 결합 결과 전체를 비교 대상으로 삼는다.

### 2.6 SERVICE endpoint와 credential-free 경계 분리

다음에는 browser USER token을 전달하지 않으며, 애초에 endpoint key가 존재하지 않는다.

- `GET /api/health` — public Health client가 credential 없이 호출한다.
- `POST /api/v1/transactions` — SERVICE `transaction:intake`
- `POST /api/v1/behavior-events` — SERVICE `behavior-event:intake`
- `/actuator/**`와 management listener `8081`
- FastAPI AI Service, External Risk Provider, Prometheus, Grafana, Alertmanager
- 그 밖의 모든 외부 origin과 문서에만 존재하는 후보 endpoint

public Health client는 endpoint registry와 `AuthClient`에 의존하지 않는다. Health 화면이
인증 상태나 Authorization Server 장애에 영향을 받지 않는다는 ADR-009의 성질이 유지된다.

### 2.7 query parameter 미지원

이번 결정에서 인증 transport는 query string을 만들지 않는다. 완성 URL 입력 API도,
`URLSearchParams` 입력 API도 두지 않으며, `?`와 `#`가 포함된 요청을 생성하지 않는다.

목록 API는 Backend 기본 pagination 동작까지만 사용할 수 있다. `page`·`size`·`sort`는
실제 업무 목록 화면의 typed API module과 함께 후속 Issue에서 결정한다. 소비자가 없는
상태에서 query 조립 경로를 먼저 열면, 검증되지 않은 입력 표면만 남기게 된다.

### 2.8 401 local invalidation

HTTP 401에서 다음을 수행한다.

- 안전한 `X-Trace-Id`만 추출한다.
- **그 요청과 함께 발급된** `invalidateIfCurrent()`를 호출한다.
- 해당 session이 아직 현재 session이면 memory session을 즉시 unauthenticated로 전환한다.
- 실패를 그대로 호출자에게 반환한다.

전역 unconditional invalidation은 사용하지 않는다. 401은 **그 요청에 실린 credential을
발급한 session**에 대한 정보이지 지금 로그인되어 있는 사람에 대한 정보가 아니다. 전역
invalidation은 다음을 구분하지 못한다.

1. session A로 요청 시작
2. fetch pending
3. session B 게시
4. A 요청의 401 도착
5. session B가 무효화됨 — 잘못된 결과

session ownership으로 이를 막는다.

- authenticated session이 게시될 때마다 새로운 opaque object identity를 만든다.
- 숫자 generation, subject, access token을 ownership 값으로 사용하지 않는다.
- ownership object는 token을 담지 않고 React state·Context·DOM·로그에 노출되지 않는다.
- authorization 시작 시 현재 ownership을 캡처하고, memory user 조회 후와 credential 반환
  직전에 다시 확인한다.
- `invalidateIfCurrent()`는 캡처한 ownership이 여전히 현재 ownership일 때만 invalidation
  하고, 그렇지 않으면 완전한 no-op이다.
- 여러 번 호출해도 안전하다.

같은 session의 동시 401 단일화는 새로운 mutex가 아니라 ADR-009가 이미 세운 idempotent
invalidation 경계를 재사용해 얻는다. 첫 호출만 유효 전이를 만들고, subscriber 통보·
`removeUser()`·transaction cleanup은 기존 in-flight teardown 공유 경계에서 각각 한 번만
실행된다. 동시 401이 세 건이어도 결과는 teardown 1회, 통보 1회다.

logout·expiry로 이미 종료된 session의 늦은 401, 그리고 교체된 session의 늦은 401은 모두
no-op이다. 새 sign-in이 이전 teardown의 settlement를 기다리는 ADR-009의 sequencing은 그대로
유지되므로, 이전 session의 정리가 새 session의 transaction record나 memory user를 지우지
않는다.

자동 redirect, 자동 재로그인, 실패 요청 replay는 하지 않는다.

### 2.9 403 session 유지

HTTP 403에서는 로그인 상태와 memory token을 그대로 유지한다. `invalidateSession()`을
호출하지 않고, teardown·redirect·retry·replay를 수행하지 않는다. 403은 "이 요청을 할 권한이
없다"이지 "당신은 로그인되어 있지 않다"가 아니다.

오류는 고정된 접근 거부 메시지와 안전한 traceId만 갖는다. response body, role, claim,
token, `WWW-Authenticate` 원문, 내부 예외는 노출하지 않는다.

### 2.10 자동 retry와 write replay 금지

요청당 `fetch`는 정확히 1회이고 자동 retry는 0회다. `POST`와 `PATCH`는 어떤 실패에서도
자동 재실행하지 않는다. 사건 상태·담당자·종결·조사 메모는 Backend가 소유하는 업무 상태이며,
자동 replay는 중복 판정이나 중복 메모를 만든다.

client는 실패한 요청 객체나 직렬화된 body를 보관하지 않으므로, replay할 대상 자체가 남지
않는다.

### 2.11 단일 deadline

인증 준비부터 response validator까지 하나의 5초 deadline을 적용한다. 포함 범위는 memory
user 조회, request authorization, fetch, response header, status 처리, body read, JSON
parse, response validator다. 단계별 timeout을 합산하는 구조를 사용하지 않는다.

deadline은 monotonic clock(`performance.now()`) 위의 **하나의 절대 시각**으로 한 번만
계산하고, 두 메커니즘이 이를 공유한다. 작업이 실제로 pending일 때는 timer가 대기를 끝내고,
단계 사이의 명시적 경과시간 검사가 timer로는 불가능한 경우를 담당한다.

JavaScript는 single-thread이므로 **동기 작업은 timer callback으로 중단할 수 없다.** 오래
도는 동기 validator는 timer callback이 실행되는 것 자체를 막는다. 따라서 이 구현은 동기
작업을 강제로 중단한다고 주장하지 않는다. 대신 **deadline을 넘겨 반환된 결과를 성공으로
채택하지 않는다.** 명시적 검사 지점은 operation 시작 직후, authorization 준비 전, 준비 완료
후, fetch 완료 후, body·JSON 완료 후, 동기 validator 완료 후, 최종 success 반환 직전이다.

경계는 다음과 같다. 4,999ms는 다른 계약이 정상이면 성공할 수 있고, 정확히 5,000ms와
5,001ms 이상은 `TimeoutError`다. `Date.now()`는 시스템 시계 조정으로 앞뒤로 뛸 수 있으므로
사용하지 않는다.

외부 `AbortSignal`은 같은 lifecycle에 결합한다. 자체 deadline은 `TimeoutError`, 외부 abort는
`NetworkError`로 분류해 서로 오분류하지 않는다. 모든 종료 경로에서 timer와 event listener를
제거하며, 늦게 도착하는 resolve·reject는 unhandled rejection을 만들지 않는다.

이미 취소된 요청은 credential을 요청하지도, 전송하지도 않는다.

### 2.12 오류 모델

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

`X-Trace-Id`는 공용 모듈에서 한 번만 정의한 정규식에 **전체 일치**할 때만 보관한다.

```text
^[A-Za-z0-9][A-Za-z0-9._:-]{7,63}$
```

trim·normalize·부분 일치는 하지 않는다. Backend `TraceIdFilter`가 401·403을 포함한 모든
응답에 이 header를 실어 보내고 CORS가 이를 노출하므로, 오류 body를 읽지 않고도 지원 참조
값을 얻을 수 있다.

### 2.13 전송 옵션

- `credentials: "omit"` — Backend CORS는 `allowCredentials=false`이고 인증은 Bearer header만
  사용한다.
- `redirect: "error"` — 승인된 endpoint 중 redirect하는 것은 없다. 예상치 못한 3xx를 따라가
  인증된 요청이 승인되지 않은 곳으로 이동하는 대신 실패로 처리한다.

### 2.14 Bearer credential grammar

같은 문법을 두 지점에서 적용한다. adapter는 **Authorization header를 만들기 전에 raw
access token 원문**을 검증하고, transport는 **auth port가 돌려준 요청의 Authorization
header 전체**를 다시 검증한다. 둘 중 어느 쪽도 다른 쪽으로 대체하지 않는다.

```text
b64token = 1*( ALPHA / DIGIT / "-" / "." / "_" / "~" / "+" / "/" ) *"="
```

본문은 1자 이상이어야 하고 `=`는 뒤쪽 padding으로만 허용한다. 따라서 `abc=`와 `abc==`는
허용하고 빈 문자열, `=`, `==`, `abc=def`, `=abc`, 공백·제어문자·허용 외
punctuation·비ASCII는 거부한다. JWT 3구간 형태로 좁히지 않으므로 opaque access token도
유효하다.

**transport 재검증만으로는 원문을 검증할 수 없다.** 플랫폼 `Headers.set()`은 값의 앞뒤
whitespace를 스스로 제거한다. 그래서 raw token이 `"opaque.token "`이면 header에 들어가는
순간 `Bearer opaque.token`으로 바뀌고, header만 보는 검사는 이를 정상 credential로
승인한다. header 값은 이미 platform이 정리한 값이므로 원문과 같지 않다.

**adapter 선검증.** `oidcAuthClient`의 `authorizeRequest()`는 memory user store에서 읽은
`access_token`을 module-private 정규식 `/^[A-Za-z0-9\-._~+/]+=*$/`로 있는 그대로
검사한다. trim·replace·normalize·재작성은 하지 않는다. 앞뒤 또는 중간의 whitespace, tab,
CR, LF, padding 위치를 벗어난 `=`, 빈 문자열은 모두 원문 그대로 거부한다. 이 검사를
통과한 경우에만 `Bearer ${accessToken}`을 만들고 `Headers.set()`을 호출한다.

실행 순서는 다음과 같다.

1. destination 검증
2. session ownership 캡처·검증
3. OIDC user와 raw `access_token` 조회
4. session ownership 재검증
5. raw `access_token` 문법 검증
6. session ownership 최종 재검증
7. Authorization header 구성과 Request 반환

최종 ownership 재검증은 header를 만들기 **전에** 한다. 6단계 이후에는 `await`도 Promise
resolution도 callback도 없어 header 구성과 Request 반환이 하나의 동기 구간이므로, 검사와
credential 부착 사이에 session이 교체될 여지가 없다.

**거부의 의미.** malformed raw token은 2.2절의 다른 `null` 사유와 완전히 같게 다룬다. 새
오류 class나 새 공개 error type을 만들지 않는다. HTTP 요청을 보내지 않고, 401이 발생한
것이 아니므로 session invalidation·subscriber 통보·`removeUser`·teardown·retry도 발생하지
않는다. 호출자는 `AuthenticationRequiredError`만 관측하며, token 값은 오류 메시지·로그·
DOM·React state 어디에도 남지 않는다.

**transport 재검증.** transport는 port를 "credential을 붙인다"까지만 신뢰하고 나머지는
신뢰하지 않으므로, 반환된 요청의 URL·method와 함께 `Bearer` + `b64token` 형태를 다시
확인한다. `Bearer`, `Bearer `, `Bearer =`, `Bearer abc=def`, `bearer abc`, `Bearer  abc`,
`Basic ...`는 모두 거부하고 fetch는 0회다. 병합된 multi-value header는 쉼표와 공백을
포함하므로 두 credential이 하나로 통과할 수 없다. scheme 대소문자나 여분 공백을 임의로
normalize해서 허용하지 않는다.

일부 값은 문법 검사 이전에 platform이 먼저 막는다. `Headers.set()`은 줄바꿈과 255를 넘는
code point를 거부한다. 이는 platform 보장으로 기록해 두는 것이지, 어느 계층의 검증도
대신하지 않는다.

### 2.15 production exact URL wiring

세 계층이 각각 독립적으로 검증된다.

1. **URL helper** — 조립 결과를 재파싱해 near-miss URL을 거부한다.
2. **transport wiring** — transport 자신이, 보유한 URL이 승인된 Backend USER 요청이며
   호출자가 요청한 바로 그 endpoint인지 다시 확인한다. URL이 어떻게 만들어졌는지에 의존하지
   않는다. 다른 승인 endpoint에 도달하는 것도 요청된 것이 아니므로 거부한다.
3. **credential capability** — 2.3절의 destination 검증.

한 계층의 결함을 다른 계층이 가리지 않도록 각각 별도로 검증한다.

## 3. 대안 비교

| 대안 | 장점 | 단점 | 결론 |
| --- | --- | --- | --- |
| raw token accessor (`getAccessToken`) | 구현·테스트가 단순하고 어떤 HTTP client와도 결합 가능 | token 문자열이 API·React 계층의 값이 되어 ADR-009의 불변식이 관례로 약해짐 | 미채택 |
| `authorizeRequest(Request) → AuthorizedRequest` | token이 port를 넘지 않음, credential이 정확히 하나임을 검증 가능, destination과 session ownership을 credential 보유자가 직접 강제 | API client가 auth port에 결합되고 `Request` 기반으로 고정됨 | 채택 |
| Context 타입만 좁히고 adapter를 그대로 게시 | 변경량이 적음 | runtime object에 credential capability가 남아 tree 안의 모든 코드가 property로 읽을 수 있음 | 미채택 |
| 전역 unconditional `invalidateSession()` | 단순함 | 교체된 session의 늦은 401이 현재 session을 로그아웃시킴 | 미채택 |
| fetch wrapper를 auth 모듈이 직접 제공 | 호출자가 인증을 우회할 여지가 가장 적음 | auth 모듈이 endpoint allowlist·오류 모델·timeout까지 소유하게 되어 책임이 뒤섞임 | 미채택 |
| 호출자가 URL을 넘기는 범용 client | 유연함 | allowlist가 문서상의 약속이 되고 우회 경로가 상시 존재 | 미채택 |

## 4. 결과

- 승인된 10개 method·path 조합 외에는 Authorization을 전달할 코드 경로가 존재하지 않으며,
  credential capability에 임의의 `Request`를 직접 건네도 credential이 붙지 않는다.
- React tree에 게시되는 값에는 credential capability가 없다.
- public Health와 Backend 외 서비스는 credential-free 경계를 유지한다.
- 같은 session의 동시 401은 한 번의 teardown과 한 번의 통보로 수렴하고, 교체·종료된
  session의 늦은 401은 현재 session에 아무 영향도 주지 않는다. 403은 session을 보존한다.
- 자동 retry와 write replay가 없으므로 Backend 업무 상태에 중복 write를 만들지 않는다.
- token은 Authorization header로만 전송되며 URL·body·query·오류·Web Storage에 나타나지 않는다.
- 신규 dependency는 추가하지 않았다. 표준 `fetch`·`Request`·`Headers`·`AbortController`와
  기존 `oidc-client-ts`만 사용한다.
- 이번 범위는 transport뿐이다. React hook, 신규 Context, 신규 Provider, route, page,
  navigation, role·authority UI, 거래·사건·메모·감사 DTO와 실제 업무 화면은 구현하지 않았다.

### 4.1 남은 위험

- 이 구현은 **동기 작업을 강제로 중단하지 못한다.** 오래 도는 동기 validator는 timer
  callback 실행 자체를 막으며, deadline은 그 작업이 반환된 뒤에야 관측된다. 보장하는 것은
  "deadline을 넘긴 결과를 성공으로 채택하지 않는다"이지 "5초 안에 반드시 반환한다"가 아니다.
- `redirect: "error"`는 실제 브라우저에서의 동작을 별도로 확인해야 한다. 단위 테스트는
  요청 객체의 `redirect` 값까지만 검증한다.
- session ownership은 하나의 document 안에서만 성립한다. 서로 다른 tab이나 document 사이의
  session 관계는 이 결정의 범위가 아니다.
- credential capability의 destination 검증은 `VITE_API_BASE_URL`이 실제 Backend origin이라는
  전제에 의존한다. 이 값의 신뢰는 배포 구성의 책임이다.

## 5. 후속 작업

- 실제 Authorization Server 제품 선정과 issuer·client 등록
- 거래·사건·메모·감사 업무 화면과 typed API module
- `page`·`size`·`sort` query pagination
- role·authority 기반 navigation·button·route guard UI
- remote end-session(RP-initiated logout)
- silent renew 또는 refresh token 도입 여부 재검토
- audience claim(`finguardops-backend-api`) 확보 방식 결정

이 결정은 위 후속 작업에서 필요하면 새 ADR로 갱신한다.
