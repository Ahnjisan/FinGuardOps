# SCN-004·005 시간창 집계 물리 계약

기준 Issue #386. 로컬 합성·Mock 검증용이다. `V33`은 승인 이체의 출금 고객·계좌/발생 시각과 수취 계좌/발생 시각 조회 인덱스를 추가하고, `INBOUND_ACCOUNT_HISTORY_COMPLETE` Mock 사실 타입을 허용한다. 기존 승인 행의 `approved_at`은 채우지 않는다.

평가 시각 `T=현재 거래 occurred_at`이다. 선행 이체는 같은 계좌 관계, `occurred_at<T`, `created_at≤T`, 현재 상태 `APPROVED`, `approved_at≤T`를 동시에 만족해야 한다. 004는 `(external_customer_ref,sender_account_ref)`의 `[T−10분,T)`, 005는 `recipient_account_ref`의 `[T−24시간,T)`이다. 현재 평가 요청은 승인 상태와 무관하게 정확히 한 건만 더한다. `transaction_id`로 과거 목록의 현재 거래 중복을 배제한다.

`approved_at IS NULL`인 과거 승인 행이 창에 있으면 `unknown_count>0`이고 탐지 확정에 쓰지 않는다. 현재 완료 상태가 승인인 것을 과거 승인으로 역추정하지 않는다. 창의 부재를 확정하려면 004는 고객별 `TRANSFER_HISTORY_COMPLETE`, 005는 수취 계좌별 `INBOUND_ACCOUNT_HISTORY_COMPLETE`의 `[coverage_from,coverage_through]`가 전체 창을 덮고 `occurred_at≤T`, `created_at≤T`여야 한다. 005 Mock의 `external_customer_ref`는 출처가 선언한 계좌 소유자 참조이며 집계 자체는 소유 관계를 추론하지 않는다. 사실 ID는 근거에 기록하되 원문 고객·계좌 참조는 외부 근거에 내보내지 않는다.

이 인덱스와 Mock 타입만으로 탐지·사건 생성이 활성화되지는 않는다. Rule v5 발행 시각 이후의 채택 경로와 Evidence·사건 정책은 [탐지 DB 계약](scn004-005-detection.md)을 따른다.
