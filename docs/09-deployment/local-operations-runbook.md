# 기본 로컬 운영 시작과 종료

이 문서는 새 체크아웃에서 **기본 Compose**의 설정·health·Rule·로컬 관측을 확인하고, 별도 **로컬 JWT fixture overlay**로 인증된 합성 거래를 확인하는 짧은 진입점이다. 명령은 저장소 루트에서 실행한다. Rule 발행, 인증 거래, scrape의 검증 명령은 [Prometheus 로컬 scrape runbook](./prometheus-local-scrape-runbook.md)의 해당 절을 사용한다. Keycloak, Kafka broker, 실제 Qwen 모델은 어느 경로에도 포함되지 않는다.

## 1. 준비와 프로젝트 기록

Docker Desktop·Compose v2, PowerShell, Git Bash와 Python 3을 준비한다. [예시 환경 파일](../../infra/.env.example)을 참고해 ignored `infra/.env`에 로컬 전용 `POSTGRES_PASSWORD`, `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD`를 준비한다. 예시 placeholder를 실제 값으로 사용하지 않는다. 값은 문서·명령 인자·화면 출력·증거 기록에 복사하지 않는다. 기존 `infra/.env`는 덮어쓰지 않는다.

아래 PowerShell은 실행할 때마다 고유 Compose 프로젝트명을 만든다. 생성된 이름, checkout commit, 저장소 루트의 절대 경로, `$compose -join ' '`의 **전체 문자열**을 세션 변수와 별개의 비민감 실행 기록에 적는다. 이후 명령과 Git Bash 검증에서 **같은 이름**을 사용한다. 기존 프로젝트 이름을 재사용하지 않는다.

```powershell
$project = 'finguardops-base-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$compose = @('compose', '-p', $project, '--env-file', 'infra/.env', '-f', 'infra/compose.yml')
if (-not (Test-Path -LiteralPath 'infra/.env')) { throw 'Prepare ignored infra/.env first' }
git check-ignore --quiet -- infra/.env
if ($LASTEXITCODE -ne 0) { throw 'infra/.env must be ignored' }
git rev-parse HEAD
$project
& docker @compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose config invalid' }
```

`config --quiet`의 성공은 설정 문법과 필수 변수 확인일 뿐 서비스 준비나 비밀 값의 적절성을 증명하지 않는다. `infra/.env`에 들어 있는 실제 비밀을 출력하는 `config` 명령은 사용하지 않는다. 이 프로젝트의 host 9090·3000 포트가 비어 있는지 확인한다. Backend `8080/8081`, PostgreSQL `5432`, AI Service `8000`은 host에 publish되지 않는다.

## 2. 기본 시작과 인증 거래

기본 Compose는 `postgresql`, `ai-service`, `backend`, `external-risk-mock`, `prometheus`, `grafana`, `alertmanager`, `alertmanager-webhook`의 8개 서비스를 사용한다. PowerShell에서 같은 `$compose`로 이미지를 빌드하고 서비스를 시작한다.

```powershell
& docker @compose build backend ai-service
if ($LASTEXITCODE -ne 0) { throw 'Image build failed' }
& docker @compose up -d --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) { throw 'Compose services did not become healthy' }
& docker @compose ps
if ($LASTEXITCODE -ne 0) { throw 'Compose status failed' }
$pg = & docker @compose ps -q postgresql
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($pg)) { throw 'PostgreSQL container not found' }
$pg
$db = docker inspect $pg | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $null -eq $db) { throw 'PostgreSQL inspect failed' }
$db.Mounts | Where-Object Destination -eq '/var/lib/postgresql/data' | Select-Object Type,Name,Destination
```

8개 서비스의 `healthy` 상태와 위 PostgreSQL ID·mount 이름을 이 프로젝트의 독립 실행 기록에 적는다. 기본 DB의 Rule 4개는 DRAFT seed이므로 [기존 runbook 4절의 로컬 Rule 발행](./prometheus-local-scrape-runbook.md#4-로컬-rule-집합-발행)을 수행해 `PUBLISHED`와 활성 4/4를 확인한다. 이미 발행된 DB에는 one-shot을 반복하지 않는다. 기본 Compose의 거래 POST는 JWT가 없으면 `401`이다. 여기까지가 **기본 Compose** 검증이며 `201 → 201 → 409`나 인증된 AI 리포트의 증거가 아니다.

거래와 그 업무 Meter를 확인할 때만 [로컬 JWT fixture overlay](./local-jwt-auth-e2e-runbook.md#5-topology와-lifecycle)를 추가한다. `infra/compose.local-jwt-e2e.yml`을 두 번째 Compose 파일로 넣은 merged config를 `config --quiet`로 검사한다. 같은 프로젝트를 전환하려면 `network_mode: service:backend`인 `external-risk-mock`을 먼저 stop/remove하고 Backend를 재생성한 뒤 두 sidecar를 시작한다. **전후 PostgreSQL 컨테이너 ID와 `/var/lib/postgresql/data` mount 이름이 같다는 것을 확인한 경우에만** 기존 Rule DB의 연속성을 인정한다. 달라졌거나 보존 판단이 어렵다면 여기서 중단하고 별도 고유 프로젝트에서 인증 절차를 진행한다. 전환 명령과 token 취급은 [관측 runbook 5절](./prometheus-local-scrape-runbook.md#5-1차-traffic으로-meter-등록)을 따른다. 그 절의 `TRANSACTION_INTAKE` fixture token은 메모리와 stdin에만 전달한다. 새 요청 `201`, 완료된 동일 요청 재전송 `201`, 같은 멱등 키의 다른 요청 `409 IDEMPOTENCY_KEY_CONFLICT`를 확인한다. [6절의 현재 scrape 확인](./prometheus-local-scrape-runbook.md#6-기준-scrape-확인과-2차-traffic)과 [7절의 recording rule 조회](./prometheus-local-scrape-runbook.md#7-recording-rule-query와-raw-대조)는 인증 거래 후의 별도 확인이다.

Git Bash의 overlay 전환이 **성공한 뒤에만** 처음 사용한 PowerShell 세션에서 다음처럼 종료용 Compose 인자를 갱신한다. 출력된 전체 문자열을 독립 실행 기록의 파일 목록에 추가한다. 전환이 실패했다면 추가하지 않고 원인을 조사한다.

```powershell
if ($compose.Count -ne 7 -or $compose[2] -ne $project) { throw 'Unexpected base Compose arguments' }
$compose += @('-f', 'infra/compose.local-jwt-e2e.yml')
$compose -join ' '
```

기존 4~7절은 **Git Bash의 동일 세션**에서 실행한다. 2절의 환경 변수 준비 방식을 따르되 Compose 배열에는 PowerShell 세션과 별도로 기록한 정확한 프로젝트명을 `-p`로 추가한다. Rule 발행까지는 `compose=(docker compose -p <기록한-프로젝트명> --env-file infra/.env -f infra/compose.yml)` 형태를 사용하고, 인증 거래부터는 [5절](./prometheus-local-scrape-runbook.md#5-1차-traffic으로-meter-등록)에 따라 JWT overlay 파일도 추가한다. 꺾쇠 안의 설명은 실제 기록값으로 바꾼다. 긴 발행·거래 명령을 이 문서에서 일부만 복사해 실행하지 않는다.

Prometheus UI는 `http://127.0.0.1:9090`에서 Backend target `UP`을, Grafana UI는 `http://127.0.0.1:3000`에서 datasource와 `FinGuardOps Local Observability` dashboard를 확인한다. 인증 거래 이후 업무 sample도 확인할 수 있다. [기존 runbook 11절](./prometheus-local-scrape-runbook.md#11-grafana-local-dashboard-검증)의 provisioning·query 기준만 참고한다. 그 절의 별도 fresh-project 시작·정리 명령을 이 프로젝트에 적용하지 않는다. commit, 프로젝트명, 기본 config·health·Rule·target·Grafana 결과와 **별도 인증 거래**의 `201/201/409` 결과를 구분해 비민감 증거로 기록한다. 비밀, token, 실제 고객 정보, payload 원문은 기록하지 않는다.

## 3. Outbox 읽기 전용 확인

기본 Compose에서 Kafka는 비활성이다. 새 AI 리포트 실행이 있으면 outbox가 `PENDING`으로 남을 수 있지만, 인증 합성 거래 `201/201/409`만으로 AI 실행이나 outbox 행이 생겼다고 가정하지 않는다. 다음 조회는 기존 [outbox 상태·최고 연령 계산](../../backend/src/main/java/com/aifds/backend/outbox/OutboxRepository.java)과 같은 `ai_report_outbox`의 `status`·`created_at`을 읽는다. DB/user 이름을 `infra/.env`에서 바꿨다면 `psql`의 두 인자를 일치시킨다.

```powershell
$outboxSql = "SELECT status,count(*) AS records,COALESCE(EXTRACT(EPOCH FROM now()-min(created_at))::bigint,0) AS oldest_seconds FROM ai_report_outbox GROUP BY status ORDER BY status;"
& docker @compose exec -T postgresql psql -U finguardops -d finguardops -v ON_ERROR_STOP=1 -c $outboxSql
if ($LASTEXITCODE -ne 0) { throw 'Read-only outbox query failed' }
```

결과가 비어 있으면 현재 DB에 outbox 행이 없는 것이다. Kafka를 켠 **별도 실험**에서는 [Kafka runbook 4절](./local-kafka-ai-report-runbook.md#4-outbox소비최종-조회지표)의 실행별 SQL, `finguardops_kafka_outbox_records{status}`, 최고 연령과 lag·DLQ를 사용한다. Lag `-1`은 미확인이다. `PUBLISHED`도 자동 삭제되지 않는다. 여기서는 삭제 SQL, 자동 정리, 경고 임계값을 정하지 않는다.

## 4. 종료와 보존 경계

종료 전에 합성 결과와 필요한 DB 증거를 확인한다. 기본 [Compose](../../infra/compose.yml)에는 **PostgreSQL named volume 선언이 없다**. PostgreSQL 이미지의 `/var/lib/postgresql/data`에는 익명 volume이 붙을 수 있다. 일반 `compose down`은 DB 컨테이너를 제거하지만 익명 volume은 물리적으로 남기며 다음 `up`에 자동으로 다시 연결하지 않는다. `down` 후 기본 경로에서 DB 데이터에 접근할 수 없다는 것과 물리적 삭제는 다르다. 보존 또는 별도 삭제 필요성은 해당 volume 이름과 소유 관계를 기록한 후 결정한다. 이 절차에서는 익명 volume을 삭제하지 않는다. DB 보존이 필요하거나 내용을 판별할 수 없으면 `down`하지 않는다.

종료 대상은 **시작 때 세션 변수와 별도로 기록한** 프로젝트명·저장소 경로·Compose 파일 인자 전체와 대조한다. `$project`와 `$compose[2]`끼리만 비교해서는 두 값이 함께 잘못된 경우를 잡지 못한다. 인증 overlay로 전환했다면 추가한 `-f infra/compose.local-jwt-e2e.yml`도 독립 기록에 적고 종료 명령의 `$compose`에 포함한다. 아래 예시는 두 경로를 모두 검사한다. `Read-Host`에는 세션 변수를 복사하지 말고 독립 기록에서 값을 입력한다. 컨테이너 label과 DB mount까지 확인하고, 한 항목이라도 다르면 중단한다.

```powershell
if ([string]::IsNullOrWhiteSpace($project) -or $compose.Count -notin @(7,9) -or
    $compose[2] -ne $project -or $compose[4] -ne 'infra/.env' -or
    $compose[6] -ne 'infra/compose.yml') { throw 'Unexpected Compose arguments' }
if ($compose.Count -eq 9 -and ($compose[7] -ne '-f' -or $compose[8] -ne 'infra/compose.local-jwt-e2e.yml')) { throw 'Unexpected overlay argument' }
$recordedProject = Read-Host 'Project name from independent run record'
$recordedArgs = Read-Host 'Full compose arguments from independent run record'
$recordedRoot = Read-Host 'Repository root from independent run record'
if ($recordedProject -ne $project -or $recordedArgs -ne ($compose -join ' ') -or
    $recordedRoot -ne (Get-Location).Path) { throw 'Independent run record does not match' }
$baseFile = (Resolve-Path -LiteralPath 'infra/compose.yml').Path
$envFile = (Resolve-Path -LiteralPath 'infra/.env').Path
$allowedFiles = @($baseFile)
if ($compose.Count -eq 9) { $allowedFiles += ($baseFile + ',' + (Resolve-Path -LiteralPath 'infra/compose.local-jwt-e2e.yml').Path) }
$containers = @(docker ps -a --filter "label=com.docker.compose.project=$project" -q)
if ($LASTEXITCODE -ne 0 -or $containers.Count -eq 0) { throw 'Owned containers not found' }
$services = @()
foreach ($id in $containers) {
  $item = docker inspect $id | ConvertFrom-Json
  if ($LASTEXITCODE -ne 0 -or $null -eq $item) { throw 'Container inspect failed' }
  if ($item.Config.Labels.'com.docker.compose.project' -ne $recordedProject -or
      $item.Config.Labels.'com.docker.compose.project.config_files' -notin $allowedFiles -or
      $item.Config.Labels.'com.docker.compose.project.environment_file' -ne $envFile) {
    throw 'Container ownership labels differ from the run record'
  }
  $services += $item.Config.Labels.'com.docker.compose.service'
  [pscustomobject]@{ Name=$item.Name; Project=$item.Config.Labels.'com.docker.compose.project'; Service=$item.Config.Labels.'com.docker.compose.service'; ConfigFiles=$item.Config.Labels.'com.docker.compose.project.config_files'; EnvFile=$item.Config.Labels.'com.docker.compose.project.environment_file' }
}
$expected = @('postgresql','ai-service','backend','external-risk-mock','prometheus','grafana','alertmanager','alertmanager-webhook')
if ($compose.Count -eq 9) { $expected += 'local-jwt-fixture' }
if (@(Compare-Object ($expected | Sort-Object) ($services | Sort-Object)).Count -ne 0) { throw 'Unexpected service set' }
$networks = @(docker network ls -q --filter "label=com.docker.compose.project=$project")
if ($LASTEXITCODE -ne 0 -or $networks.Count -eq 0) { throw 'Owned networks not found' }
foreach ($id in $networks) {
  $network = docker network inspect $id | ConvertFrom-Json
  if ($LASTEXITCODE -ne 0 -or $null -eq $network) { throw 'Network inspect failed' }
  if ($network.Labels.'com.docker.compose.project' -ne $recordedProject) { throw 'Network ownership label differs' }
  [pscustomobject]@{ Network=$network.Name; Project=$network.Labels.'com.docker.compose.project' }
}
$pg = & docker @compose ps -q postgresql
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($pg)) { throw 'PostgreSQL container not found' }
$db = docker inspect $pg | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $null -eq $db) { throw 'PostgreSQL inspect failed' }
$dbMount = @($db.Mounts | Where-Object Destination -eq '/var/lib/postgresql/data')
if ($db.Config.Labels.'com.docker.compose.service' -ne 'postgresql' -or $dbMount.Count -ne 1 -or
    [string]::IsNullOrWhiteSpace($dbMount[0].Name)) { throw 'Unexpected DB owner or mount' }
$recordedPg = Read-Host 'PostgreSQL container ID from independent run record'
$recordedMount = Read-Host 'DB mount name from independent run record'
if ($pg -ne $recordedPg -or $dbMount[0].Name -ne $recordedMount) { throw 'DB identity differs from the run record' }
$pg
$dbMount | Select-Object Type,Name,Destination
$scopeTables = @(
  'financial_transaction','behavior_event','detection_result','detection_evidence',
  'fraud_case','case_transaction','investigation_note','audit_log',
  'idempotency_record','idempotency_recovery_audit_log',
  'ai_report_request','ai_report_execution','ai_report','provider_call_attempt','ai_report_outbox',
  'fraud_rule','rule_version','transaction_intake_maintenance_gate'
)
$dbScopeSql = ($scopeTables | ForEach-Object { "SELECT '$_',count(*) FROM $_" }) -join ' UNION ALL '
$dbCounts = @(& docker @compose exec -T postgresql psql -U finguardops -d finguardops -v ON_ERROR_STOP=1 -At -c $dbScopeSql)
if ($LASTEXITCODE -ne 0 -or $dbCounts.Count -ne $scopeTables.Count) { throw 'Read-only DB scope query failed or incomplete' }
$countedTables = @($dbCounts | ForEach-Object {
  if ($_ -notmatch '^([a-z_]+)\|([0-9]+)$') { throw 'Invalid DB count row' }
  $Matches[1]
})
if (@(Compare-Object ($scopeTables | Sort-Object) ($countedTables | Sort-Object)).Count -ne 0) {
  throw 'DB count table set incomplete'
}
$dbCounts
# 거래 ID는 화면에 출력하지 않고 앞서 기록한 합성 ID 목록과 메모리에서만 대조한다.
$syntheticInput = Read-Host 'Comma-separated synthetic transaction IDs from this run (empty if none)'
$syntheticIds = @($syntheticInput -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$actualIds = @(& docker @compose exec -T postgresql psql -U finguardops -d finguardops -v ON_ERROR_STOP=1 -At -c 'SELECT transaction_id::text FROM financial_transaction ORDER BY transaction_id')
if ($LASTEXITCODE -ne 0) { throw 'Read-only transaction ID query failed' }
if (@(Compare-Object ($syntheticIds | Sort-Object) ($actualIds | Sort-Object)).Count -ne 0) {
  throw 'Transactions differ from the recorded synthetic IDs; retain the project for investigation'
}
Write-Output "Recorded synthetic transaction IDs matched: $($actualIds.Count)"
```

이 조회 블록은 `down`을 포함하지 않는다. 실제 [Flyway migration](../../backend/src/main/resources/db/migration/)에 있는 업무·운영 데이터 테이블 18개를 집계한다. `flyway_schema_history`는 migration 이력이므로 이 데이터 판단 목록과 분리한다. `fraud_rule`·`rule_version`의 seed·발행 Rule과 maintenance gate는 합성 거래 행이 아니며 0건일 필요가 없다. 거래의 탐지·감사·멱등 행과 AI 관련 행도 모두 0건이어야 하는 것은 아니다. 출력 건수와 기록한 합성 ID를 대조하고, 연결 행·Rule·AI·outbox 등을 포함해 **보존할 업무 데이터가 없는지 운영자가 판단**한다. 소유 또는 보존 필요성이 불분명하면 여기서 멈추고 아래 종료 블록을 실행하지 않는다. `psql`의 DB/user를 `infra/.env`에서 변경했다면 위 조회 인자도 일치시킨다.

보존이 불필요하다고 명시적으로 판단한 경우에만 **별도 PowerShell 입력**으로 다음 블록을 실행한다. 확인 문구는 독립 기록의 프로젝트명을 직접 입력한다. 종료 직전에도 Compose 인자와 Docker 소유 label, DB ID·mount를 다시 대조한다.

```powershell
if ($project -ne $recordedProject -or $recordedArgs -ne ($compose -join ' ') -or
    $recordedRoot -ne (Get-Location).Path) { throw 'Independent run record changed' }
$confirm = Read-Host "After reviewing DB ownership and retention, type DOWN $recordedProject"
if ($confirm -cne "DOWN $recordedProject") { throw 'No explicit down decision' }
$currentIds = @(docker ps -a --filter "label=com.docker.compose.project=$recordedProject" -q)
if ($LASTEXITCODE -ne 0 -or @(Compare-Object $containers $currentIds).Count -ne 0) { throw 'Container set changed before down' }
foreach ($id in $currentIds) {
  $item = docker inspect $id | ConvertFrom-Json
  if ($LASTEXITCODE -ne 0 -or $null -eq $item) { throw 'Container inspect failed before down' }
  if ($item.Config.Labels.'com.docker.compose.project' -ne $recordedProject -or
      $item.Config.Labels.'com.docker.compose.project.config_files' -notin $allowedFiles -or
      $item.Config.Labels.'com.docker.compose.project.environment_file' -ne $envFile) {
    throw 'Container ownership changed before down'
  }
}
$currentNetworks = @(docker network ls -q --filter "label=com.docker.compose.project=$recordedProject")
if ($LASTEXITCODE -ne 0 -or @(Compare-Object $networks $currentNetworks).Count -ne 0) { throw 'Network set changed before down' }
foreach ($id in $currentNetworks) {
  $network = docker network inspect $id | ConvertFrom-Json
  if ($LASTEXITCODE -ne 0 -or $null -eq $network -or
      $network.Labels.'com.docker.compose.project' -ne $recordedProject) { throw 'Network ownership changed before down' }
}
$currentPg = & docker @compose ps -q postgresql
if ($LASTEXITCODE -ne 0 -or $currentPg -ne $recordedPg) { throw 'PostgreSQL container changed before down' }
$currentDb = docker inspect $currentPg | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $null -eq $currentDb) { throw 'PostgreSQL inspect failed before down' }
$currentMount = @($currentDb.Mounts | Where-Object Destination -eq '/var/lib/postgresql/data')
if ($currentMount.Count -ne 1 -or $currentMount[0].Name -ne $recordedMount) { throw 'DB mount changed before down' }
$currentCounts = @(& docker @compose exec -T postgresql psql -U finguardops -d finguardops -v ON_ERROR_STOP=1 -At -c $dbScopeSql)
if ($LASTEXITCODE -ne 0 -or @(Compare-Object $dbCounts $currentCounts).Count -ne 0) { throw 'DB table counts changed before down' }
$currentTransactionIds = @(& docker @compose exec -T postgresql psql -U finguardops -d finguardops -v ON_ERROR_STOP=1 -At -c 'SELECT transaction_id::text FROM financial_transaction ORDER BY transaction_id')
if ($LASTEXITCODE -ne 0 -or @(Compare-Object $actualIds $currentTransactionIds).Count -ne 0) { throw 'Transaction IDs changed before down' }
& docker @compose down
if ($LASTEXITCODE -ne 0) { throw 'Owned project shutdown failed' }
& docker @compose ps --all
if ($LASTEXITCODE -ne 0) { throw 'Post-down Compose status failed' }
$remainingContainers = @(docker ps -a --filter "label=com.docker.compose.project=$project" -q)
if ($LASTEXITCODE -ne 0 -or $remainingContainers.Count -ne 0) { throw 'Owned containers remain after down' }
$remainingNetworks = @(docker network ls -q --filter "label=com.docker.compose.project=$project")
if ($LASTEXITCODE -ne 0 -or $remainingNetworks.Count -ne 0) { throw 'Owned networks remain after down' }
$namedVolumes = @(docker volume ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}')
if ($LASTEXITCODE -ne 0) { throw 'Named volume inspection failed' }
$namedVolumes
$remainingDbVolume = docker volume inspect $recordedMount --format '{{.Name}} {{json .Labels}}'
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($remainingDbVolume)) { throw 'Anonymous DB volume inspection failed' }
$remainingDbVolume
```

`--volumes`, `--remove-orphans`, 전역 prune을 사용하지 않는다. 일반 `down` 뒤 해당 프로젝트의 컨테이너·네트워크 잔여와 Prometheus·Grafana·Alertmanager named volume, 위에서 기록한 익명 DB volume의 잔여를 각각 확인한다. 다른 실험에서 보존한 volume은 이 종료 절차의 소유물이 아니다. 다른 Docker 프로젝트와 기존 DB, Keycloak runner의 image·receipt·secret·volume도 건드리지 않는다. 이름이 비어 있거나 기록과 다르면 명령을 중단한다. 보존 자원의 삭제 시점과 책임자는 아래 5절에서 개별적으로 결정한다. ignored `infra/.env`도 이 절차에서 변경·삭제하지 않는다.

## 5. 보존 volume의 소유권·선택적 정리 판단

이 절은 **이미 종료된 로컬 실험**의 volume을 대상으로 한다. 이 문서나 공개 Issue에는 실제 프로젝트명, volume 이름·ID, 재고 수치, 로컬 절대 경로, 비밀을 남기지 않는다. 정확한 식별자와 근거는 OWNER의 **비공개 실행 기록**에만 남긴다. 이름 접두사나 생성 시각만으로 소유권을 추정하지 않는다. 앞 절의 `down` 판단과 물리적 volume 삭제 판단은 별개다.

OWNER는 **volume 하나당 한 행**으로 아래 표를 비공개 기록에 작성한다. `독립 실행 증거`는 현재 Docker 이름·label을 그대로 베낀 값이 아니라, 실험 당시 별도로 기록한 프로젝트명·Compose 인자·checkout commit·저장소 루트와 해당 실험의 비민감 결과를 뜻한다. 과거 기록이 없으면 `미확정`으로 둔다. 삭제 승인을 받은 **정확히 한 행**만 별도 비공개 JSON 파일의 `rows` 배열에 담고, 이 파일은 Git 추적 범위 밖에 둔다. 여러 행을 담거나 다른 파일의 값을 합쳐 입력하지 않는다.

| 정확한 volume 이름·ID | Compose project·volume label | 생성 시각 | 독립 실행 증거·과거 mount | 현재 참조 컨테이너 ID | 보존 필요 여부·근거 | OWNER 개별 삭제 판단·승인 시각 |
| --- | --- | --- | --- | --- | --- | --- |
| 비공개 기록에 기입 | 비공개 기록에 기입/없음 | 비공개 기록에 기입 | 비공개 기록에 기입/미확정 | 비공개 기록에 기입/없음 | 보존/불필요/미확정 | 보류/개별 삭제 승인 |

익명 PostgreSQL volume은 Compose 소유 label이 없을 수 있다. 과거 PostgreSQL 컨테이너의 `/var/lib/postgresql/data` mount와 **정확한 이름**의 연결, Rule·합성 거래·탐지·사건·감사·AI·outbox 등 데이터의 보존 필요 여부가 모두 확인되기 전에는 삭제 후보에서 제외한다. 컨테이너가 이미 없어 mount를 재확인할 수 없거나 DB를 열지 않고 내용 판단이 불가능하면 **보존 보류**다. 이 판단을 위해 DB를 기동하거나 volume을 mount하지 않는다. 공식 Keycloak runner 자원은 [Keycloak runbook의 Cleanup](./local-keycloak-auth-e2e-runbook.md)을 따르고 여기서 제외한다. 다른 프로젝트, 참조 중인 volume, ignored `infra/.env`도 제외한다.

비공개 JSON의 단일 행에는 문자열 `volumeName`, `composeProject`, `composeVolume`, `createdAt`, `runRecordProject`, `runRecordRoot`, `runRecordComposeArgs`, `runRecordCommit`, `independentEvidence`, `retentionReason`, `owner`, `approvedAt`, `retentionDecision`, `ownerDecision`과 배열 `recordedReferences`를 둔다. `runRecordRoot`와 `runRecordComposeArgs`에는 실제 실행 당시의 값을 비공개로 기록한다. `recordedReferences`는 참조가 없는 경우에도 빈 배열로 명시한다. 삭제 가능 상태는 `retentionDecision=NOT_REQUIRED`, `ownerDecision=DELETE_APPROVED`, 참조 배열이 비어 있고 나머지 근거가 모두 있는 경우뿐이다. 이 필드의 내용과 승인 주체의 진위는 OWNER가 원본 실행 기록과 직접 대조한다. 스크립트의 문자열 검사는 그 증거의 진위를 대신하지 않는다.

다음 PowerShell은 저장소 루트에서 **개별 named volume 하나**의 비공개 승인 파일을 읽고 Docker 재고를 조회한다. `Invoke-VolumeDecision`을 `-Delete` 없이 호출하면 읽기 전용 점검만 한다. 나중에 별도 삭제 승인이 난 뒤 `-Delete`로 호출해도 함수가 **처음부터 동일한 단일 승인 행과 전체 재고를 다시 검증**한다. 앞선 점검의 세션 변수나 출력은 삭제 권한이 아니다. 파일 경로·실제 식별자·조회 출력은 비공개 운영 세션에만 둔다. 아래 블록을 정의한 뒤 비공개 파일 경로를 `Read-Host`로 받아 `Invoke-VolumeDecision -ApprovalRecord $privateApprovalPath`를 실행한다. 삭제를 별도로 승인받은 경우에만 같은 함수에 `-Delete`를 추가한다.

```powershell
function Read-VolumeApproval([object]$approvalPath) {
  if ($approvalPath -isnot [string] -or [string]::IsNullOrWhiteSpace($approvalPath)) { throw 'Approval path must be one string' }
  $resolved = (Resolve-Path -LiteralPath $approvalPath -ErrorAction Stop).Path
  $repositoryRoot = (Resolve-Path -LiteralPath '.' -ErrorAction Stop).Path.TrimEnd([char[]]@('\','/'))
  if ($resolved.StartsWith($repositoryRoot + [IO.Path]::DirectorySeparatorChar,
      [StringComparison]::OrdinalIgnoreCase)) { throw 'Private approval file must be outside the repository' }
  if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) { throw 'Approval file is missing' }
  $raw = Get-Content -LiteralPath $resolved -Raw -Encoding utf8 -ErrorAction Stop
  if ([string]::IsNullOrWhiteSpace($raw)) { throw 'Approval file is empty' }
  $document = $raw | ConvertFrom-Json -ErrorAction Stop
  if ($null -eq $document -or $document.rows -isnot [array] -or $document.rows.Count -ne 1) {
    throw 'Approval record must contain exactly one row'
  }
  $row = $document.rows[0]
  if ($null -eq $row) { throw 'Approval row is missing' }
  $fields = @('volumeName','composeProject','composeVolume','createdAt','runRecordProject',
    'runRecordRoot','runRecordComposeArgs','runRecordCommit','independentEvidence',
    'retentionReason','owner','approvedAt','retentionDecision','ownerDecision')
  foreach ($field in $fields) {
    if ($row.$field -isnot [string] -or [string]::IsNullOrWhiteSpace($row.$field)) {
      throw "Approval field missing or not a string: $field"
    }
  }
  if ($row.recordedReferences -isnot [array] -or $row.recordedReferences.Count -ne 0 -or
      $row.retentionDecision -cne 'NOT_REQUIRED' -or $row.ownerDecision -cne 'DELETE_APPROVED' -or
      $row.runRecordProject -cne $row.composeProject -or
      $row.runRecordRoot -cne (Resolve-Path -LiteralPath '.').Path -or
      $row.runRecordCommit -cnotmatch '^[0-9a-fA-F]{40}$' -or
      $row.runRecordComposeArgs -cnotmatch 'infra[/\\]compose\.yml' -or
      $row.composeProject -cnotmatch '^finguardops-' -or
      $row.composeProject -match 'keycloak' -or $row.composeVolume -match 'keycloak' -or
      $row.runRecordComposeArgs -match 'compose\.keycloak') {
    throw 'Independent run, retention, ownership or OWNER approval is not established'
  }
  [pscustomobject]@{ Row=$row; Raw=$raw }
}

function Get-VolumeInventory {
  $names = @(docker volume ls -q)
  if ($LASTEXITCODE -ne 0 -or $names.Count -eq 0) { throw 'Volume list failed or empty' }
  $volumes = foreach ($name in ($names | Sort-Object)) {
    $raw = & docker volume inspect $name --format '{{json .}}'
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($raw)) { throw 'Volume inspect failed' }
    $item = $raw | ConvertFrom-Json -ErrorAction Stop
    if ($null -eq $item -or $item.Name -ne $name) { throw 'Volume identity changed' }
    [pscustomobject]@{
      Name = $item.Name
      Created = $item.CreatedAt
      Project = $item.Labels.'com.docker.compose.project'
      ComposeVolume = $item.Labels.'com.docker.compose.volume'
      Labels = ($item.Labels | ConvertTo-Json -Compress -Depth 5 -ErrorAction Stop)
    }
  }
  $ids = @(docker ps -aq --no-trunc)
  if ($LASTEXITCODE -ne 0) { throw 'Container list failed' }
  $mounts = foreach ($id in ($ids | Sort-Object)) {
    $raw = & docker inspect $id --format '{{json .}}'
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($raw)) { throw 'Container inspect failed' }
    $item = $raw | ConvertFrom-Json -ErrorAction Stop
    if ($null -eq $item -or $item.Id -ne $id) { throw 'Container identity changed' }
    if ($null -eq $item.PSObject.Properties['Mounts'] -or $item.Mounts -isnot [array]) {
      throw 'Container Mounts field is missing or malformed'
    }
    foreach ($mount in $item.Mounts) {
      if ($null -eq $mount -or $mount.Type -isnot [string]) { throw 'Container mount entry is malformed' }
      if ($mount.Type -eq 'volume') {
        if ($mount.Name -isnot [string] -or [string]::IsNullOrWhiteSpace($mount.Name)) {
          throw 'Volume mount name is missing'
        }
        [pscustomobject]@{ Volume=$mount.Name; Container=$item.Id }
      }
    }
  }
  $networks = @(docker network ls -q)
  if ($LASTEXITCODE -ne 0 -or $networks.Count -eq 0) { throw 'Network list failed or empty' }
  [pscustomobject]@{ Volumes=@($volumes); Containers=@($ids | Sort-Object);
    Networks=@($networks | Sort-Object); Mounts=@($mounts) }
}

function Assert-ApprovedVolume($row, $inventory) {
  if ($row.volumeName -isnot [string] -or [string]::IsNullOrWhiteSpace($row.volumeName)) {
    throw 'Approved volume name must be one string'
  }
  $target = @($inventory.Volumes | Where-Object Name -CEQ $row.volumeName)
  if ($target.Count -ne 1 -or $target[0].Project -cne $row.composeProject -or
      $target[0].ComposeVolume -cne $row.composeVolume -or
      $target[0].Created -cne $row.createdAt -or
      $target[0].Name -cne ($row.composeProject + '_' + $row.composeVolume)) {
    throw 'Named volume does not match independent approval row'
  }
  if (@($inventory.Mounts | Where-Object Volume -CEQ $row.volumeName).Count -ne 0) {
    throw 'Approved volume has a referencing container'
  }
}

function Invoke-VolumeDecision {
  param([object]$ApprovalRecord, [switch]$Delete)
  $ErrorActionPreference = 'Stop'
  $approval = Read-VolumeApproval $ApprovalRecord
  $row = $approval.Row
  $before = Get-VolumeInventory
  Assert-ApprovedVolume $row $before
  if (-not $Delete) { Write-Output 'One approved named volume passed read-only checks'; return }

  $decision = Read-Host 'From the private OWNER approval, type DELETE followed by its exact volume name'
  if ($decision -cne ('DELETE ' + $row.volumeName)) { throw 'No exact deletion confirmation' }
  $currentApproval = Read-VolumeApproval $ApprovalRecord
  if ($currentApproval.Raw -cne $approval.Raw) { throw 'Private approval row changed before deletion' }
  $current = Get-VolumeInventory
  Assert-ApprovedVolume $currentApproval.Row $current
  if (($current | ConvertTo-Json -Compress -Depth 8 -ErrorAction Stop) -cne
      ($before | ConvertTo-Json -Compress -Depth 8 -ErrorAction Stop)) {
    throw 'Docker inventory changed before deletion'
  }
  $exactName = $currentApproval.Row.volumeName
  if ($exactName -isnot [string] -or $exactName -cne $row.volumeName) { throw 'Approved name changed' }
  & docker volume rm $exactName
  if ($LASTEXITCODE -ne 0) { throw 'Exact volume removal failed; stop' }
  $after = Get-VolumeInventory
  if (@($after.Volumes | Where-Object Name -CEQ $exactName).Count -ne 0) {
    throw 'Removed volume still listed; stop'
  }
  $remainingBefore = @($before.Volumes | Where-Object Name -CNE $exactName)
  if ((($remainingBefore | ConvertTo-Json -Compress -Depth 8 -ErrorAction Stop) -cne
       ($after.Volumes | ConvertTo-Json -Compress -Depth 8 -ErrorAction Stop)) -or
      (($before.Containers | ConvertTo-Json -Compress -ErrorAction Stop) -cne
       ($after.Containers | ConvertTo-Json -Compress -ErrorAction Stop)) -or
      (($before.Networks | ConvertTo-Json -Compress -ErrorAction Stop) -cne
       ($after.Networks | ConvertTo-Json -Compress -ErrorAction Stop)) -or
      (($before.Mounts | ConvertTo-Json -Compress -Depth 5 -ErrorAction Stop) -cne
       ($after.Mounts | ConvertTo-Json -Compress -Depth 5 -ErrorAction Stop))) {
    throw 'Other Docker resources changed; stop further deletion and investigate'
  }
}

$privateApprovalPath = Read-Host 'Private single-row OWNER approval JSON path'
Invoke-VolumeDecision -ApprovalRecord $privateApprovalPath # 읽기 전용 점검
```

별도 OWNER 삭제 승인을 확인한 뒤에만 `Invoke-VolumeDecision -ApprovalRecord $privateApprovalPath -Delete`를 **새 호출**로 실행한다. 이 함수는 승인 파일의 **단일 행**에서 확인 문구·최종 대조 대상·삭제 인자를 다시 도출한다. A를 읽기 전용 점검한 뒤 세션 변수를 B로 바꾸더라도 삭제 호출은 비공개 파일의 승인 행으로 전체 검증을 다시 수행한다. 파일이 바뀌거나 둘 이상의 행이 있으면 중단한다. 조회 명령 실패·빈 목록·label 누락·생성 시각 불일치·새 컨테이너 참조·`Mounts` 필드 누락이나 형식 오류도 삭제 전에 중단한다. 이 절차는 실험별 image와 익명 volume을 제거하지 않는다. `down --volumes`, `docker volume prune`, `docker system prune`, 이름 패턴 일괄 삭제는 사용하지 않는다. 앞서 정리된 컨테이너·네트워크를 다시 정리 대상으로 만들지 않는다. 삭제 후 대조에서 다른 변경이 나타나면 추가 삭제를 중단하고 OWNER에게 보고한다.

## 별도 경로 선택

| 목적 | 절차 |
| --- | --- |
| 기본 설정·health·Rule·Prometheus·Grafana와 별도 fixture 인증 합성 거래 | 이 문서와 [관측 runbook 4~7절](./prometheus-local-scrape-runbook.md#4-로컬-rule-집합-발행). 인증된 AI 리포트 결과를 뜻하지 않는다. |
| local/dev Keycloak 로그인·브라우저 Gate | [Keycloak runbook](./local-keycloak-auth-e2e-runbook.md)의 공식 runner와 `Cleanup`. 이 문서의 raw Compose 정리를 적용하지 않는다. |
| Kafka 인증 AI 리포트·outbox 발행·소비 | [Kafka runbook](./local-kafka-ai-report-runbook.md)의 별도 프로젝트와 Kafka·로컬 JWT overlay. |
| 호스트 Qwen 실제 생성·저장 | [Kafka/Qwen runbook 6절](./local-kafka-ai-report-runbook.md#6-별도-실제-qwen-인증-실행)의 별도 프로젝트와 Qwen·로컬 JWT overlay. Kafka 경로의 통과를 뜻하지 않는다. |

Loki와 OpenTelemetry는 현재 기본 Compose의 로컬 수집 경로가 아니다.
