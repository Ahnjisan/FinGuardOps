# Prometheus 로컬 scrape runbook

## 1. 목적과 경계

이 runbook은 Issue #196의 로컬 Docker Compose 검증 절차이다. PostgreSQL, AI
Service, External Risk fixture, Backend와 Prometheus를 함께 실행하고 Backend의
`/actuator/prometheus`를 실제 scrape한다. production 배포, 인증·TLS, recording
rule·alert·Grafana, HA와 장기 보존을 제공하지 않는다. loopback bind와 Docker
network 분리도 인증을 대신하지 않는다.

## 2. 사전 조건과 설정 검증

Docker Desktop과 Compose v2를 준비하고 저장소 루트에서 실행한다. 실제 `.env`를
만들거나 commit하지 않고 현재 PowerShell process에만 로컬 DB password를 둔다.

```powershell
$env:POSTGRES_PASSWORD = "replace-with-a-local-only-password"
$compose = @("compose", "-f", "infra/compose.yml")
docker @compose config --quiet
docker run --rm --entrypoint /bin/promtool `
  -v "${PWD}/infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro" `
  "prom/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0" `
  check config /etc/prometheus/prometheus.yml
```

## 3. 빌드와 시작

```powershell
docker @compose build backend ai-service
docker @compose up -d postgresql ai-service
docker @compose up -d backend
docker @compose up -d external-risk-mock
docker @compose ps
```

PostgreSQL·AI Service·Backend는 internal application network를 사용하고 Backend와
Prometheus는 internal observability network를 사용한다. Prometheus만 host UI publish용
`prometheus-ui` bridge에도 연결한다. Backend가 PostgreSQL과 AI Service 준비 뒤 시작하면
External Risk fixture를 시작한다. fixture는 `network_mode: service:backend`로 Backend
network namespace를 공유하고 `127.0.0.1:8001`에만 bind한다. Backend는 같은 loopback
주소로 fixture를 호출한다. fixture healthcheck는 Mock `8001`과 Backend application `8080`
양쪽 health를 확인한다. fixture가 `healthy`인지 확인한 뒤 Prometheus를 시작한다.

```powershell
docker @compose up -d prometheus
docker @compose ps
```

모든 service가 `healthy`인지 확인한다. host에는 Prometheus UI
`http://127.0.0.1:9090`만 노출된다. Backend application `8080`, management `8081`,
PostgreSQL `5432`, AI Service `8000`, External Risk `8001`은 host에 publish되지 않는다.
업무 traffic도 host에서 Backend로 직접 보내지 않고 같은 network namespace의 fixture에서
`127.0.0.1:8080`으로 보낸다. loopback sidecar는 기존 non-production plain HTTP 제한을
보존하는 로컬 fixture이며 production External Risk Provider 정책이나 인증·TLS를 대체하지
않는다.

## 4. 로컬 Rule 집합 발행

V5 seed는 DRAFT이므로 실제 거래 전에 기존 local 전용 one-shot 경계로 발행하고
`effectiveFrom` 이후까지 기다린다. production profile에서는 실행하지 않는다.

```powershell
$effectiveFromInstant = [DateTimeOffset]::UtcNow.AddMinutes(1)
$effectiveFrom = $effectiveFromInstant.ToString("yyyy-MM-ddTHH:mm:ssZ")
docker @compose run --rm --no-deps `
  -e SPRING_PROFILES_ACTIVE=local,rule-v1-default-publication `
  -e FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false `
  backend `
  --spring.main.web-application-type=none `
  --finguardops.rule-v1-default-publication.enabled=true `
  --finguardops.rule-v1-default-publication.confirmation=PUBLISH_RULE_V1_DEFAULT_V1 `
  "--finguardops.rule-v1-default-publication.effective-from=$effectiveFrom"
$waitSeconds = [Math]::Max(
  0,
  [Math]::Ceiling(($effectiveFromInstant - [DateTimeOffset]::UtcNow).TotalSeconds) + 1
)
Start-Sleep -Seconds $waitSeconds
```

## 5. 실제 업무 traffic 생성

첫 요청은 정상 접수·`RECEIVED`·terminal 결과, External Risk와 Rule 분석을
발생시킨다. 두 번째는 동일 payload replay, 세 번째는 같은 key의 다른 payload
conflict를 발생시킨다. 현재 v2 snapshot 계약에 따라 HTTP status가 차례로
`201`, `201`, `409`인지 확인한다.

```powershell
$trafficCode = @'
import json
import urllib.error
import urllib.request
import uuid
from datetime import UTC, datetime

key = f"local-prom-{uuid.uuid4().hex}"
payload = {
    "transactionId": str(uuid.uuid4()),
    "transactionType": "ACCOUNT_TRANSFER",
    "amount": "10000",
    "currencyCode": "KRW",
    "occurredAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
    "externalCustomerRef": "local-customer-ref",
    "senderAccountRef": "local-sender-ref",
    "recipientAccountRef": "local-recipient-ref",
    "channel": "MOBILE_BANKING",
    "deviceRef": "local-device-ref",
}

def send(body):
    request = urllib.request.Request(
        "http://127.0.0.1:8080/api/v1/transactions",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Idempotency-Key": key},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            response.read()
            return response.status
    except urllib.error.HTTPError as error:
        error.read()
        return error.code

statuses = [send(payload), send(payload)]
payload["amount"] = "10001"
statuses.append(send(payload))
if statuses != [201, 201, 409]:
    raise RuntimeError(f"Unexpected transaction statuses: {statuses}")
print(f"transaction statuses={statuses}")
'@
$traffic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($trafficCode))
docker @compose exec -T external-risk-mock python -c `
  "import base64;exec(base64.b64decode('$traffic'))"
```

## 6. target과 Meter query

최소 두 scrape interval을 기다린 뒤 target과 Counter 7개·Timer count 3개가 비어
있지 않은지 확인한다. Meter는 traffic 전에는 lazy 등록되지 않을 수 있다.

```powershell
Start-Sleep -Seconds 30
$targets = Invoke-RestMethod http://127.0.0.1:9090/api/v1/targets
if ($targets.data.activeTargets.health -notcontains "up") { throw "Backend target is not UP" }
$metrics = @(
  "finguardops_transaction_intake_outcomes_total",
  "finguardops_transactions_received_total",
  "finguardops_transaction_outcomes_total",
  "finguardops_http_duplicate_requests_total",
  "finguardops_http_idempotency_conflicts_total",
  "finguardops_external_risk_outcomes_total",
  "finguardops_rule_analysis_outcomes_total",
  "finguardops_transaction_processing_duration_seconds_count",
  "finguardops_external_risk_duration_seconds_count",
  "finguardops_rule_analysis_duration_seconds_count"
)
foreach ($metric in $metrics) {
  $encoded = [uri]::EscapeDataString($metric)
  $result = Invoke-RestMethod "http://127.0.0.1:9090/api/v1/query?query=$encoded"
  if ($result.data.result.Count -eq 0) { throw "Empty Prometheus query: $metric" }
}
```

## 7. restart·Backend 재생성과 종료

Prometheus의 단독 restart는 같은 container를 재시작하며 named volume의 TSDB를 유지한다.

```powershell
docker @compose restart prometheus
docker @compose ps
docker volume inspect finguardops-local-observability_prometheus-data
```

`docker compose restart backend`도 기존 Backend container와 network namespace를
재사용한다. 반면 `up --force-recreate backend`는 새 container와 새 namespace를 만든다.
`depends_on.restart: true`는 Compose가 의존 service 변경을 인식하게 하지만 기존 Mock
container를 새 namespace에 재연결한다고 가정하면 안 된다. 안전 절차를 생략한 단독 강제
재생성에서는 복합 healthcheck 때문에 이전 namespace의 Mock이 `healthy`로 남아서는 안 된다.

다음은 stale sidecar 상태를 의도적으로 검증한 직후 안전 순서로 복구하는 절차다. volume을
삭제하지 않는다.

```powershell
docker @compose up -d --force-recreate backend
Start-Sleep -Seconds 10
$mockId = docker @compose ps -q external-risk-mock
$mockHealth = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $mockId
if ($mockHealth -eq "healthy") {
  throw "Stale External Risk Mock remained healthy after Backend recreation"
}

docker @compose stop external-risk-mock
docker @compose rm -f external-risk-mock
docker @compose up -d --force-recreate --wait backend
docker @compose up -d --force-recreate --wait external-risk-mock

$backendNamespace = (docker @compose exec -T backend readlink /proc/self/ns/net).Trim()
$mockNamespace = (docker @compose exec -T external-risk-mock readlink /proc/self/ns/net).Trim()
if ($backendNamespace -ne $mockNamespace) {
  throw "Backend and External Risk Mock network namespaces differ"
}
$mockListener = docker @compose exec -T external-risk-mock `
  python -c "print(any('0100007F:1F41' in line and ' 0A ' in line for line in open('/proc/net/tcp')))"
if ($mockListener -ne "True") { throw "Mock is not listening on 127.0.0.1:8001" }
docker @compose ps
```

복구 뒤 5절의 내부 traffic 명령을 다시 실행해 실제 External Risk lookup과 거래 `201`을
확인한다. 종료는 volume 삭제 옵션 없이 수행한다.

```powershell
docker @compose down
docker @compose ps --all
```

`down`에 `--volumes`를 사용하지 않는다. 업무 container와 세 network는 제거되고
`finguardops-local-observability_prometheus-data` named volume만 유지되어야 한다.
