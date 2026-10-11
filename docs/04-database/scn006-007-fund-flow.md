# SCN-006·007 자금 연결 DB

V35는 `fds_mock_fund_link`를 도입한다. 불변 `link_id`와 `scenario_code + source_transaction_id + target_transaction_id + customer + account` 고유 키로 같은 선언의 중복을 막고, `source_code=LOCAL_FDS_MOCK`, `source_version=v2`, 상태·UUID·참조·시각을 CHECK로 고정한다. UPDATE/DELETE는 거부한다. 대상별·원천별 cutoff 조회 인덱스를 둔다. `fds_mock_fact`에는 `ACCOUNT_FLOW_HISTORY_COMPLETE` 유형을 추가한다.

연결 테이블은 소유권을 스스로 확정하지 않는다. 평가 시 원천/후행 거래의 종류, 고객·계좌, 승인·발생·생성 시각과 Mock 선언·이력 coverage를 재검증한다. 완료 탐지 결과는 재계산하지 않는다. 이 저장소는 후속 SCN-008의 방향성 연결에도 재사용한다.

V36은 Rule v6의 14개 스냅샷과 Rule/ML v5 식별자, 6개 점수 그룹, 자금 연결 Evidence, `SCN006`·`SCN007` 사건 코드를 허용하도록 기존 불변 DB 제약을 확장한다. 이전 버전 행은 그대로 유지한다. 대표 사건 키는 시나리오와 검증된 원천 거래 UUID의 해시이다. 활성 사건은 키별 잠금과 기존 활성 거래 연결 고유성 검사를 사용한다. 사건에는 후행 대상 외에 증명된 원천 거래와 이전 연결 거래만 연결하고 각각 감사 로그를 남긴다. 닫힌 사건을 재사용하지 않는다.
