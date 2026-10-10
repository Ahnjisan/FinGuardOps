#!/usr/bin/env python3
"""Issue 382 synthetic transaction E2E in one uniquely owned Compose project."""

import argparse
import concurrent.futures
import datetime as dt
import importlib.util
import json
import re
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("jwt_verify", Path(__file__).with_name("verify_e2e.py"))
JWT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(JWT)


def utc(value):
    return value.isoformat(timespec="seconds").replace("+00:00", "Z")


def context(tag):
    if not re.fullmatch(r"i382[a-z0-9]{6,24}", tag):
        raise ValueError("Invalid dedicated issue-382 tag")
    ctx = JWT.Context(ROOT, "finguardops-382-" + tag, 180, 1800)
    ctx.compose.extend(["-f", str(ROOT / "infra" / "compose.issue382-e2e.yml")])
    ctx.env.update({"FINGUARDOPS_ISSUE382_AI_IMAGE": "finguardops-ai-service:" + tag,
                    "FINGUARDOPS_ISSUE382_BACKEND_IMAGE": "finguardops-backend:" + tag})
    return ctx


def publish(ctx):
    now = dt.datetime.now(dt.timezone.utc)
    for version, seconds, profile, confirmation in (
        (1, 45, "rule-v1-default-publication", "PUBLISH_RULE_V1_DEFAULT_V1"),
        (2, 90, "rule-v2-local-publication", "PUBLISH_RULE_V2_LOCAL"),
        (3, 135, "rule-v3-local-publication", "PUBLISH_RULE_V3_LOCAL"),
    ):
        when = utc(now + dt.timedelta(seconds=seconds))
        prefix = "rule-v1-default-publication" if version == 1 else f"rule-v{version}-local-publication"
        ctx.compose_run([
            "run", "--rm", "--no-deps", "-T", "-e", f"SPRING_PROFILES_ACTIVE=local,{profile}",
            "-e", "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false",
            "-e", "FINGUARDOPS_KAFKA_ENABLED=false", "backend",
            "--spring.main.web-application-type=none",
            f"--finguardops.{prefix}.enabled=true",
            f"--finguardops.{prefix}.confirmation={confirmation}",
            f"--finguardops.{prefix}.effective-from={when}",
        ])
        print(json.dumps({"step": f"publish-v{version}", "exitCode": 0,
                          "effectiveFrom": when}), flush=True)
    end = now + dt.timedelta(seconds=136)
    while dt.datetime.now(dt.timezone.utc) < end:
        time.sleep(1)
    rows = ctx.sql_scalar("""
        select version_number, count(*) from rule_version
        where status='PUBLISHED' and effective_from <= current_timestamp
          and (effective_to is null or effective_to > current_timestamp)
        group by version_number order by version_number
    """)
    assert rows == "1|1\n3|4", rows
    print(json.dumps({"step": "active-scn003-set", "exitCode": 0,
                      "versions": rows}), flush=True)


def call_counts(ctx):
    ai = ctx.compose_run(["logs", "--no-color", "ai-service"]).decode("utf-8", "replace")
    risk = ctx.compose_run(["logs", "--no-color", "external-risk-mock"]).decode("utf-8", "replace")
    return (ai.count('POST /api/v2/rule-analysis'), ai.count('POST /api/v1/ml-inference'),
            risk.count('FINGUARDOPS_EXTERNAL_RISK_LOOKUP_RECEIVED'))


def counts(ctx):
    return tuple(int(ctx.sql_scalar("select count(*) from " + table)) for table in (
        "financial_transaction", "detection_result", "detection_evidence", "fraud_case", "audit_log"))


def transaction(run_id, recipient_kind, customer, *, recipient=None, occurred_at=None):
    recipient = recipient or "issue382-synthetic-" + recipient_kind + "-" + run_id
    return {"transactionId": str(uuid.uuid4()), "transactionType": "ACCOUNT_TRANSFER",
            "amount": "1000", "currencyCode": "KRW",
            "occurredAt": utc(occurred_at or dt.datetime.now(dt.timezone.utc).replace(microsecond=0)),
            "externalCustomerRef": customer,
            "senderAccountRef": "issue382-synthetic-sender-" + run_id,
            "recipientAccountRef": recipient, "channel": "MOBILE_BANKING",
            "deviceRef": "issue382-synthetic-device-" + run_id}


def create(ctx, token, body, key, status=201):
    return ctx.probe("POST", "http://127.0.0.1:8080/api/v1/transactions", status,
                     token, body, {"Content-Type": "application/json",
                                   "Idempotency-Key": key})["body"]


def adopted(ctx, viewer, tx_id):
    return ctx.probe("GET", "http://127.0.0.1:8080/api/v1/transactions/" + tx_id
                     + "/adopted-detection-result", 200, viewer)["body"]


def verify_success(ctx, viewer, body, response, matched, minimum_rule_score):
    tx_id = body["transactionId"]
    item = adopted(ctx, viewer, tx_id)["adoptedResult"]
    assert item["detectionResultId"] == response["adoptedDetectionResultId"]
    assert item["scoringPolicyVersion"] == "rule-ml-policy-v2"
    assert item["ruleScore"] >= minimum_rule_score
    assert item["riskScore"] == min(100, item["ruleScore"] + item["mlContribution"])
    safe = item["scn003Evidence"]
    assert safe["sourceVersion"] == "SCN003-contract-v1"
    assert safe["providerCode"] == "PROVIDER_V1"
    assert safe["recipientAccountMatched"] is matched
    assert all(key not in json.dumps(item) for key in
               (body["recipientAccountRef"], body["senderAccountRef"], body["externalCustomerRef"]))
    db = json.loads(ctx.sql_scalar("""
        select json_build_object('status',t.processing_status,'score',d.risk_score,
            'policy',d.scoring_policy_version,'evidence',
            (select count(*) from detection_evidence e where e.detection_result_id=d.id),
            'cases',(select count(*) from case_transaction c where c.financial_transaction_id=t.id),
            'audit',(select count(*) from audit_log a where a.transaction_id=t.transaction_id))
        from financial_transaction t join detection_result d on d.id=t.adopted_detection_result_id
        where t.transaction_id='""" + tx_id + "'") )
    assert db["status"] == response["processingStatus"] and db["score"] == item["riskScore"]
    assert db["policy"] == item["scoringPolicyVersion"] and db["evidence"] >= 2
    if response["caseId"] is not None:
        assert db["cases"] == 1 and db["audit"] >= 2
    print(json.dumps({"step": "adopted-transaction", "exitCode": 0,
                      "transactionId": tx_id, "detectionResultId": item["detectionResultId"],
                      "caseId": response["caseId"], "ruleScore": item["ruleScore"],
                      "mlContribution": item["mlContribution"], "finalScore": item["riskScore"],
                      "status": db["status"], "matched": matched}), flush=True)
    return item


def verify(ctx):
    tx_token = ctx.mint("service-transaction-ingestor")
    behavior_token = ctx.mint("service-behavior-ingestor")
    viewer = ctx.mint("user-viewer")
    run_id = uuid.uuid4().hex[:12]
    customer = "issue382-synthetic-customer-" + run_id
    normal = []
    for kind, matched, score in (("risk", True, 40), ("clear", False, 0),
                                  ("senderonly", False, 0), ("boundary", True, 40)):
        body = transaction(run_id + kind, kind, customer)
        key = "i382-" + uuid.uuid4().hex
        before = call_counts(ctx)
        response = create(ctx, tx_token, body, key)
        after = call_counts(ctx)
        assert tuple(b-a for a, b in zip(before, after)) == (1, 1, 1)
        item = verify_success(ctx, viewer, body, response, matched, score)
        if kind == "risk":
            assert item["ruleScore"] == 40 and item["scn003Evidence"]["priorApprovedRecipientTransferObserved"] is False
            rows, calls = counts(ctx), call_counts(ctx)
            replay = create(ctx, tx_token, body, key)
            assert {k: v for k, v in replay.items() if k != "traceId"} == {
                k: v for k, v in response.items() if k != "traceId"}
            assert counts(ctx) == rows and call_counts(ctx) == calls
            conflict = dict(body, amount="1001")
            create(ctx, tx_token, conflict, key, 409)
            assert counts(ctx) == rows and call_counts(ctx) == calls
            print(json.dumps({"step": "idempotency", "exitCode": 0,
                              "transactionId": body["transactionId"]}), flush=True)
        normal.append((kind, body, response))

    risk_body = normal[0][1]

    late_body = transaction(run_id + "late", "risk", customer + "late",
                            occurred_at=dt.datetime.now(dt.timezone.utc)
                            - dt.timedelta(seconds=5))
    late_response = create(ctx, tx_token, late_body, "i382-" + uuid.uuid4().hex)
    late_item = verify_success(ctx, viewer, late_body, late_response, True, 40)
    assert late_item["scn003Evidence"]["priorApprovedRecipientTransferObserved"] is False
    print(json.dumps({"step": "late-intake", "exitCode": 0,
                      "transactionId": late_body["transactionId"]}), flush=True)

    for kind in ("stale", "future", "wrongcode", "unavailable", "timeout", "contradictory"):
        body = transaction(run_id + kind, kind, customer)
        before = counts(ctx)
        expected_status = 503 if kind in {"unavailable", "timeout"} else 500
        expected_code = ("DEPENDENCY_TIMEOUT" if kind == "timeout" else
                         "DEPENDENCY_UNAVAILABLE" if kind == "unavailable" else "INTERNAL_ERROR")
        failed = create(ctx, tx_token, body, "i382-" + uuid.uuid4().hex, expected_status)
        assert failed is None  # The shared JWT probe intentionally discards non-2xx bodies.
        state = json.loads(ctx.sql_scalar("""
            select json_build_object('status',t.processing_status,'adopted',t.adopted_detection_result_id,
                'risk',t.risk_level,'failure',i.failure_code,'httpStatus',
                (i.response_snapshot->>'httpStatus')::integer)
            from financial_transaction t join idempotency_record i on i.financial_transaction_id=t.id
            where t.transaction_id='"""
                + body["transactionId"] + "'"))
        assert state == {"status": "RECEIVED", "adopted": None, "risk": None,
                         "failure": expected_code, "httpStatus": expected_status}
        assert counts(ctx) == (before[0] + 1, before[1], before[2], before[3], before[4])
        assert adopted(ctx, viewer, body["transactionId"])["adoptedResult"] is None
        print(json.dumps({"step": "provider-" + kind, "exitCode": 0,
                          "transactionId": body["transactionId"]}), flush=True)

    recipient = risk_body["recipientAccountRef"]
    event = {"eventId": str(uuid.uuid4()), "eventType": "BENEFICIARY_REGISTERED",
             "occurredAt": utc(dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=5)),
             "externalCustomerRef": customer, "accountRef": risk_body["senderAccountRef"],
             "deviceRef": None, "transactionId": None, "beneficiaryRef": recipient}
    ctx.probe("POST", "http://127.0.0.1:8080/api/v1/behavior-events", 201,
              behavior_token, event, {"Content-Type": "application/json"})
    # ML-safe snapshot selection also requires the event intake timestamp <= T.
    time.sleep(2)
    bodies = [transaction(run_id + str(i), "risk", customer, recipient=recipient) for i in range(2)]
    for body in bodies:
        body["senderAccountRef"] = risk_body["senderAccountRef"]
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(create, ctx, tx_token, body, "i382-" + uuid.uuid4().hex)
                   for body in bodies]
        responses = [future.result() for future in futures]
    assert all(response["caseId"] is not None for response in responses)
    assert responses[0]["caseId"] != responses[1]["caseId"]
    for body, response in zip(bodies, responses):
        item = verify_success(ctx, viewer, body, response, True, 50)
        assert item["scn003Evidence"]["priorApprovedRecipientTransferObserved"] is True
    print(json.dumps({"step": "concurrent-distinct-cases", "exitCode": 0,
                      "transactionIds": [body["transactionId"] for body in bodies],
                      "caseIds": [response["caseId"] for response in responses]}), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("phase", choices=["publish", "verify"])
    args = parser.parse_args()
    ctx = context(args.tag)
    (publish if args.phase == "publish" else verify)(ctx)


if __name__ == "__main__":
    main()
