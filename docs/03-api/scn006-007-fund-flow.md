# SCN-006·007 로컬 Mock 자금 연결 계약

`POST /api/v1/fds-mock-fund-links`는 기존 행동 이벤트 수집 권한을 가진 SERVICE만 호출한다. 요청은 `linkId`, `scenarioCode`(`SCN006`·`SCN007`·후속 `SCN008`), `sourceTransactionId`, `targetTransactionId`, `externalCustomerRef`, `accountRef`, `assertionState`(`VERIFIED`·`FAILED`·`PARTIAL`), `occurredAt`의 정확한 필드로 구성한다. 모든 ID는 UUID v4이다. 응답은 생성 시 201, 동일 불변 요청 재전송 시 200과 `linkId`, 서버 소유 `sourceCode=LOCAL_FDS_MOCK`, `sourceVersion=v2`이다. 같은 ID나 동일 의미 키의 상충 요청은 409이며 잘못된 요청은 400이다. 비밀·원계좌 정보는 탐지 Evidence와 화면에 노출하지 않는다.

이 입력은 실제 금융기관의 자금 귀속 증명이 아니다. 로컬 합성·Mock 환경에서만 사용하며, 조회 시 원천/후행 거래의 계좌·고객·상태·시각을 독립적으로 대조한다. `createdAt`은 서버가 기록한다. 후행 거래의 `occurredAt`을 cutoff로 삼고 선행 거래와 Mock 선언의 `occurredAt`, `createdAt`, `approvedAt`을 검증한다. `FAILED`·`PARTIAL`·상충·늦은 접수는 UNKNOWN이다.

`ACCOUNT_FLOW_HISTORY_COMPLETE` Mock fact는 기존 `/api/v1/fds-mock-facts`에 같은 계좌 소유자, 계좌, 포괄 구간을 `COMPLETE`로 선언한다. 이력이 충분하다는 로컬 출처 표시이며 실제 원장 완전성을 증명하지 않는다.
