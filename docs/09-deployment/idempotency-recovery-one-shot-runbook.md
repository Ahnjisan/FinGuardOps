# Idempotency 복구 one-shot runbook

## 목적과 안전 경계

이 절차는 장기 `IN_PROGRESS` 후보를 제한적으로 확인하고, 검증된 Snapshot v2 완료
간극의 내부 record 하나만 수동 복구한다. 정상 Backend 서버 시작과 완전히 분리된
non-web one-shot 실행이며 scheduler·batch·cron 또는 자동 반복 도구로 사용하지 않는다.

복구는 기존 상태를 검증해 누락된 Snapshot v2와 `COMPLETED`만 복원한다. External Risk
Provider, FastAPI, Rule evaluator·분석, Risk Response decision·최종화, 사건 생성·추가
연결, 업무 AuditLog와 LLM을 재실행하지 않는다. 자동 retry·fallback·cache도 없다.

## 사전 준비

1. 변경 승인과 장애 영향 범위를 확인하고 자동 반복 작업이 없음을 확인한다.
2. Backend artifact와 V1~V9 Flyway migration이 승인된 버전인지 확인한다.
3. DB 연결 정보를 command-line 인자가 아닌 기존 환경 변수로만 설정한다.

```powershell
$env:SPRING_DATASOURCE_URL = '<approved JDBC URL>'
$env:SPRING_DATASOURCE_USERNAME = '<approved DB user>'
$env:SPRING_DATASOURCE_PASSWORD = '<secret from approved secret store>'
```

실제 값을 문서, shell history, Issue, PR, stdout·stderr 또는 로그에 복사하지 않는다.
`--spring.datasource.*`와 기타 credential·token·password 인자는 금지한다.

애플리케이션에는 실제 운영자 USER 인증·인가가 구현되어 있지 않다. 명령은 기존
`SYSTEM` actor와 `finguardops-backend` reference만 사용한다. 실행 주체 승인과 접근
통제는 OS 계정, 배포 플랫폼, secret store와 DB 권한 등 애플리케이션 외부 운영
경계에서 수행한다.

## 정상 startup과 recovery startup

정상 startup에는 recovery prefix 인자를 사용하지 않는다.

```powershell
cd backend
java -jar .\build\libs\backend-0.0.1-SNAPSHOT.jar
```

`--finguardops.idempotency-recovery.` prefix가 하나라도 있으면 일반 Backend web startup을
하지 않는다. 모든 command 입력을 Spring context 생성 전에 검증하고, 유효한 경우에만
제한된 `WebApplicationType.NONE` recovery context를 시작해 한 번 실행한 뒤 닫는다.

## 후보 inspect

기본 threshold는 30분, 기본 page size는 50이다. threshold는 `PT5M` 이상 `P7D` 이하,
page size는 1~100만 허용한다. 다음 예시는 승인된 전체 형식이다.

```powershell
cd backend
java -jar .\build\libs\backend-0.0.1-SNAPSHOT.jar --finguardops.idempotency-recovery.enabled=true --finguardops.idempotency-recovery.action=inspect --finguardops.idempotency-recovery.threshold=PT30M --finguardops.idempotency-recovery.page-size=50
$LASTEXITCODE
```

inspect는 기존 bounded 조회를 정확히 한 번 수행한다. 전체 후보 순회, 다음 page 조회,
count, offset, 후보 lock, DB write와 recovery audit 생성은 하지 않는다.

```jsonl
{"type":"candidate","action":"inspect","recordId":123,"transactionId":"11111111-1111-4111-8111-111111111111","updatedAt":"2026-08-28T00:00:00Z"}
{"type":"candidate","action":"inspect","recordId":124,"transactionId":null,"updatedAt":"2026-08-28T00:01:00Z"}
{"type":"summary","action":"inspect","processedCount":2}
```

후보가 없으면 `processedCount`가 0인 summary 한 줄과 exit code 0이 정상이다.

## 정확한 record 하나 복구

1. inspect 결과와 승인된 DB·감사 조회로 후보의 내부 `recordId`를 확인한다.
2. 거래가 이미 공식 최종 상태이고 Snapshot v2 완료 간극인지 수동 검토한다.
3. 한 번의 명령에 canonical positive decimal long record ID 하나만 지정한다.

```powershell
cd backend
java -jar .\build\libs\backend-0.0.1-SNAPSHOT.jar --finguardops.idempotency-recovery.enabled=true --finguardops.idempotency-recovery.action=recover --finguardops.idempotency-recovery.record-id=123
$LASTEXITCODE
```

여러 record를 한 번에 처리하거나 후보를 자동 순회하지 않는다. 동일 명령을 scheduler,
batch, cron 또는 shell loop로 반복하지 않는다.

성공 예시:

```jsonl
{"type":"result","action":"recover","recordId":123,"transactionId":"11111111-1111-4111-8111-111111111111","decision":"RECOVERABLE_COMPLETION_GAP","auditResult":"RECOVERED"}
```

typed 거부 예시:

```jsonl
{"type":"result","action":"recover","recordId":123,"transactionId":"11111111-1111-4111-8111-111111111111","decision":"ALREADY_TERMINAL","auditResult":"REJECTED"}
```

## exit code와 오류

| Exit code | 의미 | 조치 |
| --- | --- | --- |
| `0` | inspect 완료 또는 `RECOVERED` | 아래 성공 확인 절차를 수행한다. |
| `1` | context·DB·내부 실패 또는 `FAILED` | 즉시 중단하고 DB·배포 상태와 FAILED 복구 감사를 수동 검토한다. 자동 재시도하지 않는다. |
| `2` | command 입력·strict validation 오류 | 인자를 승인된 형식과 allowlist에 맞춰 수정한다. DB 작업은 시작되지 않는다. |
| `3` | typed `REJECTED` | 업무·Idempotency 상태를 변경하지 말고 decision과 거부 감사를 수동 검토한다. |

입력 오류는 stderr에 다음 고정 code만 출력한다.

```jsonl
{"type":"error","code":"INVALID_RECOVERY_COMMAND"}
```

context·DB·내부 실패는 stderr에 다음 고정 code만 출력한다.

```jsonl
{"type":"error","code":"RECOVERY_INTERNAL_FAILURE"}
```

입력 원문, 원본 Idempotency-Key, fingerprint, Snapshot JSON, Provider payload, 고객·계좌·
기기 reference, credential, 예외 message·class·stack trace는 출력하지 않는다.

## 성공 후 확인

승인된 read-only DB 도구에서 정확한 record ID만 사용해 다음을 확인한다.

```sql
SELECT processing_status, financial_transaction_id, finished_at
FROM idempotency_record
WHERE id = 123;

SELECT recovery_decision, audit_result, actor_type, actor_id, attempted_at
FROM idempotency_recovery_audit_log
WHERE idempotency_record_id = 123
ORDER BY id;
```

`COMPLETED`, `RECOVERABLE_COMPLETION_GAP`, `RECOVERED`, `SYSTEM`,
`finguardops-backend`를 확인한다. 기존 승인된 client로 원래 public 요청을 재전송해 저장된
HTTP 201 Snapshot v2가 replay되는지 확인하되 Idempotency-Key를 이 명령이나 운영
기록에 복사하지 않는다. replay에서 coordinator·Provider·Rule·최종화·사건 생성 호출이
없어야 한다.

typed `REJECTED`이면 반복 실행하지 말고 업무·Idempotency 불변과 REJECTED 감사를
확인한다. exit 1이면 원 transaction rollback과 FAILED 감사 여부를 확인한 후 중단하고
Project Owner에게 수동 검토를 요청한다. 불확실 상태 자동 재실행과 `FAILED` 재분석,
public·internal 관리 API는 구현되어 있지 않다.
