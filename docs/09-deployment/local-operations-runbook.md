# 기본 로컬 운영 시작과 종료

이 문서는 새 체크아웃에서 **기본 Compose**의 합성 거래와 로컬 관측을 확인하는 짧은 진입점이다. 명령은 저장소 루트에서 실행한다. Rule 발행, 합성 거래, scrape의 검증 명령은 [Prometheus 로컬 scrape runbook](./prometheus-local-scrape-runbook.md)의 해당 절을 그대로 사용한다. 기본 경로에는 인증 공급자, Kafka broker, 실제 Qwen 모델이 포함되지 않는다.

## 1. 준비와 프로젝트 기록

Docker Desktop·Compose v2, PowerShell, Git Bash와 Python 3을 준비한다. [예시 환경 파일](../../infra/.env.example)을 참고해 ignored `infra/.env`에 로컬 전용 `POSTGRES_PASSWORD`, `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD`를 준비한다. 예시 placeholder를 실제 값으로 사용하지 않는다. 값은 문서·명령 인자·화면 출력·증거 기록에 복사하지 않는다. 기존 `infra/.env`는 덮어쓰지 않는다.

아래 PowerShell은 실행할 때마다 고유 Compose 프로젝트명을 만든다. 생성된 이름과 checkout commit을 비민감 실행 기록에 적고, 이후 명령과 Git Bash 검증에서 **같은 이름**을 사용한다. 기존 프로젝트 이름을 재사용하지 않는다.

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

## 2. 시작과 합성 거래

기본 Compose는 `postgresql`, `ai-service`, `backend`, `external-risk-mock`, `prometheus`, `grafana`, `alertmanager`, `alertmanager-webhook`의 8개 서비스를 사용한다. PowerShell에서 같은 `$compose`로 이미지를 빌드하고 서비스를 시작한다.

```powershell
& docker @compose build backend ai-service
if ($LASTEXITCODE -ne 0) { throw 'Image build failed' }
& docker @compose up -d --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) { throw 'Compose services did not become healthy' }
& docker @compose ps
if ($LASTEXITCODE -ne 0) { throw 'Compose status failed' }
```

8개 서비스의 `healthy` 상태를 이 프로젝트 이름과 함께 기록한다. 기본 DB의 Rule 4개는 DRAFT seed이므로 거래 전에 [기존 runbook 4절의 로컬 Rule 발행](./prometheus-local-scrape-runbook.md#4-로컬-rule-집합-발행)을 수행해 `PUBLISHED`와 활성 4/4를 확인한다. 이미 발행된 DB에는 one-shot을 반복하지 않는다. 이어서 [5절의 합성 거래](./prometheus-local-scrape-runbook.md#5-1차-traffic으로-meter-등록)를 실행해 동일 요청 `201 → 201`과 충돌 `409`를 확인한다. [6절의 현재 scrape 확인](./prometheus-local-scrape-runbook.md#6-기준-scrape-확인과-2차-traffic)과 [7절의 recording rule 조회](./prometheus-local-scrape-runbook.md#7-recording-rule-query와-raw-대조)로 과거 sample이 아닌 이번 실행의 수집 결과를 확인한다.

기존 4~7절은 **Git Bash의 동일 세션**에서 실행한다. 2절의 환경 변수 준비 방식을 따르되 Compose 배열에는 위 PowerShell에서 기록한 정확한 프로젝트명을 `-p`로 추가한다. 예를 들어 `$project`가 `finguardops-base-example`이었다면 Git Bash에서 `compose=(docker compose -p finguardops-base-example --env-file infra/.env -f infra/compose.yml)`로 설정한다. 예시 이름을 실제 실행에 재사용하지 않는다. 긴 발행·거래 명령을 이 문서에서 일부만 복사해 실행하지 않는다.

Prometheus UI는 `http://127.0.0.1:9090`에서 Backend target `UP`과 이번 실행의 sample을, Grafana UI는 `http://127.0.0.1:3000`에서 datasource와 `FinGuardOps Local Observability` dashboard를 확인한다. [기존 runbook 11절](./prometheus-local-scrape-runbook.md#11-grafana-local-dashboard-검증)의 provisioning·query 기준만 참고한다. 그 절의 별도 fresh-project 시작·정리 명령을 이 프로젝트에 적용하지 않는다. commit, 프로젝트명, config·health·Rule·`201/201/409`·target·Grafana 결과를 한 실행의 비민감 증거로 기록한다. 비밀, 실제 고객 정보, payload 원문은 기록하지 않는다.

## 3. Outbox 읽기 전용 확인

기본 Compose에서 Kafka는 비활성이다. 새 AI 리포트 실행이 있으면 outbox가 `PENDING`으로 남을 수 있지만, 합성 거래 `201/201/409`만으로 AI 실행이나 outbox 행이 생겼다고 가정하지 않는다. 다음 조회는 기존 [outbox 상태·최고 연령 계산](../../backend/src/main/java/com/aifds/backend/outbox/OutboxRepository.java)과 같은 `ai_report_outbox`의 `status`·`created_at`을 읽는다. DB/user 이름을 `infra/.env`에서 바꿨다면 `psql`의 두 인자를 일치시킨다.

```powershell
$outboxSql = "SELECT status,count(*) AS records,COALESCE(EXTRACT(EPOCH FROM now()-min(created_at))::bigint,0) AS oldest_seconds FROM ai_report_outbox GROUP BY status ORDER BY status;"
& docker @compose exec -T postgresql psql -U finguardops -d finguardops -v ON_ERROR_STOP=1 -c $outboxSql
if ($LASTEXITCODE -ne 0) { throw 'Read-only outbox query failed' }
```

결과가 비어 있으면 현재 DB에 outbox 행이 없는 것이다. Kafka를 켠 **별도 실험**에서는 [Kafka runbook 4절](./local-kafka-ai-report-runbook.md#4-outbox소비최종-조회지표)의 실행별 SQL, `finguardops_kafka_outbox_records{status}`, 최고 연령과 lag·DLQ를 사용한다. Lag `-1`은 미확인이다. `PUBLISHED`도 자동 삭제되지 않는다. 여기서는 삭제 SQL, 자동 정리, 경고 임계값을 정하지 않는다.

## 4. 종료와 보존 경계

종료 전에 합성 결과와 필요한 DB 증거를 확인한다. 기본 [Compose](../../infra/compose.yml)에는 **PostgreSQL named volume이 없다**. 같은 프로젝트를 `down`하면 해당 PostgreSQL 컨테이너의 DB 데이터가 사라진다. DB 보존이 필요하면 `down`하지 말고 별도 보존 결정을 먼저 한다. 아래 명령은 시작 때 기록한 정확한 `$project`와 `$compose`가 유지된 PowerShell 세션에서만 실행한다.

```powershell
if ([string]::IsNullOrWhiteSpace($project) -or $compose.Count -lt 3 -or $compose[2] -ne $project) {
  throw 'Recorded project and Compose target do not match'
}
$project
& docker @compose ps
if ($LASTEXITCODE -ne 0) { throw 'Project identity check failed' }
& docker volume ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}'
if ($LASTEXITCODE -ne 0) { throw 'Project volume inspection failed' }
# 기록한 프로젝트명과 서비스가 일치하고 DB 보존이 불필요할 때만 실행한다.
& docker @compose down
if ($LASTEXITCODE -ne 0) { throw 'Owned project shutdown failed' }
& docker @compose ps --all
```

`--volumes`, `--remove-orphans`, 전역 prune을 사용하지 않는다. 기본 프로젝트의 Prometheus·Grafana·Alertmanager named volume은 남는다. 이번 점검 PC에는 앞선 Kafka 실험의 `finguardops-kafka-349-20261006a`와 `finguardops-kafka-349-20261006b`에 `kafka-data`, `prometheus-data`, `grafana-data`, `alertmanager-data`가 각각 하나씩, 총 8개 보존되어 있다. 새 PC에는 없을 수 있으며 이 절차의 소유물이 아니다. 다른 Docker 프로젝트와 기존 DB, Keycloak runner의 image·receipt·secret·volume도 건드리지 않는다. 이름이 비어 있거나 기록과 다르면 명령을 중단한다. 보존 자원의 삭제 시점과 책임자는 별도로 결정한다. ignored `infra/.env`도 이 절차에서 변경·삭제하지 않는다.

## 별도 경로 선택

| 목적 | 절차 |
| --- | --- |
| 기본 합성 거래·Prometheus·Grafana | 이 문서와 기존 [관측 runbook](./prometheus-local-scrape-runbook.md). 인증된 AI 리포트 결과를 뜻하지 않는다. |
| local/dev Keycloak 로그인·브라우저 Gate | [Keycloak runbook](./local-keycloak-auth-e2e-runbook.md)의 공식 runner와 `Cleanup`. 이 문서의 raw Compose 정리를 적용하지 않는다. |
| Kafka 인증 AI 리포트·outbox 발행·소비 | [Kafka runbook](./local-kafka-ai-report-runbook.md)의 별도 프로젝트와 Kafka·로컬 JWT overlay. |
| 호스트 Qwen 실제 생성·저장 | [Kafka/Qwen runbook 6절](./local-kafka-ai-report-runbook.md#6-별도-실제-qwen-인증-실행)의 별도 프로젝트와 Qwen·로컬 JWT overlay. Kafka 경로의 통과를 뜻하지 않는다. |

Loki와 OpenTelemetry는 현재 기본 Compose의 로컬 수집 경로가 아니다.
