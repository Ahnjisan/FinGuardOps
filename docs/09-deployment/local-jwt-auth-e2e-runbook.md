# Local JWT 인증 E2E runbook

## 1. 목적과 비운영 경계

이 문서는 Issue #225의 local/manual 인증 검증 절차다. `local-jwt-fixture`는 Python
표준 라이브러리와 image 내 OpenSSL로 만든 저장소 관리 fixture이며 production
Authorization Server, 사용자 로그인 서버 또는 token endpoint가 아니다. GitHub Actions
workflow에서는 실행하지 않는다.

고정 계약은 다음과 같다.

- image: `python:3.12.11-slim-bookworm@sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7`
- issuer: `https://local-jwt.fixture.finguardops.invalid`
- audience: JSON array `[` `"finguardops-backend-api"` `]`
- JWKS transport: `http://127.0.0.1:8002/oauth2/jwks`
- readiness: `http://127.0.0.1:8002/ready`
- control: `/run/local-jwt/control.sock`, mode `0600`, owner `10001:10001`
- RS256, RSA 2048 bit 이상, exponent 65537, 최대 lifetime 15분

HTTP는 readiness와 JWKS의 `GET`만 제공한다. token 발급·rotation·fault 제어는 fixture
tmpfs의 Unix socket과 container CLI만 사용한다. private key와 socket은 4 MiB 전용
`tmpfs` 밖에 저장하지 않으며 container 재생성 때 key와 `kid`가 바뀐다.

## 2. 공개 test identity

아래 UUID는 실제 사용자·고객 식별자가 아닌 공개 고정 UUID v4다. 호출자는 `sub`,
`principal_type`, role, issuer, audience, lifetime, algorithm 또는 `kid`를 바꿀 수 없다.

| identity | sub | type | roles |
| --- | --- | --- | --- |
| `service-transaction-ingestor` | `9d0edbde-f833-43e2-822a-43a1c38d82ec` | SERVICE | `TRANSACTION_INGESTOR` |
| `service-behavior-ingestor` | `a0dc7e4b-1260-4888-9e14-54867c9f2293` | SERVICE | `BEHAVIOR_INGESTOR` |
| `user-viewer` | `3d005f9e-f48e-45e9-98f1-5f9c407d2021` | USER | `FDS_VIEWER` |
| `user-analyst` | `8fbcd138-76f7-44a8-85f1-3afcf118f1c6` | USER | `FDS_ANALYST` |
| `user-approver` | `f5b2501d-0c30-462b-b699-8cbb7aa6f3f2` | USER | `FDS_APPROVER` |
| `user-analyst-approver` | `35b78471-c387-48a8-af51-3490c8718216` | USER | `FDS_ANALYST`, `FDS_APPROVER` |
| `user-platform-admin` | `edaa43d7-c04f-4195-a7f7-82ee7f1a0de1` | USER | `PLATFORM_ADMIN` |

정상 identity와 fixture source에 열거된 negative variant만 허용된다. `jku`, `x5u`,
`scope`, `authorities` claim은 만들지 않는다.

## 3. 사전 검증과 실행

실제 `infra/.env`는 Git에 포함하지 않는다. `infra/.env.example`의 local placeholder를
복사하고 새 값을 사용한다. 프로젝트 이름은 다른 Compose project를 건드리지 않도록
`finguardops-jwt-e2e-` 접두사의 새 이름을 사용한다.

```bash
python -m py_compile \
  infra/local-jwt-fixture/fixture.py \
  infra/local-jwt-fixture/verify_e2e.py \
  infra/local-jwt-fixture/tests/test_fixture.py

docker run --rm --user 10001:10001 \
  --read-only \
  --tmpfs /tmp:rw,nosuid,nodev,noexec,size=8m,mode=1777 \
  --tmpfs /run/local-jwt:rw,nosuid,nodev,noexec,size=4m,mode=0700,uid=10001,gid=10001 \
  --mount type=bind,src="$(pwd)/infra/local-jwt-fixture",dst=/opt/local-jwt-fixture,readonly \
  --entrypoint python \
  python:3.12.11-slim-bookworm@sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7 \
  -m unittest discover -s /opt/local-jwt-fixture/tests -v

python infra/local-jwt-fixture/verify_e2e.py static \
  --project finguardops-jwt-e2e-static
python infra/local-jwt-fixture/verify_e2e.py mutations \
  --project finguardops-jwt-e2e-mutations
```

전체 local/manual 검증은 fresh project를 만들고 bounded readiness를 사용한 뒤 자신이 만든
container·network·volume만 정리한다.

```bash
set +x
python infra/local-jwt-fixture/verify_e2e.py all \
  --project finguardops-jwt-e2e-manual01 \
  --cli-timeout 30 \
  --deadline-seconds 3600
```

`all`은 static, token-claim, runtime, SERVICE·USER matrix, rotation·failure,
observability, sidecar lifecycle과 cleanup을 순서대로 실행한다. 실패한 단계는 같은 최종
source에서 영향 범위만 재실행할 수 있다. 장시간 traffic worker는 token의 `exp` 잔여시간이
안전 기준 이하이면 새 machine token을 메모리로 받아 교체해야 한다.

## 4. token CLI의 두 mode

Machine mode는 verifier 전용이다. JWT는 캡처된 subprocess pipe 한 줄로만 반환되고 부모는
이를 출력·로그·예외에 전달하지 않는다. token은 다음 HTTP 요청의 메모리나 stdin에만
전달하며 argv, environment 또는 파일에 넣지 않는다.

Explicit show mode는 사람이 token 원문을 꼭 확인할 때만 사용한다. 다음 출력은 민감정보다.
shell xtrace를 끄고 shell history, 화면 공유, 터미널 녹화와 로그 저장을 피한다.

```bash
set +x
compose=(docker compose -p finguardops-jwt-e2e-manual01 \
  -f infra/compose.yml -f infra/compose.local-jwt-e2e.yml)
token="$(${compose[@]} exec -T local-jwt-fixture python \
  /opt/local-jwt-fixture/fixture.py show mint user-viewer)"
# 필요한 단일 수동 요청만 수행한다. echo/printf로 token을 출력하지 않는다.
printf '%s\n' "$token" | "${compose[@]}" exec -T local-jwt-fixture python \
  /opt/local-jwt-fixture/verify_e2e.py _probe --method GET \
  --url http://127.0.0.1:8080/api/v1/transactions --expected 200 --timeout 10
unset token
set +x
```

Backend application port는 host에 publish되지 않으므로 위 curl은 host가 아니라 Backend
namespace를 공유하는 helper 안에서 수행해야 한다. 표시는 디버깅 전용이며 자동 verifier는
항상 machine mode를 사용한다.

## 5. topology와 lifecycle

merged config의 exact 수치는 service 9, network 4, named volume 3이다. host publish는
`127.0.0.1:9090`과 `127.0.0.1:3000`뿐이다. fixture는 `network_mode: service:backend`로
Backend namespace만 공유하며 `ports`, `expose`, 별도 network와 named volume이 없다.
Prometheus·Grafana·Alertmanager는 fixture loopback에 접근할 수 없다.

Backend container를 재생성할 때는 stale namespace 방지를 위해 다음 순서를 지킨다.

1. `external-risk-mock`, `local-jwt-fixture`를 stop하고 remove한다.
2. Backend를 recreate하고 health readiness를 bounded polling한다.
3. 두 sidecar를 새로 create하고 health readiness를 bounded polling한다.
4. 세 container의 `/proc/self/ns/net` 값이 같은지 확인한다.
5. 그 밖의 service ID, `StartedAt`, `RestartCount`가 그대로인지 확인한다.

fixture만 restart할 때 Backend와 나머지 service는 restart하지 않는다. verifier의
`lifecycle` mode가 두 방향을 모두 검사한다. fixed sleep은 사용하지 않는다.

## 6. 인증·rotation·장애 판정

`security-matrix`는 public/management 분리, SERVICE 거래 `201·201·409`, 행동 접수,
ingestor 교차 403, USER read/write 분리, PLATFORM_ADMIN 금융 403, 복수 USER role의 합집합,
missing·invalid·expired token 401을 확인한다. 거래가 HIGH/CRITICAL 사건을 만든 경우 실제
workflow·note·resolution을 수행해 note author가 JWT `sub`인지, 감사 응답에 `actorId`가
없는지도 확인한다. 거부 요청은 성공 writer에 도달하지 않으므로 업무 AuditLog를 만들지
않아야 한다.

`rotation-failure`는 A 최초·cached 재인증, A+B overlap 뒤 B refresh, unpublished C의 401,
delay 중 uncached key의 bounded 503, malformed JWKS의 안전한 500과 정상 복구를 검증한다.
connection refusal은 fixture stop 뒤 uncached token으로 별도 `503 DEPENDENCY_UNAVAILABLE`,
delay는 `503 DEPENDENCY_TIMEOUT`으로 구분해 수동 확인한다. cached key의 구체적 Nimbus TTL을
새 계약으로 고정하지 않는다.

fixture recreate 뒤 key D가 달라져야 하며 Backend, PostgreSQL, AI, Prometheus, Grafana와
Alertmanager의 ID·시작 시각·restart count는 변하지 않아야 한다. recreate 전후 token을
파일에 저장하지 말고 즉시 메모리에서 폐기한다.

## 7. Observability·업무 회귀

overlay는 metric label/cardinality, Prometheus rule, Alertmanager 설정과 Grafana dashboard를
변경하지 않는다. 기존 runbook 절차로 RuleVersion 발행·activation, External Risk·AI 장애와
복구, raw Meter 10, recording rule 14, alert rule 6, firing/resolved, Grafana 중단 중 인증된
거래 `201·201·409`, one-shot recovery·publication의 SYSTEM actor를 확인한다. non-web
one-shot에는 JWT를 추가하지 않는다. 자세한 장시간 순서는
[`prometheus-local-scrape-runbook.md`](./prometheus-local-scrape-runbook.md)를 따른다.

### 7.1 인증 overlay 전용 alert traffic worker

기존 8-service runbook의 `start_bounded_traffic`은 인증이 비활성인 base Compose 전용이다.
JWT overlay에서는 그 함수를 사용하지 않고 아래 worker를 사용한다. `compose` 배열은 base와
`infra/compose.local-jwt-e2e.yml`을 모두 포함해야 한다. `set +x`를 유지하고 token 원문을
인자로 넣거나 export·파일 저장·출력하지 않는다.

```bash
set +x
auth_traffic_pid=""
auth_traffic_stop=""
auth_traffic_log=""
auth_traffic_service="local-jwt-fixture"
auth_traffic_dir=""

cleanup_authenticated_traffic() {
  local original_status="$?"
  local cleanup_status=0
  trap - EXIT INT TERM
  set +e
  if declare -F stop_authenticated_traffic >/dev/null && [[ -n "$auth_traffic_pid" ]]; then
    stop_authenticated_traffic || cleanup_status=1
  fi
  if [[ -n "$auth_traffic_dir" && -d "$auth_traffic_dir" ]]; then
    rm -rf -- "$auth_traffic_dir" || cleanup_status=1
  fi
  if (( original_status != 0 )); then
    (( cleanup_status == 0 )) || printf 'authenticated alert cleanup also failed\n' >&2
    exit "$original_status"
  fi
  exit "$cleanup_status"
}
trap cleanup_authenticated_traffic EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

auth_traffic_dir="$(mktemp -d "${TMPDIR:-/tmp}/finguardops-jwt-alert.XXXXXX")"

start_authenticated_traffic() {
  local expected_status="$1"
  local phase="$2"
  local workers="$3"
  local token
  [[ -z "$auth_traffic_pid" ]] || return 1
  [[ "$expected_status" == "201" || "$expected_status" == "503" ]] || return 1
  [[ "$workers" == "1" || "$workers" == "2" ]] || return 1
  token="$("${compose[@]}" exec -T local-jwt-fixture python \
    /opt/local-jwt-fixture/fixture.py machine mint service-transaction-ingestor)" || return 1
  [[ "$token" == *.*.* ]] || { unset token; return 1; }
  auth_traffic_stop="/run/local-jwt/${phase}-${RANDOM}.stop"
  auth_traffic_log="${auth_traffic_dir}/${phase}.log"
  printf '%s\n' "$token" | MSYS_NO_PATHCONV=1 "${compose[@]}" exec -T \
    local-jwt-fixture python -c '
import base64, collections, concurrent.futures, datetime, json, pathlib
import sys, threading, time, urllib.error, urllib.request, uuid

expected, workers, stop_name, phase = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], sys.argv[4]
token = sys.stdin.readline(16384).strip()
if len(token) >= 16383 or token.count(".") != 2 or expected not in (201, 503) or workers not in (1, 2):
    raise RuntimeError("invalid worker input")
claims_part = token.split(".")[1]
claims = json.loads(base64.urlsafe_b64decode(claims_part + "=" * (-len(claims_part) % 4)))
if set(claims) != {"iss", "aud", "sub", "principal_type", "roles", "iat", "nbf", "exp"}:
    raise RuntimeError("unexpected token claims")
if claims["iss"] != "https://local-jwt.fixture.finguardops.invalid":
    raise RuntimeError("wrong traffic issuer")
if claims["aud"] != ["finguardops-backend-api"]:
    raise RuntimeError("wrong traffic audience")
if claims["sub"] != "9d0edbde-f833-43e2-822a-43a1c38d82ec":
    raise RuntimeError("wrong traffic subject")
if claims["principal_type"] != "SERVICE" or claims["roles"] != ["TRANSACTION_INGESTOR"]:
    raise RuntimeError("wrong traffic identity")
if claims["exp"] - int(time.time()) <= 180:
    raise RuntimeError("insufficient token lifetime at worker start")
stop, done, lock = pathlib.Path(stop_name), threading.Event(), threading.Lock()
statuses, errors, deadline = collections.Counter(), [], time.monotonic() + 600

def send(worker_id):
    while not done.is_set():
        if stop.exists():
            done.set(); return
        if int(claims["exp"]) - int(time.time()) <= 120:
            with lock: errors.append("TOKEN_REFRESH_REQUIRED")
            done.set(); return
        if time.monotonic() >= deadline:
            with lock: errors.append("WORKER_DEADLINE")
            done.set(); return
        body = {
            "transactionId": str(uuid.uuid4()), "transactionType": "ACCOUNT_TRANSFER",
            "amount": "10000", "currencyCode": "KRW",
            "occurredAt": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
            "externalCustomerRef": "local-alert-customer", "senderAccountRef": "local-alert-sender",
            "recipientAccountRef": "local-alert-recipient", "channel": "MOBILE_BANKING",
            "deviceRef": "local-alert-worker-%d" % worker_id,
        }
        request = urllib.request.Request(
            "http://127.0.0.1:8080/api/v1/transactions", data=json.dumps(body).encode(), method="POST",
            headers={"Content-Type": "application/json", "Idempotency-Key": uuid.uuid4().hex,
                     "Authorization": "Bearer " + token},
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                status = response.status; response.read(65537)
        except urllib.error.HTTPError as error:
            status = error.code; error.read(65537)
        except OSError:
            with lock: errors.append("HTTP_TRANSPORT_ERROR")
            done.set(); return
        with lock:
            statuses[status] += 1
            if status != expected:
                errors.append("UNEXPECTED_HTTP_STATUS_%d" % status)
                done.set(); return
        done.wait(1)

with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
    futures = [executor.submit(send, worker_id) for worker_id in range(workers)]
    while not done.wait(0.2) and not all(future.done() for future in futures):
        pass
    for future in futures: future.result()
summary = {"phase": phase, "expected": expected, "statuses": dict(statuses), "errors": errors}
print(json.dumps(summary, sort_keys=True))
if not statuses or errors: raise RuntimeError("authenticated traffic failed")
' "$expected_status" "$workers" "$auth_traffic_stop" "$phase" \
    >"$auth_traffic_log" 2>&1 &
  auth_traffic_pid="$!"
  unset token
}

stop_authenticated_traffic() {
  [[ -n "$auth_traffic_pid" ]] || return 0
  local worker_status=0 cleanup_status=0 grep_status=0
  if ! MSYS_NO_PATHCONV=1 "${compose[@]}" exec -T "$auth_traffic_service" python -c \
    'from pathlib import Path; import sys; Path(sys.argv[1]).touch()' \
    "$auth_traffic_stop" </dev/null; then
    cleanup_status=1
  fi
  wait "$auth_traffic_pid" || worker_status="$?"
  if grep -Eq 'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+|PRIVATE KEY|Authorization' \
    "$auth_traffic_log"; then
    (( worker_status != 0 )) || worker_status=1
  else
    grep_status="$?"
    if (( grep_status == 1 )); then
      cat "$auth_traffic_log" || cleanup_status=1
    else
      cleanup_status=1
    fi
  fi
  if ! MSYS_NO_PATHCONV=1 "${compose[@]}" exec -T "$auth_traffic_service" python -c \
    'from pathlib import Path; import sys; Path(sys.argv[1]).unlink(missing_ok=True)' \
    "$auth_traffic_stop" </dev/null; then
    cleanup_status=1
  fi
  auth_traffic_pid=""; auth_traffic_stop=""; auth_traffic_log=""
  if (( worker_status != 0 )); then
    (( cleanup_status == 0 )) || printf 'authenticated worker cleanup also failed\n' >&2
    return "$worker_status"
  fi
  return "$cleanup_status"
}
```

worker는 최대 600초이고 시작 시 잔여시간 180초 초과를 요구한다. 실행 중 잔여시간이
120초 이하이면 `TOKEN_REFRESH_REQUIRED`와 non-zero로 종료한다. 이 경우 현재 worker를
반드시 `wait`하고 stop marker를 제거한 뒤 `start_authenticated_traffic`을 같은 phase로 다시
호출한다. 새 호출은 machine mode로 새 token을 발급하므로 이전 token은 process 종료와 함께
폐기된다. 발급·검증·교체가 실패하면 alert polling도 즉시 실패시킨다. 현재 2분·5분 alert
계약은 한 worker의 600초 경계 안이지만, deadline을 연장해도 이 갱신 절차를 생략하지 않는다.

External Risk failure/recovery는 각각 다음처럼 기존 bounded polling 함수와 결합한다.
`401`은 expected status와 다르므로 즉시 non-zero이며 `503`이나 `201` 수치에 포함되지 않는다.

```bash
"${compose[@]}" stop external-risk-mock
start_authenticated_traffic 503 external-risk-auth-failure 2
wait_for_condition_readiness external-risk 180
wait_for_correlated_alert_transition external-risk 120 240 420
stop_authenticated_traffic

"${compose[@]}" up -d --no-deps --wait --wait-timeout 120 external-risk-mock
start_authenticated_traffic 201 external-risk-auth-recovery 1
wait_for_alerts_inactive external-risk
stop_authenticated_traffic
```

AI Service 장애도 동일 worker를 사용하되 `ai-service`만 stop/start하고 scenario는
`rule-analysis`로 지정한다. 각 polling checkpoint 전에는 worker PID가 살아 있는지 확인한다.
조기 종료·token 갱신 요구·예상 밖 status가 있으면 polling 결과를 성공으로 사용하지 않는다.

External Risk와 AI 검증은 전용 shell 또는 subshell에서 실행한다. 모든 worker를 `wait`한 뒤
정상 종료하면 EXIT trap이 전용 임시 디렉터리를 제거한다. polling 실패나 INT/TERM에서도 같은
trap이 실행되며, cleanup 자체가 실패해도 원래 non-zero 상태를 성공으로 바꾸지 않는다.

## 8. 종료와 증거

종료 전 worker/subprocess를 stop하고 `wait`하며 temp file·directory를 제거한다. cleanup은
검증용 exact project label의 container·network·volume만 대상으로 한다. 기존 사용자
project와 volume을 재사용하거나 삭제하지 않는다.

Phase A 보고에는 Issue·branch·HEAD, exact 13-file Scope, image digest, crypto·claim,
token 비노출, topology, matrix, JWK lifecycle, observability·one-shot, 테스트별 수치,
mutation, 실패·재실행, `git diff --check`, staged·untracked·임시 자원과 Critical/Major/Minor를
포함한다. 그 뒤 source/config/test/docs를 동결하고 Phase B는 별도 읽기 전용 리뷰로 진행한다.
