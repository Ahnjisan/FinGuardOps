# Issue #369 로컬 Rule v2 CRITICAL 검증

각 단계는 별도 고유 Compose 프로젝트에서 실행하고 해당 프로젝트가 소유한
컨테이너·네트워크·one-off만 정리한다. `down --volumes`와 prune을 사용하지 않는다.
토큰·암호·고객/계좌 참조, prompt·모델 출력은 기록하지 않는다. 각 실행의
transactionId, caseId, aiRequestId, executionId와 종료 코드만 연결한다.

## 정책 발행

새 프로젝트에는 V5의 v1 DRAFT 네 건이 있다. 먼저 기존
`rule-v1-default-publication` one-shot을 기존 runbook대로 실행하고 DB에서
PUBLISHED/활성 4/4를 확인한다. 그다음 **같은 프로젝트**에서 v2 시작 시각
`effectiveFrom`을 현재보다 충분히 미래의 UTC 마이크로초 시각으로 고정한다.
기존 거래의 `occurredAt`보다 뒤여야 한다. 아래 명령은 저장소 루트에서
해당 프로젝트의 고유 이름과 overlay를 지정해 실행한다. v1 기본 발행은
[기존 절차](./prometheus-local-scrape-runbook.md#4-로컬-rule-집합-발행)를
같은 프로젝트에 먼저 적용한다. one-shot은 웹·Kafka consumer를 시작하지 않는다.

```powershell
$project = 'finguardops-rule-v2-jwt-369' # 경로마다 고유 이름으로 변경
$overlayFiles = @('-f','infra/compose.local-jwt-e2e.yml')
$composeArgs = @('compose','-p',$project,'--env-file','infra/.env',
  '-f','infra/compose.yml') + $overlayFiles
$effectiveFrom = (Get-Date).ToUniversalTime().AddMinutes(2).ToString('yyyy-MM-ddTHH:mm:ssZ')
& docker @composeArgs run --rm --no-deps -T `
  -e SPRING_PROFILES_ACTIVE=local,rule-v2-local-publication `
  -e FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false `
  -e FINGUARDOPS_KAFKA_ENABLED=false `
  backend --spring.main.web-application-type=none `
  --finguardops.rule-v2-local-publication.enabled=true `
  --finguardops.rule-v2-local-publication.confirmation=PUBLISH_RULE_V2_LOCAL `
  "--finguardops.rule-v2-local-publication.effective-from=$effectiveFrom"
if ($LASTEXITCODE -ne 0) { throw 'Rule v2 publication failed' }
```

종료 코드 0, 성공 로그의 count 4, DB의 versionNumber 1 네 건
`effectiveTo=$effectiveFrom`, versionNumber 2 네 건 PUBLISHED 및
`effectiveFrom=$effectiveFrom`을 함께 확인한다. cutoff 직전에는 v1 네 건,
cutoff부터는 v2 네 건만 실행 가능해야 한다. 중복 one-shot은 거부한다.
되돌림은 이 runner의 기능이 아니다. 새 미래 cutoff로 v2 기간을 닫고 새
불변 버전을 발행할 별도 승인과 테스트가 필요하다. 과거 DetectionResult와
사건을 재계산하지 않는다.

## 분리된 검증 경로

1. 영향 단위·DB 통합 테스트에서 v1 최고 75/HIGH, v2 네 근거 85/CRITICAL,
   유효기간 경계와 반례를 확인한다.
2. JWT 전용 프로젝트에서 `compose.yml` + `compose.local-jwt-e2e.yml`을
   사용하고 v2 활성 후 `local-jwt-fixture` 안의
   `verify_rule_v2_critical.py`를 실행한다. 이 검증은 동일 멱등 키 재전송,
   HELD 거래, OPEN → IN_REVIEW 사건·메모·Audit, AI 리포트와 ID 결합을
   확인한다. Provider가 fallback이면 원인과 attempt를 별도 기록한다.
3. 공식 Keycloak Browser Gate는 clean commit이 된 뒤 저장소 루트에서
   `./frontend/scripts/run-keycloak-e2e.ps1 -Mode Prepare`, `-Mode Validate`,
   `-Mode Service`, `-Mode Run` 순으로 각각 종료 코드 0을 확인한다. Run은
   성공·실패 모두 소유 자원의 Cleanup을 수행하고, 정리가 끝나면 receipt를
   제거한다. Run은 고유 DB의 기존 v1 HIGH 사건·모의 Ollama 경로를 먼저
   끝낸다. 이어 같은 Gate에서 새 v2 네 버전을 미래 cutoff에 발행하고
   활성 4/4를 확인한 뒤, 네 행동 사실과 거래를 실제 접수한다. 저장된
   85/CRITICAL·HELD 및 새 사건 ID를 확인하고 실제 Keycloak USER 로그인으로
   연관 거래 링크의 채택 근거·조사·Audit·리포트를 읽는다. 별도
   PLATFORM_ADMIN 로그인으로 저장된 AI 요청을 읽는다. 기존 v1 Gate의
   모의 Ollama 첫 fallback·후속 실패 계약 이후 CRITICAL 요청은 같은 모의
   Provider의 완료 결과를 기대한다. 이를 실제 Qwen 결과로 분류하지 않는다.
   미커밋 작업 트리에서
   `SOURCE_NOT_CLEAN`이면 Prepare를 우회하지 않고 Browser 미검증으로 기록한다.
   준비 상태에서 중단됐거나 Run 실패 뒤에는 먼저 소유 receipt와 자원 잔여를
   확인한다. 소유 receipt가 남아 정리가 필요한 경우에만
   `./frontend/scripts/run-keycloak-e2e.ps1 -Mode Cleanup`을 실행한다. 정상 Run
   뒤에는 receipt가 이미 제거되므로 별도 Cleanup을 실행하거나 성공 조건으로
   세지 않는다. receipt 없이 잔여 자원이 보이면 임의 삭제 대신 실패 원인을
   조사한다.
4. Kafka 전용 프로젝트는 `compose.yml` + `compose.kafka-local.yml` +
   `compose.local-jwt-e2e.yml`로 생성한다. 요청 전후의 outbox event/execution,
   `kafkaStarts`·`kafkaRecordsStarted`·`pollingStarts`, 같은 topic/partition의
   consumer group offset 및 lag, 저장된 request·report·attempt를 대조한다.
   polling 선점이나 혼합 실행은 Kafka 성공으로 기록하지 않는다.
5. 실제 Qwen은 다른 고유 프로젝트의 `compose.qwen-local.yml`에서 마지막에
   한 CRITICAL smoke만 실행한다. 호스트·컨테이너의 `/api/tags` tag·digest·
   quantization 일치와 실행 직전 가용 메모리·디스크를 확인한다. 기존
   `evaluate_qwen_reports.py --fixtures /opt/local-jwt-fixture/rule_v2_critical_fixture.json
   --fixture-id four_distinct_critical --repetitions 1`을 사용하고 요청·execution·
   attempt와 저장 행을 #367 절차대로 대조한다. 미측정 토큰과 비용은 null이다.

서로 다른 프로젝트·사건의 결과를 단일 통합 실행으로 합치지 않는다.
Rule v2의 수취인 신호 독립성은 로컬 도메인 가설이며 오탐 개선을 입증하지 않는다.
