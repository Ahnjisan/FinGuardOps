# Issue #380 로컬 사기 탐지 ML 계약

이 계약은 합성 데이터로만 검증하는 로컬 Rule+ML 탐지 경로다. 실제 금융 사기 성능이나 운영 오탐 기준을 보증하지 않는다. Qwen 사건 리포트 모델과 별개다.

## 내부 FastAPI 추론

`POST /api/v1/ml-inference`는 Backend 전용이다. 성공은 `200`, 요청 형식 오류는 `400`, Feature 시점·중복 위반은 `422`, 모델 부재·해시/버전 불일치는 `503`이다. 응답을 받은 Backend는 Rule 응답을 기존 계약대로 먼저 검증하고 ML 응답을 별도로 검증한다. 이 endpoint는 거래·사건 상태를 변경하지 않는다.

Backend는 ML 성공 본문을 채택하기 전에 HTTP 상태가 정확히 `200`이고
Content-Type이 단일 JSON media type이며 본문이 비어 있지 않은지 확인한다.
`201`·`202`, 비 JSON·누락·중복 Content-Type, 빈 성공 본문은
`ML_INVALID_RESPONSE`로 실패 처리한다. 정상적인 모델 오류 응답의 제한된
실패 코드 분류는 유지한다.

요청은 `transactionId`(UUID v4), `evaluationCutoffAt`(UTC), `amount`(0 이상 문자열), `transactionType`, `channel`, `featureVersion=fraud-feature-v1`, `scoringPolicyVersion=rule-ml-policy-v1`, `modelVersion=fraud-logistic-v1` 또는 `fraud-logistic-v2`(cutoff로 선택), `modelSha256`(64자리 SHA-256), `events` 배열만 받는다. 각 이벤트는 불투명 `eventId`, `eventType`, `occurredAt`, `createdAt`만 포함한다. 고객·계좌·기기·수취인 원문 참조, Rule 점수, 사건 판정은 받지 않는다. Backend는 같은 고객의 24시간 이벤트를 `occurredAt DESC, eventId ASC`로 최대 1001건 읽고 1000건 초과를 `ML_EVENT_LIMIT_EXCEEDED`로 실패시킨다. `occurredAt`과 `createdAt`은 모두 cutoff 이하여야 한다. FastAPI도 시간·중복을 다시 검사한다.

결합 분석의 Rule 행동 Snapshot에도 `occurredAt`과 DB 저장 시각
`createdAt`이 모두 cutoff 이하여야 한다. 과거 Rule 전용 분석의 조회 계약은
변경하지 않으며 저장된 v1/v2 결과는 다시 계산하지 않는다.

응답 필드는 `transactionId`, `evaluationCutoffAt`, `featureVersion`, `scoringPolicyVersion`, `modelVersion`, `modelSha256`, `probabilityBasisPoints`(정수 0–10000), `reasonCode`(`ML_RISK_SIGNAL` 또는 `ML_BELOW_THRESHOLD`)뿐이다. Backend는 거래·cutoff·버전·해시·확률 범위·Reason Code 일치를 검증한다. 모델 원문, 전체 Feature 벡터, 식별자 원문은 반환하지 않는다.

The `local` profile starts ML at `2026-10-09T15:00:00Z` (2026-10-10 00:00 KST). Explicit local replay experiments may override this with `FINGUARDOPS_ML_EFFECTIVE_FROM`. Newly received transactions whose cutoff predates activation continue on the Rule v2 path. Within ML-enabled cutoffs, `fraud-logistic-v1` with its immutable SHA applies before `2026-10-10T00:40:00Z`; `fraud-logistic-v2` with its immutable SHA applies at and after that instant. This local model selection boundary is fixed in code. A transaction received later with an earlier cutoff still selects v1. The unchanged `rule-ml-policy-v1` score formula applies to both models.

## `rule-ml-policy-v1` 채택

최초 거래 분석에서 cutoff가 설정된 ML 유효 시작 시각 이상이고 로컬 ML 기능이 켜져 있을 때만 적용한다. 모델 버전·해시와 Rule 집합은 분석 시작의 불변 DetectionResult에 고정한다. 완료 거래의 멱등 재전송은 기존 Snapshot만 반환하며 재호출하지 않는다. 과거 Rule v1/v2 결과를 새 모델로 재분석하지 않는다.

기존 검증된 Rule 점수를 `R`, ML 응답을 `P` basis points로 둔다. `P ≤ 5000`이면 기여도 `M=0`, 그 외 `M=floor(((P−5000)×40+2500)/5000)`이다. 최종 점수는 `min(100,R+M)`이다. 0–19 LOW, 20–49 MEDIUM, 50–79 HIGH, 80–100 CRITICAL로 나눈다. 기존 Backend 위험 정책이 이 최종 등급을 처리한다. 5000bp와 최대 40점은 **합성 데이터 로컬 검증 정책**이며 실제 금융 운영 임계값이 아니다.

ML timeout·모델 부재·버전 불일치·Feature 오류·응답 불일치는 해당 DetectionResult와 거래를 `FAILED`로 남긴다. 채택 점수·위험 대응·사건은 생성하지 않는다. 거래 접수 호출에는 기존 제한된 의존성 실패 응답과 멱등 실패 상태를 사용하고, 채택 결과 조회의 `latestFailureCode`에는 제한된 `ML_*` 코드만 보여 준다. 같은 키를 자동 재실행하지 않는다. 실패한 최신 분석과 이전 채택 분석은 서로 다른 상태로 표시한다.

Backend failure codes include `ML_TIMEOUT`, `ML_MODEL_UNAVAILABLE`, `ML_MODEL_VERSION_MISMATCH`, `ML_PINNED_VERSION_MISMATCH`, `ML_INVALID_FEATURES`, and `ML_INVALID_RESPONSE`. A service error cannot supply a score.

Backend records `finguardops.ml.inference.total` and `finguardops.ml.inference.duration` with only `outcome` and bounded failure `category` tags. FastAPI logs inference duration and category without transaction or customer identifiers. These measure service calls and do not claim end-to-end transaction latency.

`GET /api/v1/ml-inference/model` returns `ready=true`, `modelVersion`, `modelSha256`, and `featureVersion` when the pinned model loads; it returns `503` with a restricted model error code otherwise. This status endpoint has no transaction input and makes no business decision.

## 채택 결과 조회 추가 필드

기존 `GET /api/v1/transactions/{transactionId}/adopted-detection-result`의 `adoptedResult`에 `ruleScore`(정수), `mlContribution`(nullable), `mlStatus`(`APPLIED` 또는 `RULE_ONLY`), `modelVersion`·`mlFeatureVersion`·`modelSha256`(nullable), `mlEvidence` 배열을 추가한다. ML 근거는 `reasonCode`, `scoreContribution`, `probabilityBasisPoints`만 노출한다. Rule 전용 과거 결과는 `ruleScore=riskScore`, 나머지 ML 값 null/빈 배열이다. 최상위에 `latestFailureCode`(nullable)를 추가하며 최신 분석이 실패한 경우에만 허용된 `ML_*` 실패 코드를 담는다. 채택 결과가 존재해도 최신 실패는 채택 점수를 대체하지 않는다.

Qwen 리포트는 기존 채택 결과 버전과 검증된 `RULE` 근거만 받아 자유로운 ML 사실 서술로 확대하지 않는다.
