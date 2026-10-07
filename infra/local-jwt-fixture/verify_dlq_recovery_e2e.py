#!/usr/bin/env python3
"""Isolated JWT, PostgreSQL and Kafka DLQ recovery verifier. No paid provider."""

import base64
import datetime as dt
import re
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path

from verify_e2e import Context, JWT_RE, VerificationError, project_resources, publish_rules, run, scrub

SOURCE = "finguardops.ai-report-execution-created.v1"
DLQ = SOURCE + ".dlq"
GROUP = "finguardops-ai-report-worker-v1"
PROJECT_PREFIX = "finguardops-dlq-365-"
BASE = "http://127.0.0.1:8080"


def require(value, message):
    if not value:
        raise VerificationError(message)


def api(ctx, method, url, expected, token, body=None, headers=None):
    request_headers = dict(headers or {})
    if body is not None:
        request_headers["Content-Type"] = "application/json"
    return ctx.probe(method, url, expected, token, body, request_headers)


def kafka(ctx, tool, *args, stdin=None, sensitive=False):
    return ctx.compose_run(["exec", "-T", "kafka", "/opt/kafka/bin/" + tool + ".sh",
                            "--bootstrap-server", "localhost:9092", *args],
                           input_bytes=stdin, sensitive=sensitive, timeout=40).decode("utf-8", "strict")


def end_offset(ctx, topic):
    output = kafka(ctx, "kafka-get-offsets", "--topic", topic, "--time", "-1")
    entries = [line.split(":") for line in output.splitlines() if line.startswith(topic + ":")]
    require(len(entries) == 1 and entries[0][1] == "0", "unexpected topic partitions")
    return int(entries[0][2])


def topic_id(ctx, topic):
    output = kafka(ctx, "kafka-topics", "--describe", "--topic", topic)
    found = re.search(r"TopicId:\s*([A-Za-z0-9_-]+)", output)
    require(found is not None, "topic ID unavailable")
    raw = found.group(1)
    return str(uuid.UUID(bytes=base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4))))


def group_offset(ctx):
    output = kafka(ctx, "kafka-consumer-groups", "--describe", "--group", GROUP)
    for line in output.splitlines():
        columns = line.split()
        if len(columns) >= 5 and columns[0] == GROUP and columns[1] == SOURCE and columns[2] == "0":
            return int(columns[3]) if columns[3].isdigit() else -1
    return -1


def publish(ctx, topic, key, value):
    require("\n" not in value and "\t" not in value, "fixture message is not line-safe")
    kafka(ctx, "kafka-console-producer", "--topic", topic, "--sync",
          "--reader-property", "parse.key=true", stdin=(key + "\t" + value + "\n").encode(),
          sensitive=True)


def wait_for(check, seconds, description):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        result = check()
        if result:
            return result
        time.sleep(2)
    raise VerificationError(description + " did not reach the expected state")


def create_pending(ctx):
    transaction_token = ctx.mint("service-transaction-ingestor")
    behavior_token = ctx.mint("service-behavior-ingestor")
    analyst_token = ctx.mint("user-analyst")
    marker = uuid.uuid4().hex
    now = dt.datetime.now(dt.timezone.utc)
    stamp = lambda value: value.isoformat().replace("+00:00", "Z")
    customer, sender, recipient = ("dlq-customer-" + marker,
                                   "dlq-sender-" + marker, "dlq-recipient-" + marker)
    for kind, seconds, account in [("PASSWORD_CHANGED", -3, None),
                                   ("TRANSFER_LIMIT_CHANGED", -2, sender)]:
        event = {"eventId": str(uuid.uuid4()), "eventType": kind,
                 "occurredAt": stamp(now + dt.timedelta(seconds=seconds)),
                 "externalCustomerRef": customer}
        if account:
            event["accountRef"] = account
        api(ctx,"POST", BASE + "/api/v1/behavior-events", 201, behavior_token, event)
    print("fixture behavior events created", flush=True)
    transaction_id = str(uuid.uuid4())
    created = api(ctx,"POST", BASE + "/api/v1/transactions", 201, transaction_token,
                        {"transactionId": transaction_id, "transactionType": "ACCOUNT_TRANSFER",
                         "amount": "12000000", "currencyCode": "KRW", "occurredAt": stamp(now),
                         "externalCustomerRef": customer, "senderAccountRef": sender,
                         "recipientAccountRef": recipient, "channel": "MOBILE_BANKING"},
                        {"Idempotency-Key": "dlq-" + marker})["body"]
    require(created["riskLevel"] == "HIGH", "fixture transaction was not high risk")
    print("fixture transaction created", flush=True)
    case_id = created["caseId"]
    adopted = api(ctx,"GET", BASE + "/api/v1/transactions/" + transaction_id +
                        "/adopted-detection-result", 200, analyst_token)["body"]
    case = api(ctx,"GET", BASE + "/api/v1/cases/" + case_id, 200, analyst_token)["body"]
    api(ctx,"PATCH", BASE + "/api/v1/cases/" + case_id + "/status", 200, analyst_token,
              {"targetStatus": "IN_REVIEW", "assigneeRef": str(uuid.uuid4()),
               "reasonCode": "CASE_REVIEW_STARTED",
               "expectedVersion": case["case"]["concurrencyVersion"]})
    print("fixture case in review", flush=True)
    accepted = api(ctx,"POST", BASE + "/api/v1/cases/" + case_id + "/ai-reports", 202,
                         analyst_token,
                         {"detectionResultVersion": adopted["adoptedResult"]["detectionResultVersion"],
                          "regenerationReason": None},
                         {"Idempotency-Key": "dlq-report-" + marker})["body"]
    require(accepted["reportStatus"] == "PENDING", "execution was not pending")
    print("fixture execution pending", flush=True)
    return transaction_id, case_id, accepted["executionId"], accepted["aiRequestId"]


def sql(ctx, query):
    return ctx.sql_scalar(query)


def metric(ctx, name, label):
    script = ("import urllib.request;"
              "print(urllib.request.urlopen('http://127.0.0.1:8081/actuator/prometheus',"
              "timeout=10).read(1048576).decode())")
    exposition = ctx.compose_run(["exec", "-T", "local-jwt-fixture", "python", "-c", script],
                                 sensitive=True, timeout=20).decode("utf-8", "strict")
    for line in exposition.splitlines():
        if line.startswith(name + "{") and label in line:
            return float(line.rsplit(" ", 1)[1])
    raise VerificationError("required low-cardinality metric missing: " + name)


def restart_consumer(ctx):
    ctx.compose_run(["stop", "local-jwt-fixture"])
    ctx.compose_run(["rm", "-f", "local-jwt-fixture"])
    ctx.env["FINGUARDOPS_KAFKA_CONSUMER_ENABLED"] = "true"
    ctx.compose_run(["up", "-d", "--no-deps", "--force-recreate", "--wait", "backend"], timeout=180)
    ctx.compose_run(["up", "-d", "--no-deps", "--wait", "local-jwt-fixture"], timeout=120)


def verify(ctx):
    require(not any(project_resources(ctx.project, timeout=20).values()),
            "E2E project already has resources")
    env = os.environ.copy()
    env.update(ctx.env)
    started = subprocess.run(ctx.compose + ["up", "-d", "--build", "--wait",
                                         "postgresql", "kafka", "ai-service", "backend",
                                         "local-jwt-fixture", "external-risk-mock"],
                             cwd=ctx.repo, env=env, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, timeout=min(900, ctx.remaining()),
                             check=False)
    if started.returncode != 0:
        lines = started.stderr.decode("utf-8", "replace").splitlines()
        detail = " | ".join(lines[-4:])
        raise VerificationError("Compose startup failed: " + scrub(detail))
    publish_rules(ctx)
    print("rule fixture active", flush=True)
    require(end_offset(ctx, SOURCE) == 0 and end_offset(ctx, DLQ) == 0,
            "fixture topics are not empty")
    print("empty Kafka topics verified", flush=True)
    transaction_id, case_id, execution_id, request_id = create_pending(ctx)
    audit_before = sql(ctx, "SELECT count(*) FROM audit_log WHERE case_id='%s'" % case_id)
    outcome_before = sql(ctx, "SELECT risk_response_outcome FROM financial_transaction "
                              "WHERE transaction_id='%s'" % transaction_id)
    row = wait_for(lambda: sql(ctx, "SELECT event_id::text||':'||status FROM ai_report_outbox "
                               "WHERE execution_id='%s'" % execution_id), 60, "outbox")
    event_id = row.split(":")[0]
    wait_for(lambda: sql(ctx, "SELECT status FROM ai_report_outbox WHERE event_id='%s'" % event_id)
             == "PUBLISHED", 60, "outbox broker ack")
    canonical = sql(ctx, "SELECT payload FROM ai_report_outbox WHERE event_id='%s'" % event_id)
    require(end_offset(ctx, SOURCE) == 1, "initial canonical event offset mismatch")
    consumed = kafka(ctx, "kafka-console-consumer", "--topic", SOURCE,
                     "--group", GROUP, "--from-beginning", "--max-messages", "1",
                     "--timeout-ms", "15000", sensitive=True)
    require(consumed.strip() == canonical, "source group fixture did not consume canonical event")
    print("source group consumed initial event", flush=True)
    restart_consumer(ctx)
    print("worker consumer enabled", flush=True)
    require(sql(ctx, "SELECT status FROM ai_report_execution WHERE execution_id='%s'" % execution_id)
            == "PENDING", "polling or consumer claimed the execution before failure fixture")
    before = end_offset(ctx, DLQ)
    ctx.compose_run(["stop", "postgresql"])
    publish(ctx, SOURCE, execution_id, canonical)
    wait_for(lambda: end_offset(ctx, DLQ) > before, 180, "DLQ pre-claim failure")
    print("pre-claim DLQ arrived", flush=True)
    ctx.compose_run(["start", "postgresql"])
    ctx.compose_run(["up", "-d", "--wait", "postgresql"], timeout=120)
    admin = ctx.mint("user-platform-admin")
    analyst = ctx.mint("user-analyst")
    dlq_id = topic_id(ctx, DLQ)
    path = "/api/v1/ai-report-dlq/%s/0/%d" % (dlq_id, before)
    api(ctx,"GET", BASE + path, 403, analyst)
    api(ctx,"POST", BASE + path + "/replay", 403, analyst,
              {"observedCategory": "PRE_CLAIM_TRANSIENT"})
    diagnostic = api(ctx,"GET", BASE + path, 200, admin)["body"]
    require(diagnostic["failureCategory"] == "PRE_CLAIM_TRANSIENT" and
            diagnostic["sourceVerified"] and diagnostic["sourceRecovered"] and
            diagnostic["replayAllowed"] and diagnostic["eventId"] == event_id,
            "pre-claim diagnostic is not replay eligible")
    safe_fields = {"topicId", "partition", "offset", "failureCategory", "sourceVerified",
                   "sourceRecovered", "eventId", "executionId", "executionStatus",
                   "reportExists", "attemptExists", "action", "dispatchStatus",
                   "startSource", "ackPartition", "ackOffset", "replayAllowed",
                   "rejectionReason", "traceId"}
    require(set(diagnostic) == safe_fields, "diagnostic returned unapproved fields")
    approved = api(ctx,"POST", BASE + path + "/replay", 202, admin,
                         {"observedCategory": "PRE_CLAIM_TRANSIENT"})["body"]
    require(approved["action"] == "REPLAY", "replay approval missing")
    print("DB replay intent approved", flush=True)
    api(ctx,"POST", BASE + path + "/replay", 409, admin,
              {"observedCategory": "PRE_CLAIM_TRANSIENT"})
    wait_for(lambda: sql(ctx, "SELECT status FROM ai_report_dlq_replay_dispatch WHERE event_id='%s'"
                         % event_id) == "ACKED", 60, "dispatcher broker ack")
    print("dispatcher broker ack persisted", flush=True)
    final = wait_for(lambda: sql(ctx, "SELECT status FROM ai_report_execution WHERE execution_id='%s'"
                                 % execution_id) in ("COMPLETED", "FALLBACK_COMPLETED", "FAILED"),
                     120, "AI terminal state")
    require(final, "AI result did not become terminal")
    detail = api(ctx,"GET", BASE + "/api/v1/ai-report-requests/" + request_id, 200, admin)["body"]
    require(detail["executionId"] == execution_id and
            detail["reportStatus"] in ("COMPLETED", "FALLBACK_COMPLETED", "FAILED"),
            "operator request result mismatch")
    current = api(ctx,"GET", BASE + "/api/v1/cases/" + case_id +
                  "/ai-reports/current", 200, analyst)["body"]
    require(current["latestRequest"]["reportStatus"] == detail["reportStatus"],
            "analyst report lookup disagrees with operator result")
    require(sql(ctx, "SELECT count(*) FROM ai_report_dlq_action WHERE event_id='%s'" % event_id)
            == "1", "duplicate action")
    require(sql(ctx, "SELECT count(*) FROM ai_report_execution_start_source WHERE execution_id='%s'"
                % execution_id) == "1", "business claim source missing")
    require(end_offset(ctx, SOURCE) >= 3, "canonical replay was not published")
    def recovered_offset():
        value = group_offset(ctx)
        return value if value >= 3 else None
    recovered_group_offset = wait_for(recovered_offset, 60, "replayed group offset")
    replay_published = metric(ctx, "finguardops_kafka_dlq_replay_total", 'result="published"')
    kafka_starts = metric(ctx, "finguardops_ai_report_starts_total", 'source="kafka"')
    require(replay_published >= 1,
            "replay publish counter did not advance")
    require(kafka_starts >= 1,
            "Kafka business start counter did not advance")
    poison_offset = end_offset(ctx, DLQ)
    publish(ctx, DLQ, "poison-key", "invalid-event-fixture")
    poison_path = "/api/v1/ai-report-dlq/%s/0/%d" % (dlq_id, poison_offset)
    poison = api(ctx,"GET", BASE + poison_path, 200, admin)["body"]
    require(poison["failureCategory"] == "UNKNOWN" and not poison["replayAllowed"],
            "legacy poison became replayable")
    api(ctx,"POST", BASE + poison_path + "/quarantine", 202, admin,
              {"observedCategory": "UNKNOWN"})
    require(sql(ctx, "SELECT case_status FROM fraud_case WHERE case_id='%s'" % case_id)
            == "IN_REVIEW", "case status changed")
    require(sql(ctx, "SELECT count(*) FROM audit_log WHERE case_id='%s'" % case_id)
            == audit_before, "existing case audit changed")
    require(sql(ctx, "SELECT risk_response_outcome FROM financial_transaction "
                     "WHERE transaction_id='%s'" % transaction_id) == outcome_before,
            "transaction response changed")
    logs = ctx.compose_run(["logs", "--no-color", "backend"], sensitive=True).decode(
        "utf-8", "replace")
    require(not JWT_RE.search(logs) and "dlq-customer-" not in logs and
            "dlq-sender-" not in logs and "dlq-recipient-" not in logs and
            "invalid-event-fixture" not in logs,
            "sensitive fixture material found in backend logs")
    recorded_attempts = sql(ctx, "SELECT count(*) FROM provider_call_attempt a "
                             "JOIN ai_report_execution e ON e.id=a.execution_id "
                             "WHERE e.execution_id='%s'" % execution_id)
    print("DLQ E2E PASS coordinate=%s/0/%d eventId=%s executionId=%s "
          "intent=ACKED sourceEnd=%d groupOffset=%d result=%s poison=QUARANTINE "
          "replayPublished=%g kafkaStarts=%g recordedAttempts=%s" %
          (dlq_id, before, event_id, execution_id, end_offset(ctx, SOURCE), recovered_group_offset,
           detail["reportStatus"], replay_published, kafka_starts, recorded_attempts))


def main():
    repo = Path(__file__).resolve().parents[2]
    ctx = Context(repo, PROJECT_PREFIX + uuid.uuid4().hex[:8], 45, 1800)
    print("Compose project=" + ctx.project)
    ctx.compose += ["-f", str(repo / "infra" / "compose.kafka-local.yml"),
                    "-f", str(repo / "infra" / "compose.dlq-recovery-e2e.yml")]
    ctx.env["FINGUARDOPS_KAFKA_CONSUMER_ENABLED"] = "false"
    error = None
    try:
        verify(ctx)
    except BaseException as caught:
        error = caught
    try:
        run(ctx.compose + ["down", "--remove-orphans", "--timeout", "20"],
            timeout=180, cwd=str(ctx.repo), env=ctx.env, sensitive=True)
    except BaseException as cleanup:
        if error is None:
            error = cleanup
    resources = project_resources(ctx.project, timeout=30)
    print("cleanup containers=%d networks=%d retainedVolumes=%d" %
          (len(resources["container"]), len(resources["network"]), len(resources["volume"])))
    if resources["container"] or resources["network"]:
        raise VerificationError("Compose runtime resources remain")
    if error:
        raise error


if __name__ == "__main__":
    try:
        main()
    except VerificationError as exc:
        print("DLQ E2E FAIL: " + scrub(str(exc)), file=sys.stderr)
        raise SystemExit(1)
