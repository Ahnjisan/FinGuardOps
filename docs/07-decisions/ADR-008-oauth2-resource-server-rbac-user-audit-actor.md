# ADR-008: OAuth2 Resource Server·RBAC·USER Audit actor 계약

- 상태: Accepted
- 결정일: 2026-09-02
- 결정자: Architecture Owner
- 관련 Issue: `#217 [Architecture] 인증·인가·RBAC와 USER 감사 주체 계약 확정`
- 관련 문서:
  - [`security-architecture.md`](../02-architecture/security-architecture.md)
  - [`system-architecture.md`](../02-architecture/system-architecture.md)
  - [`api-conventions.md`](../03-api/api-conventions.md)
  - [`case-audit-api.md`](../03-api/case-audit-api.md)
  - [`management-endpoints.md`](../03-api/management-endpoints.md)

## 1. 배경

FinGuardOps Spring Boot Backend에는 거래·행동 이벤트 접수, 거래·사건 조회,
사건 workflow·종결·조사 메모와 사건 감사 이력 조회가 구현되어 있다. 그러나 이
결정 시점에는 Spring Security와 OAuth2 Resource Server dependency,
`SecurityFilterChain`, JWT 검증, endpoint RBAC가 없다. 따라서 모든 Spring 업무
API는 security layer 관점에서 사실상 무인증이다.

일반 `audit_log`의 Java·DB 계약은 `SYSTEM`과 canonical lowercase UUID v4의
`USER` actor를 모두 허용하지만, 사건 상태·담당자·종결·조사 메모 writer는 현재
`SYSTEM/finguardops-backend`를 기록한다. Issue #215의 사건 감사 조회 응답도
`actorType`만 공개하고 `actorId`는 공개하지 않는다.

인증 구현보다 먼저 token 신뢰 경계, USER·SERVICE principal, role·authority,
endpoint 권한, 401·403·trace, Audit USER actor와 local/test/management 경계를 하나의
제품 중립 계약으로 확정해야 한다.

## 2. 결정

### 2.1 인증 책임

- Backend는 OAuth2 Resource Server이며 JWT를 발급하지 않는다.
- 사용자 credential, 로그인, password, access·refresh token 발급과 logout은 별도
  Authorization Server가 소유한다.
- Keycloak, Cognito 같은 구체적인 Authorization Server 제품은 이번 결정에서
  선택하지 않는다.
- Backend 자체 로그인·credential DB·JWT 발급과 trusted HTTP header·고정 production
  token 방식을 도입하지 않는다.

### 2.2 JWT 신뢰 계약

- token은 정확히 하나의 `Authorization: Bearer <JWT>` header로만 받는다.
- RS256만 허용하고 `kid`를 필수로 검증한다.
- production `issuer`와 JWK URI는 HTTPS이며 환경별 설정값과 exact match한다.
- audience는 JSON array `["finguardops-backend-api"]`와 정확히 일치한다.
- USER·SERVICE 모두 canonical lowercase UUID v4 `sub`를 사용한다.
- `principal_type`은 정확히 `USER` 또는 `SERVICE`다.
- `roles`는 중복 없는 JSON string array이며 unknown·duplicate·wrong type과
  USER·SERVICE 분류에 맞지 않는 role은 인증 실패다.
- `exp`, `iat`는 필수이고 `exp - iat`는 최대 15분이며 clock skew는 60초다.
- `nbf`는 선택이지만 존재하면 검증한다.
- 외부 `authorities`, token의 `jku`·`x5u`는 신뢰하지 않는다.
- issuer와 JWK URI를 함께 설정해 startup discovery 의존을 제거하고 초기에는
  in-memory JWK cache만 사용한다.
- old/new public key는 최소 30분 함께 게시하고 `kid`를 재사용하지 않는다.
- unsigned JWT, `alg=none`, algorithm confusion, query·body·cookie token과 token·claim
  원문 로깅을 금지한다.

### 2.3 권한 모델

- JWT role은 Backend에서 `ROLE_` prefix role과 세부 authority로 변환한다.
- 실제 endpoint와 method enforcement는 authority를 기준으로 한다.
- request-level 정책은 deny-by-default다.
- USER role과 SERVICE ingestion role을 한 principal에 혼합하지 않는다.
- 사건 write·resolution 같은 고위험 Service는 request-level 검사와 method security를
  함께 적용한다.
- `PLATFORM_ADMIN`은 모든 금융 업무 write를 자동 상속하지 않는다.

정확한 role mapping과 endpoint matrix는
[`security-architecture.md`](../02-architecture/security-architecture.md)를 따른다.

### 2.4 Audit actor

- 인증 adapter가 검증된 claim으로 immutable authenticated principal과 actor를 만든다.
- USER principal의 `sub`만 `AuditActorType.USER`의 `actorId`로 사용한다.
- SERVICE `sub`, email, display name, 내부 DB PK와 요청 body·query·임의 header 값은
  USER actorId로 사용하지 않는다.
- 사건 상태·담당자·종결·조사 메모 성공 write는 목표 상태에서 USER actor를 기록한다.
- 거래 처리, 자동 위험 대응, 사건 자동 생성·거래 연결과 RuleVersion·복구 one-shot은
  SYSTEM을 유지한다.
- read-only, 401, 403, validation, stale, 업무 거부, DB 오류, optimistic conflict와
  rollback loser는 업무 AuditLog를 만들지 않는다.
- 성공 사건 변경과 USER AuditLog는 같은 transaction·flush·rollback 경계를 사용한다.

현재 `investigation_note`와 `CASE_NOTE_CREATED` DB CHECK는 SYSTEM 전용이므로 조사 메모
USER 전환 후속 Issue에는 migration이 필요하다. 이 ADR은 migration이나 구현을 포함하지
않는다.

### 2.5 Public·service·management 경계

- `GET /api/health`만 credential 없이 허용하며 `status`, `service` 이외 상세 정보를
  추가하지 않는다. 잘못된 Bearer가 명시되면 401이다.
- 거래와 행동 이벤트 접수는 각각 분리된 SERVICE ingestion authority를 요구한다.
- 거래·사건·메모·감사 업무 조회와 사건 write는 USER authority를 요구한다.
- management `8081`에는 업무 사용자 JWT를 요구하지 않는다. local에서는 internal
  observability network와 host 미publish를 유지하고 production에서는 private network,
  방화벽/security group과 필요 시 mTLS 또는 authentication proxy로 보호한다.
- network 격리는 인증이나 TLS 구현과 동일하지 않다.
- RuleVersion 발행과 idempotency 복구는 현재 non-web one-shot이며 JWT endpoint가 아니다.

### 2.6 Session·CSRF·CORS와 SPA

- Backend는 stateless이고 HTTP session에 SecurityContext를 저장하지 않는다.
- 인증 cookie가 없는 Bearer-only 경계에서만 CSRF를 비활성화한다.
- Backend가 환경별 exact origin allowlist를 최종 강제하고
  `allowCredentials=false`를 사용한다.
- 승인된 preflight만 무인증으로 허용한다.
- SPA access token은 memory 보관을 우선하며 localStorage·sessionStorage 장기 저장을
  금지한다.
- 향후 SPA 로그인은 Authorization Code + PKCE를 사용한다. refresh token과 cookie·session
  도입은 이 결정 범위가 아니며 도입 전에 CSRF를 다시 결정한다.

## 3. 오류와 요청 흐름

목표 요청 흐름은 다음과 같다.

```text
TraceIdFilter
→ Spring Security authentication
→ request/method authorization
→ DispatcherServlet
→ Controller
→ Service
```

401·403은 filter 단계 전용 `AuthenticationEntryPoint`와 `AccessDeniedHandler`가 기존
공통 오류 body를 작성한다. `GlobalExceptionHandler`에 의존하지 않으며
`X-Trace-Id`와 body `traceId`는 같은 기존 request 값을 사용한다.

- 401: `UNAUTHORIZED`, `인증이 필요하거나 인증 정보가 유효하지 않습니다.`
- 403: `ACCESS_DENIED`, `요청한 작업을 수행할 권한이 없습니다.`
- 두 응답 모두 `fieldErrors: []`이며 상세 token·claim·Provider 오류를 노출하지 않는다.

JWK upstream 가용성 실패의 안전한 503 매핑은 실제 Spring Security 6.5 계열 예외 분류를
검증한 Issue #219에서 확정했다. cached known key는 upstream 장애 중에도 검증하고,
reachable JWKS의 unknown `kid`와 malformed·서명·claim 오류는 401이다. 원격 JWK
cause chain이 timeout allowlist와 일치하면 `DEPENDENCY_TIMEOUT`, 연결·DNS·TLS·5xx
allowlist와 일치하면 `DEPENDENCY_UNAVAILABLE`로 503을 반환한다. 예상 밖 decoder 오류는
안전한 `INTERNAL_ERROR` 500이며 503·500에는 `WWW-Authenticate`를 추가하지 않는다.

## 4. 검토한 대안

### 4.1 Backend 자체 로그인과 JWT 발급

선택하지 않았다. credential DB, password hashing, refresh rotation, revoke, logout,
account lifecycle과 key 운영 책임이 금융 업무 Backend에 결합되고 현재 Issue와 제품 범위를
크게 확장한다.

### 4.2 신뢰 HTTP header 또는 고정 token

선택하지 않았다. gateway 우회와 header 위조를 막기 위한 직접 접근 차단·mTLS 경계가
필수이며 표준 JWT claim·rotation·issuer 검증을 대체하지 못한다. local 편의를 production
보안 계약으로 승격하지 않는다.

### 4.3 특정 Authorization Server 제품 즉시 선택

유보했다. issuer, audience, JWK, claim, RBAC와 오류 계약은 제품 중립적으로 먼저 확정할 수
있다. 실제 제품과 URI는 Frontend·local·배포 구현 전에 별도 Issue로 결정한다.

## 5. 결과

### 긍정적 결과

- token 발급과 금융 업무 정합성 책임이 분리된다.
- USER와 SERVICE principal을 같은 JWT 검증 기반에서 구분할 수 있다.
- 사건 write의 실제 USER actor와 최소 권한을 일관된 계약으로 구현할 수 있다.
- local/test 편의가 production 인증 비활성으로 전파되는 것을 막는다.
- management scrape가 업무 사용자 token lifecycle에 결합되지 않는다.

### 비용과 제약

- Authorization Server와 JWK 운영 경계가 추가된다.
- 기존 Controller·MockMvc·Compose traffic generator는 인증 fixture가 필요하다.
- 조사 메모 USER 전환에는 DB migration이 필요하다.
- JWK cache refresh와 key rotation 장애를 운영·테스트해야 한다.
- SPA token 획득과 권한 UI는 별도 구현이 필요하다.

## 6. 현재 구현과 목표의 구분

Issue #219에서 다음 기반은 구현되었다.

- Spring Boot 관리 버전의 OAuth2 Resource Server와 application listener
  `SecurityFilterChain`
- RS256·`kid`·issuer·strict audience·subject·principal type·role·time validator,
  USER·SERVICE principal과 role-derived authority
- Spring Security/Nimbus 기본 in-memory JWK cache, rotation과 안전한 장애 분류
- stateless·CSRF·exact-origin CORS와 401·403·503·500 전용 응답 경계
- 기본/prometheus application·management listener 분리와 Actuator 404·health 상세 비노출

다음은 아직 구현되지 않았다.

- endpoint별 세부 authority enforcement와 `@PreAuthorize`
- 사건 write USER actor와 actorId 응답
- local issuer/JWK fixture와 SERVICE token traffic generator
- Frontend OIDC 로그인·token·권한 UI
- production Authorization Server·mTLS·authentication proxy

## 7. 후속 작업

OAuth2 Resource Server 기반과 401·403·trace 경계는 Issue #219에서 구현되었다.
남은 다음 네 Issue는 토큰 절약이 아니라 기술 책임·migration·검증 경계를 분리하기 위해
순서대로 수행한다.

1. `[Backend/Security] Endpoint RBAC와 USER·SERVICE authority matrix 적용`
2. `[Backend/Audit] 사건 write USER actor와 InvestigationNote author 연결`
3. `[Infra/Docs] Local Compose·runbook JWT fixture와 인증 E2E 적용`
4. `[Frontend] OIDC 로그인·token·권한 UI 구현`

Spring Security 개념과 JWT 검증 동작은 공식 문서를 참고하되 실제 dependency와 제품은 각
구현 Issue에서 검증한다.

- [Spring Security OAuth2 Resource Server JWT](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html)
- [Spring Security Session Management](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html)
- [Spring Security CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)
