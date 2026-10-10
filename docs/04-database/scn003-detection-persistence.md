# SCN-003 버전·저장 계약

Flyway `V23__add_scn003_policy_and_history_index.sql`은 `rule-ml-policy-v2`에 기존 ML 결합 점수식과 필수 Feature·모델 해시 CHECK를 적용하고, 동일 고객·수취 계좌의 과거 승인 이체 조회용 부분 인덱스를 추가한다. 기존 migration 파일과 완료 DetectionResult, Evidence, 멱등 Snapshot은 수정하지 않는다.

미래 cutoff 발행은 `rule-v3-local-publication` one-shot 경계에서 명시적 확인과 미래 `effectiveFrom`을 요구한다. 한 DB 트랜잭션에서 R001–R004 v2의 기간을 닫고 각 v3와 신규 R005 v1을 발행한다. 이전 시각은 기존 v1/v2 집합을 선택한다. rollback은 과거 결과 재계산이 아니라 별도의 미래 시각에 새 불변 집합을 발행하는 작업이며 기존 R005 v1을 소급 삭제하지 않는다.

R005 적중 시 RULE Evidence와 성공 EXTERNAL_RISK Evidence를 동일한 채택 DetectionResult ID에 저장하며 Provider 코드·시각을 대조한다. 성공한 비적중 결과에도 제한된 EXTERNAL_RISK Evidence를 저장한다. BEHAVIOR_PATTERN Evidence는 점수에 참여하지 않으며 `priorApprovedRecipientTransferObserved=false`가 첫 송금 확정을 뜻하지 않는다. 거래·탐지·Evidence·사건·Audit의 기존 FK와 불변 trigger를 유지한다. 사건은 동일 거래의 활성 사건 재사용 범위에서만 연결한다.
