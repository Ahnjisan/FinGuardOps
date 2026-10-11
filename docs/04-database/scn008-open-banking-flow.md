# SCN-008 DB 경계

V37 `fds_mock_open_account`는 로컬 합성 외부 계좌에 대한 등록·소유 assertion을 불변 저장한다. 실제 금융 계좌 소유 증명으로 사용하지 않는다. 복수 assertion 또는 고객 충돌은 정상 소유로 선택하지 않고 미확인으로 처리한다. SCN-008은 V35의 방향성 있는 `fds_mock_fund_link`와 기존 `ACCOUNT_FLOW_HISTORY_COMPLETE` 사실을 함께 사용한다. 링크, 원천 거래, 소유 assertion, 이력 coverage는 원천과 대상의 시각 cutoff를 각각 통과해야 한다.

V38은 Rule v7의 15개 스냅샷, Rule/ML v6 식별자, 7개 점수 그룹, `SCN008` 사건 코드를 허용하도록 기존 CHECK를 확장한다. 이전 완료 결과와 기존 제약의 판단은 보존한다. 대표 사건 키는 검증된 원천 거래 UUID 집합과 시나리오의 해시이며 활성 사건에 한해 재사용한다. 후행 대상과 증명된 원천 거래만 연결하고 각각 감사 로그를 남긴다. 증명되지 않은 동일 계좌 거래는 연결하지 않는다.
