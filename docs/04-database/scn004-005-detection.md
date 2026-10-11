# SCN-004·005 저장 및 cutoff 경계

`V33`은 승인 이체의 고객·출금계좌/수취계좌 검색 인덱스와 `fds_mock_fact`의 수취 계좌 이력 완전성 유형을 추가한다. 현재 거래 외 선행 행은 `occurred_at<T`, `created_at<=T`, `approved_at<=T`, 상태 APPROVED와 거래 유형·KRW를 모두 충족해야 한다. `approved_at`이 없는 동일 창 승인 행은 `unknownCount`에 남겨 위험 적중을 막는다. 수취 계좌 이력 증명에 상충하는 소유 Mock은 증명으로 취급하지 않는다. 현재 거래는 한 번만 포함하며 오래된 완료 결과는 갱신하지 않는다.

`V34`는 Rule+ML v4의 핀 고정 모델·feature와 점수 제약, 5개 점수 그룹 및 SCN004/005 사건 상관 코드만 확장한다. 집계 결과 자체는 채택된 탐지 Evidence의 `AGGREGATION_HISTORY_CONTEXT`에 출처·두 창·UNKNOWN 상태와 평가/창 시각으로 고정한다. 적중 Rule Evidence에는 사용한 창과 coverage Fact ID, 시간 경계, 원점수·채택점수가 있다. 기존 사건 연결 테이블의 advisory transaction lock을 재사용한다. SCN004 키는 고객+출금계좌, SCN005 키는 수취계좌를 길이 접두 SHA-256으로 만들고 각각 10분·24시간 내 활성 대표 사건만 연결한다. 같은 창의 세 시각이 검증된 승인 선행 이체만 연결하며 이미 다른 활성 사건에 속한 거래는 자동 연결하지 않는다. 선행 거래 연결은 PK 순서로 잠그고 1,000건을 넘으면 닫힌 실패로 중단한다. 종료 사건은 재사용하지 않는다.
