#!/usr/bin/env python3
"""Create one owned SCN-001+002 v4 flow in the official Keycloak Run database."""

import datetime as dt
import json
import os
import sys
import time
import uuid
from pathlib import Path

import verify_e2e as gate


def stamp(value):
    return value.isoformat(timespec="seconds").replace("+00:00", "Z")


def publish(ctx):
    active = gate.sql_scalar(ctx, """SELECT version_number || '|' || count(*) FROM rule_version
        WHERE status='PUBLISHED' AND effective_from<=current_timestamp
          AND (effective_to IS NULL OR effective_to>current_timestamp)
        GROUP BY version_number ORDER BY version_number""")
    if active != "1|1\n3|4":
        gate.fail("SCN001002_V3_BOUNDARY_INVALID")
    cutoff = stamp(dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=90))
    ctx.execute(["run", "--rm", "--no-deps", "--pull", "never", "-T",
                 "-e", "SPRING_PROFILES_ACTIVE=local,rule-v4-local-publication",
                 "-e", "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false",
                 "-e", "FINGUARDOPS_KAFKA_ENABLED=false", "backend",
                 "--spring.main.web-application-type=none",
                 "--finguardops.rule-v4-local-publication.enabled=true",
                 "--finguardops.rule-v4-local-publication.confirmation=PUBLISH_RULE_V4_LOCAL",
                 "--finguardops.rule-v4-local-publication.effective-from=" + cutoff], timeout=240)
    deadline = time.monotonic() + 120
    while dt.datetime.now(dt.timezone.utc) <= dt.datetime.fromisoformat(cutoff.replace("Z", "+00:00")):
        if time.monotonic() >= deadline:
            gate.fail("SCN001002_ACTIVATION_TIMEOUT")
        time.sleep(1)
    active = gate.sql_scalar(ctx, """SELECT version_number || '|' || count(*) FROM rule_version
        WHERE status='PUBLISHED' AND effective_from<=current_timestamp
          AND (effective_to IS NULL OR effective_to>current_timestamp)
        GROUP BY version_number ORDER BY version_number""")
    if active != "1|6\n3|1\n4|3":
        gate.fail("SCN001002_ACTIVE_SET_INVALID")


def seed_history(ctx, suffix):
    customer = "issue384-browser-customer-" + suffix
    sender = "issue384-browser-sender-" + suffix
    old_device = "issue384-browser-old-device-" + suffix
    now = dt.datetime.now(dt.timezone.utc)
    for index in range(10):
        transaction_id = str(uuid.uuid4())
        occurred = stamp(now - dt.timedelta(days=3 + index % 3, seconds=index))
        recipient = "issue384-browser-prior-" + suffix + str(index)
        gate.sql_scalar(ctx, """INSERT INTO financial_transaction
            (transaction_id,transaction_type,amount,currency_code,occurred_at,
             external_customer_ref,sender_account_ref,recipient_account_ref,
             channel,device_ref,processing_status)
            VALUES ('%s','ACCOUNT_TRANSFER',100000,'KRW','%s',
                    '%s','%s','%s','MOBILE_BANKING','%s','RECEIVED');
            UPDATE financial_transaction SET processing_status='APPROVED',version=version+1
              WHERE transaction_id='%s';""" % (
                  transaction_id, occurred, customer, sender, recipient, old_device,
                  transaction_id))
        approved = gate.sql_scalar(ctx, """SELECT (approved_at IS NOT NULL
            AND approved_at>=created_at)::text FROM financial_transaction
            WHERE transaction_id='%s'""" % transaction_id)
        if approved != "true":
            gate.fail("SCN001002_HISTORY_APPROVAL_INVALID")
    return customer, sender


def create(ctx, suffix, customer, sender):
    source = (ctx.repo / "infra" / "keycloak" / "scn001002_fixture_worker.py").read_text(
        encoding="utf-8")
    secret_root = ctx.repo / "infra" / "keycloak" / ".local" / "secrets"
    secret_input = json.dumps({
        "transaction": gate.read_secret(secret_root / "transaction-service-client-secret"),
        "behavior": gate.read_secret(secret_root / "behavior-service-client-secret"),
        "suffix": suffix,
    }, separators=(",", ":")).encode("ascii")
    output = ctx.execute(["exec", "-T", "external-risk-mock", "python", "-c", source],
                         input_bytes=secret_input, timeout=180)
    try:
        result = json.loads(output.decode("ascii", "strict"))
    except (UnicodeError, ValueError):
        gate.fail("SCN001002_WORKER_OUTPUT_INVALID")
    required = {"transactionId", "caseId", "detectionResultId", "transactionStatus"}
    if (not isinstance(result, dict) or set(result) != required or
            any(not gate.is_canonical_uuid4(result.get(key)) for key in
                ("transactionId", "caseId", "detectionResultId")) or
            result["transactionStatus"] != "HELD"):
        gate.fail("SCN001002_WORKER_IDENTITY_INVALID")
    stored = json.loads(gate.sql_scalar(ctx, """SELECT json_build_object(
        'id',d.detection_result_id,'policy',d.scoring_policy_version,
        'rule',d.rule_risk_score,'ml',d.ml_contribution,'score',d.risk_score,
        'status',t.processing_status,
        'cases',(SELECT count(*) FROM case_transaction c WHERE c.financial_transaction_id=t.id),
        'audit',(SELECT count(*) FROM audit_log a WHERE a.transaction_id=t.transaction_id),
        'caseAudit',(SELECT count(*) FROM audit_log a WHERE a.transaction_id=t.transaction_id
                     AND a.case_id='%s' AND a.target_id='%s'
                     AND a.action IN ('CASE_CREATED','CASE_TRANSACTION_LINKED')),
        'rules',(SELECT count(*) FROM detection_evidence e WHERE e.detection_result_id=d.id
                 AND e.evidence_type='RULE'),
        'r009Raw',(SELECT e.score_contribution FROM detection_evidence e
                   WHERE e.detection_result_id=d.id AND e.rule_code='MATERIAL_LIMIT_UPLIFT'),
        'r009Applied',(SELECT e.applied_contribution FROM detection_evidence e
                       WHERE e.detection_result_id=d.id AND e.rule_code='MATERIAL_LIMIT_UPLIFT'),
        'security',(SELECT jsonb_build_object('raw',g.value->'rawScore',
                       'cap',g.value->'cap','applied',g.value->'appliedScore')
                    FROM jsonb_array_elements(d.score_group_summary) AS g(value)
                    WHERE g.value->>'groupId'='security'))
        FROM financial_transaction t JOIN detection_result d ON d.id=t.adopted_detection_result_id
        WHERE t.transaction_id='""" % (result["caseId"], result["caseId"]) + result["transactionId"] + "'"))
    if (stored["id"] != result["detectionResultId"] or
            stored["policy"] != "rule-ml-policy-v3" or
            stored["rule"] != 90 or not isinstance(stored["ml"], int) or
            stored["score"] != 90 + stored["ml"] or stored["status"] != "HELD" or
            stored["cases"] != 1 or stored["audit"] < 4 or stored["caseAudit"] != 2 or
            stored["rules"] != 5 or stored["r009Raw"] != 10 or
            stored["r009Applied"] != 0 or
            stored["security"] != {"raw": 80, "cap": 60, "applied": 60}):
        gate.fail("SCN001002_STORED_RESULT_INVALID")
    if gate.sql_scalar(ctx, """SELECT count(*) FROM financial_transaction
        WHERE external_customer_ref='%s' AND sender_account_ref='%s'
          AND approved_at IS NOT NULL AND approved_at<=
              (SELECT occurred_at FROM financial_transaction WHERE transaction_id='%s')""" % (
                  customer, sender, result["transactionId"])) != "10":
        gate.fail("SCN001002_HISTORY_CUTOFF_INVALID")
    result.update({"riskScore": stored["score"], "ruleScore": stored["rule"],
                   "mlContribution": stored["ml"]})
    return result


def main():
    try:
        project = gate.validate_run_fixture_project(os.environ.get(gate.COMPOSE_PROJECT_ENVIRONMENT))
        contract = gate.load_owner_contract(dict(os.environ))
        ctx = gate.HostContext(Path(__file__).resolve().parents[2], project, 30, 400, contract)
        publish(ctx)
        suffix = uuid.uuid4().hex[:12]
        customer, sender = seed_history(ctx, suffix)
        result = create(ctx, suffix, customer, sender)
        evidence_dir = ctx.repo / "backend" / "build"
        if evidence_dir.is_symlink():
            gate.fail("SCN001002_EVIDENCE_PATH_INVALID")
        evidence_dir.mkdir(exist_ok=True)
        path = evidence_dir / ("issue384-browser-" + contract.run_id + ".json")
        with path.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump({"fixtureExitCode": 0, "commitSha": contract.commit_sha,
                       "composeProject": project, **result}, stream,
                      sort_keys=True, separators=(",", ":"))
            stream.write("\n")
        print(json.dumps(result, separators=(",", ":")))
        return 0
    except gate.VerificationError as error:
        print("verification failed: " + str(error), file=sys.stderr)
    except Exception:
        print("verification failed: SCN001002_FIXTURE_UNEXPECTED", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
