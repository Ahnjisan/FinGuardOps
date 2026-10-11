# SCN-008 로컬 Mock 외부 계좌·방향성 자금 흐름

`POST /api/v1/fds-mock-open-accounts`는 행동 이벤트 수집 권한을 가진 SERVICE 전용이다. 요청 필드는 `assertionId`(UUID v4), `externalCustomerRef`, `externalAccountRef`, `registrationState`, `ownershipState`(각각 `VERIFIED`·`PARTIAL`·`FAILED`), `occurredAt`뿐이다. 응답은 새 불변 선언에 201, 동일 요청 재전송에 200이며 `assertionId`, 서버 소유 `sourceCode=LOCAL_OPEN_BANKING_MOCK`, `sourceVersion=v1`을 담는다. 충돌은 409, 잘못된 입력은 400이다. 실제 금융기관 계좌 소유 증명은 제공하지 않는다.

기존 `POST /api/v1/fds-mock-fund-links`의 `scenarioCode=SCN008` 선언을 각 `OPEN_BANKING_TRANSFER` 원천 거래에서 최종 재송금 거래로 하나씩 기록한다. 선언의 `externalCustomerRef`와 `accountRef`는 최종 집중 계좌의 고객·출금 계좌와 일치해야 한다. 거래 입력만으로 외부 계좌 소유나 재송금 자금 귀속을 추정하지 않는다. 중복·상충·PARTIAL·FAILED·늦은 접수는 후행 평가에서 UNKNOWN이 된다.

최종 재송금의 `occurredAt=T`에 두 개 이상의 서로 다른 등록·소유 VERIFIED 외부 계좌에서 승인된 오픈뱅킹 이체가 하나의 집중 계좌로 들어오고, 30분 집중 창·15분 재송금 창·5,000,000 KRW 이상·연결 금액 대비 80% 이상을 만족할 때만 HIGH 후보이다. 동일 집중 계좌의 무관한 거래는 명시적 링크가 없으면 합산하지 않는다. 계좌 이력의 완전성은 기존 `ACCOUNT_FLOW_HISTORY_COMPLETE` Mock fact로 확인한다. 외부 수취 계좌의 소유는 추정하지 않는다.
