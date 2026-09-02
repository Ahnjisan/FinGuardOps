# FinGuardOps 인증·인가·USER Audit actor 아키텍처

## 1. 목적과 구현 상태

이 문서는 FinGuardOps Spring Boot Backend의 제품 중립적인 OAuth2 Resource Server,
JWT, USER·SERVICE principal, role·authority, endpoint RBAC, 401·403·trace와 USER Audit
actor 목표 계약을 정의한다. Architecture Decision은
[`ADR-008`](../07-decisions/ADR-008-oauth2-resource-server-rbac-user-audit-actor.md)을
따른다.

### 1.1 현재 상태

- Spring Boot 관리 버전의 Spring Security·OAuth2 Resource Server dependency와 application
  listener용 `SecurityFilterChain`, JWT decoder·validator가 구현되었다.
- 정상 JWT를 immutable USER·SERVICE principal로 변환하고 JWT role의 `ROLE_` authority와
  세부 authority를 생성한다.
- 기존 업무 endpoint는 정상 인증을 요구하지만 endpoint별 세부 authority enforcement와
  `@PreAuthorize`는 아직 구현하지 않았다.
- login·signup·refresh·logout endpoint와 사용자·role·credential DB가 없다.
- 사건 workflow·resolution·note writer는 `SYSTEM/finguardops-backend`를 기록한다.
- Issue #215 사건 감사 조회 응답은 `actorType`만 공개하고 `actorId`는 비노출한다.

### 1.2 목표와 비범위

Issue #219는 Resource Server 기반과 JWT·principal·공통 오류·listener 경계를 구현했다.
endpoint별 RBAC, USER actor, Frontend 로그인, Authorization Server와 Compose 인증은 후속
범위이며 구현 완료로 표현하지 않는다.

## 2. 신뢰 경계

```text
Authorization Server
  └─ RS256 access token 발급·credential·login 책임
       ↓ Authorization: Bearer <JWT>
Spring Boot Resource Server
  ├─ signature·issuer·audience·time·claim 검증
  ├─ role → authority 변환과 인증 요구
  ├─ endpoint별 authority enforcement는 후속 Issue
  ├─ 금융 업무 정합성·transaction 소유
  └─ 성공 사건 write의 USER Audit actor 기록

Management 8081
  └─ 업무 JWT와 분리된 private network/scrape 경계
```

Backend는 access token을 발급·갱신·폐기하지 않는다. 구체적인 Authorization Server
제품과 실제 issuer·JWK URI는 후속 Issue에서 결정한다. trusted header와 고정 production
token은 이 경계를 대체할 수 없다.

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
| `aud` | JSON string array, 필수 | 정확히 `["finguardops-backend-api"]` |
| `sub` | JSON string, 필수 | USER·SERVICE 모두 canonical lowercase UUID v4 |
| `principal_type` | JSON string, 필수 | 정확히 `USER` 또는 `SERVICE` |
| `roles` | JSON string array, 필수 | 중복 없는 알려진 role. string coercion·unknown·duplicate 금지 |
| `iat` | NumericDate, 필수 | 현재 시각보다 60초를 초과해 미래이면 거부 |
| `exp` | NumericDate, 필수 | 유효 기간 종료 검증, `exp - iat <= 15분` |
| `nbf` | NumericDate, 선택 | 존재하면 60초 clock skew로 검증 |

UUID는 trim, lowercase 변환, 재직렬화 같은 정규화를 하지 않고 입력 문자열 자체가
canonical lowercase UUID v4인지 검증한다. 외부 `authorities` claim은 존재해도 권한
결정에 사용하지 않는다.

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

## 5. 실제 Spring endpoint inventory와 목표 RBAC

아래 13개는 현재 production Controller에 실제 존재한다. Issue #219에서는 public health를
제외한 업무 endpoint에 정상 JWT를 요구하지만 표의 endpoint별 목표 authority는 아직
강제하지 않는다.

| Method·path | Controller·기능 | R/W | principal·허용 role | 목표 authority | Public | 현재 인증 | 성공 업무 AuditLog |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `GET /api/health` | `HealthController`, process health | R | 없음 | 없음 | 예 | public | 없음 |
| `POST /api/v1/transactions` | `TransactionIntakeController`, 거래 접수 | W | SERVICE · `TRANSACTION_INGESTOR` | `transaction:intake` | 아니오 | 정상 JWT 필요 | 조건부 SYSTEM 거래·사건 감사 |
| `GET /api/v1/transactions` | `TransactionQueryController`, 거래 목록 | R | USER · viewer authority 보유 role | `transaction:read` | 아니오 | 정상 JWT 필요 | 없음 |
| `GET /api/v1/transactions/{transactionId}` | `TransactionQueryController`, 거래 상세 | R | USER · viewer authority 보유 role | `transaction:read` | 아니오 | 정상 JWT 필요 | 없음 |
| `POST /api/v1/behavior-events` | `BehaviorEventIntakeController`, 행동 접수 | W | SERVICE · `BEHAVIOR_INGESTOR` | `behavior-event:intake` | 아니오 | 정상 JWT 필요 | 없음 |
| `GET /api/v1/cases` | `FraudCaseQueryController`, 사건 목록 | R | USER · viewer authority 보유 role | `case:read` | 아니오 | 정상 JWT 필요 | 없음 |
| `GET /api/v1/cases/{caseId}` | `FraudCaseQueryController`, 사건 상세 | R | USER · viewer authority 보유 role | `case:read` | 아니오 | 정상 JWT 필요 | 없음 |
| `PATCH /api/v1/cases/{caseId}/status` | `FraudCaseWorkflowController`, 상태 변경 | W | USER · `FDS_ANALYST` | `case:workflow:write` | 아니오 | 정상 JWT 필요 | 현재 SYSTEM, 목표 USER |
| `PATCH /api/v1/cases/{caseId}/assignee` | `FraudCaseWorkflowController`, 담당자 변경 | W | USER · `FDS_ANALYST` | `case:workflow:write` | 아니오 | 정상 JWT 필요 | 현재 SYSTEM, 목표 USER |
| `POST /api/v1/cases/{caseId}/resolution` | `FraudCaseWorkflowController`, 사건 종결 | W | USER · `FDS_APPROVER` | `case:resolution:write` | 아니오 | 정상 JWT 필요 | 현재 SYSTEM, 목표 USER |
| `POST /api/v1/cases/{caseId}/notes` | `InvestigationNoteController`, 메모 생성 | W | USER · `FDS_ANALYST` | `case-note:write` | 아니오 | 정상 JWT 필요 | 현재 SYSTEM, 목표 USER |
| `GET /api/v1/cases/{caseId}/notes` | `InvestigationNoteController`, 메모 조회 | R | USER · viewer authority 보유 role | `case-note:read` | 아니오 | 정상 JWT 필요 | 없음 |
| `GET /api/v1/cases/{caseId}/audit-logs` | `FraudCaseAuditLogController`, 감사 조회 | R | USER · viewer authority 보유 role | `case-audit:read` | 아니오 | 정상 JWT 필요 | 없음 |

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

- application listener의 구현 matcher·chain 책임 순서는 다음과 같다.
  1. `TraceIdFilter`가 현재 요청 traceId를 확정한다.
  2. Bearer credential이 있으면 authentication을 검증한다.
  3. 승인된 CORS preflight를 처리한다.
  4. exact public application path인 `/api/health`와 기본 profile에서 실제 노출된
     `/actuator/health`를 authority 없이 허용한다.
  5. 업무 endpoint에는 정상 인증을 요구한다.
  6. endpoint별 세부 authority 검사는 후속 Issue에서 적용한다.
- management `8081`은 위 업무 JWT chain과 별도 경계다.
- profile에 따라 mapping되지 않은 Actuator path는 Security가 먼저 401·403을 반환하지 않고
  기존 404를 유지하도록 matcher 순서와 management context를 검증한다. invalid
  Bearer의 authentication 401은 이 404 exposure 계약과 별도로 검증한다.
- SERVICE ingestion endpoint의 전용 authority와 USER 업무 endpoint의 세부 authority는
  후속 RBAC Issue에서 강제한다.
- 사건 write·resolution 같은 고위험 Service에는 authority 기반 method security를 함께
  적용한다.
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

### 8.1 현재 Java·DB 계약

- `AuditActorType`은 `SYSTEM`, `USER`를 허용한다.
- 일반 `audit_log`에서 SYSTEM actorId는 `finguardops-backend`, USER actorId는 canonical
  lowercase UUID v4다.
- 사건 workflow·resolution·note writer는 현재 SYSTEM을 사용한다.
- `investigation_note.author_type/author_ref`와 `CASE_NOTE_CREATED` DB CHECK는 현재
  SYSTEM 전용이다.
- 사건 감사 조회 응답은 `actorType`만 공개하고 `actorId`는 비노출한다.

### 8.2 목표 전달 경계

- 인증 adapter가 검증된 JWT로 immutable principal/actor를 만든다.
- USER의 `sub`만 `AuditActorType.USER`의 `actorId`로 사용한다.
- SERVICE의 `sub`는 USER actor로 저장하지 않는다.
- email, display name, 내부 DB PK와 body·query·임의 header의 actor 값은 사용하지 않는다.
- Controller는 인증 adapter가 만든 actor만 Service로 전달한다.

### 8.3 USER·SYSTEM 적용

| 처리 | 목표 actor |
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

조사 메모 USER 전환은 `investigation_note`와 `CASE_NOTE_CREATED`의 SYSTEM 전용 CHECK를
변경하는 migration을 후속 Issue에서 함께 수행해야 한다. 기존 SYSTEM row는 유지한다.

### 8.4 actorId 응답의 현재와 목표

- 현재: Issue #215 응답은 `actorType`만 공개하고 `actorId`는 비노출한다.
- 목표: USER actor 구현과 감사 API 변경이 완료된 뒤 `case:audit:read` 권한 사용자에게
  USER actor UUID만 공개한다.
- email과 display name은 공개하지 않는다.
- SYSTEM actorId 공개 여부는 후속 API 변경에서 별도로 결정한다.

목표가 구현되기 전에는 현재 #215 projection 계약을 유지한다.

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
- SPA access token은 memory 보관을 우선하고 localStorage·sessionStorage 장기 저장을
  금지한다.
- 향후 SPA 로그인은 Authorization Code + PKCE를 사용한다.
- refresh token, 로그인 화면, token 획득·갱신과 권한 UI는 별도 Frontend Issue다.

현재 Frontend에는 API client, 로그인, token 저장·refresh와 인증 상태 관리가 구현되지
않았다.

## 11. Local·test·Compose 경계

- production Security chain을 profile로 끄지 않는다.
- test는 ephemeral asymmetric key 또는 test-only decoder를 사용하되 production과 같은
  claim validator를 실행한다.
- MockMvc JWT fixture는 exact claim 타입, USER·SERVICE 구분과 role mapping을 재현한다.
- local Compose issuer/JWK fixture는 아직 없고 후속 Issue에서 적용한다.
- production과 기본 runtime issuer/JWK는 HTTPS만 허용한다. test의 in-process JWK server는
  명시적인 loopback 전용 opt-in에서만 HTTP를 허용한다.
- production issuer/JWK는 HTTPS가 아니면 fail-closed한다.
- 실제 private key, JWT와 credential은 repository·문서·`.env.example`·로그에 저장하지
  않는다.
- `.env.example`에는 issuer·JWK URI·audience 같은 비밀이 아닌 placeholder만 허용한다.
- CI key는 secret 또는 ephemeral generation으로 공급한다.
- 설정 누락은 인증 비활성으로 fallback하지 않는다.

현재 Compose의 Backend application `8080`과 management `8081`은 host에 publish되지
않는다. Prometheus는 internal observability network에서 management `8081`을 scrape한다.
Grafana·Alertmanager 계약은 이 문서 Issue에서 바꾸지 않는다. 기존 거래 traffic
generator는 Authorization header가 없으므로 SERVICE token 적용 후속 Issue에서 변경해야
한다. management scrape에는 업무 JWT를 추가하지 않는다.

## 12. 위협과 통제

| 위협 | 통제 경계 |
| --- | --- |
| forged trusted header | trusted header 방식 금지, JWT signature 검증 |
| unsigned JWT·algorithm confusion | RS256 단일 allowlist, `kid` 필수, `alg=none` 거부 |
| issuer·audience 생략 | 필수 exact validator |
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

OAuth2 Resource Server 기반과 401·403·trace 경계는 Issue #219에서 구현되었다. 아래 표는
남은 후속 구현 네 개만 순서대로 표시한다.

| 순서·제목 | 목표 | 주요 변경 영역 | Migration | 테스트 경계 | 선행 Issue | 장시간 검증·현재 상태 |
| --- | --- | --- | --- | --- | --- | --- |
| 1. `[Backend/Security] Endpoint RBAC와 USER·SERVICE authority matrix 적용` | deny-by-default와 endpoint 최소 권한 | request matcher, role converter, method security | 없음 | 13개 endpoint 200·401·403, role 혼용 | #219 | 전체 MockMvc matrix; 미구현 |
| 2. `[Backend/Audit] 사건 write USER actor와 InvestigationNote author 연결` | 검증 principal을 성공 감사에 연결 | controller/service actor, note author, audit projection | 필요 | 성공·stale·rollback·기존 SYSTEM 호환 | 1 | 동시성·migration; 미구현 |
| 3. `[Infra/Docs] Local Compose·runbook JWT fixture와 인증 E2E 적용` | local issuer와 SERVICE traffic | Compose, fixture, env example, runbook | 없음 | build·wait·traffic·scrape·alert·restart | #219·1 | Docker E2E; 미구현 |
| 4. `[Frontend] OIDC 로그인·token·권한 UI 구현` | SPA 인증·권한 UX | PKCE, memory token, API client, 401·403 UI | 없음 | browser login·expiry·권한 UI | AS 제품, #219·1 | 브라우저/AS E2E; 미구현 |

## 14. 구현 검증 계약

Issue #219 테스트는 credential 없음, malformed·서명 오류·만료 token, issuer·audience,
time·subject·principal_type·role claim 오류, USER·SERVICE role 혼용, role-derived authority,
401·403·503·500 trace와 비노출, JWK cache·rotation·장애, management listener 분리와 profile
간 보안 회귀를 검증한다. endpoint authority matrix, 성공 USER AuditLog, 실패 무감사와
optimistic rollback은 후속 RBAC·Audit Issue에서 검증한다.

## 15. 공식 참고 문서

이 문서의 개념 기준은 Spring Boot 3.5.x와 호환되는 Spring Security 6.5 계열이다.
dependency나 실제 구현이 추가되었다는 의미는 아니다.

- [OAuth2 Resource Server JWT](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html)
- [OAuth2 Bearer Token](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/bearer-tokens.html)
- [Session Management](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html)
- [CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)
