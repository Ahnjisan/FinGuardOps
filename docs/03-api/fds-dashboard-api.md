# FDS 기간별 집계 API — Issue #392

`GET /api/v1/fds-dashboard/transactions`와 `GET /api/v1/fds-dashboard/cases`는 읽기 전용이다.
인증된 USER의 `transaction:read`, `detection:read`, `case:read`가 모두 필요하다.
`FDS_VIEWER`, `FDS_ANALYST`, `FDS_APPROVER`는 접근하고 운영자 단독·SERVICE는 403이다.
미인증은 401이다. 응답에 고객·계좌 reference, Evidence 원문, 내부 PK를 넣지 않는다.

## 기간·필터

- 유일한 필터는 `from`, `to` 두 날짜(`YYYY-MM-DD`)다. 둘 다 생략하면 현재 KST 날짜를 포함한 최근 7일이다. 하나만 제공하거나, 알 수 없는·중복 파라미터는 400 `VALIDATION_ERROR`다.
- `from` 포함, `to` 미포함인 KST 날짜 구간이다. 두 날짜의 차이는 1~31일이어야 하며 그 밖은 400이다. 두 날짜의 KST 자정을 UTC instant로 바꾸어 DB에 `[start,end)` 조건을 적용한다. 1~31개의 일별 버킷을 반환하고 빈 날짜는 0이다.
- 두 API는 독립 조회다. 한쪽 실패가 다른 쪽의 성공을 숨기지 않는다. 각 응답의 `asOf`는 조회를 시작한 서버 시각이며 거래·사건 API 간 동일 스냅샷을 뜻하지 않는다. 각 API 내부 집계는 반복 읽기 트랜잭션으로 일관되게 조회한다.

## 거래 응답

```json
{"from":"2026-10-11","to":"2026-10-12","timeZone":"Asia/Seoul","asOf":"2026-10-12T00:00:00Z","days":[{"date":"2026-10-11","total":2,"unclassified":1,"classified":1,"riskLevels":{"LOW":0,"MEDIUM":0,"HIGH":1,"CRITICAL":0},"scenarios":{"SCN001":1,"SCN002":0,"SCN003":0,"SCN004":0,"SCN005":0,"SCN006":0,"SCN007":0,"SCN008":0}}],"traceId":"trace_demo_dashboard_01"}
```

- `financial_transaction.occurred_at`의 KST 일자에 모든 상태의 고유 거래를 한 번 계수한다. 멱등 replay는 새 행이 아니므로 증가하지 않는다.
- 채택된 `COMPLETED` `detection_result`의 저장 위험등급만 분류한다. 나머지는 `UNCLASSIFIED`. 모든 일자에서 `total = unclassified + classified = unclassified + 4개 riskLevels 합`이다.
- 위험등급 비율의 분모는 같은 버킷·선택 기간의 `classified`이다. 분모 0일 때 비율은 표시하지 않는다. 위험등급은 사기 확률이 아니다.
- 시나리오는 채택 결과의 `RULE` Evidence만 계수한다. 한 일자·시나리오의 동일 거래는 한 번만 센다. 한 거래가 복수 시나리오에 적중할 수 있어 합은 `classified`를 넘을 수 있다. 시나리오별 비율 분모도 `classified`다. 저장된 과거 정책 버전을 현 정책으로 재평가하지 않는다.

### 확정된 시나리오 근거 매핑

아래 버전은 `detection_result.scoring_policy_version`과 `detection_evidence.rule_version`이다. `rule_code`는 문자열 전부를 정확히 비교한다. 정책별 Backend 재계산·채택 시 고정된 Evidence의 관측 필드도 조회에서 확인한다. 공통 고액 Rule·ML·비점수 근거만으로 특정 시나리오를 확정하지 않는다.

| 시나리오 | 정책 버전 | Rule 코드 / Rule 버전 | 저장된 추가 증명 |
| --- | --- | --- | --- |
| SCN001 | v3~v6 | `RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT` / 4 | `SCN001-002-contract-v1`, 기기 이벤트·이력·신뢰 fact ID |
| SCN002 | v3~v6 | `RECENT_SECURITY_CHANGE_HIGH_AMOUNT` / 4 | 같은 출처, 비밀번호·한도 이벤트와 한도 fact ID |
| SCN003 | `scoring-policy-v3` (Rule only), `rule-ml-policy-v2`~`v6` | `EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT` / 1 | Provider 시각과 같은 결과의 `EXTERNAL_RISK` 수취 계좌 match=true |
| SCN004 | v4~v6 | `DISTRIBUTED_OUTBOUND_WINDOW` / 1 | `SCN004-005-contract-v1`, coverage fact ID |
| SCN005 | v4~v6 | `MANY_CUSTOMER_INBOUND_WINDOW` / 1 | 같은 출처, coverage fact ID |
| SCN006 | v5~v6 | `VERIFIED_INBOUND_ATM_DRAIN` / 1 | `SCN006-007-contract-v1`, 원천 거래·링크·coverage ID |
| SCN007 | v5~v6 | `VERIFIED_LOAN_NEW_RECIPIENT_DRAIN` / 1 | 같은 출처, 원천 거래·신규 수취인 이벤트·coverage ID |
| SCN008 | v6 | `VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND` / 1 | `SCN008-contract-v1`, coverage ID와 2단계 이상 자금 흐름 |

## 사건 응답

```json
{"from":"2026-10-11","to":"2026-10-12","timeZone":"Asia/Seoul","asOf":"2026-10-12T00:00:00Z","days":[{"date":"2026-10-11","newCases":1}],"currentStatuses":{"OPEN":1,"IN_REVIEW":0,"ADDITIONAL_INFORMATION_REQUIRED":0,"CLOSED":0},"traceId":"trace_demo_dashboard_02"}
```

`newCases`는 `fraud_case.created_at` 일자별 고유 사건 수다. `currentStatuses`는 **선택 기간에 생성된 사건 코호트**의 조회 시점 현재 `case_status`다. 네 상태 합은 `newCases` 합과 같다. `case_transaction`은 어느 집계에도 조인하지 않아 다대다 연결이 수를 늘리지 않는다. 과거 일자별 사건 상태 재구성은 제공하지 않는다. 최종 판정을 위험등급 또는 실제 탐지 성능으로 해석하지 않는다.

늦게 접수된 과거 발생 시각 거래는 다음 조회에서 과거 버킷을 바꿀 수 있다. 이 API는 불변 역사 스냅샷이 아니다. 외부 AI 호출을 만들지 않는다.
