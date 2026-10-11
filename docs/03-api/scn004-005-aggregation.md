# SCN-004·005 로컬 Mock 집계 입력 초안

기준 Issue #386. 기존 `POST /api/v1/fds-mock-facts`의 인증·응답·멱등 계약을 사용한다. 새 `factType=INBOUND_ACCOUNT_HISTORY_COMPLETE`는 신뢰된 서비스가 `externalCustomerRef`(선언된 계좌 소유자), `accountRef`(수취 계좌), UUID v4 `factId`, `occurredAt`, `coverageFrom`, `coverageThrough`, `assertionState=COMPLETE`를 제출한다. `deviceRef`, `beneficiaryRef`, `behaviorEventId`, 한도·지역 필드는 허용하지 않는다. 출처는 서버가 `LOCAL_FDS_MOCK/v1`로 고정하고 저장 시각은 DB가 소유한다.

이 사실은 수취 계좌의 관찰 이력 범위만 증명한다. 계좌 소유 관계 자체의 금융 원장 검증이나 외부 기관 응답 성공을 뜻하지 않는다. 결측·상충·cutoff 이후 생성 사실은 집계 신호를 확정하지 못한다. 거래 요청 본문에는 이 필드를 추가하지 않는다. 채택 탐지 결과의 신규 응답 필드와 화면 계약은 Rule/ML 및 Evidence 구현 뒤 확정한다.
