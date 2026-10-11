# SCN-008 외부 계좌 소유 Mock과 집중 흐름 DB

V37은 불변 `fds_mock_open_account`를 추가한다. 계좌별 등록·소유 상태, 서버 기록 `created_at`, 선언 `occurred_at`, 불변 assertion ID와 요청 fingerprint, 고정 출처 `LOCAL_OPEN_BANKING_MOCK/v1`을 보존한다. 계좌·발생 시각 고유성과 cutoff 조회 인덱스가 있으며 UPDATE/DELETE는 거부한다. 같은 외부 계좌에 복수·상충 선언이 보이면 소유는 UNKNOWN이다.

V35의 `fds_mock_fund_link`에서 `SCN008` 원천→최종 재송금 방향을 재사용한다. 평가 시 원천 `OPEN_BANKING_TRANSFER`의 외부 출금 계좌와 집중 수취 계좌, 고객, 승인·생성·발생 시각, 등록·소유 Mock 상태를 대조한다. `ACCOUNT_FLOW_HISTORY_COMPLETE`는 집중 계좌의 전체 30분 이력 구간을 포괄해야 한다. 결과 Evidence에는 원계좌 참조 대신 원천 거래·소유 선언·링크의 불변 ID만 남긴다.
