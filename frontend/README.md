# FinGuardOps Frontend

React·TypeScript·Vite 기반의 FinGuardOps 프론트엔드 초기 기반이다. 업무 화면과 인증은 아직 구현되지
않았다.

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

`VITE_API_BASE_URL`은 `src/main.tsx`의 `bootstrap()`이 React root를 생성·render하기 전에 앱
시작 시 정확히 한 번 검증한다(fail-fast). 값이 없거나 형식이 유효하지 않으면 `createRoot`나
render에 도달하지 않고, 애플리케이션은 원문 값을 화면이나 콘솔에 출력하지 않고 고정된 오류로
실패한다. `HealthPage`를 포함한 나머지 코드는 같은 memoized 설정(`getEnv()`)을 재사용하며 검증을
반복하지 않는다.

## 디렉터리 책임

| 경로 | 책임 |
| --- | --- |
| `src/app` | Router 구성과 최상위 App Shell (navigation, `Outlet`) |
| `src/pages` | 화면 단위 컴포넌트 |
| `src/api` | Backend HTTP client, 오류 분류, API 타입, 데이터 조회 hook |
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
| `/` | `HomePage` | 진입 화면 |
| `/health` | `HealthPage` | Backend `/api/health` 상태 조회 |
| 그 외 모든 경로 | `NotFoundPage` | 404 |

업무 화면(거래, 사건, 조사, 판정, 운영 대시보드)과 OIDC·로그인·권한 route는 이번 범위에
포함되지 않으며 존재하지 않는다.

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

- OIDC·Authorization Code + PKCE, access token 저장, 로그인·로그아웃
- 역할·권한 기반 UI
- 거래·사건·조사·판정 등 업무 화면
- Local JWT fixture(Issue #225의 `infra/compose.local-jwt-e2e.yml`)는 로컬/수동 인증 E2E
  검증용 컴포넌트이며, 브라우저에서 사용하는 OIDC Provider가 아니다. 프론트엔드는 아직 이
  fixture나 다른 어떤 Authorization Server와도 연동하지 않는다.

## 테스트

`npm run test`는 Vitest와 jsdom, Testing Library로 환경변수 검증, application entry의 fail-fast
부트스트랩, HTTP client의 요청 생애주기 전체 timeout과 오류 분류, Health API 계약과 trace id
정규식 경계, React StrictMode 아래에서의 최초 fetch 단일 실행·unmount 이후 state 미갱신·genuine
remount의 신규 fetch·loading 중 중복 retry 방지, Router와 화면 상태(loading·success·error,
명시적 재시도, 접근성 있는 role/name)를 검증한다. Web Storage(localStorage/sessionStorage/
IndexedDB)에 token이나 credential을 저장하는 코드는 없으며, OIDC·Bearer·Authorization 구현도
없다.
