# Prometheus 로컬 scrape runbook

## 1. 목적과 경계

이 runbook은 Issue #196부터 Issue #205까지의 로컬 Docker Compose 관측 검증 절차이다.
PostgreSQL, AI Service, External Risk fixture, Backend, Prometheus, Alertmanager와 Grafana를
함께 실행하고 Backend의
`/actuator/prometheus`를 실제 scrape하고 기존 업무 Meter 기반 recording rule 14개와
로컬 실패율 alert rule 6개를 평가하며 local Alertmanager routing·receiver 전달과 Grafana
datasource·16-panel dashboard provisioning·query를 검증한다. production Prometheus·
Alertmanager·Grafana·receiver 배포, 외부 알림·credential 배포, 인증·TLS·SSO·RBAC,
SLA·SLO·운영 임계값, HA와 장기 보존을 제공하지 않는다. completion gap·장기
`IN_PROGRESS` Gauge, `deployment.error_ratio`와 `deployment.latency`도 구현하지 않는다.
loopback bind와 Docker network 분리도 인증을 대신하지 않는다.

## 2. 사전 조건과 설정 검증

Docker Desktop, Compose v2, Python 3와 Git Bash를 준비하고 저장소 루트의 같은 shell
session에서 실행한다. `infra/.env.example`을 ignored `infra/.env`로 복사한 뒤 DB password와
`GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD`를 로컬 전용 값으로 교체한다. 실제 credential은
이 ignored 파일에만 두고 shell history, 명령 인자, 로그, 응답 또는 임시 파일에 기록하지 않는다.
재사용 가능한 기본 password를 사용하지 않는다.

```bash
if [[ ! -f infra/.env ]]; then
  cp infra/.env.example infra/.env
  printf '%s\n' 'Edit ignored infra/.env, then rerun this section.' >&2
  exit 1
fi
[[ -z "$(git ls-files -- infra/.env)" ]]
git check-ignore --quiet infra/.env
set +x
set -a
source infra/.env
set +a
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
: "${GRAFANA_ADMIN_USER:?GRAFANA_ADMIN_USER is required}"
: "${GRAFANA_ADMIN_PASSWORD:?GRAFANA_ADMIN_PASSWORD is required}"
compose=(docker compose --env-file infra/.env -f infra/compose.yml)
repo_root="$(pwd -W)"
prometheus_image="prom/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0"
grafana_image="grafana/grafana:13.2.0@sha256:3fd54ae1214669f8355f065ec9f6445d5279a3d77095ab048ca045685272429b"

docker buildx imagetools inspect "$grafana_image"
docker pull "$grafana_image"
[[ "$(docker image inspect "$grafana_image" --format '{{.Os}}/{{.Architecture}}')" == "linux/amd64" ]]
"${compose[@]}" config --quiet
python infra/grafana/tests/verify_dashboard.py static
python infra/grafana/tests/verify_dashboard.py mutations
docker run --rm --entrypoint /bin/promtool \
  --mount "type=bind,source=${repo_root}/infra/prometheus/prometheus.yml,target=/etc/prometheus/prometheus.yml,readonly" \
  --mount "type=bind,source=${repo_root}/infra/prometheus/rules,target=/etc/prometheus/rules,readonly" \
  "$prometheus_image" \
  check config /etc/prometheus/prometheus.yml
docker run --rm --entrypoint /bin/promtool \
  --mount "type=bind,source=${repo_root}/infra/prometheus/rules,target=/etc/prometheus/rules,readonly" \
  "$prometheus_image" \
  check rules \
  /etc/prometheus/rules/finguardops-recording-rules.yml \
  /etc/prometheus/rules/finguardops-alert-rules.yml
docker run --rm --entrypoint /bin/promtool \
  --mount "type=bind,source=${repo_root}/infra/prometheus,target=/workspace,readonly" \
  --workdir /workspace/tests \
  "$prometheus_image" \
  test rules finguardops-recording-rules.test.yml
for run in 1 2 3; do
  docker run --rm --entrypoint /bin/promtool \
    --mount "type=bind,source=${repo_root}/infra/prometheus,target=/workspace,readonly" \
    --workdir /workspace/tests \
    "$prometheus_image" \
    test rules finguardops-alert-rules.test.yml
done
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
"${compose[@]}" up -d prometheus grafana
"${compose[@]}" ps
```

모든 service가 `healthy`인지 확인한다. host에는 Prometheus UI
`http://127.0.0.1:9090`과 Grafana UI `http://127.0.0.1:3000`만 노출된다. Backend application `8080`, management `8081`,
PostgreSQL `5432`, AI Service `8000`, External Risk `8001`은 host에 publish되지 않는다.
업무 traffic도 host에서 Backend로 직접 보내지 않고 같은 network namespace의 fixture에서
`127.0.0.1:8080`으로 보낸다. loopback sidecar는 기존 non-production plain HTTP 제한을
보존하는 로컬 fixture이며 production External Risk Provider 정책이나 인증·TLS를 대체하지
않는다.

Grafana는 internal observability와 전용 `grafana-ui` bridge에만 연결한다. `grafana-ui`에는
Grafana만 연결하며 Backend는 `prometheus-ui`와 `grafana-ui` 어느 쪽에도 연결하지 않는다.
Grafana에는 Backend·Prometheus·Alertmanager dependency를 두지 않으므로 Grafana 장애는
업무 처리, scrape·rule evaluation 또는 alert routing으로 전파되지 않는다.

## 4. 로컬 Rule 집합 발행

V5 seed는 DRAFT이므로 실제 거래 전에 기존 local 전용 one-shot 경계로 발행한다.
이 절차는 8개 service가 모두 healthy가 된 뒤, one-shot 발행 명령을 실행하기 직전에 매번
새 `effectiveFrom`을 계산한다. production profile에서는 실행하지 않는다. 발행 명령의
deadline은 180초, startup 변동을 흡수하는 safety margin은 60초, `effectiveFrom` lead는
300초이며 `300 >= 180 + 60`을 명령에서도 검증한다. 발행 후 activation은 최대 420초 동안
polling하며 고정 sleep으로 추정하지 않는다.

```bash
"${compose[@]}" up -d --wait --wait-timeout 180
expected_publication_services="$(
  printf '%s\n' \
    postgresql ai-service external-risk-mock backend prometheus \
    alertmanager alertmanager-webhook grafana | sort
)"
actual_publication_services="$(
  "${compose[@]}" ps --services --status running | sort
)"
if [[ "$actual_publication_services" != "$expected_publication_services" ]]; then
  printf '%s\n' 'All eight services must be running before Rule publication' >&2
  exit 1
fi
for publication_service in \
  postgresql ai-service external-risk-mock backend prometheus \
  alertmanager alertmanager-webhook grafana; do
  publication_service_id="$("${compose[@]}" ps -q "$publication_service")"
  publication_service_health="$(
    docker inspect "$publication_service_id" \
      --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}'
  )"
  if [[ "$publication_service_health" != "healthy" ]]; then
    printf 'Service is not healthy before Rule publication: %s=%s\n' \
      "$publication_service" "$publication_service_health" >&2
    exit 1
  fi
done

# Publication cleanup traps are scoped to this subshell so an enclosing fresh-project
# cleanup trap remains installed for all later E2E steps.
(
publication_command_deadline_seconds=180
publication_safety_margin_seconds=60
effective_from_lead_seconds=300
activation_polling_deadline_seconds=420
if (( effective_from_lead_seconds <
  publication_command_deadline_seconds + publication_safety_margin_seconds )); then
  printf '%s\n' 'Rule publication lead does not cover deadline plus safety margin' >&2
  exit 1
fi

publication_temp_dir="$(
  mktemp -d "${TMPDIR:-/tmp}/finguardops-rule-publication.XXXXXX"
)"
publication_log="${publication_temp_dir}/publication.log"
publication_timestamp_file="${publication_temp_dir}/effective-from"
publication_container="finguardops-rule-publication-${RANDOM}-$$"

cleanup_rule_publication() {
  local exit_status="$?"
  trap - EXIT INT TERM
  set +e
  docker rm -f "$publication_container" >/dev/null 2>&1 || true
  rm -rf -- "$publication_temp_dir"
  exit "$exit_status"
}
trap cleanup_rule_publication EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

publication_started_epoch="$(date -u +%s)"
publication_started_at="$(
  date -u -d "@${publication_started_epoch}" '+%Y-%m-%dT%H:%M:%SZ'
)"
effective_from_epoch="$((
  publication_started_epoch + effective_from_lead_seconds
))"
effective_from="$(
  date -u -d "@${effective_from_epoch}" '+%Y-%m-%dT%H:%M:%SZ'
)"
printf '%s\n' "$effective_from" >"$publication_timestamp_file"
python - "$effective_from" "$effective_from_epoch" <<'PY'
import sys
from datetime import datetime, timezone

value = sys.argv[1]
expected_epoch = int(sys.argv[2])
parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(
    tzinfo=timezone.utc
)
if parsed.strftime("%Y-%m-%dT%H:%M:%SZ") != value:
    raise RuntimeError(f"effectiveFrom is not canonical UTC RFC3339: {value}")
if int(parsed.timestamp()) != expected_epoch:
    raise RuntimeError("effectiveFrom text and epoch do not match")
if expected_epoch <= int(datetime.now(timezone.utc).timestamp()):
    raise RuntimeError("effectiveFrom must be in the future")
PY
printf 'publication startedAt=%s effectiveFrom=%s deadline=%ss lead=%ss\n' \
  "$publication_started_at" "$effective_from" \
  "$publication_command_deadline_seconds" "$effective_from_lead_seconds"

publication_status=0
timeout --foreground --kill-after=10s \
  "${publication_command_deadline_seconds}s" \
  "${compose[@]}" run --rm --no-deps -T --name "$publication_container" \
  -e SPRING_PROFILES_ACTIVE=local,rule-v1-default-publication \
  -e FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false \
  backend \
  --spring.main.web-application-type=none \
  --finguardops.rule-v1-default-publication.enabled=true \
  --finguardops.rule-v1-default-publication.confirmation=PUBLISH_RULE_V1_DEFAULT_V1 \
  "--finguardops.rule-v1-default-publication.effective-from=${effective_from}" \
  </dev/null >"$publication_log" 2>&1 || publication_status="$?"
cat "$publication_log"
if (( publication_status == 124 || publication_status == 137 )); then
  printf 'Rule publication exceeded %s seconds\n' \
    "$publication_command_deadline_seconds" >&2
  exit 124
fi
if (( publication_status != 0 )); then
  printf 'Rule publication failed with exit code %s\n' \
    "$publication_status" >&2
  exit "$publication_status"
fi
grep -Fq 'outcome=PUBLISHED' "$publication_log"
grep -Fq "effectiveFrom=${effective_from}" "$publication_log"
publication_completed_epoch="$(date -u +%s)"
if (( publication_completed_epoch >= effective_from_epoch )); then
  printf '%s\n' 'effectiveFrom was not future after publication completed' >&2
  exit 1
fi

stored_query_status=0
stored_publication="$(
  timeout --foreground --signal=KILL \
    "${publication_command_deadline_seconds}s" \
    "${compose[@]}" exec -T postgresql \
    psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set ON_ERROR_STOP=1 --tuples-only --no-align --field-separator='|' \
    --command "
      SELECT
        count(*),
        count(*) FILTER (
          WHERE status = 'PUBLISHED' AND published_at IS NOT NULL
        ),
        count(DISTINCT effective_from),
        min(extract(epoch FROM effective_from)::bigint),
        max(extract(epoch FROM effective_from)::bigint)
      FROM rule_version
      WHERE rule_version_id IN (
        '20000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000002',
        '20000000-0000-4000-8000-000000000003',
        '20000000-0000-4000-8000-000000000004'
      );
    " </dev/null
)" || stored_query_status="$?"
if (( stored_query_status != 0 )); then
  printf 'Stored RuleVersion verification failed with exit code %s\n' \
    "$stored_query_status" >&2
  exit "$stored_query_status"
fi
stored_publication="${stored_publication//$'\r'/}"
if [[ "$stored_publication" != \
  "4|4|1|${effective_from_epoch}|${effective_from_epoch}" ]]; then
  printf 'Stored RuleVersion publication does not match requested effectiveFrom: %s\n' \
    "$stored_publication" >&2
  exit 1
fi
printf 'RuleVersion publication stored exact effectiveFrom=%s\n' \
  "$effective_from"

activation_started_epoch="$(date -u +%s)"
activation_deadline_epoch="$((
  activation_started_epoch + activation_polling_deadline_seconds
))"
activation_last_report_epoch=0
printf 'RuleVersion activation polling started deadline=%ss\n' \
  "$activation_polling_deadline_seconds"
while (( $(date -u +%s) < activation_deadline_epoch )); do
  now_epoch="$(date -u +%s)"
  activation_remaining_seconds="$((activation_deadline_epoch - now_epoch))"
  if (( activation_remaining_seconds <= 0 )); then
    break
  fi
  executable_count=0
  if (( now_epoch >= effective_from_epoch )); then
    activation_query_status=0
    executable_count="$(
      timeout --foreground --signal=KILL \
        "${activation_remaining_seconds}s" \
        "${compose[@]}" exec -T postgresql \
        psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
        --set ON_ERROR_STOP=1 --tuples-only --no-align \
        --command "
          SELECT count(*)
          FROM rule_version version
          JOIN fraud_rule rule ON rule.id = version.fraud_rule_id
          WHERE version.rule_version_id IN (
            '20000000-0000-4000-8000-000000000001',
            '20000000-0000-4000-8000-000000000002',
            '20000000-0000-4000-8000-000000000003',
            '20000000-0000-4000-8000-000000000004'
          )
            AND rule.lifecycle_status = 'ACTIVE'
            AND version.status = 'PUBLISHED'
            AND version.effective_from <= CURRENT_TIMESTAMP
            AND (
              version.effective_to IS NULL
              OR CURRENT_TIMESTAMP < version.effective_to
            );
        " </dev/null
    )" || activation_query_status="$?"
    if (( activation_query_status != 0 )); then
      printf 'RuleVersion activation query failed with exit code %s\n' \
        "$activation_query_status" >&2
      exit "$activation_query_status"
    fi
    executable_count="${executable_count//[[:space:]]/}"
  fi
  if [[ "$executable_count" == "4" ]]; then
    if (( now_epoch < effective_from_epoch )); then
      printf '%s\n' 'RuleVersion was reported executable before effectiveFrom' >&2
      exit 1
    fi
    printf 'RuleVersion activation ready effectiveFrom=%s executable=%s\n' \
      "$effective_from" "$executable_count"
    break
  fi
  if (( now_epoch - activation_last_report_epoch >= 30 )); then
    printf 'RuleVersion activation pending now=%s effectiveFrom=%s executable=%s\n' \
      "$(date -u -d "@${now_epoch}" '+%Y-%m-%dT%H:%M:%SZ')" \
      "$effective_from" "$executable_count"
    activation_last_report_epoch="$now_epoch"
  fi
  now_epoch="$(date -u +%s)"
  activation_remaining_seconds="$((activation_deadline_epoch - now_epoch))"
  if (( activation_remaining_seconds <= 0 )); then
    break
  fi
  if (( activation_remaining_seconds < 2 )); then
    sleep "$activation_remaining_seconds"
  else
    sleep 2
  fi
done
if [[ "$executable_count" != "4" ]]; then
  printf 'RuleVersion activation was not ready within %s seconds\n' \
    "$activation_polling_deadline_seconds" >&2
  exit 1
fi

trap - EXIT INT TERM
docker rm -f "$publication_container" >/dev/null 2>&1 || true
rm -rf -- "$publication_temp_dir"
)
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

<a id="prometheus-alert-response"></a>

## 8. Prometheus alert response

### 8.1 로컬 alert 계약

`finguardops-service-alerts` group은 기존 recording rule 결과만 30초마다 평가한다.

| Alert | 조건 | 지속시간 | severity |
| --- | --- | --- | --- |
| `FinGuardOpsTransactionTerminalFailureRatioWarning` | terminal failure ratio `> 0.10`, terminal rate `>= 0.10/s` | 2분 | `warning` |
| `FinGuardOpsTransactionTerminalFailureRatioCritical` | terminal failure ratio `> 0.30`, terminal rate `>= 0.10/s` | 5분 | `critical` |
| `FinGuardOpsExternalRiskFailureRatioWarning` | External Risk failure ratio `> 0.10`, 전체 result rate `>= 0.10/s` | 2분 | `warning` |
| `FinGuardOpsExternalRiskFailureRatioCritical` | External Risk failure ratio `> 0.30`, 전체 result rate `>= 0.10/s` | 5분 | `critical` |
| `FinGuardOpsRuleAnalysisFailureRatioWarning` | Rule Analysis failure ratio `> 0.10`, 전체 result rate `>= 0.10/s` | 2분 | `warning` |
| `FinGuardOpsRuleAnalysisFailureRatioCritical` | Rule Analysis failure ratio `> 0.30`, 전체 result rate `>= 0.10/s` | 5분 | `critical` |

이 값은 local validation contract이며 production SLA·SLO나 업무 실패 허용률이 아니다.
ratio가 threshold와 같으면 inactive이고 최소 처리율 0.10/s는 guard를 통과한다. ratio
입력이 missing이거나 분모가 0이라 series가 없으면 inactive이며 실제 failure ratio 0도
inactive다. warning과 critical은 독립적으로 평가하므로 동시에 firing할 수 있다.
Prometheus는 internal observability network의 Alertmanager v2 API로 전달한다. Alertmanager는
`alertname,service`로 grouping하고 같은 service·같은 signal의 critical로 warning을 억제한다.
`keep_firing_for`는 사용하지 않는다.

alert label은 `service`, `severity`, 자동 `alertname`뿐이다. annotation은 `summary`,
`description`, `runbook_url`만 사용하며 description에는 service와 소수점 셋째 자리 ratio만
포함한다. 거래·고객·계좌 식별자, credential, request path·query·body·header와 exception
원문을 label이나 annotation에 추가하지 않는다.

### 8.2 fresh project와 API 확인 함수

기존 보존 volume과 분리된 project에서 2~7절을 실행한다. 아래 대입 뒤 기존 `compose`
배열을 사용하는 명령은 모두 이 검증 project만 대상으로 한다.

```bash
alert_project="finguardops-alert-e2e-$RANDOM"
compose=(docker compose --env-file infra/.env -p "$alert_project" -f infra/compose.yml)
"${compose[@]}" config --quiet
```

다음 함수는 이미 빌드된 AI Service image를 `prometheus-ui` network에 일회성 `--rm`
helper로 연결해 Prometheus API를 polling한다. External Risk Mock이나 AI Service를 중단한
장애 시나리오에서도 조회 경로를 유지하며 임시 query·response 파일을 만들지 않는다.

```bash
wait_for_condition_readiness() {
  local scenario="$1"
  local timeout_seconds="$2"
  docker run --rm -i \
    --network "${alert_project}_prometheus-ui" \
    --entrypoint python \
    finguardops-ai-service:local \
    - "$scenario" "$timeout_seconds" <<'PY'
import json
import math
import sys
import time
import urllib.parse
import urllib.request
from datetime import UTC, datetime

scenario, timeout_seconds = sys.argv[1], int(sys.argv[2])
base_url = "http://prometheus:9090/api/v1"
service = "spring-backend"
common = {
    "terminal_ratio": (
        'finguardops:transaction_terminal_failure:ratio5m{service="spring-backend"}',
        'timestamp(finguardops:transaction_terminal_failure:ratio5m{service="spring-backend"})',
        ">",
        0.30,
    ),
    "terminal_guard": (
        'finguardops:transaction_terminal:rate5m{service="spring-backend"}',
        'timestamp(finguardops:transaction_terminal:rate5m{service="spring-backend"})',
        ">=",
        0.15,
    ),
}
scenario_queries = {
    "external-risk": {
        "external_ratio": (
            'finguardops:external_risk_failure:ratio5m{service="spring-backend"}',
            'timestamp(finguardops:external_risk_failure:ratio5m{service="spring-backend"})',
            ">",
            0.30,
        ),
        "external_guard": (
            'sum by (service) (finguardops:external_risk_by_result:rate5m{service="spring-backend"})',
            'min without (result) (timestamp(finguardops:external_risk_by_result:rate5m{service="spring-backend"}))',
            ">=",
            0.15,
        ),
    },
    "rule-analysis": {
        "rule_ratio": (
            'finguardops:rule_analysis_failure:ratio5m{service="spring-backend"}',
            'timestamp(finguardops:rule_analysis_failure:ratio5m{service="spring-backend"})',
            ">",
            0.30,
        ),
        "rule_guard": (
            'sum by (service) (finguardops:rule_analysis_by_result:rate5m{service="spring-backend"})',
            'min without (result) (timestamp(finguardops:rule_analysis_by_result:rate5m{service="spring-backend"}))',
            ">=",
            0.15,
        ),
        **common,
    },
}
queries = scenario_queries[scenario]


def query(expression):
    encoded = urllib.parse.urlencode({"query": expression})
    with urllib.request.urlopen(base_url + "/query?" + encoded, timeout=5) as response:
        payload = json.load(response)
    if payload.get("status") != "success":
        raise RuntimeError(f"Prometheus query failed: {payload}")
    return payload["data"]["result"]


def scalar(expression, *, missing_allowed):
    result = query(expression)
    if not result and missing_allowed:
        return None
    if len(result) != 1 or result[0]["metric"].get("service") != service:
        raise RuntimeError(f"expected exactly one {service} series: {expression}: {result}")
    value = float(result[0]["value"][1])
    if not math.isfinite(value):
        raise RuntimeError(f"non-finite query value: {expression}: {value}")
    return value


deadline = time.monotonic() + timeout_seconds
while time.monotonic() < deadline:
    values = {}
    missing = False
    for name, (expression, _, comparison, threshold) in queries.items():
        value = scalar(expression, missing_allowed=True)
        if value is None:
            missing = True
            break
        values[name] = value
        if comparison == ">" and not value > threshold:
            break
        if comparison == ">=" and not value >= threshold:
            break
    else:
        sample_timestamps = {
            name: scalar(timestamp_expression, missing_allowed=False)
            for name, (_, timestamp_expression, _, _) in queries.items()
        }
        print(
            json.dumps(
                {
                    "scenario": scenario,
                    "service": service,
                    "values": values,
                    "sample_timestamps": {
                        name: datetime.fromtimestamp(value, UTC).isoformat()
                        for name, value in sample_timestamps.items()
                    },
                },
                sort_keys=True,
            )
        )
        break
    time.sleep(2)
else:
    raise RuntimeError(
        f"condition readiness not observed: scenario={scenario} "
        f"last_values={values} missing={missing}"
    )
PY
}

wait_for_correlated_alert_transition() {
  local scenario="$1"
  local pending_timeout_seconds="$2"
  local warning_deadline_seconds="$3"
  local critical_deadline_seconds="$4"
  docker run --rm -i \
    --network "${alert_project}_prometheus-ui" \
    --entrypoint python \
    finguardops-ai-service:local \
    - "$scenario" "$pending_timeout_seconds" "$warning_deadline_seconds" \
    "$critical_deadline_seconds" <<'PY'
import json
import math
import sys
import time
import urllib.request
from datetime import UTC, datetime

scenario = sys.argv[1]
pending_timeout = int(sys.argv[2])
warning_deadline_seconds = int(sys.argv[3])
critical_deadline_seconds = int(sys.argv[4])
base_url = "http://prometheus:9090/api/v1"
families = {
    "external-risk": (
        "FinGuardOpsExternalRiskFailureRatio",
    ),
    "rule-analysis": (
        "FinGuardOpsRuleAnalysisFailureRatio",
        "FinGuardOpsTransactionTerminalFailureRatio",
    ),
}[scenario]
expected_names = {
    family + severity
    for family in families
    for severity in ("Warning", "Critical")
}
warning_names = {name for name in expected_names if name.endswith("Warning")}
critical_names = {name for name in expected_names if name.endswith("Critical")}
annotation_subjects = {
    "FinGuardOpsTransactionTerminalFailureRatio": (
        "Transaction terminal failure ratio",
        "transaction terminal failure ratio",
    ),
    "FinGuardOpsExternalRiskFailureRatio": (
        "External Risk failure ratio",
        "External Risk failure ratio",
    ),
    "FinGuardOpsRuleAnalysisFailureRatio": (
        "Rule Analysis failure ratio",
        "Rule Analysis failure ratio",
    ),
}
runbook_url = (
    "https://github.com/Ahnjisan/FinGuardOps/blob/main/"
    "docs/09-deployment/prometheus-local-scrape-runbook.md#prometheus-alert-response"
)


def parse_time(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def get_alerts():
    with urllib.request.urlopen(base_url + "/alerts", timeout=5) as response:
        payload = json.load(response)
    if payload.get("status") != "success":
        raise RuntimeError(f"Prometheus alerts query failed: {payload}")
    alerts = payload["data"]["alerts"]
    unexpected = [
        alert for alert in alerts
        if alert["labels"].get("alertname") not in expected_names
    ]
    if unexpected:
        raise RuntimeError(f"unexpected active alerts: {unexpected}")
    return alerts


def validate(alert):
    labels = alert["labels"]
    annotations = alert["annotations"]
    if set(labels) != {"alertname", "service", "severity"}:
        raise RuntimeError(f"unexpected alert labels: {labels}")
    if labels["service"] != "spring-backend":
        raise RuntimeError(f"unexpected alert service: {labels}")
    expected_severity = "warning" if labels["alertname"].endswith("Warning") else "critical"
    if labels["severity"] != expected_severity:
        raise RuntimeError(f"unexpected alert severity: {labels}")
    if set(annotations) != {"summary", "description", "runbook_url"}:
        raise RuntimeError(f"unexpected alert annotations: {annotations}")
    summary_subject, description_subject = next(
        value for prefix, value in annotation_subjects.items()
        if labels["alertname"].startswith(prefix)
    )
    value = float(alert["value"])
    if not math.isfinite(value):
        raise RuntimeError(f"non-finite alert value: {alert}")
    expected_annotations = {
        "summary": f"{summary_subject} {expected_severity}",
        "description": (
            f"Service spring-backend {description_subject} is {value:.3f}."
        ),
        "runbook_url": runbook_url,
    }
    if annotations != expected_annotations:
        raise RuntimeError(f"unexpected alert annotation values: {annotations}")
    parse_time(alert["activeAt"])


def indexed(alerts):
    for alert in alerts:
        validate(alert)
    return {alert["labels"]["alertname"]: alert for alert in alerts}


pending_deadline = time.monotonic() + pending_timeout
while time.monotonic() < pending_deadline:
    alerts = indexed(get_alerts())
    if set(alerts) == expected_names and all(
        alert["state"] == "pending" for alert in alerts.values()
    ):
        active_at = {name: alert["activeAt"] for name, alert in alerts.items()}
        print(json.dumps({"state": "pending", "activeAt": active_at}, sort_keys=True))
        break
    time.sleep(2)
else:
    raise RuntimeError(f"correlated pending alerts not observed: scenario={scenario}")

warning_deadline = max(parse_time(active_at[name]) for name in warning_names) + warning_deadline_seconds
while time.time() < warning_deadline:
    alerts = indexed(get_alerts())
    if set(alerts) == expected_names and all(
        alerts[name]["state"] == "firing" for name in warning_names
    ) and all(alerts[name]["state"] == "pending" for name in critical_names):
        now = datetime.now(UTC).timestamp()
        elapsed = {name: now - parse_time(active_at[name]) for name in warning_names}
        if any(value < 120 for value in elapsed.values()):
            raise RuntimeError(f"warning fired before two minutes: {elapsed}")
        print(json.dumps({"state": "warning-firing", "elapsed": elapsed}, sort_keys=True))
        break
    time.sleep(2)
else:
    raise RuntimeError(f"warning firing deadline exceeded: scenario={scenario}")

critical_deadline = max(parse_time(active_at[name]) for name in critical_names) + critical_deadline_seconds
while time.time() < critical_deadline:
    alerts = indexed(get_alerts())
    if set(alerts) == expected_names and all(
        alert["state"] == "firing" for alert in alerts.values()
    ):
        now = datetime.now(UTC).timestamp()
        elapsed = {name: now - parse_time(active_at[name]) for name in critical_names}
        if any(value < 300 for value in elapsed.values()):
            raise RuntimeError(f"critical fired before five minutes: {elapsed}")
        print(
            json.dumps(
                {
                    "state": "warning-critical-simultaneous-firing",
                    "elapsed": elapsed,
                    "alerts": sorted(alerts),
                },
                sort_keys=True,
            )
        )
        break
    time.sleep(2)
else:
    raise RuntimeError(f"critical firing deadline exceeded: scenario={scenario}")
PY
}

terminal_failure_snapshot() {
  local phase="$1"
  docker run --rm -i \
    --network "${alert_project}_prometheus-ui" \
    --entrypoint python \
    finguardops-ai-service:local \
    - "$phase" <<'PY'
import json
import math
import sys
import urllib.parse
import urllib.request

phase = sys.argv[1]
base_url = "http://prometheus:9090/api/v1"
service = "spring-backend"
queries = {
    "raw_failed_counter": (
        'sum(finguardops_transaction_outcomes_total{service="spring-backend",status="FAILED"})',
        'max(timestamp(finguardops_transaction_outcomes_total{service="spring-backend",status="FAILED"}))',
        False,
    ),
    "recorded_failure_ratio": (
        'finguardops:transaction_terminal_failure:ratio5m{service="spring-backend"}',
        'timestamp(finguardops:transaction_terminal_failure:ratio5m{service="spring-backend"})',
        True,
    ),
}


def query(expression):
    encoded = urllib.parse.urlencode({"query": expression})
    with urllib.request.urlopen(base_url + "/query?" + encoded, timeout=5) as response:
        payload = json.load(response)
    if payload.get("status") != "success":
        raise RuntimeError(f"Prometheus query failed: {payload}")
    return payload["data"]["result"]


def sample(expression, timestamp_expression, require_service):
    result = query(expression)
    if not result:
        return {"kind": "missing", "value": None, "sample_timestamp": None}
    if len(result) != 1:
        raise RuntimeError(f"expected at most one series: {expression}: {result}")
    if require_service and result[0]["metric"].get("service") != service:
        raise RuntimeError(f"unexpected service series: {expression}: {result}")
    value = float(result[0]["value"][1])
    if not math.isfinite(value):
        raise RuntimeError(f"non-finite query value: {expression}: {value}")
    timestamp_result = query(timestamp_expression)
    if len(timestamp_result) != 1:
        raise RuntimeError(f"expected one timestamp series: {timestamp_expression}: {timestamp_result}")
    sample_timestamp = float(timestamp_result[0]["value"][1])
    if not math.isfinite(sample_timestamp):
        raise RuntimeError(f"non-finite sample timestamp: {timestamp_expression}: {sample_timestamp}")
    return {"kind": "numeric", "value": value, "sample_timestamp": sample_timestamp}


snapshot = {
    name: sample(expression, timestamp_expression, require_service)
    for name, (expression, timestamp_expression, require_service) in queries.items()
}
print(json.dumps({"phase": phase, "terminal_failure": snapshot}, sort_keys=True), file=sys.stderr)
raw = snapshot["raw_failed_counter"]
ratio = snapshot["recorded_failure_ratio"]
print(
    int(raw["kind"] == "numeric"),
    raw["value"] if raw["value"] is not None else 0,
    int(ratio["kind"] == "numeric"),
    ratio["value"] if ratio["value"] is not None else 0,
)
PY
}

assert_terminal_failure_not_increased() {
  local before_raw_present="$1"
  local before_raw_value="$2"
  local before_ratio_present="$3"
  local before_ratio_value="$4"
  docker run --rm -i \
    --network "${alert_project}_prometheus-ui" \
    --entrypoint python \
    finguardops-ai-service:local \
    - "$before_raw_present" "$before_raw_value" \
    "$before_ratio_present" "$before_ratio_value" <<'PY'
import json
import math
import sys
import urllib.parse
import urllib.request

before = {
    "raw_failed_counter": {
        "kind": "numeric" if sys.argv[1] == "1" else "missing",
        "value": float(sys.argv[2]) if sys.argv[1] == "1" else None,
    },
    "recorded_failure_ratio": {
        "kind": "numeric" if sys.argv[3] == "1" else "missing",
        "value": float(sys.argv[4]) if sys.argv[3] == "1" else None,
    },
}
base_url = "http://prometheus:9090/api/v1"
service = "spring-backend"
queries = {
    "raw_failed_counter": (
        'sum(finguardops_transaction_outcomes_total{service="spring-backend",status="FAILED"})',
        'max(timestamp(finguardops_transaction_outcomes_total{service="spring-backend",status="FAILED"}))',
        False,
    ),
    "recorded_failure_ratio": (
        'finguardops:transaction_terminal_failure:ratio5m{service="spring-backend"}',
        'timestamp(finguardops:transaction_terminal_failure:ratio5m{service="spring-backend"})',
        True,
    ),
}


def query(expression):
    encoded = urllib.parse.urlencode({"query": expression})
    with urllib.request.urlopen(base_url + "/query?" + encoded, timeout=5) as response:
        payload = json.load(response)
    if payload.get("status") != "success":
        raise RuntimeError(f"Prometheus query failed: {payload}")
    return payload["data"]["result"]


def sample(expression, timestamp_expression, require_service):
    result = query(expression)
    if not result:
        return {"kind": "missing", "value": None, "sample_timestamp": None}
    if len(result) != 1:
        raise RuntimeError(f"expected at most one series: {expression}: {result}")
    if require_service and result[0]["metric"].get("service") != service:
        raise RuntimeError(f"unexpected service series: {expression}: {result}")
    value = float(result[0]["value"][1])
    if not math.isfinite(value):
        raise RuntimeError(f"non-finite query value: {expression}: {value}")
    timestamp_result = query(timestamp_expression)
    if len(timestamp_result) != 1:
        raise RuntimeError(f"expected one timestamp series: {timestamp_expression}: {timestamp_result}")
    sample_timestamp = float(timestamp_result[0]["value"][1])
    if not math.isfinite(sample_timestamp):
        raise RuntimeError(f"non-finite timestamp: {timestamp_expression}: {sample_timestamp}")
    return {"kind": "numeric", "value": value, "sample_timestamp": sample_timestamp}


after = {
    name: sample(expression, timestamp_expression, require_service)
    for name, (expression, timestamp_expression, require_service) in queries.items()
}
for name in queries:
    earlier = before[name]
    later = after[name]
    if later["kind"] == "numeric":
        baseline = earlier["value"] if earlier["kind"] == "numeric" else 0.0
        if later["value"] > baseline + 1e-12:
            raise RuntimeError(f"terminal failure increased: {name}: before={earlier} after={later}")
evidence = {
    "phase": "external-risk-failure",
    "terminal_failure_before": before,
    "terminal_failure_after": after,
    "interpretation": (
        "terminal failure sample not created"
        if after["raw_failed_counter"]["kind"] == "missing"
        else "terminal FAILED counter unchanged"
    ),
}
print(json.dumps(evidence, sort_keys=True))
PY
}

assert_alert_rules_healthy() {
  docker run --rm -i \
    --network "${alert_project}_prometheus-ui" \
    --entrypoint python \
    finguardops-ai-service:local \
    - <<'PY'
import json
import urllib.request

with urllib.request.urlopen("http://prometheus:9090/api/v1/rules?type=alert", timeout=5) as response:
    groups = json.load(response)["data"]["groups"]
group = next(group for group in groups if group["name"] == "finguardops-service-alerts")
if len(group["rules"]) != 6 or any(rule["health"] != "ok" for rule in group["rules"]):
    raise RuntimeError(f"unhealthy alert group: {group}")
print(f"group={group['name']} rules={len(group['rules'])} evaluation={group['lastEvaluation']}")
PY
}
```

정상 traffic 뒤 `assert_alert_rules_healthy`를 실행하고 `/api/v1/alerts` 결과가 비어 있는지
확인한다. 장애 복구 뒤에는 아래 함수가 단일 600초 deadline 안에서 실제 recording ratio·guard의
복구 조건을 먼저 확인하고, 그 시점보다 새로운 alert group evaluation을 기다린다. 그 뒤
Prometheus `ALERTS` active series 부재와 alert rule 6개의 inactive를 30초 이상 간격으로 두 번
연속 확인한다. API 오류·요청 timeout·active alert 잔존은 즉시 또는 deadline에서 non-zero이며,
deadline 뒤 별도 조회로 timeout을 성공 처리하지 않는다.

```bash
wait_for_alerts_inactive() {
  local scenario="$1"
  docker run --rm -i \
    --network "${alert_project}_prometheus-ui" \
    --entrypoint python \
    finguardops-ai-service:local \
    - "$scenario" <<'PY'
import json
import math
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime

scenario = sys.argv[1]
base_url = "http://prometheus:9090/api/v1"
recovery_contract = {
    "external-risk": {
        "external_ratio": (
            'finguardops:external_risk_failure:ratio5m{service="spring-backend"}',
            "<=",
            0.10,
        ),
        "external_guard": (
            'sum by (service) (finguardops:external_risk_by_result:rate5m{service="spring-backend"})',
            ">=",
            0.10,
        ),
    },
    "rule-analysis": {
        "rule_ratio": (
            'finguardops:rule_analysis_failure:ratio5m{service="spring-backend"}',
            "<=",
            0.10,
        ),
        "rule_guard": (
            'sum by (service) (finguardops:rule_analysis_by_result:rate5m{service="spring-backend"})',
            ">=",
            0.10,
        ),
        "terminal_ratio": (
            'finguardops:transaction_terminal_failure:ratio5m{service="spring-backend"}',
            "<=",
            0.10,
        ),
        "terminal_guard": (
            'finguardops:transaction_terminal:rate5m{service="spring-backend"}',
            ">=",
            0.10,
        ),
    },
}[scenario]
alert_query = (
    'ALERTS{alertname=~"FinGuardOpsTransactionTerminalFailureRatioWarning|'
    'FinGuardOpsTransactionTerminalFailureRatioCritical|'
    'FinGuardOpsExternalRiskFailureRatioWarning|'
    'FinGuardOpsExternalRiskFailureRatioCritical|'
    'FinGuardOpsRuleAnalysisFailureRatioWarning|'
    'FinGuardOpsRuleAnalysisFailureRatioCritical",'
    'alertstate=~"pending|firing",service="spring-backend"}'
)
deadline = time.monotonic() + 600


def get_json(path, params=None):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError("600 second recovery deadline exceeded")
    query = "" if params is None else "?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(
        base_url + path + query,
        timeout=min(5, remaining),
    ) as response:
        payload = json.load(response)
    if payload.get("status") != "success":
        raise RuntimeError(f"Prometheus API failed: {path}: {payload}")
    return payload


def alert_group():
    groups = get_json("/rules", {"type": "alert"})["data"]["groups"]
    matches = [item for item in groups if item["name"] == "finguardops-service-alerts"]
    if len(matches) != 1:
        raise RuntimeError(f"expected one alert group: {matches}")
    return matches[0]


def evaluation_timestamp(group):
    return datetime.fromisoformat(group["lastEvaluation"].replace("Z", "+00:00")).timestamp()


readiness_evaluation = None
last_values = {}
while time.monotonic() < deadline:
    values = {}
    for name, (expression, comparison, threshold) in recovery_contract.items():
        result = get_json("/query", {"query": expression})["data"]["result"]
        if len(result) != 1 or result[0]["metric"].get("service") != "spring-backend":
            break
        value = float(result[0]["value"][1])
        if not math.isfinite(value):
            raise RuntimeError(f"non-finite recovery value: {name}: {value}")
        values[name] = value
        if comparison == "<=" and value > threshold:
            break
        if comparison == ">=" and value < threshold:
            break
    else:
        group = alert_group()
        if len(group["rules"]) != 6 or any(rule["health"] != "ok" for rule in group["rules"]):
            raise RuntimeError(f"unhealthy alert group at recovery readiness: {group}")
        readiness_evaluation = evaluation_timestamp(group)
        last_values = values
        break
    time.sleep(min(2, max(0, deadline - time.monotonic())))
else:
    raise RuntimeError(f"recovery readiness not reached in 600 seconds: {last_values}")

confirmations = []
last_state = None
while time.monotonic() < deadline:
    group = alert_group()
    active_series = get_json("/query", {"query": alert_query})["data"]["result"]
    active_alerts = get_json("/alerts")["data"]["alerts"]
    rules = group["rules"]
    evaluation = evaluation_timestamp(group)
    inactive = (
        evaluation > readiness_evaluation
        and not active_series
        and not active_alerts
        and len(rules) == 6
        and all(rule["health"] == "ok" and rule["state"] == "inactive" for rule in rules)
    )
    last_state = {
        "evaluation": group["lastEvaluation"],
        "active_series": len(active_series),
        "active_alerts": len(active_alerts),
        "rule_states": [rule["state"] for rule in rules],
    }
    now = time.monotonic()
    if inactive:
        if not confirmations:
            confirmations.append(now)
        elif now - confirmations[0] >= 30:
            confirmations.append(now)
            if time.monotonic() >= deadline:
                break
            print(json.dumps({
                "alerts": "inactive",
                "confirmations": 2,
                "interval_seconds": confirmations[1] - confirmations[0],
                "recovery_values": last_values,
                "scenario": scenario,
                **last_state,
            }, sort_keys=True))
            sys.exit(0)
    else:
        confirmations.clear()
    time.sleep(min(2, max(0, deadline - time.monotonic())))
raise RuntimeError(f"inactive was not confirmed twice within 600 seconds: {last_state}")
PY
}
```

### 8.3 bounded 장애 traffic

고정 요청 수와 순차 sleep은 요청 latency·15초 scrape·30초 evaluation과 5분 `rate`의
extrapolation 때문에 최소 처리율을 보장하지 않는다. 아래 generator는 worker를 2개 이하로
제한하고 각 요청 완료 1초 뒤 다음 요청을 보내며 최대 12분 동안 동작한다. failure 단계는
HTTP 503만, recovery 단계는 HTTP 201만 허용한다. 예상 밖 status, 요청 오류, 12분 timeout은
worker exit non-zero이며 로그에 남는다. 고유 key·transaction만 사용하고 실제 외부
Provider·LLM·유료 서비스를 호출하지 않는다.
Stop marker는 container 내부 `/tmp`에만 만들며 Git Bash의 자동 Windows path 변환을
`MSYS_NO_PATHCONV=1`로 차단한다.

```bash
set -euo pipefail

traffic_pid=""
traffic_source=""
traffic_stop_file=""
traffic_log=""
traffic_dir="$(mktemp -d "${TMPDIR:-/tmp}/finguardops-alert-traffic.XXXXXX")"

start_bounded_traffic() {
  local source_service="$1"
  local backend_url="$2"
  local expected_status="$3"
  local phase="$4"
  local maximum_seconds="$5"
  local worker_count="$6"
  local interval_seconds="$7"
  if [[ -n "$traffic_pid" ]]; then
    printf '%s\n' 'A traffic worker is already running' >&2
    return 1
  fi
  traffic_source="$source_service"
  traffic_stop_file="/tmp/finguardops-alert-${phase}-${RANDOM}.stop"
  traffic_log="${traffic_dir}/${phase}.log"
  MSYS_NO_PATHCONV=1 "${compose[@]}" exec -T "$source_service" \
    python - "$backend_url" "$expected_status" "$maximum_seconds" \
    "$worker_count" "$interval_seconds" "$traffic_stop_file" "$phase" \
    >"$traffic_log" 2>&1 <<'PY' &
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import signal
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from datetime import UTC, datetime

backend_url = sys.argv[1]
expected_status = int(sys.argv[2])
maximum_seconds = int(sys.argv[3])
worker_count = int(sys.argv[4])
interval_seconds = float(sys.argv[5])
stop_file = Path(sys.argv[6])
phase = sys.argv[7]
if not 1 <= worker_count <= 2:
    raise RuntimeError(f"worker count outside bounded range: {worker_count}")
if not 1 <= maximum_seconds <= 720:
    raise RuntimeError(f"traffic duration outside bounded range: {maximum_seconds}")
if not 0 <= interval_seconds <= 2:
    raise RuntimeError(f"traffic interval outside bounded range: {interval_seconds}")

stop_event = threading.Event()
lock = threading.Lock()
statuses = Counter()
errors = []
deadline = time.monotonic() + maximum_seconds


def handle_signal(signum, _frame):
    with lock:
        errors.append(f"unexpected signal={signum}")
    stop_event.set()


signal.signal(signal.SIGINT, handle_signal)
signal.signal(signal.SIGTERM, handle_signal)


def send_one(worker_id):
    while not stop_event.is_set():
        if stop_file.exists():
            stop_event.set()
            break
        if time.monotonic() >= deadline:
            with lock:
                errors.append(f"bounded traffic reached its {maximum_seconds} second limit")
            stop_event.set()
            break
        payload = {
            "transactionId": str(uuid.uuid4()),
            "transactionType": "ACCOUNT_TRANSFER",
            "amount": "10000",
            "currencyCode": "KRW",
            "occurredAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            "externalCustomerRef": "local-alert-customer",
            "senderAccountRef": "local-alert-sender",
            "recipientAccountRef": "local-alert-recipient",
            "channel": "MOBILE_BANKING",
            "deviceRef": f"local-alert-worker-{worker_id}",
        }
        request = urllib.request.Request(
            backend_url + "/api/v1/transactions",
            data=json.dumps(payload).encode(),
            headers={
                "Content-Type": "application/json",
                "Idempotency-Key": uuid.uuid4().hex,
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                response.read()
                status = response.status
        except urllib.error.HTTPError as error:
            error.read()
            status = error.code
        except OSError as error:
            with lock:
                errors.append(f"request error worker={worker_id}: {error}")
            stop_event.set()
            break
        with lock:
            statuses[status] += 1
            if status != expected_status:
                errors.append(
                    f"unexpected HTTP status worker={worker_id}: "
                    f"expected={expected_status} actual={status}"
                )
                stop_event.set()
        stop_event.wait(interval_seconds)


with ThreadPoolExecutor(max_workers=worker_count) as executor:
    futures = [executor.submit(send_one, worker_id) for worker_id in range(worker_count)]
    while not stop_event.is_set():
        if stop_file.exists():
            stop_event.set()
            break
        if all(future.done() for future in futures):
            break
        time.sleep(0.2)
    for future in futures:
        future.result()

summary = {
    "phase": phase,
    "expected_status": expected_status,
    "statuses": dict(sorted(statuses.items())),
    "errors": errors,
}
print(json.dumps(summary, sort_keys=True), flush=True)
if not statuses:
    raise RuntimeError(f"traffic worker produced no request: {summary}")
if errors:
    raise RuntimeError(f"traffic worker failed: {summary}")
PY
  traffic_pid="$!"
  printf 'traffic phase=%s pid=%s log=%s\n' "$phase" "$traffic_pid" "$traffic_log"
}

stop_bounded_traffic() {
  if [[ -z "$traffic_pid" ]]; then
    return 0
  fi
  MSYS_NO_PATHCONV=1 "${compose[@]}" exec -T "$traffic_source" \
    python - "$traffic_stop_file" <<'PY'
from pathlib import Path
import sys

Path(sys.argv[1]).touch()
PY
  local worker_status=0
  if wait "$traffic_pid"; then
    worker_status=0
  else
    worker_status="$?"
  fi
  cat "$traffic_log"
  MSYS_NO_PATHCONV=1 "${compose[@]}" exec -T "$traffic_source" \
    python - "$traffic_stop_file" <<'PY'
from pathlib import Path
import sys

Path(sys.argv[1]).unlink(missing_ok=True)
PY
  traffic_pid=""
  traffic_source=""
  traffic_stop_file=""
  traffic_log=""
  if (( worker_status != 0 )); then
    printf 'Traffic worker failed with exit code %s\n' "$worker_status" >&2
    return "$worker_status"
  fi
}

assert_bounded_traffic_running() {
  if [[ -z "$traffic_pid" ]] || ! kill -0 "$traffic_pid" 2>/dev/null; then
    stop_bounded_traffic || true
    printf '%s\n' 'Traffic worker exited before validation completed' >&2
    return 1
  fi
}

cleanup_alert_validation() {
  local exit_status="$?"
  trap - EXIT INT TERM
  set +e
  if [[ -n "$traffic_pid" ]]; then
    stop_bounded_traffic
    local worker_status="$?"
    if (( exit_status == 0 && worker_status != 0 )); then
      exit_status="$worker_status"
    fi
  fi
  if [[ -d "$traffic_dir" ]]; then
    rm -rf -- "$traffic_dir"
  fi
  exit "$exit_status"
}

finish_alert_validation() {
  stop_bounded_traffic
  trap - EXIT INT TERM
  rm -rf -- "$traffic_dir"
}

trap cleanup_alert_validation EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
```

readiness gate는 alert polling보다 먼저 실행한다. missing series는 최대 180초 동안 다시
조회하지만 빈 값·NaN·Inf·중복 service series와 API 오류는 즉시 실패한다. ratio는 실제
`>0.30`, guard는 alert 경계 0.10/s보다 여유 있는 실제 `>=0.15/s`를 만족해야 한다.
성공 출력에는 값과 recording sample timestamp가 포함된다. 이후 pending은 120초,
warning firing은 `activeAt`부터 최대 240초, critical firing은 `activeAt`부터 최대 420초로
검증한다. alert 자체의 threshold·guard·`for`는 변경하지 않는다.

External Risk 장애는 Mock을 중단하고 application network의 AI Service에서 Backend로
HTTP 503 traffic을 보낸다. 이 transport failure는 거래가 terminal outcome에 도달하기 전에
발생하므로 External Risk ratio·guard만 readiness로 사용하며 External Risk warning·critical
두 alert만 active여야 한다. 같은 시각 Transaction terminal과 Rule Analysis 네 alert가 하나라도
active이면 실패한다. 장애 전후 raw terminal FAILED Counter와 recorded failure ratio를 비교하고,
raw series가 없으면 숫자 0으로 바꾸지 않고 `terminal failure sample not created`로 출력한다.
이는 현재 계측 경계에 검증 기대값을 맞춘 것이며 production 장애 전파 정책 변경, 신규 Meter·tag
추가 또는 향후 terminal 계측 확대의 구현 완료를 의미하지 않는다. critical firing 확인 전에는
failure worker를 중단하지 않는다. 복구 뒤 HTTP 201 traffic을 유지하면서 5분 window의
희석·만료와 전체 alert 6개 inactive를 bounded polling한다.

```bash
read -r external_terminal_raw_present external_terminal_raw_before \
  external_terminal_ratio_present external_terminal_ratio_before < <(
  terminal_failure_snapshot before-external-risk-failure
)
"${compose[@]}" stop external-risk-mock
start_bounded_traffic \
  ai-service http://backend:8080 503 external-risk-failure 720 2 1
wait_for_condition_readiness external-risk 180
assert_bounded_traffic_running
wait_for_correlated_alert_transition external-risk 120 240 420
assert_bounded_traffic_running
assert_terminal_failure_not_increased \
  "$external_terminal_raw_present" "$external_terminal_raw_before" \
  "$external_terminal_ratio_present" "$external_terminal_ratio_before"
stop_bounded_traffic

"${compose[@]}" up -d --wait external-risk-mock
start_bounded_traffic \
  external-risk-mock http://127.0.0.1:8080 201 external-risk-recovery 720 1 1
wait_for_alerts_inactive external-risk
assert_bounded_traffic_running
stop_bounded_traffic
```

Rule Analysis 장애는 정상 상태의 External Risk Mock을 유지한 채 AI Service를 중단하고
loopback sidecar에서 Backend로 traffic을 보낸다. 현재 구현에서 Rule Analysis failure는
Transaction terminal failure와 함께 관찰되므로 두 ratio·guard readiness를 모두 확인한 뒤 두
family의 warning·critical 네 alert가 pending→firing하고 동시에 firing하는지 확인한다. 이때
External Risk 두 alert가 하나라도 active이면 실패한다. 이 시나리오 차이도 production 장애 전파
정책을 변경한다는 뜻이 아니다. AI Service를 복구한 뒤 HTTP 201 traffic을 유지하면서 최종
alert 6개가 모두 inactive인지 확인한다.

```bash
"${compose[@]}" stop ai-service
start_bounded_traffic \
  external-risk-mock http://127.0.0.1:8080 503 rule-analysis-failure 720 2 1
wait_for_condition_readiness rule-analysis 180
assert_bounded_traffic_running
wait_for_correlated_alert_transition rule-analysis 120 240 420
assert_bounded_traffic_running
stop_bounded_traffic

"${compose[@]}" up -d --wait ai-service
start_bounded_traffic \
  external-risk-mock http://127.0.0.1:8080 201 rule-analysis-recovery 720 1 1
wait_for_alerts_inactive rule-analysis
assert_bounded_traffic_running
stop_bounded_traffic
finish_alert_validation
```

worker는 stop file, PID와 로그를 `$traffic_dir` 아래에서 관리한다. 정상 완료, timeout,
assertion 실패, API 오류, `INT`와 `TERM` 모두 trap을 통해 worker를 중단하고 `wait`한 뒤
임시 파일을 제거한다. worker 조기 종료도 다음 readiness·상태 checkpoint에서 실패한다.
`keep_firing_for`가 없으므로 condition이 false가 된 다음 evaluation에서 inactive다.

Prometheus-only restart는 같은 named volume을 유지한 채 target `UP`, 두 rule group의
health `ok`, `lastEvaluation` 증가를 확인한다. 현재 `for` 2분·5분은 기본 10분 grace보다
짧으므로 pending 시간이 restart 전과 동일하게 보존된다고 가정하지 않는다. restart
검증은 회복 후 inactive 상태에서 수행한다.

검증 종료 시 아래 명령은 현재 `$alert_project`의 container·network와 fresh 검증 volume만
제거한다. 기존 기본 project의 보존 Prometheus volume은 삭제하지 않는다.

```bash
"${compose[@]}" down --volumes --remove-orphans
```

## 9. restart·Backend 재생성과 종료

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

backend_namespace="$("${compose[@]}" exec -T backend readlink /proc/self/ns/net </dev/null | tr -d '\r')"
mock_namespace="$("${compose[@]}" exec -T external-risk-mock readlink /proc/self/ns/net </dev/null | tr -d '\r')"
if [[ "$backend_namespace" != "$mock_namespace" ]]; then
  printf '%s\n' 'Backend and External Risk Mock network namespaces differ' >&2
  exit 1
fi
mock_listener="$("${compose[@]}" exec -T external-risk-mock \
  python -c "print(any('0100007F:1F41' in line and ' 0A ' in line for line in open('/proc/net/tcp')))" \
  </dev/null \
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

`down`에 `--volumes`를 사용하지 않는다. 업무 container와 네 network는 제거되고
`finguardops-local-observability_prometheus-data`와
`finguardops-local-observability_alertmanager-data`,
`finguardops-local-observability_grafana-data` named volume만 유지되어야 한다.

## 10. Alertmanager local routing·receiver 검증

### 10.1 topology와 delivery 의미

Prometheus는 `http://alertmanager:9093`의 v2 API로 연결한다. Alertmanager와
`alertmanager-webhook`은 internal `observability` network에만 연결하며 각각 `9093`, `8080`을
container에 expose하지만 host `ports`는 없다. Alertmanager UI도 publish하지 않는다. API 확인은
internal helper 또는 `docker compose exec`를 사용한다. network topology, Backend의
network·host publish·egress 계약과 Prometheus의 `127.0.0.1:9090` publish는 바뀌지 않는다.
internal network는 인증·TLS를 대신하지 않는다.

route는 `group_by: [alertname, service]`, `group_wait: 15s`, `group_interval: 30s`,
`repeat_interval: 30m`이다. `local-webhook`은 timeout 5초, `send_resolved: true`,
`max_alerts: 16`으로 설정한다. Transaction terminal, External Risk, Rule Analysis마다
alertname이 명시된 inhibition rule을 한 개씩 사용하고 모두 `equal: [service]`를 사용한다.
따라서 critical은 같은 service·같은 signal의 warning만 억제한다.

warning notification은 critical 발생 전에 이미 전달될 수 있다. inhibition은 과거
notification을 취소하지 않는다. delivery는 exactly-once나 고정 retry 횟수를 보장하지 않으며
receiver 장애, restart와 ambiguous failure에서 duplicate 또는 loss가 가능하다. event sequence와
재전달 여부는 관찰 결과이지 production 보장 계약이 아니다.

receiver는 production receiver가 아닌 표준 라이브러리 기반 local fixture다. 허용 endpoint는
`GET /health`, `POST /api/v1/alerts`, `GET /events`, `POST /events/reset`뿐이다. request body는
256 KiB, alert는 16개, in-memory ring은 256개로 제한한다. request 원문·credential·개인정보를
저장하거나 logging하지 않고 파일 저장·외부 호출도 하지 않는다. Alertmanager v0.34가 추가하는
`notification_reason`과 빈 `routeLabels`는 검증 후 event에 저장하지 않는다. reset은 event를
지우지만 sequence는 process 수명 동안 계속 단조 증가한다. receiver restart는 메모리 event와
sequence를 초기화한다.

### 10.2 image·config 확인

Alertmanager `v0.34.0` release와 Quay manifest-list digest, `linux/amd64` child를 먼저 확인한다.

```bash
alertmanager_image='quay.io/prometheus/alertmanager:v0.34.0@sha256:690c7b525f4367aa91f73e2f91c632206d32e97c6384bdbf2fb7a861b420340d'
repo_root="$(pwd -W)"
docker buildx imagetools inspect "$alertmanager_image"
docker pull "$alertmanager_image"
docker image inspect "$alertmanager_image" --format '{{json .Config.User}}'

export POSTGRES_PASSWORD='local-validation-only'
docker compose -f infra/compose.yml config --quiet
docker run --rm \
  -v "$repo_root/infra/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
  --entrypoint /bin/amtool \
  "$alertmanager_image" \
  check-config /etc/alertmanager/alertmanager.yml
docker run --rm \
  -v "$repo_root/infra/prometheus:/etc/prometheus:ro" \
  --entrypoint /bin/promtool \
  prom/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0 \
  check config /etc/prometheus/prometheus.yml
```

`amtool`은 runtime 설정 자체를 검사한다. 별도 test config, custom template와 config reload는
사용하지 않는다.

### 10.3 fresh project readiness와 internal API

기존 보존 volume과 분리된 project를 사용한다. 종료 trap은 이 project의 container·network와
fresh Prometheus·Alertmanager·Grafana volume만 제거한다.

```bash
delivery_project="finguardops-delivery-e2e-$RANDOM"
delivery_compose=(docker compose --env-file infra/.env -p "$delivery_project" -f infra/compose.yml)
cleanup_delivery() {
  "${delivery_compose[@]}" down --volumes --remove-orphans
}
trap cleanup_delivery EXIT INT TERM

"${delivery_compose[@]}" up -d --build --wait --wait-timeout 180
"${delivery_compose[@]}" ps
"${delivery_compose[@]}" exec -T alertmanager \
  wget -qO- http://127.0.0.1:9093/-/ready </dev/null
"${delivery_compose[@]}" exec -T alertmanager-webhook \
  python -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=5).read().decode())" \
  </dev/null
"${delivery_compose[@]}" exec -T external-risk-mock python - <<'PY'
import json
import urllib.request

with urllib.request.urlopen("http://prometheus:9090/api/v1/alertmanagers", timeout=5) as response:
    data = json.load(response)["data"]
assert len(data["activeAlertmanagers"]) == 1, data
assert not data["droppedAlertmanagers"], data
print(data)
PY
```

정상 traffic 뒤 receiver `/events`의 `count`는 0이어야 한다. Prometheus target은 `UP`, recording
rule 14개와 alert rule 6개는 모두 `health=ok`, Alertmanager는 ready여야 한다.

### 10.4 firing·inhibition·resolved 확인

8절의 bounded traffic·readiness 절차를 같은 fresh project에서 사용한다. External Risk Mock을
중단하면 External warning notification 뒤 critical notification을 확인하고 Alertmanager v2
API에서 External warning이 inhibited인지 확인한다. Transaction·Rule alert는 inactive여야 한다.
복구 뒤 resolved event와 최종 active alert 0개를 확인한다.

AI Service를 중단하면 Transaction·Rule warning과 critical notification을 signal별로 확인한다.
각 warning은 대응 critical에만 inhibited되고 두 signal 사이 inhibition 오염이 없어야 하며 External
alert는 inactive여야 한다. AI Service 복구 뒤 resolved event와 최종 active alert 0개를 확인한다.
각 receiver event에서 `sequence`, `status`, `receiver`, group/common label·annotation, alert의
status·label·annotation·startsAt·endsAt·fingerprint를 확인한다. 최초 notification이 alert firing
시각보다 최소 15초 뒤인지 확인하고 30분 repeat interval 안에 불필요한 동일 firing event가 없는지
확인한다.

실제 traffic으로 만들기 어려운 교차 signal·service 격리는 Alertmanager v2 API에 bounded synthetic
alert를 전송해 확인할 수 있다. 기존 여섯 alertname과 label·annotation만 사용하고 service는
`synthetic-routing-test`처럼 명백한 test 값으로 제한한다. 실제 고객·거래·계좌 ID를 사용하지
않으며 짧은 `startsAt`·`endsAt`을 사용한다. 종료 시 같은 alert를 resolved로 보내고 receiver
event를 reset한다. runtime config를 그대로 사용하며 별도 test config로 바꾸지 않는다.

반드시 다음 조합을 확인한다.

- Transaction critical은 Transaction warning만 억제한다.
- External critical은 External warning만 억제하고 Transaction·Rule warning을 억제하지 않는다.
- Rule critical은 Rule warning만 억제한다.
- 같은 signal이라도 다른 service의 warning은 억제하지 않는다.
- critical resolved 뒤 남아 있는 warning은 inhibited 상태에서 벗어난다.
- critical 전에 receiver로 전달된 warning event는 취소되지 않는다.

### 10.5 receiver HTTP와 bounded store 직접 검증

receiver에 internal helper로 요청해 health, valid firing·resolved, event order, reset 뒤 단조 sequence,
ring 256 eviction, alert 16개 허용과 17개 거절을 확인한다. 유효 JSON 뒤 공백으로 body를 정확히
256 KiB로 맞춘 요청은 허용하고 1 byte 초과는 `413`이어야 한다. malformed JSON, schema 오류,
unexpected label·annotation, invalid·negative Content-Length와 chunked body는 안전하게 거절해야
한다. missing Content-Length는 `411`, 잘못된 Content-Type은 `415`, unknown path는 `404`,
unsupported method는 `405`다. 모든 JSON 응답은 `Content-Type`과 `Content-Length`를 명시한다.

container의 writable layer가 없고 receiver volume이 source read-only 하나뿐인지 확인한다.
`docker compose logs alertmanager-webhook`에는 request body나 stack trace가 없어야 하며 event reset
뒤 `/events`는 빈 배열이어야 한다. receiver는 외부 network에 연결되지 않는다.

### 10.6 장애·restart와 종료

Alertmanager 중단 중에도 Backend 업무 요청, Prometheus target·scrape, recording rule 14개와 alert
rule 6개 evaluation이 계속되어야 한다. `/api/v1/alertmanagers`의 active/dropped 상태를 확인하고
복구 뒤 연결이 재개되는지 확인한다. receiver 중단에서는 Alertmanager ready와 notification 전달
실패를 구분하고 Backend·Prometheus가 계속 동작하는지 확인한다. receiver 복구 뒤 실제 delivery
결과를 관찰하되 retry 횟수나 exactly-once를 보장으로 기록하지 않는다.

Alertmanager-only, receiver-only, Prometheus-only restart 뒤 target·health·rule evaluation과 연결이
재개되는지 확인한다. Alertmanager의 `/alertmanager` mount가 같은 named volume인지 확인한다.
receiver-only restart는 in-memory event를 잃는 것이 정상이다. restart 전후 event sequence로
duplicate·재전달을 관찰하지만 이를 무손실 계약으로 해석하지 않는다.

종료 전에 synthetic alert를 resolved 처리하고 receiver event를 reset하며 bounded traffic worker가
0개인지 확인한다. trap 또는 아래 명령으로 검증 project만 제거한다. 기본 project의 기존
Prometheus volume은 제거하지 않는다.

```bash
"${delivery_compose[@]}" exec -T alertmanager-webhook \
  python -c "import urllib.request; print(urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8080/events/reset', data=b'', method='POST'), timeout=5).read().decode())" \
  </dev/null
"${delivery_compose[@]}" down --volumes --remove-orphans
trap - EXIT INT TERM
```

외부 Slack·email·SMS·PagerDuty, production receiver·Prometheus·Alertmanager·Grafana·
credential 배포·SLA·SLO, 인증·TLS·SSO·RBAC, Kubernetes·AWS, HA·장기 retention과
OpenTelemetry는 계속 미구현이다.

## 11. Grafana local dashboard 검증

### 11.1 runtime·credential·volume 경계

Grafana image는
`grafana/grafana:13.2.0@sha256:3fd54ae1214669f8355f065ec9f6445d5279a3d77095ab048ca045685272429b`
manifest-list로 고정하며 `linux/amd64` child를 지원한다. container는 `472:0`, read-only root
filesystem, `no-new-privileges`, `/tmp` tmpfs를 사용한다. provisioning과 dashboard source는
read-only bind mount이고 `/var/lib/grafana`만 `grafana-data` named volume이다. console logging만
사용하고 analytics·Grafana update check·plugin update와 plugin 설치 UI를 비활성화한다.

`GRAFANA_ADMIN_USER`와 `GRAFANA_ADMIN_PASSWORD`는 기본값이 없는 필수 변수다. 실제 값은
ignored `infra/.env`에서만 제공하고 출력하지 않는다. 이 두 값은 fresh volume의 bootstrap에만
사용된다. existing volume에서 환경 변수 값을 바꿔도 기존 admin 계정 ID·password가 자동으로
변경되지 않는다. 기존 계정을 변경해야 하면 별도의 승인된 Grafana admin 절차를 사용하거나,
보존할 필요가 없는 로컬 검증 volume임을 확인한 뒤 fresh volume으로 다시 시작한다.

Datasource와 dashboard source 계약은 다음 명령으로 외부 Python dependency 없이 검증한다.

```bash
python infra/grafana/tests/verify_dashboard.py static
python infra/grafana/tests/verify_dashboard.py mutations
```

Mutation mode는 임시 디렉터리의 복사본만 변경한다. 기존 5종인 잘못된 datasource URL,
존재하지 않는 recording rule, 누락 panel, 잘못된 unit, dashboard UID와 함께 승인되지 않은
datasource/dashboard 추가, 잘못된 title, 누락 description, 중복 panel ID를 non-zero로 검출한다.
또한 Grafana frame의 numeric sample matrix, runtime inventory exact count와 credential 전송 전
CLI URL·timeout 경계를 self-test한다. source 파일은 변경하지 않는다.

### 11.2 fresh provisioning·traffic·query

기존 기본 project와 보존 volume을 건드리지 않는 fresh project를 만든다. trap은 이 project의
container·network·Prometheus·Alertmanager·Grafana volume만 제거한다. `up --wait`의 실제
deadline은 180초이며 각 service healthcheck를 polling해 고정 sleep으로 readiness를 추정하지 않는다.

```bash
dashboard_project="finguardops-grafana-e2e-$RANDOM"
dashboard_compose=(
  docker compose
  --env-file infra/.env
  -p "$dashboard_project"
  -f infra/compose.yml
)
cleanup_dashboard() {
  "${dashboard_compose[@]}" down --volumes --remove-orphans
}
trap cleanup_dashboard EXIT INT TERM

"${dashboard_compose[@]}" config --quiet
"${dashboard_compose[@]}" up -d --build --wait --wait-timeout 180
"${dashboard_compose[@]}" ps
curl --fail-with-body http://127.0.0.1:3000/api/health

anonymous_status="$(
  curl --silent --output /dev/null --write-out '%{http_code}' \
    http://127.0.0.1:3000/api/search?type=dash-db
)"
[[ "$anonymous_status" == "401" ]]

python infra/grafana/tests/verify_dashboard.py runtime \
  --allow-empty-recording \
  --require-alert-state inactive
```

Runtime mode는 admin credential을 환경 변수에서 읽되 출력하지 않는다. `--grafana-url`은
명시적인 1~65535 port를 가진 `http://127.0.0.1`의 빈 path 또는 `/`만 허용하고 userinfo,
query, fragment, IPv6, 다른 host·scheme·하위 path는 HTTP 요청 전에 거절한다. HTTP timeout은
finite `(0, 60]`, 전체 deadline은 finite `(0, 1800]`이면서 HTTP timeout 이상이어야 한다.
Grafana health와 anonymous 차단, datasource 정확히 1개, folder 정확히 1개, dashboard 정확히
1개, datasource UID·type·내부 URL·default·editable=false·health, source/API의
UID·title·refresh·time·16개 exact title·description 계약을 확인한다. 또한 `/api/ds/query`를 통해
target과 recording·alert PromQL을 실제 실행하며 time field가 아닌 number field의 finite numeric
sample만 non-empty로 인정한다.

이 fresh project에서 4절의 Rule v1 local 발행, 5절의 1차 traffic, 6절의 실제 scrape와 2차
traffic, 7절의 raw·recording 대조를 순서대로 수행한다. 아래처럼 기존 변수명을 fresh project에
연결하면 앞 절의 명령을 그대로 재사용할 수 있다.

```bash
compose=("${dashboard_compose[@]}")
alert_project="$dashboard_project"
```

후속 scrape와 recording evaluation 뒤 정확히 14개 recording panel query가 모두 non-empty이고
target panel이 `UP=1`인지 bounded polling한다.

```bash
python infra/grafana/tests/verify_dashboard.py runtime \
  --deadline-seconds 180 \
  --require-alert-state inactive
```

Ratio panel은 원본 0~1을 percent unit으로 표시하며 duration은 seconds다. inactive alert는
`ALERTS` series 부재를 뜻한다. 검증기와 dashboard 어디에서도 `or vector(0)`, `clamp_min` 또는
다른 zero synthesis를 사용하지 않는다.

### 11.3 pending·firing·inactive alert query

8.2절과 8.3절의 함수 및 bounded traffic을 같은 fresh project에서 사용한다. 장애 traffic을
시작하기 전에 pending 검증을 background에서 시작하면 worker와 Grafana query가 함께 bounded
polling된다. 다음은 External Risk signal 예시다. background 검증도 `wait` exit code로 확인하고
marker 출력만으로 성공 처리하지 않는다.
Alert-state 검증은 앞 단계에서 별도로 통과한 recording 14개 non-empty 계약을 다시 결합하지
않는다. 장애가 5분 이상 지속되어 정상 outcome rate series가 만료되어도 alert query의 실제
pending·firing·inactive 판정만 검증하도록 `--allow-empty-recording`을 명시한다.

```bash
"${compose[@]}" stop external-risk-mock
start_bounded_traffic \
  ai-service http://backend:8080 503 grafana-external-risk-failure 720 2 1
python infra/grafana/tests/verify_dashboard.py runtime \
  --deadline-seconds 240 \
  --allow-empty-recording \
  --require-alert-state pending &
pending_check_pid=$!

wait_for_condition_readiness external-risk 180
assert_bounded_traffic_running
wait_for_correlated_alert_transition external-risk 120 240 420
wait "$pending_check_pid"
python infra/grafana/tests/verify_dashboard.py runtime \
  --deadline-seconds 120 \
  --allow-empty-recording \
  --require-alert-state firing
assert_bounded_traffic_running
stop_bounded_traffic

"${compose[@]}" up -d --wait external-risk-mock
start_bounded_traffic \
  external-risk-mock http://127.0.0.1:8080 201 grafana-alert-recovery 720 1 1
wait_for_alerts_inactive external-risk
assert_bounded_traffic_running
stop_bounded_traffic
python infra/grafana/tests/verify_dashboard.py runtime \
  --deadline-seconds 120 \
  --allow-empty-recording \
  --require-alert-state inactive
```

Transaction terminal과 Rule Analysis도 8절의 기존 장애 시나리오로 같은 검증을 반복한다.
Dashboard alert query는 기존 alertname 6개, `service="spring-backend"`, `pending|firing`만
허용한다. recovery 뒤 query가 빈 결과인 것은 정상 inactive 의미이며 값 0 series를 만들지 않는다.

### 11.4 restart·recreate·장애 격리

먼저 Prometheus만 restart하고 datasource health와 14개 recording query가 다시 성공하는지
확인한다. 그 다음 Grafana를 restart하고 같은 named volume과 provisioning·query를 확인한다.
Grafana force-recreate도 source provisioning으로 같은 UID·folder·16 panel에 수렴해야 한다.

```bash
grafana_volume_before="$(
  docker inspect "$("${compose[@]}" ps -q grafana)" \
    --format '{{range .Mounts}}{{if eq .Destination "/var/lib/grafana"}}{{.Name}}{{end}}{{end}}'
)"
[[ -n "$grafana_volume_before" ]]

"${compose[@]}" restart prometheus
python infra/grafana/tests/verify_dashboard.py runtime \
  --deadline-seconds 180 \
  --require-alert-state inactive

"${compose[@]}" restart grafana
python infra/grafana/tests/verify_dashboard.py runtime \
  --deadline-seconds 180 \
  --require-alert-state inactive

"${compose[@]}" up -d --force-recreate --wait grafana
grafana_volume_after="$(
  docker inspect "$("${compose[@]}" ps -q grafana)" \
    --format '{{range .Mounts}}{{if eq .Destination "/var/lib/grafana"}}{{.Name}}{{end}}{{end}}'
)"
[[ "$grafana_volume_after" == "$grafana_volume_before" ]]
python infra/grafana/tests/verify_dashboard.py runtime \
  --deadline-seconds 180 \
  --require-alert-state inactive
```

Grafana 장애 격리는 앞선 alert 검증의 상태를 재사용하지 않고 별도의 External Risk
firing→resolved 시나리오로 실행한다. Grafana만 중단하고 나머지 service의 container ID,
`State.StartedAt`, `RestartCount`를 고정한 뒤,
Backend·Prometheus·recording 14개·alert 6개·Alertmanager·receiver를 확인한다. 이후 실제 장애
traffic은 Mock container를 재시작하지 않고 pause하여 만들고, pending→warning firing→critical
firing과 Alertmanager/receiver firing 전달을 확인한다. recovery 전에 같은 container를 unpause하고,
복구 traffic과 600초 recovery polling으로 Prometheus·Alertmanager active 0 및 receiver resolved를
확인한다. 이 전체 구간에서 Grafana는 계속 stopped여야 한다.

```bash
isolation_services=(
  postgresql
  ai-service
  external-risk-mock
  backend
  prometheus
  alertmanager
  alertmanager-webhook
)

snapshot_non_grafana_services() {
  local ids_name="$1"
  local started_name="$2"
  local restarts_name="$3"
  local -n ids_ref="$ids_name"
  local -n started_ref="$started_name"
  local -n restarts_ref="$restarts_name"
  local expected_services actual_services service container_id state_line
  local inspected_id started_at restart_count
  local -a service_ids

  ids_ref=()
  started_ref=()
  restarts_ref=()
  expected_services="$(printf '%s\n' "${isolation_services[@]}" | sort)"
  actual_services="$(
    "${compose[@]}" ps --services --status running |
      grep -v '^grafana$' |
      sort
  )"
  if [[ "$actual_services" != "$expected_services" ]]; then
    printf '%s\n' 'Non-Grafana running service set changed' >&2
    return 1
  fi

  for service in "${isolation_services[@]}"; do
    mapfile -t service_ids < <("${compose[@]}" ps -q "$service")
    if (( ${#service_ids[@]} != 1 )); then
      printf 'Expected exactly one container for %s, got %s\n' \
        "$service" "${#service_ids[@]}" >&2
      return 1
    fi
    container_id="${service_ids[0]}"
    state_line="$(
      docker inspect "$container_id" \
        --format '{{.Id}} {{.State.StartedAt}} {{.RestartCount}}'
    )"
    read -r inspected_id started_at restart_count <<<"$state_line"
    if [[ -z "$inspected_id" || -z "$started_at" || ! "$restart_count" =~ ^[0-9]+$ ]]; then
      printf 'Invalid container state for %s\n' "$service" >&2
      return 1
    fi
    ids_ref["$service"]="$inspected_id"
    started_ref["$service"]="$started_at"
    restarts_ref["$service"]="$restart_count"
  done
}

assert_non_grafana_services_unchanged() {
  local ids_name="$1"
  local started_name="$2"
  local restarts_name="$3"
  local -n expected_ids="$ids_name"
  local -n expected_started="$started_name"
  local -n expected_restarts="$restarts_name"
  local service
  local -A current_ids=()
  local -A current_started=()
  local -A current_restarts=()

  if ! snapshot_non_grafana_services \
    current_ids current_started current_restarts; then
    return 1
  fi
  for service in "${isolation_services[@]}"; do
    if [[ "${current_ids[$service]}" != "${expected_ids[$service]}" ||
      "${current_started[$service]}" != "${expected_started[$service]}" ||
      "${current_restarts[$service]}" != "${expected_restarts[$service]}" ]]; then
      printf 'Non-Grafana service restarted or was replaced: %s\n' "$service" >&2
      return 1
    fi
  done
}

declare -A isolation_ids_before
declare -A isolation_started_before
declare -A isolation_restarts_before
snapshot_non_grafana_services \
  isolation_ids_before isolation_started_before isolation_restarts_before

"${compose[@]}" exec -T alertmanager-webhook \
  python -c "import urllib.request; urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8080/events/reset', data=b'', method='POST'), timeout=5).read()" \
  </dev/null
"${compose[@]}" stop grafana
[[ "$(docker inspect "$("${compose[@]}" ps -aq grafana)" --format '{{.State.Status}}')" == "exited" ]]

"${compose[@]}" exec -T external-risk-mock python - <<'PY'
import json
import urllib.request

with urllib.request.urlopen("http://127.0.0.1:8080/api/health", timeout=5) as response:
    assert response.status == 200
with urllib.request.urlopen("http://prometheus:9090/api/v1/targets", timeout=5) as response:
    targets = json.load(response)["data"]["activeTargets"]
assert any(item["health"] == "up" for item in targets), targets
with urllib.request.urlopen("http://prometheus:9090/api/v1/rules", timeout=5) as response:
    groups = json.load(response)["data"]["groups"]
recording = next(group for group in groups if group["name"] == "finguardops-service-derived")
alerts = next(group for group in groups if group["name"] == "finguardops-service-alerts")
assert len(recording["rules"]) == 14 and all(rule["health"] == "ok" for rule in recording["rules"])
assert len(alerts["rules"]) == 6 and all(rule["health"] == "ok" for rule in alerts["rules"])
PY
"${compose[@]}" exec -T alertmanager \
  wget -qO- http://127.0.0.1:9093/-/ready </dev/null

isolation_mock_id="$("${compose[@]}" ps -q external-risk-mock)"
docker pause "$isolation_mock_id"
[[ "$(docker inspect "$isolation_mock_id" --format '{{.State.Paused}}')" == "true" ]]
start_bounded_traffic \
  ai-service http://backend:8080 503 grafana-down-external-risk-failure 720 2 1
wait_for_condition_readiness external-risk 180
assert_bounded_traffic_running
wait_for_correlated_alert_transition external-risk 120 240 420
assert_bounded_traffic_running
stop_bounded_traffic

"${compose[@]}" exec -T alertmanager-webhook python - <<'PY'
import json
import time
import urllib.request

deadline = time.monotonic() + 120
while time.monotonic() < deadline:
    with urllib.request.urlopen("http://alertmanager:9093/api/v2/alerts", timeout=5) as response:
        active = json.load(response)
    with urllib.request.urlopen("http://127.0.0.1:8080/events", timeout=5) as response:
        events = json.load(response)["events"]
    external = [
        alert for alert in active
        if alert["labels"].get("alertname", "").startswith(
            "FinGuardOpsExternalRiskFailureRatio"
        )
    ]
    firing_events = [
        event for event in events
        if event["status"] == "firing"
        and event["commonLabels"].get("alertname", "").startswith(
            "FinGuardOpsExternalRiskFailureRatio"
        )
    ]
    if external and firing_events:
        print(json.dumps({"active": len(external), "firing_events": len(firing_events)}))
        break
    time.sleep(2)
else:
    raise RuntimeError("Alertmanager active and receiver firing delivery not observed")
PY
[[ "$(docker inspect "$("${compose[@]}" ps -aq grafana)" --format '{{.State.Status}}')" == "exited" ]]

docker unpause "$isolation_mock_id"
isolation_mock_health_deadline="$(( $(date -u +%s) + 60 ))"
while (( $(date -u +%s) < isolation_mock_health_deadline )); do
  isolation_mock_health="$(
    docker inspect "$isolation_mock_id" \
      --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}'
  )"
  if [[ "$isolation_mock_health" == "healthy" ]]; then
    break
  fi
  sleep 2
done
if [[ "$isolation_mock_health" != "healthy" ]]; then
  printf '%s\n' 'External Risk Mock did not recover after unpause' >&2
  exit 1
fi
start_bounded_traffic \
  external-risk-mock http://127.0.0.1:8080 201 grafana-down-alert-recovery 720 1 1
wait_for_alerts_inactive external-risk
assert_bounded_traffic_running
stop_bounded_traffic

"${compose[@]}" exec -T alertmanager-webhook python - <<'PY'
import json
import time
import urllib.request

deadline = time.monotonic() + 120
while time.monotonic() < deadline:
    with urllib.request.urlopen("http://alertmanager:9093/api/v2/alerts", timeout=5) as response:
        active = json.load(response)
    with urllib.request.urlopen("http://127.0.0.1:8080/events", timeout=5) as response:
        events = json.load(response)["events"]
    resolved_events = [
        event for event in events
        if event["status"] == "resolved"
        and event["commonLabels"].get("alertname", "").startswith(
            "FinGuardOpsExternalRiskFailureRatio"
        )
    ]
    if not active and resolved_events:
        print(json.dumps({"active": 0, "resolved_events": len(resolved_events)}))
        break
    time.sleep(2)
else:
    raise RuntimeError("Alertmanager active 0 and receiver resolved delivery not observed")
PY
[[ "$(docker inspect "$("${compose[@]}" ps -aq grafana)" --format '{{.State.Status}}')" == "exited" ]]

assert_non_grafana_services_unchanged \
  isolation_ids_before isolation_started_before isolation_restarts_before

"${compose[@]}" up -d --wait grafana
python infra/grafana/tests/verify_dashboard.py runtime \
  --deadline-seconds 180 \
  --allow-empty-recording \
  --require-alert-state inactive

declare -A restart_mutation_ids
declare -A restart_mutation_started
declare -A restart_mutation_restarts
snapshot_non_grafana_services \
  restart_mutation_ids restart_mutation_started restart_mutation_restarts
"${compose[@]}" restart external-risk-mock
if assert_non_grafana_services_unchanged \
  restart_mutation_ids restart_mutation_started restart_mutation_restarts; then
  printf '%s\n' 'Non-Grafana restart mutation was not detected' >&2
  exit 1
else
  printf '%s\n' 'Non-Grafana restart mutation detected'
fi
"${compose[@]}" up -d --wait external-risk-mock
```

Grafana 재기동 뒤 datasource·folder·dashboard exact count와 16개 panel provisioning이 자동
복구되고 현재 `ALERTS` panel이 inactive series 부재를 유지해야 한다. 위 비교는 Backend,
Prometheus, Alertmanager, receiver를 포함한 7개 service 각각의 ID·시작 시각·restart 횟수를
확인한다. 같은 fresh 임시 project에서 External Risk Mock을 실제 restart한 mutation이 non-zero로
검출되는지 확인하며, project와 mutation 상태는 11.6 cleanup에서 함께 제거한다.
delivery는 exactly-once나 고정 retry 횟수를 주장하지 않는다.

### 11.5 network·port·egress·sidecar 회귀

모든 service의 host publish를 합치면 정확히 두 줄이어야 한다. `docker port` 결과에 wildcard나
IPv6 bind가 하나라도 추가되면 비교가 실패한다.

```bash
actual_ports="$(
  for service in \
    postgresql ai-service external-risk-mock backend prometheus grafana \
    alertmanager alertmanager-webhook; do
    container_id="$("${compose[@]}" ps -q "$service")"
    if [[ -n "$container_id" ]]; then
      docker port "$container_id"
    fi
  done | sort
)"
expected_ports="$(
  printf '%s\n' \
    '3000/tcp -> 127.0.0.1:3000' \
    '9090/tcp -> 127.0.0.1:9090' | sort
)"
[[ "$actual_ports" == "$expected_ports" ]]
```

Backend와 Grafana network, grafana-ui 단독 membership, Backend default route 부재와 Mock의
동일 namespace·loopback socket을 검사한다. project 이름은 Compose network prefix와 같아야 한다.

```bash
network_names() {
  docker inspect "$1" \
    --format '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' \
    | sed "s/^${dashboard_project}_//" \
    | sed '/^$/d' \
    | sort
}

backend_id="$("${compose[@]}" ps -q backend)"
grafana_id="$("${compose[@]}" ps -q grafana)"
[[ "$(network_names "$backend_id")" == $'application\nobservability' ]]
[[ "$(network_names "$grafana_id")" == $'grafana-ui\nobservability' ]]
[[ "$(
  docker network inspect "${dashboard_project}_grafana-ui" \
    --format '{{len .Containers}}'
)" == "1" ]]

if "${compose[@]}" exec -T backend bash -ec '
  while read -r _ destination _; do
    [[ "$destination" == "00000000" ]] && exit 0
  done < /proc/net/route
  exit 1
' </dev/null; then
  printf '%s\n' 'Backend unexpectedly has a default route' >&2
  exit 1
fi

backend_namespace="$("${compose[@]}" exec -T backend readlink /proc/self/ns/net </dev/null | tr -d '\r')"
mock_namespace="$("${compose[@]}" exec -T external-risk-mock readlink /proc/self/ns/net </dev/null | tr -d '\r')"
[[ "$backend_namespace" == "$mock_namespace" ]]
mock_listener="$("${compose[@]}" exec -T external-risk-mock \
  python -c "print(any('0100007F:1F41' in line and ' 0A ' in line for line in open('/proc/net/tcp')))" \
  </dev/null \
  | tr -d '\r')"
[[ "$mock_listener" == "True" ]]
```

9절의 stale Mock unhealthy 검출과 안전 재생성을 다시 수행한 뒤 거래 `201`, replay `201`,
conflict `409`를 확인한다. Backend는 application·observability에만, Mock은 Backend network
namespace에만 남아야 하며 외부 default route가 없어야 한다.

### 11.6 종료와 production 경계

종료 전에 모든 bounded traffic worker를 중지하고 background pending 검증 process를 `wait`한다.
임시 query·response·mutation 파일을 repository에 만들지 않았는지 확인한다. 아래 명령은 fresh
검증 project의 container·network·volume만 제거하며 기존 사용자가 보존한 기본 project volume은
삭제하지 않는다.

```bash
finish_alert_validation
"${compose[@]}" exec -T alertmanager-webhook \
  python -c "import urllib.request; print(urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8080/events/reset', data=b'', method='POST'), timeout=5).read().decode())" \
  </dev/null
"${dashboard_compose[@]}" down --volumes --remove-orphans
trap - EXIT INT TERM

[[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=${dashboard_project}")" ]]
[[ -z "$(docker network ls -q --filter "label=com.docker.compose.project=${dashboard_project}")" ]]
[[ -z "$(docker volume ls -q --filter "label=com.docker.compose.project=${dashboard_project}")" ]]
```

이 로컬 Grafana는 production credential 배포, TLS·SSO·RBAC, HA·장기 retention, production
Prometheus·Alertmanager, 외부 Slack·email·SMS·PagerDuty, Kubernetes·AWS·OpenTelemetry,
production SLA·SLO·threshold를 구현하지 않는다. 추가 사건·AI·복구 metric, completion-gap
alert와 장기 `IN_PROGRESS` Gauge도 계속 미구현이다.
