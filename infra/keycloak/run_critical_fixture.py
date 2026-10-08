#!/usr/bin/env python3
"""Create one Rule v2 case inside the official, receipt-owned Keycloak Run project.

The existing v1 Run fixture finishes before this command is invoked. Only safe
identifiers and the observed business outcome are written to stdout.
"""

import datetime as dt
import json
import os
import sys
import time
from pathlib import Path

import verify_e2e as gate


def _stamp(value: dt.datetime) -> str:
    return value.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _publish(ctx: gate.HostContext) -> str:
    if gate.sql_scalar(ctx, "select count(*) from rule_version where version_number=2") != "0":
        gate.fail("CRITICAL_V2_ALREADY_PUBLISHED")
    if gate.sql_scalar(ctx, "select count(*) from rule_version where status='PUBLISHED' and version_number=1 and effective_from<=current_timestamp and effective_to is null") != "4":
        gate.fail("CRITICAL_V1_BOUNDARY_INVALID")
    cutoff = _stamp(dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=90))
    ctx.execute([
        "run", "--rm", "--no-deps", "--pull", "never", "-T",
        "-e", "SPRING_PROFILES_ACTIVE=local,rule-v2-local-publication",
        "-e", "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false",
        "-e", "FINGUARDOPS_KAFKA_ENABLED=false",
        "backend", "--spring.main.web-application-type=none",
        "--logging.level.org.hibernate.orm.connections.pooling=WARN",
        "--finguardops.rule-v2-local-publication.enabled=true",
        "--finguardops.rule-v2-local-publication.confirmation=PUBLISH_RULE_V2_LOCAL",
        "--finguardops.rule-v2-local-publication.effective-from=" + cutoff,
    ], timeout=240)
    boundary = gate.sql_scalar(ctx, "select concat_ws('|',"
        "(select count(*) from rule_version where version_number=1 and status='PUBLISHED' and effective_to='" + cutoff + "'),"
        "(select count(*) from rule_version where version_number=2 and status='PUBLISHED' and effective_from='" + cutoff + "'))")
    if boundary != "4|4":
        gate.fail("CRITICAL_VERSION_BOUNDARY_INVALID")
    return cutoff


def _create(ctx: gate.HostContext, cutoff: str) -> dict[str, object]:
    deadline = time.monotonic() + 120
    while dt.datetime.now(dt.timezone.utc) < dt.datetime.fromisoformat(cutoff.replace("Z", "+00:00")):
        if time.monotonic() >= deadline:
            gate.fail("CRITICAL_ACTIVATION_TIMEOUT")
        time.sleep(1)
    if gate.sql_scalar(ctx, "select count(*) from rule_version where version_number=2 and status='PUBLISHED' and effective_from<=current_timestamp and (effective_to is null or effective_to>current_timestamp)") != "4":
        gate.fail("CRITICAL_ACTIVE_SET_INVALID")

    source = (ctx.repo / "infra" / "keycloak" / "critical_fixture_worker.py").read_text(encoding="utf-8")
    secret_root = ctx.repo / "infra" / "keycloak" / ".local" / "secrets"
    secret_input = json.dumps({
        "transaction": gate.read_secret(secret_root / "transaction-service-client-secret"),
        "behavior": gate.read_secret(secret_root / "behavior-service-client-secret"),
    }, separators=(",", ":")).encode("ascii")
    output = ctx.execute([
        "exec", "-T", "external-risk-mock", "python", "-c", source,
    ], input_bytes=secret_input, timeout=120)
    try:
        accepted = json.loads(output.decode("ascii", "strict"))
    except (UnicodeError, ValueError):
        gate.fail("CRITICAL_WORKER_OUTPUT_INVALID")
    if (not isinstance(accepted, dict)
            or set(accepted) != {"transactionId", "caseId", "riskScore", "riskLevel", "transactionStatus"}
            or not gate.is_canonical_uuid4(accepted.get("transactionId"))
            or not gate.is_canonical_uuid4(accepted.get("caseId"))
            or accepted.get("riskScore") != 85
            or accepted.get("riskLevel") != "CRITICAL"
            or accepted.get("transactionStatus") != "HELD"):
        gate.fail("CRITICAL_WORKER_OUTPUT_INVALID")
    transaction_id = accepted["transactionId"]
    stored = gate.sql_scalar(ctx, "select concat_ws('|',d.risk_score,d.risk_level,"
        "d.scoring_policy_version,d.feature_version,count(e.id),"
        "count(distinct e.reason_code),count(distinct e.rule_version_id),"
        "min(rv.version_number),max(rv.version_number)) from financial_transaction f "
        "join detection_result d on d.id=f.adopted_detection_result_id "
        "left join detection_evidence e on e.detection_result_id=d.id "
        "left join rule_version rv on rv.id=e.rule_version_id "
        "where f.transaction_id='" + transaction_id + "' group by d.id")
    if stored != "85|CRITICAL|scoring-policy-v2|rule-v2|4|4|4|2|2":
        gate.fail("CRITICAL_STORED_DETECTION_INVALID")
    return accepted


def main() -> int:
    try:
        project = gate.validate_run_fixture_project(os.environ.get(gate.COMPOSE_PROJECT_ENVIRONMENT))
        contract = gate.load_owner_contract(dict(os.environ))
        ctx = gate.HostContext(Path(__file__).resolve().parents[2], project, 30, 420, contract)
        cutoff = _publish(ctx)
        result = _create(ctx, cutoff)
        print(json.dumps(result, separators=(",", ":")))
        return 0
    except gate.VerificationError as error:
        print("verification failed: " + str(error), file=sys.stderr)
        return 1
    except Exception:
        print("verification failed: CRITICAL_FIXTURE_UNEXPECTED", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
