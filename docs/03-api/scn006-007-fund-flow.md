# SCN-006·007 로컬 Mock 자금 연결 계약

`POST /api/v1/fds-mock-fund-links`는 기존 행동 이벤트 수집 권한을 가진 SERVICE만 호출한다. 요청은 `linkId`, `scenarioCode`(`SCN006`·`SCN007`·후속 `SCN008`), `sourceTransactionId`, `targetTransactionId`, `externalCustomerRef`, `accountRef`, `assertionState`(`VERIFIED`·`FAILED`·`PARTIAL`), `occurredAt`의 정확한 필드로 구성한다. 모든 ID는 UUID v4이다. 응답은 생성 시 201, 동일 불변 요청 재전송 시 200과 `linkId`, 서버 소유 `sourceCode=LOCAL_FDS_MOCK`, `sourceVersion=v2`이다. 같은 ID나 동일 의미 키의 상충 요청은 409이며 잘못된 요청은 400이다. 비밀·원계좌 정보는 탐지 Evidence와 화면에 노출하지 않는다.

이 입력은 실제 금융기관의 자금 귀속 증명이 아니다. 로컬 합성·Mock 환경에서만 사용하며, 조회 시 원천/후행 거래의 계좌·고객·상태·시각을 독립적으로 대조한다. `createdAt`은 서버가 기록한다. 후행 거래의 `occurredAt`을 cutoff로 삼고 선행 거래와 Mock 선언의 `occurredAt`, `createdAt`, `approvedAt`을 검증한다. `FAILED`·`PARTIAL`·상충·늦은 접수는 UNKNOWN이다.

`ACCOUNT_FLOW_HISTORY_COMPLETE` Mock fact는 기존 `/api/v1/fds-mock-facts`에 같은 계좌 소유자, 계좌, 포괄 구간을 `COMPLETE`로 선언한다. 이력이 충분하다는 로컬 출처 표시이며 실제 원장 완전성을 증명하지 않는다.

## 탐지 응답과 버전

미래 cutoff에 게시한 14개 Rule 집합은 `POST /api/v5/rule-analysis`를 사용한다. 기존 v4 요청의 `aggregationFacts`에 `fundFlowFacts`를 추가한다. `sourceVersion=SCN006-007-contract-v1`이고 `atm`·`loan` 각각에 `status`, `reasonCode`, 원천 거래/현재 연결/coverage/신규 수취인 ID, 원천 발생·승인 시각, 원천 금액, 연결 누계, 비율(bp), 이전 연결 거래 ID 목록을 담는다. UNKNOWN은 금액·비율 0이며 귀속 ID가 없다. AI 응답은 R013 `VERIFIED_INBOUND_ATM_DRAIN`, R014 `VERIFIED_LOAN_NEW_RECIPIENT_DRAIN`의 증거를 포함한다. Backend가 동일 cutoff 사실로 점수·Evidence를 독립 재계산한 뒤에만 채택한다.

ML `POST /api/v1/ml-inference`의 새 조합은 `rule-ml-policy-v5` / `fraud-feature-v4` / `fraud-logistic-v5`와 고정 SHA-256 및 같은 `fundFlowFacts`를 요구한다. 조회 `GET /api/v1/transactions/{id}/adopted-detection-result`는 이 버전에서 `fundFlowEvidence`를 추가한다. 여기에는 Mock 출처·계약 버전·cutoff와 두 경로의 AVAILABLE/UNKNOWN, 이유, 원천/연결/coverage ID, 금액·시각·비율이 들어간다. 원계좌·실제 소유 추정은 포함하지 않는다.

SCN-006은 승인 입금 1,000만 원 이상, 30분 내 동일 계좌 ATM 인출 연결 누계 80% 이상, SCN-007은 승인 대출 Mock 실행 뒤 60분 내 등록된 신규 수취인 송금 연결 누계 80% 이상에서 HIGH 후보이다. 두 신호 단독으로 CRITICAL이 되지 않으며 별도 검증 위험이 있어야 승격할 수 있다. HIGH는 기존 Mock 추가 인증, CRITICAL은 기존 보류와 사건 정책을 따른다. 실제 ATM 제어·대출 취소를 제공하지 않는다.
