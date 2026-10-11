#!/usr/bin/env python3
"""Issue 384: isolated synthetic transaction, Evidence, case, and auth E2E."""

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
    return value.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def context(tag):
    if not re.fullmatch(r"i384[a-z0-9]{6,24}", tag):
        raise ValueError("Issue 384 requires a unique owned tag")
    ctx = JWT.Context(ROOT, "finguardops-384-" + tag, 180, 1800)
    ctx.compose.extend(["-f", str(ROOT / "infra" / "compose.issue384-e2e.yml")])
    ctx.env.update({"FINGUARDOPS_ISSUE384_AI_IMAGE": "finguardops-ai-service:" + tag,
                    "FINGUARDOPS_ISSUE384_BACKEND_IMAGE": "finguardops-backend:" + tag})
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
            "-e", "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false", "-e",
            "FINGUARDOPS_KAFKA_ENABLED=false", "backend", "--spring.main.web-application-type=none",
            f"--finguardops.{prefix}.enabled=true",
            f"--finguardops.{prefix}.confirmation={confirmation}",
            f"--finguardops.{prefix}.effective-from={when}",
        ])
        print(json.dumps({"step": f"publish-v{version}", "exitCode": 0, "effectiveFrom": when}), flush=True)
    while dt.datetime.now(dt.timezone.utc) < now + dt.timedelta(seconds=136):
        time.sleep(1)
    t4 = utc(dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=45))
    ctx.compose_run([
        "run", "--rm", "--no-deps", "-T", "-e",
        "SPRING_PROFILES_ACTIVE=local,rule-v4-local-publication",
        "-e", "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false", "-e",
        "FINGUARDOPS_KAFKA_ENABLED=false", "backend", "--spring.main.web-application-type=none",
        "--finguardops.rule-v4-local-publication.enabled=true",
        "--finguardops.rule-v4-local-publication.confirmation=PUBLISH_RULE_V4_LOCAL",
        f"--finguardops.rule-v4-local-publication.effective-from={t4}",
    ])
    print(json.dumps({"step": "publish-v4", "exitCode": 0, "effectiveFrom": t4}), flush=True)
    while dt.datetime.now(dt.timezone.utc) <= dt.datetime.fromisoformat(t4.replace("Z", "+00:00")):
        time.sleep(1)
    assert ctx.sql_scalar("""SELECT version_number, count(*) FROM rule_version
        WHERE status='PUBLISHED' AND effective_from <= current_timestamp
          AND (effective_to IS NULL OR effective_to > current_timestamp)
        GROUP BY version_number ORDER BY version_number""") == "1|6\n3|1\n4|3"
    print(json.dumps({"step": "active-v4-exact-set", "exitCode": 0}), flush=True)


def tx(customer, sender, recipient, device, amount, at):
    return {"transactionId": str(uuid.uuid4()), "transactionType": "ACCOUNT_TRANSFER",
            "amount": str(amount), "currencyCode": "KRW", "occurredAt": utc(at),
            "externalCustomerRef": customer, "senderAccountRef": sender,
            "recipientAccountRef": recipient, "channel": "MOBILE_BANKING", "deviceRef": device}


def post(ctx, token, path, body, status=201, headers=None):
    return ctx.probe("POST", "http://127.0.0.1:8080" + path, status, token, body,
                     {"Content-Type": "application/json", **(headers or {})})["body"]


def create(ctx, token, body, key=None, status=201):
    return post(ctx, token, "/api/v1/transactions", body, status,
                {"Idempotency-Key": key or "i384-" + uuid.uuid4().hex})


def event(ctx, token, kind, customer, account, device, beneficiary, at):
    eid = str(uuid.uuid4())
    post(ctx, token, "/api/v1/behavior-events", {
        "eventId": eid, "eventType": kind, "occurredAt": utc(at),
        "externalCustomerRef": customer, "accountRef": account, "deviceRef": device,
        "transactionId": None, "beneficiaryRef": beneficiary})
    return eid


def fact(ctx, token, kind, customer, at, **fields):
    fid = str(uuid.uuid4())
    body = {
        "factId": fid, "factType": kind, "externalCustomerRef": customer,
        "accountRef": None, "deviceRef": None, "beneficiaryRef": None,
        "behaviorEventId": None, "occurredAt": utc(at),
        "coverageFrom": None, "coverageThrough": None,
        "assertionState": None, "limitScope": None,
        "previousLimit": None, "newLimit": None, "countryCode": None}
    body.update(fields)
    post(ctx, token, "/api/v1/fds-mock-facts", body)
    return fid


def coverage(ctx, token, customer, kind, at):
    return fact(ctx, token, kind, customer, at,
                coverageFrom="1970-01-01T00:00:00Z",
                coverageThrough=utc(at + dt.timedelta(minutes=4)),
                assertionState="COMPLETE")


def approved_history(ctx, customer, sender, old_device, suffix, at):
    """Owned synthetic history: DB records the real approval transition now.

    Historical occurred_at is intentionally distinct from approved_at; no
    backdated approval is invented and these rows are never re-scored.
    """
    for n in range(10):
        prior = tx(customer, sender, "issue384-prior-" + suffix + str(n), old_device,
                   100000, at - dt.timedelta(days=3 + n % 3, seconds=n))
        tid = prior["transactionId"]
        ctx.sql_scalar("""INSERT INTO financial_transaction
            (transaction_id, transaction_type, amount, currency_code, occurred_at,
             external_customer_ref, sender_account_ref, recipient_account_ref,
             channel, device_ref, processing_status)
            VALUES ('%s','ACCOUNT_TRANSFER',100000,'KRW','%s',
                    '%s','%s','%s','MOBILE_BANKING','%s','RECEIVED');
            UPDATE financial_transaction SET processing_status='APPROVED',version=version+1
             WHERE transaction_id='%s';""" % (tid, prior["occurredAt"], customer,
                 sender, prior["recipientAccountRef"], old_device, tid))
        assert ctx.sql_scalar("""SELECT (approved_at IS NOT NULL AND
            approved_at >= created_at)::text FROM financial_transaction
            WHERE transaction_id='%s'""" % tid) == "true"
    print(json.dumps({"step": "owned-approved-history", "exitCode": 0,
                      "customer": customer, "count": 10}), flush=True)


def item(ctx, viewer, transaction_id):
    result = ctx.probe("GET", "http://127.0.0.1:8080/api/v1/transactions/"
                       + transaction_id + "/adopted-detection-result", 200, viewer)["body"]
    return result["adoptedResult"]


def evidence(ctx, viewer, body, response, minimum_rule, expected_rules):
    transaction_id = body["transactionId"]
    adopted = item(ctx, viewer, transaction_id)
    assert adopted["detectionResultId"] == response["adoptedDetectionResultId"]
    assert adopted["scoringPolicyVersion"] == "rule-ml-policy-v3"
    assert adopted["modelVersion"] == "fraud-logistic-v3"
    assert adopted["mlFeatureVersion"] == "fraud-feature-v2"
    assert adopted["ruleScore"] >= minimum_rule
    assert adopted["riskScore"] == adopted["ruleScore"] + adopted["mlContribution"]
    assert len(adopted["scoreGroupSummary"]) == 4
    assert sum(row["appliedContribution"] for row in adopted["ruleEvidence"]) == adopted["ruleScore"]
    codes = {row["ruleCode"] for row in adopted["ruleEvidence"]}
    assert expected_rules <= codes, (expected_rules, codes)
    assert body["externalCustomerRef"] not in json.dumps(adopted)
    assert body["senderAccountRef"] not in json.dumps(adopted)
    assert body["deviceRef"] not in json.dumps(adopted)
    stored = json.loads(ctx.sql_scalar("""
      SELECT json_build_object('status',t.processing_status,'score',d.risk_score,
       'rule',d.rule_risk_score,'ml',d.ml_contribution,'policy',d.scoring_policy_version,
       'evidence',(SELECT count(*) FROM detection_evidence e WHERE e.detection_result_id=d.id),
       'cases',(SELECT count(*) FROM case_transaction c WHERE c.financial_transaction_id=t.id),
       'audit',(SELECT count(*) FROM audit_log a WHERE a.transaction_id=t.transaction_id))
      FROM financial_transaction t JOIN detection_result d ON d.id=t.adopted_detection_result_id
      WHERE t.transaction_id='""" + transaction_id + "'"))
    assert (stored["status"], stored["score"], stored["rule"], stored["ml"], stored["policy"]) == (
        response["processingStatus"], adopted["riskScore"], adopted["ruleScore"],
        adopted["mlContribution"], "rule-ml-policy-v3")
    assert stored["evidence"] >= len(adopted["ruleEvidence"]) + 3
    if response["caseId"]:
        assert stored["cases"] == 1 and stored["audit"] >= 2
    print(json.dumps({"step": "adopted-v4", "exitCode": 0, "transactionId": transaction_id,
                      "detectionResultId": adopted["detectionResultId"],
                      "caseId": response["caseId"], "score": adopted["riskScore"],
                      "ruleScore": adopted["ruleScore"], "ml": adopted["mlContribution"],
                      "status": stored["status"]}), flush=True)
    return adopted


def verify(ctx):
    ingest = ctx.mint("service-transaction-ingestor")
    behavioral = ctx.mint("service-behavior-ingestor")
    viewer = ctx.mint("user-viewer")
    analyst = ctx.mint("user-analyst")
    suffix = uuid.uuid4().hex[:12]
    customer = "issue384-customer-" + suffix
    sender = "issue384-sender-" + suffix
    old_device = "issue384-old-device-" + suffix
    now = dt.datetime.now(dt.timezone.utc)
    approved_history(ctx, customer, sender, old_device, suffix, now)
    now = dt.datetime.now(dt.timezone.utc)
    new_device = "issue384-new-device-" + suffix
    recipient = "issue384-first-recipient-" + suffix
    device_event = event(ctx, behavioral, "DEVICE_REGISTERED", customer, sender,
                         new_device, None, now - dt.timedelta(minutes=5))
    event(ctx, behavioral, "BENEFICIARY_REGISTERED", customer, sender,
          None, recipient, now - dt.timedelta(minutes=4))
    coverage(ctx, behavioral, customer, "TRANSFER_HISTORY_COMPLETE", now)
    coverage(ctx, behavioral, customer, "DEVICE_HISTORY_COMPLETE", now)
    fact(ctx, behavioral, "DEVICE_TRUST", customer, now - dt.timedelta(minutes=5),
         accountRef=sender, deviceRef=new_device, behaviorEventId=device_event,
         assertionState="UNVERIFIED")
    time.sleep(2)
    first = tx(customer, sender, recipient, new_device, 1000000,
               dt.datetime.now(dt.timezone.utc))
    key = "i384-" + uuid.uuid4().hex
    first_response = create(ctx, ingest, first, key)
    first_item = evidence(ctx, viewer, first, first_response, 50,
                          {"CUSTOMER_BASELINE_HIGH_AMOUNT",
                           "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
                           "RECENT_BENEFICIARY_TRANSFER"})
    assert first_response["processingStatus"] == "ADDITIONAL_AUTH_REQUIRED"
    second = tx(customer, sender, recipient, new_device, 1000000,
                dt.datetime.now(dt.timezone.utc))
    second_response = create(ctx, ingest, second)
    evidence(ctx, viewer, second, second_response, 50,
             {"RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT", "RECENT_BENEFICIARY_TRANSFER"})
    assert first_response["caseId"] == second_response["caseId"]
    parallel_transactions = [tx(customer, sender, recipient, new_device, 1000000,
                                dt.datetime.now(dt.timezone.utc)) for _ in range(2)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        concurrent_results = list(pool.map(lambda body: create(ctx, ingest, body), parallel_transactions))
    for body, result in zip(parallel_transactions, concurrent_results):
        assert result["caseId"] == first_response["caseId"]
        evidence(ctx, viewer, body, result, 50,
                 {"RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT", "RECENT_BENEFICIARY_TRANSFER"})
    race_recipient = "issue384-race-first-recipient-" + suffix
    event(ctx, behavioral, "BENEFICIARY_REGISTERED", customer, sender,
          None, race_recipient, dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=1))
    time.sleep(2)
    race_transactions = [tx(customer, sender, race_recipient, new_device, 1000000,
                            dt.datetime.now(dt.timezone.utc)) for _ in range(2)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        race_results = list(pool.map(lambda body: create(ctx, ingest, body), race_transactions))
    assert race_results[0]["caseId"] is not None
    assert race_results[0]["caseId"] == race_results[1]["caseId"]
    assert race_results[0]["caseId"] != first_response["caseId"]
    assert ctx.sql_scalar("""SELECT count(DISTINCT c.fraud_case_id) || '|' || count(*)
      FROM case_transaction c JOIN financial_transaction t ON t.id=c.financial_transaction_id
      WHERE t.transaction_id IN ('%s','%s')""" % (
          race_transactions[0]["transactionId"], race_transactions[1]["transactionId"])) == "1|2"
    for body, result in zip(race_transactions, race_results):
        evidence(ctx, viewer, body, result, 50,
                 {"RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT", "RECENT_BENEFICIARY_TRANSFER"})
    print(json.dumps({"step": "concurrent-first-case-creation", "exitCode": 0,
                      "caseId": race_results[0]["caseId"]}), flush=True)
    ctx.probe("GET", "http://127.0.0.1:8080/api/v1/cases/" + first_response["caseId"],
              200, analyst)
    ctx.probe("GET", "http://127.0.0.1:8080/api/v1/cases/" + first_response["caseId"]
              + "/audit-logs", 200, analyst)
    assert create(ctx, ingest, first, key)["adoptedDetectionResultId"] == first_item["detectionResultId"]
    create(ctx, ingest, dict(first, amount="1000001"), key, 409)
    post(ctx, viewer, "/api/v1/transactions/" + first["transactionId"] + "/mock-auth",
         {"decisionId": str(uuid.uuid4()), "result": "SUCCESS"}, 403)
    decision = str(uuid.uuid4())
    auth = post(ctx, behavioral, "/api/v1/transactions/" + first["transactionId"]
                + "/mock-auth", {"decisionId": decision, "result": "SUCCESS"}, 200)
    assert auth["appliedResult"] == "SUCCESS" and auth["processingStatus"] == "APPROVED"
    replay = post(ctx, behavioral, "/api/v1/transactions/" + first["transactionId"]
                  + "/mock-auth", {"decisionId": decision, "result": "SUCCESS"}, 200)
    assert replay == auth
    assert item(ctx, viewer, first["transactionId"])["detectionResultId"] == first_item["detectionResultId"]
    failed_decision = str(uuid.uuid4())
    rejected = post(ctx, behavioral, "/api/v1/transactions/" + second["transactionId"]
                    + "/mock-auth", {"decisionId": failed_decision, "result": "FAILURE"}, 200)
    assert rejected["appliedResult"] == "FAILURE" and rejected["processingStatus"] == "HELD"
    assert post(ctx, behavioral, "/api/v1/transactions/" + second["transactionId"]
                + "/mock-auth", {"decisionId": failed_decision, "result": "FAILURE"}, 200) == rejected
    post(ctx, behavioral, "/api/v1/transactions/" + second["transactionId"]
         + "/mock-auth", {"decisionId": str(uuid.uuid4()), "result": "SUCCESS"}, 409)
    print(json.dumps({"step": "scn001-auth-case-idempotency", "exitCode": 0,
                      "caseId": first_response["caseId"]}), flush=True)

    customer2 = "issue384-security-customer-" + suffix
    sender2 = "issue384-security-sender-" + suffix
    approved_history(ctx, customer2, sender2, old_device, "security-" + suffix, now)
    current = dt.datetime.now(dt.timezone.utc)
    password_at = current - dt.timedelta(minutes=10)
    limit_at = current - dt.timedelta(minutes=5)
    password_id = event(ctx, behavioral, "PASSWORD_CHANGED", customer2, sender2,
                        None, None, password_at)
    limit_id = event(ctx, behavioral, "TRANSFER_LIMIT_CHANGED", customer2, sender2,
                     None, None, limit_at)
    coverage(ctx, behavioral, customer2, "TRANSFER_HISTORY_COMPLETE", current)
    fact(ctx, behavioral, "LIMIT_CHANGE", customer2, limit_at, accountRef=sender2,
         behaviorEventId=limit_id, assertionState="SUCCEEDED", limitScope="PER_TRANSFER",
         previousLimit=2000000, newLimit=7000000)
    fact(ctx, behavioral, "IDENTITY_CHECK", customer2, password_at, accountRef=sender2,
         behaviorEventId=password_id, assertionState="VERIFIED")
    time.sleep(2)
    security = tx(customer2, sender2, "issue384-security-new-" + suffix,
                  old_device, 1000000, dt.datetime.now(dt.timezone.utc))
    security_result = create(ctx, ingest, security)
    evidence(ctx, viewer, security, security_result, 70,
             {"RECENT_SECURITY_CHANGE_HIGH_AMOUNT", "MATERIAL_LIMIT_UPLIFT"})
    assert security_result["caseId"] is not None
    post(ctx, behavioral, "/api/v1/transactions/" + security["transactionId"]
         + "/mock-auth", {"decisionId": str(uuid.uuid4()), "result": "SUCCESS"}, 409)
    print(json.dumps({"step": "scn002-sequence", "exitCode": 0,
                      "transactionId": security["transactionId"],
                      "caseId": security_result["caseId"]}), flush=True)

    risk_device = tx("issue384-device-customer-" + suffix, sender,
                     "issue384-device-recipient-" + suffix,
                     "issue384-synthetic-risk-" + suffix, 1000,
                     dt.datetime.now(dt.timezone.utc))
    device_result = create(ctx, ingest, risk_device)
    evidence(ctx, viewer, risk_device, device_result, 20, {"EXTERNAL_RISK_DEVICE_MATCH"})
    boundary = tx("issue384-boundary-" + suffix, sender,
                  "issue384-boundary-recipient-" + suffix,
                  "issue384-synthetic-boundary-" + suffix, 1000,
                  dt.datetime.now(dt.timezone.utc))
    boundary_result = create(ctx, ingest, boundary)
    evidence(ctx, viewer, boundary, boundary_result, 20, {"EXTERNAL_RISK_DEVICE_MATCH"})
    for kind, status in (("wrongdigest", 500), ("unavailable", 503),
                         ("contradictory", 500), ("stale", 500),
                         ("future", 500), ("timeout", 503)):
        broken = tx("issue384-broken-" + kind + suffix, sender,
                    "issue384-broken-recipient-" + suffix,
                    "issue384-synthetic-" + kind + "-" + suffix,
                    1000, dt.datetime.now(dt.timezone.utc))
        create(ctx, ingest, broken, status=status)
        assert item(ctx, viewer, broken["transactionId"]) is None
    print(json.dumps({"step": "provider-exact-device-and-fail-closed", "exitCode": 0}), flush=True)

    for transaction_type, channel in (("ATM_WITHDRAWAL", "ATM"),
                                      ("LOAN_DISBURSED", "CORE_BANKING")):
        nontransfer = tx("issue384-nontransfer-" + suffix + transaction_type,
                         sender, None, "issue384-synthetic-stale-" + suffix,
                         10000, dt.datetime.now(dt.timezone.utc))
        nontransfer.update(transactionType=transaction_type, channel=channel)
        result = create(ctx, ingest, nontransfer)
        adopted = evidence(ctx, viewer, nontransfer, result, 0, set())
        assert adopted["ruleScore"] == 0 and not adopted["ruleEvidence"]
        assert result["processingStatus"] == "APPROVED" and result["caseId"] is None
    print(json.dumps({"step": "nontransfer-v4-no-new-freshness", "exitCode": 0}), flush=True)

    normal_customer = "issue384-normal-" + suffix
    approved_history(ctx, normal_customer, sender, old_device, "normal-" + suffix, now)
    normal_at = dt.datetime.now(dt.timezone.utc)
    normal_device = "issue384-normal-new-device-" + suffix
    normal_event = event(ctx, behavioral, "DEVICE_REGISTERED", normal_customer, sender,
                         normal_device, None, normal_at - dt.timedelta(minutes=2))
    coverage(ctx, behavioral, normal_customer, "TRANSFER_HISTORY_COMPLETE", normal_at)
    coverage(ctx, behavioral, normal_customer, "DEVICE_HISTORY_COMPLETE", normal_at)
    fact(ctx, behavioral, "DEVICE_TRUST", normal_customer,
         normal_at - dt.timedelta(minutes=2), accountRef=sender,
         deviceRef=normal_device, behaviorEventId=normal_event, assertionState="VERIFIED")
    normal = tx(normal_customer, sender, "issue384-normal-recipient-" + suffix,
                normal_device, 1000000, dt.datetime.now(dt.timezone.utc))
    normal_result = create(ctx, ingest, normal)
    normal_adopted = evidence(ctx, viewer, normal, normal_result, 20,
                              {"CUSTOMER_BASELINE_HIGH_AMOUNT"})
    assert "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT" not in {
        row["ruleCode"] for row in normal_adopted["ruleEvidence"]}
    assert normal_result["processingStatus"] in ("APPROVED_WITH_MONITORING", "APPROVED")

    insufficient_customer = "issue384-insufficient-" + suffix
    insufficient_at = dt.datetime.now(dt.timezone.utc)
    insufficient_device = "issue384-insufficient-device-" + suffix
    insufficient_event = event(ctx, behavioral, "DEVICE_REGISTERED", insufficient_customer,
                               sender, insufficient_device, None,
                               insufficient_at - dt.timedelta(minutes=2))
    coverage(ctx, behavioral, insufficient_customer, "TRANSFER_HISTORY_COMPLETE", insufficient_at)
    coverage(ctx, behavioral, insufficient_customer, "DEVICE_HISTORY_COMPLETE", insufficient_at)
    fact(ctx, behavioral, "DEVICE_TRUST", insufficient_customer,
         insufficient_at - dt.timedelta(minutes=2), accountRef=sender,
         deviceRef=insufficient_device, behaviorEventId=insufficient_event,
         assertionState="UNVERIFIED")
    insufficient = tx(insufficient_customer, sender,
                      "issue384-insufficient-recipient-" + suffix,
                      insufficient_device, 1000000, dt.datetime.now(dt.timezone.utc))
    insufficient_result = create(ctx, ingest, insufficient)
    insufficient_adopted = evidence(ctx, viewer, insufficient, insufficient_result, 0, set())
    assert not {"CUSTOMER_BASELINE_HIGH_AMOUNT", "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT"} & {
        row["ruleCode"] for row in insufficient_adopted["ruleEvidence"]}
    assert insufficient_adopted["scn001002Evidence"]["baselineStatus"] == "INSUFFICIENT"

    known_customer = "issue384-known-" + suffix
    approved_history(ctx, known_customer, sender, old_device, "known-" + suffix, now)
    known_at = dt.datetime.now(dt.timezone.utc)
    known_recipient = "issue384-prior-known-" + suffix + "0"
    event(ctx, behavioral, "BENEFICIARY_REGISTERED", known_customer, sender,
          None, known_recipient, known_at - dt.timedelta(minutes=2))
    coverage(ctx, behavioral, known_customer, "TRANSFER_HISTORY_COMPLETE", known_at)
    known = tx(known_customer, sender, known_recipient, old_device, 1000,
               dt.datetime.now(dt.timezone.utc))
    known_result = create(ctx, ingest, known)
    known_adopted = evidence(ctx, viewer, known, known_result, 0, set())
    assert known_adopted["scn001002Evidence"]["recipientStatus"] == "KNOWN"
    assert "RECENT_BENEFICIARY_TRANSFER" not in {
        row["ruleCode"] for row in known_adopted["ruleEvidence"]}
    print(json.dumps({"step": "normal-verified-insufficient-known", "exitCode": 0}), flush=True)

    for label, old_limit, new_limit, order_minutes in (
            ("unchanged", 2000000, 2000000, 10),
            ("lowered", 7000000, 2000000, 10),
            ("same-time", 2000000, 7000000, 5),
            ("reverse", 2000000, 7000000, 4)):
        subject = "issue384-limit-" + label + "-" + suffix
        changed_at = dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=5)
        password_at = changed_at - dt.timedelta(minutes=order_minutes - 5)
        password_id = event(ctx, behavioral, "PASSWORD_CHANGED", subject, sender,
                            None, None, password_at)
        changed_id = event(ctx, behavioral, "TRANSFER_LIMIT_CHANGED", subject, sender,
                           None, None, changed_at)
        fact(ctx, behavioral, "LIMIT_CHANGE", subject, changed_at, accountRef=sender,
             behaviorEventId=changed_id, assertionState="SUCCEEDED",
             limitScope="PER_TRANSFER", previousLimit=old_limit, newLimit=new_limit)
        fact(ctx, behavioral, "IDENTITY_CHECK", subject, password_at, accountRef=sender,
             behaviorEventId=password_id, assertionState="VERIFIED")
        check = tx(subject, sender, "issue384-limit-recipient-" + suffix,
                   old_device, 1000000, dt.datetime.now(dt.timezone.utc))
        accepted = create(ctx, ingest, check)
        observed = evidence(ctx, viewer, check, accepted, 0, set())
        codes = {row["ruleCode"] for row in observed["ruleEvidence"]}
        assert "RECENT_SECURITY_CHANGE_HIGH_AMOUNT" not in codes
        assert "MATERIAL_LIMIT_UPLIFT" not in codes
        assert accepted["caseId"] is None
    print(json.dumps({"step": "non-uplift-same-time-reverse", "exitCode": 0}), flush=True)

    late_t = dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=3)
    late_customer = "issue384-late-" + suffix
    late_password = event(ctx, behavioral, "PASSWORD_CHANGED", late_customer, sender,
                          None, None, late_t - dt.timedelta(minutes=10))
    late_limit = event(ctx, behavioral, "TRANSFER_LIMIT_CHANGED", late_customer, sender,
                       None, None, late_t - dt.timedelta(minutes=5))
    fact(ctx, behavioral, "LIMIT_CHANGE", late_customer,
         late_t - dt.timedelta(minutes=5), accountRef=sender,
         behaviorEventId=late_limit, assertionState="SUCCEEDED",
         limitScope="PER_TRANSFER", previousLimit=500000, newLimit=7000000)
    fact(ctx, behavioral, "IDENTITY_CHECK", late_customer,
         late_t - dt.timedelta(minutes=10), accountRef=sender,
         behaviorEventId=late_password, assertionState="VERIFIED")
    late = tx(late_customer, sender, "issue384-late-recipient-" + suffix,
              old_device, 1000000, late_t)
    late_result = create(ctx, ingest, late)
    late_observed = evidence(ctx, viewer, late, late_result, 0, set())
    assert "RECENT_SECURITY_CHANGE_HIGH_AMOUNT" not in {
        row["ruleCode"] for row in late_observed["ruleEvidence"]}
    assert late_result["caseId"] is None
    print(json.dumps({"step": "late-created-behavior-mock-cutoff", "exitCode": 0}), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("phase", choices=["publish", "verify"])
    args = parser.parse_args()
    ctx = context(args.tag)
    (publish if args.phase == "publish" else verify)(ctx)


if __name__ == "__main__":
    main()
