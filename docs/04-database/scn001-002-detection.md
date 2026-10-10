# SCN-001·002 DB 변경 v1

기존 Flyway 파일은 변경하지 않는다. V24는 `financial_transaction.approved_at`과 DB 소유 불변 승인 전이 trigger를 추가한다. 과거 APPROVED 행은 NULL로 두어 이력 집계에서 `UNKNOWN`으로 처리한다. V25는 `fds_mock_fact`, `detection_evidence.applied_contribution`, `detection_result.score_group_summary`를 추가한다. V26은 별도 `fds_mock_auth_decision` 결과와 Audit reason을 추가한다. V27은 원문 식별자를 담지 않는 SHA-256 상관 키의 `scenario_case_correlation`을 추가한다. V28–V30은 v4 ML 채택 기여 cap, 완료 점수·모델 핀의 NOT NULL 및 그룹 요약 제약을 강화한다. V31은 Mock fact·인증 결정·사건 상관 행의 UPDATE/DELETE를 DB에서 금지한다. V32는 명시적 `approved_at` INSERT를 거부하고 Mock `created_at`을 DB 수신 시각으로 강제하며 행동 Evidence의 UPDATE/DELETE를 금지한다. 각 migration은 별도 순서로 적용하고 이전 완료 행을 다시 계산하지 않는다.

v4 점수 저장은 동일 `detection_result_id`에 매칭 RULE Evidence 원점수/채택점수, External Risk 성공 Snapshot의 제한된 필드, 이력 상태 Evidence, ML 원기여/채택기여를 묶는다. `score_group_summary`는 네 그룹의 원점수·상한·채택점수·감점을 기록한다. DB의 `ml_contribution`은 v4에서는 채택 기여, 이전 v1/v2에서는 기존 원기여와 같다. 완료 결과와 Evidence의 불변 trigger는 유지한다.

이력 SQL은 같은 고객의 `occurred_at<T`, `created_at≤T`, `approved_at≤T`, 현재 APPROVED인 KRW 이체만 집계한다. 승인 시각 NULL 행은 알 수 없는 이력으로 세며 신규 고객 기준선, 최초 수취인, DAILY 사용액의 확정 계산을 막는다. 수취인·고객·출금계좌 및 행동/Mock의 시간창은 `docs/01-requirements/scn001-002-contract-v1.md`가 기준이다.

사건 상관 키는 SCN-002의 고객·출금계좌·비밀번호/한도 이벤트 또는 SCN-001의 고객·출금계좌·현재 기기·수취인·첫 기기 이벤트를 길이 접두 SHA-256으로 만든다. R005 적중 거래는 다른 거래 사건에 합치지 않는다. 동일 키의 advisory transaction lock 후 발생 시각 기준 30분 내 활성 사건을 정렬해 고르며 종료 사건은 재사용하지 않는다. 같은 거래의 기존 활성 사건 조회는 이 상관 절차보다 앞선다.
