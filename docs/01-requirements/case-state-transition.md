# FinGuardOps 사건 상태 전이

## 1. 문서 목적

이 문서는 FinGuardOps에서 위험 거래와 연관 거래를 조사하는 사건의 업무 진행 상태, 최종 판정과 허용 전이를 요구사항 수준에서 정의한다.

이 문서는 이후 ERD, Entity, REST API, 트랜잭션 처리, 동시성 제어와 감사 로그 설계의 기준으로 사용한다.

구체적인 사건 API 경로, 요청·응답과 오류 계약은 `docs/03-api/case-audit-api.md`를 따르며, 이 문서의 담당자·종료 조건은 해당 API의 확정 초기 정책과 일치시킨다.

## 2. 문서 범위와 경계

이 문서는 사건 생성 이후 담당자 검토, 추가 정보 요청, 조사 종료와 최종 판정의 관계를 다룬다.

Issue #154에서 사건 영속 기반을 구현했고 Issue #209에서 조사 상태·담당자 변경
API, 낙관적 동시성 및 V11 감사 확장을 구현했다. Issue #211은 `IN_REVIEW`
사건의 최종 판정·종료와 V12 감사를 구현했다. 구체적인 물리 계약은
[`fraud-case-schema.md`](../04-database/fraud-case-schema.md)를 따른다.

다음 항목은 여전히 확정하거나 구현하지 않는다.

- 담당자 자동 배정과 사용자·담당자 디렉터리 연동 방식
- 인증·인가 기반 실제 USER actor
- 감사 로그 보존 방식
- 실제 금융기관의 고객 제재나 거래 차단 절차

Spring Boot는 사건 상태와 업무 정합성의 최종 소유자이다. 생성형 AI 리포트는 조사 참고 자료이며 사건 상태나 최종 판정을 확정하지 않는다.

## 3. 용어 정의

```text
caseStatus
= 사건 업무의 현재 진행 단계

finalDisposition
= 조사 결과
```

`caseStatus`와 `finalDisposition`은 서로 다른 개념이며 하나의 상태 목록으로 합치지 않는다.

초기 계약에서 조사 진행 중에는 `finalDisposition`을 `null`로 유지한다. 현재 정보만으로 판정할 수 없는 경우 새 최종 판정을 만들지 않고 `IN_REVIEW` 또는 `ADDITIONAL_INFORMATION_REQUIRED`를 유지한다. 최종 판정은 `IN_REVIEW` 사건을 resolution API로 종료할 때만 설정한다.

### 담당자

사건을 검토하고 조사 메모, 상태, 최종 판정과 변경 사유를 관리하는 FDS 분석 담당자이다.

초기 계약에서 `OPEN` 사건은 담당자가 없을 수 있지만 `IN_REVIEW` 사건에는 담당자가 반드시 있어야 한다. `OPEN`에서 `IN_REVIEW`로 전이할 때 상태 변경 요청의 `assigneeRef`로 담당자를 함께 지정하고, `ADDITIONAL_INFORMATION_REQUIRED`에서 `IN_REVIEW`로 복귀할 때는 기존 담당자가 있어야 한다. `IN_REVIEW`는 담당자 변경만, `ADDITIONAL_INFORMATION_REQUIRED`는 배정·변경·해제를 허용한다. 자동 배정 도입 여부는 `TBD`이다.

### 최종 판정

조사 결과 정상, 오탐 또는 이상거래로 판단한 값이다. 생성형 AI나 플랫폼·클라우드 운영자가 확정할 수 없다.

## 4. 상태 목록

사건 상태인 `caseStatus`는 다음 값을 유지한다.

- `OPEN`
- `IN_REVIEW`
- `ADDITIONAL_INFORMATION_REQUIRED`
- `CLOSED`

최종 판정인 `finalDisposition`은 다음 값을 유지한다.

- `NORMAL`
- `FALSE_POSITIVE`
- `CONFIRMED_FRAUD`

새로운 사건 상태나 최종 판정은 추가하지 않는다.

## 5. 상태별 의미

### `OPEN`

사건이 생성되어 검토를 기다리는 상태이다. 초기 계약에서는 담당자가 없는 상태를 허용한다.

### `IN_REVIEW`

FDS 분석 담당자가 사건의 거래, 탐지 근거, 행동 타임라인, 연관 거래와 AI 리포트 등 참고 자료를 검토하고 있는 상태이다.

### `ADDITIONAL_INFORMATION_REQUIRED`

현재 정보만으로 최종 판정을 내릴 수 없어 고객 확인, 외부 위험정보 또는 추가 조사 자료가 필요한 상태이다. 이 상태 자체가 이상거래 판정을 의미하지 않는다.

### `CLOSED`

조사와 필요한 후속 기록이 완료되어 사건 처리를 종료한 상태이다. 초기 계약에서는 `IN_REVIEW` 사건만 종료할 수 있고 최종 판정이 반드시 필요하다.

### `NORMAL`

조사 결과 정상적인 금융거래로 확인된 최종 판정이다.

### `FALSE_POSITIVE`

Rule 또는 모델의 부정확한 경보로 확인되어 탐지 정책 개선 대상으로 기록하는 최종 판정이다.

### `CONFIRMED_FRAUD`

FDS 분석 담당자의 조사 결과 이상거래로 확정된 최종 판정이다. 이 프로젝트에서 실제 고객 제재나 실제 거래 차단을 수행한다는 의미는 아니다.

## 6. 텍스트 상태 전이도

기본 검토 흐름은 다음과 같다.

```text
OPEN
→ IN_REVIEW
→ ADDITIONAL_INFORMATION_REQUIRED
→ IN_REVIEW
→ CLOSED
```

조사 중 최종 판정의 관계는 다음과 같다.

```text
OPEN 또는 IN_REVIEW 또는 ADDITIONAL_INFORMATION_REQUIRED
└─ finalDisposition = null 유지

IN_REVIEW
└─ 조사 결과 확정
   ├─ NORMAL
   ├─ FALSE_POSITIVE
   └─ CONFIRMED_FRAUD
      + CLOSED 전이
```

초기 계약에서는 다음 전이를 허용하지 않는다.

```text
OPEN → CLOSED
ADDITIONAL_INFORMATION_REQUIRED → CLOSED
CLOSED → IN_REVIEW
```

## 7. 허용 전이

### `OPEN` → `IN_REVIEW`

- 전이 조건: FDS 분석 담당자가 상태 변경 요청의 유효한 `assigneeRef`로 담당자를 지정하고 사건 검토를 시작한다.
- 변경 주체: FDS 분석 담당자. 시스템이 검토 시작을 자동 확정하는 정책은 없음
- 생성되는 결과: 담당자, 최초 `reviewStartedAt`, 검토 시작 이력과 변경 사유
- 최종 판정: `null` 유지
- 실패 시 처리: 저장 실패 시 사용자가 입력한 내용을 보존하고 기존 상태를 유지한다.
- 재시도 가능 여부: 가능. 동일 요청이 중복 변경을 만들지 않아야 한다.
- 감사 로그 여부: 필요

담당자 지정, `caseStatus = IN_REVIEW`, 최초 `reviewStartedAt`, `lastChangedAt`, `concurrencyVersion`과 AuditLog는 일부만 반영되지 않도록 같은 업무 정합성 경계에서 처리한다.

### `IN_REVIEW` → `ADDITIONAL_INFORMATION_REQUIRED`

- 전이 조건: 현재 정보만으로 판정할 수 없고 필요한 추가 정보와 사유가 식별된다.
- 변경 주체: FDS 분석 담당자
- 생성되는 결과: 추가 정보 요청 사유와 필요한 정보 후보
- 최종 판정: `null` 유지
- 실패 시 처리: 기존 상태와 작성 중인 내용을 보존한다.
- 재시도 가능 여부: 가능. 중복 요청 기록을 방지해야 한다.
- 감사 로그 여부: 필요

### `ADDITIONAL_INFORMATION_REQUIRED` → `IN_REVIEW`

- 전이 조건: 요청한 정보가 도착했거나 담당자가 현재 자료로 검토를 재개하며 사건에 기존 담당자가 있다.
- 변경 주체: FDS 분석 담당자. 정보 도착만으로 사건 상태를 자동 확정하지 않는다.
- 생성되는 결과: 검토 재개 이력과 추가 정보 수신·확인 근거. 최초 `reviewStartedAt`은 유지한다.
- 최종 판정: `null` 유지
- 실패 시 처리: 추가 정보는 유실하지 않고 기존 상태를 유지한다.
- 재시도 가능 여부: 가능
- 감사 로그 여부: 필요

### `IN_REVIEW` → `CLOSED`

- 전이 조건: 조사 결과가 확정되고 `NORMAL`, `FALSE_POSITIVE`, `CONFIRMED_FRAUD` 중 하나의 `finalDisposition`과 `CASE_RESOLUTION_COMPLETED` 사유 코드가 제공된다.
- 변경 주체: FDS 분석 담당자
- 생성되는 결과: 필수 최종 판정, `CLOSED`, 같은 마이크로초 시각의 `closedAt`·`lastChangedAt`, 실제 증가한 `concurrencyVersion`과 구조화된 감사
- 최종 판정: 필수
- 실패 시 처리: 상태와 판정 중 일부만 반영되지 않도록 정합성을 유지한다.
- 재시도 가능 여부: 최신 사건을 다시 조회한 뒤 새 `expectedVersion`으로 가능. 기존 응답을 replay하지 않는다.
- 감사 로그 여부: 필요

종료는 일반 상태 변경 API가 아니라 `POST /api/v1/cases/{caseId}/resolution`에서만 수행하며, 최종 판정 설정과 `CLOSED` 전이를 하나의 REQUIRED 트랜잭션으로 처리한다. `Idempotency-Key`, 자유 텍스트 사유, row lock과 자동 retry는 사용하지 않는다.

## 8. 금지 전이

다음 전이는 현재 요구사항에서 금지한다.

- `caseStatus`에 `NORMAL`, `FALSE_POSITIVE`, `CONFIRMED_FRAUD`를 저장하지 않는다.
- `finalDisposition`에 `OPEN`, `IN_REVIEW`, `ADDITIONAL_INFORMATION_REQUIRED`, `CLOSED`를 저장하지 않는다.
- 생성형 AI가 사건 상태나 최종 판정을 변경하지 않는다.
- 플랫폼·클라우드 운영자가 사건의 최종 판정을 확정하지 않는다.
- AI 리포트의 `FAILED` 또는 `FALLBACK_COMPLETED` 결과가 사건 상태를 자동 변경하지 않는다.
- 외부 서비스 장애나 운영 알림만을 근거로 사건 최종 판정을 변경하지 않는다.
- 동시 수정 충돌을 무시하고 나중 요청이 먼저 저장된 변경을 덮어쓰게 하지 않는다.

다음 전이는 초기 사건 API 범위에서 금지한다.

- `OPEN` → `CLOSED`
- `ADDITIONAL_INFORMATION_REQUIRED` → `CLOSED`
- `CLOSED` → `IN_REVIEW`
- `CLOSED` → `OPEN`
- `CLOSED` 이후 `finalDisposition` 변경

## 9. 전이 조건

- 모든 전이는 현재 `caseStatus`와 요청한 다음 상태의 조합을 검증해야 한다. 같은 상태 요청은 무변경 성공으로 처리하지 않고 `409 Conflict`와 `CASE_STATUS_CONFLICT`로 거부한다.
- 사건 종료 시 현재 상태가 `IN_REVIEW`인지 확인하고 `caseStatus`, 필수 `finalDisposition`과 `closedAt`의 정합성을 함께 검증해야 한다.
- 상태 변경은 승인된 사유 코드를 기록한다. 종료는 자유 텍스트 조사 근거를 받지 않고 `CASE_RESOLUTION_COMPLETED`만 허용한다.
- 담당자 권한과 사건 접근 권한을 검증해야 한다.
- `IN_REVIEW`에는 담당자가 필요하다. `OPEN`에서 최초 진입할 때 담당자를 함께 지정하고 추가 정보 상태에서 복귀할 때는 기존 담당자를 확인한다.
- 상태와 판정의 변경은 관련 사건과 거래 식별자를 유지해야 한다.
- 외부 위험정보가 복구 후 변경되면 기존 판정을 자동 변경하지 않고 담당자 검토와 감사 이력을 거쳐야 한다.

### 초기 정책별 조건

#### `OPEN`에서 바로 `CLOSED`

초기 계약에서는 허용하지 않는다. 사건 종료는 `IN_REVIEW`에서 resolution API로만 수행한다.

#### `ADDITIONAL_INFORMATION_REQUIRED`에서 `CLOSED`

초기 계약에서는 허용하지 않는다. 기존 담당자가 있는지 확인해 `IN_REVIEW`로 복귀한 뒤 종료한다.

#### `CLOSED` 사건 재개

종료 사건 재개는 초기 범위에서 제외한다. 후속 도입 시 재개 상태, 권한, 사유, 기존 판정 처리와 이력 보존 정책에 별도 사용자 승인이 필요하다.

#### 최종 판정 없이 `CLOSED`

허용하지 않는다. `finalDisposition`이 누락되거나 null이면 사건을 변경하지 않고 `422 Unprocessable Entity`와 `FINAL_DISPOSITION_REQUIRED`로 거부한다.

#### 최종 판정 변경

종료 후 최종 판정 변경은 초기 범위에서 제외한다. 후속 도입 시 권한, 변경 사유, 승인 절차, 이전 값 보존과 사건 재개 필요 여부에 별도 사용자 승인이 필요하며 기존 판정을 덮어써 이력을 잃어서는 안 된다.

#### 담당자 없는 `IN_REVIEW`

허용하지 않는다. `OPEN` → `IN_REVIEW` 요청에 담당자가 없거나 `ADDITIONAL_INFORMATION_REQUIRED` → `IN_REVIEW` 시 기존 담당자가 없으면 사건을 변경하지 않고 `422 Unprocessable Entity`와 `ASSIGNEE_REQUIRED`로 거부한다.

## 10. 변경 주체

### 시스템

- HIGH·CRITICAL 위험 거래의 사건을 생성하고 초기 `OPEN` 상태를 설정할 수 있다.
- 승인된 자동 처리만 수행하며 담당자의 최종 판정이나 조사 상태를 임의로 확정하지 않는다.
- 상태 전이 검증, 동시성 충돌 탐지와 감사 기록의 정합성을 책임진다.

### FDS 분석 담당자

- 사건을 검토하고 조사 메모를 작성한다.
- 허용된 범위에서 사건 상태를 변경한다.
- 조사 근거를 바탕으로 `NORMAL`, `FALSE_POSITIVE`, `CONFIRMED_FRAUD`를 확정한다.
- 변경 사유를 기록한다.

### 플랫폼·클라우드 운영자

- 서비스 장애와 사건·리포트 기능의 영향 범위를 관찰한다.
- 승인된 운영 절차를 수행하고 운영 이력을 기록한다.
- 사건의 최종 판정을 확정하거나 운영 장애를 이유로 사건 상태를 업무적으로 변경하지 않는다.

### 생성형 AI

- 탐지 근거, 행동 타임라인과 담당자 확인 항목을 리포트로 요약할 수 있다.
- 위험 점수, 최종 판정과 사건 상태를 결정하거나 확정하지 않는다.

## 11. 멱등성과 재요청

- 동일 위험 거래의 재처리나 중복 이벤트가 같은 사건을 중복 생성하지 않아야 한다.
- 사건 생성 요청이 재시도되면 기존 사건 생성 또는 연결 결과를 유지해야 한다.
- 같은 상태나 같은 담당자 요청은 충돌로 거부하고 사건·감사 이력을 변경하지 않는다.
- resolution API는 `Idempotency-Key`를 요구하거나 replay하지 않는다.
- 종료 성공 후 같은·다른 판정 재요청은 모두 `CASE_ALREADY_CLOSED`이며, stale version이면 `CONCURRENT_MODIFICATION`이 먼저다.
- 여러 연관 거래를 기존 사건에 연결할지 새 사건을 만들지에 대한 병합·분리 기준은 `TBD`이다.

## 12. 동시성 고려사항

동시에 두 사용자가 같은 사건을 수정하는 경우 다음 원칙이 필요하다.

- 각 사용자가 조회한 사건의 상태와 마지막 변경 이후 다른 변경이 있었는지 확인해야 한다.
- 먼저 저장된 상태·판정·메모를 나중 요청이 조용히 덮어쓰지 않아야 한다.
- 충돌이 발생하면 사용자에게 사건이 변경되었음을 알리고 최신 값을 다시 확인할 수 있어야 한다.
- 상태와 최종 판정을 함께 변경하는 요청은 일부만 반영되지 않도록 업무 정합성을 유지해야 한다.
- 조사 메모도 상태·담당자·판정과 같은 `FraudCase.concurrencyVersion` 경계를 사용한다.
  동일 expected version의 note 대 note/resolution/status/assignee 경쟁은 정확히 하나만
  성공하고 다른 하나는 `CONCURRENT_MODIFICATION`이며 row lock·자동 retry를 사용하지 않는다.
- 사건 생성·첫 연결은 거래 → 사건 → 연결 순서의 비관적 잠금을 사용한다. 조사
  상태·담당자 변경은 body `expectedVersion`과 JPA `@Version` 낙관적 잠금을
  사용하고 row lock과 자동 retry는 추가하지 않는다.
- 사건 resolution도 같은 `expectedVersion`·JPA `@Version` 경계를 사용한다.
- 메모는 `IN_REVIEW`, `ADDITIONAL_INFORMATION_REQUIRED`에서만 허용한다. `OPEN`,
  `CLOSED`는 `NOTE_NOT_ALLOWED`이고 stale version 판정이 상태 판정보다 우선한다.
- 사건 조회와 version 비교 후 업무 규칙을 검증한다. 명시적 flush에서 version
  증가를 확정하고 같은 REQUIRED 트랜잭션에서 AuditLog를 append·flush한다.
- 동일 version 동시 요청은 정확히 하나만 성공하며 충돌 또는 감사 실패는 사건과
  감사 전체를 rollback한다.

시스템의 연관 거래 추가와 담당자의 사건 종료가 동시에 발생하는 경우, 종료 허용 여부와 재검토 필요 조건도 `TBD`이다.

## 13. 실패·재시도 원칙

- 저장 실패 시 사용자가 입력한 메모, 판정과 변경 사유를 가능한 범위에서 화면에 보존해야 한다.
- 상태와 판정 저장 결과가 불명확하면 성공으로 임의 처리하지 않는다.
- 재시도는 현재 사건 상태와 기존 처리 결과를 다시 확인한 뒤 수행해야 한다.
- AI 리포트 생성 실패는 사건 상태 변경 실패가 아니다.
- AI 리포트 실패가 사건 상태를 자동 변경하지 않는다.
- 외부 위험정보 조회 실패를 정보 없음으로 해석하거나 사건을 자동 종료하지 않는다.
- 운영 장애 복구 후 사건·거래·감사 이력의 누락과 중복을 검증해야 한다.
- 재시도 횟수, 간격과 자동화 범위는 이 문서에서 확정하지 않는다.
- Kafka는 현재 필수 사건 처리 흐름이 아니다. 향후 도입하더라도 중복 이벤트로 사건이나 상태 변경이 중복 반영되지 않아야 한다.

## 14. 감사 로그 요구사항

모든 주요 사건 변경에는 다음 정보를 기록해야 한다.

- 변경 사용자 또는 시스템
- 변경 시각
- 변경 대상
- 이전 값
- 변경 후 값
- 변경 사유
- `caseId`
- 관련 `transactionId`
- `traceId` 후보

감사 대상에는 다음 항목을 포함한다.

- 사건 생성과 기존 사건 연결
- 담당자 배정·변경·해제 성공
- `caseStatus` 변경
- `finalDisposition` 설정·변경 시도
- 추가 정보 요청과 검토 재개
- 사건 종료와 재개 시도
- 동시성 충돌과 거부된 상태 전이는 후속 별도 감사 후보
- 외부 정보 변경에 따른 근거 갱신
- 승인된 운영자 조치

감사 로그는 이전 값을 덮어쓰지 않고 변경 흐름을 추적할 수 있어야 하며 조회 화면에서
수정·삭제하지 않는다. V7 append-only AuditLog 물리 기반과 위험 대응에 따른 신규
사건·첫 연결 감사 통합은 구현되었다. 기존 활성 사건 재사용에는 사건 변경이 없으므로
사건 감사를 중복 기록하지 않는다. 성공한 조사 상태·담당자 명령은 V11의 구조화
action/reason으로 정확히 1건 기록하고 성공한 종료는 V12의
`CASE_RESOLVED/CASE_RESOLUTION_COMPLETED`를 정확히 1건 기록한다. Issue #213의
조사 메모 성공은 `CASE_NOTE_CREATED/CASE_INVESTIGATION_NOTE_ADDED`와 exact
`noteId`만 기록한다. Issue #215의 사건 감사 조회는 사건 존재 확인 후 해당 사건의
`FRAUD_CASE` 행만 결정적으로 page 조회하며, action별 승인 projection 외의 내부
식별자·원본 JSON·과거 trace를 노출하지 않는다. 실패·거부·stale 요청의 별도 감사,
보존 기간과 접근 범위는 후속 범위이다.

## 15. 사용자 결정 필요 항목

- 초기 수동 배정 외에 담당자 자동 배정을 도입할지
- 사용자·담당자 디렉터리 도입 시 `assigneeRef` 검증 방식
- 초기 범위 밖인 종료 사건 재개와 최종 판정 정정을 향후 도입할지
- 연관 거래 추가와 사건 종료의 동시 실행 정책
- 두 사용자의 동시 수정 충돌 처리와 사용자 입력 보존 방식
- 사건 병합·분리 및 동일 의심 흐름의 중복 방지 기준
- 감사 로그 보존 기간과 접근 범위

## 16. 후속 ERD·API 설계 항목

후속 설계에서는 다음 항목을 사용자 승인으로 구체화해야 한다.

- `caseStatus`와 `finalDisposition`의 분리 표현 및 null 허용 규칙
- 사건과 하나 이상의 거래 연결 구조
- 담당자와 배정 이력 표현 및 자동 배정 도입 여부
- `IN_REVIEW` 담당자 필수와 `IN_REVIEW`에서만 최종 판정과 함께 종료하는 정합성 규칙
- 상태·판정·메모 변경의 트랜잭션 경계
- 동시 수정 충돌 감지 정보와 충돌 응답 계약
- 사건 생성 및 상태 변경의 멱등성 계약
- 판정 정정 또는 사건 재개 시 이력 보존 방식
- 감사 로그와 `caseId`, `transactionId`, `traceId` 연결
- 외부 정보 갱신과 사건 근거 버전 표현

이 문서는 사건 상태와 최종 판정 계약의 기준이다. Issue #154에서 Java Enum,
사건·첫 거래 연결 Entity와 V6 물리 스키마를 구현했고, Issue #156에서 V7
append-only AuditLog 물리 기반을 구현했다. Issue #209는 상태·담당자 mutation과
V11 감사 통합을 구현했고 Issue #211은 사건 resolution과 V12 감사를 구현했다.
