# External Risk Provider HTTP API

## 1. 목적과 현재 범위

이 문서는 Spring Boot가 External Risk Provider를 조회하는 내부 HTTP 계약의 공식
기준이다. 실제 HTTP Adapter, strict mapper, timeout·bounded body·failure classifier와
production source의 Provider·Policy·coordinator Bean은 구현되었다. public transaction
intake의 멱등 단일 승자는 실제 HTTP Provider 또는 local/dev/test Mock을 호출하고,
성공 결과를 `/api/v2/rule-analysis`의 Rule v1 평가·응답 검증·결과 채택과 위험
대응·사건·감사 finalization으로 전달한 뒤 Snapshot v2를 완료해 HTTP `201`을
반환한다. typed 실패의 Failure Snapshot 저장·재생과 공개 안전 오류 mapper, 성공
Snapshot replay와 final-success completion gap의 제한된 one-shot recovery도 구현됐다.
성공 External Risk 결과의 별도 DB 영속화, retry·cache·fallback, production credential
배포, cloud deployment와 운영 dashboard·세부 metric은 구현되지 않았다.

## 2. HTTP 계약

```http
POST /v1/external-risk/lookup
Content-Type: application/json
Accept: application/json
Authorization: Bearer <api-key>
X-Trace-Id: <trace-id>
```

- 성공 status는 정확히 `200`이다.
- 응답 Content-Type은 `application/json`이며 charset parameter를 허용한다.
- redirect를 따르지 않으며 retry·fallback·cache·endpoint 전환을 하지 않는다.
- 기본 connect timeout은 `2s`, read timeout은 `3s`다.
- non-`200` 응답 body는 읽거나 보존하지 않고 status만 분류한다.
- `200` 응답 body는 최대 `65,536`바이트다. `Content-Length`를 먼저 확인하고
  stream에서도 최대값보다 1바이트까지만 읽는다.
- 응답 trace echo를 요구하거나 저장하지 않는다.

## 3. 요청

요청은 camelCase의 다음 7개 필드만 포함한다. `transactionId`는 전송하지 않는다.
nullable 필드는 생략하지 않고 JSON `null`로 전송한다.

| 필드 | 타입 | null | 계약 |
| --- | --- | --- | --- |
| `transactionType` | string | 불가 | Java enum의 exact uppercase name |
| `evaluationCutoffAt` | string | 불가 | canonical `ISO_INSTANT` UTC `Z`, 최대 마이크로초 |
| `externalCustomerRef` | string | 불가 | 기존 비식별 reference 계약 |
| `senderAccountRef` | string | 불가 | 기존 비식별 reference 계약 |
| `recipientAccountRef` | string | 허용 | 없으면 명시적 `null` |
| `deviceRef` | string | 허용 | 없으면 명시적 `null` |
| `traceId` | string | 불가 | 같은 값을 `X-Trace-Id`에도 전달 |

내부 command/request 검증이나 request mapping 실패는 호출 전 `INVALID_REQUEST`다.

## 4. 응답

root는 `providerCode`, `providerAsOf`, `matches` 세 필드만 포함한다. 각 match는
`subjectType`, `riskType`, `reasonCode` 세 필드만 포함한다. unknown·extra·missing·null·
wrong-type field, duplicate key와 trailing token을 거부한다. Enum은 exact uppercase
name만 허용한다.

- `providerCode`: `^[A-Z][A-Z0-9_]{0,63}$`
- `providerAsOf`: canonical UTC `Z`, 최대 마이크로초
- `matches`: 최대 3개, 중복 금지
- 허용 조합: `SENDER_ACCOUNT/SUSPICIOUS_ACCOUNT/SUSPICIOUS_SENDER_ACCOUNT`,
  `RECIPIENT_ACCOUNT/SUSPICIOUS_ACCOUNT/SUSPICIOUS_RECIPIENT_ACCOUNT`,
  `DEVICE/RISK_DEVICE/RISK_DEVICE`
- 거래·고객·계좌·기기 reference echo는 unknown field로 거부한다.

## 5. 실패 매핑

| 조건 | category |
| --- | --- |
| connect/read/HTTP timeout, `408`, `504` | `TIMEOUT` |
| `400`, `422`, 내부 request 계약 위반 | `INVALID_REQUEST` |
| `501` | `UNSUPPORTED_CAPABILITY` |
| 그 밖의 `3xx`, `4xx`, `5xx`, DNS·connection·TLS·I/O·interrupt | `UNAVAILABLE` |
| 200 외 `2xx`, 200의 malformed/empty/non-JSON/oversize body, strict 응답 위반 | `INVALID_RESPONSE` |
| strict 검증 뒤 domain 변환 중 예상하지 못한 `IllegalArgumentException` | `TRANSFORMATION_ERROR` |

interrupt는 flag를 복원한다. Parsing cause와 응답 body는 보존하지 않는다. 외부에는
기존 `ExternalRiskLookupException`의 category별 고정 안전 메시지만 노출한다.

## 6. 설정과 Bean

prefix는 `finguardops.external-risk.http`이며 `enabled`, `base-url`, `api-key`,
`connect-timeout`, `read-timeout`, `max-response-bytes`를 사용한다. 기본값은 각각
`false`, 미설정, 미설정, `2s`, `3s`, `65536`이다.
credential 환경변수 예시는 `FINGUARDOPS_EXTERNAL_RISK_HTTP_API_KEY`다.

- local/dev/test: `external-risk-http` profile과 `enabled=true`가 모두 필요하다.
- prod/production: `enabled=true`, HTTPS base URL과 nonblank API key가 필수다.
- local/dev/test의 HTTP base URL은 loopback 또는 HTTPS만 허용한다.
- base URL은 path·user-info·query·fragment 없는 absolute origin이다.
- timeout은 양수, body 상한은 `1..65536`이다.
- Mock enabled와 HTTP enabled 조합은 startup fail-fast다.

credential, Authorization header, request·response body, reference는 DTO의 문자열 표현,
로그, 예외, Snapshot에 기록하지 않는다.

## 7. public 연결·재생·recovery 경계

- 신규 public 거래 접수는 Validation·fingerprint, Idempotency claim, 거래 `RECEIVED`
  저장 뒤 활성 DB 트랜잭션 없이 이 Provider를 호출한다.
- Provider 성공 `ExternalRiskSnapshot`은 `/api/v2/rule-analysis` wire 입력으로만 전달되며
  Rule v1 R001~R004 결과 검증·채택, 위험 대응·사건·감사 finalization과 Snapshot v2
  completion까지 동기로 연결된다. 성공 External Risk 결과 자체의 Entity·Repository·
  table은 없다.
- 여섯 typed failure는 승인된 `503 DEPENDENCY_TIMEOUT|DEPENDENCY_UNAVAILABLE` 또는
  `500 INTERNAL_ERROR`와 안전한 고정 message로 매핑하고 Failure Snapshot에 저장한다.
  같은 key·fingerprint의 Failure Snapshot replay는 Provider·Rule·finalization을 다시
  호출하지 않는다. 성공 Snapshot legacy·v1·v2 replay도 downstream을 다시 호출하지
  않는다.
- 최종 업무 성공이 확정되고 Snapshot v2 completion만 남은 단건은 bounded 후보 조회와
  typed 판정을 거친 non-web one-shot recovery로 완료할 수 있다. 이 recovery는
  Provider·Rule·finalization을 재수행하지 않고 append-only audit를 남긴다.
- Provider 호출 성공 여부가 불확실한 작업, failure-writer crash와 Rule 실패의 재수행,
  scheduler·batch·상시 운영·HA coordination, 장기 completion-gap metric·alert·dashboard는
  구현되지 않았다.
