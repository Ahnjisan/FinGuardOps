#!/usr/bin/env python3
"""Verify Rule v2 + ML adoption and a separate ML failure in the owned Run project.

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
    if (
        gate.sql_scalar(ctx, "select count(*) from rule_version where version_number=2")
        != "0"
    ):
        gate.fail("CRITICAL_V2_ALREADY_PUBLISHED")
    if (
        gate.sql_scalar(
            ctx,
            "select count(*) from rule_version where status='PUBLISHED' and version_number=1 and effective_from<=current_timestamp and effective_to is null",
        )
        != "4"
    ):
        gate.fail("CRITICAL_V1_BOUNDARY_INVALID")
    cutoff = _stamp(dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=90))
    ctx.execute(
        [
            "run",
            "--rm",
            "--no-deps",
            "--pull",
            "never",
            "-T",
            "-e",
            "SPRING_PROFILES_ACTIVE=local,rule-v2-local-publication",
            "-e",
            "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false",
            "-e",
            "FINGUARDOPS_KAFKA_ENABLED=false",
            "backend",
            "--spring.main.web-application-type=none",
            "--logging.level.org.hibernate.orm.connections.pooling=WARN",
            "--finguardops.rule-v2-local-publication.enabled=true",
            "--finguardops.rule-v2-local-publication.confirmation=PUBLISH_RULE_V2_LOCAL",
            "--finguardops.rule-v2-local-publication.effective-from=" + cutoff,
        ],
        timeout=240,
    )
    boundary = gate.sql_scalar(
        ctx,
        "select concat_ws('|',"
        "(select count(*) from rule_version where version_number=1 and status='PUBLISHED' and effective_to='"
        + cutoff
        + "'),"
        "(select count(*) from rule_version where version_number=2 and status='PUBLISHED' and effective_from='"
        + cutoff
        + "'))",
    )
    if boundary != "4|4":
        gate.fail("CRITICAL_VERSION_BOUNDARY_INVALID")
    return cutoff


def _create(ctx: gate.HostContext, cutoff: str) -> dict[str, object]:
    deadline = time.monotonic() + 120
    while dt.datetime.now(dt.timezone.utc) < dt.datetime.fromisoformat(
        cutoff.replace("Z", "+00:00")
    ):
        if time.monotonic() >= deadline:
            gate.fail("CRITICAL_ACTIVATION_TIMEOUT")
        time.sleep(1)
    if (
        gate.sql_scalar(
            ctx,
            "select count(*) from rule_version where version_number=2 and status='PUBLISHED' and effective_from<=current_timestamp and (effective_to is null or effective_to>current_timestamp)",
        )
        != "4"
    ):
        gate.fail("CRITICAL_ACTIVE_SET_INVALID")

    source = (ctx.repo / "infra" / "keycloak" / "critical_fixture_worker.py").read_text(
        encoding="utf-8"
    )
    secret_root = ctx.repo / "infra" / "keycloak" / ".local" / "secrets"
    secret_input = json.dumps(
        {
            "transaction": gate.read_secret(
                secret_root / "transaction-service-client-secret"
            ),
            "behavior": gate.read_secret(
                secret_root / "behavior-service-client-secret"
            ),
        },
        separators=(",", ":"),
    ).encode("ascii")
    before_external, before_rule = gate.dependency_hit_counts(ctx)
    before_ml = sum(
        '"POST /api/v1/ml-inference HTTP/1.1" 200' in line
        for line in gate.service_logs(ctx, "ai-service").splitlines()
    )
    output = ctx.execute(
        [
            "exec",
            "-T",
            "external-risk-mock",
            "python",
            "-c",
            source,
        ],
        input_bytes=secret_input,
        timeout=360,
    )
    try:
        accepted = json.loads(output.decode("ascii", "strict"))
    except (UnicodeError, ValueError):
        gate.fail("CRITICAL_WORKER_OUTPUT_INVALID")
    if (
        not isinstance(accepted, dict)
        or set(accepted)
        != {
            "transactionId",
            "caseId",
            "detectionResultId",
            "failedTransactionId",
            "riskScore",
            "riskLevel",
            "transactionStatus",
        }
        or not gate.is_canonical_uuid4(accepted.get("transactionId"))
        or not gate.is_canonical_uuid4(accepted.get("caseId"))
        or not gate.is_canonical_uuid4(accepted.get("detectionResultId"))
        or not gate.is_canonical_uuid4(accepted.get("failedTransactionId"))
        or accepted.get("riskScore") != 100
        or accepted.get("riskLevel") != "CRITICAL"
        or accepted.get("transactionStatus") != "HELD"
    ):
        gate.fail("CRITICAL_WORKER_OUTPUT_INVALID")
    transaction_id = accepted["transactionId"]
    external, rule = gate.dependency_hit_counts(ctx)
    ml = sum(
        '"POST /api/v1/ml-inference HTTP/1.1" 200' in line
        for line in gate.service_logs(ctx, "ai-service").splitlines()
    )
    if (external - before_external, rule - before_rule, ml - before_ml) != (2, 2, 1):
        gate.fail("CRITICAL_DEPENDENCY_DELTA_INVALID")
    stored = json.loads(
        gate.sql_scalar(
            ctx,
            "select json_build_object("
            "'id',d.detection_result_id,'status',f.processing_status,'response',f.risk_response_outcome,"
            "'rule',d.rule_risk_score,'ml',d.ml_contribution,'score',d.risk_score,"
            "'level',d.risk_level,'policy',d.scoring_policy_version,'feature',d.ml_feature_version,"
            "'model',d.model_version,'hash',d.model_sha256,'cutoff',d.evaluation_cutoff_at,"
            "'cutoffEqual',d.evaluation_cutoff_at=f.occurred_at,"
            "'rules',(select count(*) from detection_evidence e where e.detection_result_id=d.id and e.evidence_type='RULE'),"
            "'mlEvidence',(select count(*) from detection_evidence e where e.detection_result_id=d.id and e.evidence_type='ML'),"
            "'cases',(select count(*) from case_transaction c where c.financial_transaction_id=f.id),"
            "'audit',(select count(*) from audit_log a where a.transaction_id=f.transaction_id),"
            "'events',(select count(*) from behavior_event b where b.external_customer_ref=f.external_customer_ref and b.created_at<=f.occurred_at)) "
            "from financial_transaction f join detection_result d on d.id=f.adopted_detection_result_id "
            "where f.transaction_id='" + transaction_id + "'",
        )
    )
    if (
        stored.get("id") != accepted["detectionResultId"]
        or stored.get("status") != "HELD"
        or stored.get("response") != "HELD"
        or stored.get("rule") != 85
        or stored.get("ml") != 35
        or stored.get("score") != 100
        or stored.get("level") != "CRITICAL"
        or stored.get("policy") != "rule-ml-policy-v1"
        or stored.get("feature") != "fraud-feature-v1"
        or stored.get("model") != "fraud-logistic-v2"
        or stored.get("hash")
        != "42344d398008babdd6a0404c750b24f1f27f1260aeac65851195d81500a876af"
        or stored.get("rules") != 4
        or stored.get("mlEvidence") != 1
        or stored.get("cases") != 1
        or stored.get("audit") != 4
        or stored.get("cutoffEqual") is not True
        or stored.get("events") != 12
    ):
        gate.fail("CRITICAL_STORED_DETECTION_INVALID")
    failed_id = accepted["failedTransactionId"]
    failed = json.loads(
        gate.sql_scalar(
            ctx,
            "select json_build_object("
            "'status',f.processing_status,'adopted',f.adopted_detection_result_id,"
            "'response',f.risk_response_outcome,'analysis',d.analysis_status,"
            "'code',d.failure_code,'score',d.risk_score,"
            "'evidence',(select count(*) from detection_evidence e where e.detection_result_id=d.id),"
            "'cases',(select count(*) from case_transaction c where c.financial_transaction_id=f.id),"
            "'audit',(select count(*) from audit_log a where a.transaction_id=f.transaction_id)) "
            "from financial_transaction f join detection_result d on d.financial_transaction_id=f.id "
            "where f.transaction_id='" + failed_id + "'",
        )
    )
    if failed != {
        "status": "FAILED",
        "adopted": None,
        "response": None,
        "analysis": "FAILED",
        "code": "ML_EVENT_LIMIT_EXCEEDED",
        "score": None,
        "evidence": 0,
        "cases": 0,
        "audit": 0,
    }:
        gate.fail("CRITICAL_FAILED_DETECTION_INVALID")
    accepted.update(
        {
            "ruleScore": 85,
            "mlContribution": 35,
            "modelVersion": "fraud-logistic-v2",
            "modelSha256": stored["hash"],
            "evaluationCutoffAt": stored["cutoff"],
        }
    )
    return accepted


def main() -> int:
    try:
        project = gate.validate_run_fixture_project(
            os.environ.get(gate.COMPOSE_PROJECT_ENVIRONMENT)
        )
        contract = gate.load_owner_contract(dict(os.environ))
        ctx = gate.HostContext(
            Path(__file__).resolve().parents[2], project, 30, 720, contract
        )
        cutoff = _publish(ctx)
        result = _create(ctx, cutoff)
        evidence_dir = ctx.repo / "backend" / "build"
        if evidence_dir.is_symlink():
            gate.fail("CRITICAL_EVIDENCE_PATH_INVALID")
        evidence_dir.mkdir(exist_ok=True)
        evidence_path = evidence_dir / ("issue380-browser-" + contract.run_id + ".json")
        evidence = {
            "fixtureExitCode": 0,
            "commitSha": contract.commit_sha,
            "composeProject": project,
            **result,
        }
        with evidence_path.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(evidence, stream, sort_keys=True, separators=(",", ":"))
            stream.write("\n")
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
