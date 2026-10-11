# SCN-001·002 로컬 합성/Mock 탐지 API 계약 v1

기준 Issue #384, `SCN001-002-contract-v1`. 이 정책의 점수와 ML 지표는 실제 금융기관 운영 기준이 아니다. 거래 요청자는 기기 신뢰도, 이력 완전성, 한도 상향, 본인확인, 앱/지역 위험을 선언할 수 없다.

## 별도 Mock 입력

`POST /api/v1/fds-mock-facts`는 `BEHAVIOR_INGESTOR` 서비스 권한의 `behavior-event:intake`가 필요하다. Browser CORS 허용 대상은 아니다. 요청 본문은 `factId`(UUID v4), `factType`, `externalCustomerRef`, 대상 `accountRef`/`deviceRef`, 연결 `behaviorEventId`, `occurredAt`, 이력 `coverageFrom`/`coverageThrough`, `assertionState`, 한도 `limitScope`/`previousLimit`/`newLimit`, 지역 `countryCode` 중 타입별 필수 필드만 받는다. `sourceCode=LOCAL_FDS_MOCK`, `sourceVersion=v1`, `createdAt`은 서버/DB 소유다. 응답은 `factId`, `sourceCode`, `sourceVersion`; 신규 201, 동일 ID·동일 본문 재전송 200, 충돌 409, 잘못된 대상·시각 400이다. 링크된 행동 이벤트의 고객·계좌·기기·발생 시각을 서버가 확인한다. `TRANSFER_HISTORY_COMPLETE`/`DEVICE_HISTORY_COMPLETE`는 `[coverageFrom,coverageThrough]`가 T를 포함해야 하며 최초 수취인·신규 기기의 전 생애 판단에는 `coverageFrom≤1970-01-01T00:00:00Z`를 요구한다. 중복/상충 Mock은 확정 신호가 아니다.

유효 타입: `TRANSFER_HISTORY_COMPLETE`, `DEVICE_HISTORY_COMPLETE`, `DEVICE_TRUST`, `LIMIT_CHANGE`, `IDENTITY_CHECK`, `APP_SECURITY`, `NETWORK_REGION`. `APP_SECURITY`/`NETWORK_REGION`은 해당 고객·기기의 선행 LOGIN/DEVICE_REGISTERED 관측을 요구한다. `DOMESTIC`은 `KR`, `OVERSEAS`는 `KR` 이외 국가 코드만 허용한다. 원문 비밀번호/IP는 필드가 없으며 계좌·기기 원문은 새 `scenarioFacts`, 모델 Feature, Evidence, Audit, 조회 응답으로 전달하지 않는다. 기존 내부 Rule 거래 wire의 참조 필드는 계속 사용한다. 기존 거래 요청/멱등 계약은 유지한다.

## 내부 Rule·ML wire

`POST /api/v3/rule-analysis`는 Backend 전용이다. `v3`는 HTTP wire 버전이며 Rule `scoring-policy-v4`와 별개다. 거래 T와 정확한 R001v3,R002v4,R003v4,R004v4,R005v1,R006–R010v1 Snapshot, 검증된 External Risk Snapshot, `scenarioFacts`(`SCN001-002-contract-v1`)를 받는다. Rule 응답은 각 Rule `originalContribution`/`appliedContribution`, 그룹 `rawScore`/`cap`/`appliedScore`/`reduction`, 채택 Rule 합계와 매칭 Evidence 원점수/채택점수를 반환한다. Backend가 동일 합계를 독립 재계산하고 Evidence 관측 필드를 재구성해 불일치 시 채택하지 않는다. 매칭 Rule Evidence에는 관련 Mock fact UUID와 `LOCAL_FDS_MOCK/v1` 출처를 한정해서 남기며 원문 기기·계좌와 Provider 전문은 남기지 않는다.
Evidence의 KRW 합계·한도 관측 값은 자리수 손실이나 JSON 숫자 스케일 차이를 막도록 정수 십진 문자열로 정규화한다. Backend는 비정수 값과 재구성 결과가 다른 응답을 거부한다.
T4 이후 ATM·대출도 발행된 v4 내부 wire로 평가한다. `recipientAccountRef=null`을 허용하고 SCN-001/002 R001–R010은 모두 비적중 0점으로 반환한다. 수취 계좌 없는 거래에 신규 24시간 신선도 제약을 적용하지 않으며 기존 Provider 실패는 계속 fail-closed다. v1/v2 wire와 과거 완료 결과는 바꾸지 않는다.

ML `/api/v1/ml-inference`의 `rule-ml-policy-v3` 요청은 핀된 `fraud-feature-v2`/`fraud-logistic-v3` 및 동일 `scenarioFacts`만 사용한다. 모델 원기여는 기존 5,000bp 초과 최대 40점 함수이며 최종 채택 기여는 `min(원기여,100−Rule 합계)`다. 본인확인 사후 결과, 사건 판정, cutoff 이후 행동·Mock은 모델 Feature가 아니다.

## 채택 결과 조회

기존 `GET /api/v1/transactions/{transactionId}/adopted-detection-result`의 인증·조회 권한과 외형을 유지한다. v4 채택 결과에만 `scoreGroupSummary[4]`, `mlRawContribution`, `scn001002Evidence`를 추가한다. 각 `ruleEvidence`에는 원점수 `scoreContribution`과 `appliedContribution`을 함께 보인다. `ruleScore`는 Rule 채택 합계, `mlContribution`은 ML 채택 기여다. `scn001002Evidence`는 기준선 상태/건수/일수, 최초 수취인·기기 상태와 Provider 코드/시각/정확 기기 match 여부만 포함한다. 원문 식별자, Provider 전문, Mock 결과 원문, 사건 판정은 노출하지 않는다. 구버전 결과의 응답 필드는 그대로 해석한다.

## 탐지 이후 별도 추가 인증

`POST /api/v1/transactions/{transactionId}/mock-auth`는 `BEHAVIOR_INGESTOR` 서비스 권한만 허용하며 Browser CORS 대상이 아니다. `{ "decisionId": "UUIDv4", "result": "SUCCESS|FAILURE|EXPIRE" }`를 보낸다. v4 HIGH의 `ADDITIONAL_AUTH_REQUIRED` 거래와 활성 사건 한 건에 한해서 최초 완료 시각부터 10분 이내 `SUCCESS→APPROVED`, `FAILURE→HELD`로 전이한다. 기한 이후에는 요청 종류와 무관하게 `EXPIRED→HELD`이며 `EXPIRE` 명령은 기한 이전이면 409다. 동일 decisionId·동일 본문 재전송은 저장된 응답을 반환하고, 다른 명령·종료 거래·종료 사건·CRITICAL HELD는 409다. 서버가 출처 `LOCAL_AUTH_MOCK/v1`, 결정 시각, 불변 결과와 Audit의 별도 `MOCK_AUTH_DECIDED` 상태 전이를 남긴다. 최초 `riskResponseOutcome=ADDITIONAL_AUTH_REQUIRED`, 채택 DetectionResult와 완료 멱등 Snapshot은 변경하지 않는다. 이 명령은 실제 은행 인증이나 거래 실행이 아니다.

## 장애

External Risk timeout/unavailable/invalid의 fail-closed 계약과 #382 R005 정책은 그대로다. Rule/ML 실패 시 Backend는 점수를 만들어 대체하지 않는다. 완료 멱등 재전송은 Provider/Rule/ML을 재호출하거나 저장 결과를 갱신하지 않는다.
