# FinGuardOps 인증·인가·USER Audit actor 아키텍처

## 1. 목적과 구현 상태

이 문서는 FinGuardOps Spring Boot Backend의 제품 중립적인 OAuth2 Resource Server,
JWT, USER·SERVICE principal, role·authority, endpoint RBAC, 401·403·trace와 USER Audit
actor 목표 계약을 정의한다. Architecture Decision은
[`ADR-008`](../07-decisions/ADR-008-oauth2-resource-server-rbac-user-audit-actor.md)을
따른다. local/dev Authorization Server 제품과 claim 공급 계약은 후속 결정인
[`ADR-011`](../07-decisions/ADR-011-keycloak-authorization-server-and-claim-contract.md)을
따른다. singleton audience의 표준 raw 표현 계약은
[`ADR-012`](../07-decisions/ADR-012-jwt-singleton-audience-standard-representation.md)를 따른다.

### 1.1 현재 상태

- Spring Boot 관리 버전의 Spring Security·OAuth2 Resource Server dependency와 application
  listener용 `SecurityFilterChain`, JWT decoder·validator가 구현되었다.
- 정상 JWT를 immutable USER·SERVICE principal로 변환하고 JWT role의 `ROLE_` authority와
  세부 authority를 생성한다.
- 실제 12개 업무 method·path에는 endpoint별 세부 authority를 강제하고, 그 밖의 application
  요청은 deny-by-default로 거부한다.
- 사건 상태·담당자·종결과 조사 메모 생성 Service에는 동일 authority 상수 기반
  `@PreAuthorize`를 적용했다.
- login·signup·refresh·logout endpoint와 사용자·role·credential DB가 없다.
- local/dev Authorization Server로 Keycloak을 선정했지만 container·realm·client·mapper와
  Frontend·Backend 연동 runtime은 아직 구현되지 않았다.
- 사건 workflow·resolution·note writer는 provider가 검증한 USER UUID v4를 기록한다.
- Issue #215 사건 감사 조회 응답은 `actorType`만 공개하고 `actorId`는 비노출한다.

### 1.2 목표와 비범위

Issue #219는 Resource Server 기반과 JWT·principal·공통 오류·listener 경계를 구현했고,
Issue #221은 endpoint RBAC와 high-risk write method security를 구현했고 Issue #223은
USER actor와 조사 메모 USER author를 연결했다. Issue #229와 #231은 제품 중립 Frontend
로그인 경계와 인증 API transport를 구현했다. Issue #233은 local/dev Authorization Server로
Keycloak을 선정하고 claim 계약만 확정했다. Keycloak runtime과 실제 연동은 후속 범위이며
구현 완료로 표현하지 않는다.

## 2. 신뢰 경계

```text
Authorization Server
  └─ RS256 access token 발급·credential·login 책임
       ↓ Authorization: Bearer <JWT>
Spring Boot Resource Server
  ├─ signature·issuer·audience·time·claim 검증
  ├─ role → authority 변환과 인증 요구
  ├─ exact method·path authority + deny-by-default
  ├─ 금융 업무 정합성·transaction 소유
  └─ 성공 사건 write의 USER Audit actor 기록

Management 8081
  └─ 업무 JWT와 분리된 private network/scrape 경계
```

Backend는 access token을 발급·갱신·폐기하지 않는다. 위 경계와 3장의 claim 계약은
Authorization Server 제품과 무관한 목표 계약이다. local/dev 공급자는 Keycloak으로
선정했지만 실제 issuer·JWK URI, 검증된 Keycloak 26.x exact image tag·digest와 runtime은
후속 구현 Issue에서 고정한다. production AWS 제품과 배포 방식도 별도 결정이다. trusted
header와 고정 production token은 이 경계를 대체할 수 없다.

FastAPI의 `GET /api/health`, `POST /api/v1/rule-analysis`,
`POST /api/v2/rule-analysis`는 별도 컴포넌트 보안 경계이며 이 문서의 Spring Backend
RBAC 직접 대상이 아니다.

## 3. Bearer JWT exact 계약

### 3.1 전달과 서명

| 항목 | 계약 |
| --- | --- |
| 전달 위치 | 정확히 하나의 `Authorization: Bearer <JWT>` header |
| 금지 위치 | query parameter, request body, cookie |
| algorithm | `RS256`만 허용 |
| key 식별자 | JWT header의 `kid` 필수 |
| key source | Backend 환경에 설정된 JWK URI만 사용 |
| 금지 header | token의 `jku`, `x5u` |
| 금지 | unsigned JWT, `alg=none`, algorithm confusion, 임의 algorithm 확장 |

Bearer scheme이 없거나 중복 Authorization header, 다른 scheme, 빈 token과 malformed
token은 401이다. token 원문과 decoded claim 전체는 응답·일반 로그·metric에 기록하지
않는다.

### 3.2 claim 계약

| Claim | 타입·필수 | exact 계약 |
| --- | --- | --- |
| `iss` | JSON string, 필수 | 환경별 승인된 issuer와 exact match. production은 HTTPS |
| `aud` | JSON string 또는 string array, 필수 | exact string 또는 exact singleton array이며 논리적으로 `finguardops-backend-api` 하나 |
| `sub` | JSON string, 필수 | USER·SERVICE 모두 canonical lowercase UUID v4 |
| `principal_type` | JSON string, 필수 | 정확히 `USER` 또는 `SERVICE` |
| `roles` | JSON string array, 필수 | 중복 없는 알려진 role. string coercion·unknown·duplicate 금지 |
| `iat` | NumericDate, 필수 | 현재 시각보다 60초를 초과해 미래이면 거부 |
| `exp` | NumericDate, 필수 | 유효 기간 종료 검증, `exp - iat <= 15분` |
| `nbf` | NumericDate, 선택 | 존재하면 60초 clock skew로 검증 |

UUID는 trim, lowercase 변환, 재직렬화 같은 정규화를 하지 않고 입력 문자열 자체가
canonical lowercase UUID v4인지 검증한다. 외부 `authorities` claim은 존재해도 권한
결정에 사용하지 않는다.

`aud` raw JSON은 Nimbus/JWK 처리 전에 exact string 또는 exact singleton string array인지
검증한다. Nimbus 변환 뒤에는 전체 audience List가 승인 값 하나와 정확히 같은지 다시 검증해
additional·duplicate·malformed audience를 거부한다. trim, 대소문자 변환 또는 coercion은 하지
않으며 다른 JWT 보안 계약은 변하지 않는다.

### 3.3 USER·SERVICE role 조합

- `principal_type=USER`는 `FDS_VIEWER`, `FDS_ANALYST`, `FDS_APPROVER`,
  `RULE_OPERATOR`, `RECOVERY_OPERATOR`, `PLATFORM_ADMIN`만 가질 수 있다.
- `principal_type=SERVICE`는 `TRANSACTION_INGESTOR`, `BEHAVIOR_INGESTOR`만 가질 수 있다.
- USER role과 SERVICE ingestion role을 같은 token에 혼합하면 401이다.
- claim 검증을 통과한 principal이 endpoint authority를 갖지 못하면 403이다.

### 3.4 시간과 key rotation

- clock skew는 60초다.
- access token lifetime은 `iat`부터 최대 15분이다.
- issuer와 JWK URI를 함께 설정하여 startup discovery 의존을 제거한다.
- 초기에는 Spring Security/Nimbus의 in-memory JWK cache baseline만 사용하고 외부 cache
  dependency를 추가하지 않는다.
- old/new public key는 최소 30분 함께 게시한다.
- `kid`는 재사용하지 않는다.
- cache 안의 known key는 cache 유효 중 offline signature 검증에 사용할 수 있다.
- unknown key refresh가 실패하면 다른 key나 algorithm으로 우회하지 않고 fail-closed한다.
- 실제 upstream JWK cause chain이 timeout allowlist와 일치하면 503
  `DEPENDENCY_TIMEOUT`, 연결·DNS·TLS·5xx allowlist와 일치하면 503
  `DEPENDENCY_UNAVAILABLE`로 분류한다. 이외 예상 밖 decoder 오류는 안전한 500
  `INTERNAL_ERROR`다.
- malformed·서명 오류·만료·issuer·audience·time·claim 오류는 401이다.

### 3.5 Keycloak claim 공급과 token 용도

local/dev Keycloak은 전용 client scope와 명시적인 protocol mapper로 3.2절의 exact claim을
공급해야 한다. Backend validator를 Keycloak 기본 token 형태에 맞춰 완화하지 않는다.

- Backend는 USER·SERVICE access token만 API credential로 받으며 ID token을 받지 않는다.
- USER client와 분리된 두 SERVICE client의 Backend access token `aud`는 exact JSON string 또는
  exact singleton JSON string array이고 논리적인 recipient는 `finguardops-backend-api` 하나여야
  한다. 추가·duplicate audience는 하나도 허용하지 않는다. Audience mapper는 기존 값을 대체하지
  않고 추가할 수 있으므로,
  기본 `roles` client scope의 Audience Resolve mapper를 포함해 `account`와 기타 audience를
  추가할 수 있는 모든 source를 제거, 비활성화하거나 명시적으로 통제한다.
- USER access token에는 `principal_type=USER`와 허용된 USER role만 공급한다.
- 서로 분리된 두 SERVICE client의 access token에는 `principal_type=SERVICE`와 각각
  `TRANSACTION_INGESTOR` 또는 `BEHAVIOR_INGESTOR` 하나만 공급한다.
- USER ID token에는 동일한 인증 session의 USER access token과 중복 없는 동일한 FinGuardOps
  USER role 집합과 `principal_type=USER`를 UI 표시용으로 공급한다. 배열 원소 순서는 의미가
  없고 집합 동등성으로 비교하며, unknown·duplicate role과 USER·SERVICE role 혼합은 금지한다.
- 동일 USER session의 access token과 ID token `sub`는 원문 기준으로 완전히 동일하고 각각
  canonical lowercase UUID v4여야 한다. trim·lowercase 변환·normalization·재직렬화로 불일치를
  보정하지 않으며, provisioning 또는 E2E 실패로 처리한다. 이는 Frontend 표시 사용자와
  Backend authorization·Audit actor가 동일한 subject임을 보장한다.
- `offline_access`, `uma_authorization`, `default-roles-*`와 그 밖의 Keycloak 내부 role은
  access token과 ID token의 FinGuardOps `roles`에서 제외한다. realm/client role 전체를
  포괄적으로 복사하지 않고 FinGuardOps role allowlist만 투영한다.

Frontend는 access token을 직접 decode하지 않고 OIDC client가 검증해 게시한 ID token 기반
session profile의 `principal_type`과 `roles`만 표시·action 노출에 사용한다. 이 UI 판단은
Backend authorization을 대체하지 않으며 access token을 독립적으로 검증한 Backend의
401·403이 최종 결정이다. 상세 공급자 계약은
[`ADR-011`](../07-decisions/ADR-011-keycloak-authorization-server-and-claim-contract.md)을
따른다.

## 4. Role → authority 계약

JWT role 문자열에는 `ROLE_` prefix가 없다. Backend가 role용 GrantedAuthority에는
`ROLE_`를 붙이고 아래 세부 authority를 함께 생성한다. 실제 접근 제어는 세부 authority를
기준으로 한다.

| JWT role | Backend role | authority |
| --- | --- | --- |
| `FDS_VIEWER` | `ROLE_FDS_VIEWER` | `transaction:read`, `behavior-event:read`, `detection:read`, `case:read`, `case-note:read`, `case-audit:read`, `ai-report:read` |
| `FDS_ANALYST` | `ROLE_FDS_ANALYST` | `FDS_VIEWER` 전체 + `case:workflow:write`, `case-note:write`, `ai-report:create` |
| `FDS_APPROVER` | `ROLE_FDS_APPROVER` | `FDS_VIEWER` 전체 + `case:resolution:write` |
| `RULE_OPERATOR` | `ROLE_RULE_OPERATOR` | `rule-version:read`, `rule-version:publish` |
| `RECOVERY_OPERATOR` | `ROLE_RECOVERY_OPERATOR` | `recovery:inspect`, `recovery:execute` |
| `PLATFORM_ADMIN` | `ROLE_PLATFORM_ADMIN` | `platform:read`, `ai-operations:read`, `ai-usage:read` |
| `TRANSACTION_INGESTOR` | `ROLE_TRANSACTION_INGESTOR` | `transaction:intake` |
| `BEHAVIOR_INGESTOR` | `ROLE_BEHAVIOR_INGESTOR` | `behavior-event:intake` |

`PLATFORM_ADMIN`은 사건 변경·종결, 거래·행동 접수와 RuleVersion 발행을 자동 상속하지
않는다. 여러 USER role을 가진 경우에는 각 role의 authority 합집합을 사용하되 모든 role이
알려진 USER role이어야 한다.

## 5. 실제 Spring endpoint inventory와 구현 RBAC

아래 13개는 현재 production Controller에 실제 존재한다. public health를 제외한 12개 업무
method·path는 표의 authority를 강제한다. 문서 후보 endpoint에는 matcher를 추가하지 않는다.

| Method·path | Controller·기능 | R/W | principal·허용 role | 필수 authority | Public | 구현 인가 | 성공 업무 AuditLog |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `GET /api/health` | `HealthController`, process health | R | 없음 | 없음 | 예 | public | 없음 |
| `POST /api/v1/transactions` | `TransactionIntakeController`, 거래 접수 | W | SERVICE · `TRANSACTION_INGESTOR` | `transaction:intake` | 아니오 | authority 강제 | 조건부 SYSTEM 거래·사건 감사 |
| `GET /api/v1/transactions` | `TransactionQueryController`, 거래 목록 | R | USER · viewer authority 보유 role | `transaction:read` | 아니오 | authority 강제 | 없음 |
| `GET /api/v1/transactions/{transactionId}` | `TransactionQueryController`, 거래 상세 | R | USER · viewer authority 보유 role | `transaction:read` | 아니오 | authority 강제 | 없음 |
| `POST /api/v1/behavior-events` | `BehaviorEventIntakeController`, 행동 접수 | W | SERVICE · `BEHAVIOR_INGESTOR` | `behavior-event:intake` | 아니오 | authority 강제 | 없음 |
| `GET /api/v1/cases` | `FraudCaseQueryController`, 사건 목록 | R | USER · viewer authority 보유 role | `case:read` | 아니오 | authority 강제 | 없음 |
| `GET /api/v1/cases/{caseId}` | `FraudCaseQueryController`, 사건 상세 | R | USER · viewer authority 보유 role | `case:read` | 아니오 | authority 강제 | 없음 |
| `PATCH /api/v1/cases/{caseId}/status` | `FraudCaseWorkflowController`, 상태 변경 | W | USER · `FDS_ANALYST` | `case:workflow:write` | 아니오 | URL + method | USER |
| `PATCH /api/v1/cases/{caseId}/assignee` | `FraudCaseWorkflowController`, 담당자 변경 | W | USER · `FDS_ANALYST` | `case:workflow:write` | 아니오 | URL + method | USER |
| `POST /api/v1/cases/{caseId}/resolution` | `FraudCaseWorkflowController`, 사건 종결 | W | USER · `FDS_APPROVER` | `case:resolution:write` | 아니오 | URL + method | USER |
| `POST /api/v1/cases/{caseId}/notes` | `InvestigationNoteController`, 메모 생성 | W | USER · `FDS_ANALYST` | `case-note:write` | 아니오 | URL + method | USER |
| `GET /api/v1/cases/{caseId}/notes` | `InvestigationNoteController`, 메모 조회 | R | USER · viewer authority 보유 role | `case-note:read` | 아니오 | authority 강제 | 없음 |
| `GET /api/v1/cases/{caseId}/audit-logs` | `FraudCaseAuditLogController`, 감사 조회 | R | USER · viewer authority 보유 role | `case-audit:read` | 아니오 | authority 강제 | 없음 |

여기서 viewer authority 보유 role은 `FDS_VIEWER`, 그리고 그 전체 authority를 상속하는
`FDS_ANALYST`·`FDS_APPROVER`다. 인증이 필요한 모든 행은 credential·claim 실패 시 401,
valid principal의 authority 부족 시 403을 반환한다. SERVICE 전용 행에 정상 USER가,
USER 전용 행에 정상 SERVICE가 접근하면 403이다. 분류와 충돌하는 role이 token에 있으면
principal 자체가 유효하지 않으므로 401이다.

`GET /api/health`는 credential이 없으면 200이고 body는 `status`, `service`만 반환한다.
dependency·DB·build·환경 상세를 추가하지 않는다. 잘못된 Bearer가 명시적으로 전달되면
401이다. `public`은 인터넷 공개를 의미하지 않으며 network 정책으로 더 제한할 수 있다.

### 5.1 Profile·listener별 health·Actuator 목표 계약

Issue #219의 Security 구현은 credential이 없는 요청에 대해 아래 status matrix를 보존한다.

| Profile/listener | Endpoint | Credential 없음 |
| --- | --- | ---: |
| 기본 application | `/api/health` | 200 |
| 기본 application | `/actuator/health` | 200 |
| 기본 application | `/actuator/prometheus` | 404 |
| prometheus application 8080 | `/api/health` | 200 |
| prometheus application 8080 | `/actuator/health` | 404 |
| prometheus application 8080 | `/actuator/prometheus` | 404 |
| prometheus management 8081 | `/actuator/health` | 200 |
| prometheus management 8081 | `/actuator/prometheus` | 200 |

기본 profile application listener의 exact-path public 예외는 기존 probe·테스트 호환성을
위한 `GET /api/health`와 `GET /actuator/health`다. `/actuator/health`는 credential 없이
200이고 정상 JWT가 있어도 별도 role·authority를 요구하지 않으므로 권한 부족에 따른 403이
발생하지 않는다. aggregate `status`만 공개하고 component·DB·환경·build·dependency 상세는
노출하지 않는다. 두 exact public path 모두 명시된 Bearer가 invalid하면 authentication
실패 401이다.

기본 profile의 `/actuator/prometheus`는 노출되지 않으므로 credential이 없거나 정상 JWT가
있어도 404다. prometheus profile application `8080`은 `/api/health`만 credential 없이
200이며 `/actuator/health`와 `/actuator/prometheus`는 management endpoint가 mapping되지 않아
404다. Security 설정이 존재하지 않는 endpoint를 401·403으로 바꾸면 안 된다. 다만 invalid
Bearer가 명시된 요청은 endpoint exposure 판정보다 앞선 authentication 단계에서 401이 될 수
있으며, 이는 endpoint가 노출되었다는 뜻이 아니다. 다른 `/actuator/**`는 허용하지 않는다.

prometheus profile management `8081`의 `/actuator/health`와 `/actuator/prometheus`는 업무
JWT 없이 200이며 health·prometheus 외 management endpoint는 노출하지 않는다. 이 listener는
업무 OAuth2 Resource Server filter chain과 분리한다. local은 internal observability network와
host 미publish, production은 private network와 방화벽/security group으로 보호한다. mTLS 또는
authentication proxy는 필요 시 적용할 후속 운영 결정이며 management endpoint를 public으로
표현하거나 Prometheus에 업무 USER·SERVICE JWT를 요구하지 않는다. network 격리는
authentication·TLS와 동일하지 않다.

### 5.2 문서에만 있는 미구현 후보 endpoint

다음 endpoint는 공식 API 문서 후보이지만 현재 Spring Controller가 없다. 목표 matrix에
예약하되 구현됐다고 표현하지 않는다.

| Method·path | 목표 principal·role | 목표 authority | 현재 상태 |
| --- | --- | --- | --- |
| `GET /api/v1/behavior-events` | USER · viewer authority 보유 role | `behavior-event:read` | 문서 후보, 미구현 |
| `GET /api/v1/transactions/{transactionId}/detection-results` | USER · viewer authority 보유 role | `detection:read` | 문서 후보, 미구현 |
| `GET /api/v1/detection-results/{detectionResultId}` | USER · viewer authority 보유 role | `detection:read` | 문서 후보, 미구현 |
| `GET /api/v1/cases/{caseId}/transactions` | USER · viewer authority 보유 role | `case:read` | 문서 후보, 미구현 |
| `POST /api/v1/cases/{caseId}/ai-reports` | USER · `FDS_ANALYST` | `ai-report:create` | 문서 계약, 미구현 |
| `GET /api/v1/cases/{caseId}/ai-reports/current` | USER · viewer authority 보유 role | `ai-report:read` | 문서 계약, 미구현 |
| `GET /api/v1/ai-report-requests/{aiRequestId}` | USER · `PLATFORM_ADMIN` | `ai-operations:read` | 문서 계약, 미구현 |
| `GET /api/v1/ai-report-usage` | USER · `PLATFORM_ADMIN` | `ai-usage:read` | 문서 계약, 미구현 |
| `GET /api/v1/ai-report-usage/summary` | USER · `PLATFORM_ADMIN` | `ai-usage:read` | 문서 계약, 미구현 |

RuleVersion 조회·발행 HTTP endpoint는 현재 없다. Rule v1 기본 발행과 idempotency 복구는
profile·confirmation·non-web process 실행 경계이며 JWT endpoint로 표현하지 않는다.

## 6. Authorization 정책

- `TraceIdFilter`가 요청 traceId를 확정하고, 명시된 Bearer가 있으면 authentication을 먼저
  검증한다. application listener의 authorization matcher 순서는 다음과 같다.
  1. `CorsUtils.isPreFlightRequest`에 해당하는 실제 CORS preflight
  2. exact `GET /api/health`
  3. 기존 `/actuator/**` exposure와 미노출 404 경계
  4. 12개 protected method·path와 각 authority
  5. 그 밖의 요청 `denyAll`
- management `8081`은 위 업무 JWT chain과 별도 경계다.
- profile에 따라 mapping되지 않은 Actuator path는 Security가 먼저 401·403을 반환하지 않고
  기존 404를 유지하도록 matcher 순서와 management context를 검증한다. invalid
  Bearer의 authentication 401은 이 404 exposure 계약과 별도로 검증한다.
- matcher는 Spring Security 6.5 `PathPatternRequestMatcher`로 method와 path를 고정한다.
  trailing slash와 미승인 business path·method는 valid JWT에 403, credential 없음에 401이다.
- `FraudCaseWorkflowService.changeStatus`·`changeAssignee`·`resolve`와
  `InvestigationNoteService.create`는 URL matcher와 같은 authority로 method security를
  이중 적용하며 authorization 거부가 transaction advisor보다 먼저 실행된다.
- 인증 또는 claim 검증 실패는 401, valid principal의 authority 부족은 403이다.
- 직접 Service 호출이 필요한 SYSTEM 자동 처리는 사용자용 protected facade와 책임을
  혼합하지 않는다.

## 7. Filter·trace·오류 계약

### 7.1 요청 흐름

```text
TraceIdFilter
→ Spring Security authentication
→ request/method authorization
→ DispatcherServlet
→ Controller
→ Service
```

`TraceIdFilter`는 `Ordered.HIGHEST_PRECEDENCE`로 Security chain보다 먼저 trace를
확정한다. 이 순서는 통합 테스트로 고정한다. 401·403은
DispatcherServlet 전에 발생하므로 `GlobalExceptionHandler`가 아니라 전용
`AuthenticationEntryPoint`와 `AccessDeniedHandler`가 처리한다.

ERROR·FORWARD dispatch에서 새 traceId를 만들거나 재인증하지 않는다. ASYNC endpoint는
현재 없으며 향후 도입 시 SecurityContext와 MDC 전파를 별도 검증한다.

### 7.2 401

```json
{
  "code": "UNAUTHORIZED",
  "message": "인증이 필요하거나 인증 정보가 유효하지 않습니다.",
  "traceId": "현재 요청의 traceId",
  "fieldErrors": []
}
```

- credential 없음: `WWW-Authenticate: Bearer realm="finguardops-backend"`
- invalid token·claim:
  `WWW-Authenticate: Bearer realm="finguardops-backend", error="invalid_token"`
- 대상: credential 없음, malformed Authorization header/token, 서명 오류, 만료,
  `nbf`·`iat`, issuer·audience, `sub`·`principal_type`·`roles`, unknown·duplicate·분류 충돌
  role 오류

### 7.3 403

```json
{
  "code": "ACCESS_DENIED",
  "message": "요청한 작업을 수행할 권한이 없습니다.",
  "traceId": "현재 요청의 traceId",
  "fieldErrors": []
}
```

- `WWW-Authenticate: Bearer realm="finguardops-backend", error="insufficient_scope"`
- 대상: 인증·claim 검증은 성공했지만 endpoint authority가 부족한 정상 USER·SERVICE
  principal

두 오류의 `X-Trace-Id`와 body `traceId`는 기존 request attribute의 같은 값이다. token,
claim, Provider 오류, 내부 security class와 stack trace는 응답이나 일반 로그에 포함하지
않는다. 401·403은 업무 AuditLog를 만들지 않는다.

## 8. USER Audit actor 계약

### 8.1 구현된 Java·DB 계약

- `AuditActorType`은 `SYSTEM`, `USER`를 허용한다.
- 일반 `audit_log`에서 SYSTEM actorId는 `finguardops-backend`, USER actorId는 canonical
  lowercase UUID v4다.
- 사건 workflow·resolution·note writer는 USER를 사용한다.
- `investigation_note.author_type/author_ref`와 `CASE_NOTE_CREATED` DB CHECK는 기존
  SYSTEM 조합과 신규 USER UUID v4 조합을 허용한다.
- 사건 감사 조회 응답은 `actorType`만 공개하고 `actorId`는 비노출한다.

### 8.2 전달 경계

- 인증 adapter가 검증된 JWT로 immutable principal/actor를 만든다.
- USER의 `sub`만 `AuditActorType.USER`의 `actorId`로 사용한다.
- SERVICE의 `sub`는 USER actor로 저장하지 않는다.
- email, display name, 내부 DB PK와 body·query·임의 header의 actor 값은 사용하지 않는다.
- `CurrentAuditActorProvider` 하나만 SecurityContext를 읽고, Service는 명령 시작 시 USER
  subject를 한 번 캡처한다. Controller·Entity·Repository는 SecurityContext를 읽지 않는다.

### 8.3 USER·SYSTEM 적용

| 처리 | actor |
| --- | --- |
| 사건 상태 변경 | USER |
| 사건 담당자 변경 | USER |
| 사건 종결 | USER |
| 조사 메모 생성 | USER |
| 거래 처리·자동 위험 대응 | SYSTEM |
| 사건 자동 생성·거래 연결 | SYSTEM |
| RuleVersion·복구 one-shot과 기타 자동 처리 | SYSTEM |

read-only, 401, 403, validation, stale version, 업무 상태 거부, DB 오류, optimistic
conflict와 rollback loser는 업무 AuditLog를 만들지 않는다. 성공 사건 변경과 USER
AuditLog는 같은 transaction·flush·rollback 경계를 사용한다.

V14는 `investigation_note`와 `CASE_NOTE_CREATED`의 CHECK에 USER/canonical lowercase UUID
v4 조합을 추가하고 기존 SYSTEM 조합·행·append-only trigger·index를 유지한다.

### 8.4 actorId 응답

- Issue #215 응답은 USER 구현 후에도 `actorType`만 공개하고 `actorId`는 비노출한다.
- email과 display name은 공개하지 않는다.
- SYSTEM actorId 공개 여부는 후속 API 변경에서 별도로 결정한다.

USER actor UUID, token, claim과 principal 원문은 응답·로그·metadata에 공개하지 않는다.

## 9. Management 8081 경계

- management health·prometheus는 public endpoint가 아니다.
- 업무 사용자 JWT를 Prometheus scrape에 요구하지 않는다.
- local에서는 internal observability network와 Backend port host 미publish를 유지한다.
- production에서는 private network, 방화벽/security group을 기본 경계로 하고 필요 시
  mTLS 또는 authentication proxy를 적용한다.
- `prometheus` profile에서는 application listener `8080`에 Actuator 경로를 노출하지
  않고 management `8081`의 `health,prometheus`만 노출한다.
- 기본 profile은 기존 application listener의 `/actuator/health`를 유지하되
  `/actuator/prometheus`는 404다.
- network 격리와 loopback bind는 인증·TLS 구현을 대신하지 않는다.

## 10. Session·CSRF·CORS·Frontend

- `SessionCreationPolicy.STATELESS`를 사용한다.
- HTTP session에 SecurityContext를 저장하지 않고 매 요청 Bearer JWT를 검증한다.
- 인증 cookie를 사용하지 않는 동안 CSRF를 비활성화한다.
- cookie·refresh session을 도입하기 전에 CSRF를 다시 결정한다.
- Backend가 환경별 exact origin allowlist를 최종 강제한다.
- wildcard origin + credentials 조합을 금지하고 `allowCredentials=false`를 사용한다.
- 승인된 origin·method·header의 OPTIONS preflight만 credential 없이 허용한다.
- 허용 header는 실제 endpoint가 필요한 `Authorization`, `Content-Type`,
  `Idempotency-Key`, `X-Trace-Id` 등으로 제한한다.
- SPA access token은 memory에만 보관하고 localStorage·sessionStorage·IndexedDB 저장을
  금지한다.
- SPA 로그인은 Authorization Code + PKCE를 사용한다.
- refresh token은 별도 Frontend Issue다. role·authority 기반 권한 UI의 판정 계층과 route
  guard는 Issue #243에서 구현했고, Issue #249에서 첫 production 보호 route `/transactions`에,
  Issue #251에서 조회 전용 거래 상세 route `/transactions/{transactionId}`에, Issue #253에서
  조회 전용 사건 목록 route `/cases`에 적용했다.
- Frontend UI capability 이름(`transaction:view`, `case:view`)과 Backend authority 이름
  (`transaction:read`, `case:read`)은 서로 다른 계층의 이름이므로 혼용하지 않는다. 최종 판정은
  Backend authority에만 있다.
- USER public client는 Authorization Code Flow + PKCE `S256`만 허용한다. implicit flow,
  password grant, client secret과 wildcard redirect URI를 금지한다.

Issue #229에서 Frontend는 `oidc-client-ts` 기반 Authorization Code + PKCE redirect 로그인,
`/auth/callback` 처리와 local logout을 구현했다. access·ID token은 memory user store에만
있고 reload 후 복원되지 않는다. sessionStorage에는 `finguardops.oidc.transaction.` prefix의
transient protocol transaction record만 남으며, 로그인 시작 직전과 callback 성공·실패 후
정리된다. `/auth/callback` 외 경로의 초기화는 중단된 redirect가 남긴 record를 정리하고,
callback route에서는 검증 중인 transaction을 보존한다. silent renew, refresh token과
`offline_access`는 사용하지 않고 세션은 token expiry와 로그인 완료 후 15분 중 빠른 시점에
local invalidation된다. hard deadline·expiry·logout은 하나의 in-flight teardown을 공유하므로
동시에 발생해도 teardown과 통보가 각각 1회만 일어난다.

Issue #247에서 이 경계를 Keycloak RP-initiated logout으로 확장했다. realm, bootstrap,
verifier와 Backend는 변경하지 않았다. `Sign out`은 application session, session ownership과
15분 deadline timer를 remote 작업 이전에 동기적으로 제거하고 subscriber에게 정확히 1회
통보한다. OIDC user record는 library가 `id_token_hint`를 읽을 때까지 유지하므로, adapter는
`signoutRedirect()` 이전에 `removeUser()`나 transaction 정리를 실행하지 않는다. ID token은
로그인 callback에서 검증되어 memory user store에 있는 값을 library가 내부적으로 사용하며,
application은 이를 state·React·DOM에 복사하거나 logout 인자로 전달하지 않는다. adapter는 존재
여부와 compact JWT 형태만 fail-closed로 확인하고 별도 JWK 재검증은 하지 않는다(ADR-011 2.9).
ID token이 없거나 runtime 형태가 잘못되면 redirect하지 않고 local cleanup 후 고정
`AuthSignOutError`로 끝난다.

end-session 목적지는 discovery가 아니라 설정된 issuer에서 고정한다.
`metadataSeed.end_session_endpoint`가 issuer + exact `/protocol/openid-connect/logout`이고
oidc-client-ts는 discovery 문서 위에 seed를 덮어쓰므로, 변조된 discovery 응답이 목적지를 옮길
수 없다. `post_logout_redirect_uri`는 현재 origin + exact `/`뿐이며 query·fragment·userinfo·
경로가 섞인 origin은 URL 재파싱으로 거부한다. 동시 logout은 같은 session generation에서 pending인
동안만 하나의 flight를 공유하므로 그 attempt의 redirect, logical teardown, subscriber 통보가 각각
1회다. flight는 settlement 후 해제되며, 같은 page에서 재로그인한 새 session generation의 logout은
이전 flight를 재사용하지 않고 새 remote logout을 수행한다. redirect가 실패하거나 취소되어도
local logout은 복원하지 않고, 자동 retry와 자동 재로그인도 하지 않는다.

logout callback route는 exact `/`이며 로그인 callback `/auth/callback`과 분리되어 있다.
`AuthProvider`가 최초 초기화 이전에 주소창을 분류하고, library 호출 전에
`history.replaceState()`로 bare `/`로 정리한다. 분류는 exact origin, exact `/`, fragment·userinfo
부재, `state` 정확히 1개이며 nonblank, 허용 parameter 4종 외 unknown·중복 parameter 거부,
`error` 없는 `error_description` 거부로 fail-closed다. callback은 logout transaction만 정확히
1회 consume하고 `removeUser()`·session invalidation·subscriber 통보를 실행하지 않으므로, 새
session이 살아 있는 상태에서 이전 session의 stale callback이 도착해도 현재 session·memory
user·deadline timer가 유지된다. 성공은 unauthenticated, 실패는 credential 없는 고정 logout
오류이며 성공·실패 모두 최종 주소는 exact `/`다. sessionStorage transaction record는 `si:r`과
`so:r` 두 schema로 분리해 검증하며, logout record에 nonce나 PKCE verifier가 삽입되거나
popup·silent·unknown request type이 나타나면 set·get·remove 어디에서도 거부한다.

`window.sessionStorage` property 획득은 실제 인증 operation 안에서 `try`/`catch`로 수행하며,
getter가 `SecurityError`를 던져도 `/`와 `/health` public Outlet은 그대로 렌더되고 인증 영역만
고정 오류가 된다. `/auth/callback`에서는 storage 접근보다 먼저 URL의 `code`·`state`·fragment를
제거하며, `code`와 `error`가 동시에 있는 비정상 응답은 라이브러리에 넘기지 않고 안전하게
실패한다. redirect 취소나 BFCache 복귀 시에는 명시적 재로그인이 가능한 상태로 돌아온다.
자세한 근거는 [`ADR-009`](../07-decisions/ADR-009-frontend-oidc-pkce-memory-token-boundary.md)를
따른다.

Issue #231에서 Frontend는 인증 Backend API transport와 401·403 경계를 구현했다. 설계
근거는 [`ADR-010`](../07-decisions/ADR-010-frontend-authenticated-backend-api-boundary.md)을
따른다.

`Authorization: Bearer`는 5장 matrix의 USER 행과 정확히 일치하는 아래 10개 method·path
조합에만 전달한다. 호출자는 URL·method·query·header를 전달하지 않고 endpoint key와 path
parameter만 전달하며, 등록되지 않은 key는 network 호출 이전에 거부한다.

`GET /api/v1/transactions`, `GET /api/v1/transactions/{transactionId}`,
`GET /api/v1/cases`, `GET /api/v1/cases/{caseId}`, `GET /api/v1/cases/{caseId}/notes`,
`GET /api/v1/cases/{caseId}/audit-logs`, `PATCH /api/v1/cases/{caseId}/status`,
`PATCH /api/v1/cases/{caseId}/assignee`, `POST /api/v1/cases/{caseId}/resolution`,
`POST /api/v1/cases/{caseId}/notes`.

`GET /api/health`는 계속 credential 없이 호출하며 public Health client는 endpoint registry와
`AuthClient`에 의존하지 않는다. SERVICE 전용 `POST /api/v1/transactions`와
`POST /api/v1/behavior-events`, `/actuator/**`, management listener 8081, FastAPI AI Service,
External Risk Provider, Prometheus, Grafana, Alertmanager, 그 밖의 외부 origin과 문서 후보
endpoint에는 endpoint key 자체가 존재하지 않는다.

`caseId`와 `transactionId`는 canonical lowercase UUID v4/RFC variant만 허용하고, URL은 검증된
base URL과 endpoint descriptor로 조립한 뒤 다시 파싱해 origin·userinfo·pathname·search·hash를
exact 비교한다. `startsWith()`와 substring 판정은 사용하지 않으므로 trailing slash, encoded
path, dot traversal, protocol-relative URL, userinfo, query·fragment 우회는 fetch 이전에
거부된다. Issue #245에서 목록 endpoint의 query를 추가했으며, 호출자는 여전히 raw query
string이나 `URL`·`URLSearchParams`를 전달하지 않고 endpoint별 typed plain object만
전달한다. 값은 registry가 선언한 순서대로 `URLSearchParams.set()`으로만 조립하므로 한
이름에 값이 정확히 하나이고, opaque 참조값 안의 `&`·`=`·`#`·`%`는 percent-encoding되어
query 구조가 되지 못한다.

값 규칙의 소유자는 endpoint descriptor다. registry는 이름 목록이 아니라 이름과 값
validator의 쌍, 그리고 query 전체의 교차 의미 계약을 함께 선언하고, typed builder와
완성 URL 재검증이 같은 선언을 실행한다. 개별 값만으로는 판정할 수 없는 계약이 있기
때문이다. `occurredAtFrom`은 그 자체로는 정상 instant이고 `occurredAtTo`와 나란히 놓일
때만 422가 되므로, 범위는 개별 parameter가 아니라 endpoint에 속한다. 현재 선언된 범위는
`transaction-list`의 `occurredAtFrom <= occurredAtTo`, `case-list`의
`createdAtFrom <= createdAtTo`와 `lastChangedAtFrom <= lastChangedAtTo` 세 개이며, 한쪽
bound만 있으면 허용하고 비교는 나노초까지 정확하다.

완성된 URL은 endpoint registry, transport, credential capability 세 계층에서 각각 query를
재파싱하고, 각 값을 그 endpoint의 규칙에 통과시키고, endpoint의 범위 계약까지 확인한 뒤,
같은 canonical builder로 재조립해 byte-for-byte 일치할 때만 승인한다. 따라서 중복 이름,
unknown 이름, 값이 같아도 canonical form이 아닌 encoding, 그리고 typed 경로를 우회해 손으로
만든 `page=-1`·`size=101`·`sort=createdAt,asc`·비정규 UUID·offset 시각·소문자 enum과
역전된 시간 범위는 token 조회와 Authorization 부착과 fetch 이전에 모두 거부된다. 거부는
고정 오류만 반환하며 query 값이나 원문을 오류·로그에 반사하지 않는다. query를 선언하지
않은 detail·write endpoint는 query가 붙은 URL뿐 아니라 호출자가 query 인자를 전달했다는
사실 자체를 거부하며 빈 객체도 예외가 아니다.

reference filter는 Backend validator별로 분리한다. 거래의 `externalCustomerRef`·
`accountRef`는 `TransactionQueryValidator`와 동일하게 Java `String.isBlank()` 하나만
적용하므로 trim·정규화 없이 원문 그대로 검색하고 `" acct "` 같은 nonblank padded 값을
허용하며 길이 제한을 전혀 두지 않는다. 공통 structural validator에도 길이 상한이 없고,
길이는 rule별 계약(`page`·`size`의 자릿수, `instant`·`uuid`의 문법, `assigneeRef`의 128자)
으로만 제한한다. 사건의 `assigneeRef`는 `FraudCaseQueryValidator`의
계약대로 nonblank·128자 이하·Java `trim()` 동일을 유지한다. 두 규칙을 하나로 합치면
거래 쪽에 Backend에 없는 제약이 생기므로 분리를 회귀 테스트로 고정한다.

raw access token은 `AuthClient` port 밖으로 나가지 않는다. port는 public 표면(`AuthClient`)과
credential 표면(`CredentialAuthClient`)으로 나뉘며, 후자는 승인된 `Request`에 Authorization을
부착한 새 `Request`와 session-bound invalidation callback을 돌려주는 `authorizeRequest()`만
제공하고 token accessor를 제공하지 않는다. 이 capability는 token을 조회하기 전에 대상
`Request`가 승인된 Backend USER endpoint인지 **스스로** 검증하므로, transport를 우회해 임의의
`Request`를 직접 건네도 credential이 붙지 않는다. `AuthProvider`가 React tree에 게시하는 값은
adapter가 아니라 명시적으로 구성한 public facade이며, runtime object에 `authorizeRequest`
property가 존재하지 않는다. token은 memory user store에서만 조회하며 URL·body·query·오류·
Web Storage에 나타나지 않는다. 요청은 `credentials: "omit"`과 `redirect: "error"`로 전송하고,
credential은 RFC 6750 `b64token` 문법으로 재검증한다.

401은 안전한 `X-Trace-Id`만 보관한 뒤, 그 요청을 승인한 session에만 적용되는 조건부
invalidation을 호출한다. 같은 session의 동시 401은 기존 idempotent invalidation 경계로
수렴하므로 subscriber 통보, `removeUser()`와 transaction cleanup이 각각 1회만 실행되고,
교체·logout·expiry로 이미 끝난 session의 늦은 401은 현재 session에 아무 영향도 주지 않는다.
403은 로그인 상태와 memory token을 유지하고 teardown·redirect를 하지 않는다. 두 경우 모두 response body, role, claim, token과
`WWW-Authenticate` 원문을 노출하지 않으며 고정 메시지만 사용한다. 요청당 fetch는 정확히
1회이고 자동 retry는 0회이며, `POST`와 `PATCH`를 자동 재실행하지 않는다. 인증 준비부터
response validator까지 monotonic clock 위의 단일 5초 deadline을 적용한다. 동기 작업은 timer로
중단할 수 없으므로 강제 중단을 주장하지 않고, deadline을 넘겨 반환된 결과를 성공으로 채택하지
않는다.

Issue #233에서 local/dev Authorization Server로 Keycloak을 선정했다. USER ID token에는
`principal_type=USER`와 같은 session의 access token에 공급한 것과 중복 없는 동일한 FinGuardOps
USER role 집합을 제공하며 배열 순서는 의미가 없다. 같은 두 token의 `sub` 원문은 완전히
동일하고 각각 canonical lowercase UUID v4여야 한다. Frontend는 OIDC client가 검증한 session
profile의 표시용 값만 UI 표시와 action 노출에 사용할 수 있고 access token을 직접 decode하지
않는다. UI role은 보안 경계가 아니며 Backend access token 검증과 authority 기반 401·403을
대체하지 않는다.

`offline_access` 요청과 offline token 사용은 금지한다. 일반 온라인 refresh token은 offline
token과 별개이며 `offline_access` 없이도 반환될 수 있다고 가정한다. Issue #239의 runtime adapter는
provider 설정뿐 아니라 실제 token response를 검사한다. `refresh_token`이 반환되면 해당
session을 게시하지 않고 OIDC user state를 제거하며, callback 이후 memory·user store·Web
Storage에 원문이 남지 않게 fail-closed한다. `automaticSilentRenew=false`, refresh token
grant 0회와 silent renew 0회를 유지한다. 실제 Chromium E2E는 정상 token response의 refresh token
부재와 합성 `refresh_token` 거부, state·nonce·PKCE 변조 거부를 각각 확인한다. 거래·사건·메모·
감사 업무 화면은 아직 구현되지 않았다. remote logout은 Issue #247에서 구현했고 실제 Chromium
E2E가 exact end-session endpoint·parameter 집합, exact root callback, logout state 1회 consume,
local session·credential 0, 재로그인 시 로그인 화면 재노출, consumed callback 재사용 반례와
token·password·state 원문 비노출을 확인한다. 같은 Issue에서 browser 신뢰 경계를 host Windows
인증서 저장소에서 격리 Linux Chromium container의 실행별 NSS database로 옮겨, host trust store
접근 0회와 strict TLS를 동시에 만족시킨다.

Issue #243에서 Frontend는 검증된 session profile의 USER role로 UI capability를 결정하는 판정
계층과 route guard 컴포넌트를 구현했다. `principal_type`과 `roles`는 OIDC client가 검증한 ID
token claim이며 adapter의 session 생성 지점 한 곳에서만 읽는다. access token은 계속 decode하지
않는다. `principal_type`이 정확히 `USER`가 아니거나, `roles`가 배열이 아니거나, 빈 배열이거나,
unknown·duplicate·SERVICE role이나 대소문자·공백 변형·non-string 원소가 하나라도 있으면 부분
채택 없이 전체를 거부하고 session 자체를 게시하지 않는다. 이는 Backend가 같은 token을 401로
거부하는 것과 일치시키기 위한 것이다. 빈 `roles`도 예외가 아니다. 4장 authority는 모두 role
claim에서 도출되므로 role이 없는 USER token은 어떤 업무 endpoint에서도 401이고, 이런 session을
게시하면 로그인만 성공하고 첫 요청에서 실패하는 상태가 된다. 따라서 Frontend session에는
"로그인했으나 role이 없는 상태"가 존재하지 않으며, session의 role 배열은 required이면서 비어
있을 수 없다. 거부는 session 게시 0회, subscriber 통보 0회, OIDC user state·transaction record
제거, 고정 callback 오류로 끝난다.

UI capability는 4장 authority 전체를 복제하지 않고, Frontend가 실제로 호출할 수 있는 10개
USER endpoint에 대응하는 `transaction:view`, `case:view`, `case:workflow`, `case:note-write`,
`case:resolve` 5종만 정의한다. `FDS_VIEWER`는 앞의 두 개, `FDS_ANALYST`는 여기에
`case:workflow`·`case:note-write`, `FDS_APPROVER`는 `case:resolve`를 더한다. 다중 role은
합집합이며 배열 순서에 의존하지 않는다. `RULE_OPERATOR`·`RECOVERY_OPERATOR`·`PLATFORM_ADMIN`은
대응 endpoint가 없으므로 capability가 0개이고, `PLATFORM_ADMIN`이 사건·거래 권한을 상속하지
않는다는 4장 규칙이 UI에서도 유지된다.

guard는 결정 이전(`initializing`·`authenticating`)을 거부로 확정하지 않고, 미인증과 인증 오류를
로그인 안내로 수렴시키며, 권한 없는 action을 `disabled`가 아니라 DOM에서 제거한다. 거부·안내
화면은 role·authority·claim·subject를 노출하지 않는 고정 문구만 사용한다. guard는 요청을
가로채지 않으므로 401의 session-bound invalidation과 403의 session 유지 경계는 그대로다.
이 UI는 표시 경계이며 endpoint·method authority 검증과 401·403 결정을 대체하지 않는다.
capability로 보호되는 production route·navigation 항목·action은 아직 0개이며, guard의 직접 URL
접근 동작은 test 전용 MemoryRouter route로 검증한다.

Issue #245에서 Frontend는 위 10개 endpoint를 typed API module로 구현했다. 화면·route·
navigation·button·hook·상태관리는 포함하지 않는다. 거래·사건 filter와 거래·사건·메모·감사
pagination을 지원하며, `page`·`size`·`sort`와 각 filter는 Backend validator와 같은 범위로
제한한다. `page`는 0 이상 Java `int` 범위의 정수, `size`는 1~100, `sort`는 endpoint별 단일
필드의 `asc`·`desc`만 허용하고, 음수·소수·`Number.isSafeInteger` 초과·대소문자 변형·다중
정렬·unknown 이름은 요청 조립 이전에 거부한다. UTC ISO-8601 `Z` 이외의 시각 표기와
`from > to` 범위도 요청 이전에 거부하며, 범위 비교는 밀리초에서 잘리는
`Date.getTime()`이 아니라 `(epoch second, nanosecond)` 기준이다. 조사 메모 공백 판정은
Java `Character.isWhitespace || Character.isSpaceChar`와 동일한 명시적 predicate를 사용해
NBSP 계열은 공백으로 보고 U+FEFF는 Backend와 동일하게 공백으로 보지 않는다.

write 요청 body는 caller의 object를 그대로 보내지 않는다. 계약 field 집합을 runtime에
exact 검증한 뒤 새 plain object로 재구성하므로, unknown field, inherited field, symbol key와
`authorRef`·`actorType`·`actorId` 같은 서버 결정 값은 전송 경로가 없다. `expectedVersion`은
0 이상의 safe integer만 허용하고 누락 시 기본값을 만들지 않는다. 응답은 모든 중첩 object의
exact own-key와 타입을 검증하며, 배열 항목이 하나라도 계약과 다르면 부분 채택 없이 응답
전체를 거부한다. Java `long`은 `Number.isSafeInteger` 범위만 허용하고, 금액은 `number`로
변환하지 않고 계약상 10진 정수 문자열로 최대 15자리까지 유지하며, `currencyCode`는 `KRW`
하나만, UUID는 canonical lowercase UUID v4만 허용한다. page metadata는
`totalPages`·`first`·`last`와 항목 수가 `number`·`size`·`totalElements`와 모순되지
않는지까지 검증한다.

감사 항목은 `action`과 `reasonCode`의 조합을 discriminated union으로 검증하고, Backend
`AuditMetadataPolicy`를 그대로 옮겨 값 사이의 관계까지 확인한다. `CASE_CREATED`의 `OPEN`,
`CASE_TRANSACTION_LINKED`의 `linked=true`, 세 상태 전이 reason별 정확한 상태·담당자 변화,
`CASE_ASSIGNEE_RELEASED` 이후 `null` 담당자, `CASE_RESOLVED`의 `IN_REVIEW → CLOSED`와
담당자 유지가 그것이다. 넓은 공용 summary 형태를 여러 reason에 재사용해 승인하지 않으므로
action과 항목 수만 맞고 의미가 조작된 응답도 거부된다. 감사 `changedAt`에는 mapper와 같은
microsecond 정밀도 조건을 적용해 나노초 값을 거부하며(`...000001Z` 허용,
`...000000001Z` 거부), 형식만 정상이고 정밀도만 잘못된 항목이 하나 있어도 페이지 전체를
거부한다. 이 조건은 감사 `changedAt`에만 적용하고 다른 DTO 시각은 공통 UTC validator를
그대로 사용한다.

두 workflow write는 `reasonCode`를 discriminant로 하는 discriminated union이며 runtime
validator가 같은 표를 강제한다. `CASE_REVIEW_STARTED`는 `IN_REVIEW`와 담당자 UUID를
요구하고, `CASE_ADDITIONAL_INFORMATION_REQUESTED`와 `CASE_REVIEW_RESUMED`는 `assigneeRef`
key 자체를 금지하며, 담당자 변경은 `null`을 `CASE_ASSIGNEE_RELEASED`에만, UUID를
`CASE_ASSIGNEE_ASSIGNED`·`CASE_ASSIGNEE_CHANGED`에만 허용한다. 성공할 수 없는 조합은
credential 조회 이전에 거부하고, 누락과 명시적 null의 차이는 계속 보존한다.

성공 status는 endpoint별로 정확히 비교한다. 조사 메모 생성만 `201`이고 나머지 아홉 개는
`200`이며, 다른 2xx는 body를 읽지 않고 거부한다. 성공 응답의 `X-Trace-Id`는 부재와
malformed를 구분한다. 부재는 허용하고, 존재하지만 trace 계약을 만족하지 못하면
`InvalidResponseError`이며, 유효하면 body `traceId`와 정확히 일치해야 한다. non-2xx는
반대로 malformed header를 폐기해 오류가 오류로 남는다.
`400`·`401`·`403`·`404`·`409`·`422`·`500`·
`503` body는 계속 읽지 않으며 기존 status-only 경계를 유지한다. 단일 5초 deadline,
`credentials:"omit"`, `redirect:"error"`, 요청당 fetch 1회, 자동 retry·request replay
0회, access token decode 0회, Web Storage credential 저장 0회도 그대로다.

## 11. Local·test·Compose 경계

- production Security chain을 profile로 끄지 않는다.
- test는 ephemeral asymmetric key 또는 test-only decoder를 사용하되 production과 같은
  claim validator를 실행한다.
- MockMvc JWT fixture는 exact claim 타입, USER·SERVICE 구분과 role mapping을 재현한다.
- Issue #225의 선택형 Compose overlay에는 production Authorization Server가 아닌 local/manual
  fixture가 있다. 시작 시 ephemeral RSA key를 만들고 `127.0.0.1:8002`의 readiness·JWKS만
  제공하며 발급·rotation·fault는 tmpfs의 private Unix socket과 container CLI로 제한한다.
- production과 기본 runtime issuer/JWK는 HTTPS만 허용한다. test의 in-process JWK server는
  명시적인 loopback 전용 opt-in에서만 HTTP를 허용한다.
- production issuer/JWK는 HTTPS가 아니면 fail-closed한다.
- 실제 private key, JWT와 credential은 repository·문서·`.env.example`·로그에 저장하지
  않는다.
- `.env.example`에는 issuer·JWK URI·audience 같은 비밀이 아닌 placeholder만 허용한다.
- CI key는 secret 또는 ephemeral generation으로 공급한다.
- 설정 누락은 인증 비활성으로 fallback하지 않는다.
- Keycloak local E2E와 기존 Local JWT fixture E2E는 실행 profile 또는 Compose overlay와
  Backend issuer·JWK 설정을 분리한다. 하나의 Backend 실행에서 두 issuer를 동시에 신뢰하지
  않으며 같은 issuer 설정으로 두 공급자를 함께 사용하지 않는다.

현재 Compose의 Backend application `8080`과 management `8081`은 host에 publish되지
않는다. Prometheus는 internal observability network에서 management `8081`을 scrape한다.
Grafana·Alertmanager 계약은 이 문서 Issue에서 바꾸지 않는다. 기존 거래 traffic
generator의 기존 무인증 예시는 base Compose에서 401이다. Issue #225 verifier는 machine
mode에서 JWT를 캡처하고 stdin/memory로만 전달해 인증된 traffic을 별도 실행한다. management
scrape에는 업무 JWT를 추가하지 않는다.

## 12. 위협과 통제

| 위협 | 통제 경계 |
| --- | --- |
| forged trusted header | trusted header 방식 금지, JWT signature 검증 |
| unsigned JWT·algorithm confusion | RS256 단일 allowlist, `kid` 필수, `alg=none` 거부 |
| issuer·audience 생략·audience 표현 우회 | raw exact 표현 검증과 normalized exact singleton validator |
| expired·future token | `exp`·`iat`·선택 `nbf`, 60초 skew, 최대 15분 |
| role escalation·unknown role | exact array, server-side mapping, unknown·duplicate 401 |
| USER·SERVICE 혼용 | `principal_type`과 role category 검증 |
| UUID normalization 우회 | 원문 canonical lowercase UUID v4 exact 검증 |
| email 변경에 따른 actor 불안정 | USER `sub` UUID만 actorId로 사용 |
| token·claim 로그 노출 | 원문·전체 claim·Provider 예외 비로깅·비노출 |
| test profile production 전파 | Security chain 비활성 금지, test-only decoder 격리 |
| management 외부 노출 | separate listener, private/internal network, host 미publish |
| CORS wildcard + credential | exact allowlist, `allowCredentials=false` |
| cookie/Bearer 혼용과 CSRF | 현재 금지, cookie 도입 전 재결정 |
| 권한 없는 사건 종결 | `case:resolution:write` + method security |
| stale·rollback의 잘못된 USER audit | 같은 transaction·flush·rollback, 실패 무감사 |

## 13. 후속 구현 Issue

다음 분리는 토큰 절약이 아니라 dependency, 권한 matrix, DB migration, Docker E2E와
브라우저 검증의 기술 책임이 다르기 때문이다.

OAuth2 Resource Server 기반과 401·403·trace 경계는 Issue #219에서, endpoint RBAC와
method security는 Issue #221에서 구현되었다. 아래 표는 구현 상태와 남은 후속 작업을
표시한다.

| 순서·제목 | 목표 | 주요 변경 영역 | Migration | 테스트 경계 | 선행 Issue | 장시간 검증·현재 상태 |
| --- | --- | --- | --- | --- | --- | --- |
| 완료. `[Backend/Security] Endpoint RBAC와 USER·SERVICE authority matrix 적용` | deny-by-default와 endpoint 최소 권한 | request matcher, role converter, method security | 없음 | 13개 endpoint·401·403, role 혼용 | #219 | full-stack JWT·method security 검증; 구현 |
| 완료. `[Backend/Audit] 사건 write USER actor와 InvestigationNote author 연결` | 검증 principal을 성공 감사에 연결 | provider/service actor, note author, V14 | 적용 | 성공·stale·rollback·기존 SYSTEM 호환 | #221 | 동시성·migration 검증; 구현 |
| 완료. `[Infra/Docs] Local Compose·runbook JWT fixture와 인증 E2E 적용` | local issuer와 SERVICE traffic | 선택형 Compose overlay, fixture, verifier, runbook | 없음 | build·wait·traffic·scrape·alert·restart | #219·#221 | local/manual Docker E2E 경계 구현 |
| 부분 구현. `[Frontend/Security] OIDC PKCE와 memory-only 인증 기반 구현` | SPA 인증 경계 | PKCE redirect, memory token, transaction store, `/auth/callback`, local logout | 없음 | 설정·settings·storage·lifecycle·callback·deadline | #219·#221 | jsdom 단위·컴포넌트 검증; 구현 (#229) |
| 부분 구현. `[Frontend/Security] 인증 Backend API client와 401·403 경계 구현` | 승인 endpoint에만 credential 전달 | endpoint allowlist, `authorizeRequest()`, 401 invalidation, 403 유지, 단일 deadline | 없음 | allowlist·URL 우회·401·403·timeout·abort | #229 | jsdom 단위 검증; transport 구현 (#231) |
| 완료. `[Security/Architecture] Keycloak Authorization Server와 권한 Claim 계약 확정` | local/dev 제품과 USER·SERVICE·token claim 계약 | ADR-011·보안 아키텍처·README | 없음 | 문서 claim·role·신뢰 경계 정합성 | #225·#229·#231 | 문서 계약 확정 (#233), local runtime·USER E2E 연결 (#239) |
| 완료. `[Backend/Security] JWT singleton audience 표준 표현 호환` | RFC 7519 singleton 표현과 stock Keycloak 호환 | Backend raw 검증·decoder/HTTP/validator 테스트·ADR-012 | 없음 | string·array 허용, additional·duplicate·malformed 거부, raw pre-JWK | #233·#235 | Backend 호환 구현 (#236), stock Keycloak 발급 검증 (#239) |
| 완료. `[Infra/Security] Keycloak local/dev runtime 구현` | 실제 local/dev issuer와 client·mapper | Compose, realm, client scope, protocol mapper | 없음 | tag·digest·realm·claim·singleton audience source·rotation | #233 | Phase 1 fresh/existing runtime 완료 (#239) |
| 완료. `[Security/E2E] USER 로그인과 Backend 연동` | browser OIDC와 Resource Server 연결 | Frontend·Backend·Keycloak E2E | `@playwright/test` | raw `aud`·access/ID `sub` 원문 동일성·role 집합·refresh fail-closed·401·403 | Keycloak runtime | Chromium runner 구현 (#239), 격리 Linux Chromium·NSS 신뢰로 전환 (#247) |
| 완료. `[Security/E2E] SERVICE Client Credentials 연동` | 거래·행동 접수 SERVICE 인증 | Keycloak verifier·Compose·문서 | 없음 | 실제 신규·replay·conflict·401·403, PostgreSQL cardinality, External Risk·Rule 1회 | Keycloak runtime | fresh/existing-volume·전용 resource cleanup 구현 (#241) |
| 부분 구현. `[Frontend/Security] role·authority 권한 UI` | 권한별 표시와 action 노출 | navigation, button, route guard UI | 없음 | browser login·expiry·권한 UI | USER E2E, #231 | 권한 판정 계층과 `RequireCapability` guard 구현 (#243); 이를 적용한 production 보호 route·navigation 항목·action 0개 |
| 부분 구현. `[Frontend] 업무 typed API와 query pagination` | 보호 API 소비 | 거래·사건·메모·감사 module, page·size·sort | 없음 | DTO·validator·query 조립·3중 URL 재검증 | #231 | typed API 10개, request·response validator, query pagination 기반 구현 (#245); Backend·API·DB 계약 무변경, 이를 소비하는 production 화면·route·navigation·hook 0개 |
| 완료. `[Frontend] Keycloak remote logout` | RP-initiated logout | end-session seed·exact post-logout URI·root callback·transaction schema 분리 | 없음 | 동기 local invalidation·ID token fail-closed·목적지 고정·state 1회 consume·stale callback·실패 | USER E2E | Chromium USER logout·재로그인·callback 재사용 반례 구현 (#247) |

## 14. 구현 검증 계약

Issue #219·#221 테스트는 credential 없음, malformed·서명 오류·만료 token, issuer·audience,
time·subject·principal_type·role claim 오류, USER·SERVICE role 혼용, role-derived authority,
401·403·503·500 trace와 비노출, JWK cache·rotation·장애, management listener 분리와 profile
간 보안 회귀와 13개 endpoint authority matrix, USER·SERVICE 교차 거부, CORS·encoded path,
네 method security의 transaction 선차단을 검증한다. 성공 write transaction·SYSTEM AuditLog와
optimistic rollback은 유지한다. 성공 USER AuditLog는 후속 Audit Issue에서 검증한다.

## 15. 공식 참고 문서

이 문서의 개념 기준은 Spring Boot 3.5.x와 호환되는 Spring Security 6.5 계열이다.
dependency나 실제 구현이 추가되었다는 의미는 아니다.

- [OAuth2 Resource Server JWT](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html)
- [OAuth2 Bearer Token](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/bearer-tokens.html)
- [Session Management](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html)
- [CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)

## 16. Local Keycloak runtime 경계 (Issue #235)

local/dev Keycloak issuer는 public HTTPS
`https://localhost:8443/realms/finguardops-local`이다. Backend JWK 조회는 같은 container network
namespace에서 loopback HTTP URI `127.0.0.1:8082`를 사용하고 insecure-loopback opt-in을 명시한다.
management readiness도 host 비공개 loopback `127.0.0.1:9000`으로 분리한다. Backend health는
Keycloak readiness에 의존하지 않는다.

한 Backend는 Keycloak 또는 Local JWT fixture issuer/JWK 한 쌍만 신뢰한다. 공식 preflight는 두
issuer service와 혼합 설정을 stack 생성 전에 거부하지만 임의 raw Compose 우회까지 보장하지
않는다. Keycloak private key와 signing key는 `keycloak-data`, local TLS/admin/SERVICE credential은
ignored `.local` file-backed secret 경계에만 있고 helper별 최소 mount를 적용한다.

bootstrap admin secret은 argv와 정적 Compose environment에 없지만 start wrapper가 읽어 child
Keycloak process environment에 전달한다. Docker 관리자는 PID 1 process environment와 host
mount를 볼 수 있으므로 이는 local operator 신뢰 경계이며 production secret manager를 대체하지
않는다. helper는 UID 10001, read-only root, 전용 noexec tmpfs, all-capability drop와
no-new-privileges를 사용한다.

구현된 runtime 검증 범위는 realm/client/scope/mapper reconcile, 두 SERVICE token, raw singleton
string audience, UUID subject/account 일치, 실제 거래·행동 접수의 신규·replay·conflict와
401·403이다. PostgreSQL은 단계별 전체 업무 테이블 delta와 거래별 cardinality를 함께 검사하고,
55/HIGH·ADDITIONAL_AUTH_REQUIRED, FraudCase·CaseTransaction 각 1건과 action별 AuditLog 4건을
확인한다. External Risk 고정 marker와 Rule v2 exact Uvicorn access line은 최초 거래에서만 각각
1 증가해야 하며 Backend outcome metric은 이 실제 hit와 분리된 보조 검증이다. 다른 key의 같은
transactionId 충돌은 연결되지 않은 `FAILED/DUPLICATE_TRANSACTION` 멱등 제어 기록만 남긴다.
USER browser E2E와 refresh-token fail-closed는 Issue #239에서 구현했다. remote logout은 Issue
#247에서 구현했다.

Issue #249는 role UI를 첫 production 보호 route에 적용했다. `/transactions`는
`RequireCapability("transaction:view")`로 보호하며, guard가 navigation이 아니라 route element에
있으므로 직접 URL 진입도 같은 판정을 받는다. `transaction:view`를 갖지 않는 USER
(`RULE_OPERATOR`, `RECOVERY_OPERATOR`, `PLATFORM_ADMIN`)에게는 거래 navigation 항목이 DOM에서
제거되고 route는 `AccessDeniedPage`로 수렴하며, 두 경우 모두 Backend 요청은 0회다. Frontend
guard는 표시 경계이며 Backend는 계속 access token으로 endpoint authority를 다시 판정해 401·403으로
최종 결정한다. browser E2E는 실제 Keycloak USER 로그인으로 navigation 노출, `/transactions` 진입,
실제 Backend `GET /api/v1/transactions` 1회 200, 자동 retry 0회와 credential 비노출을 확인한다.

Issue #251은 같은 capability 경계를 조회 전용 거래 상세 route
`/transactions/{transactionId}`로 확장했다. Frontend capability는 `transaction:view` 하나이며,
Backend authority `transaction:read`와 혼용하지 않는다. 이 화면은 조회 전용이고 거래 수정·재처리·
사건 생성 같은 업무 action이 없으며, API 응답에 없는 위험도·탐지 결과·사건 정보를 표시하지 않는다.

- Frontend의 입력 경계는 브라우저 URL parser가 제공한 최종 location이다. 주소창 입력, link
  click, redirect는 SPA 실행 전에 모두 브라우저 URL parser를 거치므로 dot segment, raw
  backslash, 제거 가능한 control character는 그 단계에서 canonical URL로 정규화된다.
  `/x/../transactions/{uuid}`, `/transactions/%2e%2e/transactions/{uuid}`,
  `/transactions\{uuid}`, 제거 가능한 trailing control character가 붙은 주소는 브라우저에서
  `/transactions/{uuid}`로 수렴한다. SPA는 정규화 이전의 표현을 복구하거나 판별하지 않으며,
  그렇게 주장하지 않는다. 이 경우에도 authentication, `transaction:view` capability, Backend
  `transaction:read` authorization은 모두 그대로 적용된다. SPA 이전 raw request-target 검사는
  reverse proxy·web server 같은 production hosting 경계의 책임이며 이번 Issue 범위가 아니다.
- SPA에 보존된 채 도달한 location에 대해서는 strict하게 판정한다. route parameter는 canonical
  lowercase UUID v4만 받고, 판정은 React Router가 이미 percent-decode한 parameter가 아니라
  브라우저가 넘긴 `location.pathname`·`search`·`hash`에 대해 수행하므로
  `/transactions/%32f4c0a4e-...`처럼 decode 후에만 정상 UUID가 되는 주소는 허용되지 않는다.
  uppercase, non-v4, 잘못된 RFC variant, trailing slash, 추가 segment, 중복 slash, semicolon,
  query, fragment, percent-encoded UUID 문자, `%2F`·`%5C`, double encoding, malformed percent,
  살아남은 whitespace·control character는 모두 고정 invalid-route 상태로 fail-closed되며
  credential 조회와 Backend 요청은 0회다. absolute·protocol-relative URL, userinfo, 다른
  origin·scheme·port는 브라우저 location의 pathname에 남을 수 있는 형태가 아니며, 이 문자열들에
  대한 거부는 아래 복귀 경로 allowlist가 담당한다. 거부된 입력은 화면·오류·`console`·DOM 어디에도 반사되지 않고, 브라우저가 이미
  정규화한 이전 표현은 저장·복원·로그하지 않는다.
- 로그인 후 복귀 allowlist는 `/`, `/health`, `/transactions` 세 literal과
  `/transactions/{canonical lowercase UUID v4}` 한 형태뿐이다. 판정 대상은 pathname 하나가
  아니라 현재 location의 `pathname + search + hash` 전체이므로 query나 fragment가 붙은 상세
  주소는 기본 route `/`로 fail-closed되며, query·fragment를 제거한 뒤 canonical 상세 route로
  재허용하지 않는다. prefix 판정, 문자열 포함 판정, decode·trim 후 재허용, URL normalization
  결과에 대한 신뢰는 사용하지 않으며, 허용된 값도 검증된 36자로 경로를 다시 조립해 반환하므로
  입력 문자열 자체는 반환되지 않는다.
- 401은 요청을 서명한 session만 무효화하고 상세 데이터를 즉시 제거한다. 이미 교체된 session의
  stale 401은 no-op이며 새 session과 그 데이터를 제거하지 않는다. 403과 404는 session을 유지하고
  각각 고정 `Access denied`, `Transaction not found` 화면으로 수렴한다. 어떤 상태 코드에서도
  응답 body·trace id·token·role claim은 화면에 노출되지 않는다.
- 요청당 `fetch`는 최대 1회이고 자동 retry·replay·polling은 0회다. 고객·계좌·device reference와
  금액은 URL query, history state, Web Storage, hidden field, `data-` 속성에 복제되지 않으며
  거래 UUID만 route와 history에 남는다.
- browser E2E는 로그아웃 상태의 상세 주소 직접 진입에서 Backend 요청 0회, 그 주소에서의 실제
  Keycloak 로그인과 canonical 복귀, 실제 Backend `GET /api/v1/transactions/{transactionId}` 1회
  404, `Transaction not found` 화면, session 유지, 자동 retry 0회, credential 비노출을 확인한다.
  또한 실제 404 body의 `code`·`message`·`traceId`를 E2E process memory에서만 파싱해 각 값이
  rendered text, markup, attribute, `title`, 주소창, `history.state`, Web Storage, `console`에
  존재하지 않음을 확인한다. body mock과 sentinel 주입은 없고, 추출한 값과 body 원문은 실패
  메시지·reporter·출력·파일 어디에도 남기지 않으며, parse·타입·blank 실패는 고정 문구로
  fail-closed된다. 이 runtime에는 seed된 거래 row가 없으므로 상세 200 화면은 typed API fixture를
  쓰는 component·hook test가 담당하며, API mock을 실제 Backend E2E 증거로 표현하지 않는다.

Backend, AI Service, Infra, Keycloak, DB와 API 계약은 Issue #251에서 변경하지 않았다.

Issue #253은 같은 경계를 두 번째 Frontend capability로 확장했다. 조회 전용 사건 목록 route
`/cases`는 `RequireCapability("case:view")`로 보호하며, guard는 navigation이 아니라 route
element에 있으므로 rail 클릭과 직접 URL 진입이 같은 판정을 받는다. Frontend capability는
`case:view`이고 Backend authority는 `case:read`다. 두 이름은 서로 다른 계층에 속하므로
혼용하지 않으며, 최종 판정은 Backend의 독립적인 access token 검증과 401·403 응답이 내린다.

- 접근 허용 role은 4장 capability matrix 그대로 `FDS_VIEWER`, `FDS_ANALYST`, `FDS_APPROVER`다.
  `RULE_OPERATOR`, `RECOVERY_OPERATOR`, `PLATFORM_ADMIN` session의 DOM에는 사건 navigation
  항목도 `/cases` 문자열도 남지 않고, route는 `AccessDeniedPage`로 수렴한다. 미인증·인증 오류·
  signing-out에서는 보호 내용이 제거된다. 이 모든 경로에서 credential 조회와 Backend 요청은
  0회다.
- 로그인 후 복귀 allowlist에는 exact `/cases` 하나만 추가했다. 판정 대상은 pathname이 아니라
  현재 location의 `pathname + search + hash` 전체이므로 `/cases/`, `/cases/{caseId}`,
  `/casesx`, query·fragment가 붙은 주소, encoded slash·backslash, 중복 separator, absolute·
  protocol-relative URL, userinfo, 다른 origin·scheme·port는 모두 기본 route `/`로
  fail-closed된다. query·fragment를 제거한 뒤 `/cases`로 재허용하지 않으며, 거부된 입력은
  화면·오류·`console`·DOM 어디에도 반사되지 않는다. 사건 상세 route가 없으므로 `/cases` 아래에
  parameterized 복귀 형태도 없다.
- 401은 요청을 서명한 session만 무효화하고 사건 데이터를 즉시 제거한다. 이미 교체된 session의
  stale 401은 no-op이며 새 session과 그 데이터를 제거하지 않는다. 403은 session과 capability를
  유지한 채 고정 `Access denied` 상태로 수렴하고 retry를 제공하지 않는다. 어떤 상태 코드에서도
  응답 body·status·trace id·token·role claim은 화면에 노출되지 않으며, malformed 응답은 일부
  사건만 표시하지 않고 전체를 거부한다.
- 요청당 `fetch`는 최대 1회이고 자동 retry·replay·polling은 0회다. assignee reference와 관련
  거래 UUID filter는 컴포넌트 메모리에만 있으며 URL query, history state, Web Storage,
  hidden field, `data-` 속성에 복제되지 않는다. 시간 범위 역전, malformed UUID, Backend 계약을
  벗어난 assignee reference는 credential 조회와 `fetch` 이전에 거부된다.
- browser E2E는 실제 Keycloak `FDS_ANALYST` 로그인으로 사건 navigation 노출, `/cases` 진입,
  실제 Backend `GET /api/v1/cases` 1회 200, canonical initial query와 filter 적용 query가
  socket에 실제로 쓰인 request target과 일치함, 자동 retry 0회, reference의 URL·history·Web
  Storage·console 비노출, 세 viewport에서 가로 overflow 부재, business mutation 0회를 확인한다.
  이 runtime에는 seed된 사건 row가 없으므로 deterministic empty 상태도 통과 조건으로 인정하며,
  Backend·DB·Infra에 test data 생성 경로를 추가하지 않는다.

Issue #253에서도 Backend, AI Service, Infra, Keycloak, DB와 API 계약 변경은 없다. 사건 상세,
상태 변경, 담당자 변경, 최종 판정, 조사 메모와 감사 이력 화면은 아직 구현되지 않았다.

Stock Keycloak은 HTTP와 HTTPS에 공통 listener host를 적용하므로 2026-09-05 OWNER 결정에 따라
`KC_HTTP_HOST=0.0.0.0`을 사용한다. HTTPS 8443만 host `127.0.0.1`에 publish하고 HTTP 8082와
management 9000은 publish하지 않는다. HTTP listener 자체는 공유 namespace에서
`0.0.0.0:8082`에 listen하므로 다른 Docker participant의 접근 불가능을 주장하지 않는다. Backend와
승인 helper는 실제 credential과 Admin token을 bridge 주소로 전송하지 않고 namespace loopback만
사용한다. local/dev network participant는 operator 신뢰 경계이며 production에서는 별도 network
segmentation, trusted TLS, secret manager와 production Authorization Server 계약이 필요하다.
별도 proxy/service/image와 helper 공유 persistent volume은 없고 Keycloak용 `keycloak-data`만 추가한다.
Issue #241의 SERVICE 검증은 `user_password`를 bootstrap에만 read-only mount하고 verifier·Backend·
AI Service에는 제공하지 않는다. USER 회귀는 #239 USER runbook과 Frontend 인증 targeted test를
그대로 유지하고 production 파일·realm·bootstrap을 변경하지 않는 방식으로 확인한다. headless USER 로그인, direct grant,
Chromium과 Playwright는 이 검증 경계 밖이다. Issue #247 이후 브라우저 신뢰는 Windows 인증서
저장소를 전혀 사용하지 않고, 실행별 NSS database를 가진 격리 Linux Chromium container 안에만
존재한다. 그 container image는 별도 준비 단계에서 고정 digest의 Playwright image 위에 exact version
`libnss3-tools`만 더해 빌드하며, 공식 E2E Run은 registry에 접근하지 않고 이미 local에 있는 image만
`--no-build --pull never`와 exact image ID로 실행한다. image 신뢰는 label에 두지 않는다. Run은
고정 digest base가 local에 있는지, base와 준비 image가 모두 linux/amd64인지, base의 RootFS layer
목록이 준비 image RootFS의 exact ordered prefix인지, Dockerfile 구조대로 layer가 정확히 하나만
추가되었는지, `Config.User`가 exact `pwuser`인지를 먼저 검사하고, 이어서 network 없는 read-only·
capability 0 container 안에서 package version, `certutil` 실행, Node·Playwright core·browser
revision과 실제 UID, mount 집합을 확인한다. 실패는 관측값을 반사하지 않는 고정 오류다. Run 경로에는
npm과 npx가 없고 Playwright와 Vite는 설치된 local entry point를 Node로 직접 실행한다.
fresh/existing runtime은 같은
전용 project 안에서 수행하고 종료 시 해당 label의 container·network·volume이 0이어야 한다. 공용
local Docker image는 삭제·잔존 판정 대상이 아니다.
