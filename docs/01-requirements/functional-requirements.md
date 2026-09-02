# 기능 요구사항

## 1. 거래 데이터 관리

- 사용자는 거래 데이터를 등록할 수 있다.
- 사용자는 거래 목록을 조회할 수 있다.
- 사용자는 특정 거래의 상세 정보를 조회할 수 있다.
- 시스템은 같은 거래 생성 멱등 키와 같은 정규화 요청의 재전송에서 새 거래·탐지·사건을 만들지 않고 최초 확정 업무 결과를 재생해야 한다.
- 시스템은 같은 거래 생성 멱등 키에 다른 정규화 요청이 오면 `IDEMPOTENCY_KEY_CONFLICT`로 거부해야 한다.
- 멱등 응답 재생은 최초 명령 결과를 재현하고, 최신 거래·탐지·사건 상태는 별도 조회 기능이 제공해야 한다.
- 신뢰된 금융·인증 시스템 또는 승인된 수집 어댑터는 지원되는 사용자 행동 이벤트를 등록할 수 있다.
- 시스템은 행동 이벤트의 `eventId`와 정규화 요청 지문을 기준으로 동일 이벤트 재전송과 충돌을 구분해야 한다.
- 시스템은 행동 이벤트가 거래를 참조할 때 고객·계좌·거래 유형의 업무 정합성을 검증해야 한다.

## 2. 이상거래 탐지

- 시스템은 거래 데이터를 기반으로 위험 점수를 계산한다.
- 시스템은 위험 점수에 따라 `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` 위험 등급을 분류한다.
- 시스템은 탐지 사유를 생성한다.
- 초기 Rule 기반 탐지의 입력, R001~R004 조건, 점수와 위험 등급 경계는 [`rule-v1-detection-contract.md`](./rule-v1-detection-contract.md)를 단일 기준으로 사용한다.
- Rule v1은 문서로 확정되었고 DetectionResult·Evidence 물리 영속 모델은
  구현되었지만 Rule 실행, 실행 결과 생성·검증·채택과 FastAPI 연동은
  아직 구현되지 않았다.
- 현재 거래 생성은 `RECEIVED`/null legacy Snapshot을 저장한다. 최종 동기 탐지 응답 전환, Snapshot 불변성과 version 재생 정책은 [`../07-decisions/ADR-004-idempotency-response-snapshot-transition.md`](../07-decisions/ADR-004-idempotency-response-snapshot-transition.md)를 따르며 후속 구현이 필요하다.

## 3. 사건 조회

- FDS 분석 담당자는 사건 목록과 사건 상세를 조회할 수 있다.
- 목록은 0-based 페이지와 `lastChangedAt` 단일 정렬을 사용하고 내부 `id`로
  동일 시각의 순서를 결정적으로 유지해야 한다.
- 목록은 사건 상태, 최종 판정, opaque 담당자 참조, 생성·변경 시각과 연관 거래
  식별자로 필터할 수 있으며 시간 범위는 `[from,to)`이다.
- 목록과 상세의 연관 거래 수는 현재 조회 페이지를 대상으로 한 일괄 집계로
  계산하고 건별 조회나 전체 연관 거래 로딩을 해서는 안 된다.
- 사건 상세가 없으면 안전한 `404 RESOURCE_NOT_FOUND`를 반환해야 한다.
- 내부 PK·예외·SQL·credential, 불필요한 고객·계좌·기기 원문과 내부 snapshot·
  Provider payload를 응답에 포함해서는 안 된다.
- 구체적인 계약은 [`../03-api/case-audit-api.md`](../03-api/case-audit-api.md),
  저장·인덱스 계약은
  [`../04-database/fraud-case-schema.md`](../04-database/fraud-case-schema.md)를
  따른다.

## 4. 사건 상태·담당자 변경과 종료

- FDS 분석 담당자는 승인된 세 조사 상태 전이와 상태별 담당자 배정·변경·해제만
  요청할 수 있다.
- `OPEN` → `IN_REVIEW`는 담당자 지정과 최초 `reviewStartedAt` 설정을 원자적으로
  수행하고 이후 상태 왕복에서는 최초 시각을 유지해야 한다.
- 신규 write 담당자 참조는 canonical lowercase UUID v4이며 입력을 정규화하거나
  오류·로그에 반사해서는 안 된다.
- 사건을 조회한 `expectedVersion`을 현재 `concurrencyVersion`과 비교하고 실제 JPA
  version 증가와 성공 감사 1건을 같은 REQUIRED 트랜잭션에서 확정해야 한다.
- stale·동일 값·금지 전이·종료 사건 요청은 사건과 감사를 변경하지 않아야 한다.
- `POST /api/v1/cases/{caseId}/resolution`은 담당자와 최초 조사 시각이 있는
  `IN_REVIEW` 사건만 `NORMAL`, `FALSE_POSITIVE`, `CONFIRMED_FRAUD` 중 하나로
  판정하고 종료해야 한다.
- resolution은 자유 텍스트 reason과 `Idempotency-Key`를 사용하지 않고
  `reasonCode=CASE_RESOLUTION_COMPLETED`, 필수 `expectedVersion`을 사용한다.
- 최종 판정, `CLOSED`, 하나의 마이크로초 정밀도 종료·변경 시각, 실제 JPA version
  증가와 성공 AuditLog 1건을 같은 REQUIRED 트랜잭션에서 확정해야 한다.
- stale version은 종료 상태·동일 판정보다 먼저 거부하고, 이미 종료된 사건의
  같은·다른 판정 재요청은 모두 거부해야 한다.
- 종료는 Transaction·RiskLevel·RiskResponseOutcome·CaseTransaction과 AI 처리를
  변경하지 않아야 한다.
- 인증·인가, RBAC, 실제 USER actor와 거부 요청 별도 감사는 미구현이다.
- 조사 메모는 `IN_REVIEW`, `ADDITIONAL_INFORMATION_REQUIRED` 사건에만 append-only로
  생성하며 서버가 `SYSTEM/finguardops-backend` 작성자를 설정한다.
- 조사 메모 원문은 Unicode code point 1..4,000의 plain text로만 보존하고
  AuditLog·로그·오류에 원문·길이·hash·preview를 기록하지 않는다.
- 조사 메모 생성은 필수 `expectedVersion`으로 부모 사건 version을 정확히 1 증가시키고
  메모·`CASE_NOTE_CREATED` 감사와 함께 원자적으로 commit하거나 모두 rollback한다.
- 메모 API는 `Idempotency-Key` replay, 정정 참조, 개별 조회·수정·삭제를 제공하지 않는다.
- 구체적인 API와 오류 계약은
  [`../03-api/case-audit-api.md`](../03-api/case-audit-api.md), 상태 matrix는
  [`case-state-transition.md`](./case-state-transition.md)를 따른다.

## 5. AI 분석 리포트

- 시스템은 이상거래에 대한 분석 리포트를 생성한다.
- 리포트에는 탐지 사유, 위험 요인, 권장 대응이 포함된다.

## 6. 관리자 대시보드

- 관리자는 전체 거래 수를 확인할 수 있다.
- 관리자는 위험 거래 수를 확인할 수 있다.
- 관리자는 최근 이상거래 목록을 확인할 수 있다.

## 7. 운영 및 모니터링

- 시스템은 서비스 로그를 수집한다.
- 시스템은 API 응답 시간과 에러율을 모니터링한다.
- 시스템은 배포 상태를 확인할 수 있다.
