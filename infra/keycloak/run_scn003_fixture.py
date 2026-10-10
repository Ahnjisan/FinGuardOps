#!/usr/bin/env python3
"""Publish SCN-003 after the Gate's v2 case, then verify one owned transaction."""

import datetime as dt
import json
import os
import sys
import time
from pathlib import Path

import verify_e2e as gate


def publish(ctx):
    if gate.sql_scalar(ctx, """
        select count(*) from rule_version where version_number=2 and status='PUBLISHED'
          and effective_from<=current_timestamp and effective_to is null
    """) != "4" or gate.sql_scalar(ctx,
            "select count(*) from fraud_rule where rule_code='EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT'") != "0":
        gate.fail("SCN003_V2_BOUNDARY_INVALID")
    cutoff = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=90)).replace(
        microsecond=0).isoformat().replace("+00:00", "Z")
    ctx.execute(["run", "--rm", "--no-deps", "--pull", "never", "-T",
                 "-e", "SPRING_PROFILES_ACTIVE=local,rule-v3-local-publication",
                 "-e", "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false",
                 "-e", "FINGUARDOPS_KAFKA_ENABLED=false", "backend",
                 "--spring.main.web-application-type=none",
                 "--finguardops.rule-v3-local-publication.enabled=true",
                 "--finguardops.rule-v3-local-publication.confirmation=PUBLISH_RULE_V3_LOCAL",
                 "--finguardops.rule-v3-local-publication.effective-from=" + cutoff], timeout=240)
    if gate.sql_scalar(ctx, """
        select concat_ws('|',
          (select count(*) from rule_version where version_number=2 and effective_to='"""
          + cutoff + "'),(select count(*) from rule_version where version_number=3 and effective_from='"
          + cutoff + "'),(select count(*) from rule_version where version_number=1 and effective_from='"
          + cutoff + "' and fraud_rule_id=(select id from fraud_rule where rule_code='EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT')))"
    ) != "4|4|1":
        gate.fail("SCN003_PUBLICATION_INVALID")
    deadline = time.monotonic() + 120
    while dt.datetime.now(dt.timezone.utc) < dt.datetime.fromisoformat(cutoff.replace("Z", "+00:00")):
        if time.monotonic() >= deadline:
            gate.fail("SCN003_ACTIVATION_TIMEOUT")
        time.sleep(1)
    if gate.sql_scalar(ctx, """
        select count(*) from rule_version where status='PUBLISHED'
          and effective_from<=current_timestamp and (effective_to is null or effective_to>current_timestamp)
    """) != "5":
        gate.fail("SCN003_ACTIVE_SET_INVALID")


def create(ctx):
    source = (ctx.repo / "infra" / "keycloak" / "scn003_fixture_worker.py").read_text(
        encoding="utf-8")
    secret_root = ctx.repo / "infra" / "keycloak" / ".local" / "secrets"
    secret_input = json.dumps({
        "transaction": gate.read_secret(secret_root / "transaction-service-client-secret"),
        "behavior": gate.read_secret(secret_root / "behavior-service-client-secret"),
    }, separators=(",", ":")).encode("ascii")
    before_external, before_rule = gate.dependency_hit_counts(ctx)
    output = ctx.execute(["exec", "-T", "external-risk-mock", "python", "-c", source],
                         input_bytes=secret_input, timeout=120)
    try:
        result = json.loads(output.decode("ascii", "strict"))
    except (UnicodeError, ValueError):
        gate.fail("SCN003_WORKER_OUTPUT_INVALID")
    if (not isinstance(result, dict) or set(result) != {
        "transactionId", "caseId", "detectionResultId", "evaluationCutoffAt",
        "riskScore", "riskLevel", "transactionStatus",
    } or any(not gate.is_canonical_uuid4(result.get(key)) for key in
             ("transactionId", "caseId", "detectionResultId")) or
            result["riskScore"] != 50 or result["riskLevel"] != "HIGH" or
            result["transactionStatus"] != "ADDITIONAL_AUTH_REQUIRED"):
        gate.fail("SCN003_WORKER_IDENTITY_INVALID")
    after_external, after_rule = gate.dependency_hit_counts(ctx)
    if (after_external - before_external, after_rule - before_rule) != (1, 1):
        gate.fail("SCN003_DEPENDENCY_DELTA_INVALID")
    stored = json.loads(gate.sql_scalar(ctx,
        "select json_build_object('id',d.detection_result_id,'policy',d.scoring_policy_version,"
        "'rule',d.rule_risk_score,'ml',d.ml_contribution,'score',d.risk_score,'level',d.risk_level,"
        "'status',t.processing_status,'cases',(select count(*) from case_transaction c where c.financial_transaction_id=t.id),"
        "'audit',(select count(*) from audit_log a where a.transaction_id=t.transaction_id),"
        "'r005',(select count(*) from detection_evidence e where e.detection_result_id=d.id and e.rule_code='EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT'),"
        "'riskEvidence',(select count(*) from detection_evidence e where e.detection_result_id=d.id and e.evidence_type='EXTERNAL_RISK')) "
        "from financial_transaction t join detection_result d on d.id=t.adopted_detection_result_id "
        "where t.transaction_id='" + result["transactionId"] + "'"))
    if stored != {"id": result["detectionResultId"], "policy": "rule-ml-policy-v2",
                  "rule": 50, "ml": 0, "score": 50, "level": "HIGH",
                  "status": "ADDITIONAL_AUTH_REQUIRED", "cases": 1,
                  "audit": 4, "r005": 1, "riskEvidence": 1}:
        gate.fail("SCN003_STORED_RESULT_INVALID")
    return result


def main():
    try:
        project = gate.validate_run_fixture_project(os.environ.get(gate.COMPOSE_PROJECT_ENVIRONMENT))
        contract = gate.load_owner_contract(dict(os.environ))
        ctx = gate.HostContext(Path(__file__).resolve().parents[2], project, 30, 400, contract)
        publish(ctx)
        result = create(ctx)
        evidence_dir = ctx.repo / "backend" / "build"
        if evidence_dir.is_symlink():
            gate.fail("SCN003_EVIDENCE_PATH_INVALID")
        evidence_dir.mkdir(exist_ok=True)
        path = evidence_dir / ("issue382-browser-" + contract.run_id + ".json")
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
        print("verification failed: SCN003_FIXTURE_UNEXPECTED", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
