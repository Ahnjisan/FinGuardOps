# 로컬 사기 탐지 ML 기준선

`synthetic-fraud-v2` 생성기는 seed 380과 독립 Bernoulli 라벨 생성 과정으로 고객 cohort별 600건씩 시간 순 train·validation·test를 만든다. 고객 그룹과 거래 ID는 분할 간 겹치지 않는다. 이체는 MOBILE_BANKING, ATM 출금은 ATM 채널만 생성해 Backend 거래 계약을 따른다. 라벨은 Rule 점수, 사후 사건 판정, Qwen 리포트에서 만들지 않는다. 실제 라벨 데이터의 출처·사용 권한은 확인되지 않았다. 이전 `synthetic-fraud-v1`/`fraud-logistic-v1` 산출물은 과거 결과 확인과 고정된 v2 전환 시각 이전 cutoff의 늦은 접수 거래 추론용으로 보존한다. v1 생성기는 거래 유형과 채널의 허용되지 않는 조합을 포함했으므로 해당 결과를 실제 성능 근거로 사용하지 않는다.

`fraud-feature-v1`은 거래 금액의 log 변환, 이체 여부, 모바일 여부, cutoff 이전 24시간의 네 행동 유형별 건수 7개를 사용한다. 행동은 발생 시각과 저장 시각이 모두 cutoff 이하여야 한다. 원문 식별자, 사후 사건 결과, Rule 점수, External Risk 조회 결과는 Feature가 아니다. 건수는 3에서 cap하며 1000개를 넘는 입력은 실패시킨다. 조회에서 1001번째 행을 확인해 조용한 절단을 금지한다.

학습은 Python 표준 라이브러리의 결정적 배치 로지스틱 기준 모델이다. `python -m finguardops_ai.ml.train --version v2`가 현재 모델 JSON, SHA-256 manifest, 평가 JSON을 재생성하고 `--version v1`은 과거 아티팩트를 재생성한다. v1 합성 생성기는 허용되지 않는 거래 유형·채널 조합을 포함했으므로 운영형 평가 근거로 사용하지 않는다. FastAPI는 요청의 고정된 v1 또는 v2 아티팩트 해시와 Feature 이름·버전을 확인한다. Backend는 `2026-10-10T00:40:00Z` 이전 cutoff에 v1, 그 시각부터 v2를 선택하며 늦게 접수한 과거 거래에도 동일하게 적용한다. 새로운 모델을 사용할 때는 새 모델·Feature·정책 버전과 유효 시각을 별도 승인해야 한다.

평가 원본은 `src/finguardops_ai/ml/fraud_model_v2.evaluation.json`이다. 5000bp 합성 test 600건에서 precision 0.7330, recall 0.6034, F1 0.6619, 오탐률 0.1386, 미탐률 0.3966이었다. 3000bp에서는 precision 0.5616, recall 0.8448, 오탐률 0.4158이고 7000bp에서는 precision 0.8171, recall 0.2888, 오탐률 0.0408이다. 이는 생성기의 특정 분포를 맞춘 파이프라인 검사값이며 **실제 금융 사기 탐지 성능이 아니다**. 로컬 지연은 평가 JSON의 모델 계산만 측정하며 HTTP, DB Feature 조회, 저장을 포함하지 않는다.

`rule-ml-policy-v1`의 5000bp 기준·최대 40점 가산은 합성 데이터 로컬 검증용이다. 운영에서 사용할 오탐 허용치나 고객 제재 기준으로 해석하지 않는다. 실데이터 도입에는 데이터 사용 권한, 독립 라벨의 관측 시각, 그룹·시간 분할, calibration, 비용 및 승인된 정책이 필요하다.

## Local cross-service verification

Run the local FastAPI process at a known loopback port and set `FINGUARDOPS_LIVE_ML_URL` to that origin when running `RuleAnalysisOrchestrationIntegrationTest`. The opt-in test uses real FastAPI Rule v2 and ML endpoints and an isolated Testcontainers PostgreSQL database. Its fixed synthetic transaction ID is `00000000-0000-4000-8000-000000000382`. It checks Rule and ML evidence, score, risk response, case creation, and adopted-result query for that ID. It bypasses transaction intake, External Risk HTTP lookup, idempotency, and browser rendering; those remain separate E2E gates.
