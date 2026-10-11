# SCN001-002-contract-v1 — 로컬 합성·Mock 구현 계약

상태: Issue #384에서 승인(2026-10-11). 이 문서의 수치는 로컬 합성·Mock 검증용이며 실제 금융기관 운영 임계값이나 사기 탐지 성능이 아니다. 발행 전에는 기존 v1/v2/v3 Rule·ML과 완료 멱등 Snapshot을 변경하지 않는다.

## 시간 및 출처

- 거래 평가 시각 `T=financial_transaction.occurred_at` (UTC 마이크로초). 행동·Mock·과거 거래는 `occurredAt≤T` 및 `createdAt≤T`를 동시에 만족해야 한다. 과거 승인 거래는 불변 승인 전이의 `approvedAt≤T`까지 증명한다. 현재 상태가 APPROVED인 것만으로 과거 승인으로 간주하지 않는다. 입증할 수 없는 과거 행은 `UNKNOWN`으로 두며 소급 보정하지 않는다.
- V32부터 직접 INSERT에서 `approvedAt` 지정은 거부하고 Mock `createdAt`은 DB 수신 시각으로 강제한다. 행동 이벤트는 인증된 intake가 `createdAt`을 소유하며 UPDATE/DELETE를 금지한다. DB 관리자 권한의 직접 삽입은 서비스 신뢰 경계 밖이며 운영 데이터 적재 권한을 별도로 통제해야 한다.
- 동일 고객·계좌·기기 등 참조의 소유권은 Backend의 인증된 원장 또는 별도 권한을 가진 로컬 Mock에서 검증한다. 거래 요청자가 신규·신뢰·완전성·본인확인·앱·해외 위험을 선언할 수 없다. Mock은 출처 코드, 버전, 대상, 발생·저장 시각, 불변 ID를 저장한다. 충돌은 거부하고 결측은 `UNKNOWN`이다. 원문 비밀번호, IP, 기기·계좌 식별자와 Provider 전문은 모델 Feature, Evidence, Audit, 로그, 조회 화면에 내보내지 않는다.
- 앱·지역 Mock은 같은 고객·기기의 선행 LOGIN/DEVICE_REGISTERED와 연결된 소유권을 검사한다. 지역 `DOMESTIC`은 `KR`, `OVERSEAS`는 `KR` 이외 국가 코드만 허용한다. Fact ID는 동시 동일 본문 재전송에도 한 행으로 수렴하며, 다른 본문 또는 다른 ID의 같은 의미 입력은 충돌로 처리한다. 채택 Rule Evidence에는 원문 대상 대신 사용된 Fact ID·고정 출처 `LOCAL_FDS_MOCK/v1`을 남긴다.
- 이력 완전성 Mock의 구간은 `[coverageFrom, coverageThrough]`이며 둘 다 소유권이 확인된 출처가 증명한다. `coverageThrough≥T`와 Mock `occurredAt≤T`, `createdAt≤T`를 함께 요구한다. 미래 구간을 무제한 예고할 수 없도록 등록 시각으로부터 5분 이내만 허용한다. 기존 DB에 완전성 증명 없이 남은 이력은 `UNKNOWN`이다.
- 시간순 선택은 `(occurredAt DESC, eventId ASC)` 또는 `(occurredAt DESC, transactionId ASC)`로 고정한다. 같은 의미의 이벤트는 소유권·타입·대상·시각·결과가 일치할 때만 하나로 처리한다. 한도·인증 결과의 모순 및 동일 시각의 순서 미확정은 `UNKNOWN` 또는 계약 오류다.

## SCN-001

- 고객 기준선: 같은 고객의 모든 출금 계좌에서 발생한 KRW 이체 중 `[T−90일,T)`이고 `createdAt≤T`, 불변 승인 시각 `approvedAt≤T`인 거래만 사용한다. 10건 이상, 거래일 3일 이상일 때 산술평균을 산출한다. 일부 이력의 승인 시각 또는 이력 완전성이 증명되지 않으면 기준선은 `UNKNOWN`; 충분하지 않으면 `INSUFFICIENT`다. R006은 현재 금액 ≥1,000,000 KRW, 평균의 ≥10배를 요구하며 경계값은 포함한다.
- 최초 수취인: 같은 고객의 모든 출금 계좌와 수취 계좌를 비교한다. 현재 계약에는 고객 가입 시각 원장이 없으므로 Mock의 `[1970-01-01T00:00:00Z,T]` 전 생애 이력 완전성 증명과 위 시간·승인 조건의 선행 이체 부재가 함께 있을 때만 `FIRST`다. `BENEFICIARY_REGISTERED`는 별개 관측값이며 최초 이체의 증명이 아니다. 완전성 미입증 또는 과거 승인 시각 미확인은 `UNKNOWN`이다.
- 신규 기기: Mock이 전 생애 기기 사용 이력 완전성을 `[1970-01-01T00:00:00Z,T]`로 증명한 범위에서 현재 기기의 첫 성공 LOGIN/DEVICE_REGISTERED가 T 이전 24시간 이내이며 권한 있는 기기 Mock이 `UNVERIFIED`로 증명할 때만 신규·미검증이다. 새로 등록했더라도 `VERIFIED`면 R002 비적중이다. R007은 현재 거래의 정확한 deviceRef에 대해 검증된 Provider의 DEVICE/RISK_DEVICE match만 사용하고 기대 providerCode, `T−24시간≤providerAsOf≤T≤lookedUpAt`을 검증한다. #382의 수취 계좌 R005 조건은 그대로 둔다.
- R004는 이력 완전성이 확인된 최초 수취인과 최근 24시간의 같은 고객·수취인 `BENEFICIARY_REGISTERED`가 모두 있을 때 적중한다. R008은 신뢰된 현재 기기의 앱 보안 Mock alert, R010은 15분 이내의 신뢰된 해외 지역 Mock 신호에서만 적중한다. 실제 IP 위험 조회는 포함하지 않는다.

## SCN-002

- 한도 이벤트에는 성공 결과, `PER_TRANSFER` 또는 `DAILY`, 변경 전·후의 양의 KRW 한도, 대상 고객·출금 계좌와 출처가 필요하다. `new>old`만 상향이다. 동일 값·하향·단순 이벤트 존재는 비적중이다. DAILY 사용액은 T에 승인된 거래만 합산한다. 승인 시각 불명은 `UNKNOWN`이다.
- R003은 성공 비밀번호 변경 `<` 성공 한도 상향 `<T`를 엄격하게 요구한다. 비밀번호→상향 ≤30분, 상향→송금 ≤30분, 비밀번호→송금 ≤60분이며 경계값은 포함한다. 고액 근거 R001 또는 R006 적중, 혹은 기존 이체 한도 초과가 필요하다. R009는 R003 적중에 더하여 `new≥2×old` 및 `new−old≥5,000,000 KRW`를 요구한다. 본인확인 Mock 결과는 최초 탐지의 시간 고정된 근거로만 사용하며 결측은 `UNKNOWN`, 성공을 임의 추정하지 않는다.

## 버전·점수·Evidence

- 미래 UTC 마이크로초 `T4`부터 정확한 Rule 집합만 허용한다: R001 v3(15, AMOUNT), R002 v4(20, SECURITY), R003 v4(50, SECURITY), R004 v4(10, BENEFICIARY), R005 v1(40, EXTERNAL_RECIPIENT), R006 v1(20, AMOUNT), R007 v1(20, SECURITY), R008 v1(15, SECURITY), R009 v1(10, SECURITY), R010 v1(10, SECURITY). 누락·중복·예상 외 코드/버전·유효기간 겹침은 거부한다. `scoring-policy-v4`의 그룹 상한은 AMOUNT 20, SECURITY 60, BENEFICIARY 10, EXTERNAL_RECIPIENT 40, 최종 100이다.
- R001v3의 고정 정의는 KRW 이체 `amountThreshold=10000000`, R005v1의 고정 정의는 수취 계좌 위험 `freshnessSeconds=86400`이다. 발행, Backend Snapshot 검증, AI 단독 요청에서 다른 정의를 거부한다.
- v4 발행 집합은 모든 접수 거래에서 선택되므로 내부 v3 Rule wire는 ATM·대출도 받는다. SCN-001/002 Rule R001–R010은 이 두 거래 유형에서 전부 비적중 0점으로 평가하고 수취 계좌 없는 거래의 기존 Provider 검증·fail-closed 경계를 유지한다. 이 거래 유형은 SCN-001/002 전용 Mock·행동·과거 거래 조회와 건수 상한을 적용하지 않고 모든 시나리오 사실을 `UNKNOWN`으로 둔다. v3 ML은 금액·채널 기본 Feature만 사용하고 SCN-001/002의 이력·기기·한도·보안 Feature는 0으로 둔다. R001/R006을 금액만으로 ATM·대출에 적용하지 않으며, 이 거래 유형에 SCN-003/기기 전용 24시간 신선도를 새로 강제하지 않는다. 이는 발행 후 비이체 거래가 v4 wire 거부 또는 무관한 시나리오 자료량으로 FAILED가 되지 않도록 한 계약 보정이다.
- 그룹 및 Rule 최종 상한 배분은 R001→R010 코드순이다. Evidence별 원점수와 채택 기여점수 `min(raw, 남은 그룹 상한, 남은 Rule 최종 100점)`을 별도로 기록한다. 그룹 채택점수 합계는 Rule 합계와 같다. ML 기여도 역시 최종 100점의 남은 자리만 채택 Evidence 점수로 표시한다. 그룹의 원점수·상한·채택점수, Rule 합계, ML 원기여·채택기여, 최종 점수는 AI 응답, Backend 계약 검증, DB, 조회 화면에서 같다. `rule-ml-policy-v3`는 기존 ML 기여 함수(확률 5,000bp 이하 0, 그 위 최대 40점)를 유지한다. LOW 0–19, MEDIUM 20–49, HIGH 50–79, CRITICAL 80–100이다.
- `fraud-feature-v2`와 `fraud-logistic-v3`는 동일 cutoff 증명 자료만 사용한다. 시간·고객 분리 합성 평가와 아티팩트 SHA를 고정한다. 사후 인증, 사건 판정, 미래 발생·저장 자료를 Feature에 포함하지 않는다. 기존 모델·결과와 완료 멱등 Snapshot은 재계산하지 않는다. 신규 내부 wire 버전과 공개 API는 각각 필요성에 따라 구분한다.
- R00620+R00220=40/MEDIUM; 첫 수취인 R00410이 더해지면 50/HIGH. R00350+R00620=70/HIGH; R00410이 더해지면 80/CRITICAL. R005 단독 40/MEDIUM과 R005+ML40=80/CRITICAL은 #382 계약대로 유지한다.

## 대응·사건·장애

- LOW는 APPROVED, MEDIUM은 APPROVED_WITH_MONITORING, HIGH는 ADDITIONAL_AUTH_REQUIRED와 사건, CRITICAL은 HELD와 사건이다. HIGH의 별도 권한 있는 Mock 인증 명령은 성공 시 APPROVED, 실패·만료 시 HELD로 감사 가능한 불변 전이를 남긴다. 최초 점수·Evidence·채택 결과와 완료 멱등 Snapshot은 수정하지 않는다. CRITICAL HELD는 인증 성공만으로 해제하지 않는다. 중복·역순·동시 명령, terminal 거래·사건 충돌을 거부한다.
- 서로 다른 거래의 사건 연결은 동일 고객·출금 계좌·시나리오·근거 이벤트, 30분 이내, 활성 사건을 모두 확인하고 대표 사건을 결정적으로 선택하며 잠금·고유성으로 동시 생성을 막는다. SCN-001은 같은 기기·수취 계좌, SCN-002는 같은 비밀번호·상향 이벤트 쌍을 추가 요구한다. R005 적중 거래는 #382처럼 다른 거래 사건에 합치지 않는다. 두 시나리오 동시 적중 시 SCN-002를 대표로 하고 모호한 근거는 별도 사건으로 둔다. 닫힌 사건은 재사용하지 않는다.
- Provider timeout/unavailable/invalid는 기존 fail-closed를 유지한다. Rule·ML 장애 시 백엔드는 임의 점수를 만들지 않는다. 같은 멱등 키의 완료 재전송은 외부·Rule·ML을 재호출하거나 기존 결과를 덮어쓰지 않는다.

## 검증·발행

신규 Flyway migration만 추가한다. 새 버전은 명시적 로컬 발행 명령으로 DB 현재 시각보다 미래 T4에 원자적으로 발행하고 이전 버전 유효기간을 T4에서 닫는다. 발행 이후 rollback은 코드와 미래 발행을 되돌릴 수 있어도 이미 완료된 탐지·거래·사건·Audit를 역산하지 않는다. 단위, PostgreSQL 통합, 거래 E2E, 공식 Keycloak Browser Gate에서 동일 거래·탐지·사건 ID와 점수·상태·Evidence·Audit·권한 및 정상/부정/중복/동시/시간 경계를 대조한다.
