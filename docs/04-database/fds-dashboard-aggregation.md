# FDS 집계 조회 — Issue #392

읽기 전용 조회는 기존 `financial_transaction.occurred_at`, `adopted_detection_result_id`, `detection_result`, `detection_evidence`, `fraud_case.created_at`을 사용한다. 거래·Rule Evidence는 채택 완료 결과의 PK로만 연결하고 시나리오는 `(KST 일자, scenario_code, financial_transaction.id)`별 `COUNT(DISTINCT ...)`로 센다. 사건 조회는 `fraud_case`에서 단독 집계하며 `case_transaction`을 조인하지 않는다. 완료 탐지 결과, 발행 정책, 과거 migration은 수정하지 않는다.

KST 날짜 경계는 애플리케이션이 UTC instant로 변환해 필터로 전달한다. DB는 필터된 timestamp의 KST 일자만 그룹화한다. 조회 최대 31일·31버킷을 서버가 제한한다.

2026-10-12 기존 로컬 PostgreSQL 데이터(거래 604, 완료/미완료 탐지 141, Evidence 586, 사건 24건)에서 31일 범위를 `EXPLAIN (ANALYZE, BUFFERS)`로 읽기 전용 측정했다. 거래/위험등급은 27.852ms·shared hit 43, 시나리오는 2.879ms·hit 85(계획 hit 628), 신규 사건은 0.596ms·hit 4, 현재 상태는 0.583ms·hit 4였다. 해당 크기에서 순차 스캔과 해시 조인은 합리적이고 기존 `ix_financial_transaction_occurred_at` 및 Evidence FK/unique 인덱스가 있다. 추가 인덱스가 빨라진다는 근거가 없어 이번 Issue에는 migration을 넣지 않았다. 이 측정은 대규모 운영 데이터의 지연을 보장하지 않는다.
