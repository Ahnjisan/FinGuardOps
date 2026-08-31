# Prometheus 로컬 scrape runbook

## 1. 목적과 경계

이 runbook은 Issue #196의 로컬 Docker Compose 검증 절차이다. PostgreSQL, AI
Service, External Risk fixture, Backend와 Prometheus를 함께 실행하고 Backend의
`/actuator/prometheus`를 실제 scrape하고 기존 업무 Meter 기반 recording rule 14개를
평가한다. production 배포·scrape·recording rule, 인증·TLS, alert·Alertmanager·Grafana,
SLA·SLO·운영 임계값, HA와 장기 보존을 제공하지 않는다. completion gap·장기
`IN_PROGRESS` Gauge, `deployment.error_ratio`와 `deployment.latency`도 구현하지 않는다.
loopback bind와 Docker network 분리도 인증을 대신하지 않는다.

## 2. 사전 조건과 설정 검증

Docker Desktop, Compose v2와 Git Bash를 준비하고 저장소 루트의 같은 shell session에서
실행한다. 실제 `.env`를 만들거나 commit하지 않고 현재 shell process에만 로컬 DB
password를 둔다.

```bash
export POSTGRES_PASSWORD="replace-with-a-local-only-password"
compose=(docker compose -f infra/compose.yml)
repo_root="$(pwd -W)"
prometheus_image="prom/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0"

"${compose[@]}" config --quiet
docker run --rm --entrypoint /bin/promtool \
  --mount "type=bind,source=${repo_root}/infra/prometheus/prometheus.yml,target=/etc/prometheus/prometheus.yml,readonly" \
  --mount "type=bind,source=${repo_root}/infra/prometheus/rules,target=/etc/prometheus/rules,readonly" \
  "$prometheus_image" \
  check config /etc/prometheus/prometheus.yml
docker run --rm --entrypoint /bin/promtool \
  --mount "type=bind,source=${repo_root}/infra/prometheus/rules,target=/etc/prometheus/rules,readonly" \
  "$prometheus_image" \
  check rules /etc/prometheus/rules/finguardops-recording-rules.yml
docker run --rm --entrypoint /bin/promtool \
  --mount "type=bind,source=${repo_root}/infra/prometheus,target=/workspace,readonly" \
  --workdir /workspace/tests \
  "$prometheus_image" \
  test rules finguardops-recording-rules.test.yml
```

config와 rule은 runtime container에 read-only로 mount한다. test fixture는 standalone
`promtool test rules`에서만 사용하며 runtime container에는 mount하지 않는다.

## 3. 빌드와 시작

```bash
"${compose[@]}" build backend ai-service
"${compose[@]}" up -d postgresql ai-service
"${compose[@]}" up -d backend
"${compose[@]}" up -d external-risk-mock
"${compose[@]}" ps
```

PostgreSQL·AI Service·Backend는 internal application network를 사용하고 Backend와
Prometheus는 internal observability network를 사용한다. Prometheus만 host UI publish용
`prometheus-ui` bridge에도 연결한다. Backend가 PostgreSQL과 AI Service 준비 뒤 시작하면
External Risk fixture를 시작한다. fixture는 `network_mode: service:backend`로 Backend
network namespace를 공유하고 `127.0.0.1:8001`에만 bind한다. Backend는 같은 loopback
주소로 fixture를 호출한다. fixture healthcheck는 Mock `8001`과 Backend application `8080`
양쪽 health를 확인한다. fixture가 `healthy`인지 확인한 뒤 Prometheus를 시작한다.

```bash
"${compose[@]}" up -d prometheus
"${compose[@]}" ps
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

```bash
effective_from_epoch="$(( $(date -u +%s) + 60 ))"
effective_from="$(date -u -d "@${effective_from_epoch}" '+%Y-%m-%dT%H:%M:%SZ')"
"${compose[@]}" run --rm --no-deps \
  -e SPRING_PROFILES_ACTIVE=local,rule-v1-default-publication \
  -e FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false \
  backend \
  --spring.main.web-application-type=none \
  --finguardops.rule-v1-default-publication.enabled=true \
  --finguardops.rule-v1-default-publication.confirmation=PUBLISH_RULE_V1_DEFAULT_V1 \
  "--finguardops.rule-v1-default-publication.effective-from=${effective_from}"
wait_seconds="$(( effective_from_epoch - $(date -u +%s) + 1 ))"
if (( wait_seconds > 0 )); then
  sleep "$wait_seconds"
fi
```

## 5. 1차 traffic으로 Meter 등록

각 traffic 묶음의 첫 요청은 정상 접수·`RECEIVED`·terminal 결과, External Risk와 Rule
분석을 발생시킨다. 두 번째는 동일 payload replay, 세 번째는 같은 key의 다른 payload
conflict를 발생시키며 HTTP status `201`, `201`, `409`를 확인한다. 1차 traffic의 목적은
lazy 등록되는 업무 Meter 10개를 생성하고 rate 계산의 기준이 될 최초 scrape를 확보하는
것이다. 이 단계의 non-empty 결과나 기존 volume의 과거 sample만으로 recording rule 검증을
통과 처리하지 않는다.

```bash
send_traffic() {
  local run_label="$1"
  "${compose[@]}" exec -T external-risk-mock \
    python - "$run_label" <<'PY'
import json
import sys
import urllib.error
import urllib.request
import uuid
from datetime import UTC, datetime

run_label = sys.argv[1]
key = f"local-prom-{run_label}-{uuid.uuid4().hex}"
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
PY
}

first_traffic_started_at="$(date -u +%s)"
send_traffic "meter-registration"
```

## 6. 기준 scrape 확인과 2차 traffic

고정 sleep으로 최초 scrape 완료를 추정하지 않는다. target이 `UP`이고 Counter 7개·Timer
count 3개의 실제 raw sample timestamp가 모두 1차 traffic 시작 시각보다 새로워질 때까지
Prometheus API를 polling한다. 이 조건은 fresh volume에서는 최초 Meter scrape를, 기존
volume에서는 과거 sample이 아니라 현재 실행에서 새 scrape가 발생했음을 확인한다.

```bash
read -r first_target_scrape first_raw_sample_timestamp < <(
  "${compose[@]}" exec -T external-risk-mock \
    python - "$first_traffic_started_at" <<'PY'
import json
import sys
import time
import urllib.parse
import urllib.request

base_url = "http://prometheus:9090/api/v1"
started_at = float(sys.argv[1])
metrics = [
    "finguardops_transaction_intake_outcomes_total",
    "finguardops_transactions_received_total",
    "finguardops_transaction_outcomes_total",
    "finguardops_http_duplicate_requests_total",
    "finguardops_http_idempotency_conflicts_total",
    "finguardops_external_risk_outcomes_total",
    "finguardops_rule_analysis_outcomes_total",
    "finguardops_transaction_processing_duration_seconds_count",
    "finguardops_external_risk_duration_seconds_count",
    "finguardops_rule_analysis_duration_seconds_count",
]

def get_json(path, params=None):
    query = "" if not params else "?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(base_url + path + query, timeout=5) as response:
        return json.load(response)

deadline = time.monotonic() + 120
while time.monotonic() < deadline:
    try:
        targets = get_json("/targets")["data"]["activeTargets"]
        target = next(item for item in targets if item["health"] == "up")
        latest = []
        for metric in metrics:
            result = get_json(
                "/query",
                {"query": f"timestamp({metric})"},
            )["data"]["result"]
            if not result:
                raise RuntimeError(f"empty raw metric: {metric}")
            latest.append(max(float(sample["value"][1]) for sample in result))
        if min(latest) > started_at:
            print(target["lastScrape"], min(latest))
            break
    except (OSError, KeyError, RuntimeError, StopIteration, ValueError):
        pass
    time.sleep(2)
else:
    raise RuntimeError("first successful raw Meter scrape was not observed")
PY
)
printf 'first target scrape=%s, minimum raw sample timestamp=%s\n' \
  "$first_target_scrape" \
  "$first_raw_sample_timestamp"
```

2차 traffic은 기준 scrape 이후 Counter와 Timer count·sum에 rate 계산용 후속 증가를
생성한다. 1차와 같은 정상·replay·conflict 묶음을 새 key와 transaction으로 실행한다.

```bash
second_traffic_started_at="$(date -u +%s)"
send_traffic "rate-increase"

read -r post_scrape_one post_scrape_two post_rule_evaluation second_scrape_epoch < <(
  "${compose[@]}" exec -T external-risk-mock \
    python - "$second_traffic_started_at" <<'PY'
import json
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime

base_url = "http://prometheus:9090/api/v1"
started_at = float(sys.argv[1])
metrics = [
    "finguardops_transaction_intake_outcomes_total",
    "finguardops_transactions_received_total",
    "finguardops_transaction_outcomes_total",
    "finguardops_http_duplicate_requests_total",
    "finguardops_http_idempotency_conflicts_total",
    "finguardops_external_risk_outcomes_total",
    "finguardops_rule_analysis_outcomes_total",
    "finguardops_transaction_processing_duration_seconds_count",
    "finguardops_external_risk_duration_seconds_count",
    "finguardops_rule_analysis_duration_seconds_count",
]

def get_json(path, params=None):
    query = "" if not params else "?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(base_url + path + query, timeout=5) as response:
        return json.load(response)

def epoch(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()

successful_scrapes = []
deadline = time.monotonic() + 180
while time.monotonic() < deadline:
    try:
        targets = get_json("/targets")["data"]["activeTargets"]
        target = next(item for item in targets if item["health"] == "up")
        last_scrape = target["lastScrape"]
        if epoch(last_scrape) > started_at and last_scrape not in successful_scrapes:
            successful_scrapes.append(last_scrape)
        latest = []
        for metric in metrics:
            result = get_json(
                "/query",
                {"query": f"timestamp({metric})"},
            )["data"]["result"]
            if not result:
                raise RuntimeError(f"empty raw metric: {metric}")
            latest.append(max(float(sample["value"][1]) for sample in result))
        if len(successful_scrapes) >= 2 and min(latest) > started_at:
            groups = get_json("/rules")["data"]["groups"]
            group = next(
                item for item in groups
                if item["name"] == "finguardops-service-derived"
            )
            rules = group["rules"]
            if (
                len(rules) == 14
                and all(rule["health"] == "ok" for rule in rules)
                and epoch(group["lastEvaluation"]) > epoch(successful_scrapes[-1])
            ):
                print(
                    successful_scrapes[-2],
                    successful_scrapes[-1],
                    group["lastEvaluation"],
                    epoch(successful_scrapes[-1]),
                )
                break
    except (OSError, KeyError, RuntimeError, StopIteration, ValueError):
        pass
    time.sleep(2)
else:
    raise RuntimeError("two post-traffic scrapes and later rule evaluation were not observed")
PY
)
printf 'post-traffic scrapes=%s,%s; later rule evaluation=%s\n' \
  "$post_scrape_one" \
  "$post_scrape_two" \
  "$post_rule_evaluation"
```

## 7. recording rule query와 raw 대조

recording rule은 5분 `rate`와 같은 window의 Timer `_sum`/`_count` rate를 사용한다.
모든 결과는 `service`를 보존하고 분류형 rate만 `status` 또는 `result`를 추가로
보존한다. 다음 검증은 두 번째 성공 scrape보다 새로운 recorded sample만 인정하고 14개
이름을 모두 non-empty로 요구한다. 각 recorded sample의 실제 timestamp에서 recording
rule을 입력으로 사용하지 않은 raw PromQL을 별도 평가하며 label set과 값을 절대오차
`1e-12` 이내로 비교한다.

```bash
"${compose[@]}" exec -T external-risk-mock \
  python - "$second_scrape_epoch" <<'PY'
import json
import math
import sys
import urllib.parse
import urllib.request

base_url = "http://prometheus:9090/api/v1"
minimum_timestamp = float(sys.argv[1])
expressions = {
    "finguardops:transaction_intake:rate5m":
        "sum by(service)(rate(finguardops_transaction_intake_outcomes_total[5m]))",
    "finguardops:transactions_received:rate5m":
        "sum by(service)(rate(finguardops_transactions_received_total[5m]))",
    "finguardops:transaction_terminal:rate5m":
        "sum by(service)(rate(finguardops_transaction_outcomes_total[5m]))",
    "finguardops:transaction_terminal_by_status:rate5m":
        "sum by(service,status)(rate(finguardops_transaction_outcomes_total[5m]))",
    "finguardops:transaction_terminal_failure:ratio5m": (
        "((sum by(service)(rate(finguardops_transaction_outcomes_total{status=\"FAILED\"}[5m])) "
        "or on(service) 0*sum by(service)(rate(finguardops_transaction_outcomes_total[5m]))) / "
        "sum by(service)(rate(finguardops_transaction_outcomes_total[5m]))) and on(service) "
        "sum by(service)(rate(finguardops_transaction_outcomes_total[5m])) > 0"
    ),
    "finguardops:transaction_processing_duration:avg5m": (
        "(sum by(service)(rate(finguardops_transaction_processing_duration_seconds_sum[5m])) / "
        "sum by(service)(rate(finguardops_transaction_processing_duration_seconds_count[5m]))) "
        "and on(service) sum by(service)"
        "(rate(finguardops_transaction_processing_duration_seconds_count[5m])) > 0"
    ),
    "finguardops:http_duplicate_by_result:rate5m":
        "sum by(service,result)(rate(finguardops_http_duplicate_requests_total[5m]))",
    "finguardops:http_idempotency_conflict:rate5m":
        "sum by(service)(rate(finguardops_http_idempotency_conflicts_total[5m]))",
    "finguardops:external_risk_by_result:rate5m":
        "sum by(service,result)(rate(finguardops_external_risk_outcomes_total[5m]))",
    "finguardops:external_risk_failure:ratio5m": (
        "((sum by(service)(rate(finguardops_external_risk_outcomes_total{result=\"failed\"}[5m])) "
        "or on(service) 0*sum by(service)(rate(finguardops_external_risk_outcomes_total[5m]))) / "
        "sum by(service)(rate(finguardops_external_risk_outcomes_total[5m]))) and on(service) "
        "sum by(service)(rate(finguardops_external_risk_outcomes_total[5m])) > 0"
    ),
    "finguardops:external_risk_duration:avg5m": (
        "(sum by(service)(rate(finguardops_external_risk_duration_seconds_sum[5m])) / "
        "sum by(service)(rate(finguardops_external_risk_duration_seconds_count[5m]))) "
        "and on(service) sum by(service)"
        "(rate(finguardops_external_risk_duration_seconds_count[5m])) > 0"
    ),
    "finguardops:rule_analysis_by_result:rate5m":
        "sum by(service,result)(rate(finguardops_rule_analysis_outcomes_total[5m]))",
    "finguardops:rule_analysis_failure:ratio5m": (
        "((sum by(service)(rate(finguardops_rule_analysis_outcomes_total{result=\"failed\"}[5m])) "
        "or on(service) 0*sum by(service)(rate(finguardops_rule_analysis_outcomes_total[5m]))) / "
        "sum by(service)(rate(finguardops_rule_analysis_outcomes_total[5m]))) and on(service) "
        "sum by(service)(rate(finguardops_rule_analysis_outcomes_total[5m])) > 0"
    ),
    "finguardops:rule_analysis_duration:avg5m": (
        "(sum by(service)(rate(finguardops_rule_analysis_duration_seconds_sum[5m])) / "
        "sum by(service)(rate(finguardops_rule_analysis_duration_seconds_count[5m]))) "
        "and on(service) sum by(service)"
        "(rate(finguardops_rule_analysis_duration_seconds_count[5m])) > 0"
    ),
}

def query(expression, timestamp=None):
    params = {"query": expression}
    if timestamp is not None:
        params["time"] = timestamp
    url = base_url + "/query?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(url, timeout=10) as response:
        return json.load(response)["data"]["result"]

def label_key(metric):
    return tuple(sorted(
        (name, value) for name, value in metric.items() if name != "__name__"
    ))

maximum_error = 0.0
for name, raw_expression in expressions.items():
    timestamp_samples = query(f'timestamp({name}{{service="spring-backend"}})')
    current = [
        sample for sample in timestamp_samples
        if float(sample["value"][1]) > minimum_timestamp
    ]
    if not current:
        raise RuntimeError(f"no current recorded sample: {name}")
    timestamps = {float(sample["value"][1]) for sample in current}
    if len(timestamps) != 1:
        raise RuntimeError(f"multiple current timestamps: {name}: {timestamps}")
    timestamp = timestamps.pop()
    current_keys = {label_key(sample["metric"]) for sample in current}
    recorded = {
        label_key(sample["metric"]): float(sample["value"][1])
        for sample in query(name, timestamp)
        if label_key(sample["metric"]) in current_keys
    }
    raw = {
        label_key(sample["metric"]): float(sample["value"][1])
        for sample in query(raw_expression, timestamp)
    }
    if not recorded or recorded.keys() != raw.keys():
        raise RuntimeError(
            f"label mismatch: {name}: recorded={recorded.keys()} raw={raw.keys()}"
        )
    for key in recorded:
        error = abs(recorded[key] - raw[key])
        maximum_error = max(maximum_error, error)
        if error > 1e-12:
            raise RuntimeError(
                f"value mismatch: {name}: labels={key}: error={error}"
            )
    if "failure:ratio5m" in name and any(
        not 0 <= value <= 1 for value in recorded.values()
    ):
        raise RuntimeError(f"ratio out of range: {name}: {recorded}")
    if "duration:avg5m" in name and any(
        not math.isfinite(value) or value <= 0 for value in recorded.values()
    ):
        raise RuntimeError(f"non-positive duration: {name}: {recorded}")
    print(
        f"{name} timestamp={timestamp} series={len(recorded)} "
        f"labels={sorted(recorded)} values={list(recorded.values())}"
    )
print(f"validated rules={len(expressions)} maximum absolute error={maximum_error}")
PY
```

`/api/v1/rules`의 `lastEvaluation`은 실행 시각이며 sample timestamp와 수 ms 차이가 날 수
있으므로 raw 값 대조 시각으로 사용하지 않는다. `timestamp(<recording-rule>)`의 실제 sample
timestamp와 같은 Prometheus 엔진을 사용하므로 `rate` extrapolation을 외부에서 재구현하지
않는다. failure ratio는 0~1, 평균 duration은 양수여야 한다. 기존 volume의 5분 window에는
직전 실행의 정상적인 raw sample이 포함될 수 있지만, 위 실행 시각 marker는 오래된 sample만
존재하는 상태를 성공으로 인정하지 않는다. 현재 traffic만 격리해 검증해야 하면 별도 Compose
project와 새 named volume을 사용하고 종료 시 그 검증용 volume만 제거한다.

## 8. restart·Backend 재생성과 종료

Prometheus의 단독 restart는 같은 container를 재시작하며 named volume의 TSDB를 유지한다.

```bash
read -r before_restart_evaluation before_restart_sample < <(
  "${compose[@]}" exec -T external-risk-mock python - <<'PY'
import json
import urllib.parse
import urllib.request

base_url = "http://prometheus:9090/api/v1"
with urllib.request.urlopen(base_url + "/rules", timeout=5) as response:
    groups = json.load(response)["data"]["groups"]
group = next(item for item in groups if item["name"] == "finguardops-service-derived")
query = urllib.parse.urlencode({
    "query": 'timestamp(finguardops:transaction_intake:rate5m{service="spring-backend"})'
})
with urllib.request.urlopen(base_url + "/query?" + query, timeout=5) as response:
    sample = json.load(response)["data"]["result"][0]
print(group["lastEvaluation"], sample["value"][1])
PY
)

"${compose[@]}" restart prometheus
"${compose[@]}" exec -T external-risk-mock \
  python - "$before_restart_evaluation" "$before_restart_sample" <<'PY'
import json
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime

base_url = "http://prometheus:9090/api/v1"
before_evaluation = datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00"))
before_sample = float(sys.argv[2])
deadline = time.monotonic() + 120
while time.monotonic() < deadline:
    try:
        with urllib.request.urlopen(base_url + "/targets", timeout=5) as response:
            targets = json.load(response)["data"]["activeTargets"]
        target = next(item for item in targets if item["health"] == "up")
        with urllib.request.urlopen(base_url + "/rules", timeout=5) as response:
            groups = json.load(response)["data"]["groups"]
        group = next(
            item for item in groups
            if item["name"] == "finguardops-service-derived"
        )
        query = urllib.parse.urlencode({
            "query": 'timestamp(finguardops:transaction_intake:rate5m{service="spring-backend"})'
        })
        with urllib.request.urlopen(base_url + "/query?" + query, timeout=5) as response:
            sample = json.load(response)["data"]["result"][0]
        after_evaluation = datetime.fromisoformat(
            group["lastEvaluation"].replace("Z", "+00:00")
        )
        after_sample = float(sample["value"][1])
        if (
            len(group["rules"]) == 14
            and all(rule["health"] == "ok" for rule in group["rules"])
            and after_evaluation > before_evaluation
            and after_sample > before_sample
        ):
            print(
                f"target={target['health']} rules={len(group['rules'])} "
                f"evaluation={group['lastEvaluation']} sample={after_sample}"
            )
            break
    except (OSError, KeyError, StopIteration, ValueError):
        pass
    time.sleep(2)
else:
    raise RuntimeError("Prometheus did not re-evaluate healthy rules after restart")
PY

"${compose[@]}" ps
prometheus_id="$("${compose[@]}" ps -q prometheus)"
docker inspect "$prometheus_id" \
  --format '{{range .Mounts}}{{if eq .Destination "/prometheus"}}{{.Name}}{{end}}{{end}}'
```

`docker compose restart backend`도 기존 Backend container와 network namespace를
재사용한다. 반면 `up --force-recreate backend`는 새 container와 새 namespace를 만든다.
`depends_on.restart: true`는 Compose가 의존 service 변경을 인식하게 하지만 기존 Mock
container를 새 namespace에 재연결한다고 가정하면 안 된다. 안전 절차를 생략한 단독 강제
재생성에서는 복합 healthcheck 때문에 이전 namespace의 Mock이 `healthy`로 남아서는 안 된다.

다음은 stale sidecar 상태를 의도적으로 검증한 직후 안전 순서로 복구하는 절차다. volume을
삭제하지 않는다.

```bash
"${compose[@]}" up -d --force-recreate backend
sleep 10
mock_id="$("${compose[@]}" ps -q external-risk-mock)"
mock_health="$(docker inspect \
  --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
  "$mock_id")"
if [[ "$mock_health" == "healthy" ]]; then
  printf '%s\n' 'Stale External Risk Mock remained healthy after Backend recreation' >&2
  exit 1
fi

"${compose[@]}" stop external-risk-mock
"${compose[@]}" rm -f external-risk-mock
"${compose[@]}" up -d --force-recreate --wait backend
"${compose[@]}" up -d --force-recreate --wait external-risk-mock

backend_namespace="$("${compose[@]}" exec -T backend readlink /proc/self/ns/net | tr -d '\r')"
mock_namespace="$("${compose[@]}" exec -T external-risk-mock readlink /proc/self/ns/net | tr -d '\r')"
if [[ "$backend_namespace" != "$mock_namespace" ]]; then
  printf '%s\n' 'Backend and External Risk Mock network namespaces differ' >&2
  exit 1
fi
mock_listener="$("${compose[@]}" exec -T external-risk-mock \
  python -c "print(any('0100007F:1F41' in line and ' 0A ' in line for line in open('/proc/net/tcp')))" \
  | tr -d '\r')"
if [[ "$mock_listener" != "True" ]]; then
  printf '%s\n' 'Mock is not listening on 127.0.0.1:8001' >&2
  exit 1
fi
"${compose[@]}" ps
```

복구 뒤 5절의 내부 traffic 명령을 다시 실행해 실제 External Risk lookup과 거래 `201`을
확인한다. 종료는 volume 삭제 옵션 없이 수행한다.

```bash
"${compose[@]}" down
"${compose[@]}" ps --all
```

`down`에 `--volumes`를 사용하지 않는다. 업무 container와 세 network는 제거되고
`finguardops-local-observability_prometheus-data` named volume만 유지되어야 한다.
