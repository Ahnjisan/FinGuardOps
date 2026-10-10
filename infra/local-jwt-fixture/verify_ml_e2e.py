#!/usr/bin/env python3
"""Issue 380 local transaction E2E against a uniquely owned Compose project."""

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


def context(project, tag, *, image_tag=None, fault=False):
    if not re.fullmatch(r"i380[a-z0-9]{6,24}", tag) or project != "finguardops-380-" + tag:
        raise ValueError("Invalid dedicated Compose project")
    ctx = JWT.Context(ROOT, project, 180, 1500)
    ctx.compose.extend(["-f", str(ROOT / "infra" / "compose.issue380-e2e.yml")])
    if fault:
        ctx.compose.extend(["-f", str(ROOT / "infra" / "compose.issue380-fault.yml")])
    image_tag = image_tag or tag
    if not re.fullmatch(r"i380[a-z0-9]{6,24}", image_tag):
        raise ValueError("Invalid dedicated image tag")
    ctx.env.update({
        "FINGUARDOPS_ISSUE380_AI_IMAGE": "finguardops-ai-service:" + image_tag,
        "FINGUARDOPS_ISSUE380_BACKEND_IMAGE": "finguardops-backend:" + image_tag,
    })
    return ctx


def publish(ctx):
    now = dt.datetime.now(dt.timezone.utc)
    v1 = utc(now + dt.timedelta(seconds=55))
    v2 = utc(now + dt.timedelta(seconds=110))
    ctx.compose_run([
        "run", "--rm", "--no-deps", "-T",
        "-e", "SPRING_PROFILES_ACTIVE=local,rule-v1-default-publication",
        "-e", "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false",
        "backend", "--spring.main.web-application-type=none",
        "--finguardops.rule-v1-default-publication.enabled=true",
        "--finguardops.rule-v1-default-publication.confirmation=PUBLISH_RULE_V1_DEFAULT_V1",
        "--finguardops.rule-v1-default-publication.effective-from=" + v1,
    ])
    print(json.dumps({"step": "publish-v1", "exitCode": 0, "effectiveFrom": v1}), flush=True)
    ctx.compose_run([
        "run", "--rm", "--no-deps", "-T",
        "-e", "SPRING_PROFILES_ACTIVE=local,rule-v2-local-publication",
        "-e", "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false",
        "-e", "FINGUARDOPS_KAFKA_ENABLED=false",
        "backend", "--spring.main.web-application-type=none",
        "--finguardops.rule-v2-local-publication.enabled=true",
        "--finguardops.rule-v2-local-publication.confirmation=PUBLISH_RULE_V2_LOCAL",
        "--finguardops.rule-v2-local-publication.effective-from=" + v2,
    ])
    print(json.dumps({"step": "publish-v2", "exitCode": 0, "effectiveFrom": v2}), flush=True)
    while dt.datetime.now(dt.timezone.utc) < dt.datetime.fromisoformat(v2.replace("Z", "+00:00")):
        time.sleep(1)
    rows = ctx.sql_scalar("select version_number, count(*) from rule_version where status='PUBLISHED' and effective_from <= current_timestamp and (effective_to is null or effective_to > current_timestamp) group by version_number order by version_number")
    assert rows == "2|4", rows
    print(json.dumps({"step": "active-v2", "exitCode": 0, "rows": rows}), flush=True)


def counts(ctx):
    return tuple(int(ctx.sql_scalar("select count(*) from " + table)) for table in (
        "financial_transaction", "detection_result", "detection_evidence",
        "fraud_case", "audit_log", "behavior_event"))


def call_counts(ctx):
    ai = ctx.compose_run(["logs", "--no-color", "ai-service"]).decode("utf-8", "replace")
    risk = ctx.compose_run(["logs", "--no-color", "external-risk-mock"]).decode("utf-8", "replace")
    return (ai.count('POST /api/v2/rule-analysis'),
            ai.count('POST /api/v1/ml-inference'),
            risk.count('FINGUARDOPS_EXTERNAL_RISK_LOOKUP_RECEIVED'))


def fault_ml_calls(ctx):
    output = ctx.compose_run(["logs", "--no-color", "ai-service-model-mismatch"])
    return output.decode("utf-8", "replace").count('POST /api/v1/ml-inference')


def safe_error_probe(ctx, token, body, headers):
    script = (
        "import json,sys,urllib.request,urllib.error;"
        "data=json.load(sys.stdin);"
        "req=urllib.request.Request('http://127.0.0.1:8080/api/v1/transactions',"
        "data=json.dumps(data['body']).encode(),method='POST',"
        "headers={'Authorization':'Bearer '+data['token'],"
        "'Content-Type':'application/json','Idempotency-Key':data['key']});"
        "\ntry:\n urllib.request.urlopen(req,timeout=10)\n"
        "except urllib.error.HTTPError as e:\n"
        " result=json.load(e); print(json.dumps({'status':e.code,'code':result.get('code'),"
        "'keys':sorted(result.keys())}))\n"
    )
    payload = json.dumps({"token": token, "body": body, "key": headers["Idempotency-Key"]})
    output = ctx.compose_run(["exec", "-T", "local-jwt-fixture", "python", "-c", script],
                             input_bytes=payload.encode("utf-8"), sensitive=True)
    return json.loads(output)


def normal(ctx):
    tx_token = ctx.mint("service-transaction-ingestor")
    behavior_token = ctx.mint("service-behavior-ingestor")
    viewer = ctx.mint("user-viewer")
    run_id = uuid.uuid4().hex[:12]
    customer = "issue380-synthetic-customer-" + run_id
    sender = "issue380-synthetic-sender-" + run_id
    recipient = "issue380-synthetic-recipient-" + run_id
    device = "issue380-synthetic-device-" + run_id
    base = dt.datetime.now(dt.timezone.utc).replace(microsecond=0) - dt.timedelta(seconds=20)
    before = counts(ctx)
    for kind in ("DEVICE_REGISTERED", "PASSWORD_CHANGED", "TRANSFER_LIMIT_CHANGED",
                 "BENEFICIARY_REGISTERED"):
        for number in range(3):
            event = {
                "eventId": str(uuid.uuid4()), "eventType": kind,
                "occurredAt": utc(base + dt.timedelta(seconds=number)),
                "externalCustomerRef": customer,
                "accountRef": sender if kind != "DEVICE_REGISTERED" else None,
                "deviceRef": device if kind == "DEVICE_REGISTERED" else None,
                "transactionId": None,
                "beneficiaryRef": recipient if kind == "BENEFICIARY_REGISTERED" else None,
            }
            result = ctx.probe("POST", "http://127.0.0.1:8080/api/v1/behavior-events",
                               201, behavior_token, event, {"Content-Type": "application/json"})
            assert result["body"]["eventId"] == event["eventId"]
            if kind == "DEVICE_REGISTERED" and number == 0:
                first_event = event
    duplicate = ctx.probe("POST", "http://127.0.0.1:8080/api/v1/behavior-events",
                          200, behavior_token, first_event, {"Content-Type": "application/json"})
    assert duplicate["body"]["eventId"] == first_event["eventId"]
    assert counts(ctx)[-1] - before[-1] == 12
    print(json.dumps({"step": "behavior-intake", "exitCode": 0, "unique": 12,
                      "duplicateStatus": 200}), flush=True)

    cutoff = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    transaction_id = str(uuid.uuid4())
    body = {
        "transactionId": transaction_id, "transactionType": "ACCOUNT_TRANSFER",
        "amount": "20000000", "currencyCode": "KRW", "occurredAt": utc(cutoff),
        "externalCustomerRef": customer, "senderAccountRef": sender,
        "recipientAccountRef": recipient, "channel": "MOBILE_BANKING", "deviceRef": device,
    }
    key = "issue380-" + run_id
    headers = {"Content-Type": "application/json", "Idempotency-Key": key}
    calls_before = call_counts(ctx)
    created = ctx.probe("POST", "http://127.0.0.1:8080/api/v1/transactions",
                        201, tx_token, body, headers)["body"]
    detection_id = created["adoptedDetectionResultId"]
    case_id = created["caseId"]
    assert created["transactionId"] == transaction_id and case_id
    assert created["processingStatus"] in ("ADDITIONAL_AUTH_REQUIRED", "HELD")
    calls_after = call_counts(ctx)
    assert tuple(b-a for a, b in zip(calls_before, calls_after)) == (1, 1, 1), (calls_before, calls_after)
    print(json.dumps({"step": "transaction-intake", "exitCode": 0,
                      "transactionId": transaction_id, "detectionResultId": detection_id,
                      "caseId": case_id, "status": created["processingStatus"],
                      "callDelta": [b-a for a, b in zip(calls_before, calls_after)]}), flush=True)

    detail = ctx.probe("GET", "http://127.0.0.1:8080/api/v1/transactions/" + transaction_id,
                       200, viewer)["body"]
    assert detail["transaction"]["transactionId"] == transaction_id
    assert detail["transaction"]["processingStatus"] == created["processingStatus"]
    case_detail = ctx.probe("GET", "http://127.0.0.1:8080/api/v1/cases/" + case_id,
                            200, viewer)["body"]
    assert case_detail["case"]["caseId"] == case_id
    assert case_detail["case"]["relatedTransactionCount"] == 1
    adopted = ctx.probe("GET", "http://127.0.0.1:8080/api/v1/transactions/" +
                        transaction_id + "/adopted-detection-result", 200, viewer)["body"]
    item = adopted["adoptedResult"]
    assert adopted["availability"] == "AVAILABLE" and item["detectionResultId"] == detection_id
    assert item["scoringPolicyVersion"] == "rule-ml-policy-v1"
    assert item["mlStatus"] == "APPLIED" and item["mlFeatureVersion"] == "fraud-feature-v1"
    assert item["modelVersion"] == "fraud-logistic-v2"
    assert item["modelSha256"] == "42344d398008babdd6a0404c750b24f1f27f1260aeac65851195d81500a876af"
    assert item["riskScore"] == min(100, item["ruleScore"] + item["mlContribution"])
    assert len(item["ruleEvidence"]) > 0 and len(item["mlEvidence"]) == 1
    assert item["mlEvidence"][0]["scoreContribution"] == item["mlContribution"]
    assert item["riskLevel"] == created["riskLevel"]
    db = json.loads(ctx.sql_scalar(
        "select json_build_object('status', t.processing_status, 'response', t.risk_response_outcome, "
        "'riskLevel', t.risk_level, 'cutoff', d.evaluation_cutoff_at, 'score', d.risk_score, "
        "'ruleScore', d.rule_risk_score, 'mlContribution', d.ml_contribution, "
        "'probability', d.ml_probability_basis_points, 'policy', d.scoring_policy_version, "
        "'model', d.model_version, 'feature', d.ml_feature_version, 'hash', d.model_sha256, "
        "'rules', (select count(*) from detection_evidence e where e.detection_result_id=d.id and e.evidence_type='RULE'), "
        "'ml', (select count(*) from detection_evidence e where e.detection_result_id=d.id and e.evidence_type='ML'), "
        "'cases', (select count(*) from case_transaction c where c.financial_transaction_id=t.id), "
        "'audit', (select count(*) from audit_log a where a.transaction_id=t.transaction_id)) "
        "from financial_transaction t join detection_result d on d.id=t.adopted_detection_result_id "
        "where t.transaction_id='" + transaction_id + "'"))
    assert db["status"] == created["processingStatus"] and db["riskLevel"] == item["riskLevel"]
    assert db["score"] == item["riskScore"] and db["ruleScore"] == item["ruleScore"]
    assert db["mlContribution"] == item["mlContribution"]
    assert db["policy"] == item["scoringPolicyVersion"] and db["model"] == item["modelVersion"]
    assert db["feature"] == item["mlFeatureVersion"] and db["hash"] == item["modelSha256"]
    assert db["rules"] == len(item["ruleEvidence"]) and db["ml"] == 1
    assert db["cases"] == 1 and db["audit"] >= 2
    assert str(db["cutoff"]).replace("+00:00", "Z") == body["occurredAt"]
    print(json.dumps({"step": "adopted-db-api", "exitCode": 0, "transactionId": transaction_id,
                      "detectionResultId": detection_id, "caseId": case_id,
                      "ruleScore": item["ruleScore"], "mlContribution": item["mlContribution"],
                      "finalScore": item["riskScore"], "riskLevel": item["riskLevel"],
                      "evidence": [db["rules"], db["ml"]], "auditCount": db["audit"]}), flush=True)

    rows_before = counts(ctx)
    replay = ctx.probe("POST", "http://127.0.0.1:8080/api/v1/transactions",
                       201, tx_token, body, headers)["body"]
    assert {k: v for k, v in replay.items() if k != "traceId"} == {
        k: v for k, v in created.items() if k != "traceId"}
    assert call_counts(ctx) == calls_after and counts(ctx) == rows_before
    conflict = dict(body)
    conflict["amount"] = "20000001"
    ctx.probe("POST", "http://127.0.0.1:8080/api/v1/transactions",
              409, tx_token, conflict, headers)
    assert call_counts(ctx) == calls_after and counts(ctx) == rows_before
    print(json.dumps({"step": "idempotency", "exitCode": 0, "transactionId": transaction_id,
                      "replayStatus": 201, "conflictStatus": 409, "callDelta": [0, 0, 0]}), flush=True)

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: ctx.probe(
            "POST", "http://127.0.0.1:8080/api/v1/transactions", 201,
            tx_token, body, headers)["body"], range(4)))
    assert all({k: v for k, v in result.items() if k != "traceId"} ==
               {k: v for k, v in created.items() if k != "traceId"} for result in results)
    assert call_counts(ctx) == calls_after and counts(ctx) == rows_before
    print(json.dumps({"step": "concurrent-completed-replay", "exitCode": 0,
                      "transactionId": transaction_id, "requestCount": 4,
                      "callDelta": [0, 0, 0]}), flush=True)

    late = dict(first_event)
    late["eventId"] = str(uuid.uuid4())
    late["occurredAt"] = utc(cutoff + dt.timedelta(seconds=1))
    ctx.probe("POST", "http://127.0.0.1:8080/api/v1/behavior-events",
              201, behavior_token, late, {"Content-Type": "application/json"})
    after_late = ctx.probe("GET", "http://127.0.0.1:8080/api/v1/transactions/" +
                           transaction_id + "/adopted-detection-result", 200, viewer)["body"]
    assert after_late["adoptedResult"] == item
    print(json.dumps({"step": "late-event-preservation", "exitCode": 0,
                      "transactionId": transaction_id}), flush=True)


def replay_previous(ctx, previous_transaction_id):
    tx_token = ctx.mint("service-transaction-ingestor")
    raw = ctx.sql_scalar(
        "select json_build_object('transactionId',transaction_id,'transactionType',transaction_type,"
        "'amount',amount::bigint::text,'currencyCode',currency_code,"
        "'occurredAt',to_char(occurred_at at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),"
        "'externalCustomerRef',external_customer_ref,'senderAccountRef',sender_account_ref,"
        "'recipientAccountRef',recipient_account_ref,'channel',channel,'deviceRef',device_ref) "
        "from financial_transaction where transaction_id='" + previous_transaction_id + "'")
    previous_body = json.loads(raw)
    previous_key = "issue380-" + previous_body["externalCustomerRef"].rsplit("-", 1)[1]
    headers = {"Content-Type": "application/json", "Idempotency-Key": previous_key}
    before = counts(ctx)
    calls_before = call_counts(ctx)
    replay = ctx.probe("POST", "http://127.0.0.1:8080/api/v1/transactions", 201,
                       tx_token, previous_body, headers)["body"]
    assert replay["transactionId"] == previous_transaction_id
    assert counts(ctx) == before and call_counts(ctx) == calls_before
    print(json.dumps({"step": "completed-replay-after-model-change", "exitCode": 0,
                      "transactionId": previous_transaction_id, "callDelta": [0, 0, 0]}), flush=True)


def delayed_cutoff(ctx, previous_transaction_id):
    tx_token = ctx.mint("service-transaction-ingestor")
    viewer = ctx.mint("user-viewer")
    raw = ctx.sql_scalar(
        "select json_build_object('transactionId',transaction_id,'transactionType',transaction_type,"
        "'amount',amount::bigint::text,'currencyCode',currency_code,"
        "'occurredAt',to_char(occurred_at at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),"
        "'externalCustomerRef',external_customer_ref,'senderAccountRef',sender_account_ref,"
        "'recipientAccountRef',recipient_account_ref,'channel',channel,'deviceRef',device_ref) "
        "from financial_transaction where transaction_id='" + previous_transaction_id + "'")
    body = json.loads(raw)
    old = ctx.probe("GET", "http://127.0.0.1:8080/api/v1/transactions/" +
                    previous_transaction_id + "/adopted-detection-result", 200, viewer)["body"]
    assert old["adoptedResult"]["modelVersion"] == "fraud-logistic-v1"
    new_id = str(uuid.uuid4())
    body["transactionId"] = new_id
    calls_before = call_counts(ctx)
    created = ctx.probe("POST", "http://127.0.0.1:8080/api/v1/transactions", 201,
                        tx_token, body, {"Content-Type": "application/json",
                                         "Idempotency-Key": "issue380-delayed-" + uuid.uuid4().hex[:12]})["body"]
    new = ctx.probe("GET", "http://127.0.0.1:8080/api/v1/transactions/" +
                    new_id + "/adopted-detection-result", 200, viewer)["body"]
    item = new["adoptedResult"]
    assert item["modelVersion"] == "fraud-logistic-v1"
    assert item["modelSha256"] == "93a91797f6a652a08871191e87385ced6edd1a560abd6ee9b2782411bb19c286"
    assert item["mlEvidence"][0]["probabilityBasisPoints"] == old["adoptedResult"]["mlEvidence"][0]["probabilityBasisPoints"]
    assert item["riskScore"] == old["adoptedResult"]["riskScore"]
    assert created["adoptedDetectionResultId"] == item["detectionResultId"]
    assert tuple(b-a for a, b in zip(calls_before, call_counts(ctx))) == (1, 1, 1)
    print(json.dumps({"step": "delayed-cutoff-v1-selection", "exitCode": 0,
                      "transactionId": new_id,
                      "detectionResultId": item["detectionResultId"], "caseId": created["caseId"],
                      "modelVersion": item["modelVersion"],
                      "probabilityBasisPoints": item["mlEvidence"][0]["probabilityBasisPoints"],
                      "callDelta": [1, 1, 1]}), flush=True)


def fault(ctx, previous_transaction_id):
    replay_previous(ctx, previous_transaction_id)
    tx_token = ctx.mint("service-transaction-ingestor")
    viewer = ctx.mint("user-viewer")
    previous_body = json.loads(ctx.sql_scalar(
        "select json_build_object('transactionType',transaction_type,'amount',amount::bigint::text,"
        "'currencyCode',currency_code,'senderAccountRef',sender_account_ref,"
        "'recipientAccountRef',recipient_account_ref,'channel',channel,'deviceRef',device_ref) "
        "from financial_transaction where transaction_id='" + previous_transaction_id + "'"))

    transaction_id = str(uuid.uuid4())
    failure_body = dict(previous_body)
    failure_body["transactionId"] = transaction_id
    failure_body["occurredAt"] = utc(dt.datetime.now(dt.timezone.utc).replace(microsecond=0))
    failure_body["externalCustomerRef"] = "issue380-failure-customer-" + uuid.uuid4().hex[:12]
    key = "issue380-failure-" + uuid.uuid4().hex[:12]
    headers = {"Idempotency-Key": key}
    before_calls = call_counts(ctx)
    before_fault_calls = fault_ml_calls(ctx)
    response = safe_error_probe(ctx, tx_token, failure_body, headers)
    assert response["status"] == 503 and response["code"] == "DEPENDENCY_UNAVAILABLE", response
    assert response["keys"] == ["code", "fieldErrors", "message", "traceId"]
    failure = json.loads(ctx.sql_scalar(
        "select json_build_object('transactionStatus',t.processing_status,"
        "'adopted',t.adopted_detection_result_id,'riskLevel',t.risk_level,"
        "'riskResponse',t.risk_response_outcome,'analysis',d.analysis_status,"
        "'failureCode',d.failure_code,'score',d.risk_score,"
        "'evidence',(select count(*) from detection_evidence e where e.detection_result_id=d.id),"
        "'cases',(select count(*) from case_transaction c where c.financial_transaction_id=t.id),"
        "'audit',(select count(*) from audit_log a where a.transaction_id=t.transaction_id),"
        "'idempotency',i.processing_status) from financial_transaction t "
        "join detection_result d on d.financial_transaction_id=t.id "
        "join idempotency_record i on i.financial_transaction_id=t.id "
        "where t.transaction_id='" + transaction_id + "'"))
    assert failure == {
        "transactionStatus": "FAILED", "adopted": None, "riskLevel": None,
        "riskResponse": None, "analysis": "FAILED", "failureCode": "ML_MODEL_HASH_MISMATCH",
        "score": None, "evidence": 0, "cases": 0, "audit": 0, "idempotency": "FAILED",
    }, failure
    adopted = ctx.probe("GET", "http://127.0.0.1:8080/api/v1/transactions/" +
                        transaction_id + "/adopted-detection-result", 200, viewer)["body"]
    assert adopted["availability"] == "FAILED" and adopted["adoptedResult"] is None
    assert adopted["latestFailureCode"] == "ML_MODEL_HASH_MISMATCH"
    after_calls = call_counts(ctx)
    after_fault_calls = fault_ml_calls(ctx)
    assert tuple(b-a for a, b in zip(before_calls, after_calls)) == (1, 0, 1)
    assert after_fault_calls - before_fault_calls == 1
    row_counts = counts(ctx)
    replay = safe_error_probe(ctx, tx_token, failure_body, headers)
    assert replay == response and counts(ctx) == row_counts
    assert call_counts(ctx) == after_calls and fault_ml_calls(ctx) == after_fault_calls
    print(json.dumps({"step": "ml-model-hash-mismatch", "exitCode": 0,
                      "transactionId": transaction_id, "publicStatus": 503,
                      "publicCode": response["code"], "failureCode": failure["failureCode"],
                      "transactionStatus": failure["transactionStatus"],
                      "analysisStatus": failure["analysis"], "idempotency": failure["idempotency"],
                      "evidence": 0, "cases": 0, "audit": 0,
                      "replayStatus": replay["status"], "replayCallDelta": [0, 0, 0, 0]}), flush=True)


def fixture(ctx, transaction_id, failed_transaction_id):
    viewer = ctx.mint("user-viewer")
    output = {}
    for name, item_id in (("adopted", transaction_id), ("failed", failed_transaction_id)):
        response = ctx.probe("GET", "http://127.0.0.1:8080/api/v1/transactions/" +
                             item_id + "/adopted-detection-result", 200, viewer)["body"]
        assert response["transactionId"] == item_id
        output[name] = response
    assert output["adopted"]["availability"] == "AVAILABLE"
    assert output["failed"]["availability"] == "FAILED"
    destination = ROOT / "frontend" / "src" / "test" / "issue380AdoptedFixture.json"
    data = json.dumps(output, sort_keys=True, indent=2, ensure_ascii=False) + "\n"
    assert all(term not in data for term in ("externalCustomerRef", "accountRef", "deviceRef"))
    destination.write_text(data, encoding="utf-8")
    print(json.dumps({"step": "frontend-fixture", "exitCode": 0,
                      "transactionId": transaction_id,
                      "detectionResultId": output["adopted"]["adoptedResult"]["detectionResultId"],
                      "failedTransactionId": failed_transaction_id}), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--image-tag")
    parser.add_argument("--previous-transaction-id")
    parser.add_argument("--failed-transaction-id")
    parser.add_argument("phase", choices=["publish", "normal", "replay", "delayed", "fault", "fixture"])
    args = parser.parse_args()
    for item in (args.previous_transaction_id, args.failed_transaction_id):
        if item is not None and (str(uuid.UUID(item)) != item or uuid.UUID(item).version != 4):
            raise ValueError("Invalid E2E transaction ID")
    ctx = context("finguardops-380-" + args.tag, args.tag, image_tag=args.image_tag,
                  fault=args.phase == "fault")
    if args.phase == "publish":
        publish(ctx)
    elif args.phase == "normal":
        normal(ctx)
    elif args.phase == "replay":
        assert args.previous_transaction_id is not None
        replay_previous(ctx, args.previous_transaction_id)
    elif args.phase == "delayed":
        assert args.previous_transaction_id is not None
        delayed_cutoff(ctx, args.previous_transaction_id)
    elif args.phase == "fault":
        assert args.previous_transaction_id is not None
        fault(ctx, args.previous_transaction_id)
    else:
        assert args.previous_transaction_id is not None and args.failed_transaction_id is not None
        fixture(ctx, args.previous_transaction_id, args.failed_transaction_id)


if __name__ == "__main__":
    main()
