#!/usr/bin/env python3
"""Issue 394: one isolated JWT/PostgreSQL run across eight stored scenarios.

The runner uses only synthetic references. Tokens stay in the existing fixture
process boundary; stdout contains opaque IDs and bounded, non-sensitive counts.
"""

import argparse
import datetime as dt
import importlib.util
import json
import re
import time
import uuid
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("issue394_jwt", Path(__file__).with_name("verify_e2e.py"))
JWT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(JWT)

UTC = dt.timezone.utc
BASE = "http://127.0.0.1:8080/api/v1"
RULES = {
    "SCN001": "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
    "SCN002": "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
    "SCN003": "EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT",
    "SCN004": "DISTRIBUTED_OUTBOUND_WINDOW",
    "SCN005": "MANY_CUSTOMER_INBOUND_WINDOW",
    "SCN006": "VERIFIED_INBOUND_ATM_DRAIN",
    "SCN007": "VERIFIED_LOAN_NEW_RECIPIENT_DRAIN",
    "SCN008": "VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND",
}


def stamp(value):
    return value.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def now():
    return dt.datetime.now(UTC)


def uid():
    return str(uuid.uuid4())


def context(tag):
    if not re.fullmatch(r"i394[a-z0-9]{8,24}", tag):
        raise ValueError("Issue 394 requires one unique owned tag")
    ctx = JWT.Context(ROOT, "finguardops-394-" + tag, 240, 5400)
    ctx.compose.extend(["-f", str(ROOT / "infra" / "compose.issue382-e2e.yml")])
    image_ai = "finguardops-ai-service:" + tag
    image_backend = "finguardops-backend:" + tag
    ctx.env.update({
        "FINGUARDOPS_ISSUE382_AI_IMAGE": image_ai,
        "FINGUARDOPS_ISSUE382_BACKEND_IMAGE": image_backend,
        "FINGUARDOPS_ISSUE384_AI_IMAGE": image_ai,
        "FINGUARDOPS_ISSUE384_BACKEND_IMAGE": image_backend,
        "FINGUARDOPS_AI_OLLAMA_BASE_URL": "http://127.0.0.1:65534",
    })
    return ctx


def publish(ctx):
    # Each publisher requires the preceding active set. Keep the same database.
    phases = [
        ("v1", "rule-v1-default-publication", "PUBLISH_RULE_V1_DEFAULT_V1"),
        ("v2", "rule-v2-local-publication", "PUBLISH_RULE_V2_LOCAL"),
        ("v3", "rule-v3-local-publication", "PUBLISH_RULE_V3_LOCAL"),
        ("v4", "rule-v4-local-publication", "PUBLISH_RULE_V4_LOCAL"),
        ("v5", "rule-v5-local-publication", "PUBLISH_RULE_V5_LOCAL"),
        ("v6", "rule-v6-local-publication", "PUBLISH_RULE_V6_LOCAL"),
        ("v7", "rule-v7-local-publication", "PUBLISH_RULE_V7_LOCAL"),
    ]
    for version, profile, confirmation in phases:
        effective = now() + dt.timedelta(seconds=60)
        prefix = profile
        ctx.compose_run([
            "run", "--rm", "--no-deps", "-T", "-e", f"SPRING_PROFILES_ACTIVE=local,{profile}",
            "-e", "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false", "-e",
            "FINGUARDOPS_KAFKA_ENABLED=false", "backend", "--spring.main.web-application-type=none",
            f"--finguardops.{prefix}.enabled=true",
            f"--finguardops.{prefix}.confirmation={confirmation}",
            f"--finguardops.{prefix}.effective-from={stamp(effective)}",
        ])
        while now() <= effective:
            time.sleep(0.5)
        print(json.dumps({"step": "publish-" + version, "exitCode": 0,
                          "effectiveFrom": stamp(effective)}), flush=True)


def request(ctx, token, method, path, expected=200, body=None, headers=None):
    return ctx.probe(method, BASE + path, expected, token, body,
                     {"Content-Type": "application/json", **(headers or {})})["body"]


def transaction(customer, sender, recipient, amount, *, kind="ACCOUNT_TRANSFER",
                channel=None, device=None, occurred=None):
    return {"transactionId": uid(), "transactionType": kind, "amount": str(amount),
            "currencyCode": "KRW", "occurredAt": stamp(occurred or now()),
            "externalCustomerRef": customer, "senderAccountRef": sender,
            "recipientAccountRef": recipient,
            "channel": channel or {"ACCOUNT_TRANSFER": "MOBILE_BANKING",
                                   "OPEN_BANKING_TRANSFER": "OPEN_BANKING",
                                   "ATM_WITHDRAWAL": "ATM",
                                   "LOAN_DISBURSED": "CORE_BANKING"}[kind],
            "deviceRef": device}


def create(ctx, token, body, *, key=None, status=201):
    return request(ctx, token, "POST", "/transactions", status, body,
                   {"Idempotency-Key": key or "i394-" + uuid.uuid4().hex})


def safe_error_probe(ctx, token, body, key):
    script = (
        "import json,sys,urllib.request,urllib.error;"
        "data=json.load(sys.stdin);"
        "req=urllib.request.Request('http://127.0.0.1:8080/api/v1/transactions',"
        "data=json.dumps(data['body']).encode(),method='POST',"
        "headers={'Authorization':'Bearer '+data['token'],"
        "'Content-Type':'application/json','Idempotency-Key':data['key']});"
        "\ntry:\n"
        " r=urllib.request.urlopen(req,timeout=10); print(json.dumps({'status':r.status,'code':None}))\n"
        "except urllib.error.HTTPError as e:\n"
        " result=json.load(e); print(json.dumps({'status':e.code,'code':result.get('code')}))\n"
    )
    payload = json.dumps({"token": token, "body": body, "key": key})
    output = ctx.compose_run(["exec", "-T", "local-jwt-fixture", "python", "-c", script],
                             input_bytes=payload.encode("utf-8"), sensitive=True)
    return json.loads(output)


def event(ctx, token, kind, customer, account, *, device=None, recipient=None,
          occurred=None):
    body = {"eventId": uid(), "eventType": kind, "occurredAt": stamp(occurred or now()),
            "externalCustomerRef": customer, "accountRef": account,
            "deviceRef": device, "transactionId": None, "beneficiaryRef": recipient}
    request(ctx, token, "POST", "/behavior-events", 201, body)
    return body["eventId"]


def fact(ctx, token, kind, customer, *, account=None, device=None, event_id=None,
         at=None, from_at=None, through=None, state=None, scope=None,
         old=None, new=None):
    body = {"factId": uid(), "factType": kind, "externalCustomerRef": customer,
            "accountRef": account, "deviceRef": device, "behaviorEventId": event_id,
            "occurredAt": stamp(at or now()), "coverageFrom": stamp(from_at) if from_at else None,
            "coverageThrough": stamp(through) if through else None,
            "assertionState": state, "limitScope": scope, "previousLimit": old,
            "newLimit": new}
    request(ctx, token, "POST", "/fds-mock-facts", 201, body)
    return body["factId"]


def cover(ctx, token, kind, customer, *, account=None, days=1):
    moment = now()
    return fact(ctx, token, kind, customer, account=account, state="COMPLETE",
                at=moment, from_at=moment - dt.timedelta(days=days),
                through=moment + dt.timedelta(minutes=3))


def link(ctx, token, code, source, target, customer, account, state="VERIFIED"):
    body = {"linkId": uid(), "scenarioCode": code, "sourceTransactionId": source,
            "targetTransactionId": target, "externalCustomerRef": customer,
            "accountRef": account, "assertionState": state, "occurredAt": stamp(now())}
    request(ctx, token, "POST", "/fds-mock-fund-links", 201, body)
    return body["linkId"]


def open_owner(ctx, token, customer, account, state="VERIFIED"):
    body = {"assertionId": uid(), "externalCustomerRef": customer,
            "externalAccountRef": account, "registrationState": state,
            "ownershipState": state, "occurredAt": stamp(now())}
    request(ctx, token, "POST", "/fds-mock-open-accounts", 201, body)
    return body["assertionId"]


def snapshot(ctx, transaction_id):
    return json.loads(ctx.sql_scalar("""SELECT json_build_object(
        'transactionId',t.transaction_id,'status',t.processing_status,
        'outcome',t.risk_response_outcome,'risk',t.risk_level,
        'detectionResultId',d.detection_result_id,'policy',d.scoring_policy_version,
        'rule',d.rule_risk_score,'ml',d.ml_contribution,'score',d.risk_score,
        'version',d.detection_result_version,'ruleSet',d.rule_set_version,
        'feature',d.ml_feature_version,'model',d.model_version,'modelSha',d.model_sha256,
        'evidenceCount',(SELECT count(*) FROM detection_evidence e WHERE e.detection_result_id=d.id),
        'caseIds',(SELECT coalesce(json_agg(c.case_id),'[]'::json) FROM case_transaction ct
          JOIN fraud_case c ON c.id=ct.fraud_case_id WHERE ct.financial_transaction_id=t.id),
        'audit',(SELECT count(*) FROM audit_log a WHERE a.transaction_id=t.transaction_id))
        FROM financial_transaction t LEFT JOIN detection_result d
          ON d.id=t.adopted_detection_result_id
        WHERE t.transaction_id='%s'""" % transaction_id))


def db_json(ctx, sql):
    return json.loads(ctx.sql_scalar(sql))


def quoted_ids(values):
    # IDs originate from uuid4(), never user input.
    return ",".join("'%s'" % uuid.UUID(value) for value in values)


def evidence_rows(ctx, transaction_id):
    return db_json(ctx, """SELECT coalesce(json_agg(json_build_object(
        'type',e.evidence_type,'ruleCode',e.rule_code,'ruleVersion',e.rule_version,
        'reasonCode',e.reason_code,'score',e.score_contribution,
        'applied',e.applied_contribution,'observation',e.observation_summary)
        ORDER BY e.sort_order),'[]'::json) FROM financial_transaction t
        JOIN detection_result d ON d.id=t.adopted_detection_result_id
        JOIN detection_evidence e ON e.detection_result_id=d.id
        WHERE t.transaction_id='%s'""" % transaction_id)


def check(ctx, viewer, body, response, code=None, anchors=None):
    transaction_id = body["transactionId"]
    adopted = request(ctx, viewer, "GET", "/transactions/" + transaction_id
                      + "/adopted-detection-result")["adoptedResult"]
    stored = snapshot(ctx, transaction_id)
    assert adopted and adopted["detectionResultId"] == response["adoptedDetectionResultId"]
    assert stored["detectionResultId"] == adopted["detectionResultId"]
    assert stored["status"] == response["processingStatus"]
    assert stored["outcome"] == response["riskResponseOutcome"]
    assert stored["risk"] == adopted["riskLevel"]
    assert stored["score"] == adopted["riskScore"]
    assert stored["version"] == adopted["detectionResultVersion"]
    assert stored["ruleSet"] == adopted["ruleSetVersion"]
    assert re.fullmatch(r"[0-9a-f]{64}", stored["ruleSet"])
    assert stored["feature"] == adopted["mlFeatureVersion"]
    assert stored["model"] == adopted["modelVersion"]
    assert stored["modelSha"] == adopted["modelSha256"]
    assert stored["rule"] == adopted["ruleScore"]
    assert stored["ml"] == adopted["mlContribution"]
    assert stored["policy"] == adopted["scoringPolicyVersion"] == "rule-ml-policy-v6"
    assert stored["score"] == min(100, stored["rule"] + stored["ml"])
    evidence = evidence_rows(ctx, transaction_id)
    assert stored["evidenceCount"] == len(evidence)
    db_rules = [(row["ruleCode"], row["ruleVersion"], row["reasonCode"],
                 row["score"], row["applied"]) for row in evidence if row["type"] == "RULE"]
    api_rules = [(row["ruleCode"], row["ruleVersion"], row["reasonCode"],
                  row["scoreContribution"], row["appliedContribution"])
                 for row in adopted["ruleEvidence"]]
    assert api_rules == db_rules
    db_ml = [(row["reasonCode"], row["score"],
              row["observation"].get("probabilityBasisPoints"))
             for row in evidence if row["type"] == "ML"]
    api_ml = [(row["reasonCode"], row["scoreContribution"], row["probabilityBasisPoints"])
              for row in adopted["mlEvidence"]]
    assert api_ml == db_ml
    history = {row["reasonCode"]: row["observation"] for row in evidence
               if row["type"] == "BEHAVIOR_PATTERN"}
    for field, reason in (("aggregationEvidence", "AGGREGATION_HISTORY_CONTEXT"),
                          ("fundFlowEvidence", "FUND_FLOW_HISTORY_CONTEXT"),
                          ("openBankingEvidence", "OPEN_BANKING_CHAIN_CONTEXT")):
        assert adopted.get(field) == history.get(reason)
    if adopted.get("scn001002Evidence"):
        projection = adopted["scn001002Evidence"]
        prior = history["SCENARIO_HISTORY_CONTEXT"]
        assert all(projection[field] == prior[field] for field in
                   ("baselineStatus", "baselineCount", "baselineDays",
                    "recipientStatus", "deviceStatus"))
        external = next(row["observation"] for row in evidence
                        if row["type"] == "EXTERNAL_RISK")
        assert all(projection[field] == external[field] for field in
                   ("providerCode", "providerAsOf", "lookedUpAt",
                    "recipientAccountMatched", "exactDeviceMatched"))
    if adopted.get("scn003Evidence"):
        projection = adopted["scn003Evidence"]
        external = next(row["observation"] for row in evidence
                        if row["type"] == "EXTERNAL_RISK")
        prior = history["SCENARIO_HISTORY_CONTEXT"]
        assert all(projection[field] == external[field] for field in
                   ("providerCode", "providerAsOf", "lookedUpAt",
                    "recipientAccountMatched"))
        assert projection["priorApprovedRecipientTransferObserved"] == (
            prior["priorApprovedRecipientTransferObserved"])
    assert stored["audit"] >= 2
    rules = {row["ruleCode"] for row in adopted["ruleEvidence"]}
    if code:
        assert RULES[code] in rules, (code, rules)
        rule_observation = next(row["observation"] for row in evidence
                                if row["type"] == "RULE" and row["ruleCode"] == RULES[code])
        expected_source = {"SCN001": "SCN001-002-contract-v1",
                           "SCN002": "SCN001-002-contract-v1",
                           "SCN004": "SCN004-005-contract-v1",
                           "SCN005": "SCN004-005-contract-v1",
                           "SCN006": "SCN006-007-contract-v1",
                           "SCN007": "SCN006-007-contract-v1",
                           "SCN008": "SCN008-contract-v1"}.get(code)
        if expected_source:
            assert rule_observation["sourceVersion"] == expected_source
        for field, expected in (anchors or {}).items():
            if field == "caseTransactionIds":
                continue
            if field == "stages":
                actual_stages = {stage["sourceTransactionId"]: stage
                                 for stage in rule_observation["stages"]}
                assert set(actual_stages) == {stage["sourceTransactionId"] for stage in expected}
                assert all({key: actual_stages[stage["sourceTransactionId"]][key]
                            for key in stage} == stage for stage in expected)
                assert len(rule_observation["stages"]) == len(expected)
            else:
                assert rule_observation[field] == expected, (code, field)
        if code in {"SCN006", "SCN007", "SCN008"}:
            stages = anchors["stages"] if code == "SCN008" else [anchors]
            source_ids = [stage["sourceTransactionId"] for stage in stages]
            sources = db_json(ctx, """SELECT coalesce(json_agg(json_build_object(
                'id',transaction_id,'status',processing_status,
                'approvedBeforeCutoff',approved_at<='%s'::timestamptz)),
                '[]'::json) FROM financial_transaction WHERE transaction_id IN (%s)""" %
                              (body["occurredAt"], quoted_ids(source_ids)))
            assert {row["id"] for row in sources} == set(source_ids)
            assert all(row["status"] == "APPROVED" and row["approvedBeforeCutoff"]
                       for row in sources)
            link_ids = [stage.get("currentLinkId", stage.get("linkId")) for stage in stages]
            links = db_json(ctx, """SELECT coalesce(json_agg(json_build_object(
                'id',link_id,'source',source_transaction_id,'target',target_transaction_id,
                'state',assertion_state)),'[]'::json) FROM fds_mock_fund_link
                WHERE link_id IN (%s)""" % quoted_ids(link_ids))
            assert {(row["id"], row["source"]) for row in links} == {
                (link_ids[index], source_ids[index]) for index in range(len(stages))}
            assert all(row["target"] == transaction_id and row["state"] == "VERIFIED"
                       for row in links)
            if code == "SCN008":
                owner_ids = [stage["ownershipAssertionId"] for stage in stages]
                owners = db_json(ctx, """SELECT coalesce(json_agg(json_build_object(
                    'id',assertion_id,'registration',registration_state,
                    'ownership',ownership_state)),'[]'::json)
                    FROM fds_mock_open_account WHERE assertion_id IN (%s)""" %
                                 quoted_ids(owner_ids))
                assert {row["id"] for row in owners} == set(owner_ids)
                assert all(row["registration"] == row["ownership"] == "VERIFIED"
                           for row in owners)
        if code in {"SCN004", "SCN005", "SCN006", "SCN007", "SCN008"}:
            assert rule_observation["evaluationCutoffAt"] == body["occurredAt"]
        assert response["caseId"] and response["caseId"] in stored["caseIds"]
        case_id = response["caseId"]
        case = request(ctx, viewer, "GET", "/cases/" + case_id)["case"]
        assert case["caseId"] == case_id and case["caseStatus"] == "OPEN"
        links = request(ctx, viewer, "GET", "/cases/" + case_id + "/transactions")
        db_links = set(ctx.sql_scalar("""SELECT t.transaction_id FROM case_transaction ct
            JOIN fraud_case c ON c.id=ct.fraud_case_id
            JOIN financial_transaction t ON t.id=ct.financial_transaction_id
            WHERE c.case_id='%s'""" % case_id).splitlines())
        assert {item["transactionId"] for item in links["content"]} == db_links
        assert transaction_id in db_links
        if anchors and "caseTransactionIds" in anchors:
            assert db_links == {transaction_id, *anchors["caseTransactionIds"]}, code
        db_case = db_json(ctx, """SELECT json_build_object('id',case_id,
            'status',case_status,'count',(SELECT count(*) FROM case_transaction ct
            WHERE ct.fraud_case_id=fraud_case.id)) FROM fraud_case
            WHERE case_id='%s'""" % case_id)
        assert db_case == {"id": case_id, "status": case["caseStatus"],
                           "count": case["relatedTransactionCount"]}
        audit = request(ctx, viewer, "GET", "/cases/" + case_id + "/audit-logs")
        db_audit = db_json(ctx, """SELECT coalesce(json_agg(json_build_object(
            'action',action,'reasonCode',reason_code,'actorType',actor_type,
            'afterSummary',after_value_summary) ORDER BY changed_at DESC,id DESC),
            '[]'::json) FROM audit_log WHERE case_id='%s' AND target_type='FRAUD_CASE'""" % case_id)
        api_audit = audit["content"]
        assert len(api_audit) == len(db_audit) == audit["page"]["totalElements"]
        assert [(x["action"], x["reasonCode"], x["actorType"], x["afterSummary"])
                for x in api_audit] == [(x["action"], x["reasonCode"], x["actorType"],
                                         x["afterSummary"]) for x in db_audit]
        assert {x["action"] for x in db_audit} >= {"CASE_CREATED", "CASE_TRANSACTION_LINKED"}
        tx_audit = db_json(ctx, """SELECT coalesce(json_agg(json_build_object(
            'action',action,'reason',reason_code,'transaction',transaction_id,
            'detection',metadata->>'detectionResultId')),'[]'::json)
            FROM audit_log WHERE transaction_id='%s' AND target_type='FINANCIAL_TRANSACTION'"""
                           % transaction_id)
        assert {x["action"] for x in tx_audit} == {
            "TRANSACTION_RISK_RESPONSE_APPLIED", "TRANSACTION_STATUS_CHANGED"}
        assert all(x["transaction"] == transaction_id and
                   x["detection"] == stored["detectionResultId"] for x in tx_audit)
    else:
        assert response["caseId"] is None and stored["caseIds"] == []
        assert response["processingStatus"] == "APPROVED"
    evidence_ids = db_json(ctx, """SELECT coalesce(json_agg(e.evidence_id ORDER BY e.sort_order),
        '[]'::json) FROM financial_transaction t JOIN detection_result d
        ON d.id=t.adopted_detection_result_id JOIN detection_evidence e
        ON e.detection_result_id=d.id WHERE t.transaction_id='%s'""" % transaction_id)
    audit_ids = db_json(ctx, """SELECT coalesce(json_agg(a.audit_id ORDER BY a.id),'[]'::json)
        FROM audit_log a WHERE a.transaction_id='%s'""" % transaction_id)
    assert len(evidence_ids) == len(evidence) and len(audit_ids) == stored["audit"]
    print(json.dumps({"step": code or "NORMAL", "exitCode": 0,
                      "transactionId": transaction_id,
                      "detectionResultId": stored["detectionResultId"],
                      "caseId": response["caseId"], "policy": stored["policy"],
                      "model": stored["model"], "modelSha": stored["modelSha"],
                      "ruleSetVersion": stored["ruleSet"],
                      "mlFeatureVersion": stored["feature"],
                      "evidenceCount": len(evidence),
                      "evidenceIds": evidence_ids if code else None,
                      "auditIds": audit_ids if code else None,
                      "ruleScore": stored["rule"], "mlContribution": stored["ml"],
                      "riskScore": stored["score"], "status": stored["status"],
                      "riskResponseOutcome": stored["outcome"],
                      "auditCount": stored["audit"]}), flush=True)
    return adopted


def dashboard_view(ctx, viewer, date):
    end = date + dt.timedelta(days=1)
    path = f"?from={date.isoformat()}&to={end.isoformat()}"
    return (request(ctx, viewer, "GET", "/fds-dashboard/transactions" + path),
            request(ctx, viewer, "GET", "/fds-dashboard/cases" + path))


def dashboard(ctx, viewer, created, failed_ids, baseline, date, baseline_ids):
    kst = dt.timezone(dt.timedelta(hours=9))
    assert now().astimezone(kst).date() == date
    transactions, cases = dashboard_view(ctx, viewer, date)
    day = transactions["days"][0]
    case_day = cases["days"][0]
    before_day, before_case = baseline[0]["days"][0], baseline[1]["days"][0]
    expected_ids = {body["transactionId"] for body, _, _ in created}
    assert len(expected_ids) == len(created)
    new_transactions = db_json(ctx, """SELECT coalesce(json_agg(json_build_object(
        'id',transaction_id,'adopted',adopted_detection_result_id IS NOT NULL)),
        '[]'::json) FROM financial_transaction WHERE id>%d
        AND (occurred_at AT TIME ZONE 'Asia/Seoul')::date='%s'"""
                               % (baseline_ids[0], date.isoformat()))
    assert {x["id"] for x in new_transactions} == expected_ids | set(failed_ids)
    assert {x["id"] for x in new_transactions if x["adopted"]} == expected_ids
    assert day["total"] - before_day["total"] == len(new_transactions)
    assert day["classified"] - before_day["classified"] == sum(x["adopted"] for x in new_transactions)
    assert day["unclassified"] - before_day["unclassified"] == sum(not x["adopted"] for x in new_transactions)
    assert all(snapshot(ctx, failed_id)["detectionResultId"] is None for failed_id in failed_ids)
    expected_scenarios = Counter()
    expected_by_code = {code: set() for code in RULES}
    expected_cases = set()
    for body, response, code in created:
        if code:
            expected_scenarios[code] += 1
            expected_by_code[code].add(body["transactionId"])
            if code == "SCN005":
                expected_scenarios["SCN003"] += 1
                expected_by_code["SCN003"].add(body["transactionId"])
        if response["caseId"]:
            expected_cases.add(response["caseId"])
    for code in RULES:
        db_matches = set(ctx.sql_scalar("""SELECT DISTINCT t.transaction_id
            FROM financial_transaction t JOIN detection_result d
            ON d.id=t.adopted_detection_result_id JOIN detection_evidence e
            ON e.detection_result_id=d.id WHERE t.id>%d
            AND (t.occurred_at AT TIME ZONE 'Asia/Seoul')::date='%s'
            AND e.evidence_type='RULE' AND e.rule_code='%s'""" %
            (baseline_ids[0], date.isoformat(), RULES[code])).splitlines())
        assert db_matches == expected_by_code[code], (code, db_matches, expected_by_code[code])
        assert day["scenarios"][code] - before_day["scenarios"][code] == len(db_matches), code
    new_cases = db_json(ctx, """SELECT coalesce(json_agg(json_build_object(
        'id',case_id,'status',case_status)),'[]'::json) FROM fraud_case
        WHERE id>%d AND (created_at AT TIME ZONE 'Asia/Seoul')::date='%s'""" %
                        (baseline_ids[1], date.isoformat()))
    assert {x["id"] for x in new_cases} == expected_cases
    assert case_day["newCases"] - before_case["newCases"] == len(new_cases)
    db_statuses = Counter(x["status"] for x in new_cases)
    for status, count in cases["currentStatuses"].items():
        assert count - baseline[1]["currentStatuses"].get(status, 0) == db_statuses[status]
    print(json.dumps({"step": "dashboard", "exitCode": 0, "date": date.isoformat(),
                      "totalDelta": day["total"] - before_day["total"],
                      "classifiedDelta": day["classified"] - before_day["classified"],
                      "unclassifiedDelta": day["unclassified"] - before_day["unclassified"],
                      "newCasesDelta": case_day["newCases"] - before_case["newCases"],
                      "transactionIds": sorted(expected_ids), "caseIds": sorted(expected_cases),
                      "scenarios": dict(expected_scenarios)}), flush=True)


def verify(ctx):
    ctx.compose_run(["up", "-d", "--no-build", "--no-deps", "--force-recreate", "--wait",
                     "external-risk-mock"], timeout=180)
    ingest = ctx.mint("service-transaction-ingestor")
    behavior = ctx.mint("service-behavior-ingestor")
    viewer = ctx.mint("user-viewer")
    suffix = uuid.uuid4().hex[:12]
    created = []
    kst_date = now().astimezone(dt.timezone(dt.timedelta(hours=9))).date()
    baseline = dashboard_view(ctx, viewer, kst_date)
    baseline_ids = (int(ctx.sql_scalar("SELECT coalesce(max(id),0) FROM financial_transaction")),
                    int(ctx.sql_scalar("SELECT coalesce(max(id),0) FROM fraud_case")))
    print(json.dumps({"step": "run-baseline", "exitCode": 0,
                      "project": ctx.project, "attemptId": suffix,
                      "date": kst_date.isoformat(),
                      "transactions": baseline[0]["days"][0]["total"],
                      "classified": baseline[0]["days"][0]["classified"],
                      "unclassified": baseline[0]["days"][0]["unclassified"],
                      "newCases": baseline[1]["days"][0]["newCases"]}), flush=True)

    def submit(body, code=None, *, key=None, anchors=None):
        response = create(ctx, ingest, body, key=key)
        check(ctx, viewer, body, response, code, anchors)
        if dt.datetime.fromisoformat(body["occurredAt"].replace("Z", "+00:00")).astimezone(
                dt.timezone(dt.timedelta(hours=9))).date() == now().astimezone(
                    dt.timezone(dt.timedelta(hours=9))).date():
            created.append((body, response, code))
        return response

    # The dedicated 382 overlay initially supplies a recipient-risk Provider.
    scn003 = transaction("i394-003-" + suffix, "i394-003-sender-" + suffix,
                         "issue382-synthetic-risk-" + suffix, 10000000)
    risk = submit(scn003, "SCN003")
    key = "i394-" + uuid.uuid4().hex
    replay_body = transaction("i394-replay-" + suffix, "i394-replay-sender-" + suffix,
                              "i394-replay-recipient-" + suffix, 1000)
    replay = submit(replay_body, key=key)
    before = snapshot(ctx, replay_body["transactionId"])
    assert create(ctx, ingest, replay_body, key=key)["adoptedDetectionResultId"] == before["detectionResultId"]
    create(ctx, ingest, dict(replay_body, amount="1001"), key=key, status=409)
    assert snapshot(ctx, replay_body["transactionId"]) == before
    print(json.dumps({"step": "idempotency-replay-conflict", "exitCode": 0,
                      "transactionId": replay_body["transactionId"]}), flush=True)

    failed = transaction("i394-provider-failure-" + suffix, "i394-failure-sender-" + suffix,
                         "issue382-synthetic-unavailable-" + suffix, 1000)
    create(ctx, ingest, failed, status=503)
    broken = snapshot(ctx, failed["transactionId"])
    assert broken["status"] == "RECEIVED" and broken["detectionResultId"] is None
    print(json.dumps({"step": "provider-fail-closed", "exitCode": 0,
                      "transactionId": failed["transactionId"]}), flush=True)

    # Recreate only this execution's fixture with the approved exact-device mode.
    ctx.compose.extend(["-f", str(ROOT / "infra" / "compose.issue384-e2e.yml")])
    ctx.compose_run(["up", "-d", "--no-build", "--no-deps", "--force-recreate", "--wait",
                     "external-risk-mock"], timeout=180)
    print(json.dumps({"step": "provider-fixture-switch", "exitCode": 0}), flush=True)

    # Synthetic approved history is a prerequisite, not a scenario target.
    customer = "i394-001-" + suffix
    account = "i394-001-sender-" + suffix
    for index in range(10):
        historical = transaction(customer, account, "i394-001-prior-%d-%s" % (index, suffix),
                                 100000, device="i394-001-old-device-" + suffix,
                                 occurred=now() - dt.timedelta(days=3 + index % 3,
                                                               seconds=index))
        tid = historical["transactionId"]
        ctx.sql_scalar("""INSERT INTO financial_transaction
            (transaction_id,transaction_type,amount,currency_code,occurred_at,
             external_customer_ref,sender_account_ref,recipient_account_ref,
             channel,device_ref,processing_status)
            VALUES ('%s','ACCOUNT_TRANSFER',100000,'KRW','%s',
                    '%s','%s','%s','MOBILE_BANKING','%s','RECEIVED');
            UPDATE financial_transaction SET processing_status='APPROVED',version=version+1
            WHERE transaction_id='%s'""" %
            (tid, historical["occurredAt"], customer, account,
             historical["recipientAccountRef"], historical["deviceRef"], tid))
        assert ctx.sql_scalar("SELECT (approved_at IS NOT NULL)::text FROM financial_transaction "
                              "WHERE transaction_id='%s'" % tid) == "true"
    new_device = "i394-001-new-device-" + suffix
    first_recipient = "i394-001-new-recipient-" + suffix
    device_at = now() - dt.timedelta(minutes=5)
    device_id = event(ctx, behavior, "DEVICE_REGISTERED", customer, account,
                      device=new_device, occurred=device_at)
    event(ctx, behavior, "BENEFICIARY_REGISTERED", customer, account,
          recipient=first_recipient, occurred=now() - dt.timedelta(minutes=4))
    epoch = dt.datetime(1970, 1, 1, tzinfo=UTC)
    coverage_ids = {}
    for kind in ("TRANSFER_HISTORY_COMPLETE", "DEVICE_HISTORY_COMPLETE"):
        coverage_ids[kind] = fact(ctx, behavior, kind, customer, state="COMPLETE",
                                  from_at=epoch, through=now() + dt.timedelta(minutes=3))
    trust_id = fact(ctx, behavior, "DEVICE_TRUST", customer, account=account,
                    device=new_device, event_id=device_id, at=device_at, state="UNVERIFIED")
    time.sleep(1.2)
    submit(transaction(customer, account, first_recipient, 1000000,
                       device=new_device), "SCN001", anchors={
                           "deviceEventId": device_id, "deviceTrustFactId": trust_id,
                           "deviceCoverageFactId": coverage_ids["DEVICE_HISTORY_COMPLETE"]})

    # 002 has a strict password -> real limit uplift -> transfer order.
    c2 = "i394-002-" + suffix
    a2 = "i394-002-sender-" + suffix
    password_at = now() - dt.timedelta(minutes=10)
    limit_at = now() - dt.timedelta(minutes=5)
    password = event(ctx, behavior, "PASSWORD_CHANGED", c2, a2, occurred=password_at)
    limit = event(ctx, behavior, "TRANSFER_LIMIT_CHANGED", c2, a2, occurred=limit_at)
    limit_fact = fact(ctx, behavior, "LIMIT_CHANGE", c2, account=a2, event_id=limit,
                      at=limit_at, state="SUCCEEDED", scope="PER_TRANSFER",
                      old=2000000, new=12000000)
    identity_fact = fact(ctx, behavior, "IDENTITY_CHECK", c2, account=a2,
                         event_id=password, at=password_at, state="VERIFIED")
    time.sleep(1.2)
    submit(transaction(c2, a2, "i394-002-recipient-" + suffix, 10000000), "SCN002",
           anchors={"passwordEventId": password, "limitEventId": limit,
                    "limitFactId": limit_fact, "identityFactId": identity_fact})

    # 004: three approved transfers by one customer to different recipients.
    c4 = "i394-004-" + suffix
    a4 = "i394-004-sender-" + suffix
    prior4 = []
    for index in range(2):
        source4 = transaction(c4, a4, "i394-004-prior-%d-%s" % (index, suffix),
                              1200000, device="i394-004-safe-" + suffix)
        submit(source4)
        prior4.append(source4["transactionId"])
    coverage4 = fact(ctx, behavior, "TRANSFER_HISTORY_COMPLETE", c4, state="COMPLETE",
                     from_at=dt.datetime(1970, 1, 1, tzinfo=UTC),
                     through=now() + dt.timedelta(minutes=3))
    recipient4 = "i394-004-target-" + suffix
    event(ctx, behavior, "BENEFICIARY_REGISTERED", c4, a4,
          recipient=recipient4, occurred=now() - dt.timedelta(minutes=2))
    time.sleep(1.2)
    submit(transaction(c4, a4, recipient4, 10000000,
                       device="issue384-synthetic-risk-" + suffix), "SCN004",
           anchors={"coverageFactId": coverage4, "caseTransactionIds": prior4})

    # 005: three approved customers into one collector. The target has an
    # independent R005 recipient and amount risk signals, so it requires a case.
    collector = "issue382-synthetic-risk-" + suffix
    prior5 = []
    for index in range(2):
        source5 = transaction("i394-005-prior-%d-%s" % (index, suffix),
                              "i394-005-sender-%d-%s" % (index, suffix), collector,
                              2000000, device="i394-005-safe-" + suffix)
        submit(source5)
        prior5.append(source5["transactionId"])
    coverage5 = cover(ctx, behavior, "INBOUND_ACCOUNT_HISTORY_COMPLETE",
                      "i394-005-owner-" + suffix, account=collector)
    ctx.compose = ctx.compose[:-2]
    ctx.compose_run(["up", "-d", "--no-build", "--no-deps", "--force-recreate", "--wait",
                     "external-risk-mock"], timeout=180)
    time.sleep(1.2)
    submit(transaction("i394-005-target-" + suffix, "i394-005-target-sender-" + suffix,
                       collector, 10000000,
                       device="issue384-synthetic-risk-" + suffix), "SCN005",
           anchors={"coverageFactId": coverage5, "caseTransactionIds": prior5})

    # 006: approved inbound source, predeclared directional link, ATM use.
    c6 = "i394-006-" + suffix
    a6 = "i394-006-account-" + suffix
    source6 = transaction("i394-006-origin-" + suffix, "i394-006-origin-account-" + suffix,
                          a6, 10000000)
    submit(source6)
    target6 = transaction(c6, a6, None, 8000000, kind="ATM_WITHDRAWAL")
    coverage6 = cover(ctx, behavior, "ACCOUNT_FLOW_HISTORY_COMPLETE", c6, account=a6)
    link6 = link(ctx, behavior, "SCN006", source6["transactionId"],
                 target6["transactionId"], c6, a6)
    time.sleep(1.2)
    target6["occurredAt"] = stamp(now())
    submit(target6, "SCN006", anchors={"sourceTransactionId": source6["transactionId"],
                                     "currentLinkId": link6,
                                     "historyCoverageFactId": coverage6,
                                     "caseTransactionIds": [source6["transactionId"]]})

    # 007: approved loan, one newly registered beneficiary, 80% disbursement.
    c7 = "i394-007-" + suffix
    a7 = "i394-007-account-" + suffix
    loan = transaction(c7, a7, None, 10000000, kind="LOAN_DISBURSED")
    submit(loan)
    recipient7 = "i394-007-new-recipient-" + suffix
    beneficiary7 = event(ctx, behavior, "BENEFICIARY_REGISTERED", c7, a7,
                         recipient=recipient7, occurred=now())
    target7 = transaction(c7, a7, recipient7, 8000000)
    coverage7 = cover(ctx, behavior, "ACCOUNT_FLOW_HISTORY_COMPLETE", c7, account=a7)
    link7 = link(ctx, behavior, "SCN007", loan["transactionId"],
                 target7["transactionId"], c7, a7)
    time.sleep(1.2)
    target7["occurredAt"] = stamp(now())
    submit(target7, "SCN007", anchors={"sourceTransactionId": loan["transactionId"],
                                     "currentLinkId": link7,
                                     "beneficiaryEventId": beneficiary7,
                                     "historyCoverageFactId": coverage7,
                                     "caseTransactionIds": [loan["transactionId"]]})

    # 008: two distinct Mock-owned external sources into the same hub.
    c8 = "i394-008-" + suffix
    hub = "i394-008-hub-" + suffix
    external = ["i394-008-external-a-" + suffix, "i394-008-external-b-" + suffix]
    owner_ids = {}
    for account8 in external:
        owner_ids[account8] = open_owner(ctx, behavior, c8, account8)
    time.sleep(1.2)
    sources8 = []
    for account8 in external:
        source8 = transaction(c8, account8, hub, 5000000,
                              kind="OPEN_BANKING_TRANSFER")
        submit(source8)
        sources8.append(source8)
    target8 = transaction(c8, hub, "i394-008-out-" + suffix, 8000000)
    coverage8 = cover(ctx, behavior, "ACCOUNT_FLOW_HISTORY_COMPLETE", c8, account=hub)
    stages8 = []
    for source8 in sources8:
        link8 = link(ctx, behavior, "SCN008", source8["transactionId"],
                     target8["transactionId"], c8, hub)
        stages8.append({"sourceTransactionId": source8["transactionId"],
                        "linkId": link8,
                        "ownershipAssertionId": owner_ids[source8["senderAccountRef"]]})
    time.sleep(1.2)
    target8["occurredAt"] = stamp(now())
    submit(target8, "SCN008", anchors={"stages": stages8,
                                     "historyCoverageFactId": coverage8,
                                     "caseTransactionIds": [source8["transactionId"]
                                                            for source8 in sources8]})

    normal = transaction("i394-normal-" + suffix, "i394-normal-sender-" + suffix,
                         "i394-normal-recipient-" + suffix, 1000)
    normal_result = submit(normal)
    assert normal_result["caseId"] is None
    dashboard(ctx, viewer, created, [failed["transactionId"]], baseline, kst_date, baseline_ids)

    # Report request is a separate API operation after transaction finalization.
    report_only(ctx, risk["caseId"], scn003["transactionId"])
    dashboard(ctx, viewer, created, [failed["transactionId"]], baseline, kst_date, baseline_ids)
    fault_ids = faults(ctx, suffix)
    dashboard(ctx, ctx.mint("user-viewer"), created, [failed["transactionId"], *fault_ids],
              baseline, kst_date, baseline_ids)


def faults(ctx, suffix):
    overlay = ["-f", str(ROOT / "infra" / "local-jwt-fixture" /
                         "compose.issue394-fault.yml")]
    ctx.compose.extend(overlay)
    failure_ids = []
    try:
        for stage in ("rule", "ml"):
            ctx.env["FINGUARDOPS_ISSUE394_RULE_BASE_URL"] = (
                "http://127.0.0.1:65534" if stage == "rule" else "http://ai-service:8000")
            ctx.env["FINGUARDOPS_ISSUE394_ML_BASE_URL"] = (
                "http://127.0.0.1:65534" if stage == "ml" else "http://ai-service:8000")
            ctx.compose_run(["up", "-d", "--no-build", "--no-deps", "--force-recreate",
                             "--wait", "backend"], timeout=180)
            # Both fixtures share backend's network namespace and must follow it.
            for service in ("local-jwt-fixture", "external-risk-mock"):
                ctx.compose_run(["up", "-d", "--no-build", "--no-deps", "--force-recreate",
                                 "--wait", service], timeout=180)
            ingest = ctx.mint("service-transaction-ingestor")
            viewer = ctx.mint("user-viewer")
            body = transaction("i394-" + stage + "-fault-" + suffix,
                               "i394-" + stage + "-fault-sender-" + suffix,
                               "i394-" + stage + "-fault-recipient-" + suffix, 1000)
            key = "i394-" + uuid.uuid4().hex
            result = safe_error_probe(ctx, ingest, body, key)
            assert result == {"status": 503, "code": "DEPENDENCY_UNAVAILABLE"}, result
            transaction_id = body["transactionId"]
            failure = db_json(ctx, """SELECT json_build_object(
                'transactionStatus',t.processing_status,
                'adopted',t.adopted_detection_result_id,
                'analysis',d.analysis_status,'failureCode',d.failure_code,
                'evidence',(SELECT count(*) FROM detection_evidence e
                  WHERE e.detection_result_id=d.id),
                'cases',(SELECT count(*) FROM case_transaction ct
                  WHERE ct.financial_transaction_id=t.id),
                'audit',(SELECT count(*) FROM audit_log a
                  WHERE a.transaction_id=t.transaction_id))
                FROM financial_transaction t JOIN detection_result d
                ON d.financial_transaction_id=t.id WHERE t.transaction_id='%s'"""
                              % transaction_id)
            assert failure["transactionStatus"] == "FAILED"
            assert failure["adopted"] is None and failure["analysis"] == "FAILED"
            assert failure["evidence"] == failure["cases"] == failure["audit"] == 0
            if stage == "rule":
                assert failure["failureCode"] == "AI_SERVICE_UNAVAILABLE"
            else:
                assert failure["failureCode"] in {"ML_SERVICE_UNAVAILABLE", "ML_TIMEOUT"}
            adoption = request(ctx, viewer, "GET", "/transactions/" + transaction_id
                               + "/adopted-detection-result")
            assert adoption["availability"] == "FAILED" and adoption["adoptedResult"] is None
            assert safe_error_probe(ctx, ingest, body, key) == result
            assert snapshot(ctx, transaction_id)["detectionResultId"] is None
            failure_ids.append(transaction_id)
            print(json.dumps({"step": stage + "-fault", "exitCode": 0,
                              "transactionId": transaction_id,
                              "failureCode": failure["failureCode"],
                              "status": failure["transactionStatus"]}), flush=True)
    finally:
        ctx.compose = ctx.compose[:-2]
        ctx.env.pop("FINGUARDOPS_ISSUE394_RULE_BASE_URL", None)
        ctx.env.pop("FINGUARDOPS_ISSUE394_ML_BASE_URL", None)
        ctx.compose_run(["up", "-d", "--no-build", "--no-deps", "--force-recreate",
                         "--wait", "backend"], timeout=180)
        for service in ("local-jwt-fixture", "external-risk-mock"):
            ctx.compose_run(["up", "-d", "--no-build", "--no-deps", "--force-recreate",
                             "--wait", service], timeout=180)
    return failure_ids


def report_only(ctx, case_id, transaction_id):
    analyst = ctx.mint("user-analyst")
    viewer = ctx.mint("user-viewer")
    detail = request(ctx, analyst, "GET", "/cases/" + case_id)["case"]
    before = snapshot(ctx, transaction_id)
    assert case_id in before["caseIds"]
    changed = request(ctx, analyst, "PATCH", "/cases/" + case_id + "/status", 200,
                      {"targetStatus": "IN_REVIEW", "assigneeRef": JWT.IDENTITIES["user-analyst"][0],
                       "reasonCode": "CASE_REVIEW_STARTED",
                       "expectedVersion": detail["concurrencyVersion"]})
    assert changed["caseStatus"] == "IN_REVIEW"
    detection_version = before["version"]
    assert detection_version and detection_version > 0
    accepted = request(ctx, analyst, "POST", "/cases/" + case_id + "/ai-reports", 202,
                       {"detectionResultVersion": detection_version},
                       {"Idempotency-Key": "i394-ai-" + uuid.uuid4().hex})
    request_id, execution_id = accepted["aiRequestId"], accepted["executionId"]
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        current = request(ctx, analyst, "GET", "/cases/" + case_id + "/ai-reports/current")
        latest = current.get("latestRequest") or {}
        if latest.get("aiRequestId") == request_id and latest.get("reportStatus") in (
                "COMPLETED", "FALLBACK_COMPLETED", "FAILED"):
            break
        time.sleep(2)
    else:
        raise AssertionError("AI report did not reach a terminal state")
    report = current["currentReport"]
    assert latest["reportStatus"] in ("COMPLETED", "FALLBACK_COMPLETED")
    assert report and report["caseId"] == case_id
    assert report["executionId"] == execution_id == latest["executionId"]
    assert snapshot(ctx, transaction_id) == before
    stored = json.loads(ctx.sql_scalar("""SELECT json_build_object(
        'requestId',q.ai_request_id,'executionId',e.execution_id,'reportId',r.report_id,
        'requestVersion',q.detection_result_version,
        'executionVersion',e.detection_result_version,
        'reportVersion',r.detection_result_version,
        'reportSource',r.report_source,'reportStatus',r.report_status)
        FROM ai_report_request q JOIN ai_report_execution e ON e.id=q.execution_id
        JOIN ai_report r ON r.execution_id=e.id WHERE q.ai_request_id='%s'""" % request_id))
    assert stored["requestId"] == request_id
    assert stored["requestVersion"] == stored["executionVersion"] == stored["reportVersion"] == detection_version
    assert stored["executionId"] == execution_id
    assert stored["reportId"] == report["reportId"]
    assert stored["reportSource"] == report["reportSource"]
    assert stored["reportStatus"] == report["reportStatus"]
    date = now().astimezone(dt.timezone(dt.timedelta(hours=9))).date()
    _, case_dashboard = dashboard_view(ctx, viewer, date)
    db_statuses = json.loads(ctx.sql_scalar("""SELECT coalesce(json_object_agg(case_status,n),'{}'::json)
        FROM (SELECT case_status,count(*) n FROM fraud_case
        WHERE (created_at AT TIME ZONE 'Asia/Seoul')::date='%s'
        GROUP BY case_status) x""" % date.isoformat()))
    assert {k: v for k, v in case_dashboard["currentStatuses"].items() if v} == db_statuses
    print(json.dumps({"step": "ai-report", "exitCode": 0,
                      "transactionId": transaction_id, "caseId": case_id,
                      "aiRequestId": request_id, "executionId": execution_id,
                      "reportId": report["reportId"], "status": report["reportStatus"],
                      "source": report["reportSource"],
                      "dashboardCurrentStatuses": case_dashboard["currentStatuses"]}), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("phase", choices=["publish", "verify", "report-only", "fault-only"])
    parser.add_argument("--case-id")
    parser.add_argument("--transaction-id")
    args = parser.parse_args()
    ctx = context(args.tag)
    if args.phase == "publish":
        publish(ctx)
    elif args.phase == "verify":
        verify(ctx)
    elif args.phase == "fault-only":
        faults(ctx, uuid.uuid4().hex[:12])
    else:
        if not args.case_id or not args.transaction_id:
            parser.error("report-only requires --case-id and --transaction-id")
        try:
            case_id = str(uuid.UUID(args.case_id, version=4))
            transaction_id = str(uuid.UUID(args.transaction_id, version=4))
        except ValueError:
            parser.error("report-only requires canonical UUID v4 IDs")
        if case_id != args.case_id or transaction_id != args.transaction_id:
            parser.error("report-only requires canonical UUID v4 IDs")
        report_only(ctx, case_id, transaction_id)


if __name__ == "__main__":
    main()
