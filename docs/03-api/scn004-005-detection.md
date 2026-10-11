# SCN-004·005 로컬 합성/Mock 탐지 계약

Rule v5 발행 시각 T5 이후 평가 거래에는 내부 `POST /api/v4/rule-analysis`를 사용한다. 기존 R001–R010의 버전과 뜻은 유지하고 R011 `DISTRIBUTED_OUTBOUND_WINDOW`, R012 `MANY_CUSTOMER_INBOUND_WINDOW`를 추가한다. 요청에는 `aggregationFacts.sourceVersion=SCN004-005-contract-v1`, `outbound`(고객·출금계좌 10분), `inbound`(수취계좌 24시간)를 포함한다. 각 창은 `transferCount`, `distinctCount`, 문자열 KRW `totalAmount`, `unknownCount`, `coverageFactId`를 갖는다. 이력 증명 없음·승인 시각 불명은 비적중이다. 입금·ATM·대출은 빈 창으로 평가한다.

Backend는 응답의 12개 Rule 기여도, 5개 점수 그룹, Evidence 원본 ID·시각·Mock 출처·합계를 요청 Snapshot으로 독립 재계산한다. 집계 그룹은 각 25점, 합계 40점 상한이다. SCN-004 단독은 3건/3수취인/300만 KRW, SCN-005 단독은 3건/3고객/500만 KRW를 충족해도 MEDIUM 후보이며, 기존 신호로도 HIGH가 아닌 경우 SCN-004는 R004 또는 R005, SCN-005는 R005가 없으면 Rule+ML 최종 49점 상한이다. 이는 정상 다건 정산·집금 반례에 대한 로컬 정책이다.

`POST /api/v1/ml-inference`의 새 조합은 `rule-ml-policy-v4`/`fraud-feature-v3`/`fraud-logistic-v4` 및 SHA-256 고정 모델이다. 12개 기존 feature에 완전성 증명된 집계의 이진 feature 두 개를 더한다. 손실·지연·부분 Mock 응답은 UNKNOWN이며 위험 적중으로 변환하지 않는다. 모델 가중치는 합성 로컬 정책이며 실제 금융 성능을 뜻하지 않는다.

기존 `GET /api/v1/transactions/{transactionId}/adopted-detection-result`의 v5 결과는 기존 `scn001002Evidence`와 `scoreGroupSummary[5]`, `mlRawContribution` 및 `aggregationEvidence`를 준다. `aggregationEvidence`는 출처, 평가 cutoff, 각 창의 시작 시각과 AVAILABLE/UNKNOWN, 건수·고유 관계·합계·승인 시각 불명 건수·Mock 증명 ID를 포함하며 원문 계좌/고객 식별자는 노출하지 않는다. 기존 v4 결과의 형식은 유지한다. HIGH는 기존 Mock 추가 인증, CRITICAL은 후속 거래 Mock 보류 및 사건 처리 경로를 사용한다. 과거 완료 결과는 재평가하지 않는다.
