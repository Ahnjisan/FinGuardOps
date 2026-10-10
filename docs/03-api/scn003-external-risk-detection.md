# SCN-003 외부 위험 수취 계좌 탐지 계약

Issue #382의 `SCN003-contract-v1`을 따른다. 이 문서는 Rule v1/v2 및 Rule+ML v1 문서의 과거 계약을 변경하지 않고, 미래 cutoff에 발행할 정책 집합만 정의한다. 점수와 임계값은 합성·Mock 로컬 정책이며 실제 금융 운영 기준이나 탐지 성능 주장이 아니다.

## 요청과 버전

새 공개 엔드포인트는 없다. 내부 `POST /api/v2/rule-analysis`의 기존 `externalRisk` 구조를 사용한다. wire 경로의 `v2`와 Rule 정책 버전은 별개다. 정확한 집합은 R001–R004 각각 v3 및 R005 v1이다. 누락·중복·다른 버전은 거부한다. R005의 Rule 코드와 Rule Evidence reasonCode는 `EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT`이고, Provider match reasonCode는 `SUSPICIOUS_RECIPIENT_ACCOUNT`다.

R005 conditionDefinition은 `subjectType=RECIPIENT_ACCOUNT`, `riskType=SUSPICIOUS_ACCOUNT`, `reasonCode=SUSPICIOUS_RECIPIENT_ACCOUNT`, `freshnessSeconds=86400`으로 고정한다. 이체의 수취 계좌가 있을 때 Provider match 세 값이 정확히 일치해야 한다. `T=transaction.occurredAt`에서 `T-24h <= providerAsOf <= T <= lookedUpAt`을 포함 경계로 검증한다. 송신 계좌·기기 match는 R005 점수에 기여하지 않는다. ATM·대출에는 이 신규 신선도 정책을 적용하지 않고 기존 Provider 검증·장애 처리를 유지한다.

HTTP Provider의 기대 `providerCode`는 필수 시작 설정 `finguardops.external-risk.http.expected-provider-code`로 고정한다. 로컬 HTTP fixture는 `PROVIDER_V1`, Mock adapter는 `EXTERNAL_RISK_MOCK_V1`이다. 불일치·누락·모순·늦은 Snapshot은 `INVALID_RESPONSE`로 fail closed 처리한다. 거래는 RECEIVED로 남고, 채택 DetectionResult·사건·Audit는 생성하지 않는다. 같은 멱등 키의 재전송은 기존 실패 Snapshot을 반환하며 자동 Provider 재호출은 없다.
현재 실패 Snapshot의 공개 매핑을 유지한다. `TIMEOUT`은 503 `DEPENDENCY_TIMEOUT`, `UNAVAILABLE`은 503 `DEPENDENCY_UNAVAILABLE`, `INVALID_RESPONSE`는 500 `INTERNAL_ERROR`다. 실패 응답은 원문 Provider 값이나 계좌 참조를 돌려주지 않는다.

## 점수와 Evidence

R005 기여도 40점과 `external_recipient` 그룹 상한 40점을 기존 amount 15, security 60, beneficiary 10 뒤에 적용하고 Rule 합계를 100점에서 자른다. ML 적용 시 기존 확률 기여 함수 0–40점을 Rule 점수 뒤에 더해 100점에서 자르며 정책 버전은 `rule-ml-policy-v2`다. R005 단독은 40/MEDIUM 및 모니터링 승인, R005+R004는 50/HIGH 및 검토 보류, R005 단독+ML40은 80/CRITICAL 및 차단이다. 기존 LOW 0–19, MEDIUM 20–49, HIGH 50–79, CRITICAL 80–100 경계를 유지한다.

R005 RULE Evidence의 observationSummary는 `providerCode`, `providerAsOf`, `lookedUpAt`, `freshnessSeconds`만 포함한다. 동일 채택 DetectionResult의 EXTERNAL_RISK Evidence는 `sourceVersion`, `providerCode`, `providerAsOf`, `lookedUpAt`, `recipientAccountMatched`만 포함한다. 양쪽의 Provider 코드·시각과 적중 여부를 저장 전에 대조한다. Evidence ID를 상호 참조하지 않는다. 비점수 BEHAVIOR_PATTERN Evidence에는 `sourceVersion`과 `priorApprovedRecipientTransferObserved`만 저장한다. 동일 고객·수취 계좌의 승인 이체 중 `occurredAt < T`와 `createdAt <= T`를 모두 만족하는 이력만 사용한다. `false`는 적격 이력 미관측이며 첫 송금 확정이 아니다.

기존 `GET /api/v1/transactions/{transactionId}/adopted-detection-result`의 SCN-003 결과에만 `scn003Evidence`를 추가한다. 필드는 `sourceVersion`, `providerCode`, `providerAsOf`, `lookedUpAt`, `recipientAccountMatched`, `priorApprovedRecipientTransferObserved`로 제한한다. 다른 정책의 과거 응답에는 이 필드가 없다. 원문 계좌·Provider 원문·이전 거래 ID는 응답, Evidence, 로그, ML Feature에 포함하지 않는다. 동일 거래의 활성 사건만 재사용하며 서로 다른 거래는 수취 계좌가 같아도 사건을 합치지 않는다.
